package dev.madeinabyss.kernel.light;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.LongAdder;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Transient coordination for exact immutable-source probes. */
public final class SkyProbeSingleFlight<K,V> {
    private final int capacity;
    private final HashMap<K,FutureTask<V>> pending=new HashMap<>();
    private final LongAdder evaluations=new LongAdder(),joins=new LongAdder();
    public SkyProbeSingleFlight(int capacity) {
        if(capacity<1||capacity>4096)throw new IllegalArgumentException("Probe flight capacity must be1..4096");
        this.capacity=capacity;
    }
    public V getOrCompute(ConcurrentMap<K,V> values,K key,int maximumValues,Supplier<V> query){
        Objects.requireNonNull(values);Objects.requireNonNull(key);Objects.requireNonNull(query);
        if(maximumValues<0)throw new IllegalArgumentException("Negative probe value capacity");
        V known=values.get(key);if(known!=null)return known;
        FutureTask<V> task;
        synchronized(pending){
            known=values.get(key);if(known!=null)return known;
            task=pending.get(key);
            if(task!=null)joins.increment();
            else if(pending.size()<capacity){
                task=new FutureTask<>(()->compute(values,key,maximumValues,query));pending.put(key,task);
            }
        }
        // Saturation changes work reuse only, never completeness or the exact result.
        if(task==null)return compute(values,key,maximumValues,query);
        task.run(); // FutureTask selects exactly one runner, with no lock held during tracing.
        try {return task.get();}
        catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();throw new CancellationException("Interrupted waiting for exact sky probe");
        }catch(ExecutionException failed){
            Throwable cause=failed.getCause();if(cause instanceof RuntimeException runtime)throw runtime;
            if(cause instanceof Error error)throw error;throw new IllegalStateException("Sky probe failed",cause);
        }finally {if(task.isDone())synchronized(pending){pending.remove(key,task);}}
    }
    private V compute(ConcurrentMap<K,V> values,K key,int maximumValues,Supplier<V> query){
        evaluations.increment();V made=Objects.requireNonNull(query.get(),"Exact probe returned null");
        synchronized(values){
            V known=values.get(key);if(known!=null)return known;
            if(values.size()<maximumValues)values.put(key,made);
        }
        return made;
    }
    public long evaluations(){return evaluations.sum();}
    public long joins(){return joins.sum();}
    public int pendingCount(){synchronized(pending){return pending.size();}}
    public int capacity(){return capacity;}
    public static int hashCoordinates(int x,int y,int z){
        long hash=(long)x*0x9e3779b97f4a7c15L+(long)y*0xc2b2ae3d27d4eb4fL+(long)z*0x165667b19e3779f9L;
        hash=(hash^(hash>>>30))*0xbf58476d1ce4e5b9L;
        hash=(hash^(hash>>>27))*0x94d049bb133111ebL;
        return (int)(hash^(hash>>>31));
    }
}
