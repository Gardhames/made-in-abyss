package dev.madeinabyss.kernel.concurrent;

import java.util.HashSet;
import java.util.Objects;

/** Finite opt-in diagnostic. Only first-admitted keys have complete repeat coverage. */
public final class BoundedDiagnosticHistory<K> {
    private final int capacity;
    private final HashSet<K> seen=new HashSet<>();
    private long untracked;
    public BoundedDiagnosticHistory(int capacity){
        if(capacity<1||capacity>1_000_000)throw new IllegalArgumentException("History capacity must be1..1000000");this.capacity=capacity;
    }
    /** False proves a repeat. At capacity, true can also mean an untracked repeat. */
    public synchronized boolean add(K key){
        Objects.requireNonNull(key);if(seen.contains(key))return false;
        if(seen.size()<capacity)seen.add(key);else untracked++;
        return true;
    }
    public synchronized int size(){return seen.size();}
    public synchronized long untracked(){return untracked;}
}
