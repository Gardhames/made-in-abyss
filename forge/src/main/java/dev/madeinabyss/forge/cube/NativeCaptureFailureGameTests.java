package dev.madeinabyss.forge.cube;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.BlockCoordinate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NativeCaptureFailureGameTests {
    @GameTest(template = "empty")
    public static void oneFailedFinalCaptureCanRecoverWithoutInputMutation(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = new BlockPos(8,-19992,8);
        var space = new SparseCubeSpace();
        space.loadEmpty(new CubeKey(0,-1250,0));
        space.setBlock(new BlockCoordinate(8,-19992,8), Block.getId(Blocks.GLOWSTONE.defaultBlockState()));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var light = new CubeBlockLightAccess(space, new CubeBlockView(space, -24000, 23936), (current, keys) -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("Injected transient final native capture failure");
            return NativeBlockLightHalo.capture(current, keys);
        });
        long generation = space.contentGeneration();
        boolean refused = false;
        try { light.value(level, pos); }
        catch (IllegalStateException expected) { refused = expected.getMessage().equals("Injected transient final native capture failure"); }
        helper.assertTrue(refused && light.computedCubes() == 0, "failed final capture refuses publication");
        long failedRevision = light.revision(level);
        helper.assertTrue(light.value(level, pos) == 15, "retry after one final capture failure restores known source15");
        helper.assertTrue(space.contentGeneration() == generation, "recovery must not fake a geometry mutation");
        helper.assertTrue(light.recomputations() == 1 && failedRevision > 0, "one stable output is published after recovery");
        helper.succeed();
    }
}
