package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.*;
import java.security.*;

/** Sparse immutable per-cube byte index: geometry is baked once, never rebuilt per voxel. */
public final class LivingAbyssVoxelPlan {
    private final Map<CubeKey,byte[]> cubes;private final dev.madeinabyss.kernel.concurrent.FrozenCubeTable<byte[]> table;private final long voxels;private final String digest;
    private LivingAbyssVoxelPlan(Map<CubeKey,byte[]> input){
        var copy=new HashMap<CubeKey,byte[]>();long count=0;for(var e:input.entrySet()){var bytes=e.getValue().clone();copy.put(e.getKey(),bytes);for(byte b:bytes)if(b!=0)count++;}cubes=Collections.unmodifiableMap(copy);table=new dev.madeinabyss.kernel.concurrent.FrozenCubeTable<>(copy);voxels=count;
        try{var md=MessageDigest.getInstance("SHA-256");var keys=new ArrayList<>(cubes.keySet());keys.sort(Comparator.comparingInt(CubeKey::x).thenComparingInt(CubeKey::y).thenComparingInt(CubeKey::z));
            for(var k:keys){md.update(java.nio.ByteBuffer.allocate(12).putInt(k.x()).putInt(k.y()).putInt(k.z()).array());md.update(cubes.get(k));}digest=HexFormat.of().formatHex(md.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public int at(int x,int y,int z){var data=table.get(x>>4,y>>4,z>>4);return data==null?0:Byte.toUnsignedInt(data[((y&15)*16+(z&15))*16+(x&15)]);}
    /** Immutable reader, not an array accessor. One lookup per prepared cube; absent cubes are null. */
    public static final class CubeView {
        private final byte[] values;
        private CubeView(byte[] values){this.values=values;}
        public int atLocal(int index){return Byte.toUnsignedInt(values[index]);}
    }
    public CubeView cubeView(int cx,int cy,int cz){var data=table.get(cx,cy,cz);return data==null?null:new CubeView(data);}
    /** False proves no feature voxel exists within one voxel of (x,y,z): all (up to eight) touched cubes are absent. */
    public boolean touches(int x,int y,int z){
        int x0=(x-1)>>4,x1=(x+1)>>4,y0=(y-1)>>4,y1=(y+1)>>4,z0=(z-1)>>4,z1=(z+1)>>4;
        for(int cx=x0;cx<=x1;cx++)for(int cy=y0;cy<=y1;cy++)for(int cz=z0;cz<=z1;cz++)if(table.get(cx,cy,cz)!=null)return true;
        return false;
    }
    public int cubeCount(){return cubes.size();}public long voxelCount(){return voxels;}public long estimatedBytes(){return (long)cubes.size()*(4096+96);}
    /** Immutable keys only; the private voxel arrays remain inaccessible. */
    public Set<CubeKey> cubeKeys(){return cubes.keySet();}
    public String digest(){return digest;}
    public void visit(LivingAbyssTreeStamp.Voxel visitor){for(var e:cubes.entrySet()){var k=e.getKey();var g=e.getValue();for(int i=0;i<4096;i++)if(g[i]!=0)visitor.accept(k.x()*16+(i&15),k.y()*16+(i>>8),k.z()*16+((i>>4)&15),Byte.toUnsignedInt(g[i]));}}
    public static final class Builder {
        private final dev.madeinabyss.kernel.concurrent.MutableCubeTable<byte[]> cubes=new dev.madeinabyss.kernel.concurrent.MutableCubeTable<>();
        public Builder(){}
        public int at(int x,int y,int z){var b=cubes.get(x>>4,y>>4,z>>4);return b==null?0:Byte.toUnsignedInt(b[((y&15)*16+(z&15))*16+(x&15)]);}
        public void put(int x,int y,int z,int code){if(code<0||code>255)throw new IllegalArgumentException("byte palette");int cx=x>>4,cy=y>>4,cz=z>>4;var g=cubes.get(cx,cy,cz);
            if(g==null){if(code==0)return;g=new byte[4096];cubes.put(cx,cy,cz,g);}g[((y&15)*16+(z&15))*16+(x&15)]=(byte)code;}
        public LivingAbyssVoxelPlan freeze(){var copy=new HashMap<CubeKey,byte[]>();cubes.forEach(copy::put);return new LivingAbyssVoxelPlan(copy);}
        public void clear(){cubes.clear();}
    }
}
