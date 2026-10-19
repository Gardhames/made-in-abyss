package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeBlockView;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Exercises real Level and Entity.move, with fixture residency held for each synchronous test. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepLevelGameTests {
    @GameTest(template = "empty")
    public static void actualLevelReadsDeepStateAndKeepsVanillaAccess(GameTestHelper helper) {
        var level = helper.getLevel();
        var ordinary = helper.absolutePos(new BlockPos(1, 2, 1));
        var original = level.getBlockState(ordinary);
        var space = window();
        space.setBlock(new BlockCoordinate(5, -20000, 5), Block.getId(Blocks.STONE.defaultBlockState()));
        DeepCubeDomain.attach(level, space);
        try {
            helper.assertTrue(level.getBlockState(new BlockPos(5, -20000, 5)).is(Blocks.STONE),
                    "actual ServerLevel must read deep stone rather than VOID_AIR or packed alias");
            helper.assertTrue(level.getFluidState(new BlockPos(5, -20000, 5)).isEmpty(), "stone fluid is empty");
            helper.assertTrue(level.getBlockEntity(new BlockPos(5, -20000, 5)) == null, "inert fixture has no block entity");
            helper.assertTrue(level.setBlock(ordinary, Blocks.DIRT.defaultBlockState(), 2), "ordinary native write remains allowed");
            helper.assertTrue(level.getBlockState(ordinary).is(Blocks.DIRT), "ordinary Level path observes native write");
            helper.assertTrue(level.getMinBuildHeight() == -64, "experimental domain does not enlarge vanilla accessor");
        } finally {
            DeepCubeDomain.detach(level);
            level.setBlock(ordinary, original, 2);
        }
        helper.assertTrue(level.getBlockState(new BlockPos(5, -20000, 5)).is(Blocks.VOID_AIR),
                "detaching restores original deep Level behavior");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeEntityMoveStopsAtActualDeepFloor(GameTestHelper helper) {
        var level = helper.getLevel();
        var space = window();
        for (int x = 3; x <= 7; x++) for (int z = 3; z <= 7; z++) {
            space.setBlock(new BlockCoordinate(x, -20000, z), Block.getId(Blocks.STONE.defaultBlockState()));
        }
        DeepCubeDomain.attach(level, space);
        Entity body = body(helper, -19998.5);
        try {
            body.move(MoverType.SELF, new Vec3(0, -5, 0));
            helper.assertTrue(Math.abs(body.getY() - -19999.0) < 1.0e-7,
                    "native Entity.move must stop on real global floor at Y=-19999");
            helper.assertTrue(body.onGround(), "native collision records onGround");
            body.checkBelowWorld();
            helper.assertTrue(!body.isRemoved(), "deep body within technical domain must not get vanilla void removal");
        } finally { DeepCubeDomain.detach(level); body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unknownDeepWindowFreezesNativeMovement(GameTestHelper helper) {
        var level = helper.getLevel();
        DeepCubeDomain.attach(level, new SparseCubeSpace());
        Entity body = body(helper, -19999.0);
        try {
            body.setDeltaMovement(0.4, -1.0, 0.2);
            body.move(MoverType.SELF, new Vec3(1, -5, 0));
            helper.assertTrue(body.position().equals(new Vec3(5.5, -19999.0, 5.5)),
                    "unknown residency must freeze actual Entity.move, never permit falling through air");
            helper.assertTrue(body.getDeltaMovement().equals(Vec3.ZERO), "freeze clears stale velocity");
        } finally { DeepCubeDomain.detach(level); body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualLevelAvailabilityRetainsCubeY(GameTestHelper helper) {
        var level = helper.getLevel();
        var space = window();
        DeepCubeDomain.attach(level, space);
        try {
            helper.assertTrue(level.hasChunkAt(new BlockPos(5, -20000, 5)), "deep position must see resident cube");
            helper.assertTrue(!level.hasChunkAt(new BlockPos(5, -21000, 5)), "same X/Z unknown Y must remain absent");
            helper.assertTrue(level.hasChunksAt(new BlockPos(4, -20001, 4), new BlockPos(6, -19998, 6)),
                    "deep resident 3D bounds are available");
            helper.assertTrue(!level.hasChunksAt(new BlockPos(4, -21001, 4), new BlockPos(6, -19998, 6)),
                    "3D availability must reject missing vertical interval");
            helper.assertTrue(level.getBlockStatesIfLoaded(new AABB(5, -20000, 5, 6, -19999, 6)).count() > 0,
                    "real loaded-state stream must include deep known air");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unattachedLevelDoesNotSeeDeepFixture(GameTestHelper helper) {
        var level = helper.getLevel();
        DeepCubeDomain.detach(level);
        helper.assertTrue(level.getBlockState(new BlockPos(5, -20000, 5)).is(Blocks.VOID_AIR),
                "unattached Level retains original deep read");
        Entity body = body(helper, -19999);
        body.checkBelowWorld();
        helper.assertTrue(body.isRemoved(), "unattached entity retains original void behavior");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void livingTickAfterDeepRelocationCannotReuseSurfaceSupportXZ(GameTestHelper helper) {
        var level = helper.getLevel();
        var oldFloor = new BlockPos(105, 76, -104);
        var original = level.getBlockState(oldFloor);
        var body = EntityType.PIG.create(level);
        if (body == null) throw new IllegalStateException("Pig factory returned null");
        body.setNoAi(true);
        body.setNoGravity(true);
        try {
            level.setBlock(oldFloor, Blocks.STONE.defaultBlockState(), 2);
            body.setPos(105.5, 77.0, -103.5);
            body.setOnGround(true);
            helper.assertTrue(body.isSupportedBy(oldFloor), "native supporting-block query must establish old surface support");
            DeepCubeDomain.attach(level, window());
            body.moveTo(5.5, -19996.0, 5.5, 0, 0);
            boolean staleRead = false;
            try { body.tick(); }
            catch (CubeBlockView.MissingCubeException exception) {
                helper.assertTrue(exception.key().equals(new CubeKey(6, -1250, -7)),
                        "reproduction must identify old X/Z at new depth, not unrelated fixture failure");
                staleRead = true;
            }
            helper.assertTrue(!staleRead, "LivingEntity.tick after relocation must not read old surface X/Z at deep Y");
            helper.assertTrue(body.getOnPosLegacy().getX() == 5 && body.getOnPosLegacy().getZ() == 5,
                    "legacy standing-block query follows actual relocated X/Z");
            helper.assertTrue(body.mainSupportingBlockPos.isEmpty(), "airborne relocated body has no old supporting block");
        } finally {
            DeepCubeDomain.detach(level);
            body.discard();
            level.setBlock(oldFloor, original, 2);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void returningToSurfaceCannotReuseDeepSupportXZ(GameTestHelper helper) {
        var level = helper.getLevel();
        var surfaceFloor = new BlockPos(105, 76, -104);
        var original = level.getBlockState(surfaceFloor);
        var space = window();
        space.setBlock(new BlockCoordinate(5, -20000, 5), Block.getId(Blocks.STONE.defaultBlockState()));
        var body = EntityType.PIG.create(level);
        if (body == null) throw new IllegalStateException("Pig factory returned null");
        body.setNoAi(true);
        body.setNoGravity(true);
        DeepCubeDomain.attach(level, space);
        try {
            level.setBlock(surfaceFloor, Blocks.STONE.defaultBlockState(), 2);
            body.setPos(5.5, -19999.0, 5.5);
            body.setOnGround(true);
            helper.assertTrue(body.isSupportedBy(new BlockPos(5, -20000, 5)), "native query must establish deep support");
            body.absMoveTo(105.5, 77.0, -103.5);
            helper.assertTrue(body.getOnPosLegacy().equals(new BlockPos(105, 76, -104)),
                    "returning from deep domain must not combine old deep X/Z with surface Y");
            body.tick();
            helper.assertTrue(!body.isSupportedBy(new BlockPos(5, -20000, 5)), "surface tick cannot retain remote deep support");
        } finally {
            DeepCubeDomain.detach(level);
            body.discard();
            level.setBlock(surfaceFloor, original, 2);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void remoteDeepRelocationCannotReuseAnotherWindowSupport(GameTestHelper helper) {
        var level = helper.getLevel();
        var space = window();
        space.setBlock(new BlockCoordinate(5, -20000, 5), Block.getId(Blocks.STONE.defaultBlockState()));
        for (int x = 5; x <= 7; x++) for (int y = -1315; y <= -1311; y++) {
            for (int z = -8; z <= -6; z++) space.loadEmpty(new CubeKey(x, y, z));
        }
        var body = EntityType.PIG.create(level);
        if (body == null) throw new IllegalStateException("Pig factory returned null");
        body.setNoAi(true);
        body.setNoGravity(true);
        DeepCubeDomain.attach(level, space);
        try {
            body.setPos(5.5, -19999.0, 5.5);
            body.setOnGround(true);
            helper.assertTrue(body.isSupportedBy(new BlockPos(5, -20000, 5)), "native query establishes original deep support");
            body.setPos(105.5, -20996.0, -103.5);
            body.tick();
            helper.assertTrue(body.getOnPosLegacy().getX() == 105 && body.getOnPosLegacy().getZ() == -104,
                    "remote deep tick must use current window X/Z");
            helper.assertTrue(body.mainSupportingBlockPos.isEmpty(), "airborne remote body has no old window support");
            helper.assertTrue(!space.isLoaded(new CubeKey(0, -1313, 0)),
                    "test must not conceal stale-origin query by loading that unrelated cube");
        } finally { DeepCubeDomain.detach(level); body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void overlappingDeepUpdatesRetainNativeSupportAndMovement(GameTestHelper helper) {
        var level = helper.getLevel();
        var space = window();
        for (int x = 3; x <= 7; x++) for (int z = 3; z <= 7; z++) {
            space.setBlock(new BlockCoordinate(x, -20000, z), Block.getId(Blocks.STONE.defaultBlockState()));
        }
        var body = EntityType.PIG.create(level);
        if (body == null) throw new IllegalStateException("Pig factory returned null");
        body.setNoAi(true);
        body.setNoGravity(true);
        DeepCubeDomain.attach(level, space);
        try {
            body.setPos(5.5, -19999.0, 5.5);
            body.setOnGround(true);
            var floor = new BlockPos(5, -20000, 5);
            helper.assertTrue(body.isSupportedBy(floor), "native query establishes floor before small update");
            body.setPos(5.6, -19999.0, 5.5);
            helper.assertTrue(body.onGround() && body.isSupportedBy(floor),
                    "overlapping direct position update must retain original native support state");
            body.move(MoverType.SELF, new Vec3(0.1, -0.2, 0));
            helper.assertTrue(Math.abs(body.getX() - 5.7) < 1.0e-7 && Math.abs(body.getY() + 19999) < 1.0e-7,
                    "small real native collision move follows horizontal request and stops on floor");
            helper.assertTrue(body.onGround() && body.isSupportedBy(floor), "native movement recomputes actual floor support");
        } finally { DeepCubeDomain.detach(level); body.discard(); }
        helper.succeed();
    }

    private static Entity body(GameTestHelper helper, double y) {
        Entity body = EntityType.ITEM.create(helper.getLevel());
        if (body == null) throw new IllegalStateException("Item entity factory returned null");
        body.setNoGravity(true);
        body.setPos(5.5, y, 5.5);
        return body;
    }

    private static SparseCubeSpace window() {
        var space = new SparseCubeSpace();
        for (int x = -1; x <= 1; x++) for (int y = -1252; y <= -1248; y++) {
            for (int z = -1; z <= 1; z++) space.loadEmpty(new CubeKey(x, y, z));
        }
        return space;
    }
}
