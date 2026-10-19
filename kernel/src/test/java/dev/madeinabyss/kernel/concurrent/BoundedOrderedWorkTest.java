package dev.madeinabyss.kernel.concurrent;

import dev.madeinabyss.kernel.light.SkyVisibility;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedOrderedWorkTest {
    @Test void cancelledOwnerDoesNotReturnUntilItsRunningPhaseActuallyExits() throws Exception {
        var phases=Executors.newSingleThreadExecutor();var owner=Executors.newSingleThreadExecutor();
        var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        var release=new CountDownLatch(1);var returned=new CountDownLatch(1);var phaseExited=new CountDownLatch(1);
        try {
            var task=owner.submit(()->{
                try {BoundedOrderedWork.map(List.of(1),1,phases,x->{
                    entered.countDown();boolean restore=false;
                    try {while(true)try{release.await();break;}catch(InterruptedException stop){restore=true;interrupted.countDown();}}
                    finally {phaseExited.countDown();if(restore)Thread.currentThread().interrupt();}
                    return x;
                });} finally {returned.countDown();}
            });
            assertTrue(entered.await(3,TimeUnit.SECONDS));task.cancel(true);
            assertTrue(interrupted.await(3,TimeUnit.SECONDS));
            assertFalse(returned.await(100,TimeUnit.MILLISECONDS),"Owner publication/cancellation must join actual phase exit, not Future.isDone");
            release.countDown();assertTrue(returned.await(3,TimeUnit.SECONDS));assertEquals(0,phaseExited.getCount());
        } finally {release.countDown();owner.shutdownNow();phases.shutdownNow();}
    }
    @Test void executesFourWorkersWithoutChangingOutputOrder() {
        var executor=Executors.newFixedThreadPool(4);
        try {
            var barrier=new CyclicBarrier(4);var count=new AtomicInteger();
            var active=new AtomicInteger();var maximum=new AtomicInteger();
            var input=IntStream.range(-32,32).boxed().toList();
            var output=BoundedOrderedWork.map(input,4,executor,x->{
                int now=active.incrementAndGet();maximum.accumulateAndGet(now,Math::max);
                try {
                    if(count.incrementAndGet()<=4)barrier.await(2,TimeUnit.SECONDS);
                    return x*x;
                }catch(Exception e){throw new IllegalStateException(e);}
                finally {active.decrementAndGet();}
            });
            assertEquals(input.stream().map(x->x*x).toList(),output);
            assertEquals(4,maximum.get());assertEquals(64,count.get());
        } finally {executor.shutdownNow();}
    }
    @Test void frontLoadedExpensiveSkyWorkIsSharedWithoutDuplicatingOrReorderingIndices() {
        var executor=Executors.newFixedThreadPool(4);
        try {
            var input=IntStream.range(0,1024).boxed().toList();
            var visits=new java.util.concurrent.atomic.AtomicIntegerArray(input.size());
            var expensiveWorkers=ConcurrentHashMap.<Long>newKeySet();
            var expensiveWave=new CyclicBarrier(4);
            java.util.function.Function<Integer,Double> solve=i->i<256
                    ? SkyVisibility.visibleFraction(i-128,-32,-17,32,10000,
                        (x,y,z)->y==0&&Math.floorMod(x+z,7)!=0?15:0) : (double)i;
            var expected=input.stream().map(solve).toList();
            var actual=BoundedOrderedWork.map(input,4,executor,i->{
                visits.incrementAndGet(i);
                if(i<256&&expensiveWorkers.add(Thread.currentThread().getId())) {
                    try {expensiveWave.await(2,TimeUnit.SECONDS);}
                    catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new CancellationException();}
                    catch(BrokenBarrierException|TimeoutException failure) {throw new IllegalStateException("Expensive prefix was left on one worker",failure);}
                }
                return solve.apply(i);
            });
            assertEquals(expected,actual);
            assertEquals(4,expensiveWorkers.size());
            for(int i=0;i<input.size();i++)assertEquals(1,visits.get(i),"index "+i);
        } finally {executor.shutdownNow();}
    }
    @Test void exactNegativeVoxelSkyFixtureMatchesSerialAndKeepsUnknownFailure() {
        var executor=Executors.newFixedThreadPool(4);
        try {
            var positions=IntStream.range(-20,20).boxed().toList();
            java.util.function.Function<Integer,Double> solve=x->SkyVisibility.visibleFraction(x,-16,-7,16,1000,
                    (px,py,pz)->py==0&&Math.abs(px)>8?15:0);
            assertEquals(positions.stream().map(solve).toList(),BoundedOrderedWork.map(positions,4,executor,solve));
            var unknown=new IllegalStateException("UNKNOWN fixture geometry");
            assertSame(unknown,assertThrows(IllegalStateException.class,()->BoundedOrderedWork.map(positions,4,executor,
                    x->{throw unknown;})));
        } finally {executor.shutdownNow();}
    }
    @Test void smallJobsAndPartialBatchTailsVisitEveryIndexExactlyOnce() {
        var executor=Executors.newFixedThreadPool(4);
        try {
            for(int size:new int[]{1,2,3,4,5,63,64,65,255,256,257,1023}) {
                var input=IntStream.range(0,size).boxed().toList();
                var visits=new java.util.concurrent.atomic.AtomicIntegerArray(size);
                assertEquals(input,BoundedOrderedWork.map(input,4,executor,i->{visits.incrementAndGet(i);return i;}));
                for(int i=0;i<size;i++)assertEquals(1,visits.get(i),"size "+size+", index "+i);
            }
        } finally {executor.shutdownNow();}
    }
    @Test void interruptedCallerDoesNotStartOrPublishWork() {
        var executor=Executors.newFixedThreadPool(4);var calls=new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class,()->BoundedOrderedWork.map(List.of(1,2,3),4,executor,x->calls.incrementAndGet()));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,calls.get());
        } finally {Thread.interrupted();executor.shutdownNow();}
    }
    @Test void firstFailureCancelsRunningSiblingAndPreservesOriginalFailure() throws Exception {
        var executor=Executors.newFixedThreadPool(2);var gate=new CyclicBarrier(2);var cancelled=new CountDownLatch(1);
        var original=new IllegalStateException("geometry unavailable");
        try {
            assertSame(original,assertThrows(IllegalStateException.class,()->BoundedOrderedWork.map(List.of(0,1),2,executor,x->{
                try {
                    gate.await(2,TimeUnit.SECONDS);
                    if(x==0)throw original;
                    Thread.sleep(10000);return x;
                }catch(InterruptedException interrupted){cancelled.countDown();Thread.currentThread().interrupt();throw new CancellationException();}
                catch(BrokenBarrierException|TimeoutException broken){throw new IllegalStateException(broken);}
            })));
            assertTrue(cancelled.await(2,TimeUnit.SECONDS));
        } finally {executor.shutdownNow();}
    }
    @Test void rejectsUnboundedWorkerCountsAndHandlesEmptyWorkWithoutSubmission() {
        var executor=Executors.newSingleThreadExecutor();var calls=new AtomicInteger();
        try {
            assertEquals(List.of(),BoundedOrderedWork.map(List.<Integer>of(),4,executor,x->calls.incrementAndGet()));
            assertThrows(IllegalArgumentException.class,()->BoundedOrderedWork.map(List.of(1),13,executor,x->x));
            assertThrows(IllegalArgumentException.class,()->BoundedOrderedWork.map(List.of(1),0,executor,x->x));
            assertEquals(0,calls.get());
        } finally {executor.shutdownNow();}
    }
    @Test void twelveWorkersShareRealWorkAndPreserveSignedInputOrder() {
        var executor=Executors.newFixedThreadPool(12);var gate=new CyclicBarrier(12);
        var workers=ConcurrentHashMap.<Long>newKeySet();var visits=new java.util.concurrent.atomic.AtomicIntegerArray(96);
        try {
            var input=IntStream.range(-48,48).boxed().toList();
            var actual=BoundedOrderedWork.map(input,12,executor,i->{
                visits.incrementAndGet(i+48);
                if(workers.add(Thread.currentThread().getId()))try{gate.await(3,TimeUnit.SECONDS);}
                catch(Exception failure){throw new IllegalStateException(failure);}
                return i*i;
            });
            assertEquals(input.stream().map(i->i*i).toList(),actual);assertEquals(12,workers.size());
            for(int i=0;i<96;i++)assertEquals(1,visits.get(i));
        } finally {executor.shutdownNow();}
    }
    @Test void twelveWorkerFailureCancelsAllRunningSiblings() throws Exception {
        var executor=Executors.newFixedThreadPool(12);var gate=new CyclicBarrier(12);var cancelled=new CountDownLatch(11);
        var original=new IllegalStateException("original twelve-worker failure");
        try {
            assertSame(original,assertThrows(IllegalStateException.class,()->BoundedOrderedWork.map(
                    IntStream.range(0,12).boxed().toList(),12,executor,i->{
                try{gate.await(3,TimeUnit.SECONDS);if(i==0)throw original;Thread.sleep(10000);return i;}
                catch(InterruptedException interrupted){cancelled.countDown();Thread.currentThread().interrupt();throw new CancellationException();}
                catch(BrokenBarrierException|TimeoutException failure){throw new IllegalStateException(failure);}
            })));
            assertTrue(cancelled.await(3,TimeUnit.SECONDS));
        } finally {executor.shutdownNow();}
    }
}
