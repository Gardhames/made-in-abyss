package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import java.util.function.Function;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalProvisionalSurfacePagesTest {
    private static final Comparator<SurfaceQuadPlan.PageKey> ORDER=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
    private static TerrainLodColumns.Box box(long x){return new TerrainLodColumns.Box(x,-1,1,x+1,0,2,TerrainMaterial.ROCK);}
    private static SurfaceQuadPlan.Owner light(TerrainLodColumns.Box b,int sky){return new SurfaceQuadPlan.Owner(b,sky,0,0xff777777);}
    private static SurfaceQuadPages.Partial provisional(IncrementalSurfacePages pages,SurfaceQuadPlan.PageSet retained,Function<TerrainLodColumns.Box,SurfaceQuadPlan.Owner> lookup){
        try{return (SurfaceQuadPages.Partial)IncrementalSurfacePages.class.getMethod("preparePartial",SurfaceQuadPlan.PageSet.class,int.class,Comparator.class,ExecutorService.class,int.class,boolean.class,Function.class)
            .invoke(pages,retained,100,ORDER,null,1,true,lookup);}
        catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof RuntimeException cause)throw cause;throw new AssertionError(failed.getCause());}
        catch(ReflectiveOperationException missing){throw new AssertionError("Missing presentation-only provisional page preparation; exact light still blocks geometry",missing);}
    }
    private static Set<String> pixels(SurfaceQuadPlan.PageSet pages){var out=new HashSet<String>();for(var page:pages.pages())for(var f:page.prepared().faces())for(long u=f.u0();u<f.u1();u++)for(long v=f.v0();v<f.v1();v++)out.add(f.axis()+":"+f.positive()+":"+f.plane()+":"+u+":"+v);return out;}
    @Test void fullGeometryCanPublishWhileExactDebtStaysMissingAndExactValuesWin(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var exact=box(-129);var pending=box(-128);
        pages.update(List.of(),List.of(exact,pending));pages.supplyLight(exact,light(exact,11));
        assertTrue(pages.preparePartial(null,100,ORDER).pendingPages()>0);
        var partial=provisional(pages,null,b->light(b,3));
        assertEquals(0,partial.pendingPages());assertEquals(1,((Number)pages.status().get("visibleMissing")).intValue());
        assertEquals(List.of(pending),pages.missingOwners(ORDER,10));
        for(var page:partial.published().pages())for(var owner:page.prepared().owners())
            if(owner.box().equals(exact))assertEquals(11,owner.sky());
        pages.supplyLight(pending,light(pending,8));var finished=pages.preparePartial(partial.published(),100,ORDER);
        assertEquals(0,finished.pendingPages());assertEquals(0,((Number)pages.status().get("visibleMissing")).intValue());
        assertEquals(pixels(partial.published()),pixels(finished.published()));
        for(var page:finished.published().pages())for(var owner:page.prepared().owners())assertEquals(owner.box().equals(exact)?11:8,owner.sky());
    }
    @Test void failedProvisionalSamplerCannotCommitPreparedPagesOrEraseDebt(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var b=box(-1);pages.update(List.of(),List.of(b));
        assertThrows(IllegalStateException.class,()->provisional(pages,null,x->{throw new IllegalStateException("unavailable physical authority");}));
        assertEquals(1,((Number)pages.status().get("visibleMissing")).intValue());assertTrue(pages.currentPages().isEmpty());
        assertTrue(pages.preparePartial(null,100,ORDER).published().pages().isEmpty());
    }
    @Test void invalidationDiscardsPresentationAndRemovedGeometryCannotRemainCurrent(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var b=box(-128);pages.update(List.of(),List.of(b));
        var first=provisional(pages,null,x->light(x,3));pages.invalidatePrepared();
        var next=provisional(pages,first.published(),x->light(x,7));
        assertEquals(1,((Number)pages.status().get("visibleMissing")).intValue());
        for(var page:next.published().pages())for(var owner:page.prepared().owners())assertEquals(7,owner.sky());
        pages.update(List.of(b),List.of());var empty=provisional(pages,next.published(),x->light(x,7));
        assertTrue(empty.published().pages().isEmpty());assertEquals(0,empty.pendingPages());assertFalse(pages.containsOwner(b));
    }
}
