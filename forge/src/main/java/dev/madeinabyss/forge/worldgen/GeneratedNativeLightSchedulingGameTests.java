package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.VoxelSkyLight;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;

@GameTestHolder("near_stage1")
@PrefixGameTestTemplate(false)
public final class GeneratedNativeLightSchedulingGameTests {
    @GameTest(template="empty",timeoutTicks=2400)
    public static void signedClippedTilesKeepEveryStateExposureLightAndPriorCube(GameTestHelper h)throws Exception {
        var manifest=LivingAbyssGameTests.parentManifest();
        var profile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        var bounds=new VoxelSkyLight.Bounds(-41,-351,489,65,49,51);
        int air=Block.getId(Blocks.AIR.defaultBlockState()),stone=Block.getId(Blocks.STONE.defaultBlockState());
        var saved=new HashMap<CubeKey,int[]>();
        for(int cy=-24;cy<-17;cy++)for(int cz=28;cz<36;cz++)for(int cx=-5;cx<4;cx++) {
            int[] states=new int[4096];Arrays.fill(states,air);
            for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(Math.floorMod(cx*16+x+cz*16+z,17)<3)states[7*256+z*16+x]=stone;
            if(cx==-1&&cy==-21&&cz==32) {
                states[8*256+4*16+4]=Block.getId(Blocks.GLOWSTONE.defaultBlockState());
                states[8*256+4*16+5]=Block.getId(Blocks.WATER.defaultBlockState());
                states[9*256+4*16+5]=Block.getId(Blocks.OAK_LEAVES.defaultBlockState());
            }
            saved.put(new CubeKey(cx,cy,cz),states);
        }
        var serialSource=new GeneratedSkySource(profile,saved);var parallelSource=new GeneratedSkySource(profile,saved);
        var serialPool=Executors.newFixedThreadPool(1);var parallelPool=Executors.newFixedThreadPool(4);
        try {
            var serial=GeneratedNativeLightScheduler.solve(serialSource,bounds,Map.of(),serialPool,1,()->{},(s,k)->{});
            var parallel=GeneratedNativeLightScheduler.solve(parallelSource,bounds,Map.of(),parallelPool,4,()->{},(s,k)->{});
            equal(h,serial,parallel);
            var reused=GeneratedNativeLightScheduler.solve(parallelSource,bounds,parallel.cubes(),parallelPool,4,()->{},(s,k)->{});
            equal(h,serial,reused);
            h.assertTrue(reused.timings().reusedDensityCells()==4096L*parallel.cubes().size(),"All and only complete prior cubes are reused");
            h.assertTrue(reused.timings().sampledDensityCells()+reused.timings().reusedDensityCells()==65L*49*51,"Partial signed boundary cells are still sampled exactly");
            for(var entry:parallel.cubes().entrySet()) {
                h.assertTrue(reused.cubes().get(entry.getKey())==entry.getValue(),"Completed immutable prior arrays retain ownership");
                var key=entry.getKey();var expected=saved.get(key);
                h.assertTrue(Arrays.equals(expected,entry.getValue()),"Every captured cube equals literal saved authority");
            }
            System.out.println("MIA_T4_CLIPPED cells="+(65*49*51)+" cubes="+parallel.cubes().size()+" reused="+reused.timings().reusedDensityCells());
            h.succeed();
        } finally {serialPool.shutdownNow();parallelPool.shutdownNow();}
    }
    private static void equal(GameTestHelper h,GeneratedNativeLightSolver.Result a,GeneratedNativeLightSolver.Result b) {
        h.assertTrue(Arrays.equals(a.sky().lowerSnapshot(),b.sky().lowerSnapshot())&&Arrays.equals(a.sky().upperSnapshot(),b.sky().upperSnapshot()),"All exact SKY bounds are identical");
        h.assertTrue(Arrays.equals(a.block().lowerSnapshot(),b.block().lowerSnapshot())&&Arrays.equals(a.block().upperSnapshot(),b.block().upperSnapshot()),"All exact BLOCK bounds are identical");
        h.assertTrue(a.cubes().keySet().equals(b.cubes().keySet()),"Only complete cubes are published");
        for(var key:a.cubes().keySet())h.assertTrue(Arrays.equals(a.cubes().get(key),b.cubes().get(key)),"Full-state geometry stays exact");
        var bounds=a.sky().bounds();
        for(int i=0;i<a.exposure().length;i++) {
            var x=a.exposure()[i];var y=b.exposure()[i];h.assertTrue(x.rainHeight()==y.rainHeight(),"Rain authority stays exact");
            for(int yy=bounds.minY();yy<bounds.minY()+bounds.sizeY();yy++)h.assertTrue(x.directSky(yy)==y.directSky(yy),"Every exact column seed stays equal");
        }
    }
}
