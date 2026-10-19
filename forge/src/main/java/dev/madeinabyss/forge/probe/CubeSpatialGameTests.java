package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeBlockView;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real native spatial algorithms over own residency; player Level/ticking/render remains separate. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CubeSpatialGameTests {
    @GameTest(template = "empty")
    public static void nativeRaycastKeepsDeepNegativeCoordinate(GameTestHelper helper) {
        var space = loadedWindow(0, -20000, 0);
        var view = new CubeBlockView(space, -24000, 24576);
        var marker = new BlockCoordinate(0, -20000, 0);
        space.setBlock(marker, Block.getId(Blocks.STONE.defaultBlockState()));
        var context = new ClipContext(new Vec3(.5, -19996.5, .5), new Vec3(.5, -20002, .5),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null);
        var hit = view.clip(context);
        helper.assertTrue(hit.getType() == HitResult.Type.BLOCK, "native ray must hit deep marker");
        helper.assertTrue(hit.getBlockPos().equals(new BlockPos(0, -20000, 0)), "hit must retain int Y, not packed alias 480");
        helper.assertTrue(hit.getDirection() == Direction.UP && hit.getLocation().y == -19999, "actual native face and point must match stone top");
        space.setBlock(marker, Block.getId(Blocks.AIR.defaultBlockState()));
        helper.assertTrue(view.clip(context).getType() == HitResult.Type.MISS, "query must observe actual marker removal");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeCollisionClampsDescentAtDeepFloor(GameTestHelper helper) {
        var space = loadedWindow(0, -20000, 0);
        var view = new CubeBlockView(space, -24000, 24576);
        space.setBlock(new BlockCoordinate(0, -20000, 0), Block.getId(Blocks.STONE.defaultBlockState()));
        helper.assertTrue(!view.noCollision(new AABB(.2, -19999.8, .2, .8, -19999.2, .8)), "deep solid block must obstruct native AABB");
        var body = new AABB(.2, -19998.5, .2, .8, -19996.7, .8);
        helper.assertTrue(view.noCollision(body), "loaded air above floor must be traversable");
        double allowed = Shapes.collide(Direction.Axis.Y, body,
                view.getBlockCollisions(null, body.expandTowards(0, -5, 0)), -5);
        helper.assertTrue(Math.abs(allowed + .5) < 1.0e-8, "native shape collision must stop 5-block descent at floor after .5 blocks");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeFluidRaycastKeepsActualSourceWater(GameTestHelper helper) {
        var space = loadedWindow(-1, -20000, -1);
        var view = new CubeBlockView(space, -24000, 24576);
        space.setBlock(new BlockCoordinate(-1, -20000, -1), Block.getId(Blocks.WATER.defaultBlockState()));
        helper.assertTrue(view.getFluidState(new BlockPos(-1, -20000, -1)).is(Fluids.WATER), "adapter must return native fluid state");
        var hit = view.clip(new ClipContext(new Vec3(-.5, -19996.5, -.5), new Vec3(-.5, -20002, -.5),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, null));
        helper.assertTrue(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(new BlockPos(-1, -20000, -1)),
                "actual source-water shape must be selectable at negative depth and X/Z");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unloadedQueriesCannotMasqueradeAsPassableAir(GameTestHelper helper) {
        var view = new CubeBlockView(new SparseCubeSpace(), -24000, 24576);
        boolean collisionRejected = false;
        try { view.noCollision(new AABB(.2, -19999.8, .2, .8, -19999.2, .8)); }
        catch (CubeBlockView.MissingCubeException expected) { collisionRejected = true; }
        helper.assertTrue(collisionRejected, "unknown residency must not permit collision-free movement");
        boolean rayRejected = false;
        try { view.clip(new ClipContext(new Vec3(.5, -19996.5, .5), new Vec3(.5, -20002, .5),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)); }
        catch (CubeBlockView.MissingCubeException expected) { rayRejected = true; }
        helper.assertTrue(rayRejected, "unknown residency must not return a trustworthy MISS");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidQueryBoundsFailBeforeUnboundedTraversal(GameTestHelper helper) {
        var view = new CubeBlockView(loadedWindow(0, -20000, 0), -24000, 24576);
        boolean rejected = false;
        try { view.clip(new ClipContext(new Vec3(Double.NaN, -20000, 0), new Vec3(1, -20000, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "nonfinite ray must be rejected before native iteration");
        rejected = false;
        try { view.noCollision(new AABB(-30000000, -20000, -30000000, 30000000, -19999, 30000000)); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "enormous spatial query must fail its explicit budget before iterating");
        helper.succeed();
    }

    private static SparseCubeSpace loadedWindow(int x, int y, int z) {
        var space = new SparseCubeSpace();
        var center = CubeKey.fromBlock(new BlockCoordinate(x, y, z));
        for (int sx = center.x() - 1; sx <= center.x() + 1; sx++) {
            for (int sy = center.y() - 1; sy <= center.y() + 1; sy++) {
                for (int sz = center.z() - 1; sz <= center.z() + 1; sz++) space.loadEmpty(new CubeKey(sx, sy, sz));
            }
        }
        return space;
    }
}
