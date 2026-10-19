package dev.madeinabyss.forge.probe;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.FileCubeStore;
import dev.madeinabyss.kernel.cube.PersistentCubeSpace;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Real engine subcomponents at extended coordinates, without claiming a running deep ServerLevel. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CubeProbeGameTests {
    private static final int MIN_Y = -24000;
    private static final int HEIGHT = 24576;
    private static final int[] DEPTHS = {64, -4096, -15904, -20000};

    @GameTest(template = "empty")
    public static void wideningProtoChunkAloneDoesNotSaveDeepBlock(GameTestHelper helper) {
        var chunk = new ProtoChunk(new ChunkPos(4, 4), UpgradeData.EMPTY,
                LevelHeightAccessor.create(MIN_Y, HEIGHT), biomes(helper), null);
        var surface = new BlockPos(65, 64, 65);
        var deep = new BlockPos(65, -20000, 65);
        chunk.setBlockState(surface, Blocks.STONE.defaultBlockState(), false);
        chunk.setBlockState(deep, Blocks.GLOWSTONE.defaultBlockState(), false);
        helper.assertTrue(chunk.getSections().length == 1536, "extended native chunk must contain 1536 sections");
        helper.assertTrue(chunk.getBlockState(deep).is(Blocks.GLOWSTONE), "native ProtoChunk can address deep block in memory");
        CompoundTag saved = ChunkSerializer.write(helper.getLevel(), chunk);
        var sectionTags = saved.getList("sections", Tag.TAG_COMPOUND);
        boolean deepWritten = false;
        for (int index = 0; index < sectionTags.size(); index++) {
            if (sectionTags.getCompound(index).getInt("Y") == -1250) deepWritten = true;
        }
        helper.assertTrue(!deepWritten, "original-height level serializer must omit out-of-light-range deep section");
        var restored = ChunkSerializer.read(helper.getLevel(), helper.getLevel().getPoiManager(), chunk.getPos(), saved);
        helper.assertTrue(restored.getBlockState(surface).is(Blocks.STONE), "ordinary-height marker must survive native serialization");
        helper.assertTrue(!restored.getBlockState(deep).is(Blocks.GLOWSTONE), "widening only chunk must not be mistaken for deep persistence");
        var report = new JsonObject();
        report.addProperty("scope", "Extended native ProtoChunk within an ordinary-height ServerLevel; no deep runtime");
        report.addProperty("native_section_count", chunk.getSections().length);
        report.addProperty("in_memory_deep_block", "minecraft:glowstone");
        report.addProperty("serializer_light_min_section", helper.getLevel().getChunkSource().getLightEngine().getMinLightSection());
        report.addProperty("serializer_light_max_section_exclusive", helper.getLevel().getChunkSource().getLightEngine().getMaxLightSection());
        report.addProperty("written_section_count", sectionTags.size());
        report.addProperty("deep_section_written", deepWritten);
        report.addProperty("restored_deep_block", restored.getBlockState(deep).toString());
        writeReport("native-deep-chunk-baseline", report);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cubeStoreRestoresActualMinecraftStatesAtFourDepths(GameTestHelper helper) throws IOException {
        var directory = Path.of("diagnostics", "cube-stores", UUID.randomUUID().toString());
        var space = new SparseCubeSpace();
        var manager = new PersistentCubeSpace(space, new FileCubeStore(directory));
        BlockState[] markers = {Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                Blocks.GLOWSTONE.defaultBlockState(), Blocks.DIAMOND_BLOCK.defaultBlockState()};
        for (int index = 0; index < DEPTHS.length; index++) {
            var position = new BlockCoordinate(-17, DEPTHS[index], -1);
            var key = CubeKey.fromBlock(position);
            manager.load(key);
            space.setBlock(position, Block.getId(markers[index]));
            manager.saveAndUnload(key);
        }
        helper.assertTrue(space.loadedCubeCount() == 0, "all four cubes must be unloaded after saving");
        var restored = new SparseCubeSpace();
        var reader = new PersistentCubeSpace(restored, new FileCubeStore(directory));
        var rows = new JsonArray();
        for (int index = 0; index < DEPTHS.length; index++) {
            var position = new BlockCoordinate(-17, DEPTHS[index], -1);
            reader.load(CubeKey.fromBlock(position));
            var state = Block.stateById(restored.block(position).orElseThrow());
            helper.assertTrue(state.equals(markers[index]), "actual Minecraft state must survive at Y=" + DEPTHS[index]);
            var section = new LevelChunkSection(biomes(helper));
            section.setBlockState(15, Math.floorMod(position.y(), 16), 15, state);
            helper.assertTrue(section.getBlockState(15, Math.floorMod(position.y(), 16), 15).equals(markers[index]),
                    "restored state must be accepted by a native Minecraft section");
            var row = new JsonObject();
            row.addProperty("y", position.y());
            row.addProperty("section_y", CubeKey.fromBlock(position).y());
            row.addProperty("restored_state", state.toString());
            rows.add(row);
        }
        var report = new JsonObject();
        report.addProperty("scope", "Own diagnostic cube store plus native block registry/sections, not ServerLevel integration");
        report.addProperty("registry_ids_are_session_specific", true);
        report.addProperty("loaded_cube_count", restored.loadedCubeCount());
        report.add("markers", rows);
        writeReport("native-cube-storage", report);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void nativeSectionCostComparison(GameTestHelper helper) {
        Registry<Biome> biomes = biomes(helper);
        byte[] mixedSection = mixedSection(biomes);
        // Warm the constructor/serialization paths; retain no measured data from this pass.
        measureColumns(biomes, mixedSection, false);
        measureCubes(biomes, mixedSection, false);
        var samples = new JsonArray();
        for (int repeat = 0; repeat < 3; repeat++) {
            for (boolean mixed : new boolean[]{false, true}) {
                JsonObject column = measureColumns(biomes, mixedSection, mixed);
                JsonObject cube = measureCubes(biomes, mixedSection, mixed);
                column.addProperty("repeat", repeat);
                cube.addProperty("repeat", repeat);
                helper.assertTrue(column.get("section_count").getAsInt() == 13824, "9 full native columns must own 13824 sections");
                helper.assertTrue(cube.get("section_count").getAsInt() == 108, "four disjoint 3x3x3 windows must own 108 sections");
                samples.add(column);
                samples.add(cube);
            }
        }
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        String rejection;
        int oneColumnBytes = mixedSection.length * (HEIGHT / 16);
        try {
            buffer.writeNbt(new CompoundTag());
            buffer.writeVarInt(oneColumnBytes);
            for (int section = 0; section < HEIGHT / 16; section++) buffer.writeBytes(mixedSection);
            buffer.writeVarInt(0); // block entities
            try {
                new ClientboundLevelChunkPacketData(buffer, 0, 0);
                throw new IllegalStateException("expected the baseline 2 MiB chunk payload limit");
            } catch (RuntimeException expected) {
                rejection = expected.getMessage();
                helper.assertTrue("Chunk Packet trying to allocate too much memory on read.".equals(rejection),
                        "must fail for real packet-size guard, not an unrelated parse error");
            }
        } finally { buffer.release(); }
        var report = new JsonObject();
        report.addProperty("scope", "Actual native ProtoChunk/LevelChunkSection allocation and section serialization only; no world/tick/render benchmark");
        report.addProperty("min_y", MIN_Y);
        report.addProperty("height", HEIGHT);
        report.addProperty("horizontal_radius_sections", 1);
        report.addProperty("vertical_radius_sections", 1);
        report.addProperty("player_depth_count", 4);
        report.addProperty("mixed_material_types", 8);
        report.addProperty("mixed_section_bytes", mixedSection.length);
        report.addProperty("mixed_full_column_bytes", oneColumnBytes);
        report.addProperty("original_packet_decoder_error", rejection);
        report.addProperty("custom_cube_network_protocol_implemented", false);
        report.addProperty("allocation_measure", "HotSpot per-thread allocated bytes; includes temporaries, not retained heap");
        report.add("samples", samples);
        writeReport("native-section-comparison", report);
        helper.succeed();
    }

    private static JsonObject measureColumns(Registry<Biome> biomes, byte[] encoded, boolean mixed) {
        long allocatedBefore = allocatedBytes();
        long started = System.nanoTime();
        var columns = new ArrayList<ProtoChunk>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                var chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY,
                        LevelHeightAccessor.create(MIN_Y, HEIGHT), biomes, null);
                if (mixed) {
                    for (var section : chunk.getSections()) decode(section, encoded);
                }
                columns.add(chunk);
            }
        }
        long constructionNanos = System.nanoTime() - started;
        long allocated = allocationDelta(allocatedBefore);
        var sections = new ArrayList<LevelChunkSection>();
        for (var column : columns) sections.addAll(List.of(column.getSections()));
        return measureSerialization("full_columns", mixed, sections, constructionNanos, allocated);
    }

    private static JsonObject measureCubes(Registry<Biome> biomes, byte[] encoded, boolean mixed) {
        long allocatedBefore = allocatedBytes();
        long started = System.nanoTime();
        Map<CubeKey, LevelChunkSection> cubes = new HashMap<>();
        for (int depth : DEPTHS) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    for (int y = Math.floorDiv(depth, 16) - 1; y <= Math.floorDiv(depth, 16) + 1; y++) {
                        var section = new LevelChunkSection(biomes);
                        if (mixed) decode(section, encoded);
                        cubes.put(new CubeKey(x, y, z), section);
                    }
                }
            }
        }
        long constructionNanos = System.nanoTime() - started;
        long allocated = allocationDelta(allocatedBefore);
        return measureSerialization("active_cube_windows", mixed, new ArrayList<>(cubes.values()), constructionNanos, allocated);
    }

    private static JsonObject measureSerialization(String representation, boolean mixed,
                                                   List<LevelChunkSection> sections, long nanos, long allocated) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer(4096));
        long bytes = 0;
        long estimatedBytes = 0;
        long started = System.nanoTime();
        try {
            for (var section : sections) {
                buffer.clear();
                section.write(buffer);
                // Vanilla's size function is an allocation estimate: its VarInt count uses
                // logical storage size instead of raw-long count. All-air writes are one byte smaller.
                if (buffer.writerIndex() > section.getSerializedSize()) throw new IllegalStateException("native estimate underallocates");
                bytes += buffer.writerIndex();
                estimatedBytes += section.getSerializedSize();
            }
        } finally { buffer.release(); }
        var row = new JsonObject();
        row.addProperty("representation", representation);
        row.addProperty("scenario", mixed ? "eight_materials_every_section" : "all_air");
        row.addProperty("section_count", sections.size());
        row.addProperty("construction_ns", nanos);
        row.addProperty("construction_allocated_bytes", allocated);
        row.addProperty("section_serialization_ns", System.nanoTime() - started);
        row.addProperty("section_payload_bytes", bytes);
        row.addProperty("native_estimated_payload_bytes", estimatedBytes);
        return row;
    }

    private static byte[] mixedSection(Registry<Biome> biomes) {
        var section = new LevelChunkSection(biomes);
        Block[] materials = {Blocks.STONE, Blocks.DIRT, Blocks.GRANITE, Blocks.DIORITE,
                Blocks.ANDESITE, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.COBBLESTONE};
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) section.setBlockState(x, y, z, materials[(x + y + z) % materials.length].defaultBlockState());
            }
        }
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            section.write(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally { buffer.release(); }
    }

    private static void decode(LevelChunkSection section, byte[] bytes) {
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try { section.read(buffer); }
        finally { buffer.release(); }
    }

    private static long allocatedBytes() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()) {
            return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        }
        return -1;
    }

    private static long allocationDelta(long before) {
        long after = allocatedBytes();
        return before < 0 || after < 0 ? -1 : after - before;
    }

    private static Registry<Biome> biomes(GameTestHelper helper) {
        return helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
    }

    private static void writeReport(String name, JsonObject report) {
        try {
            Path directory = Path.of("diagnostics");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(report),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new IllegalStateException("could not preserve probe evidence", exception); }
    }
}
