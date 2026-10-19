package dev.madeinabyss.kernel.lod;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Partial scalar dictionary, never a render scene or a substitute for source geometry. */
public final class ExactBoxLightDiskCache {
    public record Scope(String sourceIdentity,String lightingCode,TerrainLodColumns.Bounds bounds,int maxBoxes) {
        public Scope {
            Objects.requireNonNull(sourceIdentity);Objects.requireNonNull(lightingCode);Objects.requireNonNull(bounds);
            if(sourceIdentity.isEmpty()||lightingCode.isEmpty()||maxBoxes<1||maxBoxes>400_000)
                throw new IllegalArgumentException("Complete bounded scalar authority required");
        }
        public String key(){return TerrainLodDiskCache.identity("abyss-exact-box-scalar-dictionary-v1",sourceIdentity,lightingCode,bounds.toString());}
    }
    public record Read(Map<TerrainLodColumns.Box,TerrainLodDiskCache.Light> values,String status) {
        public Read {values=Map.copyOf(values);}
    }
    private ExactBoxLightDiskCache(){}
    private static final int MAGIC=0x4d494c44,SCHEMA=1,HEADER=102,ENTRY=51,FOOTER=32;
    private static final long MAX_BYTES=64L*1024*1024;
    private static final dev.madeinabyss.kernel.terrain.TerrainMaterial[] MATERIALS=dev.madeinabyss.kernel.terrain.TerrainMaterial.values();
    public static Path file(Path directory,Scope scope){return directory.resolve("scalar-slot-"+(Character.digit(scope.key().charAt(0),16)%4)+".bin");}
    private static byte[] digest(byte[] bytes,int length){try{var sha=MessageDigest.getInstance("SHA-256");sha.update(bytes,0,length);return sha.digest();}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void inside(TerrainLodColumns.Box box,TerrainLodColumns.Bounds b){
        if(box.minX()<b.minX()||box.minY()<b.minY()||box.minZ()<b.minZ()||box.maxXExclusive()>(long)b.maxXInclusive()+1
                ||box.maxYExclusive()>(long)b.maxYInclusive()+1||box.maxZExclusive()>(long)b.maxZInclusive()+1
                ||box.material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.AIR)throw new IllegalArgumentException("Scalar key outside authoritative geometry domain");
    }
    private static int[] bounds(TerrainLodColumns.Bounds b){return new int[]{b.minX(),b.minY(),b.minZ(),b.maxXInclusive(),b.maxYInclusive(),b.maxZInclusive()};}
    public static void save(Path directory,Scope scope,Map<TerrainLodColumns.Box,TerrainLodDiskCache.Light> values)throws IOException {
        if(values.size()>scope.maxBoxes())throw new IllegalArgumentException("Scalar dictionary exceeds its entry budget");
        for(var e:values.entrySet()){inside(e.getKey(),scope.bounds());Objects.requireNonNull(e.getValue());}
        var bytes=new ByteArrayOutputStream(Math.addExact(HEADER,Math.multiplyExact(ENTRY,values.size())));
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeInt(SCHEMA);out.writeUTF(scope.key());for(int v:bounds(scope.bounds()))out.writeInt(v);out.writeInt(values.size());
            for(var e:values.entrySet()){
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Scalar persistence canceled");
                var b=e.getKey();out.writeLong(b.minX());out.writeLong(b.minY());out.writeLong(b.minZ());out.writeLong(b.maxXExclusive());out.writeLong(b.maxYExclusive());out.writeLong(b.maxZExclusive());
                out.writeByte(b.material().ordinal());out.writeByte(e.getValue().sky());out.writeByte(e.getValue().block());
            }
        }
        byte[] payload=bytes.toByteArray(),sha=digest(payload,payload.length);
        Files.createDirectories(directory);var target=file(directory,scope);var temporary=directory.resolve(target.getFileName()+"."+UUID.randomUUID()+".tmp");
        try{
            try(var out=Files.newOutputStream(temporary)){out.write(payload);out.write(sha);}
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Scalar publication canceled");
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    public static Read load(Path directory,Scope scope){
        try {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Scalar load canceled");
            var target=file(directory,scope);if(!Files.exists(target))return new Read(Map.of(),"MISS");
            long maximum=Math.min(MAX_BYTES,(long)HEADER+(long)ENTRY*scope.maxBoxes()+FOOTER),size=Files.size(target);
            if(size<HEADER+FOOTER||size>maximum)throw new IOException("Scalar file size outside budget");
            byte[] bytes;
            try(var input=Files.newInputStream(target)){bytes=input.readNBytes(Math.toIntExact(maximum+1));}
            if(bytes.length!=size||bytes.length>maximum)throw new IOException("Scalar file changed during load");
            int length=bytes.length-FOOTER;
            if(!MessageDigest.isEqual(digest(bytes,length),Arrays.copyOfRange(bytes,length,bytes.length)))throw new IOException("Scalar checksum mismatch");
            try(var in=new DataInputStream(new ByteArrayInputStream(bytes,0,length))){
                if(in.readInt()!=MAGIC||in.readInt()!=SCHEMA)throw new IOException("Scalar schema mismatch");
                if(!scope.key().equals(in.readUTF()))return new Read(Map.of(),"KEY_MISS");
                for(int expected:bounds(scope.bounds()))if(in.readInt()!=expected)throw new IOException("Scalar bounds mismatch");
                int count=in.readInt();
                if(count<0||count>scope.maxBoxes()||length!=(long)HEADER+(long)ENTRY*count)throw new IOException("Scalar entry count mismatch");
                var values=new HashMap<TerrainLodColumns.Box,TerrainLodDiskCache.Light>();
                for(int i=0;i<count;i++){
                    if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Scalar load canceled");
                    long minX=in.readLong(),minY=in.readLong(),minZ=in.readLong(),maxX=in.readLong(),maxY=in.readLong(),maxZ=in.readLong();
                    int material=in.readUnsignedByte(),sky=in.readUnsignedByte(),block=in.readUnsignedByte();
                    if(material>=MATERIALS.length)throw new IOException("Unknown scalar material");
                    var box=new TerrainLodColumns.Box(minX,minY,minZ,maxX,maxY,maxZ,MATERIALS[material]);inside(box,scope.bounds());
                    if(values.put(box,new TerrainLodDiskCache.Light(sky,block))!=null)throw new IOException("Duplicate scalar key");
                }
                if(in.read()!=-1)throw new IOException("Trailing scalar bytes");
                return new Read(values,"HIT");
            }
        }catch(IOException|IllegalArgumentException refused){return new Read(Map.of(),"INVALID:"+refused.getClass().getSimpleName());}
    }
}
