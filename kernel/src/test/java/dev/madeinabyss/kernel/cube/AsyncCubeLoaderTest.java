package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class AsyncCubeLoaderTest {
    private static final BlockCoordinate ORIGIN = new BlockCoordinate(-1, -20000, -17);
    private static final CubeKey ORIGIN_KEY = CubeKey.fromBlock(ORIGIN);

    // Deterministic coordinate-dependent factory, so a wrong key or truncation is visible.
    private static CubeSnapshot generated(CubeKey key) {
        int[] states = new int[CubeSnapshot.BLOCK_COUNT];
        Arrays.fill(states, Math.floorMod(key.x() * 31 + key.y() * 17 + key.z() * 7, 1000) + 1);
        return new CubeSnapshot(key, states);
    }

    // Catches a background snapshot differing from the owner-thread oracle at negative coordinates.
    @Test void preparedWindowEqualsSynchronousMoveAndLoadsNothingOnOwnerThread() throws IOException {
        var oracle = new CubeResidencyService(new VersionedMemoryStore(), AsyncCubeLoaderTest::generated, 27, 27, 1);
        oracle.move("a", ORIGIN, 1, 1);
        var store = new VersionedMemoryStore();
        var service = new CubeResidencyService(store, AsyncCubeLoaderTest::generated, 27, 27, 1);
        try (var loader = new AsyncCubeLoader(store, AsyncCubeLoaderTest::generated, 2, 64, "test-loader")) {
            service.attachLoader(loader);
            assertTrue(service.tryMove("a", ORIGIN, 1, 1).isEmpty(), "first demand is deferred, never generated in the tick");
            assertEquals(0, service.space().loadedCubeCount());
            assertTrue(service.ownerWindow("a").isEmpty(), "deferred move keeps the previous (empty) demand");
            assertTrue(loader.awaitIdle(5000));
            var delta = service.tryMove("a", ORIGIN, 1, 1).orElseThrow();
            assertEquals(27, delta.added().size());
            for (var key : delta.added()) assertArrayEquals(oracle.space().snapshot(key).orElseThrow().states(), service.space().snapshot(key).orElseThrow().states(), key.toString());
            assertEquals(27, service.loadMetrics().preparedLoads());
            assertEquals(0, service.loadMetrics().ownerThreadLoads());
            assertEquals(1, service.loadMetrics().deferredMoves());
        }
    }

    // Catches the background path generating over a visited cube.
    @Test void savedCubeWinsOverGeneration() throws IOException {
        var store = new VersionedMemoryStore();
        int[] edited = generated(ORIGIN_KEY).states(); edited[0] = 4242;
        store.write(new CubeSnapshot(ORIGIN_KEY, edited));
        var generatorCalls = new AtomicInteger();
        Function<CubeKey, CubeSnapshot> factory = key -> { generatorCalls.incrementAndGet(); return generated(key); };
        try (var loader = new AsyncCubeLoader(store, factory, 1, 32, "test-loader")) {
            loader.request(ORIGIN_KEY, 0);
            assertTrue(loader.awaitIdle(5000));
            assertEquals(4242, loader.take(ORIGIN_KEY).orElseThrow().states()[0]);
            assertEquals(0, generatorCalls.get());
        }
    }

    // Catches a corrupt or unreadable file falling through to regeneration.
    @Test void storeFailureIsReportedNotGenerated() {
        var store = new VersionedMemoryStore(); store.failing.add(ORIGIN_KEY);
        var generatorCalls = new AtomicInteger();
        try (var loader = new AsyncCubeLoader(store, key -> { generatorCalls.incrementAndGet(); return generated(key); }, 1, 32, "test-loader")) {
            loader.request(ORIGIN_KEY, 0);
            assertTrue(loader.awaitIdle(5000));
            assertThrows(IOException.class, () -> loader.take(ORIGIN_KEY));
            assertEquals(0, generatorCalls.get());
        }
    }

    // Catches a stale prefetched copy replacing edits saved after the prefetch was read.
    @Test void saveAfterPrefetchInvalidatesTheProposalAndEditsSurviveReload() throws IOException {
        var store = new VersionedMemoryStore();
        var service = new CubeResidencyService(store, AsyncCubeLoaderTest::generated, 27, 27, 1);
        try (var loader = new AsyncCubeLoader(store, AsyncCubeLoaderTest::generated, 2, 64, "test-loader")) {
            service.attachLoader(loader);
            service.move("a", ORIGIN, 1, 1); // synchronous admission
            service.space().setBlock(ORIGIN, 777);
            // A stale proposal for the resident cube is prepared before the edit is saved.
            loader.request(ORIGIN_KEY, 0);
            assertTrue(loader.awaitIdle(5000));
            var far = new BlockCoordinate(ORIGIN.x() + 16 * 10, ORIGIN.y(), ORIGIN.z());
            service.move("a", far, 1, 1); // unload saves the edit and bumps the version
            assertFalse(loader.isReady(ORIGIN_KEY), "proposal read before the save is stale");
            assertTrue(service.tryMove("a", ORIGIN, 1, 1).isEmpty());
            assertTrue(loader.awaitIdle(5000));
            service.tryMove("a", ORIGIN, 1, 1).orElseThrow();
            assertEquals(777, service.space().block(ORIGIN).orElseThrow());
        }
    }

    // Catches accepting a read that overlapped a file replacement (odd version) or raced it.
    @Test void readOverlappingAWriteIsNeverAccepted() throws Exception {
        var store = new VersionedMemoryStore();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        Function<CubeKey, CubeSnapshot> slow = key -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return generated(key); };
        try (var loader = new AsyncCubeLoader(store, slow, 1, 32, "test-loader")) {
            loader.request(ORIGIN_KEY, 0);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            int[] saved = generated(ORIGIN_KEY).states(); saved[1] = 99;
            store.write(new CubeSnapshot(ORIGIN_KEY, saved)); // completes while the generation is running
            release.countDown();
            assertTrue(loader.awaitIdle(5000));
            assertTrue(loader.take(ORIGIN_KEY).isEmpty(), "generation raced a save and must be dropped");
            store.versionOverride.put(ORIGIN_KEY, 7L); // a writer currently replacing the file
            loader.request(ORIGIN_KEY, 0);
            assertTrue(loader.awaitIdle(5000));
            assertFalse(loader.isReady(ORIGIN_KEY), "odd version: no read is published");
        }
    }

    // Catches unbounded growth of prepared snapshots for abandoned predictions.
    @Test void preparedSnapshotsAreBounded() {
        var store = new VersionedMemoryStore();
        try (var loader = new AsyncCubeLoader(store, AsyncCubeLoaderTest::generated, 2, 32, "test-loader")) {
            for (int i = 0; i < 200; i++) loader.request(new CubeKey(-i, -1250, 3), 1);
            assertTrue(loader.awaitIdle(10000));
            loader.isReady(ORIGIN_KEY); // owner-side trim
            assertTrue(loader.metrics().ready() <= 32, "ready=" + loader.metrics().ready());
        }
    }

    // Catches FIFO requests from an abandoned target delaying an equally urgent current target.
    @Test void latestOwnerInterestRemovesObsoleteEqualPriorityQueue() throws Exception {
        var store = new VersionedMemoryStore();
        var gate = new CubeKey(-100, -1250, -100);
        var oldA = new CubeKey(-31, -21, 20);
        var oldB = new CubeKey(-32, -21, 20);
        var current = new CubeKey(31, -21, -20);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var starts = new CopyOnWriteArrayList<CubeKey>();
        try (var loader = new AsyncCubeLoader(store, key -> {
            starts.add(key);
            if (key.equals(gate)) { entered.countDown(); await(release); }
            return generated(key);
        }, 1, 32, "test-loader")) {
            interest(loader, "guard", Map.of(gate, 0)); loader.request(gate, 0);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            interest(loader, "alice", Map.of(oldA, 0, oldB, 0));
            loader.request(oldA, 0); loader.request(oldB, 0);
            interest(loader, "alice", Map.of(current, 0)); loader.request(current, 0);
            release.countDown(); assertTrue(loader.awaitIdle(5000));
            assertEquals(List.of(gate, current), starts, "abandoned same-priority cubes must not run ahead of the latest target");
            assertTrue(loader.take(current).isPresent());
        } finally { release.countDown(); }
    }

    // Catches cancellation based on only the moving player's interest, losing another player's cube.
    @Test void movingOwnerRetainsSharedInterestUntilLastOwnerLeaves() throws Exception {
        var store = new VersionedMemoryStore();
        var gate = new CubeKey(0, -1250, 0); var shared = new CubeKey(-2, -20, -3);
        var current = new CubeKey(29, -20, 30);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var loader = new AsyncCubeLoader(store, key -> {
            if (key.equals(gate)) { entered.countDown(); await(release); }
            return generated(key);
        }, 1, 32, "test-loader")) {
            interest(loader, "alice", Map.of(gate, 0)); loader.request(gate, 0);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            interest(loader, "alice", Map.of(gate, 0, shared, 1)); loader.request(shared, 1);
            interest(loader, "bob", Map.of(shared, 0)); loader.request(shared, 0);
            interest(loader, "carol", Map.of(shared, 2));
            interest(loader, "dana", Map.of(shared, 3));
            interest(loader, "alice", Map.of(gate, 0, current, 0)); loader.request(current, 0);
            loader.releaseInterest("bob");loader.releaseInterest("carol");
            release.countDown(); assertTrue(loader.awaitIdle(5000));
            assertTrue(loader.take(shared).isPresent(), "four-player union retains dana's cube after alice moves and bob/carol leave");
            assertTrue(loader.take(current).isPresent());
        } finally { release.countDown(); }
    }

    // Catches promoting an active shared request by duplicating generation and cancelling useful work.
    @Test void priorityPromotionDoesNotDuplicateAnActiveSharedLoad() throws Exception {
        var store = new VersionedMemoryStore();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var loader = new AsyncCubeLoader(store, key -> {
            calls.incrementAndGet(); entered.countDown(); await(release); return generated(key);
        }, 1, 32, "test-loader")) {
            interest(loader, "alice", Map.of(ORIGIN_KEY, 3)); loader.request(ORIGIN_KEY, 3);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            interest(loader, "bob", Map.of(ORIGIN_KEY, 0)); loader.request(ORIGIN_KEY, 0);
            interest(loader, "alice", Map.of());
            release.countDown(); assertTrue(loader.awaitIdle(5000));
            assertEquals(1, calls.get(), "running generation remains useful to bob; promotion must not create a second task");
            assertTrue(loader.take(ORIGIN_KEY).isPresent());
        } finally { release.countDown(); }
    }

    // Catches an obsolete blocking store read proceeding into expensive generation after its owner moved.
    @Test void abandonedReadSkipsGenerationAndCannotPublishAfterSavedEdit() throws Exception {
        var memory = new VersionedMemoryStore(); var current = new CubeKey(30, -1250, 30);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var oldGenerations = new AtomicInteger();
        VersionedCubeStore store = new VersionedCubeStore() {
            @Override public Optional<CubeSnapshot> read(CubeKey key) throws IOException {
                if (key.equals(ORIGIN_KEY)) { entered.countDown(); await(release); return Optional.empty(); }
                return memory.read(key);
            }
            @Override public void write(CubeSnapshot snapshot) { memory.write(snapshot); }
            @Override public long version(CubeKey key) { return memory.version(key); }
        };
        try (var loader = new AsyncCubeLoader(store, key -> {
            if (key.equals(ORIGIN_KEY)) oldGenerations.incrementAndGet(); return generated(key);
        }, 1, 32, "test-loader")) {
            interest(loader, "alice", Map.of(ORIGIN_KEY, 0)); loader.request(ORIGIN_KEY, 0);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            interest(loader, "alice", Map.of(current, 0)); loader.request(current, 0);
            int[] edited = generated(ORIGIN_KEY).states(); edited[0] = 8123;
            store.write(new CubeSnapshot(ORIGIN_KEY, edited));
            release.countDown(); assertTrue(loader.awaitIdle(5000));
            assertEquals(0, oldGenerations.get(), "cancelled read must not start obsolete generation");
            assertTrue(loader.take(ORIGIN_KEY).isEmpty(), "obsolete result cannot override a subsequent saved edit");
            assertEquals(8123, memory.read(ORIGIN_KEY).orElseThrow().states()[0]);
        } finally { release.countDown(); }
    }

    // Catches speculative ready cubes evicting the already prepared current ring under a bounded cache.
    @Test void readyCacheKeepsCurrentInterestBeforeSpeculation() {
        var store = new VersionedMemoryStore();
        try (var loader = new AsyncCubeLoader(store,AsyncCubeLoaderTest::generated,1,32,"test-loader")) {
            var demand = new java.util.HashMap<CubeKey,Integer>(); demand.put(ORIGIN_KEY,0);
            for(int i=0;i<40;i++) demand.put(new CubeKey(i,-1250,30),7);
            loader.replaceInterest("alice",demand);
            assertTrue(loader.awaitIdle(5000));
            assertTrue(loader.isReady(ORIGIN_KEY),"bounded ready cache must evict speculative priorities before the current physical ring");
            assertTrue(loader.metrics().ready()<=32);
        }
    }

    private static void interest(AsyncCubeLoader loader, String owner, Map<CubeKey, Integer> wanted) {
        loader.replaceInterest(owner, wanted);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    static final class VersionedMemoryStore implements VersionedCubeStore {
        final Map<CubeKey, CubeSnapshot> data = new ConcurrentHashMap<>();
        final Map<CubeKey, AtomicLong> versions = new ConcurrentHashMap<>();
        final Map<CubeKey, Long> versionOverride = new ConcurrentHashMap<>();
        final java.util.Set<CubeKey> failing = ConcurrentHashMap.newKeySet();
        @Override public Optional<CubeSnapshot> read(CubeKey key) throws IOException {
            if (failing.contains(key)) throw new IOException("corrupt test cube " + key);
            return Optional.ofNullable(data.get(key));
        }
        @Override public void write(CubeSnapshot snapshot) {
            var version = versions.computeIfAbsent(snapshot.key(), k -> new AtomicLong());
            version.incrementAndGet();
            try { data.put(snapshot.key(), snapshot); } finally { version.incrementAndGet(); }
        }
        @Override public long version(CubeKey key) {
            var forced = versionOverride.get(key); if (forced != null) return forced;
            var version = versions.get(key); return version == null ? 0 : version.get();
        }
    }
}
