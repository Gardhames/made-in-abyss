package dev.madeinabyss.kernel.cube;

import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact complete tile values, bounded reuse and cooperative cancellation. */
class ExactCubeTileCacheTest {
    @Test void provedHomogeneousTileHasIndependentFullStateAndOpacityWithoutScanBuffers() throws Exception {
        var exact=uniform(419,7);assertNotNull(exact,"A certified homogeneous cube must be representable without4096 scan buffers");
        for(int i=0;i<4096;i++){assertEquals(419,exact.stateAt(i));assertEquals(7,exact.opacityAt(i));}
        assertEquals(5,exact.residentBytes());var copy=exact.states();copy[0]=999;assertEquals(419,exact.stateAt(0));
        assertThrows(IllegalArgumentException.class,()->uniform(-1,0));assertThrows(IllegalArgumentException.class,()->uniform(0,-1));assertThrows(IllegalArgumentException.class,()->uniform(0,16));
    }
    @Test void defaultRayMemoRetainsThousandsOfCompactAirTilesBelowItsMemoryBudget() {
        var cache=new ExactCubeTileCache(ExactCubeTileCache.DEFAULT_MAX_BYTES,ExactCubeTileCache.DEFAULT_MAX_TILES);
        var builds=new java.util.concurrent.atomic.AtomicInteger();var states=new int[4096];var opacity=new byte[4096];
        for(int pass=0;pass<2;pass++)for(int x=0;x<8192;x++){
            var key=new CubeKey(x-4096,-50,33);
            var tile=cache.getOrCompute(key,()->{builds.incrementAndGet();return ExactCubeTileCache.Tile.copyOf(states,opacity);},()->{});
            assertEquals(0,tile.opacityAt(2048));
        }
        assertEquals(8192,builds.get(),"Compact AIR entries must not repeatedly resample long rays while using only40KiB of primitive payload");
        assertTrue(cache.residentBytes()<64*1024);
    }
    @Test void signedKeysAndNeighbouringFacesKeepExactIndependentValues() {
        var cache=cache(100_000,8);
        var keys=new CubeKey[]{new CubeKey(-1,-17,-33),new CubeKey(0,-17,-33),new CubeKey(-1,-16,-33),new CubeKey(-1,-17,-32)};
        for(int i=0;i<keys.length;i++){int state=101+i;compute(cache,keys[i],()->tile(state,7),()->{});}
        for(int i=0;i<keys.length;i++) {
            int expected=101+i;var exact=compute(cache,keys[i],()->{fail("Resident exact tile must avoid another factory evaluation");return null;},()->{});
            assertEquals(expected,number(exact,"stateAt",0));assertEquals(7,number(exact,"opacityAt",0));
            assertEquals(0,number(exact,"stateAt",4095));assertEquals(0,number(exact,"opacityAt",4095));
        }
    }

    @Test void inputAndExportArraysCannotMutatePublishedExactTiles() {
        int[] states=new int[4096];byte[] opacity=new byte[4096];states[0]=7;opacity[0]=3;
        var exact=tile(states,opacity);states[0]=99;opacity[0]=15;
        assertEquals(7,number(exact,"stateAt",0));assertEquals(3,number(exact,"opacityAt",0));
        int[] copy=exact.states();copy[0]=88;
        assertEquals(7,number(exact,"stateAt",0));
    }

    @Test void boundedPayloadEvictsAndRecomputesWithoutChangingState() {
        var cache=cache(40_960,8);var first=new CubeKey(-1,-1,-1);var calls=new AtomicInteger();
        compute(cache,first,()->{calls.incrementAndGet();return tile(123,1);},()->{});
        compute(cache,new CubeKey(1,1,1),()->tile(124,2),()->{});
        compute(cache,new CubeKey(2,2,2),()->tile(125,3),()->{});
        assertTrue(number(cache,"residentBytes")<=40_960);assertEquals(2,number(cache,"residentTiles"));
        assertEquals(123,number(compute(cache,first,()->{calls.incrementAndGet();return tile(123,1);},()->{}),"stateAt",0));
        assertEquals(2,calls.get());assertTrue(number(cache,"evictions")>=1);
    }

    @Test void homogeneousTilesAreCompactButRetainAll4096ExactStates() {
        int[] states=new int[4096];byte[] opacity=new byte[4096];Arrays.fill(states,419);Arrays.fill(opacity,(byte)15);
        var cache=cache(100,2);var exact=compute(cache,new CubeKey(-123,-4096,456),()->tile(states,opacity),()->{});
        assertTrue(number(cache,"residentBytes")<=100);assertEquals(1,number(cache,"residentTiles"));
        assertEquals(419,number(exact,"stateAt",4095));assertEquals(15,number(exact,"opacityAt",2048));
        assertArrayEquals(states,exact.states());
    }

    @Test void unknownInvalidAndCancelledFactoriesNeverPublishPartialTile() {
        var cache=cache(100_000,4);var key=new CubeKey(-2,-89,-3);
        assertThrows(CancellationException.class,()->compute(cache,key,()->{throw new CancellationException("obsolete target");},()->{}));
        assertEquals(0,number(cache,"residentTiles"));
        int[] unknown=new int[4096];unknown[23]=-1;
        assertThrows(IllegalArgumentException.class,()->tile(unknown,new byte[4096]));
        byte[] invalid=new byte[4096];invalid[4095]=16;
        assertThrows(IllegalArgumentException.class,()->tile(new int[4096],invalid));
        assertEquals(0,number(cache,"residentBytes"));
        assertEquals(17,number(compute(cache,key,()->tile(17,0),()->{}),"stateAt",0));
    }

    @Test void concurrentReadersJoinOneCompleteFactoryResult() throws Exception {
        var cache=cache(100_000,4);var key=new CubeKey(-5,-19,-7);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var pool=Executors.newFixedThreadPool(4);
        Supplier<ExactCubeTileCache.Tile> factory=()->{calls.incrementAndGet();entered.countDown();await(release);return tile(901,4);};
        try {
            var first=pool.submit(()->compute(cache,key,factory,()->{}));assertTrue(entered.await(2,TimeUnit.SECONDS));
            var second=pool.submit(()->compute(cache,key,factory,()->{}));var third=pool.submit(()->compute(cache,key,factory,()->{}));
            release.countDown();var result=first.get(2,TimeUnit.SECONDS);
            assertSame(result,second.get(2,TimeUnit.SECONDS));assertSame(result,third.get(2,TimeUnit.SECONDS));
            assertEquals(1,calls.get());assertEquals(901,number(result,"stateAt",0));
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
    }

    @Test void cancelledWaiterExitsWhileOriginalFactoryStillWorks() throws Exception {
        var cache=cache(100_000,4);var key=new CubeKey(-5,-19,-7);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var waiterStarted=new CountDownLatch(1);var cancelled=new AtomicBoolean();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var builder=pool.submit(()->compute(cache,key,()->{entered.countDown();await(release);return tile(41,3);},()->{}));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            var waiter=pool.submit(()->assertThrows(CancellationException.class,()->compute(cache,key,()->tile(42,4),()->{waiterStarted.countDown();if(cancelled.get())throw new CancellationException();})));
            assertTrue(waiterStarted.await(2,TimeUnit.SECONDS));cancelled.set(true);
            waiter.get(1,TimeUnit.SECONDS);assertFalse(builder.isDone());
            release.countDown();assertEquals(41,number(builder.get(2,TimeUnit.SECONDS),"stateAt",0));
            assertEquals(41,number(compute(cache,key,()->tile(42,4),()->{}),"stateAt",0));
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
    }

    private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Controlled tile factory timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}}
    private static ExactCubeTileCache cache(long bytes,int count){return new ExactCubeTileCache(bytes,count);}
    private static ExactCubeTileCache.Tile tile(int first,int opacity){int[] s=new int[4096];byte[] o=new byte[4096];s[0]=first;o[0]=(byte)opacity;return tile(s,o);}
    private static ExactCubeTileCache.Tile tile(int[] states,byte[] opacity){return ExactCubeTileCache.Tile.copyOf(states,opacity);}
    private static ExactCubeTileCache.Tile uniform(int state,int opacity){return ExactCubeTileCache.Tile.uniform(state,opacity);}
    private static ExactCubeTileCache.Tile compute(ExactCubeTileCache cache,CubeKey key,Supplier<ExactCubeTileCache.Tile> factory,Runnable checkpoint){return cache.getOrCompute(key,factory,checkpoint);}
    private static long number(ExactCubeTileCache.Tile tile,String method,int index){return switch(method){case "stateAt"->tile.stateAt(index);case "opacityAt"->tile.opacityAt(index);default->throw new AssertionError(method);};}
    private static long number(ExactCubeTileCache cache,String method){return switch(method){case "residentTiles"->cache.residentTiles();case "residentBytes"->cache.residentBytes();case "evictions"->cache.evictions();default->throw new AssertionError(method);};}
}
