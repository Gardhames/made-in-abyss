package dev.madeinabyss.kernel.concurrent;

import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedReuseCacheTest {
    private record Key(int x,int y,int z) { @Override public int hashCode(){return 7;} }
    @Test void negativeExtremeCoordinatesAndHashCollisionsRemainDistinct() {
        var cache=new BoundedReuseCache<Key,String>(20,4,String::length);
        var first=new Key(Integer.MIN_VALUE,-257,-1);var second=new Key(-1,-4096,Integer.MAX_VALUE);
        cache.put(first,"first");cache.put(second,"second");
        assertEquals("first",cache.get(first));assertEquals("second",cache.get(second));
        assertNull(cache.get(new Key(-1,-4095,Integer.MAX_VALUE)));
        assertEquals(2,cache.size());assertEquals(11,cache.weight());
    }
    @Test void fifoEvictionEnforcesWeightAndHitsDoNotChangeOrder() {
        var cache=new BoundedReuseCache<Integer,String>(6,10,String::length);
        cache.put(1,"aaa");cache.put(2,"bb");assertEquals("aaa",cache.get(1));cache.put(3,"cc");
        assertNull(cache.get(1));assertEquals("bb",cache.get(2));assertEquals("cc",cache.get(3));
        assertEquals(4,cache.weight());assertEquals(1,cache.evictions());
    }
    @Test void entryCapAndDuplicateAdmissionRetainFirstExactValueAndWeight() {
        var cache=new BoundedReuseCache<Integer,String>(100,2,String::length);
        cache.put(1,"a");cache.put(2,"bb");cache.put(1,"duplicate");
        assertEquals("a",cache.get(1));assertEquals(3,cache.weight());
        cache.put(3,"ccc");assertNull(cache.get(1));assertEquals(5,cache.weight());assertEquals(1,cache.evictions());
    }
    @Test void oversizedAdmissionDoesNotDiscardUsefulEntriesOrExistingExactValue() {
        var cache=new BoundedReuseCache<Integer,String>(3,2,String::length);
        cache.put(1,"abc");cache.put(2,"oversized");cache.put(1,"also oversized");
        assertEquals("abc",cache.get(1));assertNull(cache.get(2));
        assertEquals(1,cache.size());assertEquals(3,cache.weight());assertEquals(0,cache.evictions());
    }
    @Test void invalidBoundsNullsAndNonpositiveWeightsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new BoundedReuseCache<Integer,String>(0,1,String::length));
        assertThrows(IllegalArgumentException.class,()->new BoundedReuseCache<Integer,String>(1,0,String::length));
        assertThrows(NullPointerException.class,()->new BoundedReuseCache<Integer,String>(1,1,null));
        var cache=new BoundedReuseCache<Integer,String>(3,2,String::length);
        assertThrows(NullPointerException.class,()->cache.get(null));
        assertThrows(NullPointerException.class,()->cache.put(null,"x"));
        assertThrows(NullPointerException.class,()->cache.put(1,null));
        assertThrows(IllegalArgumentException.class,()->cache.put(1,""));
        var negative=new BoundedReuseCache<Integer,Long>(3,2,value->value);
        assertThrows(IllegalArgumentException.class,()->negative.put(1,-1L));
        assertEquals(0,cache.size());assertEquals(0,cache.weight());
    }
    @Test void weightArithmeticDoesNotOverflowAtLongLimit() {
        var cache=new BoundedReuseCache<Integer,Long>(Long.MAX_VALUE,4,value->value);
        cache.put(1,Long.MAX_VALUE-1);cache.put(2,2L);
        assertNull(cache.get(1));assertEquals(2L,cache.get(2));assertEquals(2,cache.weight());
    }
    @Test void concurrentHitsAndAdmissionsNeverExceedBoundsAndKeepExactValues() throws Exception {
        var cache=new BoundedReuseCache<Key,String>(150,30,String::length);
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try {
            var jobs=new ArrayList<Future<?>>();
            for(int worker=0;worker<8;worker++){int id=worker;jobs.add(pool.submit(()->{
                try {start.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new RuntimeException(interrupted);}
                for(int i=0;i<1000;i++){
                    var key=new Key(id,-i,-16);String value="v"+id;
                    cache.put(key,value);String actual=cache.get(key);if(actual!=null)assertEquals(value,actual);
                    assertTrue(cache.size()<=30);assertTrue(cache.weight()<=150);assertTrue(cache.weight()>=0);
                }
            }));}
            start.countDown();for(var job:jobs)job.get(20,TimeUnit.SECONDS);
            assertEquals(30,cache.size());assertEquals(60,cache.weight());assertEquals(7970,cache.evictions());
        } finally {pool.shutdownNow();}
    }
    @Test void expensiveWeightCallbackDoesNotBlockHitsOrOtherAdmission() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var cache=new BoundedReuseCache<Integer,String>(20,4,value->{
            if(value.equals("slow")){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}
            return value.length();
        });
        cache.put(1,"ready");var pool=Executors.newFixedThreadPool(2);
        try {
            var slow=pool.submit(()->cache.put(2,"slow"));assertTrue(entered.await(2,TimeUnit.SECONDS));
            var fast=pool.submit(()->{assertEquals("ready",cache.get(1));cache.put(3,"fast");});
            fast.get(2,TimeUnit.SECONDS);release.countDown();slow.get(2,TimeUnit.SECONDS);
            assertEquals(3,cache.size());
        } finally {release.countDown();pool.shutdownNow();}
    }
}
