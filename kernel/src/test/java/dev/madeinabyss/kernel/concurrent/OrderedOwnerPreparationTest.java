package dev.madeinabyss.kernel.concurrent;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderedOwnerPreparationTest {
    private static final class Manual implements Executor {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable task){tasks.addLast(task);}
        void next(){assertFalse(tasks.isEmpty());tasks.removeFirst().run();}
    }
    @Test void preparationAndPublicationWaitForTheirSeparateExecutors() {
        var worker=new Manual();var owner=new Manual();var q=new OrderedOwnerPreparation(worker,owner);
        var values=new ArrayList<Integer>();
        q.submit(()->new OrderedOwnerPreparation.Work<>(check->{check.run();return 42;},(value,error)->{assertNull(error);values.add(value);}));
        assertEquals(List.of(),values,"submitting must not decode or publish on the owner");
        assertEquals(1,worker.tasks.size());worker.next();assertEquals(List.of(),values);
        owner.next();assertEquals(List.of(42),values);assertFalse(q.active());
    }
    @Test void startersAndPacketBarriersRetainArrivalOrder() {
        var worker=new Manual();var owner=new Manual();var q=new OrderedOwnerPreparation(worker,owner);
        var trace=new ArrayList<String>();
        q.submit(()->{trace.add("start1");return new OrderedOwnerPreparation.Work<>(check->11,(v,e)->trace.add("publish"+v));});
        q.after(()->trace.add("light1"));
        q.submit(()->{trace.add("start2");return new OrderedOwnerPreparation.Work<>(check->22,(v,e)->trace.add("publish"+v));});
        q.after(()->trace.add("item2"));
        assertEquals(List.of("start1"),trace);worker.next();owner.next();
        assertEquals(List.of("start1","publish11","light1","start2"),trace);
        worker.next();owner.next();assertEquals(List.of("start1","publish11","light1","start2","publish22","item2"),trace);
    }
    @Test void refusalIsDeliveredOnOwnerBeforeTheFollowingRetry() {
        var worker=new Manual();var owner=new Manual();var q=new OrderedOwnerPreparation(worker,owner);
        var values=new ArrayList<String>();var failure=new IllegalArgumentException("malformed");
        q.submit(()->new OrderedOwnerPreparation.Work<Integer>(check->{throw failure;},(v,e)->{assertSame(failure,e);values.add("refused");}));
        q.submit(()->new OrderedOwnerPreparation.Work<>(check->2,(v,e)->values.add("revision"+v)));
        assertEquals(List.of(),values);worker.next();assertEquals(List.of(),values);owner.next();worker.next();owner.next();
        assertEquals(List.of("refused","revision2"),values);
    }
    @Test void retiredCompletionCannotPublishOrRunOldPacketBarriers() {
        var worker=new Manual();var owner=new Manual();var q=new OrderedOwnerPreparation(worker,owner);
        var values=new ArrayList<Integer>();
        q.submit(()->new OrderedOwnerPreparation.Work<>(check->1,(v,e)->values.add(v)));
        q.after(()->values.add(99));worker.next();q.clear();
        q.submit(()->new OrderedOwnerPreparation.Work<>(check->2,(v,e)->values.add(v)));
        assertTrue(worker.tasks.isEmpty(),"retired work keeps the slot until its completion reaches the owner");
        owner.next();assertEquals(List.of(),values);worker.next();owner.next();assertEquals(List.of(2),values);
    }
    @Test void clearBeforeWorkerStartsCancelsPreparationWithoutARefusal() {
        var worker=new Manual();var owner=new Manual();var q=new OrderedOwnerPreparation(worker,owner);
        var calls=new AtomicInteger();
        q.submit(()->new OrderedOwnerPreparation.Work<>(check->{calls.incrementAndGet();return 1;},(v,e)->calls.addAndGet(100)));
        q.clear();worker.next();owner.next();assertEquals(0,calls.get());assertFalse(q.active());
    }
    @Test void cancellationCheckpointStopsAnActivePreparation() throws Exception {
        var worker=Executors.newSingleThreadExecutor();var deliveries=new LinkedBlockingQueue<Runnable>();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var stopped=new AtomicBoolean();
        var published=new AtomicBoolean();var q=new OrderedOwnerPreparation(worker,deliveries::add);
        try {
            q.submit(()->new OrderedOwnerPreparation.Work<>(check->{entered.countDown();
                try {assertTrue(release.await(3,TimeUnit.SECONDS));check.run();return 1;}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}
                catch(CancellationException e){stopped.set(true);throw e;}
            },(v,e)->published.set(true)));
            assertTrue(entered.await(3,TimeUnit.SECONDS));q.clear();release.countDown();
            var callback=deliveries.poll(3,TimeUnit.SECONDS);assertNotNull(callback);callback.run();
            assertTrue(stopped.get());assertFalse(published.get());
        } finally {release.countDown();worker.shutdownNow();}
    }
    @Test void realWorkerNeverTouchesTheOwnerAndDoesNotWaitForPublication() throws Exception {
        var worker=Executors.newSingleThreadExecutor();var deliveries=new LinkedBlockingQueue<Runnable>();
        var caller=Thread.currentThread();var preparedOn=new AtomicReference<Thread>();var publishedOn=new AtomicReference<Thread>();
        var q=new OrderedOwnerPreparation(worker,deliveries::add);
        try {
            q.submit(()->new OrderedOwnerPreparation.Work<>(check->{preparedOn.set(Thread.currentThread());return 7;},(v,e)->{assertEquals(7,v);publishedOn.set(Thread.currentThread());}));
            var callback=deliveries.poll(3,TimeUnit.SECONDS);assertNotNull(callback);assertNull(publishedOn.get());
            assertNotEquals(caller,preparedOn.get());callback.run();assertEquals(caller,publishedOn.get());
        } finally {worker.shutdownNow();}
    }
    @Test void throwingPublicationReleasesItsSlotAndStartsTheNextTask() {
        var worker=new Manual();var owner=new Manual();var q=new OrderedOwnerPreparation(worker,owner);var values=new ArrayList<Integer>();
        q.submit(()->new OrderedOwnerPreparation.Work<>(check->1,(v,e)->{throw new IllegalStateException("owner publication");}));
        q.submit(()->new OrderedOwnerPreparation.Work<>(check->2,(v,e)->values.add(v)));
        worker.next();assertThrows(IllegalStateException.class,owner::next);worker.next();owner.next();assertEquals(List.of(2),values);
    }
    @Test void nullStarterSkipsStaleWindowsAndDoesNotCreateWork() {
        var worker=new Manual();var owner=new Manual();var q=new OrderedOwnerPreparation(worker,owner);var calls=new AtomicInteger();
        q.submit(()->null);q.after(calls::incrementAndGet);assertEquals(1,calls.get());assertTrue(worker.tasks.isEmpty());
    }
    @Test void reentrantBarriersAreAppendedAfterAlreadyReceivedPackets() {
        var worker=new Manual();var owner=new Manual();var q=new OrderedOwnerPreparation(worker,owner);var trace=new ArrayList<Integer>();
        q.submit(()->new OrderedOwnerPreparation.Work<>(check->1,(v,e)->{trace.add(v);q.after(()->trace.add(3));}));
        q.after(()->trace.add(2));worker.next();owner.next();assertEquals(List.of(1,2,3),trace);
    }
    @Test void callsFromAForeignOwnerAreRefused() throws Exception {
        var worker=new Manual();var owner=new Manual();var q=new OrderedOwnerPreparation(worker,owner);var caught=new AtomicReference<Throwable>();
        var thread=new Thread(()->{try {q.clear();}catch(Throwable e){caught.set(e);}});thread.start();thread.join();
        assertInstanceOf(IllegalStateException.class,caught.get());
    }
    @Test void retiredRunningWorkerKeepsItsSlotUntilItActuallyExits() throws Exception {
        var worker=Executors.newSingleThreadExecutor();var deliveries=new LinkedBlockingQueue<Runnable>();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var nextStarted=new AtomicBoolean();
        var q=new OrderedOwnerPreparation(worker,deliveries::add);var values=new ArrayList<Integer>();
        try {
            q.submit(()->new OrderedOwnerPreparation.Work<>(check->{entered.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return 1;},(v,e)->values.add(v)));
            assertTrue(entered.await(3,TimeUnit.SECONDS));q.clear();
            q.submit(()->new OrderedOwnerPreparation.Work<>(check->{nextStarted.set(true);return 2;},(v,e)->values.add(v)));
            assertFalse(nextStarted.get());assertTrue(q.active());release.countDown();
            var callback=deliveries.poll(3,TimeUnit.SECONDS);assertNotNull(callback);callback.run();
            callback=deliveries.poll(3,TimeUnit.SECONDS);assertNotNull(callback);callback.run();assertEquals(List.of(2),values);
        } finally {release.countDown();worker.shutdownNow();}
    }
    @Test void fatalPreparationErrorIsRethrownOnOwnerWithoutAFalseRefusal() {
        var worker=new Manual();var owner=new Manual();var q=new OrderedOwnerPreparation(worker,owner);var refusal=new AtomicBoolean();
        var error=new AssertionError("fatal decoder error");
        q.submit(()->new OrderedOwnerPreparation.Work<Integer>(check->{throw error;},(v,e)->refusal.set(true)));
        worker.next();assertSame(error,assertThrows(AssertionError.class,owner::next));assertFalse(refusal.get());assertFalse(q.active());
    }
    @Test void executorRejectionUsesTheSameOwnerRefusalCompletion() {
        var owner=new Manual();var error=new RejectedExecutionException("unavailable");
        var q=new OrderedOwnerPreparation(task->{throw error;},owner);var failures=new ArrayList<RuntimeException>();
        q.submit(()->new OrderedOwnerPreparation.Work<>(check->42,(v,e)->failures.add(e)));
        assertEquals(List.of(error),failures);assertFalse(q.active());
    }
}
