package dev.madeinabyss.forge.cube;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.VersionedCubeStore;
import dev.madeinabyss.kernel.cube.SavedCubeBlockEntity;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.tick.SavedCubeTick;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/** Stable named states and pending ticks in one atomic cube frame; no multi-cube transaction guarantee. */
public final class StableCubeStore implements VersionedCubeStore {
    private static final int MAGIC = 0x4d495350; // MISP: independently versioned stable palette format.
    private static final int VERSION = 3;
    public static final int MAX_PENDING_TICKS = 512;
    public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private final Path root;
    private final String generatorId;
    private final int generatorVersion;
    private final ConcurrentHashMap<CubeKey, AtomicLong> versions = new ConcurrentHashMap<>();
    public StableCubeStore(Path worldRoot, String generatorId, int generatorVersion) {
        root = Objects.requireNonNull(worldRoot).toAbsolutePath().normalize().resolve("stable-cubes-v1");
        var parsed = ResourceLocation.tryParse(Objects.requireNonNull(generatorId));
        if (parsed == null || !parsed.toString().equals(generatorId) || generatorId.length() > 256)
            throw new IllegalArgumentException("generator identity must be a canonical namespaced resource location");
        if (generatorVersion < 0) throw new IllegalArgumentException("generator version must be nonnegative");
        this.generatorId = generatorId;
        this.generatorVersion = generatorVersion;
    }
    public Optional<CubeSnapshot> read(CubeKey key) throws IOException {
        Objects.requireNonNull(key);
        byte[] bytes;
        try (var source = Files.newInputStream(path(key))) {
            bytes = source.readNBytes(MAX_FILE_BYTES + 1);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
        if (bytes.length < 36 || bytes.length > MAX_FILE_BYTES) throw new IOException("invalid stable cube length");
        int payloadSize = bytes.length - Integer.BYTES;
        var crc = new CRC32(); crc.update(bytes, 0, payloadSize);
        if (ByteBuffer.wrap(bytes).getInt(payloadSize) != (int) crc.getValue()) throw new IOException("stable cube checksum mismatch: " + key);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes, 0, payloadSize))) {
            if (input.readInt() != MAGIC) throw new IOException("unrecognized stable cube format");
            int version = input.readInt();
            if (version < 1 || version > VERSION) throw new IOException("unsupported stable cube version");
            var recorded = new CubeKey(input.readInt(), input.readInt(), input.readInt());
            if (!recorded.equals(key)) throw new IOException("stable cube coordinate mismatch: " + recorded + " vs " + key);
            String recordedGenerator = StableBlockStatePalette.readText(input, 256);
            int recordedVersion = input.readInt();
            if (!generatorId.equals(recordedGenerator) || generatorVersion != recordedVersion)
                throw new IOException("stable cube generator provenance mismatch: " + recordedGenerator + "@" + recordedVersion
                        + " vs " + generatorId + "@" + generatorVersion);
            int count = input.readInt();
            if (count < 1 || count > CubeSnapshot.BLOCK_COUNT) throw new IOException("invalid stable palette count: " + count);
            int[] palette = new int[count];
            var unique = new HashSet<BlockState>();
            for (int index = 0; index < count; index++) {
                BlockState state = StableBlockStatePalette.readState(input);
                if (!unique.add(state)) throw new IOException("duplicate stable palette state");
                palette[index] = Block.getId(state);
            }
            if (input.readInt() != CubeSnapshot.BLOCK_COUNT) throw new IOException("invalid stable cube cell count");
            int[] states = new int[CubeSnapshot.BLOCK_COUNT];
            for (int index = 0; index < states.length; index++) {
                int paletteIndex = input.readUnsignedShort();
                if (paletteIndex >= count) throw new IOException("invalid stable palette index: " + paletteIndex);
                states[index] = palette[paletteIndex];
            }
            List<SavedCubeTick> ticks = version == 1 ? List.of() : readTicks(input, recorded);
            List<SavedCubeBlockEntity> blockEntities = version < 3 ? List.of() : readBlockEntities(input, recorded, states);
            if (input.available() != 0) throw new IOException("trailing stable cube data");
            return Optional.of(new CubeSnapshot(recorded, states, ticks, blockEntities));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid stable cube tuple: " + key, invalid);
        }
    }
    /** Odd while this process replaces the cube file, even otherwise; background readers validate against it. */
    @Override public long version(CubeKey key) {
        var version = versions.get(Objects.requireNonNull(key));
        return version == null ? 0 : version.get();
    }
    // Also used by AsyncCubeSaver's conditional write. Direct acknowledged gameplay writes
    // and background snapshots must share this monitor, so an older queued save cannot win.
    public synchronized void write(CubeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot);
        var version = versions.computeIfAbsent(snapshot.key(), ignored -> new AtomicLong());
        version.incrementAndGet();
        try { writeFrame(snapshot); } finally { version.incrementAndGet(); }
    }
    private void writeFrame(CubeSnapshot snapshot) throws IOException {
        // Reject corruption or incompatible provenance before replacing a visited cube. No implicit regeneration/migration.
        read(snapshot.key());
        List<SavedCubeTick> ticks = snapshot.ticks();
        validateTicks(snapshot.key(), ticks);
        var palette = new LinkedHashMap<BlockState, Integer>();
        int[] ids = snapshot.states();
        validateBlockEntities(snapshot.key(), ids, snapshot.blockEntities());
        int[] indices = new int[ids.length];
        for (int index = 0; index < ids.length; index++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(ids[index]);
            if (state == null || Block.getId(state) != ids[index]) throw new IOException("unknown native block state ID: " + ids[index]);
            indices[index] = palette.computeIfAbsent(state, ignored -> palette.size());
        }
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(new BoundedOutput(bytes))) {
            output.writeInt(MAGIC); output.writeInt(VERSION);
            output.writeInt(snapshot.key().x()); output.writeInt(snapshot.key().y()); output.writeInt(snapshot.key().z());
            StableBlockStatePalette.writeText(output, generatorId, 256); output.writeInt(generatorVersion);
            output.writeInt(palette.size());
            for (BlockState state : palette.keySet()) StableBlockStatePalette.writeState(output, state);
            output.writeInt(CubeSnapshot.BLOCK_COUNT);
            for (int index : indices) output.writeShort(index);
            writeTicks(output, ticks);
            writeBlockEntities(output, snapshot.blockEntities());
        }
        var crc = new CRC32(); byte[] payload = bytes.toByteArray(); crc.update(payload);
        byte[] frame = ByteBuffer.allocate(payload.length + Integer.BYTES).put(payload).putInt((int) crc.getValue()).array();
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, "cube-", ".pending");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(frame);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Unsupported atomic replacement is an error. PersistentCubeSpace retains the resident cube on any write failure.
            Files.move(temporary, path(snapshot.key()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
    public Path path(CubeKey key) {
        Objects.requireNonNull(key);
        return root.resolve("c." + key.x() + "." + key.y() + "." + key.z() + ".miacube");
    }

    /** V2 suffix: int count; byte kind; bounded UTF8 type; int XYZ; long delay; int priority; long order. */
    private static List<SavedCubeTick> readTicks(DataInputStream input, CubeKey key) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_PENDING_TICKS) throw new IOException("invalid stable cube pending tick count");
        var ticks = new ArrayList<SavedCubeTick>(count);
        for (int index = 0; index < count; index++) {
            int kind = input.readUnsignedByte();
            if (kind > 1) throw new IOException("unknown stable cube tick kind");
            String type = StableBlockStatePalette.readText(input, 256);
            var position = new BlockCoordinate(input.readInt(), input.readInt(), input.readInt());
            long delay = input.readLong();
            int priority = input.readInt();
            long order = input.readLong();
            ticks.add(new SavedCubeTick(kind == 0 ? SavedCubeTick.Kind.BLOCK : SavedCubeTick.Kind.FLUID,
                    type, position, delay, priority, order));
        }
        validateTicks(key, ticks);
        return List.copyOf(ticks);
    }

    private static void writeTicks(DataOutputStream output, List<SavedCubeTick> ticks) throws IOException {
        output.writeInt(ticks.size());
        for (SavedCubeTick tick : ticks) {
            output.writeByte(tick.kind() == SavedCubeTick.Kind.BLOCK ? 0 : 1);
            StableBlockStatePalette.writeText(output, tick.type(), 256);
            output.writeInt(tick.position().x()); output.writeInt(tick.position().y()); output.writeInt(tick.position().z());
            output.writeLong(tick.delay()); output.writeInt(tick.priority()); output.writeLong(tick.order());
        }
    }

    private static void validateTicks(CubeKey key, List<SavedCubeTick> ticks) throws IOException {
        if (ticks.size() > MAX_PENDING_TICKS) throw new IOException("stable cube pending tick budget exceeded");
        var unique = new HashSet<TickIdentity>();
        for (SavedCubeTick tick : ticks) {
            if (tick == null || tick.kind() == null || tick.position() == null || tick.type() == null)
                throw new IOException("incomplete stable cube tick");
            var id = ResourceLocation.tryParse(tick.type());
            if (id == null || !id.toString().equals(tick.type()) || tick.type().length() > 256)
                throw new IOException("noncanonical stable cube tick type");
            if (tick.kind() == SavedCubeTick.Kind.BLOCK) {
                if (!BuiltInRegistries.BLOCK.containsKey(id)) throw new IOException("unknown stable scheduled block: " + id);
            } else {
                if (!BuiltInRegistries.FLUID.containsKey(id) || BuiltInRegistries.FLUID.get(id) == Fluids.EMPTY)
                    throw new IOException("unknown or empty stable scheduled fluid: " + id);
            }
            if (!CubeKey.fromBlock(tick.position()).equals(key)) throw new IOException("stable tick outside owning cube");
            if (tick.priority() < -3 || tick.priority() > 3 || TickPriority.byValue(tick.priority()).getValue() != tick.priority())
                throw new IOException("invalid stable tick priority");
            if (!unique.add(new TickIdentity(tick.kind(), tick.type(), tick.position())))
                throw new IOException("duplicate stable pending tick identity");
        }
    }

    private record TickIdentity(SavedCubeTick.Kind kind, String type, BlockCoordinate position) { }

    /** V3 suffix: count; bounded UTF8 type; signed int XYZ; int payload length; native uncompressed NBT. */
    private static List<SavedCubeBlockEntity> readBlockEntities(DataInputStream input, CubeKey key, int[] states) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > SavedCubeBlockEntity.MAX_PER_CUBE) throw new IOException("invalid stable block entity count");
        var records = new ArrayList<SavedCubeBlockEntity>(count);
        for (int index = 0; index < count; index++) {
            String type = StableBlockStatePalette.readText(input, 256);
            var position = new BlockCoordinate(input.readInt(), input.readInt(), input.readInt());
            int size = input.readInt();
            if (size < 1 || size > SavedCubeBlockEntity.MAX_PAYLOAD_BYTES || size > input.available())
                throw new IOException("invalid stable block entity payload length");
            byte[] data = new byte[size]; input.readFully(data);
            records.add(new SavedCubeBlockEntity(position, type, data));
        }
        validateBlockEntities(key, states, records);
        return List.copyOf(records);
    }

    private static void writeBlockEntities(DataOutputStream output, List<SavedCubeBlockEntity> records) throws IOException {
        output.writeInt(records.size());
        for (var record : records) {
            StableBlockStatePalette.writeText(output, record.type(), 256);
            output.writeInt(record.position().x()); output.writeInt(record.position().y()); output.writeInt(record.position().z());
            byte[] data = record.data(); output.writeInt(data.length); output.write(data);
        }
    }

    private static void validateBlockEntities(CubeKey key, int[] states, List<SavedCubeBlockEntity> records) throws IOException {
        if (records.size() > SavedCubeBlockEntity.MAX_PER_CUBE) throw new IOException("stable block entity budget exceeded");
        var positions = new HashSet<BlockCoordinate>();
        for (var record : records) {
            if (!key.equals(CubeKey.fromBlock(record.position()))) throw new IOException("stable block entity outside owning cube");
            if (!positions.add(record.position())) throw new IOException("duplicate stable block entity position");
            int index = (Math.floorMod(record.position().y(),16)<<8) | (Math.floorMod(record.position().z(),16)<<4) | Math.floorMod(record.position().x(),16);
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(states[index]);
            if (state == null || Block.getId(state) != states[index]) throw new IOException("unknown native state for block entity");
            ChestBlockEntityCodec.validateAndRead(record, state);
        }
        int chestCount = 0;
        for (int id : states) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null || Block.getId(state) != id) throw new IOException("unknown native block state ID: " + id);
            if (state.hasBlockEntity()) {
                if (!state.is(Blocks.CHEST)) throw new IOException("unsupported stable block entity state: " + state);
                chestCount++;
            }
        }
        if (chestCount != positions.size()) throw new IOException("stable chest state lacks full block entity metadata");
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream target;
        private int count;
        private BoundedOutput(ByteArrayOutputStream target) { this.target = target; }
        private void reserve(int size) throws IOException {
            if (size < 0 || size > MAX_FILE_BYTES - Integer.BYTES - count) throw new IOException("stable cube exceeds file budget");
            count += size;
        }
        @Override public void write(int value) throws IOException { reserve(1); target.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { reserve(length); target.write(bytes, offset, length); }
    }
}
