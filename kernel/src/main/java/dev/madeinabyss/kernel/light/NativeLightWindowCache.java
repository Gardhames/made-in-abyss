package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Disposable exact native window snapshots. Four bounded slots; no world writes. */
public final class NativeLightWindowCache {
    private static final int MAGIC=0x4e4c5743,VERSION=1;
    private static final long BYTE_LIMIT=384L*1024*1024;
    private NativeLightWindowCache() { }
    public static final class Entry {
        private final VoxelSkyLight.Bounds bounds;
        private final VoxelSkyLight.Result sky,block;
        private final Map<CubeKey,int[]> cubes;
        public Entry(VoxelSkyLight.Bounds bounds,VoxelSkyLight.Result sky,VoxelSkyLight.Result block,Map<CubeKey,int[]> cubes) {
            int cells=VoxelSkyLight.cellCount(bounds,40_000_000);
            if(!bounds.equals(sky.bounds())||!bounds.equals(block.bounds()))throw new IllegalArgumentException("Light bounds mismatch");
            long nx=completeCount(bounds.minX(),bounds.sizeX()),ny=completeCount(bounds.minY(),bounds.sizeY()),nz=completeCount(bounds.minZ(),bounds.sizeZ());
            if(cubes.size()!=nx*ny*nz||cubes.size()>cells/4096)throw new IllegalArgumentException("Incomplete full-cube density");
            var copy=new HashMap<CubeKey,int[]>();
            cubes.forEach((key,states)->{
                if(states.length!=4096||!inside(key.x(),bounds.minX(),bounds.sizeX())||!inside(key.y(),bounds.minY(),bounds.sizeY())||!inside(key.z(),bounds.minZ(),bounds.sizeZ()))throw new IllegalArgumentException("Density cube outside bounds");
                var cloned=states.clone();for(int id:cloned)if(id<0)throw new IllegalArgumentException("Negative state ID");copy.put(key,cloned);
            });
            this.bounds=bounds;this.sky=sky;this.block=block;this.cubes=Collections.unmodifiableMap(copy);
        }
        public VoxelSkyLight.Bounds bounds(){return bounds;}
        public VoxelSkyLight.Result sky(){return sky;}
        public VoxelSkyLight.Result block(){return block;}
        public Map<CubeKey,int[]> cubes(){var copy=new HashMap<CubeKey,int[]>();cubes.forEach((k,v)->copy.put(k,v.clone()));return Collections.unmodifiableMap(copy);}
    }
    public record Read(Optional<Entry> entry,String status) { }
    private static long completeCount(int min,int size){return Math.max(0,Math.floorDiv((long)min+size,16)+Math.floorDiv(-(long)min,16));}
    private static boolean inside(int cube,int min,int size){long base=(long)cube*16;return base>=min&&base+16<=(long)min+size;}
    public static Path file(Path directory,String key){if(key==null||!key.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("SHA256 identity required");return directory.resolve("slot-"+(Character.digit(key.charAt(63),16)&3)+".native.gz");}
    public static Read read(Path directory,String key,VoxelSkyLight.Bounds bounds,int maxCells,int paletteSize) {
        int cells=VoxelSkyLight.cellCount(bounds,maxCells);var file=file(directory,key);
        try {
            if(!Files.exists(file))return miss("MISS");if(Files.size(file)>BYTE_LIMIT)return miss("OVERSIZE");
            try(var input=new DataInputStream(new BufferedInputStream(new LimitedInput(new GZIPInputStream(new LimitedInput(Files.newInputStream(file)))),65536))) {
                var digest=sha();var hashing=new DigestInputStream(input,digest);var in=new DataInputStream(hashing);
                if(in.readInt()!=MAGIC||in.readInt()!=VERSION)return miss("FORMAT_MISS");
                if(!key.equals(in.readUTF()))return miss("IDENTITY_MISS");
                var savedBounds=new VoxelSkyLight.Bounds(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());
                if(!bounds.equals(savedBounds)||in.readInt()!=cells)return miss("BOUNDS_MISS");
                var sky=readLight(in,bounds,cells,maxCells);var block=readLight(in,bounds,cells,maxCells);
                int count=in.readInt();long expected=completeCount(bounds.minX(),bounds.sizeX())*completeCount(bounds.minY(),bounds.sizeY())*completeCount(bounds.minZ(),bounds.sizeZ());
                if(count!=expected||count<0||count>cells/4096)return miss("DENSITY_MISS");
                var cubes=new HashMap<CubeKey,int[]>();
                for(int i=0;i<count;i++) {
                    var cube=new CubeKey(in.readInt(),in.readInt(),in.readInt());
                    if(!inside(cube.x(),bounds.minX(),bounds.sizeX())||!inside(cube.y(),bounds.minY(),bounds.sizeY())||!inside(cube.z(),bounds.minZ(),bounds.sizeZ()))return miss("DENSITY_MISS");
                    int[] states=new int[4096];for(int j=0;j<4096;j++){states[j]=in.readInt();if(states[j]<0||states[j]>=paletteSize)return miss("PALETTE_MISS");}
                    if(cubes.put(cube,states)!=null)return miss("DUPLICATE_CUBE");
                }
                var actual=digest.digest();hashing.on(false);byte[] expectedDigest=new byte[32];in.readFully(expectedDigest);
                if(!MessageDigest.isEqual(actual,expectedDigest)||in.read()!=-1)return miss("CORRUPT");
                return new Read(Optional.of(new Entry(bounds,sky,block,cubes)),"HIT");
            }
        }catch(IOException|IllegalArgumentException invalid){return miss("CORRUPT");}
    }
    private static VoxelSkyLight.Result readLight(DataInputStream in,VoxelSkyLight.Bounds bounds,int cells,int maxCells)throws IOException {
        byte[] lower=new byte[cells],upper=new byte[cells];in.readFully(lower);in.readFully(upper);return VoxelSkyLight.restore(bounds,lower,upper,maxCells);
    }
    public static void write(Path directory,String key,Entry entry)throws IOException {
        var destination=file(directory,key);Files.createDirectories(directory);var temporary=Files.createTempFile(directory,".native-",".tmp");
        try {
            try(var compressed=new GZIPOutputStream(new LimitedOutput(Files.newOutputStream(temporary)))) {
                var digest=sha();var hashing=new DigestOutputStream(new BufferedOutputStream(new LimitedOutput(compressed),65536),digest);var out=new DataOutputStream(hashing);
                out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(key);var b=entry.bounds;
                out.writeInt(b.minX());out.writeInt(b.minY());out.writeInt(b.minZ());out.writeInt(b.sizeX());out.writeInt(b.sizeY());out.writeInt(b.sizeZ());out.writeInt(VoxelSkyLight.cellCount(b,40_000_000));
                for(var light:List.of(entry.sky,entry.block)){out.write(light.lowerSnapshot());out.write(light.upperSnapshot());}
                var keys=new ArrayList<>(entry.cubes.keySet());keys.sort(Comparator.comparingInt(CubeKey::x).thenComparingInt(CubeKey::y).thenComparingInt(CubeKey::z));out.writeInt(keys.size());
                for(var cube:keys){out.writeInt(cube.x());out.writeInt(cube.y());out.writeInt(cube.z());for(int state:entry.cubes.get(cube))out.writeInt(state);}
                byte[] checksum=digest.digest();hashing.on(false);out.write(checksum);out.flush();
            }
            // An unsupported atomic move is a cache-write miss; never expose a partial replacement.
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    private static Read miss(String status){return new Read(Optional.empty(),status);}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static final class LimitedInput extends FilterInputStream {
        private long count;LimitedInput(InputStream in){super(in);}
        private void used(int n)throws IOException{if(n>0&&(count+=n)>BYTE_LIMIT)throw new IOException("Native cache byte bound");}
        @Override public int read()throws IOException{int b=in.read();used(b<0?0:1);return b;}
        @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,(int)Math.min(len,BYTE_LIMIT-count+1));used(n);return n;}
    }
    private static final class LimitedOutput extends FilterOutputStream {
        private long count;LimitedOutput(OutputStream out){super(out);}
        private void reserve(int n)throws IOException{if((count+=n)>BYTE_LIMIT)throw new IOException("Native cache byte bound");}
        @Override public void write(int b)throws IOException{reserve(1);out.write(b);}
        @Override public void write(byte[] b,int off,int len)throws IOException{reserve(len);out.write(b,off,len);}
    }
}
