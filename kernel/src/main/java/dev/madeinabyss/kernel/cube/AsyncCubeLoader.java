package dev.madeinabyss.kernel.cube;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Background store read and generation of non-resident cubes. Results are only proposals:
 * the owner thread accepts one when the cube's store version still equals the even version
 * observed before the read, so a save or edit that happened meanwhile always wins. A store
 * read failure is reported for that key and never falls through to generation.
 * Requests, acceptance and eviction belong to the single owner thread.
 * The optional interest ledger contains load demand, never residency or save ownership.
 * Replacing one player's demand cancels only keys absent from every player's demand.
 */
public final class AsyncCubeLoader implements AutoCloseable {
    private static final int MAX_INTEREST_OWNERS = 4;
    private static final int MAX_TRACE_EVENTS = 256;
    private record Result(CubeSnapshot snapshot, long version, IOException failure, Task task) { }
    private final class Task implements Runnable, Comparable<Task> {
        final CubeKey key;volatile int priority;final long sequence,queuedNanos=System.nanoTime();
        volatile boolean cancelled,finished;
        Task(CubeKey key,int priority,long sequence){this.key=key;this.priority=priority;this.sequence=sequence;}
        @Override public int compareTo(Task other){int c=Integer.compare(priority,other.priority);return c!=0?c:Long.compare(sequence,other.sequence);}
        @Override public void run(){
            running.incrementAndGet();
            long started=System.nanoTime();
            maxQueueWaitNanos.accumulateAndGet(started-queuedNanos,Math::max);
            trace(this,"START",started-queuedNanos);
            try{
                if(cancelled||closed)return;
                long before=store.version(key);
                if((before&1)!=0)return; // A writer is replacing this file; the owner requests again later.
                Result result;
                try{
                    long readStarted=System.nanoTime();
                    var saved=store.read(key);
                    trace(this,"STORE_READ",System.nanoTime()-readStarted);
                    // A slow read must not begin generation for an abandoned destination or stale version.
                    if(cancelled||closed||store.version(key)!=before)return;
                    long generationStarted=System.nanoTime();
                    var snapshot=saved.isPresent()?saved.orElseThrow():Objects.requireNonNull(factory.apply(key),"cube snapshot");
                    if(!key.equals(snapshot.key()))throw new IllegalArgumentException("Snapshot coordinate mismatch: "+key);
                    result=new Result(snapshot,before,null,this);
                    if(saved.isEmpty()){
                        generated.incrementAndGet();trace(this,"GENERATED",System.nanoTime()-generationStarted);
                    }else read.incrementAndGet();
                }catch(IOException failure){result=new Result(null,before,failure,this);}
                catch(RuntimeException failure){
                    // Unknown domain or refused generation is reported to the owner like a read failure.
                    result=new Result(null,before,new IOException("Background cube preparation refused: "+key,failure),this);
                    failures.incrementAndGet();lastFailure=failure.toString();
                }
                if(cancelled||closed||store.version(key)!=before)return;
                // A replacement of the same key may already be running. Serialize publication
                // per key so an old cancelled task cannot overwrite its newer proposal.
                Result proposal=result;
                if(ready.compute(key,(ignored,previous)->cancelled||closed||inFlight.get(key)!=this?previous:proposal)==proposal){
                    order.add(key);trace(this,"READY",System.nanoTime()-started);
                }
            }catch(RuntimeException failure){
                failures.incrementAndGet();lastFailure=failure.toString();
            }finally{
                synchronized(this){
                    finished=true;inFlight.remove(key,this);
                    if(cancelled)cancelledExits.incrementAndGet();
                    trace(this,cancelled?"EXIT_CANCELLED":"EXIT",System.nanoTime()-started);
                    running.decrementAndGet();
                }
            }
        }
    }
    private final VersionedCubeStore store;
    private final Function<CubeKey,CubeSnapshot> factory;
    private final int maxReady;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<CubeKey,Result> ready=new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CubeKey> order=new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<CubeKey,Task> inFlight=new ConcurrentHashMap<>();
    private final AtomicLong generated=new AtomicLong(),read=new AtomicLong(),failures=new AtomicLong();
    private final AtomicInteger running=new AtomicInteger();
    private final AtomicLong cancelledExits=new AtomicLong(),maxQueueWaitNanos=new AtomicLong();
    private final Map<String,Map<CubeKey,Integer>> interests=new HashMap<>();
    private Map<CubeKey,Integer> wanted=Map.of();
    private final ArrayDeque<Trace> traces=new ArrayDeque<>();
    private final long createdNanos=System.nanoTime();
    private long sequence,accepted,stale,requests,evicted;
    private long cancelledQueued,cancelledRunning,reprioritized,interestRevision;
    private volatile boolean closed;
    private volatile String lastFailure="";

    public AsyncCubeLoader(VersionedCubeStore store,Function<CubeKey,CubeSnapshot> factory,int threads,int maxReady,String threadName){
        this.store=Objects.requireNonNull(store,"store");this.factory=Objects.requireNonNull(factory,"factory");
        if(threads<1||threads>8||maxReady<27)throw new IllegalArgumentException("Bounded loader requires 1..8 threads and room for one window");
        this.maxReady=maxReady;Objects.requireNonNull(threadName,"threadName");
        executor=new ThreadPoolExecutor(threads,threads,30,TimeUnit.SECONDS,new PriorityBlockingQueue<>(),runnable->{
            var thread=new Thread(runnable,threadName);thread.setDaemon(true);thread.setPriority(Thread.NORM_PRIORITY);return thread;});
        executor.allowCoreThreadTimeOut(true);
    }

    /** Owner thread. Lower priority values run first; an already ready or sooner request is kept. */
    public void request(CubeKey key,int priority){
        Objects.requireNonNull(key,"key");if(closed)return;
        // Managed demand is authoritative: a later visual request cannot promote the whole
        // 243-cube window to the physical ring's priority. Legacy callers need no ledger.
        priority=wanted.getOrDefault(key,priority);
        if(ready.containsKey(key))return;
        var existing=inFlight.get(key);
        if(existing!=null&&!existing.cancelled){
            if(existing.priority>priority)reprioritize(existing,priority);
            return;
        }
        if(executor.getQueue().size()>maxReady*4)return; // Stale predictions must not grow without bound.
        var task=new Task(key,priority,sequence++);inFlight.put(key,task);requests++;trace(task,"QUEUE",0);executor.execute(task);
    }

    /**
     * Owner thread. Replace one player's current and predicted demand, submit the nearest
     * priorities first, and prune only work outside the complete multiplayer union.
     * Cancelling an active read/generator is cooperative between stages: it remains counted
     * as running until it actually exits, and its result can never be accepted afterwards.
     */
    public void replaceInterest(String owner,Map<CubeKey,Integer> demand){
        Objects.requireNonNull(owner,"owner");Objects.requireNonNull(demand,"demand");
        if(owner.isBlank())throw new IllegalArgumentException("Interest owner cannot be blank");
        if(closed)return;
        if(!demand.isEmpty()&&!interests.containsKey(owner)&&interests.size()>=MAX_INTEREST_OWNERS)
            throw new IllegalArgumentException("Load interest owner budget exceeded");
        if(demand.size()>maxReady*4)throw new IllegalArgumentException("Load interest cube budget exceeded");
        for(var entry:demand.entrySet()){
            Objects.requireNonNull(entry.getKey(),"interest cube");
            if(Objects.requireNonNull(entry.getValue(),"interest priority")<0)throw new IllegalArgumentException("Negative interest priority");
        }
        Map<CubeKey,Integer> next=Map.copyOf(demand);
        if(next.equals(interests.getOrDefault(owner,Map.of())))return;
        if(next.isEmpty())interests.remove(owner);else interests.put(owner,next);
        var union=new HashMap<CubeKey,Integer>();
        for(var values:interests.values())values.forEach((key,priority)->union.merge(key,priority,Math::min));
        wanted=Map.copyOf(union);interestRevision++;
        for(var task:inFlight.values()){
            Integer priority=wanted.get(task.key);
            if(priority==null)cancel(task);
            else if(!task.cancelled&&task.priority!=priority)reprioritize(task,priority);
        }
        // Old proposals are harmless when version-valid, but must not occupy the bounded
        // cache ahead of the latest four-player union.
        ready.keySet().removeIf(key->!wanted.containsKey(key));
        var entries=new ArrayList<>(wanted.entrySet());
        entries.sort(Comparator.comparingInt(Map.Entry<CubeKey,Integer>::getValue)
                .thenComparingInt(entry->entry.getKey().x()).thenComparingInt(entry->entry.getKey().y())
                .thenComparingInt(entry->entry.getKey().z()));
        for(var entry:entries)request(entry.getKey(),entry.getValue());
    }
    public void releaseInterest(String owner){replaceInterest(owner,Map.of());}

    private void reprioritize(Task task,int priority){
        // Removing before changing the comparator key preserves PriorityBlockingQueue order.
        // A worker that already removed the task keeps it; promotion never duplicates a load.
        synchronized(task){
            if(task.cancelled||task.finished)return;
            boolean queued=executor.remove(task);
            task.priority=priority;reprioritized++;trace(task,"PRIORITY",0);
            if(queued)executor.execute(task);
        }
    }
    private void cancel(Task task){
        synchronized(task){
            if(task.cancelled||task.finished)return;
            task.cancelled=true;
            if(executor.remove(task)){
                inFlight.remove(task.key,task);cancelledQueued++;trace(task,"CANCEL_QUEUED",0);
            }else{
                cancelledRunning++;trace(task,"CANCEL_RUNNING",0);
            }
        }
    }
    /** Owner thread. A valid proposal is removed; a stale one is dropped and the caller decides how to load. */
    public Optional<CubeSnapshot> take(CubeKey key) throws IOException {
        var result=valid(key);if(result==null)return Optional.empty();
        ready.remove(key);accepted++;
        if(result.failure()!=null)throw result.failure();
        return Optional.of(result.snapshot());
    }
    /** Owner thread. Valid proposal without consuming it, for read-only consumers such as visual windows. */
    public Optional<CubeSnapshot> peek(CubeKey key) throws IOException {
        var result=valid(key);if(result==null)return Optional.empty();
        if(result.failure()!=null)throw result.failure();
        return Optional.of(result.snapshot());
    }
    public boolean isReady(CubeKey key){return valid(key)!=null;}
    private Result valid(CubeKey key){
        Objects.requireNonNull(key,"key");trim();
        var result=ready.get(key);if(result==null)return null;
        long now=store.version(key);
        if(result.task().cancelled||now!=result.version()||(now&1)!=0){ready.remove(key,result);stale++;return null;}
        return result;
    }
    private void trim(){
        while(ready.size()>maxReady){
            if(interests.isEmpty()){
                var oldest=order.poll();if(oldest==null)break;if(ready.remove(oldest)!=null)evicted++;
            }else{
                // Prepared current physics/visual cubes must not lose to later speculative
                // snapshots while the four owners wait for complete admission windows.
                Map.Entry<CubeKey,Result> candidate=null;int worst=Integer.MIN_VALUE;
                for(var entry:ready.entrySet()){
                    int priority=wanted.getOrDefault(entry.getKey(),Integer.MAX_VALUE);
                    if(candidate==null||priority>worst||priority==worst&&entry.getValue().task().sequence<candidate.getValue().task().sequence){
                        candidate=entry;worst=priority;
                    }
                }
                if(candidate==null)break;
                if(ready.remove(candidate.getKey(),candidate.getValue()))evicted++;
            }
        }
        if(order.size()>maxReady*4)order.removeIf(key->!ready.containsKey(key));
    }
    public Metrics metrics(){return new Metrics(requests,generated.get(),read.get(),accepted,stale,evicted,failures.get(),
            ready.size(),inFlight.size(),lastFailure,executor.getQueue().size(),running.get(),interests.size(),wanted.size(),
            interestRevision,cancelledQueued,cancelledRunning,cancelledExits.get(),reprioritized,maxQueueWaitNanos.get()/1e6);}
    public record Metrics(long requests,long generated,long read,long accepted,long stale,long evicted,long failures,
                          int ready,int inFlight,String lastFailure,int queued,int running,int interestOwners,int interestCubes,
                          long interestRevision,long cancelledQueued,long cancelledRunning,long cancelledExits,long reprioritized,
                          double maxQueueWaitMillis) { }
    public record Trace(CubeKey key,long taskSequence,int priority,String phase,double elapsedMillis,double durationMillis) { }
    private synchronized void trace(Task task,String phase,long durationNanos){
        traces.addLast(new Trace(task.key,task.sequence,task.priority,phase,(System.nanoTime()-createdNanos)/1e6,durationNanos/1e6));
        while(traces.size()>MAX_TRACE_EVENTS)traces.removeFirst();
    }
    public synchronized List<Trace> traces(){return List.copyOf(traces);}
    @Override public void close(){
        closed=true;for(var task:inFlight.values())cancel(task);
        executor.shutdownNow();ready.clear();interests.clear();wanted=Map.of();
    }
    /** Test and shutdown support: wait for queued work without accepting it. */
    public boolean awaitIdle(long timeoutMillis){
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(System.nanoTime()<until){if(inFlight.isEmpty()&&running.get()==0)return true;
            try{Thread.sleep(1);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new UncheckedIOException(new IOException("interrupted",interrupted));}}
        return inFlight.isEmpty()&&running.get()==0;
    }
}
