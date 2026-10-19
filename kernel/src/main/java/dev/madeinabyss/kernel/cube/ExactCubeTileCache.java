package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;
import dev.madeinabyss.kernel.light.SkyProbeSingleFlight;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Completed immutable16³ tiles for one immutable geometry/physical source.
 * Exact signed keys never alias. FIFO eviction changes reuse only, not authority.
 * No supplier, checkpoint, source or worker is retained after a request returns. */
public final class ExactCubeTileCache {
    public static final long DEFAULT_MAX_BYTES=64L*1024*1024;
    // Long exact rays cross many homogeneous AIR tiles. Bound compressed payload separately
    // from directory entries so a few MiB of AIR does not continually rebuild at4096 entries.
    public static final int DEFAULT_MAX_TILES=65536;
    private final BoundedReuseCache<CubeKey,Tile> completed;
    private final ReentrantLock[] stripes=new ReentrantLock[64];
    private final LongAdder hits=new LongAdder(),misses=new LongAdder(),builds=new LongAdder(),joins=new LongAdder();

    public ExactCubeTileCache(long maxBytes,int maxTiles) {
        completed=new BoundedReuseCache<>(maxBytes,maxTiles,Tile::residentBytes);
        for(int i=0;i<stripes.length;i++)stripes[i]=new ReentrantLock();
    }

    /** A cancelled/failed factory has no observable partial tile. Cooperative waiting lets
     * a superseded target leave without waiting for another target's full cube scan. */
    public Tile getOrCompute(CubeKey key,Supplier<Tile> factory,Runnable checkpoint) {
        Objects.requireNonNull(key);Objects.requireNonNull(factory);Objects.requireNonNull(checkpoint);
        check(checkpoint);var cached=completed.get(key);
        if(cached!=null){hits.increment();return cached;}
        misses.increment();
        var lock=stripes[SkyProbeSingleFlight.hashCoordinates(key.x(),key.y(),key.z())&(stripes.length-1)];
        boolean acquired=false;
        try {
            while(!acquired) {
                check(checkpoint);
                try{acquired=lock.tryLock(2,TimeUnit.MILLISECONDS);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("Interrupted waiting for exact cube tile");}
            }
            check(checkpoint);cached=completed.get(key);
            if(cached!=null){hits.increment();joins.increment();return cached;}
            var made=Objects.requireNonNull(factory.get(),"Exact cube tile factory returned null");
            check(checkpoint);completed.put(key,made);builds.increment();return made;
        } finally {if(acquired)lock.unlock();}
    }

    private static void check(Runnable checkpoint){
        if(Thread.currentThread().isInterrupted())throw new CancellationException("Interrupted exact cube tile request");
        checkpoint.run();
    }
    public long hits(){return hits.sum();}
    public long misses(){return misses.sum();}
    public long builds(){return builds.sum();}
    public long joins(){return joins.sum();}
    public long evictions(){return completed.evictions();}
    public long residentBytes(){return completed.weight();}
    public int residentTiles(){return completed.size();}

    /** States and opacity compress independently when homogeneous. Neither input nor
     * an internal array escapes; opacity never stands in for full state identity. */
    public static final class Tile {
        private final CubeStates states;
        private final byte[] opacity;
        private final int uniformState;
        private final byte uniformOpacity;

        private Tile(CubeStates states,byte[] opacity,int uniformState,byte uniformOpacity){
            this.states=states;this.opacity=opacity;this.uniformState=uniformState;this.uniformOpacity=uniformOpacity;
        }
        /** A cube-wide proof may supply one exact state and opacity without scan buffers. */
        public static Tile uniform(int state,int opacity) {
            if(state<0)throw new IllegalArgumentException("UNKNOWN state cannot enter an exact tile");
            if(opacity<0||opacity>15)throw new IllegalArgumentException("Exact tile opacity must be0..15");
            return new Tile(null,null,state,(byte)opacity);
        }
        public static Tile copyOf(int[] states,byte[] opacity) {
            Objects.requireNonNull(states);Objects.requireNonNull(opacity);
            if(states.length!=4096||opacity.length!=4096)throw new IllegalArgumentException("Exact tile must contain4096 states and opacities");
            // Copy before validation: the published arrays never retain caller ownership.
            var stateCopy=states.clone();var opacityCopy=opacity.clone();boolean sameState=true,sameOpacity=true;
            for(int i=0;i<4096;i++) {
                if(stateCopy[i]<0)throw new IllegalArgumentException("UNKNOWN state cannot enter an exact tile");
                if(opacityCopy[i]<0||opacityCopy[i]>15)throw new IllegalArgumentException("Exact tile opacity must be0..15");
                sameState&=stateCopy[i]==stateCopy[0];sameOpacity&=opacityCopy[i]==opacityCopy[0];
            }
            return new Tile(sameState?null:CubeStates.copyOf(stateCopy),sameOpacity?null:opacityCopy,stateCopy[0],opacityCopy[0]);
        }
        public int stateAt(int index){Objects.checkIndex(index,4096);return states==null?uniformState:states.get(index);}
        public int opacityAt(int index){Objects.checkIndex(index,4096);return opacity==null?uniformOpacity:opacity[index];}
        /** Caller-owned density snapshot in the native cube y/z/x order. */
        public int[] states(){if(states!=null)return states.toArray();var result=new int[4096];Arrays.fill(result,uniformState);return result;}
        /** Primitive payload only; VM-dependent headers/references and lock objects are excluded. */
        public long residentBytes(){return (states==null?Integer.BYTES:4096L*Integer.BYTES)+(opacity==null?1:4096L);}
    }
}
