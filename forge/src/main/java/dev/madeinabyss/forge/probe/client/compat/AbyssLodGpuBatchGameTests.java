package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;

/** Batch transport must keep scalar/archive/completion semantics; CPU-only callbacks stay scalar. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodGpuBatchGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void exactBatchTransportKeepsNegativeOwnerScalarsAndCompletion(GameTestHelper h)throws Exception{
        verifyBatch();h.succeed();
    }
    public static void verifyBatch()throws Exception{
        var calls=new AtomicInteger();var base=AbyssLodStreamingGameTests.input(1_000_000);
        AbyssLodPipeline.BoxLighting light=new AbyssLodPipeline.BoxLighting(){
            public AbyssLodPipeline.Light light(TerrainLodColumns.Box box){return expected(box);}
            public boolean batchLightEnabled(){return true;}
            public List<AbyssLodPipeline.Light> lightBatch(List<TerrainLodColumns.Box> owners,Runnable checkpoint){calls.incrementAndGet();checkpoint.run();return owners.stream().map(AbyssLodGpuBatchGameTests::expected).toList();}
        };
        var input=new AbyssLodPipeline.Input(base.target(),base.camera(),base.nearBounds(),base.worldBounds(),base.bands(),base.nativeCuts(),base.terrain(),light,base.limits());
        try(var pipeline=new AbyssLodPipeline(true)){
            pipeline.request(input);var terminal=AbyssLodStreamingGameTests.complete(pipeline,12);
            if(calls.get()==0)throw new AssertionError("Exact scheduler must call the batch transport");
            if(!terminal.exactLightComplete())throw new AssertionError("Completion stays exact");
            for(var value:terminal.boxes())if(!value.light().equals(expected(value.box())))throw new AssertionError("Owner/result order swap");
            int checked=0;if(terminal.surfacePages()!=null)for(var page:terminal.surfacePages().pages())for(var owner:page.prepared().owners()){
                var wanted=expected(owner.box());if(owner.sky()!=wanted.sky()||owner.block()!=wanted.block())throw new AssertionError("Surface owner/result order swap");checked++;}
            if(checked==0)throw new AssertionError("No terminal surface owners were checked");
            if(((Number)pipeline.status().get("actualLightJobs")).longValue()<=0)throw new AssertionError("Batch results must count as exact evaluations");
        }
    }
    private static AbyssLodPipeline.Light expected(TerrainLodColumns.Box box){return new AbyssLodPipeline.Light((int)Math.floorMod(box.minX()+box.minY()+box.minZ(),16),3);}
}
