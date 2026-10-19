package dev.madeinabyss.kernel.lod;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owner-thread leases for immutable surface identity, not expensive payload equality or digests. */
public final class SurfacePageLeases<P,G> {
    public record Scope(String session,long lifetime,String sourceIdentity,long sourceRevision,long resourceRevision,
                        long presentationRevision,long materialRevision,Object model){
        public Scope{Objects.requireNonNull(session);Objects.requireNonNull(sourceIdentity);Objects.requireNonNull(model);}
        @Override public boolean equals(Object value){return value instanceof Scope s&&session.equals(s.session)&&lifetime==s.lifetime
            &&sourceIdentity.equals(s.sourceIdentity)&&sourceRevision==s.sourceRevision&&resourceRevision==s.resourceRevision
            &&presentationRevision==s.presentationRevision&&materialRevision==s.materialRevision&&model==s.model;}
        @Override public int hashCode(){int h=session.hashCode();h=31*h+Long.hashCode(lifetime);h=31*h+sourceIdentity.hashCode();
            h=31*h+Long.hashCode(sourceRevision);h=31*h+Long.hashCode(resourceRevision);h=31*h+Long.hashCode(presentationRevision);
            h=31*h+Long.hashCode(materialRevision);return 31*h+System.identityHashCode(model);}
    }
    private final Thread owner=Thread.currentThread();private final int maximum;
    private final HashMap<Scope,IdentityHashMap<P,Core>> live=new HashMap<>();
    private final IdentityHashMap<G,Core> values=new IdentityHashMap<>();
    private int entries;private long reuses,creations,disposals;
    public SurfacePageLeases(int maximum){if(maximum<1||maximum>16384)throw new IllegalArgumentException("Surface page lease cap");this.maximum=maximum;}
    public Lease acquire(Scope scope,P prepared,Supplier<G> factory,Consumer<G> dispose){
        requireOwner();Objects.requireNonNull(scope);Objects.requireNonNull(prepared);Objects.requireNonNull(factory);Objects.requireNonNull(dispose);
        var bucket=live.get(scope);var existing=bucket==null?null:bucket.get(prepared);
        if(existing!=null){existing.references++;reuses++;return new Lease(existing);}
        if(entries>=maximum)throw new IllegalStateException("Surface page lease cap exceeded");
        G value=Objects.requireNonNull(factory.get());if(values.containsKey(value))throw new IllegalStateException("Factory returned an already owned surface page");
        var core=new Core(scope,prepared,value,dispose);
        live.computeIfAbsent(scope,unused->new IdentityHashMap<>()).put(prepared,core);values.put(value,core);entries++;creations++;return new Lease(core);
    }
    public int size(){requireOwner();return entries;}
    public long reuses(){requireOwner();return reuses;}
    public long creations(){requireOwner();return creations;}
    public long disposals(){requireOwner();return disposals;}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Surface page leases require owner thread");}
    private final class Core {
        final Scope scope;final P prepared;final G value;final Consumer<G> dispose;int references=1;boolean ready;
        Core(Scope scope,P prepared,G value,Consumer<G> dispose){this.scope=scope;this.prepared=prepared;this.value=value;this.dispose=dispose;}
    }
    public final class Lease implements AutoCloseable {
        private final Core core;private boolean closed;Lease(Core core){this.core=core;}
        public G value(){requireOwner();if(closed)throw new IllegalStateException("Closed surface page lease");return core.value;}
        public boolean ready(){requireOwner();return !closed&&core.ready;}
        public void markReady(){requireOwner();if(closed)throw new IllegalStateException("Closed surface page lease");core.ready=true;}
        @Override public void close(){requireOwner();if(closed)return;closed=true;if(--core.references==0){
            var bucket=live.get(core.scope);bucket.remove(core.prepared);if(bucket.isEmpty())live.remove(core.scope);
            values.remove(core.value);entries--;disposals++;core.dispose.accept(core.value);}}
    }
}
