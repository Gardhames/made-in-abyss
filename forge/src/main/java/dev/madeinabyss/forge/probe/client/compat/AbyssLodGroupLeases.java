package dev.madeinabyss.forge.probe.client.compat;

import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owner-only live GPU leases. Exact payload equality, never a digest, certifies sharing. */
public final class AbyssLodGroupLeases<B,G> {
    public record Scope(String session,long lifetime,String sourceIdentity,long sourceRevision,long resourceRevision){
        public Scope{Objects.requireNonNull(session);Objects.requireNonNull(sourceIdentity);}
    }
    public static final class Payload<B> {
        private final String partition;private final List<B> boxes;private final int sky,block,hash;
        public Payload(String partition,List<B> boxes,int sky,int block){
            this.partition=Objects.requireNonNull(partition);this.boxes=List.copyOf(boxes);this.sky=sky;this.block=block;
            if(boxes.isEmpty()||boxes.size()>4096||sky<0||sky>15||block<0||block>15)throw new IllegalArgumentException("Invalid bounded physical group payload");
            hash=Objects.hash(partition,this.boxes,sky,block);
        }
        public String partition(){return partition;}public List<B> boxes(){return boxes;}public int sky(){return sky;}public int block(){return block;}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return this==other||other instanceof Payload<?> p&&hash==p.hash&&sky==p.sky&&block==p.block&&partition.equals(p.partition)&&boxes.equals(p.boxes);}
    }
    private final Thread owner=Thread.currentThread();
    private final int maximum;
    private record Key(Scope scope,Payload<?> payload){}
    private final HashMap<Key,Core> live=new HashMap<>();
    private final IdentityHashMap<G,Core> identities=new IdentityHashMap<>();
    private long reuseHits;
    public AbyssLodGroupLeases(int maximum){if(maximum<1||maximum>4096)throw new IllegalArgumentException("Group lease cap");this.maximum=maximum;}
    public Lease acquire(Scope scope,Payload<B> payload,Supplier<G> factory,Consumer<G> dispose){
        requireOwner();Objects.requireNonNull(scope);Objects.requireNonNull(payload);Objects.requireNonNull(factory);Objects.requireNonNull(dispose);
        var key=new Key(scope,payload);var existing=live.get(key);
        if(existing!=null){existing.references++;reuseHits++;return new Lease(existing);}
        if(live.size()>=maximum)throw new IllegalStateException("Owned GPU group cap exceeded");
        G value=Objects.requireNonNull(factory.get());if(identities.containsKey(value))throw new IllegalStateException("Factory returned already owned group");
        var core=new Core(key,value,dispose);live.put(key,core);identities.put(value,core);return new Lease(core);
    }
    public int size(){requireOwner();return live.size();}
    public long reuseHits(){requireOwner();return reuseHits;}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("GPU leases require their owner thread");}
    private final class Core {
        final Key key;final G value;final Consumer<G> dispose;int references=1;boolean warm;
        Core(Key key,G value,Consumer<G> dispose){this.key=key;this.value=value;this.dispose=dispose;}
    }
    public final class Lease implements AutoCloseable {
        private final Core core;private boolean closed;
        private Lease(Core core){this.core=core;}
        public G value(){return core.value;}
        public boolean warm(){requireOwner();return core.warm;}
        public void markWarm(){requireOwner();if(!closed)core.warm=true;}
        @Override public void close(){requireOwner();if(closed)return;closed=true;if(--core.references==0){live.remove(core.key);identities.remove(core.value);core.dispose.accept(core.value);}}
    }
}
