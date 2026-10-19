package dev.madeinabyss.forge.probe;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.SectionAddress;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Characterization of the unmodified 1.20.1 engine, not acceptance of full depth. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EngineProbeGameTests {
    @GameTest(template = "empty")
    public static void packedPositionBaseline(GameTestHelper helper) {
        helper.assertTrue(BlockPos.PACKED_Y_LENGTH == 12, "baseline packed Y must be 12 bits");
        BlockPos deep = new BlockPos(5, -20000, -7);
        helper.assertTrue(BlockPos.of(deep.asLong()).equals(new BlockPos(5, 480, -7)),
                "baseline must reproduce deep Y alias, not silently assume full depth works");
        JsonObject report = new JsonObject();
        report.addProperty("minecraft", "1.20.1");
        report.addProperty("forge", "47.4.20");
        report.addProperty("scope", "real engine position APIs; not a full-depth world");
        report.addProperty("packed_y_bits", BlockPos.PACKED_Y_LENGTH);
        JsonArray positions = new JsonArray();
        for (int y : new int[]{-24576, -20000, -19936, -4097, -2049, -2048, -2032, -1, 0, 2031, 2047, 2048, 20000}) {
            BlockPos input = new BlockPos(5, y, -7);
            BlockPos packed = BlockPos.of(input.asLong());
            BlockPos nbt = NbtUtils.readBlockPos(NbtUtils.writeBlockPos(input));
            SectionPos section = SectionPos.of(SectionPos.of(input).asLong());
            JsonObject row = new JsonObject();
            row.addProperty("input_y", y);
            row.addProperty("packed_roundtrip_y", packed.getY());
            row.addProperty("nbt_int_roundtrip_y", nbt.getY());
            row.addProperty("section_roundtrip_y", section.getY());
            positions.add(row);
        }
        report.add("positions", positions);
        writeReport("packed-positions", report);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void networkPositionBaseline(GameTestHelper helper) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeBlockPos(new BlockPos(5, -20000, -7));
            BlockPos read = buffer.readBlockPos();
            helper.assertTrue(read.equals(new BlockPos(5, 480, -7)), "network baseline must reproduce Y loss");
            JsonObject report = new JsonObject();
            report.addProperty("input_y", -20000);
            report.addProperty("received_y", read.getY());
            report.addProperty("bytes", buffer.writerIndex());
            writeReport("network-position", report);
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void chunkSectionByteEncodingBaseline(GameTestHelper helper) {
        // Actual ChunkSerializer use of putByte/getByte was independently identified in 1.20.1 bytecode.
        // This test isolates that NBT encoding; it does not claim to load a deep LevelChunk.
        CompoundTag tag = new CompoundTag();
        tag.putByte("Y", (byte) -1250);
        int restoredSection = tag.getByte("Y");
        helper.assertTrue(restoredSection == 30, "section byte encoding must reproduce truncation");
        JsonObject report = new JsonObject();
        report.addProperty("scope", "NBT primitive used by baseline ChunkSerializer, not a full chunk restart");
        report.addProperty("input_section_y", -1250);
        report.addProperty("restored_section_y", restoredSection);
        writeReport("section-byte", report);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void dimensionCodecRejectsFullDepthBaseline(GameTestHelper helper) {
        JsonObject encoded = DimensionType.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,
                helper.getLevel().dimensionType()).result().orElseThrow().getAsJsonObject();
        encoded.addProperty("min_y", -24000);
        encoded.addProperty("height", 24576);
        encoded.addProperty("logical_height", 24576);
        var decoded = DimensionType.DIRECT_CODEC.parse(JsonOps.INSTANCE, encoded);
        helper.assertTrue(decoded.error().isPresent(), "baseline dimension codec must reject full depth");
        JsonObject report = new JsonObject();
        report.addProperty("requested_min_y", -24000);
        report.addProperty("requested_height", 24576);
        report.addProperty("baseline_min_y", DimensionType.MIN_Y);
        report.addProperty("baseline_max_y", DimensionType.MAX_Y);
        report.addProperty("codec_error", decoded.error().orElseThrow().message());
        writeReport("dimension-codec", report);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void kernelMatchesRealSectionAddressAtDepth(GameTestHelper helper) {
        BlockPos block = new BlockPos(-17, -20001, 47);
        SectionPos engine = SectionPos.of(block);
        SectionAddress kernel = SectionAddress.fromBlock(new BlockCoordinate(-17, -20001, 47));
        helper.assertTrue(engine.getX() == kernel.sectionX() && engine.getY() == kernel.sectionY()
                && engine.getZ() == kernel.sectionZ(), "kernel and engine section coordinates must agree");
        helper.assertTrue(NbtUtils.readBlockPos(NbtUtils.writeBlockPos(block)).equals(block),
                "int-based position NBT must preserve depth");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualLocalBlockInteraction(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Blocks.STONE);
        helper.assertBlockPresent(Blocks.STONE, relative);
        helper.setBlock(relative, Blocks.AIR);
        helper.assertBlockPresent(Blocks.AIR, relative);
        helper.succeed();
    }

    private static void writeReport(String name, JsonObject report) {
        try {
            Path directory = Path.of("diagnostics");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(report),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("could not preserve diagnostic evidence", exception);
        }
    }
}
