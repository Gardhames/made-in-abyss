package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.ticket.CubeTickets;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Single-owner-thread residency boundary, independent of Minecraft ticking. Commits are
 * synchronous by default. Optional background loading prepares version-checked snapshots;
 * optional saving pins outgoing cubes until durable acknowledgement of their current contents.
 * Failed reads/factories retain previous demand. Async write failures retain pinned contents;
 * release/flush/close are synchronous durability barriers and failed release retains its owner.
 * Disk writes that succeeded before a later error remain valid; this is not a disk transaction.
 * Staging holds one new active union in addition to the old one and at most maxPinnedCubes pins.
 * The caller must hold residency for the whole player tick, not just a movement callback.
 */
public final class CubeResidencyService implements AutoCloseable {
    public static final int DEFAULT_MAX_PINNED_CUBES = 256;
    private final SparseCubeSpace space = new SparseCubeSpace();
    private final CubeStore store;
    private final Function<CubeKey, CubeSnapshot> missingFactory;
    private final CubeTickets tickets;
    private long loadedCubes;
    private long savedCubes;
    private long unloadedCubes;
    private AsyncCubeLoader loader;
    private long deferredMoves,preparedLoads,ownerThreadLoads;
    private AsyncCubeSaver saver;
    private int maxPinnedCubes, peakPinnedCubes;
    private final Set<CubeKey> pinned = new HashSet<>();
    private final Map<CubeKey, PendingSave> pending = new LinkedHashMap<>();
    private final Map<CubeKey, Long> retryAfter = new HashMap<>();
    private long submittedSaves, completedSaves, saveFailures, saveRetries, staleSnapshots, backpressureWrites;
    private long ownerThreadSaves;
    private long backpressureEvents;
    private String lastSaveFailure = "";
    private boolean closed;
    private record PendingSave(CubeSnapshot snapshot, Future<Boolean> result) { }

    /** Explicit opt-in keeps existing kernel callers and synchronous tests unchanged. */
    public void enableAsyncSaving(int maxPinnedCubes, String threadName) {
        requireOpen();
        if (saver != null || maxPinnedCubes < 1) throw new IllegalArgumentException("writer already enabled or invalid pin limit");
        this.maxPinnedCubes = maxPinnedCubes;
        saver = new AsyncCubeSaver(store, threadName);
    }

    private void requireOpen() {
        space.scheduledTicks().assertOwner();
        if (closed) throw new IllegalStateException("cube residency service closed");
    }

    /** Optional background source. Its proposals are accepted only against the current store version. */
    public void attachLoader(AsyncCubeLoader loader) { this.loader = loader; }
    public AsyncCubeLoader loader() { return loader; }

    /**
     * Owner-thread move that never reads or generates a missing cube itself. When any newly
     * demanded cube is not yet prepared, owner demand stays unchanged and missing keys are
     * requested first; independent outgoing saves may finish while the old window is retained.
     * Without a loader this is the ordinary synchronous move.
     */
    public Optional<CubeTickets.Delta> tryMove(String owner, BlockCoordinate position, int horizontalRadius, int verticalRadius) throws IOException {
        requireOpen();
        if (loader == null) return Optional.of(move(owner, position, horizontalRadius, verticalRadius));
        CubeTickets.Delta delta = tickets.previewMove(owner, position, horizontalRadius, verticalRadius);
        processPendingSaves(delta.added());
        boolean complete = true;
        for (CubeKey key : delta.added()) {
            if (space.isLoaded(key) || loader.isReady(key)) continue;
            loader.request(key, 0);
            complete = false;
        }
        if (!complete) { deferredMoves++; return Optional.empty(); }
        return Optional.of(move(owner, position, horizontalRadius, verticalRadius));
    }

    /** Requests non-resident cubes ahead of demand; lower priority values are prepared first. */
    public void prefetch(Iterable<CubeKey> keys, int priority) {
        if (loader == null) return;
        for (CubeKey key : keys) if (!space.isLoaded(key)) loader.request(key, priority);
    }
    public CubeResidencyService(CubeStore store, Function<CubeKey, CubeSnapshot> missingFactory,
                                int maxWindowCubes, int maxActiveCubes, int maxOwners) {
        this.store = Objects.requireNonNull(store, "store");
        this.missingFactory = Objects.requireNonNull(missingFactory, "missingFactory");
        tickets = new CubeTickets(maxWindowCubes, maxActiveCubes, maxOwners);
    }

    public CubeTickets.Delta move(String owner, BlockCoordinate position, int horizontalRadius, int verticalRadius) throws IOException {
        requireOpen();
        CubeTickets.Delta delta = tickets.previewMove(owner, position, horizontalRadius, verticalRadius);
        if (saver != null) {
            processPendingSaves(delta.added());
            // Bound both resident pins and queued snapshots, including writes reacquired by owners.
            // Do this before staging incoming data: the barrier may unload a previously pinned key.
            int extra = 0;
            for (var key : delta.removed()) if (!pending.containsKey(key)) extra++;
            int futurePins = pinned.size() + delta.removed().size();
            for (var key : delta.added()) if (pinned.contains(key)) futurePins--;
            if (futurePins > maxPinnedCubes || pending.size() + extra > maxPinnedCubes) {
                backpressureEvents++;
                long before = ownerThreadSaves;
                try { relieveBackpressure(delta); } finally { backpressureWrites += ownerThreadSaves - before; }
            }
            if (delta.removed().size() <= maxPinnedCubes) {
                Map<CubeKey, CubeSnapshot> incoming = stageIncoming(delta.added());
                var transition = space.stageTransition(incoming, Set.of());
                // Validate all outgoing snapshots before publishing the window.
                Map<CubeKey, CubeSnapshot> outgoing = snapshots(delta.removed());
                tickets.move(owner, position, horizontalRadius, verticalRadius);
                space.commitTransition(transition);
                pinned.removeAll(delta.added());
                for (var key : delta.added()) retryAfter.remove(key);
                pinned.addAll(delta.removed());
                peakPinnedCubes = Math.max(peakPinnedCubes, pinned.size());
                for (var snapshot : outgoing.values()) if (!pending.containsKey(snapshot.key())) submit(snapshot);
                loadedCubes += incoming.size();
                return delta;
            }
            // A window larger than the entire pin budget is saved synchronously below.
        }
        Map<CubeKey, CubeSnapshot> incoming = stageIncoming(delta.added());
        var transition = space.stageTransition(incoming, delta.removed());
        long before = ownerThreadSaves;
        try { saveOutgoing(delta.removed()); }
        finally { if (saver != null) backpressureWrites += ownerThreadSaves - before; }
        // Caller confines store/factory/resident access to this owner thread; no interleaved edits.
        tickets.move(owner, position, horizontalRadius, verticalRadius);
        space.commitTransition(transition);
        pinned.removeAll(delta.added());
        loadedCubes += incoming.size();
        unloadedCubes += delta.removed().size();
        return delta;
    }

    /** An IOException retains the owner too; caller retries release after storage recovers. */
    public CubeTickets.Delta release(String owner) throws IOException {
        requireOpen();
        CubeTickets.Delta delta = tickets.previewRemove(owner);
        // All pending writes, even those from another owner, must finish before release returns.
        if (saver != null) flush();
        var transition = space.stageTransition(Map.of(), delta.removed());
        if (saver == null) saveOutgoing(delta.removed()); // async flush already persisted this window
        tickets.remove(owner);
        space.commitTransition(transition);
        unloadedCubes += delta.removed().size();
        return delta;
    }

    /** Saves all resident data without releasing tickets; acknowledged unowned pins may unload. */
    public void flush() throws IOException {
        requireOpen();
        if (saver == null) { saveOutgoing(tickets.snapshot().keySet()); return; }
        // Join ALL old snapshots first. Even a failed write must not remain queued behind a
        // newer synchronous save. Failures are retained in metrics; latest full saves retry them.
        drainPending();
        saveOutgoing(space.residentKeys());
        // Failure above leaves every remaining resident cube and ticket intact.
        var unload = new HashSet<>(pinned);
        if (!unload.isEmpty()) {
            space.commitTransition(space.stageTransition(Map.of(), unload));
            unloadedCubes += unload.size();
            pinned.removeAll(unload);
        }
        retryAfter.clear();
    }

    private void relieveBackpressure(CubeTickets.Delta delta) throws IOException {
        // Preserve write order before any owner-thread write. Only enough old pins are saved
        // and removed to admit this move; reacquired cubes stay resident throughout the barrier.
        drainPending();
        var candidates = new HashSet<>(pinned);
        candidates.removeAll(delta.added());
        int outgoingPins = delta.removed().size() <= maxPinnedCubes ? delta.removed().size() : 0;
        int needed = Math.max(0, candidates.size() + outgoingPins - maxPinnedCubes);
        var unload = new HashSet<CubeKey>();
        for (var key : candidates) {
            if (unload.size() >= needed) break;
            unload.add(key);
        }
        saveOutgoing(unload);
        if (!unload.isEmpty()) {
            space.commitTransition(space.stageTransition(Map.of(), unload));
            pinned.removeAll(unload);
            unload.forEach(retryAfter::remove);
            unloadedCubes += unload.size();
        }
    }

    /** On failure the service stays open and reachable so flush/close can be retried. */
    @Override public void close() throws IOException {
        space.scheduledTicks().assertOwner();
        if (closed) return;
        flush();
        if (saver != null) saver.close();
        if (loader != null) loader.close();
        closed = true;
    }

    private Map<CubeKey, CubeSnapshot> stageIncoming(Set<CubeKey> keys) throws IOException {
        Map<CubeKey, CubeSnapshot> incoming = new HashMap<>();
        for (CubeKey key : keys) {
            if (space.isLoaded(key)) {
                if (!pinned.contains(key)) throw new IllegalStateException("Unmanaged resident cube: " + key);
                continue; // Reacquire current resident edits, never an older prepared/disk copy.
            }
            var prepared = loader == null ? Optional.<CubeSnapshot>empty() : loader.take(key);
            CubeSnapshot snapshot;
            if (prepared.isPresent()) { snapshot = prepared.orElseThrow(); preparedLoads++; }
            else {
                var stored = Objects.requireNonNull(store.read(key), "store result");
                // Never invoke a generator when a saved file is corrupt, incompatible or unreadable.
                snapshot = stored.isPresent() ? stored.orElseThrow() : missingFactory.apply(key);
                ownerThreadLoads++;
            }
            Objects.requireNonNull(snapshot, "cube snapshot");
            if (!key.equals(snapshot.key())) throw new IllegalArgumentException("Snapshot coordinate mismatch: " + key);
            incoming.put(key, snapshot);
        }
        return incoming;
    }

    private Map<CubeKey, CubeSnapshot> snapshots(Set<CubeKey> keys) {
        // Snapshot every outgoing cube before any write. A contract violation cannot partially save.
        Map<CubeKey, CubeSnapshot> outgoing = new HashMap<>();
        for (CubeKey key : keys) {
            outgoing.put(key, space.snapshot(key).orElseThrow(() -> new IllegalStateException("Demanded cube is absent: " + key)));
        }
        return outgoing;
    }

    private void saveOutgoing(Set<CubeKey> keys) throws IOException {
        for (CubeSnapshot snapshot : snapshots(keys).values()) {
            try { store.write(snapshot); savedCubes++; ownerThreadSaves++; }
            catch (IOException failure) { recordFailure(snapshot.key(), failure); throw failure; }
        }
    }

    private void submit(CubeSnapshot snapshot) {
        pending.put(snapshot.key(), new PendingSave(snapshot, saver.submit(snapshot)));
        submittedSaves++;
    }

    private void recordFailure(CubeKey key, IOException failure) {
        saveFailures++;
        lastSaveFailure = key + ": " + failure;
        retryAfter.put(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
    }

    private boolean acknowledge(CubeKey key, PendingSave save) throws IOException {
        try {
            boolean written = save.result().get();
            if (written) { savedCubes++; completedSaves++; }
            else staleSnapshots++;
            return written;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted awaiting durable cube write: " + key, interrupted);
        } catch (ExecutionException failure) {
            recordFailure(key, new IOException("Background cube write failed: " + failure.getCause(), failure.getCause()));
            return false;
        }
    }

    private void drainPending() throws IOException {
        for (var entry : new ArrayList<>(pending.entrySet())) {
            acknowledge(entry.getKey(), entry.getValue());
            pending.remove(entry.getKey());
        }
    }

    /**
     * Join submitted IO without unloading or snapshotting native state. Used when an upstream
     * item/chest flush has already failed: retain all residents and let the caller report failure.
     * Write failures are recorded in saveMetrics; this is NOT a successful persistence barrier.
     */
    public void awaitPendingSaves() throws IOException { requireOpen(); drainPending(); }

    /** Owner-thread tick pump: never waits for IO. Errors remain pinned and retry after 1 second. */
    public void processPendingSaves() throws IOException {
        processPendingSaves(Set.of());
    }

    private void processPendingSaves(Set<CubeKey> reacquiring) throws IOException {
        requireOpen();
        if (saver == null) return;
        var unload = new HashSet<CubeKey>();
        for (var entry : new ArrayList<>(pending.entrySet())) {
            var key = entry.getKey(); var save = entry.getValue();
            // The move is about to reclaim this resident cube. Do not unload it between
            // tryMove's readiness check and staging, even if its Future just completed.
            if (reacquiring.contains(key)) continue;
            if (!save.result().isDone()) continue;
            boolean written = acknowledge(key, save);
            pending.remove(key);
            if (!pinned.contains(key)) { retryAfter.remove(key); continue; }
            if (written) {
                retryAfter.remove(key);
                var current = space.snapshot(key).orElseThrow();
                if (sameContents(save.snapshot(), current)) unload.add(key);
                else staleSnapshots++;
            }
        }
        if (!unload.isEmpty()) {
            space.commitTransition(space.stageTransition(Map.of(), unload));
            pinned.removeAll(unload);
            unloadedCubes += unload.size();
        }
        long now = System.nanoTime();
        for (var key : pinned) {
            if (pending.containsKey(key)) continue;
            if (reacquiring.contains(key)) continue;
            if (pending.size() >= maxPinnedCubes) break;
            var retry = retryAfter.get(key);
            if (retry != null && now - retry < 0) continue;
            if (retry != null) { retryAfter.remove(key); saveRetries++; }
            submit(space.snapshot(key).orElseThrow());
        }
    }

    private static boolean sameContents(CubeSnapshot a, CubeSnapshot b) {
        return Arrays.equals(a.states(), b.states()) && a.ticks().equals(b.ticks())
                && new HashSet<>(a.blockEntities()).equals(new HashSet<>(b.blockEntities()));
    }

    /** Read/edit backing space; caller must not directly load/unload cubes owned by this service. */
    public SparseCubeSpace space() { return space; }
    public Set<CubeKey> ownerWindow(String owner) { return tickets.ownerWindow(owner); }
    public Map<CubeKey, Integer> snapshot() { return tickets.snapshot(); }
    public Metrics metrics() { return new Metrics(tickets.ownerCount(), space.loadedCubeCount(), loadedCubes, savedCubes, unloadedCubes); }
    /** Saved counts completed writes, including successful writes preceding a failed transition. */
    public record Metrics(int owners, int residentCubes, long loadedCubes, long savedCubes, long unloadedCubes) { }
    public LoadMetrics loadMetrics() { return new LoadMetrics(deferredMoves, preparedLoads, ownerThreadLoads); }
    /** ownerThreadLoads counts cubes read or generated on the owner thread, including initial admission. */
    public record LoadMetrics(long deferredMoves, long preparedLoads, long ownerThreadLoads) { }
    public SaveMetrics saveMetrics() {
        space.scheduledTicks().assertOwner();
        return new SaveMetrics(saver != null, pinned.size(), pending.size(), maxPinnedCubes, peakPinnedCubes,
                submittedSaves, completedSaves, saveFailures, saveRetries, staleSnapshots, backpressureWrites, backpressureEvents, ownerThreadSaves, lastSaveFailure);
    }
    public record SaveMetrics(boolean enabled, int pinnedCubes, int pendingWrites, int maxPinnedCubes,
            int peakPinnedCubes, long submitted, long completed, long failures, long retries,
            long staleSnapshots, long backpressureWrites, long backpressureEvents, long ownerThreadWrites, String lastFailure) { }
}
