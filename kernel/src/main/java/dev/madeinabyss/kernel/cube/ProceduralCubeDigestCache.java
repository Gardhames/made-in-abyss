package dev.madeinabyss.kernel.cube;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Optional derived procedural baselines. Never a world save or authority for sampled states.
 * Callers include profile, generator code and the numeric registry palette in the explicit key,
 * and invoke disk operations only from their derived-data worker. */
public final class ProceduralCubeDigestCache {
    public static final int MAX_ENTRIES=8192, MAX_BYTES=1024*1024;
    private static final int MAGIC=0x4d495044, VERSION=1, DIGEST_BYTES=32;
    private static final int MIN_CUBE=-134217728, MAX_CUBE=134217727;
    private ProceduralCubeDigestCache() {}

    /** Deep copies on both input and output prevent mutated digests poisoning later reads. */
    public record Read(Optional<Map<CubeKey,byte[]>> entry,String status) {
        public Read {
            Objects.requireNonNull(entry);Objects.requireNonNull(status);
            entry=entry.map(ProceduralCubeDigestCache::snapshot);
        }
        @Override public Optional<Map<CubeKey,byte[]>> entry() {
            return entry.map(ProceduralCubeDigestCache::snapshot);
        }
    }

    public static Read read(Path directory,String key) {
        validateKey(key);Objects.requireNonNull(directory);
        try {
            byte[] bytes;
            try(var in=Files.newInputStream(file(directory,key))) {bytes=in.readNBytes(MAX_BYTES+1);}
            if(bytes.length>MAX_BYTES)return miss("OVERSIZE");
            if(bytes.length<76+DIGEST_BYTES)return miss("INVALID: truncated header");
            int payloadLength=bytes.length-DIGEST_BYTES;
            if(!MessageDigest.isEqual(sha().digest(Arrays.copyOf(bytes,payloadLength)),
                    Arrays.copyOfRange(bytes,payloadLength,bytes.length)))return miss("INVALID: checksum");
            try(var in=new DataInputStream(new ByteArrayInputStream(bytes,0,payloadLength))) {
                if(in.readInt()!=MAGIC||in.readInt()!=VERSION)return miss("INVALID: format");
                byte[] recordedKey=new byte[64];in.readFully(recordedKey);
                if(!key.equals(new String(recordedKey,StandardCharsets.US_ASCII)))return miss("KEY_MISS");
                int count=in.readInt();if(count<0||count>MAX_ENTRIES)return miss("INVALID: count");
                if(payloadLength!=76+count*44)return miss("INVALID: length");
                var entries=new HashMap<CubeKey,byte[]>();
                for(int i=0;i<count;i++) {
                    var cube=new CubeKey(in.readInt(),in.readInt(),in.readInt());validateCube(cube);
                    byte[] value=new byte[DIGEST_BYTES];in.readFully(value);
                    if(entries.put(cube,value)!=null)return miss("INVALID: duplicate cube");
                }
                return new Read(Optional.of(entries),"HIT");
            }
        }catch(NoSuchFileException absent){return miss("MISS");}
        catch(IOException|IllegalArgumentException invalid){return miss("INVALID: "+invalid.getMessage());}
    }

    public static void write(Path directory,String key,Map<CubeKey,byte[]> entries) throws IOException {
        validateKey(key);Objects.requireNonNull(directory);
        var copy=snapshot(entries);
        var ordered=new ArrayList<>(copy.keySet());
        ordered.sort(Comparator.comparingInt(CubeKey::x).thenComparingInt(CubeKey::y).thenComparingInt(CubeKey::z));
        var bytes=new ByteArrayOutputStream(76+44*copy.size());
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.write(key.getBytes(StandardCharsets.US_ASCII));out.writeInt(copy.size());
            for(var cube:ordered){out.writeInt(cube.x());out.writeInt(cube.y());out.writeInt(cube.z());out.write(copy.get(cube));}
        }
        byte[] payload=bytes.toByteArray(),checksum=sha().digest(payload);
        if(payload.length+checksum.length>MAX_BYTES)throw new IOException("Procedural digest cache exceeds byte bound");
        Files.createDirectories(directory);
        var temporary=Files.createTempFile(directory,".procedural-digest-",".tmp");
        try {
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                for(byte[] body:new byte[][]{payload,checksum}){var buffer=ByteBuffer.wrap(body);while(buffer.hasRemaining())channel.write(buffer);}
                channel.force(true);
            }
            Files.move(temporary,file(directory,key),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }

    /** Hash exactly 4096 numeric state IDs in their existing Y/Z/X array order as BE ints. */
    public static byte[] digest(int[] states) {
        Objects.requireNonNull(states);
        if(states.length!=4096)throw new IllegalArgumentException("Expected 4096 states");
        var hash=sha();var bytes=new byte[4096*Integer.BYTES];var buffer=ByteBuffer.wrap(bytes);
        for(int state:states){if(state<0)throw new IllegalArgumentException("Negative state ID");buffer.putInt(state);}
        return hash.digest(bytes);
    }
    private static Map<CubeKey,byte[]> snapshot(Map<CubeKey,byte[]> entries) {
        Objects.requireNonNull(entries);
        if(entries.size()>MAX_ENTRIES)throw new IllegalArgumentException("Procedural digest entry bound");
        var copy=new HashMap<CubeKey,byte[]>();
        for(var entry:entries.entrySet()) {
            validateCube(entry.getKey());byte[] digest=Objects.requireNonNull(entry.getValue());
            if(digest.length!=DIGEST_BYTES)throw new IllegalArgumentException("Expected SHA-256 digest");
            if(copy.put(entry.getKey(),digest.clone())!=null)throw new IllegalArgumentException("Duplicate cube");
            if(copy.size()>MAX_ENTRIES)throw new IllegalArgumentException("Procedural digest entry bound");
        }
        return Collections.unmodifiableMap(copy);
    }
    private static void validateCube(CubeKey cube) {
        Objects.requireNonNull(cube);
        for(int coordinate:new int[]{cube.x(),cube.y(),cube.z()})
            if(coordinate<MIN_CUBE||coordinate>MAX_CUBE)throw new IllegalArgumentException("Cube outside signed global block domain");
    }
    private static void validateKey(String key) {
        if(key==null||!key.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Expected explicit SHA-256 key");
    }
    private static Path file(Path directory,String key) {
        return directory.resolve("slot-"+(Character.digit(key.charAt(63),16)&7)+".pcd");
    }
    private static Read miss(String status){return new Read(Optional.empty(),status);}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
