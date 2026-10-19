package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real CPU-pipeline ownership/retarget oracle; WORLD ACK here is simulated, not GPU proof. */
@GameTestHolder("madeinabyss")
@PrefixGameTestTemplate(false)
public final class AbyssLodIncrementalPagesGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void localNativeCutUpdatesExactOwnersWithoutGlobalGeometryOrRootIndexRebuild(GameTestHelper h)throws Exception {
        var near=new TerrainLodColumns.Bounds(-16,0,-16,15,15,15);
        var world=new TerrainLodColumns.Bounds(-128,0,-128,127,15,127);
        var bands=List.of(new TerrainLodRefinement.Band(64,1),new TerrainLodRefinement.Band(128,2),new TerrainLodRefinement.Band(192,4));
        try(var pipeline=new AbyssLodPipeline(true)){
            var target=new AbyssLodPipeline.Target("incremental-oracle",1,"incremental-exact-boxes",1,1,1,1);
            pipeline.request(input(target,near,world,bands,List.of()));
            AbyssLodPipeline.Proposal before=null;
            for(int i=0;i<128;i++){before=await(pipeline);h.assertTrue(pipeline.acknowledge(before,true),"Actual current positive CPU WORLD simulation accepted");if(before.requestedComplete())break;}
            h.assertTrue(before!=null&&before.requestedComplete(),"Finite source fixture fully acquired and lit before local-delta measurement");
            long globalBefore=number(pipeline.status(),"surfaceGlobalGeometryBuilds","renderSurfaceBuilds");
            h.assertTrue(globalBefore==0,"Production surface pipeline uses persistent dirty-page extraction, not global geometry()");
            long rootsBefore=number(pipeline.status(),"incrementalRootIndexBuilds",null);
            var oldPages=new HashMap<SurfaceQuadPlan.PageKey,SurfaceQuadPlan.Prepared>();
            for(var p:before.surfacePages().pages())oldPages.put(p.key(),p.prepared());
            var hole=new TerrainLodClipper.BoundsLong(1,0,1,9,16,9);
            var next=new AbyssLodPipeline.Target("incremental-oracle",1,"incremental-exact-boxes",1,2,1,2);
            pipeline.request(input(next,near,world,bands,List.of(hole)));
            AbyssLodPipeline.Proposal after=null;
            for(int i=0;i<128;i++){after=await(pipeline);h.assertTrue(after.target().equals(next),"Every local-delta proposal belongs to current native target");
                for(var box:after.boxes())h.assertTrue(box.light().equals(light(box.box())),"Every submitted original physical BOX retains exact coordinate-dependent scalar");
                h.assertTrue(pipeline.acknowledge(after,true),"Local dirty publication requires current positive CPU WORLD simulation");if(after.requestedComplete())break;}
            h.assertTrue(after!=null&&after.requestedComplete(),"Local cut delta drains without restarting source acquisition");
            h.assertTrue(number(pipeline.status(),"surfaceGlobalGeometryBuilds","renderSurfaceBuilds")==globalBefore,"No global geometry rebuild after one local native-cut delta");
            h.assertTrue(number(pipeline.status(),"incrementalRootIndexBuilds",null)==rootsBefore,"Original FAR-root index is persistent through a native-cut delta");
            int reused=0;for(var p:after.surfacePages().pages()){
                if(oldPages.get(p.key())==p.prepared())reused++;
                for(var owner:p.prepared().owners()){var expected=light(owner.box());h.assertTrue(owner.sky()==expected.sky()&&owner.block()==expected.block(),"Dirty pages preserve whole-owner physical lighting");}
            }
            h.assertTrue(reused>0,"An unrelated exact Prepared page survives local delta by identity");
        }
        h.succeed();
    }
    private static AbyssLodPipeline.Input input(AbyssLodPipeline.Target target,TerrainLodColumns.Bounds near,TerrainLodColumns.Bounds world,List<TerrainLodRefinement.Band> bands,List<TerrainLodClipper.BoundsLong> cuts){
        return new AbyssLodPipeline.Input(target,new AbyssLodPipeline.Camera(0,10,0),near,world,bands,cuts,
                (x,y,z)->new dev.madeinabyss.kernel.terrain.TerrainSample.Known(y<=8?TerrainMaterial.ROCK:TerrainMaterial.AIR),AbyssLodIncrementalPagesGameTests::light,AbyssLodPipeline.Limits.defaults());
    }
    private static AbyssLodPipeline.Light light(TerrainLodColumns.Box b){return new AbyssLodPipeline.Light(Math.floorMod(b.minX()*3+b.minY()*5+b.minZ()*7,16),0);}
    private static long number(Map<String,Object> status,String key,String fallback){var value=status.get(key);if(!(value instanceof Number)&&fallback!=null)value=status.get(fallback);return value instanceof Number n?n.longValue():-1;}
    private static AbyssLodPipeline.Proposal await(AbyssLodPipeline pipeline)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<deadline){var next=pipeline.poll();if(next.isPresent())return next.orElseThrow();if(!String.valueOf(pipeline.status().get("error")).isEmpty())throw new AssertionError(pipeline.status());Thread.sleep(2);}throw new AssertionError("Bounded actual CPU proposal timeout: "+pipeline.status());
    }
}
