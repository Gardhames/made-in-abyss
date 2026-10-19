package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.natural.NaturalProtection.Box;
import java.util.*;

/** Immutable v15-only AABB index. False proves no authored path/cave reservation at the queried voxel. */
public final class LivingAbyssReservations {
    private static final long MAX_REFERENCES=2_000_000;
    private final Map<CubeKey,Box[]> index;
    private final dev.madeinabyss.kernel.concurrent.FrozenCubeTable<Box[]> table;
    private final int references;
    public LivingAbyssReservations(List<Box> boxes) {
        var staged=new HashMap<CubeKey,ArrayList<Box>>();long refs=0;
        for(var b:List.copyOf(boxes)){
            long xa=Math.max(Integer.MIN_VALUE,b.minX()),xb=Math.min(Integer.MAX_VALUE,b.maxX());
            long ya=Math.max(Integer.MIN_VALUE,b.minY()),yb=Math.min(Integer.MAX_VALUE,b.maxY());
            long za=Math.max(Integer.MIN_VALUE,b.minZ()),zb=Math.min(Integer.MAX_VALUE,b.maxZ());
            if(xa>xb||ya>yb||za>zb)continue;
            int x0=(int)Math.floorDiv(xa,16),x1=(int)Math.floorDiv(xb,16),y0=(int)Math.floorDiv(ya,16),y1=(int)Math.floorDiv(yb,16),z0=(int)Math.floorDiv(za,16),z1=(int)Math.floorDiv(zb,16);
            long nx=(long)x1-x0+1,ny=(long)y1-y0+1,nz=(long)z1-z0+1;
            if(nx>MAX_REFERENCES||ny>MAX_REFERENCES||nz>MAX_REFERENCES||nx*ny>MAX_REFERENCES||nz>MAX_REFERENCES/(nx*ny)||nx*ny*nz>MAX_REFERENCES-refs)
                throw new IllegalArgumentException("v15 reservation index exceeds bounded 2M references");
            refs+=nx*ny*nz;
            for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)staged.computeIfAbsent(new CubeKey(x,y,z),k->new ArrayList<>()).add(b);
        }
        var frozen=new HashMap<CubeKey,Box[]>();staged.forEach((k,v)->frozen.put(k,v.toArray(Box[]::new)));index=Map.copyOf(frozen);table=new dev.madeinabyss.kernel.concurrent.FrozenCubeTable<>(index);references=(int)refs;
    }
    public boolean contains(int x,int y,int z){
        var boxes=table.get(x>>4,y>>4,z>>4);
        if(boxes!=null)for(var b:boxes)if(x>=b.minX()&&x<=b.maxX()&&y>=b.minY()&&y<=b.maxY()&&z>=b.minZ()&&z<=b.maxZ())return true;
        return false;
    }
    /** One signed Y bitset per XZ column; no per-voxel geometry probes. Returns whether the cube intersects. */
    public boolean fillCubeMask(int x,int y,int z,short[] mask){
        if((x&15)!=0||(y&15)!=0||(z&15)!=0||mask.length!=256)throw new IllegalArgumentException("Aligned cube and 256 column masks required");
        Arrays.fill(mask,(short)0);var boxes=index.get(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)));
        if(boxes==null)return false;
        int xx=Math.addExact(x,15),yy=Math.addExact(y,15),zz=Math.addExact(z,15);boolean intersects=false;
        for(var b:boxes){int xa=(int)Math.max(x,b.minX()),xb=(int)Math.min(xx,b.maxX()),ya=(int)Math.max(y,b.minY()),yb=(int)Math.min(yy,b.maxY()),za=(int)Math.max(z,b.minZ()),zb=(int)Math.min(zz,b.maxZ());
            if(xa>xb||ya>yb||za>zb)continue;intersects=true;int bits=((1<<(yb-ya+1))-1)<<(ya-y);
            for(int qz=za-z;qz<=zb-z;qz++)for(int qx=xa-x;qx<=xb-x;qx++)mask[qz*16+qx]|=(short)bits;
        }
        return intersects;
    }
    public int cells(){return index.size();}public int references(){return references;}
    public Set<CubeKey> cubeKeys(){return index.keySet();}
}
