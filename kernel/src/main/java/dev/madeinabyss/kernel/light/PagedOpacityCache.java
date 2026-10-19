package dev.madeinabyss.kernel.light;

import java.util.Arrays;

/** Thread-confined exact opacity memo. Only visited cells are filled; no source/callback is retained. */
public final class PagedOpacityCache {
    public static final int MAX_CAPACITY=32768;
    private static final int PAGE_CELLS=512;
    private final int[] xs,ys,zs;
    private final byte[][] pages;
    private final int mask;
    private byte[] lastPage;
    private int lastX,lastY,lastZ,residentPages;
    private long hits,misses,evictions;

    public PagedOpacityCache(){this(4096);}
    public PagedOpacityCache(int capacity) {
        if(capacity<1||capacity>MAX_CAPACITY||(capacity&(capacity-1))!=0)
            throw new IllegalArgumentException("Opacity page capacity must be a power of two in1..32768");
        xs=new int[capacity];ys=new int[capacity];zs=new int[capacity];pages=new byte[capacity][];mask=capacity-1;
    }
    /** -1 means absent, including unvisited cells of a resident page. Reads never allocate. */
    public int get(int x,int y,int z) {
        int px=x>>3,py=y>>3,pz=z>>3;
        byte[] page;
        if(lastPage!=null&&lastX==px&&lastY==py&&lastZ==pz)page=lastPage;
        else {
            int slot=slot(px,py,pz);page=pages[slot];
            if(page==null||xs[slot]!=px||ys[slot]!=py||zs[slot]!=pz){misses++;return -1;}
            remember(page,px,py,pz);
        }
        int value=page[cell(x,y,z)];
        if(value>=0)hits++;else misses++;
        return value;
    }
    /** Publish only a successfully sampled scalar value; invalid/UNKNOWN values never allocate. */
    public void put(int x,int y,int z,int opacity) {
        if(opacity<0||opacity>15)throw new IllegalArgumentException("Opacity must be0..15");
        int px=x>>3,py=y>>3,pz=z>>3;
        byte[] page;
        if(lastPage!=null&&lastX==px&&lastY==py&&lastZ==pz)page=lastPage;
        else {
            int slot=slot(px,py,pz);page=pages[slot];
            if(page==null) {
                page=new byte[PAGE_CELLS];pages[slot]=page;residentPages++;
                Arrays.fill(page,(byte)-1);
            }else if(xs[slot]!=px||ys[slot]!=py||zs[slot]!=pz) {
                Arrays.fill(page,(byte)-1);evictions++;
            }
            xs[slot]=px;ys[slot]=py;zs[slot]=pz;remember(page,px,py,pz);
        }
        page[cell(x,y,z)]=(byte)opacity;
    }
    private void remember(byte[] page,int x,int y,int z){lastPage=page;lastX=x;lastY=y;lastZ=z;}
    private static int cell(int x,int y,int z){return ((y&7)<<6)|((z&7)<<3)|(x&7);}
    private int slot(int x,int y,int z) {
        return SkyProbeSingleFlight.hashCoordinates(x,y,z)&mask;
    }
    public int capacity(){return pages.length;}
    public int residentPages(){return residentPages;}
    /** Exact primitive-array payload bytes, excluding VM-dependent headers and reference arrays. */
    public long residentBytes(){return (long)xs.length*3*Integer.BYTES+(long)residentPages*PAGE_CELLS;}
    public long hits(){return hits;}
    public long misses(){return misses;}
    public long evictions(){return evictions;}
}
