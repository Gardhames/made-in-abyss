package dev.madeinabyss.kernel.lod;
import java.nio.file.Path;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
public final class TerrainLodDiskCache {
    private static final int MAGIC=0x4d494443,SCHEMA=1;
    private static final long MAX_BYTES=64L*1024*1024;
    public record Light(int sky,int block) {
        public Light {if(sky<0||sky>15||block<0||block>15)throw new IllegalArgumentException("Scalar light0..15 required");}
    }
    public record Entry(TerrainLodColumns.Result base,Map<TerrainLodColumns.Box,Light> lights) {
        public Entry {Objects.requireNonNull(base);lights=Collections.unmodifiableMap(new HashMap<>(lights));}
    }
    public record Read(Optional<Entry> entry,String status) {}
    private TerrainLodDiskCache() {}
    public static String viewIdentity(String baseKey,String sourceKey,String quality,String bands,double x,double y,double z,double clip,
                                      Map<dev.madeinabyss.kernel.cube.CubeKey,byte[]> mirror){
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Double.isFinite(clip))throw new IllegalArgumentException("Finite exact view required");
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest));
            out.writeUTF("dh-exact-view-v1");out.writeUTF(baseKey);out.writeUTF(sourceKey);out.writeUTF(quality);out.writeUTF(bands);
            out.writeDouble(x);out.writeDouble(y);out.writeDouble(z);out.writeDouble(clip);
            var keys=new ArrayList<>(mirror.keySet());keys.sort(Comparator.comparingInt(dev.madeinabyss.kernel.cube.CubeKey::x)
                    .thenComparingInt(dev.madeinabyss.kernel.cube.CubeKey::y).thenComparingInt(dev.madeinabyss.kernel.cube.CubeKey::z));
            out.writeInt(keys.size());for(var key:keys){out.writeInt(key.x());out.writeInt(key.y());out.writeInt(key.z());var states=mirror.get(key);out.writeInt(states.length);out.write(states);}
            out.flush();return HexFormat.of().formatHex(digest.digest());
        }catch(IOException|NoSuchAlgorithmException failed){throw new IllegalStateException("Cannot identify exact cached view",failed);}
    }
    public static Read loadScene(Path directory,String key,TerrainLodColumns.Bounds bounds,TerrainLodColumns.Parameters limits,long expectedSamples){return load0(directory,key,bounds,limits,true,expectedSamples);}
    public static void saveScene(Path directory,String key,Entry entry,TerrainLodColumns.Bounds bounds,TerrainLodColumns.Parameters limits,long expectedSamples)throws IOException{save0(directory,key,entry,bounds,limits,true,expectedSamples);}
    public static Path file(Path directory,String key){
        if(!key.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("SHA256 key required");
        return directory.resolve("slot-"+(Character.digit(key.charAt(0),16)%4)+".bin");
    }
    public static String identity(String... components) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            for(String value:components){byte[] part=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(part.length);out.write(part);}
            return HexFormat.of().formatHex(sha(bytes.toByteArray(),bytes.size()));
        }catch(IOException impossible){throw new AssertionError(impossible);}
    }
    /** Actual loaded code identity includes nested records/implementation classes, outside world identity. */
    public static String codeIdentity(Class<?>... roots) {
        try {
            var classes=new TreeMap<String,Class<?>>();for(var root:roots)collect(root,classes);
            var digest=MessageDigest.getInstance("SHA-256");
            var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest));
            for(var item:classes.entrySet()) {
                out.writeUTF(item.getKey());
                try(var input=item.getValue().getResourceAsStream("/"+item.getKey())) {
                    if(input==null)throw new IOException("Missing loaded class "+item.getKey());
                    var bytes=input.readAllBytes();out.writeInt(bytes.length);out.write(bytes);
                }
            }
            out.flush();return HexFormat.of().formatHex(digest.digest());
        }catch(IOException|NoSuchAlgorithmException failed){throw new IllegalStateException("Cannot identify cache code",failed);}
    }
    private static void collect(Class<?> type,Map<String,Class<?>> values){values.put(type.getName().replace('.','/')+".class",type);for(var child:type.getDeclaredClasses())collect(child,values);}
    private static byte[] sha(byte[] bytes,int length){try{var d=MessageDigest.getInstance("SHA-256");d.update(bytes,0,length);return d.digest();}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public static Read load(Path directory,String key,TerrainLodColumns.Bounds bounds,TerrainLodColumns.Parameters limits){
        return load0(directory,key,bounds,limits,false,0);
    }
    private static Read load0(Path directory,String key,TerrainLodColumns.Bounds bounds,TerrainLodColumns.Parameters limits,boolean scene,long expectedSamples){
        try {
            var file=file(directory,key);if(!Files.exists(file))return new Read(Optional.empty(),"MISS");
            long size=Files.size(file);if(size<112||size>MAX_BYTES)throw new IOException("File size outside cache bound");
            var bytes=Files.readAllBytes(file);int length=bytes.length-32;
            if(!MessageDigest.isEqual(sha(bytes,length),Arrays.copyOfRange(bytes,length,bytes.length)))throw new IOException("Checksum mismatch");
            var in=new DataInputStream(new ByteArrayInputStream(bytes,0,length));
            if(in.readInt()!=MAGIC||in.readInt()!=SCHEMA)throw new IOException("Cache schema mismatch");
            if(!key.equals(in.readUTF()))return new Read(Optional.empty(),"KEY_MISS");
            long sampled=in.readLong();int count=in.readInt();checkCount(count,limits.maxBoxes());
            var boxes=new ArrayList<TerrainLodColumns.Box>(count);for(int i=0;i<count;i++)boxes.add(readBox(in));
            int lightCount=in.readInt();checkCount(lightCount,limits.maxBoxes());
            var lights=new HashMap<TerrainLodColumns.Box,Light>();
            for(int i=0;i<lightCount;i++){var box=readBox(in);if(lights.put(box,new Light(in.readUnsignedByte(),in.readUnsignedByte()))!=null)throw new IOException("Duplicate light box");}
            if(in.available()!=0)throw new IOException("Trailing cache bytes");
            var entry=new Entry(new TerrainLodColumns.Result(boxes,sampled),lights);validate(entry,bounds,limits,scene,expectedSamples);
            return new Read(Optional.of(entry),"HIT");
        }catch(IOException|IllegalArgumentException failed){return new Read(Optional.empty(),"INVALID: "+failed.getMessage());}
    }
    private static void checkCount(int count,int cap)throws IOException{if(count<0||count>cap)throw new IOException("Cache record count exceeds bound");}
    private static TerrainLodColumns.Box readBox(DataInputStream in)throws IOException{
        long x=in.readLong(),y=in.readLong(),z=in.readLong(),xx=in.readLong(),yy=in.readLong(),zz=in.readLong();int material=in.readUnsignedByte();
        if(material>=TerrainMaterial.values().length)throw new IOException("Invalid cached material");
        return new TerrainLodColumns.Box(x,y,z,xx,yy,zz,TerrainMaterial.values()[material]);
    }
    private static void writeBox(DataOutputStream out,TerrainLodColumns.Box b)throws IOException{
        out.writeLong(b.minX());out.writeLong(b.minY());out.writeLong(b.minZ());out.writeLong(b.maxXExclusive());out.writeLong(b.maxYExclusive());out.writeLong(b.maxZExclusive());out.writeByte(b.material().ordinal());
    }
    private static void inside(TerrainLodColumns.Box b,TerrainLodColumns.Bounds bounds){
        if(b.minX()<bounds.minX()||b.minY()<bounds.minY()||b.minZ()<bounds.minZ()||b.maxXExclusive()>(long)bounds.maxXInclusive()+1||b.maxYExclusive()>(long)bounds.maxYInclusive()+1||b.maxZExclusive()>(long)bounds.maxZInclusive()+1)
            throw new IllegalArgumentException("Cached box outside source bounds");
    }
    private static void validate(Entry entry,TerrainLodColumns.Bounds bounds,TerrainLodColumns.Parameters limits,boolean scene,long expectedSamples){
        int step=limits.horizontalStep();long nx=((long)bounds.maxXInclusive()+1-bounds.minX()+step-1)/step,nz=((long)bounds.maxZInclusive()+1-bounds.minZ()+step-1)/step,height=(long)bounds.maxYInclusive()+1-bounds.minY();
        long cap=limits.maxSampleVoxels();if(height>cap||nx>cap/height||nz>cap/(nx*height)
                ||entry.base().sampledVoxels()!=(scene?expectedSamples:nx*nz*height)||entry.base().sampledVoxels()<1||entry.base().sampledVoxels()>cap)
            throw new IllegalArgumentException("Cached sample coverage outside budget");
        if(entry.base().boxes().size()>limits.maxBoxes()||entry.lights().size()>limits.maxBoxes())throw new IllegalArgumentException("Cached box budget exceeded");
        TerrainLodColumns.Box previous=null;
        var unique=scene?new HashSet<TerrainLodColumns.Box>():null;
        for(var box:entry.base().boxes()) {
            inside(box,bounds);
            if(scene){if(!unique.add(box))throw new IllegalArgumentException("Duplicate final scene box");continue;}
            if(Math.floorMod(box.minX()-bounds.minX(),step)!=0||Math.floorMod(box.minZ()-bounds.minZ(),step)!=0
                    ||box.maxXExclusive()!=Math.min((long)bounds.maxXInclusive()+1,box.minX()+step)||box.maxZExclusive()!=Math.min((long)bounds.maxZInclusive()+1,box.minZ()+step))
                throw new IllegalArgumentException("Cached base tile alignment differs");
            if(previous!=null&&(box.minX()<previous.minX()||box.minX()==previous.minX()&&(box.minZ()<previous.minZ()||box.minZ()==previous.minZ()&&box.minY()<previous.maxYExclusive())))
                throw new IllegalArgumentException("Cached base order/overlap differs");
            previous=box;
        }
        for(var box:entry.lights().keySet())inside(box,bounds);
        if(scene&&!entry.lights().keySet().equals(unique))throw new IllegalArgumentException("Final raw light coverage differs from geometry");
    }
    public static void save(Path directory,String key,Entry entry,TerrainLodColumns.Bounds bounds,TerrainLodColumns.Parameters limits) throws IOException {
        save0(directory,key,entry,bounds,limits,false,0);
    }
    private static void save0(Path directory,String key,Entry entry,TerrainLodColumns.Bounds bounds,TerrainLodColumns.Parameters limits,boolean scene,long expectedSamples) throws IOException {
        validate(entry,bounds,limits,scene,expectedSamples);var target=file(directory,key);
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);out.writeInt(SCHEMA);out.writeUTF(key);out.writeLong(entry.base().sampledVoxels());out.writeInt(entry.base().boxes().size());
        for(var box:entry.base().boxes())writeBox(out,box);
        out.writeInt(entry.lights().size());for(var item:entry.lights().entrySet()){writeBox(out,item.getKey());out.writeByte(item.getValue().sky());out.writeByte(item.getValue().block());}
        out.flush();var payload=bytes.toByteArray();if((long)payload.length+32>MAX_BYTES)throw new IOException("Cache file exceeds64MiB");
        Files.createDirectories(directory);var temporary=Files.createTempFile(directory,"pending-",".tmp");
        try {
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){
                var body=java.nio.ByteBuffer.wrap(payload);while(body.hasRemaining())channel.write(body);
                var checksum=java.nio.ByteBuffer.wrap(sha(payload,payload.length));while(checksum.hasRemaining())channel.write(checksum);channel.force(true);
            }
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temporary);}
    }
}
