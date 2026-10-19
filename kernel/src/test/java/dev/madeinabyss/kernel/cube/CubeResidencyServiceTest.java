package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.ticket.CubeTickets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CubeResidencyServiceTest {
    @TempDir Path directory;
    private static final BlockCoordinate ORIGIN = new BlockCoordinate(-1, -20000, -17);
    private static final CubeKey ORIGIN_KEY = new CubeKey(-1, -1250, -2);
    private static final BlockCoordinate FAR = new BlockCoordinate(47, -15904, 16);
    private static final CubeKey FAR_KEY = new CubeKey(2, -994, 1);

    // Catches committing tickets without loading data, or truncation of negative section coordinates.
    @Test void movingOwnerLoadsItsNegativeWindowBeforeReportingDemand() throws IOException {
        var service = service(new MemoryStore(), 27, 27, 1);
        var delta = service.move("a", ORIGIN, 1, 1);
        assertEquals(27, delta.added().size());
        assertTrue(delta.removed().isEmpty());
        assertEquals(27, service.space().loadedCubeCount());
        assertEquals(27, service.ownerWindow("a").size());
        assertEquals(1, service.metrics().owners());
        assertTrue(service.space().isLoaded(new CubeKey(-2, -1251, -3)));
        assertEquals(0, service.space().block(ORIGIN).orElseThrow());
        assertTrue(service.space().block(FAR).isEmpty());
    }

    // Catches unloading a shared cube or discarding the last owner's edits.
    @Test void sharedCubeSurvivesUntilLastOwnerReleaseThenRestoresEdits() throws IOException {
        var store = new MemoryStore();
        var service = service(store, 1, 1, 2);
        service.move("a", ORIGIN, 0, 0); service.move("b", ORIGIN, 0, 0);
        service.space().setBlock(ORIGIN, 71);
        assertTrue(service.release("a").removed().isEmpty());
        assertEquals(Map.of(ORIGIN_KEY, 1), service.snapshot());
        assertEquals(71, service.space().block(ORIGIN).orElseThrow());
        assertEquals(Set.of(ORIGIN_KEY), service.release("b").removed());
        assertEquals(0, service.space().loadedCubeCount());
        service.move("a", ORIGIN, 0, 0);
        assertEquals(71, service.space().block(ORIGIN).orElseThrow());
        assertEquals(new CubeTickets.Delta(Set.of(), Set.of()), service.release("absent"));
    }

    // Catches regenerating saved cubes on movement or failing to persist a released window.
    @Test void editSurvivesAwayReturnAndAFreshServiceUsingRealFiles() throws IOException {
        var store = new FileCubeStore(directory);
        var first = service(store, 1, 1, 1);
        first.move("a", ORIGIN, 0, 0); first.space().setBlock(ORIGIN, 42);
        var delta = first.move("a", FAR, 0, 0);
        assertEquals(Set.of(FAR_KEY), delta.added()); assertEquals(Set.of(ORIGIN_KEY), delta.removed());
        assertFalse(first.space().isLoaded(ORIGIN_KEY));
        assertEquals(1, first.metrics().residentCubes());
        first.move("a", ORIGIN, 0, 0); assertEquals(42, first.space().block(ORIGIN).orElseThrow());
        first.release("a");
        var fresh = new CubeResidencyService(store, key -> { throw new AssertionError("Saved data must not regenerate"); }, 1, 1, 1);
        fresh.move("a", ORIGIN, 0, 0);
        assertEquals(42, fresh.space().block(ORIGIN).orElseThrow());
    }

    // Catches releasing the old demand before a new read succeeds, or regenerating after read errors.
    @Test void readFailureRetainsOldTicketsAndAllEditedResidentData() throws IOException {
        var store = new MemoryStore();
        var generated = new AtomicInteger();
        var service = new CubeResidencyService(store, key -> { generated.incrementAndGet(); return empty(key); }, 1, 1, 1);
        service.move("a", ORIGIN, 0, 0); service.space().setBlock(ORIGIN, 17);
        store.failRead = FAR_KEY;
        assertThrows(IOException.class, () -> service.move("a", FAR, 0, 0));
        assertEquals(Map.of(ORIGIN_KEY, 1), service.snapshot());
        assertEquals(Set.of(ORIGIN_KEY), service.ownerWindow("a"));
        assertEquals(17, service.space().block(ORIGIN).orElseThrow());
        assertTrue(service.space().block(FAR).isEmpty());
        assertEquals(1, generated.get());
        assertEquals(1, service.metrics().residentCubes());
        assertEquals(new CubeResidencyService.Metrics(1, 1, 1, 0, 0), service.metrics());
    }

    // Catches unloading even one cube before every outgoing save has succeeded.
    @Test void lateSaveFailureLeavesWholePreviousWindowAndOwnerDemandIntact() throws IOException {
        var store = new MemoryStore();
        var service = service(store, 3, 3, 1);
        service.move("a", ORIGIN, 0, 1); service.space().setBlock(ORIGIN, 19);
        var before = service.snapshot();
        store.failAfterWrites = 1;
        assertThrows(IOException.class, () -> service.move("a", FAR, 0, 1));
        assertEquals(before, service.snapshot());
        assertEquals(before.keySet(), service.ownerWindow("a"));
        assertEquals(3, service.space().loadedCubeCount());
        assertEquals(19, service.space().block(ORIGIN).orElseThrow());
        assertTrue(service.space().block(FAR).isEmpty());
        assertEquals(1, store.writes);
        assertEquals(new CubeResidencyService.Metrics(1, 3, 3, 1, 0), service.metrics());
        store.failAfterWrites = Integer.MAX_VALUE;
        service.move("a", FAR, 0, 1);
        assertEquals(3, service.space().loadedCubeCount());
        service.move("a", ORIGIN, 0, 1);
        assertEquals(19, service.space().block(ORIGIN).orElseThrow());
    }

    // Catches disconnect removing the owner despite failed persistence.
    @Test void failedReleaseKeepsOwnerAndCanBeRetriedAfterStoreRecovery() throws IOException {
        var store = new MemoryStore(); var service = service(store, 1, 1, 1);
        service.move("a", ORIGIN, 0, 0); service.space().setBlock(ORIGIN, 90);
        store.failAfterWrites = 0;
        assertThrows(IOException.class, () -> service.release("a"));
        assertEquals(1, service.metrics().owners());
        assertEquals(90, service.space().block(ORIGIN).orElseThrow());
        assertEquals(Set.of(ORIGIN_KEY), service.ownerWindow("a"));
        store.failAfterWrites = Integer.MAX_VALUE;
        service.release("a"); assertEquals(0, service.metrics().owners());
        assertEquals(0, service.metrics().residentCubes());
    }

    // Catches generating before validating requested volume/domain or active/owner budgets.
    @Test void invalidMoveBudgetsAndCoordinatesCauseNoReadsOrStateChanges() throws IOException {
        var store = new MemoryStore(); var service = service(store, 1, 1, 1);
        service.move("a", ORIGIN, 0, 0); store.reads = 0;
        assertThrows(IllegalArgumentException.class, () -> service.move("a", FAR, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> service.move("a", FAR, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> service.move("a", new BlockCoordinate(Integer.MAX_VALUE, 0, 0), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> service.move("b", FAR, 0, 0));
        assertEquals(0, store.reads);
        assertEquals(Map.of(ORIGIN_KEY, 1), service.snapshot());
        assertEquals(1, service.space().loadedCubeCount());
    }

    // Catches accepting another coordinate's stored snapshot or factory output before commit.
    @Test void wrongSnapshotCoordinateAndThrowingFactoryNeverPublishPartialResidency() throws IOException {
        var store = new MemoryStore(); store.data.put(ORIGIN_KEY, empty(FAR_KEY));
        var service = service(store, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> service.move("a", ORIGIN, 0, 0));
        assertTrue(service.snapshot().isEmpty()); assertEquals(0, service.space().loadedCubeCount());
        var wrongFactory = new CubeResidencyService(new MemoryStore(), key -> empty(FAR_KEY), 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> wrongFactory.move("a", ORIGIN, 0, 0));
        assertEquals(0, wrongFactory.metrics().owners());
        var throwingFactory = new CubeResidencyService(new MemoryStore(), key -> { throw new IllegalStateException("generator unavailable"); }, 1, 1, 1);
        assertThrows(IllegalStateException.class, () -> throwingFactory.move("a", ORIGIN, 0, 0));
        assertEquals(0, throwingFactory.space().loadedCubeCount());
    }

    // Catches flush releasing tickets/data or not writing edits while owners remain active.
    @Test void flushPersistsAllResidentEditsWithoutUnloadingDemand() throws IOException {
        var store = new FileCubeStore(directory); var service = service(store, 1, 2, 2);
        service.move("a", ORIGIN, 0, 0); service.move("b", FAR, 0, 0);
        service.space().setBlock(ORIGIN, 21); service.space().setBlock(FAR, 22);
        service.flush();
        assertEquals(2, service.metrics().residentCubes()); assertEquals(2, service.metrics().owners());
        var fresh = new CubeResidencyService(store, key -> { throw new AssertionError("Saved cube must load"); }, 1, 2, 2);
        fresh.move("a", ORIGIN, 0, 0); fresh.move("b", FAR, 0, 0);
        assertEquals(21, fresh.space().block(ORIGIN).orElseThrow());
        assertEquals(22, fresh.space().block(FAR).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> service.snapshot().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.ownerWindow("a").clear());
    }

    // Catches preview APIs mutating their ledger or returning owner-local instead of union deltas.
    @Test void ticketPreviewsValidateAndDescribeWithoutChangingOwnership() {
        var tickets = new CubeTickets(1, 2, 2);
        assertEquals(Set.of(ORIGIN_KEY), tickets.previewMove("a", ORIGIN, 0, 0).added());
        assertTrue(tickets.snapshot().isEmpty());
        tickets.move("a", ORIGIN, 0, 0); tickets.move("b", ORIGIN, 0, 0);
        assertTrue(tickets.previewRemove("a").removed().isEmpty());
        assertEquals(Set.of(FAR_KEY), tickets.previewMove("a", FAR, 0, 0).added());
        assertTrue(tickets.previewMove("a", FAR, 0, 0).removed().isEmpty());
        assertEquals(Map.of(ORIGIN_KEY, 2), tickets.snapshot());
        assertThrows(IllegalArgumentException.class, () -> tickets.previewMove("c", FAR, 0, 0));
        tickets.remove("b");
        assertEquals(Set.of(ORIGIN_KEY), tickets.previewRemove("a").removed());
        assertEquals(1, tickets.ownerCount());
    }

    // Catches reporting staged loads or failed writes as successful committed work.
    @Test void cumulativeMetricsCountOnlyCommittedResidencyAndCompletedWrites() throws IOException {
        var store = new MemoryStore(); var service = service(store, 1, 1, 1);
        service.move("a", ORIGIN, 0, 0);
        assertEquals(new CubeResidencyService.Metrics(1, 1, 1, 0, 0), service.metrics());
        service.flush();
        assertEquals(new CubeResidencyService.Metrics(1, 1, 1, 1, 0), service.metrics());
        service.move("a", FAR, 0, 0);
        assertEquals(new CubeResidencyService.Metrics(1, 1, 2, 2, 1), service.metrics());
        store.failAfterWrites = store.writes;
        assertThrows(IOException.class, () -> service.release("a"));
        assertEquals(new CubeResidencyService.Metrics(1, 1, 2, 2, 1), service.metrics());
        store.failAfterWrites = Integer.MAX_VALUE;
        service.release("a");
        assertEquals(new CubeResidencyService.Metrics(0, 0, 2, 3, 2), service.metrics());
    }

    private static CubeResidencyService service(CubeStore store, int window, int active, int owners) {
        return new CubeResidencyService(store, CubeResidencyServiceTest::empty, window, active, owners);
    }
    private static CubeSnapshot empty(CubeKey key) { return new CubeSnapshot(key, new int[4096]); }
    private static final class MemoryStore implements CubeStore {
        final Map<CubeKey, CubeSnapshot> data = new HashMap<>();
        CubeKey failRead;
        int reads;
        int writes;
        int failAfterWrites = Integer.MAX_VALUE;
        @Override public Optional<CubeSnapshot> read(CubeKey key) throws IOException {
            reads++;
            if (key.equals(failRead)) throw new IOException("fixture read failure");
            return Optional.ofNullable(data.get(key));
        }
        @Override public void write(CubeSnapshot snapshot) throws IOException {
            if (writes >= failAfterWrites) throw new IOException("fixture write failure");
            data.put(snapshot.key(), snapshot); writes++;
        }
    }
}
