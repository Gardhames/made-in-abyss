package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Actual ServerLevel boundary tests; direct kernel queries cannot satisfy these assertions. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepBlockLightGameTests {
    @GameTest(template = "empty")
    public static void nativeBlockLightReadsSourceAtNegativeSectionSeams(GameTestHelper helper) {
        var level = helper.getLevel();
        var space = new SparseCubeSpace();
        int[] depths = {-4097, -4096, -20001, -20000};
        for (int y : depths) {
            var position = new BlockCoordinate(-1, y, -1);
            space.loadEmpty(CubeKey.fromBlock(position));
            space.setBlock(position, Block.getId(Blocks.GLOWSTONE.defaultBlockState()));
        }
        DeepCubeDomain.attach(level, space);
        try {
            for (int y : depths) {
                int actual = level.getBrightness(LightLayer.BLOCK, new BlockPos(-1, y, -1));
                helper.assertTrue(actual == 15, "native BLOCK light at actual Y=" + y
                        + " must be 15 for glowstone, actual=" + actual);
            }
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeLightCrossesThreeNegativeSeams(GameTestHelper helper) {
        var level = helper.getLevel();
        for (int y : new int[]{-4097, -4096, -20001, -20000}) {
            var source = new BlockPos(-1, y, -1);
            var space = closedWindow(source);
            put(space, source, Blocks.GLOWSTONE);
            put(space, source.above(), Blocks.AIR);
            put(space, source.above(2), Blocks.AIR);
            put(space, source.east(), Blocks.AIR);
            put(space, source.south(), Blocks.AIR);
            DeepCubeDomain.attach(level, space);
            try {
                brightness(helper, source, 15);
                brightness(helper, source.above(), 14);
                brightness(helper, source.above(2), 13);
                brightness(helper, source.east(), 14);
                brightness(helper, source.south(), 14);
                brightness(helper, source.west(), 0);
                helper.assertTrue(level.getRawBrightness(source.above(), 15) == 14,
                        "native raw path must use deep BLOCK even when SKY is fully darkened");
            } finally { DeepCubeDomain.detach(level); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removingSourceClearsLightWithoutPackedYAlias(GameTestHelper helper) {
        var level = helper.getLevel();
        var source = new BlockPos(-1, -20000, -1);
        var alias = new BlockPos(-1, -15904, -1);
        var space = closedWindow(source);
        var aliasSpace = closedWindow(alias);
        for (CubeKey key : aliasSpace.residentKeys()) {
            if (!space.isLoaded(key)) space.load(aliasSpace.snapshot(key).orElseThrow());
        }
        put(space, source, Blocks.GLOWSTONE);
        put(space, source.east(), Blocks.AIR);
        put(space, alias, Blocks.AIR);
        DeepCubeDomain.attach(level, space);
        try {
            brightness(helper, source, 15);
            brightness(helper, source.east(), 14);
            brightness(helper, alias, 0);
            put(space, source, Blocks.AIR);
            brightness(helper, source.east(), 0);
            put(space, alias, Blocks.GLOWSTONE);
            brightness(helper, alias, 15);
            brightness(helper, source, 0);
            var key = CubeKey.fromBlock(new BlockCoordinate(alias.getX(), alias.getY(), alias.getZ()));
            var saved = space.unload(key).orElseThrow();
            space.load(saved);
            brightness(helper, alias, 15);
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unknownLightBoundaryAndShapeCannotBecomeDarkness(GameTestHelper helper) {
        var level = helper.getLevel();
        var position = new BlockPos(0, -20000, 0);
        var space = new SparseCubeSpace();
        space.loadEmpty(CubeKey.fromBlock(new BlockCoordinate(0, -20000, 0)));
        DeepCubeDomain.attach(level, space);
        try {
            boolean refused = false;
            try { level.getBrightness(LightLayer.BLOCK, position); }
            catch (IllegalStateException expected) { refused = true; }
            helper.assertTrue(refused, "loaded AIR next to missing light inputs must remain unknown, not brightness zero");
            refused = false;
            try { level.getBrightness(LightLayer.BLOCK, position.below(32)); }
            catch (IllegalStateException expected) { refused = true; }
            helper.assertTrue(refused, "missing deep cube must not fall back to native packed light");
            put(space, position, Blocks.OAK_SLAB);
            refused = false;
            try { level.getBrightness(LightLayer.BLOCK, position); }
            catch (UnsupportedOperationException expected) { refused = true; }
            helper.assertTrue(refused, "shape-dependent light propagation must be explicitly unsupported in this first slice");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ordinaryAndSkyQueriesRetainNativeEnginePath(GameTestHelper helper) {
        var level = helper.getLevel();
        var position = helper.absolutePos(new BlockPos(1, 2, 1));
        int block = level.getBrightness(LightLayer.BLOCK, position);
        int sky = level.getBrightness(LightLayer.SKY, position);
        int raw = level.getRawBrightness(position, 3);
        boolean canSeeSky = level.canSeeSky(position);
        DeepCubeDomain.attach(level, closedWindow(new BlockPos(-1, -20000, -1)));
        try {
            helper.assertTrue(level.getBrightness(LightLayer.BLOCK, position) == block, "attached ordinary BLOCK unchanged");
            helper.assertTrue(level.getBrightness(LightLayer.SKY, position) == sky, "attached SKY remains native");
            helper.assertTrue(level.getRawBrightness(position, 3) == raw, "attached ordinary raw brightness unchanged");
            helper.assertTrue(level.canSeeSky(position) == canSeeSky, "native canSeeSky unchanged");
            var deep = new BlockPos(-1, -20000, -1);
            helper.assertTrue(level.getBrightness(LightLayer.SKY, deep)
                    == level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(deep), "deep SKY explicitly remains native");
        } finally { DeepCubeDomain.detach(level); }
        helper.assertTrue(level.getBrightness(LightLayer.BLOCK, position) == block, "detached ordinary BLOCK unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedLightRebuildCannotPublishStaleBrightness(GameTestHelper helper) {
        var level = helper.getLevel();
        var source = new BlockPos(8, -19992, 8);
        var space = new SparseCubeSpace();
        var sourceKey = CubeKey.fromBlock(new BlockCoordinate(8, -19992, 8));
        space.loadEmpty(sourceKey);
        put(space, source, Blocks.GLOWSTONE);
        DeepCubeDomain.attach(level, space);
        try {
            brightness(helper, source, 15);
            for (int x = 1; x <= 108; x++) space.loadEmpty(new CubeKey(x, sourceKey.y(), 0));
            boolean refused = false;
            try { level.getBrightness(LightLayer.BLOCK, source); }
            catch (IllegalStateException expected) { refused = true; }
            helper.assertTrue(refused, "over-budget invalidation cannot return the old 15 snapshot");
            for (int x = 1; x <= 108; x++) space.unload(new CubeKey(x, sourceKey.y(), 0));
            brightness(helper, source, 15);
            space.setBlock(new BlockCoordinate(8, -19992, 8), Integer.MAX_VALUE);
            refused = false;
            try { level.getBrightness(LightLayer.BLOCK, source); }
            catch (UnsupportedOperationException expected) { refused = true; }
            helper.assertTrue(refused, "invalid registry state cannot become AIR or reuse published source light");
            put(space, source, Blocks.GLOWSTONE);
            brightness(helper, source, 15);
            space.unload(sourceKey);
            refused = false;
            try { level.getBrightness(LightLayer.BLOCK, source); }
            catch (IllegalStateException expected) { refused = true; }
            helper.assertTrue(refused, "unloaded source cannot retain its old confirmed light");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void generatedOrdinaryMirrorCannotSupplyDeepLightAtNativeSeam(GameTestHelper helper) {
        var level = helper.getLevel();
        var deep = nativeSeamFixture(helper);
        var ordinary = deep.above();
        var previous = level.getBlockState(ordinary);
        level.setBlock(ordinary, Blocks.STONE.defaultBlockState(), 3);
        var space = closedWindow(deep);
        put(space, deep, Blocks.AIR);
        put(space, ordinary, Blocks.GLOWSTONE);
        DeepCubeDomain.attach(level, space);
        try {
            helper.assertTrue(!level.getBlockState(ordinary).is(Blocks.GLOWSTONE),
                    "ordinary native state must differ from the deliberately fictional cube snapshot source");
            boolean refused = false;
            try { level.getBrightness(LightLayer.BLOCK, deep); }
            catch (IllegalStateException expected) { refused = true; }
            // Native halo may eventually prove zero; it must never take the fictional source as input.
            if (!refused) brightness(helper, deep, 0);
        } finally { DeepCubeDomain.detach(level); level.setBlock(ordinary, previous, 3); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void realOrdinarySourceLightsDeepSeamAndInvalidatesWithoutCubeMutation(GameTestHelper helper) {
        var level = helper.getLevel();
        var deep = nativeSeamFixture(helper);
        var source = deep.above();
        var previous = level.getBlockState(source);
        var space = closedWindow(deep);
        put(space, deep, Blocks.AIR);
        put(space, deep.below(), Blocks.AIR);
        // The mirrored source deliberately disagrees with native terrain, in the opposite direction.
        put(space, source, Blocks.STONE);
        level.setBlock(source, Blocks.GLOWSTONE.defaultBlockState(), 3);
        DeepCubeDomain.attach(level, space);
        try {
            long generation = space.contentGeneration();
            brightness(helper, deep, 14);
            brightness(helper, deep.below(), 13);
            long calculated = DeepCubeDomain.lookup(level).orElseThrow().blockLight().recomputations();
            brightness(helper, deep, 14);
            helper.assertTrue(DeepCubeDomain.lookup(level).orElseThrow().blockLight().recomputations() == calculated,
                    "unchanged native section and cube generations reuse the light snapshot");
            level.setBlock(source, Blocks.STONE.defaultBlockState(), 3);
            brightness(helper, deep, 0);
            level.setBlock(source, Blocks.GLOWSTONE.defaultBlockState(), 3);
            brightness(helper, deep, 14);
            helper.assertTrue(space.contentGeneration() == generation,
                    "native-only mutations cannot fake a cube content generation change");
        } finally {
            DeepCubeDomain.detach(level);
            level.setBlock(source, previous, 3);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeSectionDirectWriteAlsoInvalidatesDeepLight(GameTestHelper helper) {
        var level = helper.getLevel();
        var deep = nativeSeamFixture(helper);
        var source = deep.above();
        var previous = level.getBlockState(source);
        var space = closedWindow(deep);
        put(space, deep, Blocks.AIR);
        level.setBlock(source, Blocks.GLOWSTONE.defaultBlockState(), 3);
        var chunk = level.getChunkSource().getChunkNow(source.getX() >> 4, source.getZ() >> 4);
        helper.assertTrue(chunk != null, "fixture ordinary FULL chunk is already cached");
        var section = chunk.getSection(chunk.getSectionIndex(source.getY()));
        DeepCubeDomain.attach(level, space);
        try {
            brightness(helper, deep, 14);
            section.setBlockState(source.getX() & 15, source.getY() & 15, source.getZ() & 15,
                    Blocks.STONE.defaultBlockState());
            brightness(helper, deep, 0);
        } finally {
            DeepCubeDomain.detach(level);
            section.setBlockState(source.getX() & 15, source.getY() & 15, source.getZ() & 15, previous);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingNativeHaloDoesNotLoadChunkOrConfirmDarkness(GameTestHelper helper) {
        var level = helper.getLevel();
        var deep = new BlockPos(1000000, -65, 1000000);
        var source = deep.above();
        var cache = level.getChunkSource();
        helper.assertTrue(cache.getChunkNow(source.getX() >> 4, source.getZ() >> 4) == null,
                "distant fixture native chunk is initially absent");
        var space = closedWindow(deep);
        put(space, deep, Blocks.AIR);
        DeepCubeDomain.attach(level, space);
        try {
            boolean unknown = false;
            try { level.getBrightness(LightLayer.BLOCK, deep); }
            catch (dev.madeinabyss.forge.cube.CubeBlockLightAccess.UnavailableLightException expected) { unknown = true; }
            helper.assertTrue(unknown, "missing ordinary halo retains explicit uncertainty");
            helper.assertTrue(cache.getChunkNow(source.getX() >> 4, source.getZ() >> 4) == null,
                    "light query must not synchronously load the distant native chunk");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativePacketReadInvalidatesReusedSectionLight(GameTestHelper helper) {
        var level = helper.getLevel();
        var deep = nativeSeamFixture(helper);
        var source = deep.above();
        var previous = level.getBlockState(source);
        var space = closedWindow(deep);
        put(space, deep, Blocks.AIR);
        level.setBlock(source, Blocks.GLOWSTONE.defaultBlockState(), 3);
        var chunk = level.getChunkSource().getChunkNow(source.getX() >> 4, source.getZ() >> 4);
        var section = chunk.getSection(chunk.getSectionIndex(source.getY()));
        var bytes = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        section.write(bytes);
        DeepCubeDomain.attach(level, space);
        try {
            brightness(helper, deep, 14);
            level.setBlock(source, Blocks.STONE.defaultBlockState(), 3);
            brightness(helper, deep, 0);
            // Same section identity, new packet contents. A block-set-only hook would return stale zero.
            section.read(bytes);
            brightness(helper, deep, 14);
        } finally {
            DeepCubeDomain.detach(level);
            bytes.release(); level.setBlock(source, previous, 3);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void inactiveLightDiagnosticRetainsNativeBrightnessPath(GameTestHelper helper) {
        var level = helper.getLevel();
        var source = new BlockPos(8, -19992, 8);
        int nativeBlock = level.getBrightness(LightLayer.BLOCK, source);
        int nativeRaw = level.getRawBrightness(source, 3);
        var space = closedWindow(source);
        put(space, source, Blocks.GLOWSTONE);
        DeepCubeDomain.attach(level, space, false);
        try {
            helper.assertTrue(level.getBlockState(source).is(Blocks.GLOWSTONE), "block routing remains active with light diagnostic disabled");
            helper.assertTrue(level.getBrightness(LightLayer.BLOCK, source) == nativeBlock,
                    "unimplemented light in the legacy shaft must retain the prior native path until native halo is implemented");
            helper.assertTrue(level.getRawBrightness(source, 3) == nativeRaw, "inactive diagnostic raw path also remains native");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void closedOrdinaryNativeReferenceAgreesWithDeepAttenuation(GameTestHelper helper) {
        var level = helper.getLevel();
        var ordinary = helper.absolutePos(new BlockPos(1, 2, 1));
        // A known opaque shell prevents ambient/block-light sources outside the small room.
        for (int x = -1; x <= 3; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            level.setBlock(ordinary.offset(x, y, z), Blocks.STONE.defaultBlockState(), 3);
        }
        level.setBlock(ordinary, Blocks.GLOWSTONE.defaultBlockState(), 3);
        level.setBlock(ordinary.east(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(ordinary.east(2), Blocks.AIR.defaultBlockState(), 3);
        helper.succeedWhen(() -> {
            helper.assertTrue(level.getBrightness(LightLayer.BLOCK, ordinary.east(2)) == 13,
                    "ordinary reference waits for real native light propagation to reach 13");
            var source = new BlockPos(-1, -20000, -1);
            var space = closedWindow(source);
            put(space, source, Blocks.GLOWSTONE);
            put(space, source.east(), Blocks.AIR);
            put(space, source.east(2), Blocks.AIR);
            DeepCubeDomain.attach(level, space);
            try {
                brightness(helper, source, level.getBrightness(LightLayer.BLOCK, ordinary));
                brightness(helper, source.east(), level.getBrightness(LightLayer.BLOCK, ordinary.east()));
                brightness(helper, source.east(2), 13);
            } finally { DeepCubeDomain.detach(level); }
        });
    }

    @GameTest(template = "empty")
    public static void scalarDependenciesLightOutsideGeometryWithoutGrantingBlockResidency(GameTestHelper helper) {
        var level = helper.getLevel();
        var query = new BlockPos(31, -19999, 8);
        var source = query.east();
        var space = closedWindow(new BlockPos(8, -19992, 8));
        put(space, query, Blocks.AIR);
        DeepCubeDomain.attach(level, space);
        try {
            var light = DeepCubeDomain.lookup(level).orElseThrow().blockLight();
            var inputs = new java.util.ArrayList<>(light.deepInputs());
            var emission = new byte[4096]; var opacity = new byte[4096];
            java.util.Arrays.fill(opacity, (byte)15);
            emission[1 << 8 | 8 << 4] = 15;
            var key = new CubeKey(2, -1250, 0);
            inputs.add(new dev.madeinabyss.kernel.light.CubeLightInput(key, emission, opacity));
            light.dependencies(inputs, true);
            brightness(helper, query, 14);
            helper.assertTrue(space.loadedCubeCount() == 27 && !space.isLoaded(key),
                    "scalar halo cannot grant geometry/physics residency to its source");
            boolean unavailable = false;
            try { level.getBrightness(LightLayer.BLOCK, source); }
            catch (dev.madeinabyss.forge.cube.CubeBlockLightAccess.UnavailableLightException expected) { unavailable = true; }
            helper.assertTrue(unavailable, "light-only source must not become a loaded geometry query");
            light.dependencies(java.util.List.of(), false);
            unavailable = false;
            try { level.getBrightness(LightLayer.BLOCK, query); }
            catch (dev.madeinabyss.forge.cube.CubeBlockLightAccess.UnavailableLightException expected) { unavailable = true; }
            helper.assertTrue(unavailable, "lost dependency snapshot cannot retain the old confirmed14");
            emission[1 << 8 | 8 << 4] = 0;
            inputs.set(inputs.size()-1, new dev.madeinabyss.kernel.light.CubeLightInput(key, emission, opacity));
            light.dependencies(inputs, true);
            brightness(helper, query, 0);
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedNativeCaptureInvalidatesRendererRevisionAndRecovers(GameTestHelper helper) {
        var level = helper.getLevel();
        var deep = nativeSeamFixture(helper);
        var source = deep.above();
        var previous = level.getBlockState(source);
        var space = closedWindow(deep);
        put(space, deep, Blocks.AIR);
        level.setBlock(source, Blocks.GLOWSTONE.defaultBlockState(), 3);
        var center = CubeKey.fromBlock(new BlockCoordinate(deep.getX(), deep.getY(), deep.getZ()));
        DeepCubeDomain.attach(level, space);
        try {
            var light = DeepCubeDomain.lookup(level).orElseThrow().blockLight();
            brightness(helper, deep, 14);
            long before = light.revision(level);
            // 9 seam columns in the valid window plus 28 distinct columns exceed the native halo cap36,
            // while physical residency stays below108. Capture fails before the solver is entered.
            for (int x = 10; x <= 37; x++) space.loadEmpty(new CubeKey(center.x()+x, center.y(), center.z()));
            long failed = light.revision(level);
            helper.assertTrue(failed > before, "failed native capture must invalidate previously lit renderer geometry");
            helper.assertTrue(light.revision(level) == failed, "unchanged capture failure cannot rebuild every render frame");
            helper.assertTrue(light.computedCubes() == 0, "capture failure cannot retain published light output");
            boolean refused = false;
            try { level.getBrightness(LightLayer.BLOCK, deep); }
            catch (IllegalStateException expected) { refused = true; }
            helper.assertTrue(refused, "capture failure must refuse stale14");
            for (int x = 10; x <= 37; x++) space.unload(new CubeKey(center.x()+x, center.y(), center.z()));
            brightness(helper, deep, 14);
            helper.assertTrue(light.revision(level) > failed, "valid input recovery must invalidate failed renderer state");
        } finally { DeepCubeDomain.detach(level); level.setBlock(source, previous, 3); }
        helper.succeed();
    }

    private static BlockPos nativeSeamFixture(GameTestHelper helper) {
        // GameTest controllers/beacons occupy the native bottom section near the templates.
        // Preload an isolated ordinary reference explicitly during setup; queries never load chunks.
        var anchor = helper.absolutePos(new BlockPos(1032, 0, 1032));
        var level = helper.getLevel();
        int sx = Math.floorDiv(anchor.getX(),16), sz = Math.floorDiv(anchor.getZ(),16);
        for (int x = sx-1; x <= sx+1; x++) for (int z = sz-1; z <= sz+1; z++) level.getChunk(x,z);
        return new BlockPos(anchor.getX(), level.getMinBuildHeight()-1, anchor.getZ());
    }

    private static SparseCubeSpace closedWindow(BlockPos center) {
        var space = new SparseCubeSpace();
        var key = CubeKey.fromBlock(new BlockCoordinate(center.getX(), center.getY(), center.getZ()));
        int[] states = new int[4096];
        java.util.Arrays.fill(states, Block.getId(Blocks.STONE.defaultBlockState()));
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            space.load(new CubeSnapshot(new CubeKey(key.x() + x, key.y() + y, key.z() + z), states));
        }
        return space;
    }
    private static void put(SparseCubeSpace space, BlockPos position, net.minecraft.world.level.block.Block block) {
        space.setBlock(new BlockCoordinate(position.getX(), position.getY(), position.getZ()), Block.getId(block.defaultBlockState()));
    }
    private static void brightness(GameTestHelper helper, BlockPos position, int expected) {
        int actual = helper.getLevel().getBrightness(LightLayer.BLOCK, position);
        helper.assertTrue(actual == expected, "native light at " + position + " expected " + expected + ", actual " + actual);
    }
}
