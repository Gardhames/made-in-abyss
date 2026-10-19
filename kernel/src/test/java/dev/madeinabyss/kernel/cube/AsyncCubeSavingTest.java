package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.tick.CubeScheduledTicks;
import dev.madeinabyss.kernel.tick.SavedCubeTick;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class AsyncCubeSavingTest {
    @TempDir Path directory;
    private static final BlockCoordinate A = new BlockCoordinate(-1, -20000, -17);
    private static final BlockCoordinate B = new BlockCoordinate(16, -19984, 0);
    private static final CubeKey KA = CubeKey.fromBlock(A);
    private static CubeSnapshot empty(CubeKey key) { return new CubeSnapshot(key, new int[4096]); }
    private static CubeResidencyService service(CubeStore store, int limit) {
        var service = new CubeResidencyService(store, AsyncCubeSavingTest::empty, 27, 54, 2);
        service.enableAsyncSaving(limit, "test-cube-writer");
        return service;
    }
    private static void settle(CubeResidencyService service, BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            service.processPendingSaves();
            if (condition.getAsBoolean()) return;
            Thread.sleep(1);
        } while (System.nanoTime() < until);
        fail("save did not settle: " + service.saveMetrics());
    }

    @Test void backgroundFileWriteSurvivesReloadAtNegativeBoundaries() throws Exception {
        var store = new FileCubeStore(directory);
        try (var service = service(store, 256)) {
            service.move("a", A, 0, 0);
            service.space().setBlock(A, 71);
            service.move("a", B, 0, 0);
            settle(service, () -> !service.space().isLoaded(KA));
            assertEquals(0, service.saveMetrics().pinnedCubes());
        }
        try (var fresh = new CubeResidencyService(store, key -> { throw new AssertionError("must not regenerate"); }, 1, 1, 1)) {
            fresh.move("a", A, 0, 0);
            assertEquals(71, fresh.space().block(A).orElseThrow());
        }
    }

    @Test void pendingCubeIsReadableAndReacquiredWithoutLoading() throws Exception {
        var store = new GateStore();
        try (var service = service(store, 256)) {
            try {
                service.move("a", A, 0, 0); service.space().setBlock(A, 41);
                service.move("a", B, 0, 0);
                assertTrue(store.entered.await(5, TimeUnit.SECONDS));
                assertFalse(service.snapshot().containsKey(KA));
                assertEquals(41, service.space().block(A).orElseThrow());
                long loads = service.loadMetrics().ownerThreadLoads();
                service.move("a", A, 0, 0);
                assertEquals(loads, service.loadMetrics().ownerThreadLoads());
                store.proceed.countDown();
                settle(service, () -> service.saveMetrics().pendingWrites() == 0);
                assertTrue(service.space().isLoaded(KA));
                assertEquals(41, service.space().block(A).orElseThrow());
            } finally { store.proceed.countDown(); }
        }
    }

    @Test void editsTicksAndBlockEntitiesAfterSnapshotPreventStaleUnload() throws Exception {
        var store = new GateStore();
        try (var service = service(store, 256)) {
            try {
                service.move("a", A, 0, 0); service.space().setBlock(A, 1);
                service.move("a", B, 0, 0);
                assertTrue(store.entered.await(5, TimeUnit.SECONDS));
                service.space().setBlock(A, 2);
                var entity = new SavedCubeBlockEntity(A, "test:chest", new byte[]{1, 2, 3});
                service.space().setBlockEntity(entity);
                service.space().scheduledTicks().schedule(new CubeScheduledTicks.Tick(SavedCubeTick.Kind.BLOCK, "test:tick", A, 30, 0, 1));
                store.proceed.countDown();
                settle(service, () -> !service.space().isLoaded(KA));
                assertTrue(service.saveMetrics().staleSnapshots() > 0);
                var saved = store.read(KA).orElseThrow();
                assertEquals(List.of(entity), saved.blockEntities());
                assertEquals(1, saved.ticks().size());
                service.move("a", A, 0, 0);
                assertEquals(2, service.space().block(A).orElseThrow());
                assertEquals(1, service.space().scheduledTicks().pendingCount());
            } finally { store.proceed.countDown(); }
        }
    }

    @Test void failedWritePinsEditsAndRetriesAfterRecovery() throws Exception {
        var store = new GateStore(); store.proceed.countDown(); store.fail = true;
        try (var service = service(store, 256)) {
            service.move("a", A, 0, 0); service.space().setBlock(A, 92);
            service.move("a", B, 0, 0);
            settle(service, () -> service.saveMetrics().failures() > 0);
            assertEquals(92, service.space().block(A).orElseThrow());
            assertEquals(1, service.saveMetrics().pinnedCubes());
            assertFalse(service.saveMetrics().lastFailure().isEmpty());
            store.fail = false;
            settle(service, () -> !service.space().isLoaded(KA));
            assertTrue(service.saveMetrics().retries() > 0);
            service.move("a", A, 0, 0);
            assertEquals(92, service.space().block(A).orElseThrow());
        }
    }

    @Test void failedReleaseAndCloseKeepOwnerAndEditsForRetry() throws Exception {
        var store = new GateStore(); store.proceed.countDown();
        var service = service(store, 256);
        try {
            service.move("a", A, 0, 0); service.space().setBlock(A, 90);
            service.move("a", B, 0, 0); service.space().setBlock(B, 91);
            store.fail = true;
            assertThrows(IOException.class, () -> service.release("a"));
            assertEquals(Set.of(CubeKey.fromBlock(B)), service.ownerWindow("a"));
            assertEquals(91, service.space().block(B).orElseThrow());
            assertThrows(IOException.class, service::close);
            assertEquals(1, service.metrics().owners());
            store.fail = false;
            service.release("a");
            assertEquals(0, service.metrics().residentCubes());
            service.close();
            var fresh = new CubeResidencyService(store, AsyncCubeSavingTest::empty, 1, 2, 2);
            fresh.move("a", A, 0, 0); fresh.move("b", B, 0, 0);
            assertEquals(90, fresh.space().block(A).orElseThrow());
            assertEquals(91, fresh.space().block(B).orElseThrow());
        } finally { store.fail = false; service.close(); }
    }

    @Test void oversizedOutgoingWindowUsesSynchronousBackpressureWithoutLoss() throws Exception {
        var store = new FileCubeStore(directory);
        try (var service = service(store, 1)) {
            service.move("a", A, 0, 1); service.space().setBlock(A, 17);
            service.move("a", new BlockCoordinate(160, -19840, 160), 0, 1);
            assertTrue(service.saveMetrics().backpressureWrites() >= 3);
            assertTrue(service.saveMetrics().peakPinnedCubes() <= 1);
            assertFalse(service.space().isLoaded(KA));
            service.move("a", A, 0, 1);
            assertEquals(17, service.space().block(A).orElseThrow());
        }
    }

    @Test void closeDrainsPendingAndPersistsActiveEdits() throws Exception {
        var store = new FileCubeStore(directory);
        var service = service(store, 256);
        service.move("a", A, 0, 0); service.space().setBlock(A, 6);
        service.move("a", B, 0, 0); service.space().setBlock(B, 7);
        service.close(); service.close();
        assertEquals(0, service.saveMetrics().pendingWrites());
        assertFalse(service.space().isLoaded(KA));
        var fresh = new CubeResidencyService(store, AsyncCubeSavingTest::empty, 1, 2, 2);
        fresh.move("a", A, 0, 0); fresh.move("b", B, 0, 0);
        assertEquals(6, fresh.space().block(A).orElseThrow());
        assertEquals(7, fresh.space().block(B).orElseThrow());
    }

    @Test void reacquisitionAfterWriteFinishedStillUsesResidentCopy() throws Exception {
        var store = new GateStore(); store.proceed.countDown();
        try (var service = service(store, 256)) {
            service.move("a", A, 0, 0); service.space().setBlock(A, 12);
            service.move("a", B, 0, 0);
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            // Wait for the writer without owner-side acknowledgement/unload.
            synchronized (store) { assertTrue(store.read(KA).isPresent()); }
            long loads = service.loadMetrics().ownerThreadLoads();
            service.move("a", A, 0, 0);
            assertEquals(loads, service.loadMetrics().ownerThreadLoads());
            assertEquals(12, service.space().block(A).orElseThrow());
        }
    }

    @Test void newerDirectWriteWinsOverQueuedOldSnapshot() throws Exception {
        var store = new GateStore(); store.proceed.countDown();
        try (var service = service(store, 256)) {
            service.move("a", A, 0, 0); service.space().setBlock(A, 11);
            CubeSnapshot latest;
            synchronized (store) {
                // The writer cannot enter the store until this newer acknowledged edit is saved.
                service.move("a", B, 0, 0);
                service.space().setBlock(A, 22);
                latest = service.space().snapshot(KA).orElseThrow();
                store.write(latest);
            }
            settle(service, () -> !service.space().isLoaded(KA));
            assertTrue(service.saveMetrics().staleSnapshots() > 0);
            for (var written : store.writes) if (written.key().equals(KA))
                assertArrayEquals(latest.states(), written.states(), "old queued copy overwrote a direct acknowledged edit");
        }
    }

    @Test void tickOnlyAndPayloadOnlyChangesAreAlsoResaved() throws Exception {
        for (boolean tickOnly : List.of(true, false)) {
            var store = new GateStore();
            try (var service = service(store, 256)) {
                try {
                    service.move("a", A, 0, 0); service.move("a", B, 0, 0);
                    assertTrue(store.entered.await(5, TimeUnit.SECONDS));
                    if (tickOnly) service.space().scheduledTicks().schedule(new CubeScheduledTicks.Tick(SavedCubeTick.Kind.FLUID, "test:water", A, 7, 0, 0));
                    else service.space().setBlockEntity(new SavedCubeBlockEntity(A, "test:chest", new byte[]{4}));
                    store.proceed.countDown();
                    settle(service, () -> !service.space().isLoaded(KA));
                    var saved = store.read(KA).orElseThrow();
                    assertEquals(tickOnly ? 1 : 0, saved.ticks().size());
                    assertEquals(tickOnly ? 0 : 1, saved.blockEntities().size());
                    assertTrue(service.saveMetrics().staleSnapshots() > 0);
                } finally { store.proceed.countDown(); }
            }
        }
    }

    @Test void realFilesystemFailureRetainsPinnedEditsUntilRepair() throws Exception {
        var root = directory.resolve("blocked");
        Files.writeString(root, "existing data");
        var files = new FileCubeStore(root);
        // Reads describe new cubes; the real FileCubeStore write fails on a non-directory root.
        CubeStore store = new CubeStore() {
            public Optional<CubeSnapshot> read(CubeKey key) { return Optional.empty(); }
            public void write(CubeSnapshot snapshot) throws IOException { files.write(snapshot); }
        };
        try (var service = service(store, 256)) {
            service.move("a", A, 0, 0); service.space().setBlock(A, 52);
            service.move("a", B, 0, 0);
            settle(service, () -> service.saveMetrics().failures() > 0);
            assertEquals(52, service.space().block(A).orElseThrow());
            assertEquals("existing data", Files.readString(root));
            Files.delete(root);
            service.flush();
            assertFalse(service.space().isLoaded(KA));
            var fresh = new CubeResidencyService(files, AsyncCubeSavingTest::empty, 1, 1, 1);
            fresh.move("a", A, 0, 0);
            assertEquals(52, fresh.space().block(A).orElseThrow());
        }
    }

    @Test void accumulatedPinsHitBoundAndFailedBackpressureDoesNotMoveOwner() throws Exception {
        var store = new GateStore(); store.proceed.countDown(); store.fail = true;
        try (var service = service(store, 1)) {
            try {
                service.move("a", A, 0, 0); service.space().setBlock(A, 63);
                service.move("a", B, 0, 0); service.space().setBlock(B, 64);
                settle(service, () -> service.saveMetrics().failures() > 0);
                var c = new BlockCoordinate(32, -19968, 16);
                assertThrows(IOException.class, () -> service.move("a", c, 0, 0));
                assertEquals(Set.of(CubeKey.fromBlock(B)), service.ownerWindow("a"));
                assertEquals(63, service.space().block(A).orElseThrow());
                assertEquals(64, service.space().block(B).orElseThrow());
                assertTrue(service.space().block(c).isEmpty());
                store.fail = false;
                service.move("a", c, 0, 0);
                assertTrue(service.saveMetrics().backpressureWrites() >= 1);
                assertTrue(service.saveMetrics().backpressureEvents() > 0);
                assertTrue(service.saveMetrics().peakPinnedCubes() <= 1);
                service.flush();
                service.move("a", A, 0, 0); assertEquals(63, service.space().block(A).orElseThrow());
                service.move("a", B, 0, 0); assertEquals(64, service.space().block(B).orElseThrow());
            } finally { store.fail = false; }
        }
    }

    @Test void sharedOwnersAndFlushKeepActiveContentsResident() throws Exception {
        var store = new FileCubeStore(directory);
        try (var service = service(store, 256)) {
            service.move("a", A, 0, 0); service.move("b", A, 0, 0);
            service.space().setBlock(A, 73);
            service.move("a", B, 0, 0);
            assertEquals(0, service.saveMetrics().pinnedCubes());
            assertEquals(0, service.saveMetrics().submitted());
            service.move("b", B, 0, 0);
            service.space().setBlock(B, 74);
            service.flush();
            assertEquals(2, service.metrics().owners());
            assertFalse(service.space().isLoaded(KA));
            assertEquals(74, service.space().block(B).orElseThrow());
            assertEquals(0, service.saveMetrics().pendingWrites());
            var fresh = new CubeResidencyService(store, AsyncCubeSavingTest::empty, 1, 2, 2);
            fresh.move("a", A, 0, 0); fresh.move("b", B, 0, 0);
            assertEquals(73, fresh.space().block(A).orElseThrow());
            assertEquals(74, fresh.space().block(B).orElseThrow());
        }
    }

    @Test void pendingAndCompletedSavesCannotRestoreStaleLoaderProposal() throws Exception {
        var store = new GateStore();
        try (var service = service(store, 256);
             var loader = new AsyncCubeLoader(store, AsyncCubeSavingTest::empty, 1, 32, "test-save-loader")) {
            try {
                service.attachLoader(loader);
                service.move("a", A, 0, 0); service.space().setBlock(A, 81);
                loader.request(KA, 0); assertTrue(loader.awaitIdle(5000));
                service.move("a", B, 0, 0);
                assertTrue(store.entered.await(5, TimeUnit.SECONDS));
                long loads = service.loadMetrics().ownerThreadLoads();
                service.tryMove("a", A, 0, 0).orElseThrow();
                assertEquals(81, service.space().block(A).orElseThrow());
                assertEquals(loads, service.loadMetrics().ownerThreadLoads());
                store.proceed.countDown();
                settle(service, () -> service.saveMetrics().pendingWrites() == 0);
                assertTrue(loader.take(KA).isEmpty());
                service.move("a", B, 0, 0);
                settle(service, () -> !service.space().isLoaded(KA));
                service.tryMove("a", A, 0, 0);
                assertTrue(loader.awaitIdle(5000));
                service.tryMove("a", A, 0, 0).orElseThrow();
                assertEquals(81, service.space().block(A).orElseThrow());
            } finally { store.proceed.countDown(); }
        }
    }

    @Test void loaderReadOverlappingBackgroundWriteIsRejected() throws Exception {
        var store = new GateStore(); store.proceed.countDown();
        var readStarted = new CountDownLatch(1); var finishRead = new CountDownLatch(1);
        try (var service = service(store, 256);
             var loader = new AsyncCubeLoader(store, key -> {
                 readStarted.countDown();
                 try {
                     if (!finishRead.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("read gate timed out");
                 } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                 return empty(key);
             }, 1, 32, "test-overlap-loader")) {
            try {
                service.move("a", A, 0, 0); service.space().setBlock(A, 101);
                loader.request(KA, 0);
                assertTrue(readStarted.await(5, TimeUnit.SECONDS));
                service.move("a", B, 0, 0);
                settle(service, () -> !service.space().isLoaded(KA));
                finishRead.countDown();
                assertTrue(loader.awaitIdle(5000));
                assertTrue(loader.take(KA).isEmpty(), "read began before the background save and must be discarded");
                loader.request(KA, 0); assertTrue(loader.awaitIdle(5000));
                var reloaded = new SparseCubeSpace(); reloaded.load(loader.take(KA).orElseThrow());
                assertEquals(101, reloaded.block(A).orElseThrow());
            } finally { finishRead.countDown(); }
        }
    }

    /** Only immutable snapshots cross threads. Reads/version checks never block on the gate. */
    private static final class GateStore implements VersionedCubeStore {
        final Map<CubeKey, CubeSnapshot> data = new ConcurrentHashMap<>();
        final Map<CubeKey, AtomicLong> versions = new ConcurrentHashMap<>();
        final CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        final java.util.Queue<CubeSnapshot> writes = new java.util.concurrent.ConcurrentLinkedQueue<>();
        volatile boolean fail;
        public Optional<CubeSnapshot> read(CubeKey key) { return Optional.ofNullable(data.get(key)); }
        public long version(CubeKey key) { var v = versions.get(key); return v == null ? 0 : v.get(); }
        public synchronized void write(CubeSnapshot snapshot) throws IOException {
            var v = versions.computeIfAbsent(snapshot.key(), key -> new AtomicLong());
            v.incrementAndGet();
            try {
                entered.countDown();
                try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IOException("test gate timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                if (fail) throw new IOException("test disk failure");
                data.put(snapshot.key(), snapshot);
                writes.add(snapshot);
            } finally { v.incrementAndGet(); }
        }
    }
}
