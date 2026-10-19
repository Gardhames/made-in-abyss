package dev.madeinabyss.kernel.concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Exact ordered work on a caller-owned bounded executor. */
public final class BoundedOrderedWork {
    private BoundedOrderedWork() {}
    public static <T,R> List<R> map(List<T> input,int workers,ExecutorService executor,Function<T,R> operation) {
        return map(input,workers,executor,64,operation);
    }
    /** A smaller claim cap balances spatially concentrated cost without adding executor tasks. */
    public static <T,R> List<R> map(List<T> input,int workers,ExecutorService executor,int maximumBatchSize,Function<T,R> operation) {
        Objects.requireNonNull(executor);Objects.requireNonNull(operation);
        if(workers<1 || workers>12)throw new IllegalArgumentException("Worker budget must be 1..12");
        if(maximumBatchSize<1||maximumBatchSize>64)throw new IllegalArgumentException("Claim budget must be 1..64");
        if(Thread.currentThread().isInterrupted())throw new CancellationException("Ordered work interrupted");
        var stable=List.copyOf(input);
        if(stable.isEmpty())return List.of();
        int count=Math.min(workers,stable.size());Object[] output=new Object[stable.size()];
        // Small jobs still reach every worker; large jobs balance spatially uneven cost.
        final int batchSize=(int)Math.min(maximumBatchSize,((long)stable.size()+count-1)/count);
        var next=new AtomicInteger();
        var completed=new ExecutorCompletionService<Void>(executor);
        var pending=new ArrayList<Future<Void>>(count);
        var exits=new ArrayList<PhaseExit>(count);
        try {
            for(int range=0;range<count;range++) {
                var exit=new PhaseExit();exits.add(exit);
                pending.add(completed.submit(()->{
                    if(!exit.start())return null;
                    try {
                    while(true) {
                        if(Thread.currentThread().isInterrupted())throw new CancellationException("Ordered batch interrupted");
                        // Saturating cursor cannot wrap at Integer.MAX_VALUE or claim an index twice.
                        int start=next.getAndUpdate(value->(int)Math.min(stable.size(),(long)value+batchSize));
                        if(start==stable.size())break;
                        int end=(int)Math.min(stable.size(),(long)start+batchSize);
                        for(int i=start;i<end;i++) {
                            if(Thread.currentThread().isInterrupted())throw new CancellationException("Ordered batch interrupted");
                            output[i]=Objects.requireNonNull(operation.apply(stable.get(i)),"Work result");
                        }
                    }
                    return null;
                    } finally {exit.finish();}
                }));
            }
            for(int range=0;range<count;range++)completed.take().get();
            if(Thread.currentThread().isInterrupted())throw new CancellationException("Ordered publication interrupted");
            var result=new ArrayList<R>(output.length);
            for(var value:output) {
                @SuppressWarnings("unchecked") R typed=(R)value;
                result.add(typed);
            }
            return List.copyOf(result);
        }catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new CancellationException("Ordered wait interrupted");
        }catch(ExecutionException failed) {
            if(failed.getCause() instanceof RuntimeException runtime)throw runtime;
            if(failed.getCause() instanceof Error error)throw error;
            throw new IllegalStateException("Ordered work failed",failed.getCause());
        }finally {
            // Future cancellation completes immediately, before an interrupted operation exits.
            // Join the actual writers before returning their shared arrays or reusing worker slots.
            for(var exit:exits)exit.cancelQueued();
            for(var future:pending)if(!future.isDone())future.cancel(true);
            boolean interrupted=Thread.interrupted();
            for(var exit:exits)while(true)try{exit.exited.await();break;}
                catch(InterruptedException stop){interrupted=true;}
            if(interrupted)Thread.currentThread().interrupt();
        }
    }
    private static final class PhaseExit {
        private final AtomicInteger state=new AtomicInteger();
        final CountDownLatch exited=new CountDownLatch(1);
        boolean start(){return state.compareAndSet(0,1);}
        void finish(){state.set(2);exited.countDown();}
        void cancelQueued(){if(state.compareAndSet(0,2))exited.countDown();}
    }
}
