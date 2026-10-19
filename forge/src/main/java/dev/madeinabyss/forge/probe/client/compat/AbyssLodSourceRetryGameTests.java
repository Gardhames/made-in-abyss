package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A refused source batch remains required work when the same display request is retried. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodSourceRetryGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void sameWindowRetryCannotSkipFailedFineSourceBatch(GameTestHelper h)throws Exception {
        var clean=AbyssLodStreamingGameTests.input(1_000_000);
        com.google.gson.JsonObject expected;
        try(var reference=new AbyssLodPipeline(true)){
            reference.request(clean);
            expected=LodSpeedGameTests.oracle(AbyssLodStreamingGameTests.complete(reference,8));
        }
        var armed=new AtomicBoolean();var failures=new AtomicInteger();
        var retried=new AbyssLodPipeline.Input(clean.target(),clean.camera(),clean.nearBounds(),clean.worldBounds(),
            clean.bands(),clean.nativeCuts(),(x,y,z)->{
                // Canonical step16 samples use x==7(mod16), step4 uses x==1(mod4).
                // Only an exact fine lattice can trigger this one-shot source refusal.
                if(Math.floorMod(x,4)==2&&armed.compareAndSet(true,false)){
                    failures.incrementAndGet();
                    throw new TerrainLodColumns.BudgetExceededException("Controlled fine source refusal");
                }
                return clean.terrain().sample(x,y,z);
            },clean.lighting(),clean.limits());
        try(var pipeline=new AbyssLodPipeline(true)){
            pipeline.request(retried);var first=nextProposal(pipeline,8);
            h.assertTrue(first.stage()==AbyssLodPipeline.Stage.NEAR_FINE&&first.farStep()==0,
                "Fault stays disabled until the exact positive startup prefix is ready");
            armed.set(true);
            h.assertTrue(pipeline.acknowledge(first,true),"The first exact fine prefix receives its current CPU WORLD ACK");
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);String error="";
            while(System.nanoTime()<until){
                var proposal=pipeline.poll();
                if(proposal.isPresent())h.assertTrue(pipeline.acknowledge(proposal.orElseThrow(),true),"Current intermediate CPU WORLD ACK succeeds");
                error=String.valueOf(pipeline.status().get("error"));if(!error.isEmpty())break;
                Thread.sleep(2);
            }
            h.assertTrue(failures.get()==1&&error.contains("Controlled fine source refusal"),
                "Exactly one real fine-source batch must fail: "+pipeline.status());
            h.assertTrue(((Number)pipeline.status().get("farRawBoxes")).intValue()>0,
                "FAR has already been adopted; retry cannot accidentally rebuild the plan and reset its cursor");
            h.assertTrue(!Boolean.TRUE.equals(pipeline.status().get("completed")),"A refused fine batch cannot be COMPLETE");
            pipeline.request(retried);
            var terminal=AbyssLodStreamingGameTests.complete(pipeline,8);
            h.assertTrue(failures.get()==1,"The retry reaches the unchanged source without a second injected failure");
            h.assertTrue(expected.equals(LodSpeedGameTests.oracle(terminal)),
                "Same-window retry must retain every fine grid and exactly match clean page geometry, light and render order");
        }
        h.succeed();
    }
    private static AbyssLodPipeline.Proposal nextProposal(AbyssLodPipeline pipeline,int seconds)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<until){
            var proposal=pipeline.poll();if(proposal.isPresent())return proposal.orElseThrow();
            if(!String.valueOf(pipeline.status().get("error")).isEmpty())throw new GameTestAssertException(pipeline.status().toString());
            Thread.sleep(2);
        }
        throw new GameTestAssertException("Initial exact fine proposal did not arrive: "+pipeline.status());
    }
}
