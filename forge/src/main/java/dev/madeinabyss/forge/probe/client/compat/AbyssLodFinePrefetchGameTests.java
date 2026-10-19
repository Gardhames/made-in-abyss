package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.*;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Source latency must not prevent an independent exact page from reaching the existing ACK. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodFinePrefetchGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void readyFarPagesPublishWhileFineSourcePrefixIsHeld(GameTestHelper h)throws Exception {
        var base=AbyssLodStreamingGameTests.input(1_000_000);
        var fineEntered=new CountDownLatch(1);var releaseFine=new CountDownLatch(1);
        var lightEntered=new CountDownLatch(1);var releaseLight=new CountDownLatch(1);
        var input=new AbyssLodPipeline.Input(base.target(),base.camera(),base.nearBounds(),base.worldBounds(),base.bands(),List.of(),
            (x,y,z)->{if(x>=-48&&Math.floorMod(x,4)==2){fineEntered.countDown();await(releaseFine);}return base.terrain().sample(x,y,z);},
            box->{if(box.maxXExclusive()-box.minX()>4){lightEntered.countDown();await(releaseLight);}return base.lighting().light(box);},base.limits());
        try(var pipeline=new AbyssLodPipeline(true)) {
            pipeline.request(input);long until=deadline(8);boolean initial=false;
            while(System.nanoTime()<until&&(fineEntered.getCount()!=0||lightEntered.getCount()!=0)) {
                var next=pipeline.poll();if(next.isPresent()){initial|=next.get().stage()==AbyssLodPipeline.Stage.NEAR_FINE;
                    h.assertTrue(pipeline.acknowledge(next.get(),true),"Current startup/intermediate proposal ACK");}
                noError(h,pipeline);Thread.sleep(2);
            }
            h.assertTrue(initial&&fineEntered.getCount()==0&&lightEntered.getCount()==0,
                "Actual fine source and independent FAR light both execute while coordinator remains available: "+pipeline.status());
            h.assertTrue(Boolean.TRUE.equals(pipeline.status().get("fineSourcePending")),"A real source prefix remains pending");
            releaseLight.countDown();boolean published=false;until=deadline(8);
            while(System.nanoTime()<until&&!published) {
                var next=pipeline.poll();if(next.isPresent()){
                    var value=next.get();h.assertTrue(pipeline.acknowledge(value,true),"Independent exact page receives the same current WORLD ACK");
                    published=value.farStep()==16&&value.surfacePages().faces()>0;
                    h.assertTrue(!value.requestedComplete(),"A blocked exact source prefix forbids terminal completion");}
                noError(h,pipeline);Thread.sleep(2);
            }
            h.assertTrue(published&&releaseFine.getCount()==1&&Boolean.TRUE.equals(pipeline.status().get("fineSourcePending")),
                "FAR page publication proceeds without releasing blocked fine source: "+pipeline.status());
            h.assertTrue(((Number)pipeline.status().get("selectionReuses")).longValue()>0,
                "Unchanged geometry reuses its exact active selection while source execution is held");
            releaseFine.countDown();var complete=AbyssLodStreamingGameTests.complete(pipeline,8);
            h.assertTrue(complete.fine().size()==8,"Every original exact fine grid is eventually installed");
            h.assertTrue(((Number)pipeline.status().get("liveFineScratchBytes")).longValue()==0,"Adopted result releases scratch leases");
        } finally {releaseLight.countDown();releaseFine.countDown();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void canceledFinePrefixHoldsScratchUntilIgnoredInterruptActuallyExits(GameTestHelper h)throws Exception {
        var base=AbyssLodStreamingGameTests.input(1_000_000);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var armed=new AtomicBoolean(true);
        var input=new AbyssLodPipeline.Input(base.target(),base.camera(),base.nearBounds(),base.worldBounds(),base.bands(),List.of(),
            (x,y,z)->{if(x>=-48&&Math.floorMod(x,4)==2&&armed.compareAndSet(true,false)){
                entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException deliberatelyIgnored) { }
            }return base.terrain().sample(x,y,z);},base.lighting(),base.limits());
        try(var pipeline=new AbyssLodPipeline(true)) {
            pipeline.request(input);long until=deadline(8);
            while(System.nanoTime()<until&&entered.getCount()!=0){var p=pipeline.poll();if(p.isPresent())h.assertTrue(pipeline.acknowledge(p.get(),true),"Initial prefix ACK");
                noError(h,pipeline);Thread.sleep(2);}
            h.assertTrue(entered.getCount()==0&&Boolean.TRUE.equals(pipeline.status().get("fineSourcePending")),"An actual prefetched fine builder is held");
            pipeline.close();Thread.sleep(20);
            h.assertTrue(((Number)pipeline.status().get("liveFineScratchBytes")).longValue()>0,"Future cancellation does not release an executing builder's scratch");
            h.assertTrue(((Number)pipeline.status().get("freshSampleReserve")).longValue()>0,"Its source reservation also remains held until actual exit");
            release.countDown();until=deadline(3);
            while(System.nanoTime()<until&&(((Number)pipeline.status().get("liveFineScratchBytes")).longValue()!=0
                    ||((Number)pipeline.status().get("freshSampleReserve")).longValue()!=0))Thread.sleep(2);
            h.assertTrue(((Number)pipeline.status().get("liveFineScratchBytes")).longValue()==0
                    &&((Number)pipeline.status().get("freshSampleReserve")).longValue()==0,"Both executing leases release exactly once after exit");
        } finally {release.countDown();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void retargetRetiresTenOldLightTasksBeforeNewSourcePrefetch(GameTestHelper h)throws Exception {
        var release=new CountDownLatch(1);var enabled=new AtomicBoolean();var blocked=new java.util.concurrent.atomic.AtomicInteger();
        var world=new TerrainLodColumns.Bounds(-1024,0,-1024,1023,15,1023);
        var bands=List.of(new TerrainLodRefinement.Band(192,1));var limits=AbyssLodPipeline.Limits.defaults();
        dev.madeinabyss.kernel.terrain.trail.TrailNetwork.SampleSource terrain=(x,y,z)->new dev.madeinabyss.kernel.terrain.TerrainSample.Known(
            y==8?dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK:dev.madeinabyss.kernel.terrain.TerrainMaterial.AIR);
        AbyssLodPipeline.BoxLighting light=box->{if(enabled.get()&&box.minX()<256){blocked.incrementAndGet();await(release);}return new AbyssLodPipeline.Light(7,0);};
        var initialTarget=new AbyssLodPipeline.Target("prefetch-capacity",1,"non-disk-source",0,0,0,1);
        var movedTarget=new AbyssLodPipeline.Target("prefetch-capacity",1,"non-disk-source",0,0,0,2);
        var initial=new AbyssLodPipeline.Input(initialTarget,new AbyssLodPipeline.Camera(0,8,0,1,0,0),
            new TerrainLodColumns.Bounds(-16,0,-16,15,15,15),world,bands,List.of(),terrain,light,limits);
        var moved=new AbyssLodPipeline.Input(movedTarget,new AbyssLodPipeline.Camera(768,8,0,1,0,0),
            new TerrainLodColumns.Bounds(752,0,-16,783,15,15),world,bands,List.of(),terrain,light,limits);
        try(var pipeline=new AbyssLodPipeline(true)) {
            pipeline.request(initial);long until=deadline(12);int expected=0;
            while(System.nanoTime()<until){var next=pipeline.poll();if(next.isPresent()){
                    enabled.set(true);h.assertTrue(pipeline.acknowledge(next.get(),true),"Positive prefix ACK before the controlled light debt");}
                noError(h,pipeline);var status=pipeline.status();expected=((Number)status.get("maximumSourceWorkers")).intValue()-1;
                if(!Boolean.TRUE.equals(status.get("fineSourcePending"))&&blocked.get()>=expected
                        &&((Number)status.get("surfaceLightInFlight")).intValue()==expected)break;Thread.sleep(2);}
            var before=pipeline.status();
            h.assertTrue(expected>0&&blocked.get()>=expected&&((Number)before.get("surfaceLightInFlight")).intValue()==expected,
                "All available old light slots are really held before retarget (ten on the 12-thread benchmark): "+before);
            long oldPrepared=((Number)before.get("preparedFineJobs")).longValue();pipeline.request(moved);
            boolean newFine=false;until=deadline(12);
            while(System.nanoTime()<until&&!newFine){var next=pipeline.poll();if(next.isPresent()){
                    var value=next.get();h.assertTrue(pipeline.acknowledge(value,true),"Retarget exact page ACK");
                    newFine=value.target().equals(movedTarget)&&value.fine().stream().anyMatch(grid->grid.step()==1&&grid.bounds().minX()>=576);
                    h.assertTrue(!value.requestedComplete(),"Held exact background light prevents completion");}
                noError(h,pipeline);Thread.sleep(2);}
            h.assertTrue(newFine&&release.getCount()==1&&((Number)pipeline.status().get("preparedFineJobs")).longValue()>oldPrepared,
                "Fresh positive-X fine pages publish while unrelated old light remains held: "+pipeline.status());
        } finally {release.countDown();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=900)
    public static void activePrefixRetargetCannotAdvanceTheNewPlansCursor(GameTestHelper h)throws Exception {
        var base=AbyssLodStreamingGameTests.input(1_000_000);
        var movedTarget=new AbyssLodPipeline.Target(base.target().session(),base.target().lifetime(),base.target().sourceIdentity(),0,0,0,2);
        var movedCamera=new AbyssLodPipeline.Camera(32,-8,-8,-1,0,0);
        var movedNear=new TerrainLodColumns.Bounds(32,-16,-16,47,-1,-1);
        var clean=new AbyssLodPipeline.Input(movedTarget,movedCamera,movedNear,base.worldBounds(),base.bands(),List.of(),base.terrain(),base.lighting(),base.limits());
        com.google.gson.JsonObject expected;
        try(var reference=new AbyssLodPipeline(true)) {reference.request(clean);expected=LodSpeedGameTests.oracle(AbyssLodStreamingGameTests.complete(reference,8));}
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var armed=new AtomicBoolean(true);
        dev.madeinabyss.kernel.terrain.trail.TrailNetwork.SampleSource held=(x,y,z)->{
            if(x>=-48&&Math.floorMod(x,4)==2&&armed.compareAndSet(true,false)){
                entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException deliberatelyIgnored) { }
            }return base.terrain().sample(x,y,z);
        };
        var initial=new AbyssLodPipeline.Input(base.target(),base.camera(),base.nearBounds(),base.worldBounds(),base.bands(),List.of(),held,base.lighting(),base.limits());
        var moved=new AbyssLodPipeline.Input(movedTarget,movedCamera,movedNear,base.worldBounds(),base.bands(),List.of(),held,base.lighting(),base.limits());
        try(var pipeline=new AbyssLodPipeline(true)) {
            pipeline.request(initial);long until=deadline(8);
            while(System.nanoTime()<until&&entered.getCount()!=0){var p=pipeline.poll();if(p.isPresent())h.assertTrue(pipeline.acknowledge(p.get(),true),"Initial exact prefix ACK");noError(h,pipeline);Thread.sleep(2);}
            h.assertTrue(entered.getCount()==0,"Retarget starts with an actual old source prefix held");pipeline.request(moved);until=deadline(3);
            while(System.nanoTime()<until&&((Number)pipeline.status().get("finePrefetchRetirements")).longValue()==0){
                var p=pipeline.poll();if(p.isPresent())h.assertTrue(pipeline.acknowledge(p.get(),true),"Retarget partial ACK");noError(h,pipeline);Thread.sleep(2);}
            var status=pipeline.status();long scratch=((Number)status.get("liveFineScratchBytes")).longValue();
            h.assertTrue(((Number)status.get("finePrefetchRetirements")).longValue()>0,
                "Coordinator retires the old plan without waiting for its held callback: "+status);
            h.assertTrue(scratch>0&&scratch<=base.limits().maxLedgerBytes(),"Old executing and new pending results share the unchanged scratch cap");
            release.countDown();var terminal=AbyssLodStreamingGameTests.complete(pipeline,8);
            h.assertTrue(expected.equals(LodSpeedGameTests.oracle(terminal)),"Retarget cannot skip the new plan's prefix; all four terminal geometry/light/render digests match clean acquisition");
        } finally {release.countDown();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=900)
    public static void discardedFinerCornerCannotSkipRequiredCoarseSource(GameTestHelper h)throws Exception {
        var world=new TerrainLodColumns.Bounds(-128,-16,-128,127,-1,127);
        var bands=List.of(new TerrainLodRefinement.Band(64,1),new TerrainLodRefinement.Band(128,2),new TerrainLodRefinement.Band(256,4));
        var input=new AbyssLodPipeline.Input(new AbyssLodPipeline.Target("prefetch-coverage",1,"non-disk-source",0,0,0,1),
            new AbyssLodPipeline.Camera(0,-8,0,1,0,0),new TerrainLodColumns.Bounds(-16,-16,-16,15,-1,15),world,bands,List.of(),
            (x,y,z)->new dev.madeinabyss.kernel.terrain.TerrainSample.Known(y==-8
                ?dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK:dev.madeinabyss.kernel.terrain.TerrainMaterial.AIR),
            box->new AbyssLodPipeline.Light(7,0),new AbyssLodPipeline.Limits(10_000_000,200_000,800_000,1024L*1024,10_000,1));
        // Equal six MiB resident capacity; the streaming source scratch remains one MiB.
        // Rectangular fine plans include corner grids that radial display selection drops.
        var legacy=new AbyssLodPipeline.Input(input.target(),input.camera(),input.nearBounds(),world,bands,List.of(),input.terrain(),input.lighting(),
            new AbyssLodPipeline.Limits(10_000_000,200_000,800_000,6L*1024*1024,10_000,1));
        com.google.gson.JsonObject expected;
        try(var reference=new AbyssLodPipeline(true,false)) {
            reference.request(legacy);var terminal=AbyssLodStreamingGameTests.complete(reference,12);
            h.assertTrue(terminal.fine().size()==256,"Legacy reference retains all 256 required angular cells");
            expected=LodSpeedGameTests.oracle(terminal);
        }
        try(var pipeline=new AbyssLodPipeline(true)) {
            pipeline.request(input);var terminal=AbyssLodStreamingGameTests.complete(pipeline,12);var status=pipeline.status();
            h.assertTrue(((Number)status.get("finePrefetchAdoptions")).intValue()>1,"Bounded scratch really splits the fixed-camera plan into multiple prefixes");
            h.assertTrue(((Number)status.get("angularBootstrapRemaining")).intValue()==0&&terminal.fine().size()==256,
                "A finer corner discarded at publication cannot permanently cover and skip its later required coarse source: "+status);
            h.assertTrue(expected.equals(LodSpeedGameTests.oracle(terminal)),"All four terminal geometry, owner, ordered-render and fine-grid digests equal the synchronous reference");
        }
        h.succeed();
    }
    private static long deadline(int seconds){return System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);}
    private static void await(CountDownLatch latch){try{latch.await();}catch(InterruptedException canceled){Thread.currentThread().interrupt();throw new CancellationException("Controlled source/light canceled");}}
    private static void noError(GameTestHelper h,AbyssLodPipeline pipeline){h.assertTrue(pipeline.status().get("error").equals(""),"Pipeline error: "+pipeline.status());}
}
