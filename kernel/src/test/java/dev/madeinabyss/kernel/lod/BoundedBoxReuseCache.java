package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;
import java.util.function.ToLongFunction;

/** Rejected T8 cache-key experiment, retained only as an executable benchmark/oracle. */
public final class BoundedBoxReuseCache<V> {
    private final BoundedReuseCache<StrongBoxKey,V> cache;
    private volatile long revision;
    public BoundedBoxReuseCache(long maxWeight,int maxEntries,ToLongFunction<V> weight){
        cache=new BoundedReuseCache<>(maxWeight,maxEntries,weight);
    }
    public V get(TerrainLodColumns.Box box){return cache.get(new StrongBoxKey(box));}
    public synchronized void put(TerrainLodColumns.Box box,V value){int size=cache.size();long evictions=cache.evictions();cache.put(new StrongBoxKey(box),value);if(cache.size()!=size||cache.evictions()!=evictions)revision++;}
    public int size(){return cache.size();}
    public long evictions(){return cache.evictions();}
    public long revision(){return revision;}
}
