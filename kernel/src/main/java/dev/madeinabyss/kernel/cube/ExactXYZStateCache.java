package dev.madeinabyss.kernel.cube;

import java.util.Arrays;

/** Thread-confined bounded memo. Full signed tags make hash collisions ordinary misses. */
public final class ExactXYZStateCache {
    public static final int MAX_CAPACITY=65536;
    private final int[] xs,ys,zs,states;
    private final int mask;
    // Live diagnostic estimates; no atomic operation on the sampling hot path.
    private long hits,misses;
    public ExactXYZStateCache(int capacity) {
        if(capacity<1 || capacity>MAX_CAPACITY || (capacity&(capacity-1))!=0)
            throw new IllegalArgumentException("State cache capacity must be a power of two in 1..65536");
        xs=new int[capacity];ys=new int[capacity];zs=new int[capacity];states=new int[capacity];
        Arrays.fill(states,-1);mask=capacity-1;
    }
    public int get(int x,int y,int z) {
        int slot=slot(x,y,z),state=states[slot];
        if(state>=0 && xs[slot]==x && ys[slot]==y && zs[slot]==z){hits++;return state;}
        misses++;return -1;
    }
    public void put(int x,int y,int z,int state) {
        if(state<0)throw new IllegalArgumentException("Unknown state cannot be memoized");
        int slot=slot(x,y,z);xs[slot]=x;ys[slot]=y;zs[slot]=z;states[slot]=state;
    }
    private int slot(int x,int y,int z) {
        int hash=x*0x8da6b343 ^ y*0xd8163841 ^ z*0xcb1ab31f;
        hash^=hash>>>16;hash*=0x7feb352d;hash^=hash>>>15;hash*=0x846ca68b;hash^=hash>>>16;
        return hash&mask;
    }
    public int capacity(){return states.length;}
    public long hits(){return hits;}
    public long misses(){return misses;}
}
