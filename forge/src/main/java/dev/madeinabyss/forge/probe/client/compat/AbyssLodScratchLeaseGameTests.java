package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Cancellation retires a Future before an uncooperative source callback actually exits. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodScratchLeaseGameTests {
    @GameTest(template="empty",timeoutTicks=800)
    public static void canceledExecutingSourceKeepsScratchChargedAcrossSameSourceRetry(GameTestHelper h)throws Exception {
        var base=AbyssLodStreamingGameTests.input(1_000_000);
        long cap=base.limits().maxLedgerBytes(),heldBytes=128+80L*16*16*16;
        var armed=new AtomicBoolean();var retrying=new AtomicBoolean();var roles=new AtomicInteger();
        var held=new CountDownLatch(1);var canceled=new CountDownLatch(1);
        var release=new CountDownLatch(1);var retryEntered=new CountDownLatch(1);
        var input=new AbyssLodPipeline.Input(base.target(),base.camera(),base.nearBounds(),base.worldBounds(),
            base.bands(),base.nativeCuts(),(x,y,z)->{
                // Once per exact16m fine tile, excluding the step4 and step16 sample lattices.
                if(armed.get()&&Math.floorMod(x,16)==2&&y==-16&&z==-16){
                    if(retrying.get())retryEntered.countDown();
                    int role=roles.getAndIncrement();
                    if(role==0){
                        held.countDown();boolean interrupted=false;
                        for(;;)try{release.await();break;}catch(InterruptedException ignored){interrupted=true;canceled.countDown();}
                        if(interrupted)Thread.currentThread().interrupt();
                    }else if(role==1){
                        try{if(!held.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Held sibling did not enter source");}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Refusal sibling interrupted");}
                        throw new TerrainLodColumns.BudgetExceededException("Controlled scratch sibling refusal");
                    }
                }
                return base.terrain().sample(x,y,z);
            },base.lighting(),base.limits());
        try(var pipeline=new AbyssLodPipeline(true)){
            pipeline.request(input);var first=firstProposal(pipeline);
            h.assertTrue(first.stage()==AbyssLodPipeline.Stage.NEAR_FINE&&first.farStep()==0,
                "Controlled concurrency starts only after the initial exact prefix");
            armed.set(true);h.assertTrue(pipeline.acknowledge(first,true),"Initial exact CPU WORLD ACK succeeds");
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);String error="";
            while(System.nanoTime()<until){
                var proposal=pipeline.poll();if(proposal.isPresent())h.assertTrue(pipeline.acknowledge(proposal.orElseThrow(),true),"Intermediate CPU WORLD ACK succeeds");
                var status=pipeline.status();assertCap(h,status,cap);
                error=String.valueOf(status.get("error"));if(!error.isEmpty())break;
                Thread.sleep(2);
            }
            h.assertTrue(error.contains("Controlled scratch sibling refusal"),"A real sibling failure cancels the held worker: "+pipeline.status());
            h.assertTrue(canceled.await(3,TimeUnit.SECONDS),"The held callback observes cancellation and deliberately remains executing");
            var failed=pipeline.status();assertCap(h,failed,cap);
            h.assertTrue(number(failed,"farRawBoxes")>0,"FAR is already adopted, preserving the same source-job window on retry");
            h.assertTrue(number(failed,"liveFineScratchBytes")>=heldBytes,"Future cancellation cannot release the executing fine builder's reservation");
            retrying.set(true);pipeline.request(input);
            h.assertTrue(retryEntered.await(3,TimeUnit.SECONDS),"Same-source retry actually enters fresh source work while the old callback remains held");
            until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(200);
            do{
                // Leave any staged proposal unacknowledged until the held callback is released.
                pipeline.poll();var status=pipeline.status();assertCap(h,status,cap);
                h.assertTrue(String.valueOf(status.get("error")).isEmpty(),"Retry has no second injected source failure");
                h.assertTrue(number(status,"liveFineScratchBytes")>=heldBytes,"The old executing lease survives every retry observation");
                Thread.sleep(2);
            }while(System.nanoTime()<until);
            release.countDown();var terminal=AbyssLodStreamingGameTests.complete(pipeline,8);
            h.assertTrue(terminal.requestedComplete(),"Releasing the canceled callback permits exact source completion");
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(number(pipeline.status(),"liveFineScratchBytes")!=0&&System.nanoTime()<until)Thread.sleep(2);
            var completed=pipeline.status();assertCap(h,completed,cap);
            h.assertTrue(number(completed,"liveFineScratchBytes")==0,"All running, queued and retained-result scratch leases return to zero");
        }finally{release.countDown();}
        h.succeed();
    }
    private static void assertCap(GameTestHelper h,Map<String,Object> status,long cap){
        long live=number(status,"liveFineScratchBytes"),peak=number(status,"peakFineScratchLeases");
        h.assertTrue(live>=0&&live<=cap&&peak<=cap,"Live and peak fine scratch, including canceled builders, obey the 1 MiB cap: "+status);
    }
    private static long number(Map<String,Object> status,String key){return ((Number)status.get(key)).longValue();}
    private static AbyssLodPipeline.Proposal firstProposal(AbyssLodPipeline pipeline)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<until){
            var proposal=pipeline.poll();if(proposal.isPresent())return proposal.orElseThrow();
            if(!String.valueOf(pipeline.status().get("error")).isEmpty())throw new GameTestAssertException(pipeline.status().toString());
            Thread.sleep(2);
        }
        throw new GameTestAssertException("Initial exact fine proposal did not arrive: "+pipeline.status());
    }
}
