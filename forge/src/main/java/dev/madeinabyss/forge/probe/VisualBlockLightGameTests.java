package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeBlockLightAccess;
import dev.madeinabyss.forge.cube.CubeBlockView;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VisualBlockLightGameTests {
    @GameTest(template="empty")
    public static void immutableVisualBatchKeepsItsEpochAndFreshBatchObservesEdits(GameTestHelper helper) {
        var level=helper.getLevel();var space=window(new CubeKey(-1,-1250,-1),false);
        var source=new BlockPos(-1,-20152,-1);put(space,source,Blocks.GLOWSTONE);
        var light=light(space);var old=light.visualSnapshot(level);
        var sample=new BlockCoordinate(0,-20152,-1);
        long calculated=light.recomputations();
        for(int i=0;i<10000;i++) helper.assertTrue(old.value(sample).orElseThrow()==14,
                "batch face queries use one confirmed negative-seam light snapshot");
        helper.assertTrue(light.recomputations()==calculated && old.keys().size()==243,
                "per-face queries cannot trigger another capture or solve");
        helper.assertTrue(old.value(new BlockCoordinate(-32,-20152,-1)).isEmpty(),
                "batch query retains uncertain boundary rather than zero");
        put(space,source,Blocks.STONE);
        var fresh=light.visualSnapshot(level);
        helper.assertTrue(fresh.value(sample).orElseThrow()==0 && old.value(sample).orElseThrow()==14,
                "new geometry gets fresh0 without mutating an already frozen mesh batch");
        put(space,source,Blocks.OAK_SLAB);
        boolean refused=false;
        try {light.visualSnapshot(level);} catch(UnsupportedOperationException expected){refused=true;}
        helper.assertTrue(refused && light.computedCubes()==0,"failed batch capture cannot republish old light");
        put(space,source,Blocks.GLOWSTONE);
        helper.assertTrue(light.visualSnapshot(level).value(sample).orElseThrow()==14,"valid batch recovers after failure");
        refused=false;
        try {new CubeBlockLightAccess(space,new CubeBlockView(space,-24000,23936)).visualSnapshot(level);}
        catch(UnsupportedOperationException expected){refused=true;}
        helper.assertTrue(refused,"physical API cannot bypass residency through a visual batch");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void distantVisualSourceLightsWithoutAttachingPhysics(GameTestHelper helper) {
        var space = window(new CubeKey(-1,-1250,-1), false);
        var source = new BlockPos(-1,-20152,-1);
        put(space,source,Blocks.GLOWSTONE);
        var light=light(space);
        helper.assertTrue(light.value(helper.getLevel(), source.east())==14,
                "243 visual snapshots must light a distant source at negative seams, outside physics27");
        helper.assertTrue(light.computedCubes()==243,"visual input count stays bounded");
        helper.assertTrue(DeepCubeDomain.lookup(helper.getLevel()).isEmpty(),
                "a visual solve cannot attach its snapshots to native block reads or collision");
        long calculated=light.recomputations();
        helper.assertTrue(light.value(helper.getLevel(),source.east())==14 && light.recomputations()==calculated,
                "stable visual geometry reuses its derived result");
        put(space,source,Blocks.STONE);
        helper.assertTrue(light.value(helper.getLevel(),source.east())==0,"source removal must clear visual light");
        put(space,source,Blocks.GLOWSTONE);
        helper.assertTrue(light.value(helper.getLevel(),source.east())==14,"source restoration recomputes visual14");
        boolean unknown=false;
        try {light.value(helper.getLevel(),new BlockPos(-32,-20152,-1));}
        catch(CubeBlockLightAccess.UnavailableLightException expected){unknown=true;}
        helper.assertTrue(unknown,"unknown external light cannot become confirmed darkness");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void deepSourceAlsoLightsRealNativeHaloSample(GameTestHelper helper) {
        var level=helper.getLevel();
        var anchor=helper.absolutePos(new BlockPos(2056,0,2056));
        var source=new BlockPos(anchor.getX(),-65,anchor.getZ());
        var center=new CubeKey(Math.floorDiv(source.getX(),16),-5,Math.floorDiv(source.getZ(),16));
        for(int x=center.x()-1;x<=center.x()+1;x++) for(int z=center.z()-1;z<=center.z()+1;z++) level.getChunk(x,z);
        var nativePos=source.above();
        var before=level.getBlockState(nativePos);
        var space=window(center,true);
        put(space,source,Blocks.GLOWSTONE);
        level.setBlock(nativePos,Blocks.AIR.defaultBlockState(),3);
        try {
            var light=light(space);
            helper.assertTrue(light.value(level,nativePos)==14,
                    "rendering a deep source's top face must sample combined deep/native light, not vanilla darkness");
            helper.assertTrue(light.nativeHaloCubes()==9 && light.computedCubes()<=252,
                    "visual243 plus cached native9 fits the existing bounded solver");
            long old=light.revision(level);
            level.setBlock(nativePos,Blocks.STONE.defaultBlockState(),3);
            helper.assertTrue(light.value(level,nativePos)==0 && light.revision(level)>old,
                    "real native occluder changes invalidate visual output without cube mutations");
            level.setBlock(nativePos,Blocks.GLOWSTONE.defaultBlockState(),3);
            put(space,source,Blocks.AIR);
            helper.assertTrue(light.value(level,source)==14,"real native source must light back into deep visual data");
        } finally {level.setBlock(nativePos,before,3);}
        helper.succeed();
    }

    private static CubeBlockLightAccess light(SparseCubeSpace space) {
        return CubeBlockLightAccess.visual(space,new CubeBlockView(space,-24000,23936));
    }
    private static SparseCubeSpace window(CubeKey center, boolean stone) {
        var space=new SparseCubeSpace();var states=new int[4096];
        java.util.Arrays.fill(states,Block.getId((stone?Blocks.STONE:Blocks.AIR).defaultBlockState()));
        for(int x=-1;x<=1;x++) for(int y=-21;y<=5;y++) for(int z=-1;z<=1;z++)
            space.load(new CubeSnapshot(new CubeKey(center.x()+x,center.y()+y,center.z()+z),states));
        return space;
    }
    private static void put(SparseCubeSpace space,BlockPos pos,Block block) {
        space.setBlock(new BlockCoordinate(pos.getX(),pos.getY(),pos.getZ()),Block.getId(block.defaultBlockState()));
    }
}
