package dev.madeinabyss.kernel.cube;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Owner-thread sequencing; finite immutable batches may be compared on a worker.
 * Pending observations survive leaving a residency window. Only an exact comparison of
 * the latest version can classify a key as procedural, including restoration of an edit.
 */
public final class GeneratedObservationQueue {
    private final int retainedLimit,pendingLimit,batchLimit;
    private long generation,sequence;
    private Map<CubeKey,int[]> retained=Map.of();
    private final LinkedHashMap<CubeKey,Observation> pending=new LinkedHashMap<>();
    private final LinkedHashMap<CubeKey,int[]> verified=new LinkedHashMap<>(16,.75f,true);
    private Batch active;
    private record Observation(long version,int[] states) { }
    public static final class Batch {
        private final long generation;
        private final Map<CubeKey,Observation> observations;
        private Batch(long generation,Map<CubeKey,Observation> observations){this.generation=generation;this.observations=Map.copyOf(observations);}
        public int size(){return observations.size();}
    }
    public static final class Compared {
        private final Batch batch;private final Map<CubeKey,Boolean> pristine;
        private Compared(Batch batch,Map<CubeKey,Boolean> pristine){this.batch=batch;this.pristine=Map.copyOf(pristine);}
    }
    public record Commit(boolean accepted,boolean geometryChanged,int committed,int pristineComparisons) { }

    public GeneratedObservationQueue(int retainedLimit,int pendingLimit,int batchLimit) {
        if(retainedLimit<1||pendingLimit<1||batchLimit<1||batchLimit>pendingLimit)
            throw new IllegalArgumentException("Invalid generated observation bounds");
        this.retainedLimit=retainedLimit;this.pendingLimit=pendingLimit;this.batchLimit=batchLimit;
    }
    /** Initial authoritative saved geometry; newer pending observations remain queued. */
    public void baseline(Map<CubeKey,int[]> saved) {
        if(active!=null)throw new IllegalStateException("Cannot install inventory during comparison");
        if(saved.size()>retainedLimit)throw new IllegalArgumentException("Retained generated cube count exceeds bound");
        var next=new HashMap<CubeKey,int[]>();saved.forEach((key,states)->next.put(Objects.requireNonNull(key),copy(states)));
        retained=Collections.unmodifiableMap(next);verified.clear();
    }
    /** No generation here. Input arrays are copied before they can reach another thread. */
    public void observe(Map<CubeKey,int[]> incoming) {
        var updates=new LinkedHashMap<CubeKey,int[]>();int count=pending.size();
        for(var entry:incoming.entrySet()) {
            var key=Objects.requireNonNull(entry.getKey());var states=entry.getValue();validate(states);
            var queued=pending.get(key);
            if(queued!=null&&Arrays.equals(queued.states(),states))continue;
            if(queued==null&&(Arrays.equals(verified.get(key),states)||Arrays.equals(retained.get(key),states)))continue;
            if(queued==null&&++count>pendingLimit)throw new IllegalArgumentException("Pending generated observation count exceeds bound");
            updates.put(key,states.clone());
        }
        if(sequence>Long.MAX_VALUE-updates.size())throw new IllegalStateException("Generated observation sequence exhausted");
        updates.forEach((key,states)->pending.put(key,new Observation(++sequence,states)));
    }
    public Batch takeBatch() {
        if(active!=null)throw new IllegalStateException("Generated comparison already in flight");
        if(pending.isEmpty())return null;
        var entries=new LinkedHashMap<CubeKey,Observation>();
        for(var entry:pending.entrySet()){entries.put(entry.getKey(),entry.getValue());if(entries.size()==batchLimit)break;}
        return active=new Batch(generation,entries);
    }
    /** The generator is immutable for the batch's session; never derive expected states from incoming data. */
    public static Compared compare(Batch batch,Function<CubeKey,int[]> procedural) {
        Objects.requireNonNull(batch);Objects.requireNonNull(procedural);var pristine=new HashMap<CubeKey,Boolean>();
        for(var entry:batch.observations.entrySet()) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Generated observation comparison interrupted");
            var expected=procedural.apply(entry.getKey());validate(expected);
            pristine.put(entry.getKey(),Arrays.equals(expected,entry.getValue().states()));
        }
        return new Compared(batch,pristine);
    }
    public Commit commit(Compared result) {
        Objects.requireNonNull(result);var batch=result.batch;
        if(batch.generation!=generation||batch!=active)return new Commit(false,false,0,0);
        var next=new HashMap<>(retained);boolean changed=false;int committed=0,pristineCount=0;
        for(var entry:batch.observations.entrySet()) {
            var key=entry.getKey();var current=pending.get(key);
            if(current==null||current.version()!=entry.getValue().version())continue;
            committed++;
            if(result.pristine.get(key)) {pristineCount++;if(next.remove(key)!=null)changed=true;}
            else if(!Arrays.equals(next.get(key),current.states())){next.put(key,current.states());changed=true;}
        }
        // Refuse the entire completion before altering pending/verified/retained state.
        if(next.size()>retainedLimit)throw new IllegalArgumentException("Retained generated cube count exceeds bound");
        retained=Collections.unmodifiableMap(next);
        for(var entry:batch.observations.entrySet()) {
            var key=entry.getKey();var current=pending.get(key);
            if(current==null||current.version()!=entry.getValue().version())continue;
            pending.remove(key);verified.put(key,current.states());
            if(verified.size()>pendingLimit)verified.remove(verified.keySet().iterator().next());
        }
        active=null;return new Commit(true,changed,committed,pristineCount);
    }
    public int pendingCount(){return pending.size();}
    public int verifiedCount(){return verified.size();}
    public boolean pending(CubeKey key){return pending.containsKey(key);}
    /** Read-only snapshots; state arrays are exclusively owned and must not be mutated. */
    public Map<CubeKey,int[]> retained(){return retained;}
    /** Reject an old worker after an admission error, preserving every queued observation. */
    public void invalidateBatch(){generation=Math.incrementExact(generation);active=null;}
    public void clear(){generation=Math.incrementExact(generation);sequence=0;retained=Map.of();pending.clear();verified.clear();active=null;}
    private static int[] copy(int[] states){validate(states);return states.clone();}
    private static void validate(int[] states){if(states==null||states.length!=4096)throw new IllegalArgumentException("Expected complete generated cube");}
}
