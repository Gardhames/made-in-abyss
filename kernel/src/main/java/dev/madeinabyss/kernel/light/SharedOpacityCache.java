package dev.madeinabyss.kernel.light;

import java.util.Arrays;
import java.util.Objects;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded exact memo owned by one immutable geometry source. Sparse 8-cubed pages are filled
 * only at requested cells; UNKNOWN is never certified as empty. Published hits do not lock.
 * Striped sampling deduplicates concurrent resident misses. Page tags never change, so an
 * evicted page held by a reader remains correct. No callback, source or worker is retained.
 * Different geometry revisions own different memos.
 */
public final class SharedOpacityCache {
    private static final VarHandle CELL=MethodHandles.arrayElementVarHandle(byte[].class);
    private static final class Page {
        final int x,y,z;
        final byte[] values=new byte[512];
        Page(int x,int y,int z){this.x=x;this.y=y;this.z=z;Arrays.fill(values,(byte)-1);}
        boolean matches(int x,int y,int z){return this.x==x&&this.y==y&&this.z==z;}
        int value(int index){return (byte)CELL.getAcquire(values,index);}
    }
    private final AtomicReferenceArray<Page> pages;
    private final Object[] locks;
    private final int mask;
    private final LongAdder hits=new LongAdder(),misses=new LongAdder(),evictions=new LongAdder(),resident=new LongAdder();

    public SharedOpacityCache(int capacity) {
        if(capacity<1||capacity>PagedOpacityCache.MAX_CAPACITY||(capacity&(capacity-1))!=0)
            throw new IllegalArgumentException("Shared opacity capacity must be a power of two in1..32768");
        pages=new AtomicReferenceArray<>(capacity);mask=capacity-1;
        locks=new Object[Math.min(256,capacity)];for(int i=0;i<locks.length;i++)locks[i]=new Object();
    }
    public int getOrCompute(int x,int y,int z,SkyVisibility.VoxelOpacity source) {
        Objects.requireNonNull(source,"source");
        int px=x>>3,py=y>>3,pz=z>>3,slot=slot(px,py,pz),cell=((y&7)<<6)|((z&7)<<3)|(x&7);
        Page page=pages.get(slot);
        if(page!=null&&page.matches(px,py,pz)){
            int value=page.value(cell);if(value>=0){hits.increment();return value;}
        }
        synchronized(locks[slot&(locks.length-1)]) {
            page=pages.get(slot);boolean matching=page!=null&&page.matches(px,py,pz);
            if(matching){int value=page.value(cell);if(value>=0){hits.increment();return value;}}
            misses.increment();
            // A failing/invalid sample cannot evict good cached geometry or publish an air default.
            int sampled=source.opacity(x,y,z);
            if(sampled<0||sampled>15)throw new IllegalArgumentException("Voxel opacity must be 0..15");
            if(!matching){
                if(page==null)resident.increment();else evictions.increment();
                page=new Page(px,py,pz);
                CELL.setRelease(page.values,cell,(byte)sampled);pages.set(slot,page);
            }else CELL.setRelease(page.values,cell,(byte)sampled);
            return sampled;
        }
    }
    private int slot(int x,int y,int z){
        return SkyProbeSingleFlight.hashCoordinates(x,y,z)&mask;
    }
    public int capacity(){return pages.length();}
    public long hits(){return hits.sum();}
    public long misses(){return misses.sum();}
    public long evictions(){return evictions.sum();}
    public long residentPages(){return resident.sum();}
    /** Primitive payload only; VM object/array headers, reference slots and locks are excluded. */
    public long residentBytes(){return residentPages()*(12+512);}
}
