package dev.madeinabyss.kernel.lod;

import java.nio.charset.StandardCharsets;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Worker-confined bounded FIFO proofs. Keys never hash/compare the immutable RAW contents.
 * Weak RAW identities prevent diagnostic proofs retaining evicted heavyweight geometry.
 * Legacy SHA text and box order deliberately remain byte-exact.
 */
public final class RawGridProofCache {
    private final int maxEntries;
    private final ReferenceQueue<TerrainLodColumns.Result> collected=new ReferenceQueue<>();
    private final HashMap<Key,String> entries=new HashMap<>();
    private final ArrayDeque<Key> order=new ArrayDeque<>();
    private long digestBuilds,evictions,hits;
    private static final class Key extends WeakReference<TerrainLodColumns.Result> {
        private final String source;
        private final TerrainLodColumns.Bounds bounds;
        private final int step,hash;
        Key(AbyssLodFineLedger.Grid grid,ReferenceQueue<TerrainLodColumns.Result> queue){
            super(grid.raw(),queue);source=grid.sourceIdentity();bounds=grid.bounds();step=grid.step();
            hash=31*(31*(31*source.hashCode()+bounds.hashCode())+step)+System.identityHashCode(grid.raw());
        }
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){
            if(this==other)return true;
            if(!(other instanceof Key key))return false;
            var raw=get();return raw!=null&&raw==key.get()&&step==key.step&&source.equals(key.source)&&bounds.equals(key.bounds);
        }
    }
    public RawGridProofCache(int maxEntries){
        if(maxEntries<1||maxEntries>32768)throw new IllegalArgumentException("Proof entry cap must be1..32768");
        this.maxEntries=maxEntries;
    }
    public String proof(AbyssLodFineLedger.Grid grid){
        Objects.requireNonNull(grid);var raw=grid.raw();try{
            checkCanceled();discardCollected();
            var existing=entries.get(new Key(grid,null));
            if(existing!=null){hits++;return existing;}
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update((grid.sourceIdentity()+"/"+grid.bounds()+"/"+grid.step()).getBytes(StandardCharsets.UTF_8));
            for(var box:raw.boxes()){
                checkCanceled();
                digest.update(box.toString().getBytes(StandardCharsets.UTF_8));
            }
            checkCanceled();String proof=HexFormat.of().formatHex(digest.digest());
            // Bound both live entries and weak-key tombstones; GC cannot grow the FIFO indefinitely.
            while(order.size()>=maxEntries){if(entries.remove(order.removeFirst())!=null)evictions++;}
            var key=new Key(grid,collected);entries.put(key,proof);order.addLast(key);digestBuilds++;return proof;
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        finally{Reference.reachabilityFence(raw);}
    }
    private void discardCollected(){Reference<? extends TerrainLodColumns.Result> key;while((key=collected.poll())!=null)entries.remove(key);}
    private static void checkCanceled(){if(Thread.currentThread().isInterrupted())throw new CancellationException("Raw proof canceled");}
    public long digestBuilds(){return digestBuilds;}
    public long hits(){return hits;}
    public int residentEntries(){discardCollected();return entries.size();}
    public long evictions(){return evictions;}
    public void clear(){entries.clear();order.clear();while(collected.poll()!=null){} }
}
