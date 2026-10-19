package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.concurrent.BoundedOrderedWork;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLightWorkPartitionTest {
    @Test void fineSignedPartitionsHaveOneWriterAndNeverSplitACompleteCube() {
        for(int minimum:new int[]{Integer.MIN_VALUE,-1286,-840,-17,-1,0,17,Integer.MAX_VALUE-384})
            for(int size:new int[]{1,15,16,31,80,184,320,384}) {
                var ranges=tiles(minimum,size);long cursor=minimum,end=(long)minimum+size;
                for(var slab:ranges) {
                    assertEquals(cursor,slab.minimum());assertTrue(slab.limit()>slab.minimum());
                    assertTrue(slab.limit()-slab.minimum()<=16);cursor=slab.limit();
                    if(cursor<end)assertEquals(0,Math.floorMod(cursor,16));
                }
                assertEquals(end,cursor);
                for(long y=minimum;y<end;y++) {
                    long point=y;
                    assertEquals(1,ranges.stream().filter(s->point>=s.minimum()&&point<s.limit()).count());
                }
                for(long y=-Math.floorDiv(-(long)minimum,16)*16;y+16<=end;y+=16) {
                    long first=y;
                    assertEquals(1,ranges.stream().filter(s->first>=s.minimum()&&first+16<=s.limit()).count());
                }
            }
        assertThrows(IllegalArgumentException.class,()->tiles(Integer.MAX_VALUE,1));
        assertThrows(IllegalArgumentException.class,()->tiles(0,0));
    }

    @Test void firstFourExpensiveTilesCanRunTogetherAndResultsKeepInputOrder() {
        var pool=Executors.newFixedThreadPool(4);var firstWave=new CyclicBarrier(4);
        var visits=new AtomicIntegerArray(32);
        try {
            var input=IntStream.range(0,32).boxed().toList();
            var actual=map(input,pool,4,1,i->{
                visits.incrementAndGet(i);
                if(i<4)try {firstWave.await(3,TimeUnit.SECONDS);}
                catch(Exception failure){throw new IllegalStateException("Concentrated native work must reach all workers",failure);}
                return SkyVisibility.visibleFraction(i-17,-8,-17,16,10000,
                        (x,y,z)->y==0&&Math.floorMod(x+z,7)!=0?15:0);
            });
            var expected=input.stream().map(i->SkyVisibility.visibleFraction(i-17,-8,-17,16,10000,
                    (x,y,z)->y==0&&Math.floorMod(x+z,7)!=0?15:0)).toList();
            assertEquals(expected,actual);
            for(int i=0;i<32;i++)assertEquals(1,visits.get(i));
        } finally {pool.shutdownNow();}
    }

    @Test void fineClaimFailureStillJoinsTheRunningSibling() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CyclicBarrier(2);
        var exited=new CountDownLatch(1);var original=new IllegalStateException("UNKNOWN geometry");
        try {
            assertSame(original,assertThrows(IllegalStateException.class,()->map(List.of(0,1,2,3),pool,2,1,i->{
                try {
                    gate.await(3,TimeUnit.SECONDS);
                    if(i==0)throw original;
                    Thread.sleep(10000);return i;
                }catch(InterruptedException stop){Thread.currentThread().interrupt();throw new CancellationException();}
                catch(BrokenBarrierException|TimeoutException failure){throw new IllegalStateException(failure);}
                finally {if(i==1)exited.countDown();}
            })));
            assertEquals(0,exited.getCount(),"No shared-array writer may outlive the failed map");
            assertThrows(IllegalArgumentException.class,()->map(List.of(1),pool,2,0,Function.identity()));
            assertThrows(IllegalArgumentException.class,()->map(List.of(1),pool,2,65,Function.identity()));
        } finally {pool.shutdownNow();}
    }

    @SuppressWarnings("unchecked")
    private static List<AlignedVoxelSlabs.Slab> tiles(int minimum,int size) {
        try{return (List<AlignedVoxelSlabs.Slab>)AlignedVoxelSlabs.class.getMethod("tiles",int.class,int.class).invoke(null,minimum,size);}
        catch(NoSuchMethodException missing){throw new AssertionError("Missing fine cube-aligned native work partition",missing);}
        catch(InvocationTargetException failed){throw cause(failed);}
        catch(ReflectiveOperationException failed){throw new AssertionError(failed);}
    }
    @SuppressWarnings("unchecked")
    private static <T,R> List<R> map(List<T> input,ExecutorService executor,int workers,int batch,Function<T,R> operation) {
        try{return (List<R>)BoundedOrderedWork.class.getMethod("map",List.class,int.class,ExecutorService.class,int.class,Function.class)
                .invoke(null,input,workers,executor,batch,operation);}
        catch(NoSuchMethodException missing){throw new AssertionError("Missing bounded fine-claim native scheduling",missing);}
        catch(InvocationTargetException failed){throw cause(failed);}
        catch(ReflectiveOperationException failed){throw new AssertionError(failed);}
    }
    private static RuntimeException cause(InvocationTargetException failed) {
        if(failed.getCause() instanceof RuntimeException runtime)return runtime;
        if(failed.getCause() instanceof Error error)throw error;
        throw new AssertionError(failed.getCause());
    }
}
