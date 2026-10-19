package dev.madeinabyss.kernel.terrain.natural;

import java.util.Arrays;
import java.util.Map;

/** Immutable exact full signed XYZ lookup. No coordinate packing, query allocations or tree bins. */
public final class RouteVoxelIndex {
    static final int MAX_OCCUPANCY_CELLS=2*1024*1024;
    private final int[] xs,ys,zs,values;
    private final int mask;
    private final boolean empty;
    private final int minCellX,minCellY,minCellZ,cellsX,cellsY,cellsZ;
    private final boolean[] occupied;
    RouteVoxelIndex(Map<SupportedCaveTerrainSampler.Foot,Integer> entries) {
        if(entries.size()>SupportedCaveTerrainSampler.MAX_ROUTE_VOXELS)throw new IllegalArgumentException("Route voxel index exceeds bound");
        int capacity=1;while(capacity<entries.size()*2L)capacity<<=1;
        mask=capacity-1;xs=new int[capacity];ys=new int[capacity];zs=new int[capacity];values=new int[capacity];Arrays.fill(values,-1);
        empty=entries.isEmpty();
        int xa=Integer.MAX_VALUE,ya=Integer.MAX_VALUE,za=Integer.MAX_VALUE;
        int xb=Integer.MIN_VALUE,yb=Integer.MIN_VALUE,zb=Integer.MIN_VALUE;
        for(var entry:entries.entrySet()) {
            var key=entry.getKey();int value=entry.getValue();if(value<0)throw new IllegalArgumentException("Negative route voxel code");
            int slot=hash(key.x(),key.y(),key.z())&mask;
            while(values[slot]!=-1)slot=(slot+1)&mask;
            xs[slot]=key.x();ys[slot]=key.y();zs[slot]=key.z();values[slot]=value;
            int cx=Math.floorDiv(key.x(),16),cy=Math.floorDiv(key.y(),16),cz=Math.floorDiv(key.z(),16);
            xa=Math.min(xa,cx);ya=Math.min(ya,cy);za=Math.min(za,cz);
            xb=Math.max(xb,cx);yb=Math.max(yb,cy);zb=Math.max(zb,cz);
        }
        minCellX=empty?0:xa;minCellY=empty?0:ya;minCellZ=empty?0:za;
        cellsX=empty?0:(int)((long)xb-xa+1);cellsY=empty?0:(int)((long)yb-ya+1);cellsZ=empty?0:(int)((long)zb-za+1);
        // Cap before multiplication: full signed-int cuboids can overflow even a long.
        long xy=(long)cellsX*cellsY;
        boolean bounded=empty||(cellsX<=MAX_OCCUPANCY_CELLS&&xy<=MAX_OCCUPANCY_CELLS
                &&cellsZ<=MAX_OCCUPANCY_CELLS/xy);
        occupied=bounded?new boolean[empty?0:(int)(xy*cellsZ)]:null;
        if(!empty&&occupied!=null)for(var key:entries.keySet()){
            int cx=Math.floorDiv(key.x(),16)-minCellX,cy=Math.floorDiv(key.y(),16)-minCellY,cz=Math.floorDiv(key.z(),16)-minCellZ;
            occupied[cx+cellsX*(cz+cellsZ*cy)]=true;
        }
    }
    int get(int x,int y,int z) {
        if(!mayContain(x,y,z))return -1;
        int slot=hash(x,y,z)&mask;
        while(values[slot]!=-1) {
            if(xs[slot]==x&&ys[slot]==y&&zs[slot]==z)return values[slot];
            slot=(slot+1)&mask;
        }
        return -1;
    }
    /** False proves the immutable route table has no entry in this signed cell. */
    boolean mayContain(int x,int y,int z){
        if(empty)return false;
        // Huge sparse extents retain the exact primitive hash; no unbounded allocation.
        if(occupied==null)return true;
        int cx=Math.floorDiv(x,16)-minCellX,cy=Math.floorDiv(y,16)-minCellY,cz=Math.floorDiv(z,16)-minCellZ;
        return cx>=0&&cx<cellsX&&cy>=0&&cy<cellsY&&cz>=0&&cz<cellsZ
                &&occupied[cx+cellsX*(cz+cellsZ*cy)];
    }
    int occupancyCellCount(){return occupied==null?0:occupied.length;}
    static int hash(int x,int y,int z) {
        long h=(Integer.toUnsignedLong(x)*0x9e3779b97f4a7c15L)
                ^Long.rotateLeft(Integer.toUnsignedLong(y)*0xbf58476d1ce4e5b9L,21)
                ^Long.rotateLeft(Integer.toUnsignedLong(z)*0x94d049bb133111ebL,42);
        h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;h^=h>>>31;
        return (int)(h^(h>>>32));
    }
}
