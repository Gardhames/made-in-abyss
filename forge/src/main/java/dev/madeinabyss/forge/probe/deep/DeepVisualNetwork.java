package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.DirectionalVisualWindow;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Optional visual-only native sections; packet bound is not a JVM memory bound. */
public final class DeepVisualNetwork {
    public static final int MAX_CUBES = 243;
    // Vanilla ClientboundCustomPayloadPacket allows 1 MiB; SimpleChannel adds one discriminator byte.
    public static final int MAX_PACKET_BYTES = 1024 * 1024 - 1;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MadeInAbyssProbe.MOD_ID, "deep_visual"), () -> "3",
            NetworkRegistry.acceptMissingOr("3"), NetworkRegistry.acceptMissingOr("3"));
    private static Consumer<Window> receiver = packet -> {};
    private static boolean registered;

    private DeepVisualNetwork() {}

    public static void register() {
        if (registered) return;
        registered = true;
        CHANNEL.registerMessage(0, Window.class, DeepVisualNetwork::encode, DeepVisualNetwork::decode,
                (packet, context) -> {
                    var work = context.get();
                    work.enqueueWork(() -> receiver.accept(packet));
                    work.setPacketHandled(true);
                }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Install once at client bootstrap, before any server window can arrive. */
    public static void receiver(Consumer<Window> windows) { receiver = Objects.requireNonNull(windows); }

    public static Set<CubeKey> windowKeys(CubeKey center) {
        return DirectionalVisualWindow.keys(new BlockCoordinate(Math.multiplyExact(center.x(), 16),
                Math.multiplyExact(center.y(), 16), Math.multiplyExact(center.z(), 16)));
    }
    public static Set<CubeKey> windowKeys(CubeKey center, int minimumCubeY) {
        if (minimumCubeY < Integer.MIN_VALUE / 16 || minimumCubeY > center.y())
            throw new IllegalArgumentException("Invalid known visual lower bound");
        var known = new java.util.HashSet<>(windowKeys(center));
        known.removeIf(key -> key.y() < minimumCubeY);
        return Set.copyOf(known);
    }

    /** Encode every incoming snapshot before publishing server scene metadata. */
    public static List<byte[]> frames(ServerPlayer player, List<CubeSnapshot> snapshots) {
        if (snapshots.size() > MAX_CUBES) throw new IllegalArgumentException("Too many visual cubes");
        var biomes = player.level().registryAccess().registryOrThrow(Registries.BIOME);
        List<byte[]> frames = new ArrayList<>();
        int bytes = 0;
        for (CubeSnapshot snapshot : snapshots) {
            int[] states = snapshot.states();
            var section = new LevelChunkSection(biomes);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var state = Block.BLOCK_STATE_REGISTRY.byId(states[y << 8 | z << 4 | x]);
                if (state == null) throw new IllegalArgumentException("Unknown visual state ID");
                section.setBlockState(x, y, z, state);
            }
            var buffer = new FriendlyByteBuf(Unpooled.buffer(4096, CubeSectionPacketCodec.MAX_SECTION_BYTES + 24));
            try {
                CubeSectionPacketCodec.encode(buffer, snapshot.key(), section);
                byte[] frame = new byte[buffer.readableBytes()];
                buffer.readBytes(frame);
                bytes = Math.addExact(bytes, frame.length + varIntBytes(frame.length));
                if (bytes > MAX_PACKET_BYTES) throw new IllegalArgumentException("Oversized visual frames");
                frames.add(frame);
            } finally { buffer.release(); }
        }
        return List.copyOf(frames);
    }

    public static void send(ServerPlayer player, Window window) {
        validate(window);
        if (!CHANNEL.isRemotePresent(player.connection.connection))
            throw new IllegalStateException("Remote visual channel unavailable");
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), window);
    }

    private static void encode(Window window, FriendlyByteBuf buffer) {
        validate(window);
        buffer.writeUUID(window.token());
        buffer.writeLong(window.revision());
        buffer.writeBoolean(window.full());
        writeKey(buffer, window.center());
        buffer.writeInt(window.minimumCubeY());
        buffer.writeVarInt(window.removed().size());
        for (CubeKey key : window.removed()) writeKey(buffer, key);
        buffer.writeVarInt(window.frames().size());
        for (byte[] frame : window.frames()) {
            buffer.writeVarInt(frame.length);
            buffer.writeBytes(frame);
        }
    }

    private static Window decode(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MAX_PACKET_BYTES) throw new IllegalArgumentException("Oversized visual window");
        UUID token = buffer.readUUID();
        long revision = buffer.readLong();
        boolean full = buffer.readBoolean();
        CubeKey center = readKey(buffer);
        int minimumCubeY = buffer.readInt();
        int removeCount = buffer.readVarInt();
        if (removeCount < 0 || removeCount > MAX_CUBES) throw new IllegalArgumentException("Invalid visual removals");
        List<CubeKey> removed = new ArrayList<>();
        for (int i = 0; i < removeCount; i++) removed.add(readKey(buffer));
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_CUBES) throw new IllegalArgumentException("Invalid visual frame count");
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int length = buffer.readVarInt();
            if (length <= 0 || length > CubeSectionPacketCodec.MAX_SECTION_BYTES + 24
                    || length > buffer.readableBytes()) throw new IllegalArgumentException("Invalid visual frame length");
            byte[] frame = new byte[length];
            buffer.readBytes(frame);
            frames.add(frame);
        }
        if (buffer.isReadable()) throw new IllegalArgumentException("Trailing visual packet data");
        Window window = new Window(token, revision, full, center, List.copyOf(removed), List.copyOf(frames), minimumCubeY);
        validate(window);
        return window;
    }

    /** Also used by the transactional client receiver for directly constructed envelopes. */
    public static void validate(Window window) {
        Objects.requireNonNull(window.token());
        var expected = windowKeys(window.center(), window.minimumCubeY());
        if (window.revision() <= 0 || window.frames().size() > MAX_CUBES || window.removed().size() > MAX_CUBES)
            throw new IllegalArgumentException("Invalid visual revision/counts");
        if (window.full() && (window.frames().size() != expected.size() || !window.removed().isEmpty()))
            throw new IllegalArgumentException("Incomplete full visual window");
        int bytes = 16 + 8 + 1 + 12 + 4 + varIntBytes(window.removed().size())
                + 12 * window.removed().size() + varIntBytes(window.frames().size());
        for (byte[] frame : window.frames()) {
            if (frame.length <= 0 || frame.length > CubeSectionPacketCodec.MAX_SECTION_BYTES + 24)
                throw new IllegalArgumentException("Invalid visual frame size");
            bytes = Math.addExact(bytes, varIntBytes(frame.length) + frame.length);
        }
        if (bytes > MAX_PACKET_BYTES) throw new IllegalArgumentException("Oversized visual packet");
    }

    private static int varIntBytes(int value) {
        int bytes = 1;
        while ((value >>>= 7) != 0) bytes++;
        return bytes;
    }
    private static void writeKey(FriendlyByteBuf buffer, CubeKey key) {
        buffer.writeInt(key.x()); buffer.writeInt(key.y()); buffer.writeInt(key.z());
    }
    private static CubeKey readKey(FriendlyByteBuf buffer) {
        return new CubeKey(buffer.readInt(), buffer.readInt(), buffer.readInt());
    }

    /** Frame arrays belong exclusively to this envelope and are never edited after staging. */
    public record Window(UUID token, long revision, boolean full, CubeKey center,
                         List<CubeKey> removed, List<byte[]> frames, int minimumCubeY) {
        public Window(UUID token, long revision, boolean full, CubeKey center, List<CubeKey> removed, List<byte[]> frames) {
            this(token, revision, full, center, removed, frames, Integer.MIN_VALUE / 16);
        }
    }
}
