package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.madeinabyss.kernel.lod.T3LodOracleTest.*;

class T8CacheAndLookupOracleTest {
    @Test void randomizedCacheHitsFifoDuplicatesAndEvictionsMatchLegacy() {
        var before=new BoundedReuseCache<TerrainLodColumns.Box,Integer>(31,17,Integer::longValue);
        var after=new BoundedBoxReuseCache<Integer>(31,17,Integer::longValue);var random=new Random(8133);
        var pool=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<96;i++)pool.add(box(i%3==0?Integer.MIN_VALUE+i:100+i,-20480,10_000_000L-961L*i,2,8,2,TerrainMaterial.values()[1+i%4]));
        for(int i=0;i<5000;i++){
            var b=pool.get(random.nextInt(pool.size()));int weight=1+random.nextInt(40);
            if(random.nextBoolean()){before.put(b,weight);after.put(b,weight);}
            for(var key:pool)assertEquals(before.get(key),after.get(key));
            assertEquals(before.size(),after.size());assertEquals(before.evictions(),after.evictions());
        }
    }
    @Test void stablePerCallDictionariesMatchEveryOrderedPageWhileVersionChangesAndResourcesInvalidate() {
        for(boolean shared:new boolean[]{false,true})for(int metres:new int[]{64,128}){
            var a=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),metres,shared);
            var b=new T8ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),metres,shared);
            var dictionary=new HashMap<TerrainLodColumns.Box,SurfaceQuadPlan.Owner>();var revision=new AtomicLong();
            a.setLightLookup(dictionary::get,revision::get);b.setLightLookup(dictionary::get);
            var pool=new ArrayList<TerrainLodColumns.Box>();var random=new Random(944+metres);
            for(int i=0;i<48;i++)pool.add(box(random.nextInt(8)*64L-257,random.nextInt(4)*64L-193,random.nextInt(8)*64L-257,1+random.nextInt(80),1+random.nextInt(130),1+random.nextInt(80),TerrainMaterial.values()[1+random.nextInt(4)]));
            var live=new ArrayList<TerrainLodColumns.Box>();SurfaceQuadPlan.PageSet ar=null,br=null;
            for(int turn=0;turn<120;turn++){
                var removed=new ArrayList<TerrainLodColumns.Box>();var added=new ArrayList<TerrainLodColumns.Box>();
                for(int n=random.nextInt(4);n>0&&!live.isEmpty();n--)removed.add(live.remove(random.nextInt(live.size())));
                for(int n=random.nextInt(4);n>0;n--){var box=pool.get(random.nextInt(pool.size()));added.add(box);live.add(box);}
                var local=turn%3==0?List.of(footprint(pool.get(turn%pool.size()))):List.<TerrainLodClipper.BoundsLong>of();
                a.update(removed,added,local,true);b.update(removed,added,local,true);
                for(int n=0;n<4;n++){var box=pool.get(random.nextInt(pool.size()));dictionary.put(box,light(box));}revision.incrementAndGet();
                if(turn%17==0){a.invalidatePrepared();b.invalidatePrepared();}
                var claimed=Set.copyOf(added);
                assertEquals(b.missingPageOwners(ORDER,11,claimed),a.missingPageOwners(ORDER,11,claimed));
                assertEquals(b.missingLocalOwners(ORDER,11,claimed),a.missingLocalOwners(ORDER,11,claimed));
                assertEquals(b.missingBackgroundOwners(ORDER,11,claimed),a.missingBackgroundOwners(ORDER,11,claimed));
                int cap=1+random.nextInt(15);var ap=a.preparePartial(ar,cap,ORDER);var bp=b.preparePartial(br,cap,ORDER);
                assertEquals(bp,ap,"every page/owner/face/provenance/light/byte count turn="+turn);assertEquals(b.status(),a.status());ar=ap.published();br=bp.published();
            }
        }
    }
    @Test void aThrowingLookupRemainsRetryableAtSameRevisionAndNeverInfersLight() {
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64);var box=box(-65,-65,-65,130,130,130,TerrainMaterial.ROCK);pages.update(List.of(),List.of(box));
        var calls=new AtomicInteger();pages.setLightLookup(b->{if(calls.getAndIncrement()==0)throw new CancellationException("fixture cancellation");return light(b);},()->7L);
        assertThrows(CancellationException.class,()->pages.preparePartial(null,1000,ORDER));
        assertEquals(0,pages.preparePartial(null,1000,ORDER).pendingPages());assertEquals(2,calls.get());
    }
    @Test void concurrentCacheAdmissionIsObservedAfterRevisionPublication() throws Exception {
        var cache=new BoundedReuseCache<TerrainLodColumns.Box,Integer>(128*2048,2048,v->128L);var executor=Executors.newFixedThreadPool(4);
        try{
            var jobs=new ArrayList<Future<?>>();
            for(int w=0;w<4;w++){final int worker=w;jobs.add(executor.submit(()->{for(int i=0;i<512;i++)cache.put(box(worker*1024L+i,-i,worker,1,1,1,TerrainMaterial.ROCK),worker*512+i);}));}
            for(var job:jobs)job.get(10,TimeUnit.SECONDS);
            assertEquals(2048,BoxCacheRevision.read(cache));assertEquals(2048,cache.size());
            for(int w=0;w<4;w++)for(int i=0;i<512;i++)assertEquals(w*512+i,cache.get(box(w*1024L+i,-i,w,1,1,1,TerrainMaterial.ROCK)));
        }finally{executor.shutdownNow();}
    }
}
