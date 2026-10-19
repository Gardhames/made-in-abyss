package dev.madeinabyss.forge.worldgen;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonObject;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Small ORIGINAL numeric fixture; never a world preset or approved geography. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ParameterizedTerrainGameTests {
    private static final LevelHeightAccessor COLUMN = LevelHeightAccessor.create(-64, 384);

    private static TerrainParameters parameters() {
        return new TerrainParameters(713L,
                new TerrainParameters.Surface(0, -48, 1000, 1200, 120, 64, 300, 650, 120, 100, 3),
                new TerrainParameters.Cavity(40, 64, -160, 60, 48, 8, 6),
                new TerrainParameters.Noise(64, 0, 0, 0));
    }

    private static ParameterizedTerrainChunkGenerator generator(GameTestHelper helper) {
            var biome = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME)
                    .getHolderOrThrow(Biomes.PLAINS);
            return new ParameterizedTerrainChunkGenerator(parameters(), new FixedBiomeSource(biome), -64, 384);
    }

    private static CubeSnapshot cube(ParameterizedTerrainChunkGenerator generator, CubeKey key) {
        return generator.generateCube(key);
    }

    // Catches dropped global offsets, floorDiv errors, seam discontinuity, and mutable snapshots.
    @GameTest(template = "empty")
    public static void globalSourceColumnCubeSeamAgreement(GameTestHelper helper) throws Exception {
        var generator = generator(helper);
        for (int x : new int[]{-201, 201}) {
            var column = generator.getBaseColumn(x, -1, COLUMN, null);
            var biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
            var chunk = new ProtoChunk(new ChunkPos(Math.floorDiv(x, 16), -1), UpgradeData.EMPTY, COLUMN, biomes, null);
            generator.fillFromNoise(Runnable::run, null, null, null, chunk).join();
            helper.assertTrue(column.getBlock(-64).is(Blocks.STONE), "literal island wall at ordinary lower seam");
            helper.assertTrue(column.getBlock(64).is(Blocks.GRASS_BLOCK), "literal terrace skin");
            helper.assertTrue(column.getBlock(65).isAir(), "literal terrace air");
            for (int y : new int[]{-65, -64, -1, 0, 63, 64}) {
                var key = new CubeKey(Math.floorDiv(x, 16), Math.floorDiv(y, 16), -1);
                var snapshot = cube(generator, key);
                int index = (Math.floorMod(y, 16) << 8) | (15 << 4) | Math.floorMod(x, 16);
                var nativeState = Block.stateById(snapshot.states()[index]);
                helper.assertTrue(nativeState.is(y == 64 ? Blocks.GRASS_BLOCK : y >= 62 ? Blocks.DIRT : Blocks.STONE),
                        "literal cube material at signed seam " + x + "/" + y);
                if (y >= -64) {
                    helper.assertTrue(nativeState.equals(column.getBlock(y)), "column/cube same global XYZ");
                    helper.assertTrue(nativeState.equals(chunk.getBlockState(new BlockPos(x, y, -1))), "actual native fill/cube same XYZ");
                }
                int[] exposed = snapshot.states(); exposed[index] = Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState());
                helper.assertTrue(!Block.stateById(snapshot.states()[index]).is(Blocks.DIAMOND_BLOCK), "snapshot defensive copy");
            }
        }
        helper.succeed();
    }

    // Catches confusing water surface with ocean floor and filling local instead of global X/Z.
    @GameTest(template = "empty")
    public static void realChunkWaterHeightmapsAgreeWithQueries(GameTestHelper helper) throws Exception {
        var generator = generator(helper);
        var biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var chunk = new ProtoChunk(new ChunkPos(-101, -1), UpgradeData.EMPTY, COLUMN, biomes, null);
        generator.fillFromNoise(Runnable::run, null, null, null, chunk).join();
        int x = chunk.getPos().getMinBlockX();
        int z = chunk.getPos().getMinBlockZ();
        helper.assertTrue(chunk.getBlockState(new BlockPos(x, -48, z)).is(Blocks.STONE), "literal ocean bed");
        helper.assertTrue(chunk.getBlockState(new BlockPos(x, 0, z)).is(Blocks.WATER), "literal sea surface");
        helper.assertTrue(chunk.getBlockState(new BlockPos(x, 1, z)).isAir(), "literal air above water");
        for (var type : new Heightmap.Types[]{Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG}) {
            int expected = type == Heightmap.Types.WORLD_SURFACE_WG ? 1 : -47;
            helper.assertTrue(generator.getBaseHeight(x, z, type, COLUMN, null) == expected, "native query predicate " + type);
            helper.assertTrue(chunk.getOrCreateHeightmapUnprimed(type).getFirstAvailable(0, 0) == expected,
                    "native populated heightmap " + type);
        }
        helper.succeed();
    }

    // Catches unavailable terrain becoming fictive air and unrelated dimension clipping.
    @GameTest(template = "empty")
    public static void unsupportedRangeRefusesAndCodecHasNoDefaults(GameTestHelper helper) throws Exception {
        var generator = generator(helper);
        try { cube(generator, new CubeKey(0, -11, 0)); helper.fail("below cutoff cube must refuse"); }
        catch (TerrainUnavailableException expected) {
            helper.assertTrue(expected.position().y() == -176, "cutoff refusal retains full global Y");
            helper.assertTrue(expected.reason() == dev.madeinabyss.kernel.terrain.TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER,
                    "typed kernel availability reason retained");
        }
        var p = parameters();
        var c = p.cavity();
        var crossingParameters = new TerrainParameters(p.seed(), p.surface(), new TerrainParameters.Cavity(c.mouthRadius(),
                c.firstLayerTopY(), -159, c.lowerRadius(), c.ledgePeriod(), c.ledgeWidth(), c.ledgeProjection()), p.noise());
        var crossing = new ParameterizedTerrainChunkGenerator(crossingParameters, (FixedBiomeSource) generator.getBiomeSource(), -64, 384);
        try { crossing.generateCube(new CubeKey(-1, -10, -1)); helper.fail("half-known cutoff cube must refuse atomically"); }
        catch (TerrainUnavailableException expected) { helper.assertTrue(expected.position().y() == -160, "crossing refusal reports unavailable voxel"); }
        try { generator.getBaseColumn(0, 0, LevelHeightAccessor.create(-80, 384), null); helper.fail("unrelated column envelope must refuse"); }
        catch (IllegalArgumentException expected) { }
        var biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var unsupported = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, LevelHeightAccessor.create(-80, 384), biomes, null);
        unsupported.setBlockState(new BlockPos(0, 0, 0), Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
        try { generator.fillFromNoise(Runnable::run, null, null, null, unsupported); helper.fail("unsupported chunk must refuse before mutation"); }
        catch (IllegalArgumentException expected) { }
        helper.assertTrue(unsupported.getBlockState(new BlockPos(0, 0, 0)).is(Blocks.DIAMOND_BLOCK), "preflight keeps existing unsupported chunk data");
        var codec = ParameterizedTerrainChunkGenerator.CODEC;
        helper.assertTrue(net.minecraft.core.registries.BuiltInRegistries.CHUNK_GENERATOR.get(TerrainGeneratorRegistration.ID) == codec,
                "registered generator id resolves the parameter codec");
        helper.assertTrue(codec.parse(JsonOps.INSTANCE, new JsonObject()).error().isPresent(), "missing all required fields is rejected");
        var registryOps = net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var encoded = codec.encodeStart(registryOps, generator).result().orElseThrow();
        var restored = codec.parse(registryOps, encoded).result().orElseThrow();
        helper.assertTrue(restored.parameters().equals(parameters()), "all explicit metric fields survive codec roundtrip");
        helper.assertTrue(restored.getMinY() == -64 && restored.getGenDepth() == 384, "ordinary envelope survives roundtrip");
        JsonObject root = encoded.getAsJsonObject();
        var seedless = root.deepCopy(); seedless.getAsJsonObject("parameters").remove("seed");
        helper.assertTrue(codec.parse(registryOps, seedless).error().isPresent(), "seed must be explicit");
        for (String group : new String[]{"surface", "cavity", "noise"}) {
            for (String field : root.getAsJsonObject("parameters").getAsJsonObject(group).keySet()) {
                var incomplete = root.deepCopy();
                incomplete.getAsJsonObject("parameters").getAsJsonObject(group).remove(field);
                helper.assertTrue(codec.parse(registryOps, incomplete).error().isPresent(), "missing metric refuses " + group + "/" + field);
            }
        }
        for (String field : new String[]{"parameters", "biome_source", "column_min_y", "column_height"}) {
            var incomplete = root.deepCopy(); incomplete.remove(field);
            helper.assertTrue(codec.parse(registryOps, incomplete).error().isPresent(), "missing generator field refuses " + field);
        }
        var invalid = root.deepCopy();
        invalid.getAsJsonObject("parameters").getAsJsonObject("surface").addProperty("island_radius_x", 0);
        helper.assertTrue(codec.parse(registryOps, invalid).error().isPresent(), "invalid metric constructor yields codec error");
        helper.succeed();
    }
}
