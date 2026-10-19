package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalSurfaceExtractionTest {
    private static final Comparator<SurfaceQuadPlan.PageKey> ORDER=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x)
            .thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
    private static TerrainLodColumns.Box rock(long x,long y,long z,long xx,long yy,long zz){return new TerrainLodColumns.Box(x,y,z,xx,yy,zz,TerrainMaterial.ROCK);}
    private static void update(IncrementalSurfacePages pages,List<TerrainLodColumns.Box> removed,List<TerrainLodColumns.Box> added,
            List<TerrainLodClipper.BoundsLong> footprints,boolean local,ExecutorService executor) {
        var method=assertDoesNotThrow(()->IncrementalSurfacePages.class.getMethod("update",List.class,List.class,List.class,boolean.class,ExecutorService.class,int.class),
                "Page extraction needs a bounded parallel overload with atomic aggregate admission");
        try{method.invoke(pages,removed,added,footprints,local,executor,3);}
        catch(InvocationTargetException failed){if(failed.getCause() instanceof RuntimeException cause)throw cause;throw new AssertionError(failed.getCause());}
        catch(IllegalAccessException failure){throw new AssertionError(failure);}
    }
    private static void supply(IncrementalSurfacePages pages){for(var box:pages.missingOwners(ORDER,1000))pages.supplyLight(box,
            new SurfaceQuadPlan.Owner(box,(int)Math.floorMod(box.minX()+box.maxYExclusive(),16),2,
                    box.material()==TerrainMaterial.WATER?0xb4355ea3:0xff7e7b77));}
    private record Retained(SurfaceQuadPlan.PageSet serial,SurfaceQuadPlan.PageSet parallel){}
    private static Retained comparePublication(IncrementalSurfacePages serial,IncrementalSurfacePages parallel,Retained previous) {
        var retained=previous==null?new Retained(null,null):previous;
        assertEquals(serial.missingOwners(ORDER,1000),parallel.missingOwners(ORDER,1000));
        assertEquals(serial.preparePartial(retained.serial(),2,ORDER),parallel.preparePartial(retained.parallel(),2,ORDER));
        supply(serial);supply(parallel);int pending;
        do {var expected=serial.preparePartial(retained.serial(),2,ORDER);var actual=parallel.preparePartial(retained.parallel(),2,ORDER);
            assertEquals(expected,actual);retained=new Retained(expected.published(),actual.published());pending=actual.pendingPages();}while(pending>0);
        assertEquals(serial.status(),parallel.status());return retained;
    }
    @Test void deltaSequenceKeepsExactOrderedPagesLightAndReuseAcrossNegativeSeams() {
        var serial=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var parallel=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var executor=Executors.newFixedThreadPool(3);
        var shared=rock(-129,-129,-129,-127,-127,-127);var adjacent=rock(-127,-129,-129,-126,-127,-127);
        var remote=new TerrainLodColumns.Box(257,-127,1,258,-126,2,TerrainMaterial.WATER);
        var air=new TerrainLodClipper.BoundsLong(-128,-128,-128,-112,-64,-112);
        try {
            var initial=List.of(shared,shared,remote);serial.update(List.of(),initial,List.of(),false);update(parallel,List.of(),initial,List.of(),false,executor);
            var retained=comparePublication(serial,parallel,null);
            var remoteBefore=retained.parallel().pages().stream().filter(p->p.key().x()==2).findFirst().orElseThrow().prepared();
            serial.update(List.of(shared),List.of(adjacent),List.of(air),true);update(parallel,List.of(shared),List.of(adjacent),List.of(air),true,executor);
            retained=comparePublication(serial,parallel,retained);
            assertSame(remoteBefore,retained.parallel().pages().stream().filter(p->p.key().x()==2).findFirst().orElseThrow().prepared());
            serial.update(List.of(shared,adjacent),List.of(),List.of(air),false);update(parallel,List.of(shared,adjacent),List.of(),List.of(air),false,executor);
            retained=comparePublication(serial,parallel,retained);
            serial.update(List.of(),List.of(shared),List.of(),false);update(parallel,List.of(),List.of(shared),List.of(),false,executor);
            retained=comparePublication(serial,parallel,retained);
            serial.invalidatePrepared();parallel.invalidatePrepared();comparePublication(serial,parallel,retained);
        } finally {executor.shutdownNow();}
    }
    @Test void aggregateFaceRefusalDoesNotCommitAnyPageOwnerOrLocalFootprint() {
        var limits=new SurfaceQuadPages.Limits(32,1000,1_000_000,12,1_000_000,1000,76000);
        var pages=new IncrementalSurfacePages(limits,128);var original=rock(1,1,1,2,2,2);
        pages.update(List.of(),List.of(original));supply(pages);var retained=pages.preparePartial(null,100,ORDER).published();var status=pages.status();
        var executor=Executors.newFixedThreadPool(3);
        try {
            assertThrows(TerrainLodColumns.BudgetExceededException.class,()->update(pages,List.of(),List.of(rock(-127,1,1,-126,2,2),rock(257,1,1,258,2,2)),
                    List.of(new TerrainLodClipper.BoundsLong(-128,0,0,-112,64,16)),true,executor));
            assertEquals(status,pages.status());assertEquals(retained,pages.preparePartial(retained,100,ORDER).published());
            assertTrue(pages.containsOwner(original));assertFalse(pages.containsOwner(rock(-127,1,1,-126,2,2)));
        } finally {executor.shutdownNow();}
    }
    @Test void workerFaceFailureAndExecutorRejectionLeaveEmptyIndexUntouched() {
        var pages=new IncrementalSurfacePages(new SurfaceQuadPages.Limits(32,1000,1_000_000,5,1_000_000,1000,76000),128);
        var status=pages.status();var executor=Executors.newFixedThreadPool(3);var boxes=List.of(rock(-127,1,1,-126,2,2),rock(257,1,1,258,2,2));
        try {
            assertThrows(TerrainLodColumns.BudgetExceededException.class,()->update(pages,List.of(),boxes,List.of(),true,executor));assertEquals(status,pages.status());
            executor.shutdownNow();assertThrows(RejectedExecutionException.class,()->update(pages,List.of(),boxes,List.of(),true,executor));
            assertEquals(status,pages.status());assertTrue(pages.targetKeys().isEmpty());assertTrue(pages.currentPages().isEmpty());
        } finally {executor.shutdownNow();}
    }
    @Test void cancellingCallerCannotCommitFrozenPagesAfterWorkersAreReleased() throws Exception {
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var status=pages.status();
        var started=new CountDownLatch(3);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        var executor=new ThreadPoolExecutor(3,3,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(3)){
            @Override protected void beforeExecute(Thread thread,Runnable task){started.countDown();try{release.await();}catch(InterruptedException interrupted){thread.interrupt();}}
        };
        var caller=new Thread(()->{try{update(pages,List.of(),List.of(rock(-255,1,1,-254,2,2),rock(1,1,1,2,2,2),rock(257,1,1,258,2,2)),List.of(),true,executor);}
            catch(Throwable caught){failure.set(caught);}},"page-extraction-caller");
        try {
            caller.start();assertTrue(started.await(3,TimeUnit.SECONDS));caller.interrupt();caller.join(3000);
            assertFalse(caller.isAlive());assertInstanceOf(CancellationException.class,failure.get());assertEquals(status,pages.status());
            release.countDown();executor.shutdown();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));assertEquals(status,pages.status());
        } finally {release.countDown();caller.interrupt();caller.join(3000);executor.shutdownNow();}
    }
}
