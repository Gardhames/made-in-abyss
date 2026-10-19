package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;

/** LOD-only observation of the unchanged generator-pinned FIFO cache. */
public final class BoxCacheRevision {
    private BoxCacheRevision(){}
    /**
     * Each admission adds one live entry and any eviction transfers one live entry to the
     * eviction count. Their sum is the exact admission count, including at full capacity.
     * Read both under the admission monitor: separate reads could expose a phantom revision.
     */
    public static long read(BoundedReuseCache<?,?> cache){
        synchronized(cache){return cache.evictions()+(long)cache.size();}
    }
}
