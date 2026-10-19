package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class T8CoordinatorTest {
    @Test void lightCacheRevisionPublishesAdmissionsAndNotDuplicateOrRefusedPuts() throws Exception {
        var cache=new dev.madeinabyss.kernel.concurrent.BoundedReuseCache<TerrainLodColumns.Box,Integer>(2,2,Integer::longValue);
        var a=T3LodOracleTest.box(-1,-1,-1,1,1,1,TerrainMaterial.ROCK);
        var b=T3LodOracleTest.box(0,0,0,1,1,1,TerrainMaterial.WATER);
        cache.put(a,1);long first=BoxCacheRevision.read(cache);
        assertTrue(first>0,"A completed admission must invalidate earlier negative lookups");
        cache.put(a,2);assertEquals(first,BoxCacheRevision.read(cache));assertEquals(1,cache.get(a));
        cache.put(b,3);assertEquals(first,BoxCacheRevision.read(cache));assertNull(cache.get(b));
        cache.put(b,2);assertEquals(2,BoxCacheRevision.read(cache));assertNull(cache.get(a));assertEquals(2,cache.get(b));
    }
    @Test void unchangedLightCacheRevisionDoesNotRepeatMissesButNewRevisionRestoresLight() throws Exception {
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64);
        var box=T3LodOracleTest.box(-65,-65,-65,130,130,130,TerrainMaterial.ROCK);pages.update(List.of(),List.of(box));
        var calls=new AtomicInteger();var revision=new AtomicLong();var values=new HashMap<TerrainLodColumns.Box,SurfaceQuadPlan.Owner>();
        java.util.function.Function<TerrainLodColumns.Box,SurfaceQuadPlan.Owner> lookup=b->{calls.incrementAndGet();return values.get(b);};
        try{pages.getClass().getMethod("setLightLookup",java.util.function.Function.class,java.util.function.LongSupplier.class).invoke(pages,lookup,(java.util.function.LongSupplier)revision::get);}
        catch(NoSuchMethodException red){pages.setLightLookup(lookup);}
        pages.preparePartial(null,1000,T3LodOracleTest.ORDER);pages.preparePartial(null,1000,T3LodOracleTest.ORDER);
        assertEquals(1,calls.get(),"An unchanged completed-value dictionary cannot resolve an earlier miss");
        values.put(box,T3LodOracleTest.light(box));revision.incrementAndGet();
        assertEquals(0,pages.preparePartial(null,1000,T3LodOracleTest.ORDER).pendingPages());
        assertEquals(2,calls.get());
        pages.invalidatePrepared();
        assertEquals(0,pages.preparePartial(null,1000,T3LodOracleTest.ORDER).pendingPages());
        assertEquals(3,calls.get(),"Resource invalidation must restore even at the same cache revision");
    }
    @Test void stagedPageDeltaAvoidsDuplicateMembershipMapAllocation() throws Exception {
        var actual=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var old=new T8ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var first=T3LodAllocationTest.denseBoxes(512);
        var second=first.stream().map(b->new TerrainLodColumns.Box(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive()+1,b.maxZExclusive(),b.material())).toList();
        actual.update(List.of(),first);old.update(List.of(),first);var executor=java.util.concurrent.Executors.newFixedThreadPool(3);
        try{
            int[] ai={0},bi={0};
            T3TileCodecOracleTest.Work a=()->{boolean f=(ai[0]++&1)==0;actual.update(f?first:second,f?second:first,List.of(),false,executor,3);};
            T3TileCodecOracleTest.Work b=()->{boolean f=(bi[0]++&1)==0;old.update(f?first:second,f?second:first,List.of(),false,executor,3);};
            for(int i=0;i<12;i++){a.run();b.run();}
            long before=T3TileCodecOracleTest.allocated(b,8),after=T3TileCodecOracleTest.allocated(a,8);
            assertTrue(after<before*.95,"Coordinator transaction allocation must shrink: old="+before+" current="+after);
        }finally{executor.shutdownNow();}
    }
    @Test void oneMissingPhysicalOwnerIsRestoredOncePerPreparationEvenAcrossPages() {
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64);
        var box=T3LodOracleTest.box(-65,-65,-65,130,130,130,TerrainMaterial.ROCK);
        pages.update(List.of(),List.of(box));
        var calls=new AtomicInteger();
        pages.setLightLookup(b->{calls.incrementAndGet();return null;});
        var partial=pages.preparePartial(null,1000,T3LodOracleTest.ORDER);
        assertTrue(partial.pendingPages()>1);
        assertEquals(1,calls.get(),"One negative lookup per distinct physical owner, not per incident page");
        pages.setLightLookup(b->{calls.incrementAndGet();return T3LodOracleTest.light(b);});
        var complete=pages.preparePartial(null,1000,T3LodOracleTest.ORDER);
        assertEquals(0,complete.pendingPages(),"A later call must observe newly available light");
        assertEquals(2,calls.get());
        assertEquals(0,((Number)pages.status().get("visibleMissing")).intValue());
    }
    @Test void claimedMissingPhysicalOwnerIsRestoredOncePerRoleScan() {
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64);
        var box=T3LodOracleTest.box(-65,-65,-65,130,130,130,TerrainMaterial.ROCK);
        pages.update(List.of(),List.of(box));var calls=new AtomicInteger();
        pages.setLightLookup(b->{calls.incrementAndGet();return null;});
        assertEquals(List.of(),pages.missingPageOwners(T3LodOracleTest.ORDER,10,Set.of(box)));
        assertEquals(1,calls.get(),"Claimed shared owner must not repeat a cache miss across pages");
        assertEquals(List.of(box),pages.missingPageOwners(T3LodOracleTest.ORDER,10));
        assertEquals(2,calls.get());
    }
    @Test void lookupHashSeparatesAlignedTranslationCollisionsWithoutChangingBoxHash() throws Exception {
        var legacy=new HashSet<Integer>();var mixed=new HashSet<Integer>();
        java.lang.reflect.Method hash=null;
        try{hash=Class.forName("dev.madeinabyss.kernel.lod.StrongBoxKey").getMethod("hash",TerrainLodColumns.Box.class);}
        catch(ClassNotFoundException missing) { /* RED exercises the legacy lookup hash. */ }
        for(int i=0;i<4096;i++){
            var b=T3LodOracleTest.box(100+i,100,10_000_000L-961L*i,2,8,2,TerrainMaterial.ROCK);
            legacy.add(b.hashCode());mixed.add(hash==null?b.hashCode():(Integer)hash.invoke(null,b));
        }
        assertEquals(1,legacy.size(),"Historical Box hash/order remains intact");
        assertTrue(mixed.size()>4090,"Lookup hash must separate the aligned lattice: "+mixed.size());
    }
}
