package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeBlockView;
import dev.madeinabyss.forge.probe.deep.VerticalShaftFixture;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VerticalFixtureGameTests {
    // Break caught: missing wall/interior/floor geometry or packed depth aliases lose the continuous shaft.
    @GameTest(template = "empty")
    public static void nativeSnapshotsContainShaftWallsAirInteriorAndDeepFloor(GameTestHelper helper) {
        var space = new SparseCubeSpace();
        var view = new CubeBlockView(space, -24000, 24384);
        helper.assertTrue(state(space, view, 190, -1000, 192).is(Blocks.STONE), "west wall must be native stone at negative depth");
        for (int[] point : new int[][]{{194,-1000,192},{192,-1000,190},{192,-1000,194},{190,-1000,190}})
            helper.assertTrue(state(space, view, point[0], point[1], point[2]).is(Blocks.STONE), "all four sides and corners enclose shaft");
        for (int x = 191; x <= 193; x++) for (int z = 191; z <= 193; z++)
            helper.assertTrue(state(space, view, x, -1000, z).isAir(), "3x3 interior remains traversable native air");
        for (int x = 190; x <= 194; x++) for (int z = 190; z <= 194; z++)
            helper.assertTrue(state(space, view, x, -22000, z).is(x == 192 && z == 192 ? Blocks.DIAMOND_BLOCK : Blocks.STONE), "entire 5x5 bottom floor exists with center diamond");
        helper.assertTrue(state(space, view, 190, -4096, 192).is(Blocks.GOLD_BLOCK), "first depth marker stays at actual -4096");
        helper.assertTrue(state(space, view, 190, -15904, 192).is(Blocks.DIAMOND_BLOCK), "second depth marker stays at actual -15904");
        helper.assertTrue(state(space, view, 190, -20000, 192).is(Blocks.EMERALD_BLOCK), "deep marker stays at actual -20000");
        var floor = VerticalShaftFixture.generate(new CubeKey(12, -1375, 12));
        helper.assertTrue(floor.key().equals(new CubeKey(12, -1375, 12)), "snapshot retains exact floor key");
        for (int id : floor.states()) {
            BlockState nativeState = Block.BLOCK_STATE_REGISTRY.byId(id);
            helper.assertTrue(nativeState != null && BuiltInRegistries.BLOCK.containsKey(BuiltInRegistries.BLOCK.getKey(nativeState.getBlock())), "all generated states resolve to valid named native registry blocks");
        }
        helper.succeed();
    }

    // Break caught: off-by-one surface/bottom or int multiplication overflow generates blocks in unrelated cubes.
    @GameTest(template = "empty")
    public static void shaftBoundsAndExtremeSectionKeysNeverGenerateAliases(GameTestHelper helper) {
        var space = new SparseCubeSpace();
        var view = new CubeBlockView(space, -24000, 24384);
        helper.assertTrue(state(space, view, 190, -65, 192).is(Blocks.STONE), "wall begins in final below-vanilla block at -65");
        helper.assertTrue(state(space, view, 190, -64, 192).isAir(), "native surface entrance is not generated into cube ownership");
        helper.assertTrue(state(space, view, 190, 80, 192).isAir(), "ordinary start is prepared separately in native columns");
        helper.assertTrue(state(space, view, 190, -21999, 192).is(Blocks.STONE), "wall reaches block immediately above floor");
        for (int[] point : new int[][]{{192,-22001,192},{189,-1000,192},{195,-1000,192},{192,-1000,189},{192,-1000,195}})
            helper.assertTrue(state(space, view, point[0], point[1], point[2]).isAir(), "outside footprint or below floor remains known air");
        // Each x/z extreme multiplies far beyond int range; every huge positive/negative section stays unrelated.
        for (var key : new CubeKey[]{new CubeKey(Integer.MIN_VALUE, -63, 12),new CubeKey(Integer.MAX_VALUE, -63, 12),
                new CubeKey(12,Integer.MIN_VALUE,12),new CubeKey(12,Integer.MAX_VALUE,12),
                new CubeKey(12,-63,Integer.MIN_VALUE),new CubeKey(12,-63,Integer.MAX_VALUE),
                new CubeKey(268435468,-63,12),new CubeKey(12,-63,268435468),new CubeKey(12,-268435519,12)}) {
            var snapshot = VerticalShaftFixture.generate(key);
            helper.assertTrue(snapshot.key().equals(key), "extreme section key remains lossless");
            for (int id : snapshot.states()) helper.assertTrue(id == Block.getId(Blocks.AIR.defaultBlockState()), "overflowing section origins must not alias shaft walls/floor");
        }
        helper.succeed();
    }

    private static BlockState state(SparseCubeSpace space, CubeBlockView view, int x, int y, int z) {
        var key = CubeKey.fromBlock(new BlockCoordinate(x, y, z));
        if (!space.isLoaded(key)) space.load(VerticalShaftFixture.generate(key));
        return view.getBlockState(new BlockPos(x, y, z));
    }
}
