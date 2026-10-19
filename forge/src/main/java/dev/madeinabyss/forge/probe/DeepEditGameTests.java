package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.DeepCreativeEdits;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepEditGameTests {
    private static final BlockPos TARGET = new BlockPos(-1, -20000, -1);
    private static final Vec3 EYE = new Vec3(-.5, -19996, -.5);
    private static final AABB BODY = new AABB(1.2, -19999, -.8, 1.8, -19997.2, -.2);

    @GameTest(template = "empty")
    public static void creativeBreakKeepsNegativeGlobalPosition(GameTestHelper h) {
        var space = window();
        var result = apply(space, false, true, Blocks.STONE.defaultBlockState());
        h.assertTrue(result.accepted(), "reachable creative break must be accepted");
        h.assertTrue(result.changed().equals(TARGET), "break must keep Y=-20000 and negative X/Z");
        h.assertTrue(space.block(new BlockCoordinate(-1,-20000,-1)).orElseThrow() == Block.getId(Blocks.AIR.defaultBlockState()), "server must replace actual target with air");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void creativePlaceUsesServerHeldBlockAndFace(GameTestHelper h) {
        var space = window();
        var result = apply(space, true, true, Blocks.EMERALD_BLOCK.defaultBlockState());
        h.assertTrue(result.accepted(), "reachable empty face target must accept held emerald");
        h.assertTrue(result.changed().equals(new BlockPos(-1,-19999,-1)), "place must use face-adjacent global position");
        h.assertTrue(space.block(new BlockCoordinate(-1,-19999,-1)).orElseThrow() == Block.getId(Blocks.EMERALD_BLOCK.defaultBlockState()), "server held state must appear at actual depth");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidDeepEditsNeverMutateResidentData(GameTestHelper h) {
        var space = window();
        h.assertTrue(!apply(space, false, false, Blocks.STONE.defaultBlockState()).accepted(), "survival is outside the implemented slice");
        h.assertTrue(!apply(space, true, true, Blocks.CHEST.defaultBlockState()).accepted(), "block entities must not be placed as inert blocks");
        var stale = new DeepCreativeEdits.Request(false,TARGET,Direction.UP,new Vec3(-.5,-19999,-.5),Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()));
        h.assertTrue(!DeepCreativeEdits.apply(space,stale,EYE,BODY,true,Blocks.STONE.defaultBlockState(),5).accepted(), "stale clicked state must reject");
        var normal = request(false);
        h.assertTrue(!DeepCreativeEdits.apply(space,normal,new Vec3(-.5,-19990,-.5),BODY,true,Blocks.STONE.defaultBlockState(),5).accepted(), "out of reach must reject");
        h.assertTrue(!DeepCreativeEdits.apply(space,request(true),EYE,new AABB(-.8,-19999,-.8,-.2,-19997.2,-.2),true,Blocks.STONE.defaultBlockState(),5).accepted(), "place inside player must reject");
        h.assertTrue(space.block(new BlockCoordinate(-1,-20000,-1)).orElseThrow()==Block.getId(Blocks.STONE.defaultBlockState()), "rejections must preserve original target");
        h.assertTrue(space.block(new BlockCoordinate(-1,-19999,-1)).orElseThrow()==Block.getId(Blocks.AIR.defaultBlockState()), "rejections must preserve empty adjacent cell");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void unknownAndOccludedClicksCannotBecomeTrustedAir(GameTestHelper h) {
        var space = window();
        var unknown = new DeepCreativeEdits.Request(false,new BlockPos(100,-20000,100),Direction.UP,new Vec3(100.5,-19999,100.5),Block.getId(Blocks.STONE.defaultBlockState()));
        h.assertTrue(!DeepCreativeEdits.apply(space,unknown,new Vec3(100.5,-19996,100.5),new AABB(101,-19999,100,102,-19997,101),true,Blocks.STONE.defaultBlockState(),5).accepted(), "unknown data must reject without pretending air");
        space.setBlock(new BlockCoordinate(-1,-19998,-1),Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()));
        h.assertTrue(!apply(space,false,true,Blocks.STONE.defaultBlockState()).accepted(), "closer obstruction must block target interaction");
        h.assertTrue(space.block(new BlockCoordinate(-1,-20000,-1)).orElseThrow()==Block.getId(Blocks.STONE.defaultBlockState()), "occluded target must stay intact");
        h.succeed();
    }

    private static DeepCreativeEdits.Request request(boolean place) {
        return new DeepCreativeEdits.Request(place,TARGET,Direction.UP,new Vec3(-.5,-19999,-.5),Block.getId(Blocks.STONE.defaultBlockState()));
    }
    @GameTest(template="empty")
    public static void rayCrossingTechnicalBoundaryRejectsWithoutMutation(GameTestHelper h) {
        var space=new SparseCubeSpace();
        space.loadEmpty(new CubeKey(0,-1500,0));
        space.setBlock(new BlockCoordinate(0,-24000,0),Block.getId(Blocks.STONE.defaultBlockState()));
        var request=new DeepCreativeEdits.Request(false,new BlockPos(0,-24000,0),Direction.UP,new Vec3(.5,-23999,.5),Block.getId(Blocks.STONE.defaultBlockState()));
        var result=DeepCreativeEdits.apply(space,request,new Vec3(.5,-23997,.5),new AABB(2,-23999,0,3,-23997,1),true,Blocks.STONE.defaultBlockState(),5);
        h.assertTrue(!result.accepted(),"unavailable ray halo at bottom must reject without crashing");
        h.assertTrue(space.block(new BlockCoordinate(0,-24000,0)).orElseThrow()==Block.getId(Blocks.STONE.defaultBlockState()),"bottom rejection preserves data");
        h.succeed();
    }
    private static DeepCreativeEdits.Result apply(SparseCubeSpace space, boolean place, boolean creative, net.minecraft.world.level.block.state.BlockState held) {
        return DeepCreativeEdits.apply(space,request(place),EYE,BODY,creative,held,5);
    }
    private static SparseCubeSpace window() {
        var space = new SparseCubeSpace();
        for(int x=-2;x<=0;x++) for(int y=-1251;y<=-1249;y++) for(int z=-2;z<=0;z++) space.loadEmpty(new CubeKey(x,y,z));
        space.setBlock(new BlockCoordinate(-1,-20000,-1),Block.getId(Blocks.STONE.defaultBlockState()));
        return space;
    }
}
