package dev.madeinabyss.kernel.cube;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedObservationQueueTest {
    private static final CubeKey A=new CubeKey(-1,-4096,-1), B=new CubeKey(2,3,4), C=new CubeKey(5,6,7);
    private static int[] states(int value){var result=new int[4096];java.util.Arrays.fill(result,value);return result;}

    @Test void delayedBatchCannotEraseNewerEditEvenAfterCubeLeavesWindow() {
        var queue=new GeneratedObservationQueue(4,4,2);
        queue.observe(Map.of(A,states(1)));
        var old=queue.takeBatch();
        queue.observe(Map.of(A,states(2),B,states(0)));
        queue.observe(Map.of(A,states(3),C,states(0)));
        assertEquals(3,queue.pendingCount());
        var first=GeneratedObservationQueue.compare(old,k->states(0));
        assertFalse(queue.commit(first).geometryChanged());
        assertFalse(queue.retained().containsKey(A));
        while(queue.pendingCount()>0){var batch=queue.takeBatch();queue.commit(GeneratedObservationQueue.compare(batch,k->states(0)));}
        assertEquals(3,queue.retained().get(A)[0]);
        assertEquals(1,queue.retained().size());
    }

    @Test void observationIsImmutableAndOnlyVerifiedOverlapCanSkipComparison() {
        var queue=new GeneratedObservationQueue(4,4,2);var input=states(4);var calls=new AtomicInteger();
        queue.observe(Map.of(A,input));input[0]=9;
        queue.observe(Map.of(A,states(4)));
        assertEquals(1,queue.pendingCount());
        var result=queue.commit(GeneratedObservationQueue.compare(queue.takeBatch(),k->{calls.incrementAndGet();return states(0);}));
        assertTrue(result.geometryChanged());assertEquals(4,queue.retained().get(A)[0]);assertEquals(1,calls.get());
        queue.observe(Map.of(A,states(4)));assertEquals(0,queue.pendingCount());
        queue.observe(Map.of(B,states(0)));assertEquals(1,queue.pendingCount());
        queue.commit(GeneratedObservationQueue.compare(queue.takeBatch(),k->states(0)));
        queue.observe(Map.of(B,states(0)));assertEquals(0,queue.pendingCount());
    }

    @Test void exactPristineReversionRemovesEditAndClearRejectsOldSessionBatch() {
        var queue=new GeneratedObservationQueue(4,4,2);queue.baseline(Map.of(A,states(2)));
        queue.observe(Map.of(A,states(0)));
        assertTrue(queue.commit(GeneratedObservationQueue.compare(queue.takeBatch(),k->states(0))).geometryChanged());
        assertTrue(queue.retained().isEmpty());
        queue.observe(Map.of(A,states(5)));var old=queue.takeBatch();queue.clear();
        queue.baseline(Map.of(B,states(8)));queue.observe(Map.of(A,states(6)));
        assertFalse(queue.commit(GeneratedObservationQueue.compare(old,k->states(0))).accepted());
        assertEquals(1,queue.pendingCount());assertEquals(8,queue.retained().get(B)[0]);
        queue.commit(GeneratedObservationQueue.compare(queue.takeBatch(),k->states(0)));
        assertEquals(6,queue.retained().get(A)[0]);
    }

    @Test void newerPreInventoryObservationOverridesSavedBaselineWithoutBeingLost() {
        var queue=new GeneratedObservationQueue(4,4,2);
        queue.observe(Map.of(A,states(0),B,states(3)));
        queue.baseline(Map.of(A,states(8)));
        queue.commit(GeneratedObservationQueue.compare(queue.takeBatch(),k->states(0)));
        assertFalse(queue.retained().containsKey(A));assertEquals(3,queue.retained().get(B)[0]);
    }

    @Test void pendingAndRetainedLimitsRefuseAtomicallyWithoutDroppingEdits() {
        var queue=new GeneratedObservationQueue(1,2,1);
        queue.observe(Map.of(A,states(2)));
        assertThrows(IllegalArgumentException.class,()->queue.observe(Map.of(B,states(3),C,states(4))));
        assertEquals(1,queue.pendingCount());
        queue.commit(GeneratedObservationQueue.compare(queue.takeBatch(),k->states(0)));
        queue.observe(Map.of(B,states(3)));var done=GeneratedObservationQueue.compare(queue.takeBatch(),k->states(0));
        assertThrows(IllegalArgumentException.class,()->queue.commit(done));
        assertEquals(2,queue.retained().get(A)[0]);assertEquals(1,queue.pendingCount());
        queue.observe(Map.of(A,states(0)));
        // The rejected batch is retryable; a newer pristine A can free capacity first.
        queue.clear();assertEquals(0,queue.pendingCount());assertTrue(queue.retained().isEmpty());
    }

    @Test void finiteBatchAndVerifiedCacheStayBoundedDuringLongExploration() {
        var queue=new GeneratedObservationQueue(2,3,2);
        for(int x=-50;x<50;x++){
            queue.observe(Map.of(new CubeKey(x,-1,0),states(0)));
            var batch=queue.takeBatch();assertTrue(batch.size()<=2);
            assertFalse(queue.commit(GeneratedObservationQueue.compare(batch,k->states(0))).geometryChanged());
            assertTrue(queue.verifiedCount()<=3);assertTrue(queue.retained().isEmpty());
        }
    }

    @Test void realDelayedWorkerCannotBlockOwnerOrCommitSupersededObservations() throws Exception {
        var queue=new GeneratedObservationQueue(4,4,2);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            queue.observe(Map.of(A,states(2)));var batch=queue.takeBatch();
            var result=executor.submit(()->GeneratedObservationQueue.compare(batch,key->{
                entered.countDown();
                try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("worker gate timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                return states(0);
            }));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            queue.observe(Map.of(A,states(3)));queue.observe(Map.of(A,states(4),B,states(0)));
            assertFalse(result.isDone());assertEquals(2,queue.pendingCount());
            release.countDown();assertFalse(queue.commit(result.get(5,java.util.concurrent.TimeUnit.SECONDS)).geometryChanged());
            queue.commit(GeneratedObservationQueue.compare(queue.takeBatch(),key->states(0)));
            assertEquals(4,queue.retained().get(A)[0]);assertFalse(queue.retained().containsKey(B));
        }finally{release.countDown();executor.shutdownNow();}
    }

    @Test void invalidationKeepsOffWindowObservationButRejectsOldEpoch() {
        var queue=new GeneratedObservationQueue(4,4,2);queue.observe(Map.of(A,states(9)));
        var old=queue.takeBatch();queue.invalidateBatch();
        assertFalse(queue.commit(GeneratedObservationQueue.compare(old,k->states(0))).accepted());
        assertEquals(1,queue.pendingCount());
        queue.commit(GeneratedObservationQueue.compare(queue.takeBatch(),k->states(0)));
        assertEquals(9,queue.retained().get(A)[0]);
    }
}
