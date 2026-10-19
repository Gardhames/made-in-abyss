package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.kernel.cube.CubeKey;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;

/** Actual native state/biome codec tests; no network channel or deep ServerLevel is implied. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CubePacketGameTests {
    // Break caught: packing Y into vanilla's narrower key aliases these two distinct cubes.
    @GameTest(template = "empty")
    public static void deepSectionsKeepDistinctKeysAndActualStates(GameTestHelper helper) {
        var registry = biomes(helper);
        var first = fixture(registry);
        var second = new LevelChunkSection(registry);
        second.setBlockState(15, 0, 15, Blocks.DIAMOND_BLOCK.defaultBlockState());
        var a = roundTrip(registry, new CubeKey(-17, -1250, -1), first);
        var b = roundTrip(registry, new CubeKey(-17, -994, -1), second);
        helper.assertTrue(a.key().equals(new CubeKey(-17, -1250, -1)), "deep key must retain all signed coordinates");
        helper.assertTrue(b.key().equals(new CubeKey(-17, -994, -1)), "nearby formerly packed-alias Y must stay distinct");
        helper.assertTrue(a.section().getBlockState(15, 0, 15).is(Blocks.GLOWSTONE), "first native marker survives");
        helper.assertTrue(b.section().getBlockState(15, 0, 15).is(Blocks.DIAMOND_BLOCK), "second native marker survives independently");
        helper.assertTrue(a.section().getBlockState(0, 15, 0).is(Blocks.WATER), "fluid state survives");
        helper.assertTrue(a.section().getNoiseBiome(0, 0, 0).is(Biomes.DESERT), "real biome holder survives");
        helper.assertTrue(a.section().getNoiseBiome(3, 3, 3).is(Biomes.PLAINS), "second biome survives");
        helper.assertTrue(!a.section().hasOnlyAir(), "native non-air count survives");
        helper.succeed();
    }

    // Break caught: truncating any coordinate to shorts/packed fields loses signed int boundaries.
    @GameTest(template = "empty")
    public static void signedIntBoundariesRoundTrip(GameTestHelper helper) {
        var registry = biomes(helper);
        for (var key : new CubeKey[]{new CubeKey(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE),
                new CubeKey(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE)}) {
            var decoded = roundTrip(registry, key, new LevelChunkSection(registry));
            helper.assertTrue(decoded.key().equals(key), "all signed int key boundaries must survive");
            helper.assertTrue(decoded.section().hasOnlyAir(), "known empty section stays known air");
        }
        helper.succeed();
    }

    // Break caught: accepting future formats, short payloads, excess bytes or false native counts publishes bad data.
    @GameTest(template = "empty")
    public static void malformedPacketsNeverPublishPartialSections(GameTestHelper helper) {
        var registry = biomes(helper);
        byte[] valid = encoded(new CubeKey(1, -1250, -3), fixture(registry));
        byte[] unknownVersion = valid.clone();
        unknownVersion[7] = 2;
        reject(helper, registry, unknownVersion, "unknown version");
        byte[] magic = valid.clone(); magic[0] ^= 1;
        reject(helper, registry, magic, "wrong magic");
        byte[] oversized = valid.clone();
        putInt(oversized, 20, CubeSectionPacketCodec.MAX_SECTION_BYTES + 1);
        reject(helper, registry, oversized, "oversized declared payload");
        byte[] negative = valid.clone(); putInt(negative, 20, -1);
        reject(helper, registry, negative, "negative length");
        for (int size : new int[]{0, 3, 23, valid.length - 1})
            reject(helper, registry, Arrays.copyOf(valid, size), "truncation at " + size);
        reject(helper, registry, Arrays.copyOf(valid, valid.length + 1), "trailing packet byte");
        byte[] internalTail = Arrays.copyOf(valid, valid.length + 1);
        putInt(internalTail, 20, valid.length - 24 + 1);
        reject(helper, registry, internalTail, "trailing section byte");
        byte[] falseCount = valid.clone(); falseCount[24] = 0; falseCount[25] = 0;
        reject(helper, registry, falseCount, "non-air count inconsistent with states");
        // Air payload: short count, zero-bit block palette, AIR ID 0, raw-long count at offset 28.
        byte[] badLongCount = encoded(new CubeKey(0, 0, 0), new LevelChunkSection(registry));
        badLongCount[28] = 1;
        reject(helper, registry, badLongCount, "native storage long count mismatch");
        byte[] hugeLongCount = new byte[badLongCount.length + 4];
        System.arraycopy(badLongCount, 0, hugeLongCount, 0, 28);
        hugeLongCount[28] = (byte) 0xff; hugeLongCount[29] = (byte) 0xff;
        hugeLongCount[30] = (byte) 0xff; hugeLongCount[31] = (byte) 0xff; hugeLongCount[32] = 7;
        System.arraycopy(badLongCount, 29, hugeLongCount, 33, badLongCount.length - 29);
        putInt(hugeLongCount, 20, hugeLongCount.length - 24);
        reject(helper, registry, hugeLongCount, "huge inner count before allocation");
        helper.succeed();
    }

    // Break caught: vanilla HashMapPalette can retain 33 entries under a five-bit (32-entry) storage.
    @GameTest(template = "empty")
    public static void overfullNativePaletteIsRejected(GameTestHelper helper) {
        var registry = biomes(helper);
        reject(helper, registry, palettePacket(registry, 33), "five-bit palette with 33 entries");
        helper.succeed();
    }

    // Break caught: an untrusted palette count must never drive an allocation or unbounded insertion loop.
    @GameTest(template = "empty")
    public static void absurdNativePaletteCountIsRejected(GameTestHelper helper) {
        var registry = biomes(helper);
        reject(helper, registry, palettePacket(registry, Integer.MAX_VALUE), "absurd inner palette count");
        helper.succeed();
    }

    private static byte[] palettePacket(Registry<Biome> registry, int count) {
        var nativeOut = new FriendlyByteBuf(Unpooled.buffer());
        try {
            nativeOut.writeShort(4096); // every raw index below refers to the first, stone entry
            nativeOut.writeByte(5); // five-bit storage can address only 32 palette entries
            nativeOut.writeVarInt(count);
            if (count == 33) {
                int stoneId = Block.getId(Blocks.STONE.defaultBlockState());
                nativeOut.writeVarInt(stoneId);
                int written = 1;
                for (int id = 0; written < 33; id++) {
                    if (id != stoneId) { nativeOut.writeVarInt(id); written++; }
                }
                nativeOut.writeVarInt(342); // ceil(4096 / floor(64 / 5))
                for (int index = 0; index < 342; index++) nativeOut.writeLong(0);
                new LevelChunkSection(registry).getBiomes().write(nativeOut);
            }
            byte[] header = encoded(new CubeKey(0, -1250, 0), new LevelChunkSection(registry));
            byte[] packet = Arrays.copyOf(header, 24 + nativeOut.readableBytes());
            putInt(packet, 20, nativeOut.readableBytes());
            nativeOut.readBytes(packet, 24, nativeOut.readableBytes());
            return packet;
        } finally { nativeOut.release(); }
    }

    private static LevelChunkSection fixture(Registry<Biome> registry) {
        var section = new LevelChunkSection(registry);
        section.setBlockState(15, 0, 15, Blocks.GLOWSTONE.defaultBlockState());
        section.setBlockState(0, 15, 0, Blocks.WATER.defaultBlockState());
        section.fillBiomesFromNoise((x, y, z, sampler) -> registry.getHolderOrThrow(x == 0 ? Biomes.DESERT : Biomes.PLAINS),
                null, 0, 0, 0);
        return section;
    }

    private static CubeSectionPacketCodec.DecodedCube roundTrip(Registry<Biome> registry, CubeKey key, LevelChunkSection section) {
        byte[] bytes = encoded(key, section);
        var in = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try { return CubeSectionPacketCodec.decode(in, registry); }
        finally { in.release(); }
    }

    private static byte[] encoded(CubeKey key, LevelChunkSection section) {
        var out = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CubeSectionPacketCodec.encode(out, key, section);
            byte[] bytes = new byte[out.readableBytes()]; out.readBytes(bytes); return bytes;
        } finally { out.release(); }
    }

    private static void reject(GameTestHelper helper, Registry<Biome> registry, byte[] bytes, String reason) {
        var in = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        boolean rejected = false;
        int readerBefore = in.readerIndex();
        try { CubeSectionPacketCodec.decode(in, registry); }
        catch (IllegalArgumentException expected) { rejected = true; }
        finally {
            try { helper.assertTrue(in.readerIndex() == readerBefore, "failure must leave source readerIndex unchanged: " + reason); }
            finally { in.release(); }
        }
        helper.assertTrue(rejected, "must reject " + reason + " before returning any section");
    }

    private static Registry<Biome> biomes(GameTestHelper helper) {
        return helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24); bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8); bytes[offset + 3] = (byte) value;
    }
}
