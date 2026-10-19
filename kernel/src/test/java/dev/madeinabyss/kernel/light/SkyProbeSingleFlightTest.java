package dev.madeinabyss.kernel.light;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkyProbeSingleFlightTest {
    @Test void simultaneousExactProbeTracesOnce() throws Exception {
        var memo=new SkyProbeSingleFlight<String,Double>(64);var values=new ConcurrentHashMap<String,Double>();
        var calls=new AtomicInteger();var pool=Executors.newFixedThreadPool(8);
        var entered=new CountDownLatch(8);var release=new CountDownLatch(1);
        try {
            var futures=new java.util.ArrayList<Future<Double>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{
                entered.countDown();entered.await();return memo.getOrCompute(values,"same",16,()->{
                    calls.incrementAndGet();try {release.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}
                    return SkyVisibility.visibleFraction(-9,-32,-9,16,10000,(x,y,z)->y==-8&&x<0?15:y==0?4:0);
                });
            }));
            double expected=SkyVisibility.visibleFraction(-9,-32,-9,16,10000,(x,y,z)->y==-8&&x<0?15:y==0?4:0);
            for(var future:futures)assertEquals(expected,future.get(3,TimeUnit.SECONDS));
            assertEquals(1,calls.get());
        }finally {release.countDown();pool.shutdownNow();}
    }
    @Test void failedProbeIsNotCachedAndCanRetry() {
        var memo=new SkyProbeSingleFlight<Integer,Integer>(1);var values=new ConcurrentHashMap<Integer,Integer>();
        var unknown=new IllegalStateException("UNKNOWN geometry");
        assertSame(unknown,assertThrows(IllegalStateException.class,()->memo.getOrCompute(values,1,16,()->{throw unknown;})));
        assertEquals(0,values.size());assertEquals(7,memo.getOrCompute(values,1,16,()->7));
        assertEquals(7,memo.getOrCompute(values,1,16,()->15));
    }
    @Test void realSignedLatticeDoesNotCollapseIntoRecordPolynomialHashes() {
        var hashes=new java.util.HashSet<Integer>();int count=0;
        for(int y=-1280;y<=320;y+=32)for(int z=-720;z<=720;z+=32)for(int x=-720;x<=720;x+=32){
            hashes.add(SkyProbeSingleFlight.hashCoordinates(x,y,z));count++;
        }
        assertTrue(hashes.size()>count*.999,"Distinct XYZ sky probes must not collapse into the record31 polynomial on real32m lattice");
    }
    @Test void saturatedFlightsAndValueCapacityNeverLoseExactResults() throws Exception {
        var memo=new SkyProbeSingleFlight<Integer,Integer>(1);var values=new ConcurrentHashMap<Integer,Integer>();
        var pool=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var first=pool.submit(()->memo.getOrCompute(values,1,1,()->{
                entered.countDown();try {release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}return 7;
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(1,memo.pendingCount());
            assertEquals(13,memo.getOrCompute(values,2,1,()->13));assertEquals(1,memo.pendingCount());
            release.countDown();assertEquals(7,first.get(2,TimeUnit.SECONDS));
            assertEquals(1,values.size());assertEquals(0,memo.pendingCount());
            assertEquals(11,memo.getOrCompute(values,3,1,()->11));assertEquals(1,values.size());
            assertEquals(1,memo.capacity());assertEquals(3,memo.evaluations());
        }finally {release.countDown();pool.shutdownNow();}
    }
    @Test void exactFinalAndPreviewScopesStayIndependent() {
        var finals=new SkyProbeSingleFlight<Integer,Double>(1);var previews=new SkyProbeSingleFlight<Integer,Double>(1);
        var finalValues=new ConcurrentHashMap<Integer,Double>();var previewValues=new ConcurrentHashMap<Integer,Double>();
        assertEquals(.25,finals.getOrCompute(finalValues,17,1,()->.25));
        assertEquals(.375,previews.getOrCompute(previewValues,17,1,()->.375));
        assertEquals(.25,finals.getOrCompute(finalValues,17,1,()->1.));
        assertEquals(.375,previews.getOrCompute(previewValues,17,1,()->1.));
        assertThrows(IllegalArgumentException.class,()->new SkyProbeSingleFlight<>(0));
    }
}
