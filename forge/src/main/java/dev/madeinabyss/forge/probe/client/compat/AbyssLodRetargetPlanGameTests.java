package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/** A settled retarget must select exactly the fresh view, without rewriting acknowledged history. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodRetargetPlanGameTests {
    private static final TrailNetwork.SampleSource PLANE=(x,y,z)->new TerrainSample.Known(y==-8?TerrainMaterial.ROCK:TerrainMaterial.AIR);
    private static final AbyssLodPipeline.BoxLighting LIGHT=box->new AbyssLodPipeline.Light((int)Math.floorMod(box.minX()+box.minZ(),16),3);

    @GameTest(template="empty",timeoutTicks=1800)
    public static void fractionalNegativeAndBoundaryMovesMatchFreshTerminalPages(GameTestHelper h)throws Exception {
        var cameras=List.of(new AbyssLodPipeline.Camera(-64.25,-8,-64.25,1,0,0),
            new AbyssLodPipeline.Camera(-63.75,-8,-63.75,1,0,0),new AbyssLodPipeline.Camera(-33,-8,-33,1,0,0),
            new AbyssLodPipeline.Camera(-.25,-8,-.25,1,0,0),new AbyssLodPipeline.Camera(.25,-8,.25,1,0,0),
            new AbyssLodPipeline.Camera(15.75,-8,15.75,1,0,0),new AbyssLodPipeline.Camera(31,-8,31,1,0,0),
            new AbyssLodPipeline.Camera(63.75,-8,63.75,1,0,0),new AbyssLodPipeline.Camera(64.25,-8,64.25,1,0,0));
        try(var warm=new AbyssLodPipeline(true)) {
            AbyssLodPipeline.Proposal last=null;
            for(int index=0;index<cameras.size();index++) {
                var input=plane(cameras.get(index),index+1,0);
                com.google.gson.JsonObject expected;
                try(var fresh=new AbyssLodPipeline(true)) {fresh.request(input);expected=LodSpeedGameTests.oracle(AbyssLodStreamingGameTests.complete(fresh,12));}
                var previous=displayed(warm);warm.request(input);
                var first=next(warm,12);
                if(last!=null)h.assertTrue(displayed(warm)==previous,"Preparing the new canonical plan cannot mutate the old WORLD ledger before ACK");
                h.assertTrue(warm.acknowledge(first,true),"Current retarget WORLD ACK");
                last=AbyssLodStreamingGameTests.complete(warm,12,first);
                h.assertTrue(expected.equals(LodSpeedGameTests.oracle(last)),"All four terminal hashes match fresh at exact camera "+cameras.get(index));
                h.assertTrue(((Number)warm.status().get("liveFineScratchBytes")).longValue()==0,"No completed retarget retains prefetch scratch");
            }
            // Native/resource revisions at a stationary exact view do not rebuild its source plan.
            var plan=field(warm,"context","jobs");long samples=((Number)warm.status().get("actualFreshSamples")).longValue();
            var expected=LodSpeedGameTests.oracle(last);warm.request(plane(cameras.get(cameras.size()-1),100,1));
            var repeated=AbyssLodStreamingGameTests.complete(warm,12);
            h.assertTrue(field(warm,"context","jobs")==plan,"A stationary native-revision retarget reuses the immutable source plan");
            h.assertTrue(((Number)warm.status().get("actualFreshSamples")).longValue()==samples,"Stationary native revision performs no fresh source sampling");
            h.assertTrue(expected.equals(LodSpeedGameTests.oracle(repeated)),"Stationary native revision preserves all four terminal hashes");
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=900)
    public static void stationaryRotationReplacesOnlyTheFreshWitnessPlan(GameTestHelper h)throws Exception {
        var world=new TerrainLodColumns.Bounds(-128,-16,-64,127,-1,63);
        var near=new TerrainLodColumns.Bounds(-64,-16,-64,63,-1,63);
        var bands=List.of(new TerrainLodRefinement.Band(16,1),new TerrainLodRefinement.Band(32,2),new TerrainLodRefinement.Band(128,4));
        TrailNetwork.SampleSource walls=(x,y,z)->new TerrainSample.Known(x>=80&&x<96||x>=-96&&x<-80?TerrainMaterial.ROCK:TerrainMaterial.AIR);
        var first=new AbyssLodPipeline.Input(target(1,0),new AbyssLodPipeline.Camera(0,-8,0,1,0,0),near,world,bands,List.of(),walls,LIGHT,limits());
        var turned=new AbyssLodPipeline.Input(target(2,0),new AbyssLodPipeline.Camera(0,-8,0,-1,0,0),near,world,bands,List.of(),walls,LIGHT,limits());
        com.google.gson.JsonObject expected;
        try(var fresh=new AbyssLodPipeline(true)){fresh.request(turned);var terminal=AbyssLodStreamingGameTests.complete(fresh,12);
            h.assertTrue(terminal.fine().stream().anyMatch(g->g.step()==1&&g.bounds().minX()<=-80),"Fresh reverse view has a real1m remote witness");expected=LodSpeedGameTests.oracle(terminal);}
        try(var warm=new AbyssLodPipeline(true)){warm.request(first);var original=AbyssLodStreamingGameTests.complete(warm,12);
            h.assertTrue(original.fine().stream().anyMatch(g->g.step()==1&&g.bounds().minX()>=80),"Original view has the opposite real1m remote witness");
            var previous=displayed(warm);warm.request(turned);var pending=next(warm,12);
            h.assertTrue(displayed(warm)==previous,"Old witness remains WORLD-acknowledged until replacement ACK");
            h.assertTrue(warm.acknowledge(pending,true),"Turned current WORLD ACK");var terminal=AbyssLodStreamingGameTests.complete(warm,12,pending);
            h.assertTrue(expected.equals(LodSpeedGameTests.oracle(terminal)),"Stationary rotation matches all four fresh terminal hashes, including witness provenance");}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=900)
    public static void delayedFarCannotMakeClippedAcquisitionHistoryTerminal(GameTestHelper h)throws Exception {
        var base=plane(new AbyssLodPipeline.Camera(-64.25,-8,-64.25,1,0,0),1,0);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var armed=new AtomicBoolean(true);
        var held=new AbyssLodPipeline.Input(base.target(),base.camera(),base.nearBounds(),base.worldBounds(),base.bands(),List.of(),
            (x,y,z)->{if(x==-185&&z==-185&&armed.compareAndSet(true,false)){
                entered.countDown();try{release.await();}catch(InterruptedException canceled){Thread.currentThread().interrupt();throw new CancellationException("Controlled FAR canceled");}
            }return PLANE.sample(x,y,z);},LIGHT,limits());
        com.google.gson.JsonObject expected;
        try(var fresh=new AbyssLodPipeline(true)){fresh.request(base);expected=LodSpeedGameTests.oracle(AbyssLodStreamingGameTests.complete(fresh,12));}
        // Stored before the rejected same-step fragment retirement: local source had
        // completed before FAR. Preserve its exact BOX/light/render partition.
        var original=Map.of("pagesSha256","0d5a87e9330b45d85b6aa94de0513a8e6f52934608dd93106a0a7080d543f583",
            "ownersSha256","df53b604a0e3fcae686210d4ab6653a10ad420a41ed45beecb6c50c2b3e336b1",
            "orderedRenderSha256","c31830db6c6799503ae4978ee248145d9ba7cf651fd4512d9c7b6821340d6045",
            "fineGridsSha256","709954e0994c2c386d93c27d0c85e19700b82d0c0fd9930c458efe6e74add2bf");
        for(var digest:original.entrySet())h.assertTrue(expected.get(digest.getKey()).getAsString().equals(digest.getValue()),
            "Fresh acquisition preserves the original exact fragment baseline: "+digest.getKey());
        try(var pipeline=new AbyssLodPipeline(true)){
            pipeline.request(held);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);boolean partial=false,finished=false;
            while(System.nanoTime()<until){var next=pipeline.poll();if(next.isPresent()){
                    var proposal=next.get();partial|=proposal.fine().stream().anyMatch(g->g.bounds().maxXInclusive()-g.bounds().minX()!=15||g.bounds().maxZInclusive()-g.bounds().minZ()!=15);
                    h.assertTrue(!proposal.requestedComplete(),"Held FAR cannot produce a terminal scene");h.assertTrue(pipeline.acknowledge(proposal,true),"Exact local fragment WORLD ACK");}
                h.assertTrue(pipeline.status().get("error").equals(""),"No local source error: "+pipeline.status());
                finished=entered.getCount()==0&&!Boolean.TRUE.equals(pipeline.status().get("fineSourcePending"))
                    &&((Number)field(pipeline,"context","fineCursor")).intValue()==((List<?>)field(pipeline,"context","jobs")).size();
                if(partial&&finished)break;Thread.sleep(2);}
            h.assertTrue(partial&&finished&&release.getCount()==1,"Real clipped local cells publish and finish before the controlled FAR adoption");
            release.countDown();var terminal=AbyssLodStreamingGameTests.complete(pipeline,12);
            h.assertTrue(terminal.fine().size()==252&&terminal.fine().stream().anyMatch(g->g.bounds().maxXInclusive()-g.bounds().minX()!=15||g.bounds().maxZInclusive()-g.bounds().minZ()!=15),
                "Canonical initial acquisition fragments preserve the original 252-grid exact BOX ownership");
            h.assertTrue(expected.equals(LodSpeedGameTests.oracle(terminal)),"Delayed FAR and normal cold schedule produce the same four terminal hashes");
        }finally{release.countDown();}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void cachedFarCannotSkipCompleteCellsWitnessedByTheInitialLocalLattice(GameTestHelper h)throws Exception {
        var entered=new CountDownLatch(1);var releaseFar=new CountDownLatch(1);var armed=new AtomicBoolean(true);
        TrailNetwork.SampleSource terrain=(x,y,z)->{
            if(x==-89&&z==-89&&armed.compareAndSet(true,false)){
                entered.countDown();try{releaseFar.await();}catch(InterruptedException canceled){Thread.currentThread().interrupt();throw new CancellationException();}}
            // The shifted local16m midpoints include x=1/33,z=1; the full-world
            // FAR midpoints miss both real columns. Their cells are complete, not fragments.
            return new TerrainSample.Known(y==-8||((x==1||x==33)&&z==1&&y>=-100&&y<=-81)?TerrainMaterial.ROCK:TerrainMaterial.AIR);
        };
        var input=new AbyssLodPipeline.Input(new AbyssLodPipeline.Target("initial-plan",1,"controlled-index-lattice",0,0,0,1),
            new AbyssLodPipeline.Camera(0,-90,0,1,0,0),new TerrainLodColumns.Bounds(-54,-128,-54,73,-1,73),
            new TerrainLodColumns.Bounds(-96,-128,-96,95,-1,95),List.of(new TerrainLodRefinement.Band(256,1)),List.of(),terrain,
            box->new AbyssLodPipeline.Light((int)Math.floorMod(box.minX()+box.minY()+box.minZ(),16),0),
            new AbyssLodPipeline.Limits(10_000_000,100_000,400_000,8L*1024*1024,10_000,1));
        var parent=Path.of(System.getProperty("mia.storageRoot", ".local"),"codex","20261013-lod-speed","stage-d","retarget-tests");
        Files.createDirectories(parent);var cache=Files.createTempDirectory(parent,"initial-full-plan-");
        com.google.gson.JsonObject expected;
        try(var cold=new AbyssLodPipeline(true,true,cache)) {
            cold.request(input);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);boolean local=false;
            while(System.nanoTime()<until){ackIfReady(h,cold);
                local=entered.getCount()==0&&!Boolean.TRUE.equals(cold.status().get("fineSourcePending"))
                    &&((Number)field(cold,"context","fineCursor")).intValue()==((List<?>)field(cold,"context","jobs")).size();
                if(local)break;Thread.sleep(2);}
            h.assertTrue(local,"The actual local plan finishes before the controlled FAR");releaseFar.countDown();
            var terminal=AbyssLodStreamingGameTests.complete(cold,15);expected=LodSpeedGameTests.oracle(terminal);
            h.assertTrue(terminal.fine().size()==196&&terminal.surfacePages().faces()==74508,"Original local-first scene includes the second column and its six real faces");
        }finally{releaseFar.countDown();}
        var dispatchEntered=new CountDownLatch(1);var releaseDispatch=new CountDownLatch(1);
        try(var warm=new AbyssLodPipeline(true,true,cache)) {
            var dispatcher=AbyssLodPipeline.class.getDeclaredField("fineOrchestrator");dispatcher.setAccessible(true);
            ((ExecutorService)dispatcher.get(warm)).submit(()->{dispatchEntered.countDown();
                try{releaseDispatch.await();}catch(InterruptedException canceled){Thread.currentThread().interrupt();}});
            h.assertTrue(dispatchEntered.await(2,TimeUnit.SECONDS),"Source dispatch is held independently of FAR and page workers");
            warm.request(input);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);boolean adopted=false;
            while(System.nanoTime()<until){ackIfReady(h,warm);adopted=((Number)warm.status().get("farRawBoxes")).intValue()>0;
                if(adopted)break;Thread.sleep(2);}
            h.assertTrue(adopted&&releaseDispatch.getCount()==1,"Cached FAR is adopted before any local source prefix can execute");
            releaseDispatch.countDown();var terminal=AbyssLodStreamingGameTests.complete(warm,15);
            h.assertTrue(expected.equals(LodSpeedGameTests.oracle(terminal)),"Cached FAR-first retains the complete initial plan and all four original terminal digests");
        }finally{releaseDispatch.countDown();}
        h.succeed();
    }
    private static void ackIfReady(GameTestHelper h,AbyssLodPipeline pipeline) {
        var proposal=pipeline.poll();if(proposal.isPresent())h.assertTrue(pipeline.acknowledge(proposal.get(),true),"Current exact WORLD ACK");
        h.assertTrue(pipeline.status().get("error").equals(""),"Pipeline error: "+pipeline.status());
    }
    private static AbyssLodPipeline.Input plane(AbyssLodPipeline.Camera camera,long revision,long nativeRevision) {
        int x=(int)Math.floor(camera.x()),z=(int)Math.floor(camera.z());
        return new AbyssLodPipeline.Input(target(revision,nativeRevision),camera,new TerrainLodColumns.Bounds(x-64,-16,z-64,x+63,-1,z+63),
            new TerrainLodColumns.Bounds(-192,-16,-192,191,-1,191),
            List.of(new TerrainLodRefinement.Band(32,1),new TerrainLodRefinement.Band(64,2),new TerrainLodRefinement.Band(128,4)),
            List.of(),PLANE,LIGHT,limits());
    }
    private static AbyssLodPipeline.Target target(long revision,long nativeRevision){return new AbyssLodPipeline.Target("retarget-plan",1,"non-disk-source",0,nativeRevision,0,revision);}
    private static AbyssLodPipeline.Limits limits(){return new AbyssLodPipeline.Limits(10_000_000,200_000,800_000,4L*1024*1024,10_000,1);}
    private static Object displayed(AbyssLodPipeline pipeline)throws Exception {
        var f=AbyssLodPipeline.class.getDeclaredField("ledger");f.setAccessible(true);var ledger=(AbyssLodFineLedger)f.get(pipeline);return ledger==null?null:ledger.displayed();
    }
    private static Object field(AbyssLodPipeline pipeline,String first,String second)throws Exception {
        var f=AbyssLodPipeline.class.getDeclaredField(first);f.setAccessible(true);var value=f.get(pipeline);
        var nested=value.getClass().getDeclaredField(second);nested.setAccessible(true);return nested.get(value);
    }
    private static AbyssLodPipeline.Proposal next(AbyssLodPipeline pipeline,int seconds)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<until){var proposal=pipeline.poll();if(proposal.isPresent())return proposal.get();
            if(!pipeline.status().get("error").equals(""))throw new GameTestAssertException(pipeline.status().toString());Thread.sleep(2);}
        throw new GameTestAssertException("No retarget proposal: "+pipeline.status());
    }
}
