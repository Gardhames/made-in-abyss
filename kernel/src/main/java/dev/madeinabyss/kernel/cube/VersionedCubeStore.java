package dev.madeinabyss.kernel.cube;

/**
 * Store whose per-cube write version lets an off-thread reader prove it did not observe a
 * stale or partially replaced file. Writers make the version odd before replacing data and
 * even afterwards; a read is valid only when the same even version brackets it.
 * When used with CubeResidencyService.enableAsyncSaving, direct external write calls must
 * synchronize on this store's monitor, shared with the writer's version-check-and-write.
 * With no external writers the service serializes its own background and barrier writes.
 */
public interface VersionedCubeStore extends CubeStore {
    long version(CubeKey key);
}
