package dev.madeinabyss.kernel.cube;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A single ordered writer of immutable snapshots. Residency and the bounded pending ledger
 * belong to CubeResidencyService's owner thread, never to this worker. Other writers to the
 * same store must synchronize on the store monitor too (StableCubeStore does so), or route
 * every write through the service. Reads remain concurrent and version-checked by the loader.
 */
final class AsyncCubeSaver implements AutoCloseable {
    private final CubeStore store;
    private final ExecutorService executor;

    AsyncCubeSaver(CubeStore store, String threadName) {
        this.store = Objects.requireNonNull(store);
        Objects.requireNonNull(threadName);
        executor = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, threadName);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    /** False means a newer direct write superseded this queued snapshot; it must not unload. */
    Future<Boolean> submit(CubeSnapshot snapshot) {
        long expected = store instanceof VersionedCubeStore versioned ? versioned.version(snapshot.key()) : 0;
        return executor.submit(() -> {
            synchronized (store) {
                if (store instanceof VersionedCubeStore versioned
                        && ((expected & 1) != 0 || versioned.version(snapshot.key()) != expected)) return false;
                store.write(snapshot);
                return true;
            }
        });
    }

    /** Called only after the owner has joined every submitted Future. Never cancels saves. */
    @Override public void close() { executor.shutdown(); }
}
