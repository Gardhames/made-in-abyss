package dev.madeinabyss.forge.cube;

import dev.madeinabyss.kernel.cube.CubeKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;
import java.util.Objects;

/** A single native section; registry IDs are session-specific, never a disk format. */
public final class CubeSectionPacketCodec {
    public static final int MAX_SECTION_BYTES = 65536;
    private static final int MAGIC = 0x4d494143; // MIAC
    private static final int VERSION = 1;
    private CubeSectionPacketCodec() {}

    public static void encode(FriendlyByteBuf out, CubeKey key, LevelChunkSection section) {
        Objects.requireNonNull(out);
        Objects.requireNonNull(key);
        byte[] payload = serialize(Objects.requireNonNull(section));
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(key.x()); out.writeInt(key.y()); out.writeInt(key.z());
        out.writeInt(payload.length);
        out.writeBytes(payload);
    }

    /** Reads exactly one packet. Failed decoding leaves the source reader index unchanged. */
    public static DecodedCube decode(FriendlyByteBuf in, Registry<Biome> biomes) {
        Objects.requireNonNull(in);
        Objects.requireNonNull(biomes);
        var frame = new FriendlyByteBuf(in.duplicate());
        try {
            if (frame.readInt() != MAGIC) throw new IllegalArgumentException("invalid cube packet magic");
            if (frame.readInt() != VERSION) throw new IllegalArgumentException("unsupported cube packet version");
            var key = new CubeKey(frame.readInt(), frame.readInt(), frame.readInt());
            int length = frame.readInt();
            if (length <= 0 || length > MAX_SECTION_BYTES)
                throw new IllegalArgumentException("cube section payload outside bound");
            if (frame.readableBytes() != length)
                throw new IllegalArgumentException("truncated cube payload or trailing packet data");
            byte[] payload = new byte[length];
            frame.readBytes(payload);
            var section = new LevelChunkSection(biomes);
            var nativeInput = new ExactStorageBuffer(Unpooled.wrappedBuffer(payload));
            try {
                section.read(nativeInput);
                if (nativeInput.isReadable()) throw new IllegalArgumentException("trailing native section data");
                validateNativeValues(section, nativeInput.getUnsignedShort(0));
                if (!Arrays.equals(payload, serialize(section)))
                    throw new IllegalArgumentException("noncanonical native section data");
            } finally { nativeInput.release(); }
            var decoded = new DecodedCube(key, section);
            in.readerIndex(frame.readerIndex());
            return decoded;
        } catch (RuntimeException invalid) {
            // Native palette/id/storage exceptions are malformed-input errors too; no section escapes.
            if (invalid instanceof IllegalArgumentException argument) throw argument;
            throw new IllegalArgumentException("invalid cube section packet", invalid);
        }
        // frame is a non-retained duplicate, owned by the caller: never release it here.
    }

    private static byte[] serialize(LevelChunkSection section) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer(4096, MAX_SECTION_BYTES));
        try {
            section.write(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } catch (IndexOutOfBoundsException oversized) {
            throw new IllegalArgumentException("native section exceeds packet bound", oversized);
        } finally { buffer.release(); }
    }

    private static void validateNativeValues(LevelChunkSection section, int writtenCount) {
        int nonAir = 0;
        int fluid = 0;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            var state = section.getBlockState(x, y, z);
            if (state == null) throw new IllegalArgumentException("missing native block state");
            if (!state.isAir()) nonAir++;
            if (!state.getFluidState().isEmpty()) fluid++;
        }
        // 1.20.1 setBlockState counts nonAir; recalcBlockCounts also counts nonempty fluids.
        // Both occur in real native sections. Do not recalc and silently alter the wire count.
        if (writtenCount != nonAir && writtenCount != nonAir + fluid)
            throw new IllegalArgumentException("native section count disagrees with its states");
        for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            if (section.getNoiseBiome(x, y, z) == null)
                throw new IllegalArgumentException("missing native biome holder");
        }
    }

    /** Vanilla discards a mismatched array returned by readLongArray; reject before allocating it. */
    private static final class ExactStorageBuffer extends FriendlyByteBuf {
        private int containersRead;
        private int nextPaletteCapacity;
        private boolean readingVarInt;
        private ExactStorageBuffer(ByteBuf source) { super(source); }

        @Override
        public byte readByte() {
            byte bits = super.readByte();
            if (readingVarInt) return bits;
            containersRead++;
            // Native section reads state bits first, then biome bits. Zero-bit singleton and
            // global palettes have no count field; local palettes must fit their bit width.
            int maxLocalBits = containersRead == 1 ? 8 : 3;
            nextPaletteCapacity = bits > 0 && bits <= maxLocalBits ? 1 << bits : 0;
            return bits;
        }

        @Override
        public int readVarInt() {
            int value;
            // FriendlyByteBuf.readVarInt dispatches to this.readByte; those bytes are not container headers.
            readingVarInt = true;
            try { value = super.readVarInt(); }
            finally { readingVarInt = false; }
            if (nextPaletteCapacity != 0) {
                int capacity = nextPaletteCapacity;
                nextPaletteCapacity = 0;
                if (value <= 0 || value > capacity)
                    throw new IllegalArgumentException("native palette count exceeds storage bit width");
            }
            return value;
        }

        @Override
        public long[] readLongArray(long[] expected) {
            int count = readVarInt();
            if (expected == null || count != expected.length || count > readableBytes() / Long.BYTES)
                throw new IllegalArgumentException("native storage long count mismatch");
            for (int index = 0; index < count; index++) expected[index] = readLong();
            return expected;
        }
    }

    /** Immutable envelope; the contained native Minecraft section is intentionally mutable. */
    public record DecodedCube(CubeKey key, LevelChunkSection section) {
        public DecodedCube {
            Objects.requireNonNull(key);
            Objects.requireNonNull(section);
        }
    }
}
