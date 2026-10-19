package dev.madeinabyss.kernel.concurrent;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/**
 * Bounded FIFO reuse of completed immutable values. Exact keys must include the caller's
 * source/version identity; this cache does not validate freshness. Compute values outside
 * this cache. Duplicate admission retains the first value and its original FIFO age.
 * Hits use the concurrent map without acquiring the admission monitor.
 */
public final class BoundedReuseCache<K,V> {
    private record Entry<V>(V value,long weight) {}
    private final long maxWeight;
    private final int maxEntries;
    private final ToLongFunction<V> weigh;
    private final ConcurrentHashMap<K,Entry<V>> entries=new ConcurrentHashMap<>();
    private final ArrayDeque<K> order=new ArrayDeque<>();
    private long residentWeight,evictionCount;

    public BoundedReuseCache(long maxWeight,int maxEntries,ToLongFunction<V> weight) {
        if(maxWeight<1 || maxEntries<1)throw new IllegalArgumentException("Positive weight and entry bounds required");
        this.maxWeight=maxWeight;this.maxEntries=maxEntries;this.weigh=Objects.requireNonNull(weight,"weight");
    }

    public V get(K key) {
        var entry=entries.get(Objects.requireNonNull(key,"key"));
        return entry==null?null:entry.value();
    }

    public void put(K key,V value) {
        Objects.requireNonNull(key,"key");Objects.requireNonNull(value,"value");
        // User code never runs under the admission lock, even when it is expensive.
        long valueWeight=weigh.applyAsLong(value);
        if(valueWeight<1)throw new IllegalArgumentException("Value weight must be positive");
        if(valueWeight>maxWeight)return;
        synchronized(this) {
            if(entries.containsKey(key))return;
            // Subtraction avoids overflow even when maxWeight is Long.MAX_VALUE.
            while(entries.size()>=maxEntries || residentWeight>maxWeight-valueWeight) {
                var oldest=entries.remove(order.removeFirst());
                residentWeight-=oldest.weight();evictionCount++;
            }
            entries.put(key,new Entry<>(value,valueWeight));order.addLast(key);residentWeight+=valueWeight;
        }
    }

    public synchronized int size(){return entries.size();}
    public synchronized long weight(){return residentWeight;}
    public synchronized long evictions(){return evictionCount;}
    /** Consistent immutable view for background persistence; values are already immutable. */
    public synchronized java.util.Map<K,V> snapshot(){
        var result=new java.util.LinkedHashMap<K,V>();
        for(var key:order){var entry=entries.get(key);if(entry!=null)result.put(key,entry.value());}
        return java.util.Collections.unmodifiableMap(result);
    }
}
