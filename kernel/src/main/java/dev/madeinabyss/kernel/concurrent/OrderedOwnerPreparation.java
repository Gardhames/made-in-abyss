package dev.madeinabyss.kernel.concurrent;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.*;

/** One private preparation at a time; all admission, completion and packet barriers belong to the owner.
 * Retiring a generation never waits for a worker. Its actual exit/completion releases the slot.
 * Pending deltas are intentionally not coalesced: a later revision can depend on every earlier one.
 */
public final class OrderedOwnerPreparation {
    public record Work<T>(Function<Runnable,T> prepare,BiConsumer<T,RuntimeException> complete) {
        public Work {Objects.requireNonNull(prepare);Objects.requireNonNull(complete);}
    }
    private final Thread ownerThread=Thread.currentThread();
    private final Executor worker,owner;
    private final ArrayDeque<Runnable> pending=new ArrayDeque<>();
    private Ticket active;
    private boolean draining;
    private static final class Ticket {
        volatile boolean cancelled;
        void checkpoint(){if(cancelled||Thread.currentThread().isInterrupted())throw new CancellationException("Window preparation retired");}
    }
    public OrderedOwnerPreparation(Executor worker,Executor owner) {
        this.worker=Objects.requireNonNull(worker);this.owner=Objects.requireNonNull(owner);
    }
    private void assertOwner(){if(Thread.currentThread()!=ownerThread)throw new IllegalStateException("Window publication requires its owner thread");}
    public <T> void submit(Supplier<Work<T>> starter) {
        assertOwner();Objects.requireNonNull(starter);
        pending.addLast(()->start(starter));drain();
    }
    private <T> void start(Supplier<Work<T>> starter) {
        var work=starter.get();if(work==null)return;
        var ticket=new Ticket();active=ticket;
        try {worker.execute(()->{
            T result=null;RuntimeException failure=null;Error fatal=null;
            try {ticket.checkpoint();result=work.prepare().apply(ticket::checkpoint);}
            catch(RuntimeException e){failure=e;}catch(Error e){fatal=e;}
            T prepared=result;RuntimeException refused=failure;Error failed=fatal;
            owner.execute(()->finish(ticket,work,prepared,refused,failed));
        });}catch(RuntimeException rejected){finish(ticket,work,null,rejected,null);}
    }
    private <T> void finish(Ticket ticket,Work<T> work,T prepared,RuntimeException failure,Error fatal) {
        assertOwner();if(active!=ticket)return;
        active=null;boolean wasDraining=draining;draining=true;
        try {
            if(!ticket.cancelled){if(fatal!=null)throw fatal;work.complete().accept(prepared,failure);}
        } finally {draining=wasDraining;drain();}
    }
    public void after(Runnable action){
        assertOwner();pending.addLast(Objects.requireNonNull(action));drain();
    }
    private void drain(){
        assertOwner();if(draining)return;draining=true;
        try {while(active==null&&!pending.isEmpty())pending.removeFirst().run();}
        finally {draining=false;}
    }
    public void clear() {
        assertOwner();pending.clear();if(active!=null)active.cancelled=true;
    }
    public int pending(){assertOwner();return pending.size();}
    public boolean active(){assertOwner();return active!=null;}
}
