package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.client.compat.AbyssLodPipeline;
import dev.madeinabyss.forge.probe.client.compat.AbyssNativeDensity;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.VoxelSkyLight;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Production coordinator checks: importing completed native data is not a display request. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodCacheInputGameTests {
    private static AbyssLodPipeline.Input initial() {
        var target=new AbyssLodPipeline.Target("native-cache-check",1,"exact-cache-source",2,7,3,11);
        var bounds=new TerrainLodColumns.Bounds(-48,-48,-32,47,15,63);
        return new AbyssLodPipeline.Input(target,new AbyssLodPipeline.Camera(-4,-32,4,-1,0,0),bounds,bounds,
                List.of(new TerrainLodRefinement.Band(128,1)),List.of(),
                (x,y,z)->new TerrainSample.Known(x<-16?TerrainMaterial.ROCK:TerrainMaterial.AIR),
                box->new AbyssLodPipeline.Light(12,5),new AbyssLodPipeline.Limits(2_000_000,20_000,80_000,8L*1024*1024,128,8));
    }
    private static AbyssNativeDensity.Snapshot nativeData(AbyssLodPipeline.Input input) {
        int[] states=new int[4096];Arrays.fill(states,Block.getId(Blocks.STONE.defaultBlockState()));
        var data=AbyssNativeDensity.Data.fromOwned(new VoxelSkyLight.Bounds(-32,-48,0,16,16,16),Map.of(new CubeKey(-2,-3,0),states));
        return new AbyssNativeDensity.Snapshot(data,new AbyssNativeDensity.Authority(input.target().sourceIdentity(),
                input.target().sourceRevision(),4,input.target().nativeRevision()),2,
                id->id==Block.getId(Blocks.AIR.defaultBlockState())?TerrainMaterial.AIR:TerrainMaterial.ROCK);
    }
    private static AbyssLodPipeline.Input cacheInput(AbyssLodPipeline.Input original,List<TerrainLodClipper.BoundsLong> cuts) {
        return new AbyssLodPipeline.Input(original.target(),original.camera(),original.nearBounds(),original.worldBounds(),
                original.bands(),cuts,original.terrain(),original.lighting(),original.limits(),nativeData(original));
    }
    private static AbyssLodPipeline.Proposal await(AbyssLodPipeline pipeline)throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline){var result=pipeline.poll();if(result.isPresent())return result.orElseThrow();Thread.sleep(2);}
        throw new AssertionError("Actual coordinator did not prepare the bounded positive wall");
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void completedNativeCacheDoesNotDiscardPendingWorldProposal(GameTestHelper h)throws Exception {
        try(var pipeline=new AbyssLodPipeline()) {
            var original=initial();pipeline.request(original);var first=await(pipeline);
            h.assertTrue(!first.boxes().isEmpty(),"Control actually prepares positive source geometry");
            pipeline.updateNativeCache(cacheInput(original,original.nativeCuts()));
            h.assertTrue(pipeline.poll().orElse(null)==first,"Cache-only native publication must preserve the exact pending proposal");
            h.assertTrue(!pipeline.acknowledge(first,false),"A retained cache event still cannot acknowledge hidden uploads");
            h.assertTrue(pipeline.acknowledge(first,true),"The same current proposal still accepts its actual WORLD acknowledgment");
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void metadataUpdateRejectsChangedNativeHoleWithoutDiscardingPendingProposal(GameTestHelper h)throws Exception {
        try(var pipeline=new AbyssLodPipeline()) {
            var original=initial();pipeline.request(original);var first=await(pipeline);
            boolean refused=false;
            try{pipeline.updateNativeCache(cacheInput(original,List.of(new TerrainLodClipper.BoundsLong(-32,-48,0,-16,-32,16))));}
            catch(IllegalArgumentException expected){refused=true;}
            h.assertTrue(refused,"Changed real native coverage requires a render request, never the cache-only path");
            h.assertTrue(pipeline.poll().orElse(null)==first,"Refused metadata cannot discard the acknowledged source candidate");
        }
        h.succeed();
    }
}
