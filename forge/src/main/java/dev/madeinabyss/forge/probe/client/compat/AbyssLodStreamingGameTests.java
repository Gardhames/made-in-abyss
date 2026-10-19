package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.*;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Required convergence regressions; a small aggregate budget must not prohibit a finite view. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodStreamingGameTests {
    @GameTest(template="empty",timeoutTicks=300)
    public static void finiteNegativeWindowCompletesBeyondOneSourceQuantum(GameTestHelper h)throws Exception {
        var input=input(8192);
        try(var pipeline=new AbyssLodPipeline(true)){
            pipeline.request(input);var terminal=complete(pipeline,8);
            h.assertTrue(terminal.requestedComplete(),"Finite exact scene completes after many reused source reservations");
            h.assertTrue(((Number)pipeline.status().get("actualFreshSamples")).longValue()>8192,"Work exceeds one source quantum without lowering detail");
            h.assertTrue(((Number)pipeline.status().get("freshSampleReserve")).longValue()==0,"No source reservation remains after completion");
            h.assertTrue(((Number)pipeline.status().get("peakSourceReserve")).longValue()<=8192,"Concurrent source leases never exceed the unchanged quantum cap");
            long scratch=((Number)pipeline.status().get("maximumFineScratchBytes")).longValue();
            h.assertTrue(scratch>0&&scratch<=input.limits().maxLedgerBytes(),"Retained results plus executing worst-case reservations stay within the 1 MiB scratch cap: "+scratch);
            h.assertTrue(((Number)pipeline.status().get("deferredSourceJobs")).intValue()==0,"No source job silently deferred");
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void canceledSourceReleasesItsExecutingLease(GameTestHelper h)throws Exception {
        var original=input(8192);var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var input=new AbyssLodPipeline.Input(original.target(),original.camera(),original.nearBounds(),original.worldBounds(),original.bands(),List.of(),
            (x,y,z)->{entered.countDown();try{release.await();}catch(InterruptedException canceled){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Controlled source canceled");}
                return original.terrain().sample(x,y,z);},original.lighting(),original.limits());
        try(var pipeline=new AbyssLodPipeline(true)){
            pipeline.request(input);h.assertTrue(entered.await(3,TimeUnit.SECONDS),"An executing source really holds the lease");
            h.assertTrue(((Number)pipeline.status().get("freshSampleReserve")).longValue()>0,"The active job reserves before sampling");
            pipeline.close();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(((Number)pipeline.status().get("freshSampleReserve")).longValue()!=0&&System.nanoTime()<until)Thread.sleep(2);
            h.assertTrue(((Number)pipeline.status().get("freshSampleReserve")).longValue()==0,"Canceled execution releases exactly its own reservation");
        }finally{release.countDown();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void identicalCompletedRequestKeepsTerminalTruthAndExactPages(GameTestHelper h)throws Exception {
        var input=input(1_000_000);
        try(var pipeline=new AbyssLodPipeline(true)){
            pipeline.request(input);var first=complete(pipeline,8);
            var expected=LodSpeedGameTests.oracle(first);
            pipeline.request(input);var repeated=complete(pipeline,2,first);
            h.assertTrue(Boolean.TRUE.equals(pipeline.status().get("completed")),"Identical request must not erase a successfully acknowledged completion");
            h.assertTrue(expected.equals(LodSpeedGameTests.oracle(repeated)),"No geometry, exact light, ordering or fine-grid change on identical input");
        }
        h.succeed();
    }
    static AbyssLodPipeline.Input input(long budget){
        var near=new TerrainLodColumns.Bounds(-64,-16,-16,-49,-1,-1);
        var world=new TerrainLodColumns.Bounds(-64,-16,-16,63,-1,-1);
        return new AbyssLodPipeline.Input(new AbyssLodPipeline.Target("stream-test",1,"non-disk-source",0,0,0,1),
                new AbyssLodPipeline.Camera(-64,-8,-8,1,0,0),near,world,List.of(new TerrainLodRefinement.Band(256,1)),List.of(),
                (x,y,z)->new TerrainSample.Known(y==-8?TerrainMaterial.ROCK:TerrainMaterial.AIR),box->new AbyssLodPipeline.Light(7,0),
                new AbyssLodPipeline.Limits(budget,20_000,80_000,1024L*1024,128,1));
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void residentSceneRefusalCannotRetireQualityAndClaimComplete(GameTestHelper h)throws Exception {
        var base=input(1_000_000);
        var input=new AbyssLodPipeline.Input(base.target(),base.camera(),base.nearBounds(),base.worldBounds(),base.bands(),List.of(),
            (x,y,z)->new TerrainSample.Known(y>=-4?TerrainMaterial.AIR:TerrainMaterial.ROCK),base.lighting(),
            new AbyssLodPipeline.Limits(1_000_000,300,300,8L*1024*1024,128,1));
        try(var pipeline=new AbyssLodPipeline(true)){
            pipeline.request(input);long acknowledged=0;
            for(int attempt=0;attempt<2;attempt++){
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean refused=false;
                while(System.nanoTime()<until){var proposal=pipeline.poll();if(proposal.isPresent()){
                        h.assertTrue(!proposal.get().requestedComplete(),"Insufficient resident cap cannot produce a quality-reduced terminal proposal");
                        h.assertTrue(pipeline.acknowledge(proposal.get(),true),"Earlier exact partial scene remains valid");acknowledged=proposal.get().sequence();}
                    var status=pipeline.status();if(!status.get("error").equals("")){refused=true;break;}Thread.sleep(2);}
                h.assertTrue(refused&&acknowledged>0,"Real scene cap refuses after a positive exact prefix was acknowledged: "+pipeline.status());
                h.assertTrue(((Number)pipeline.status().get("plannedFineJobs")).intValue()>1,"A witnessed AIR/ROCK surface creates a real refinement plan beyond the initial grid");
                h.assertTrue(!Boolean.TRUE.equals(pipeline.status().get("completed")),"A refused resident scene cannot be complete on retry");
                if(attempt==0)pipeline.request(input);
            }
        }
        h.succeed();
    }
    static AbyssLodPipeline.Proposal complete(AbyssLodPipeline pipeline,int seconds)throws Exception {return complete(pipeline,seconds,null);}
    static AbyssLodPipeline.Proposal complete(AbyssLodPipeline pipeline,int seconds,AbyssLodPipeline.Proposal previous)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);var last=previous;
        while(System.nanoTime()<until){var p=pipeline.poll();if(p.isPresent()){last=p.get();if(!pipeline.acknowledge(last,true))throw new GameTestAssertException("CPU ACK refused");}
            var status=pipeline.status();if(!status.get("error").equals(""))throw new GameTestAssertException(status.toString());
            if(Boolean.TRUE.equals(status.get("completed"))&&last!=null)return last;
            Thread.sleep(2);
        }
        throw new GameTestAssertException("Finite source did not complete: "+pipeline.status());
    }
}
