package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalSurfaceReadyFeedTest {
    private static final Comparator<SurfaceQuadPlan.PageKey> ORDER=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x)
            .thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
    private static final List<TerrainLodColumns.Box> BOXES=List.of(
            box(-129,-129,-1,-127,-128,1),box(126,-3,4,128,-1,5),box(257,5,7,258,6,8));
    private static TerrainLodColumns.Box box(long x,long y,long z,long xx,long yy,long zz){
        return new TerrainLodColumns.Box(x,y,z,xx,yy,zz,TerrainMaterial.ROCK);
    }
    private static SurfaceQuadPlan.Owner light(TerrainLodColumns.Box box,int rgba){return new SurfaceQuadPlan.Owner(box,11,3,rgba);}
    private static IncrementalSurfacePages pages(List<TerrainLodColumns.Box> boxes){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);pages.update(List.of(),boxes);return pages;
    }
    private static SurfaceQuadPages.Partial ready(IncrementalSurfacePages pages,SurfaceQuadPlan.PageSet retained,int maximum,boolean restore){
        return pages.preparePartial(retained,maximum,ORDER,null,1,restore);
    }
    @Test void pendingReadyFeedDoesNotScanCacheAndExplicitSupplyMatchesEveryLegacyPublication(){
        var actual=pages(BOXES);var legacy=pages(BOXES);var lookups=new AtomicInteger();
        actual.setLightLookup(box->{lookups.incrementAndGet();return light(box,0xff123456);});
        legacy.setLightLookup(box->light(box,0xff123456));
        var pending=ready(actual,null,2,false);
        assertEquals(0,lookups.get());assertEquals(0,pending.published().faces());assertTrue(pending.pendingPages()>0);
        assertEquals(BOXES.size(),((Number)actual.status().get("visibleMissing")).intValue());
        for(var box:BOXES)actual.supplyLight(box,light(box,0xff123456));
        SurfaceQuadPlan.PageSet oldRetained=null,newRetained=null;int remaining;
        do{
            var expected=legacy.preparePartial(oldRetained,2,ORDER);var next=ready(actual,newRetained,2,false);
            assertEquals(expected,next,"Exact ordered pages, owners, light, contributors and readiness agree at signed seams");
            oldRetained=expected.published();newRetained=next.published();remaining=next.pendingPages();
        }while(remaining>0);
        assertEquals(0,lookups.get());assertTrue(newRetained.faces()>0);
    }
    @Test void roleSelectionRestoresCachedOwnersBeforeCheckingTheirClaims(){
        var local=box(-129,1,1,-127,2,2);var background=box(513,1,1,514,2,2);
        var actual=pages(List.of(background));actual.update(List.of(),List.of(local),List.of(),true);
        var restored=new HashSet<TerrainLodColumns.Box>();
        actual.setLightLookup(box->{restored.add(box);return light(box,0xff112233);});
        assertEquals(0,ready(actual,null,100,false).published().faces());assertTrue(restored.isEmpty());
        var claimed=Set.of(local,background);
        assertTrue(actual.missingLocalOwners(ORDER,100,claimed).isEmpty());assertEquals(Set.of(local),restored);
        assertTrue(actual.missingBackgroundOwners(ORDER,100,claimed).isEmpty());assertEquals(claimed,restored);
        var legacy=pages(List.of(background));legacy.update(List.of(),List.of(local),List.of(),true);
        legacy.setLightLookup(box->light(box,0xff112233));
        assertEquals(legacy.preparePartial(null,100,ORDER),ready(actual,null,100,false));
    }
    @Test void explicitRestoreAfterResourceInvalidationMatchesLegacyAndRebuildsColor(){
        var actual=pages(BOXES);var legacy=pages(BOXES);var lookups=new AtomicInteger();int[] color={0xff123456};
        actual.setLightLookup(box->{lookups.incrementAndGet();return light(box,color[0]);});legacy.setLightLookup(box->light(box,color[0]));
        var old=ready(actual,null,100,true);var oldLegacy=legacy.preparePartial(null,100,ORDER);assertEquals(oldLegacy,old);
        color[0]=0xffabcdef;actual.invalidatePrepared();legacy.invalidatePrepared();lookups.set(0);
        var waiting=ready(actual,old.published(),100,false);assertEquals(0,lookups.get());assertTrue(waiting.pendingPages()>0);
        assertEquals(old.published(),waiting.published(),"Retained exact old-resource pages are not silently replaced by defaults");
        var next=ready(actual,old.published(),100,true);assertEquals(legacy.preparePartial(oldLegacy.published(),100,ORDER),next);
        assertTrue(lookups.get()>0);assertEquals(0,next.pendingPages());
        for(var page:next.published().pages())for(var owner:page.prepared().owners())assertEquals(color[0],owner.rgba());
    }
    @Test void parallelReadyFeedMatchesSerialCompletePagesWithoutCallingLookup(){
        var actual=pages(BOXES);var legacy=pages(BOXES);
        for(var box:BOXES){actual.supplyLight(box,light(box,0xff123456));legacy.supplyLight(box,light(box,0xff123456));}
        actual.setLightLookup(box->{throw new AssertionError("Ready-feed publication consulted the cache");});
        var workers=Executors.newFixedThreadPool(3);
        try{assertEquals(legacy.preparePartial(null,100,ORDER),actual.preparePartial(null,100,ORDER,workers,3,false));}
        finally{workers.shutdownNow();}
    }
    @Test void cancellationBeforeReadyFeedCannotAdvancePreparedPagesOrLookup(){
        var actual=pages(BOXES);var lookups=new AtomicInteger();actual.setLightLookup(box->{lookups.incrementAndGet();return light(box,0xff123456);});
        var before=actual.status();Thread.currentThread().interrupt();
        try{assertThrows(CancellationException.class,()->ready(actual,null,100,false));}finally{Thread.interrupted();}
        assertEquals(0,lookups.get());assertEquals(before,actual.status());assertTrue(actual.currentPages().isEmpty());
        for(var box:BOXES)actual.supplyLight(box,light(box,0xff123456));
        assertEquals(0,ready(actual,null,100,false).pendingPages());assertEquals(0,lookups.get());
    }
}
