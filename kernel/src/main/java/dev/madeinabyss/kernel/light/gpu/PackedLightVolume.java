package dev.madeinabyss.kernel.light.gpu;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.volume.*;
import java.util.*;
import java.nio.IntBuffer;

/** Transport-only view. A sealed optical component is never canonical solid geometry. */
public final class PackedLightVolume {
    public static final int UNKNOWN=0,UNIFORM=1,SEALED=2,DENSE=3;
    private final CubeKey min,max;
    private final int px,py,pz,nx,ny,nz;
    private final int[] directory,roots,data;
    private final boolean closed;
    private PackedLightVolume(CubeKey min,CubeKey max,int[] directory,int[] roots,int[] data,boolean closed){
        this.min=min;this.max=max;this.closed=closed;px=Math.floorDiv(min.x(),8);py=Math.floorDiv(min.y(),8);pz=Math.floorDiv(min.z(),8);
        nx=Math.floorDiv(max.x(),8)-px+1;ny=Math.floorDiv(max.y(),8)-py+1;nz=Math.floorDiv(max.z(),8)-pz+1;this.directory=directory;this.roots=roots;this.data=data;
    }
    public static PackedLightVolume compile(CubeKey min,CubeKey max,Map<VoxelVolumeArchive.PageKey,VoxelVolumeArchive.Page> source,boolean closure){
        Objects.requireNonNull(min);Objects.requireNonNull(max);if(min.x()>max.x()||min.y()>max.y()||min.z()>max.z())throw new IllegalArgumentException("Inverted volume");
        var pages=new TreeMap<VoxelVolumeArchive.PageKey,VoxelBrick[]>(Comparator.comparingInt(VoxelVolumeArchive.PageKey::y).thenComparingInt(VoxelVolumeArchive.PageKey::z).thenComparingInt(VoxelVolumeArchive.PageKey::x));source.forEach((key,page)->pages.put(key,page.bricks()));
        int px=Math.floorDiv(min.x(),8),py=Math.floorDiv(min.y(),8),pz=Math.floorDiv(min.z(),8),nx=Math.floorDiv(max.x(),8)-px+1,ny=Math.floorDiv(max.y(),8)-py+1,nz=Math.floorDiv(max.z(),8)-pz+1;
        int[] directory=new int[Math.multiplyExact(Math.multiplyExact(nx,ny),nz)],roots=new int[Math.multiplyExact(pages.size(),512)];if(closure)Arrays.fill(roots,SEALED);
        long words=0;for(var slots:pages.values())for(var brick:slots)if(brick!=null){if(!brick.allKnown())throw new IllegalArgumentException("Unknown brick in packed snapshot");if(!brick.uniformOpacity())words+=512;}
        if(words>=(1L<<30))throw new IllegalArgumentException("Opacity pool exceeds root address space");int[] data=new int[Math.toIntExact(words)];
        int nextRoot=0,nextData=0;
        for(var entry:pages.entrySet()){var p=entry.getKey();if(p.x()<px||p.x()>=px+nx||p.y()<py||p.y()>=py+ny||p.z()<pz||p.z()>=pz+nz)throw new IllegalArgumentException("Foreign page");
            directory[((p.y()-py)*nz+p.z()-pz)*nx+p.x()-px]=nextRoot+1;
            for(int i=0;i<512;i++){var brick=entry.getValue()[i];if(brick==null)continue;var key=new CubeKey(p.brickX(i),p.brickY(i),p.brickZ(i));if(!inside(key,min,max))throw new IllegalArgumentException("Foreign brick");
                if(brick.uniformOpacity())roots[nextRoot+i]=UNIFORM|(brick.opacityAt(0)<<2);
                else{roots[nextRoot+i]=DENSE|(nextData<<2);for(int w=0;w<512;w++){int value=0;for(int nibble=0;nibble<8;nibble++)value|=brick.opacityAt(w*8+nibble)<<(nibble*4);data[nextData++]=value;}}
            }nextRoot+=512;
        }
        if(closure)validateClosure(min,max,pages);
        return new PackedLightVolume(min,max,directory,roots,data,closure);
    }
    private static boolean inside(CubeKey k,CubeKey min,CubeKey max){return k.x()>=min.x()&&k.x()<=max.x()&&k.y()>=min.y()&&k.y()<=max.y()&&k.z()>=min.z()&&k.z()<=max.z();}
    private static VoxelBrick get(Map<VoxelVolumeArchive.PageKey,VoxelBrick[]> pages,CubeKey key){var p=pages.get(VoxelVolumeArchive.PageKey.ofBrick(key.x(),key.y(),key.z()));return p==null?null:p[VoxelVolumeArchive.PageKey.slot(key.x(),key.y(),key.z())];}
    private static void validateClosure(CubeKey min,CubeKey max,Map<VoxelVolumeArchive.PageKey,VoxelBrick[]> pages){
        for(int y=min.y();y<=max.y();y++)for(int z=min.z();z<=max.z();z++)for(int x=min.x();x<=max.x();x++)if(x==min.x()||x==max.x()||y==min.y()||y==max.y()||z==min.z()||z==max.z())if(get(pages,new CubeKey(x,y,z))==null)throw new IllegalArgumentException("Missing finite-volume boundary");
        for(var entry:pages.entrySet())for(int i=0;i<512;i++){var brick=entry.getValue()[i];if(brick==null||brick.uniformOpacity()&&brick.opacityAt(0)==15)continue;
            int mask=neighborMask(brick);var p=entry.getKey();var key=new CubeKey(p.brickX(i),p.brickY(i),p.brickZ(i));
            for(int bit=0;bit<27;bit++)if((mask&(1<<bit))!=0){var n=new CubeKey(key.x()+bit%3-1,key.y()+(bit/3)%3-1,key.z()+bit/9-1);if(inside(n,min,max)&&get(pages,n)==null)throw new IllegalArgumentException("Missing transmissive frontier "+n);}
        }
    }
    private static int neighborMask(VoxelBrick brick){if(brick.uniformOpacity())return((1<<27)-1)&~(1<<13);int mask=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if((x==0||x==15||y==0||y==15||z==0||z==15)&&brick.opacityAt((y<<8)|(z<<4)|x)<15)
            for(int dy=y==0?-1:0;dy<=(y==15?1:0);dy++)for(int dz=z==0?-1:0;dz<=(z==15?1:0);dz++)for(int dx=x==0?-1:0;dx<=(x==15?1:0);dx++)if(dx!=0||dy!=0||dz!=0)mask|=1<<((dz+1)*9+(dy+1)*3+dx+1);
        return mask;
    }
    private int root(int x,int y,int z){var key=new CubeKey(x>>4,y>>4,z>>4);if(!inside(key,min,max))return UNKNOWN;int page=directory[(((key.y()>>3)-py)*nz+(key.z()>>3)-pz)*nx+(key.x()>>3)-px];return page==0?(closed?SEALED:UNKNOWN):roots[page-1+VoxelVolumeArchive.PageKey.slot(key.x(),key.y(),key.z())];}
    private int value(int root,int x,int y,int z){int tag=root&3;if(tag==UNIFORM)return(root>>>2)&15;if(tag==DENSE){int i=SparseVoxelVolume.index(x,y,z);return(data[(root>>>2)+(i>>>3)]>>>((i&7)*4))&15;}return-1;}
    public int lightOpacity(int x,int y,int z){int root=root(x,y,z);return(root&3)==SEALED?15:value(root,x,y,z);}
    public int canonicalOpacity(int x,int y,int z){return value(root(x,y,z),x,y,z);}
    public IntBuffer directory(){return IntBuffer.wrap(directory).asReadOnlyBuffer();}
    public IntBuffer roots(){return IntBuffer.wrap(roots).asReadOnlyBuffer();}
    public IntBuffer data(){return IntBuffer.wrap(data).asReadOnlyBuffer();}
    /** Inclusive brick bounds, then page origin and dimensions. */
    public int[] dimensions(){return new int[]{min.x(),min.y(),min.z(),max.x(),max.y(),max.z(),px,py,pz,nx,ny,nz};}
}
