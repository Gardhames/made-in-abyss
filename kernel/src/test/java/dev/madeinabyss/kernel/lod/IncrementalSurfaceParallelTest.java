package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalSurfaceParallelTest {
    private static final Comparator<SurfaceQuadPlan.PageKey> ORDER=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x)
            .thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
    private static IncrementalSurfacePages pages() {
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var boxes=new ArrayList<TerrainLodColumns.Box>();
        for(int x=-256;x<384;x+=128){
            boxes.add(new TerrainLodColumns.Box(x-1,-129,-1,x+1,-128,1,TerrainMaterial.ROCK));
            boxes.add(new TerrainLodColumns.Box(x+3,-129,-1,x+4,-128,0,TerrainMaterial.WATER));
        }
        pages.update(List.of(),boxes);for(var box:pages.missingOwners(ORDER,100))pages.supplyLight(box,
                new SurfaceQuadPlan.Owner(box,11,2,box.material()==TerrainMaterial.WATER?0xb4355ea3:0xff7e7b77));
        return pages;
    }
    private static SurfaceQuadPages.Partial parallel(IncrementalSurfacePages pages,SurfaceQuadPlan.PageSet retained,int maximum,ExecutorService executor) {
        var method=assertDoesNotThrow(()->IncrementalSurfacePages.class.getMethod("preparePartial",
                SurfaceQuadPlan.PageSet.class,int.class,Comparator.class,ExecutorService.class,int.class),
                "Frozen independent page preparation needs an explicit bounded executor overload");
        try{return (SurfaceQuadPages.Partial)method.invoke(pages,retained,maximum,ORDER,executor,3);}
        catch(InvocationTargetException failed){if(failed.getCause() instanceof RuntimeException cause)throw cause;throw new AssertionError(failed.getCause());}
        catch(IllegalAccessException failure){throw new AssertionError(failure);}
    }
    @Test void parallelReadyPagesMatchEveryOrderedSerialPublicationAtNegativeSeams() {
        var serial=pages();var parallel=pages();var executor=Executors.newFixedThreadPool(3);
        try {
            SurfaceQuadPlan.PageSet serialRetained=null,parallelRetained=null;int pending;
            do {
                var expected=serial.preparePartial(serialRetained,3,ORDER);var actual=parallel(parallel,parallelRetained,3,executor);
                assertEquals(expected,actual);serialRetained=expected.published();parallelRetained=actual.published();pending=actual.pendingPages();
            } while(pending>0);
            assertEquals(0,((Number)parallel.status().get("visibleMissing")).intValue());
            assertEquals(serial.preparePartial(serialRetained,3,ORDER),parallel(parallel,parallelRetained,3,executor));
        } finally {executor.shutdownNow();}
    }
    @Test void failedExecutorDoesNotPublishOrCommitHalfOfThePreparationBatch() {
        var source=pages();var executor=Executors.newFixedThreadPool(3);executor.shutdownNow();
        assertThrows(RejectedExecutionException.class,()->parallel(source,null,3,executor));
        assertEquals(0L,((Number)source.status().get("builtPages")).longValue());assertTrue(source.currentPages().isEmpty());
        assertEquals(pages().preparePartial(null,100,ORDER),source.preparePartial(null,100,ORDER));
    }
}
