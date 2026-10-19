package dev.madeinabyss.kernel.light;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
class SkySourceDiskCacheTest {
    @TempDir Path directory;
    private static final String KEY="a".repeat(64);
    private SkySourceDiskCache.Entry fixture(){return new SkySourceDiskCache.Entry(-1286,320,
        List.of(new SkySourceDiskCache.Visibility(-17,-665,Integer.MIN_VALUE,.375)),
        List.of(new SkySourceDiskCache.Exposure(Integer.MAX_VALUE,-16,-666,new byte[]{14,8,0},-665)));}
    @Test void exactSignedRoundTripAndDefensiveSnapshotOwnership() throws Exception {
        byte[] a={15,7,0};var column=new SkySourceDiskCache.Exposure(-1,-16,-2,a,0);a[0]=0;column.attenuation()[1]=0;
        assertArrayEquals(new byte[]{15,7,0},column.attenuation());
        var e=fixture();SkySourceDiskCache.write(directory,KEY,e);var read=SkySourceDiskCache.read(directory,KEY,-1286,320);
        assertEquals("HIT",read.status());assertEquals(e,read.entry().orElseThrow());
        assertThrows(UnsupportedOperationException.class,()->e.visibility().clear());
        assertTrue(SkySourceDiskCache.read(directory,KEY,-1285,320).entry().isEmpty());
    }
    @Test void fixedEightSlotsAndKeyValidationNeverTouchOtherFiles() throws Exception {
        Files.createDirectories(directory);var unrelated=directory.resolve("keep.txt");Files.writeString(unrelated,"keep");
        for(int i=0;i<32;i++)SkySourceDiskCache.write(directory,String.format("%064x",i),fixture());
        try(var files=Files.list(directory)){assertTrue(files.filter(p->p.getFileName().toString().endsWith(".sky.gz")).count()<=8);}
        assertEquals("keep",Files.readString(unrelated));assertEquals("KEY_MISS",SkySourceDiskCache.read(directory,"f".repeat(64),-1286,320).status());
        assertThrows(IllegalArgumentException.class,()->SkySourceDiskCache.write(directory,"../escape",fixture()));
    }
    @Test void corruptTruncatedChecksumTamperAndOversizeCountsAreSafeMisses() throws Exception {
        SkySourceDiskCache.write(directory,KEY,fixture());Path file=cacheFile();byte[] original=Files.readAllBytes(file);
        Files.write(file,Arrays.copyOf(original,original.length/2));assertTrue(SkySourceDiskCache.read(directory,KEY,-1286,320).entry().isEmpty());
        Files.write(file,original);byte[] raw=unzip(original);raw[raw.length-1]^=1;Files.write(file,zip(raw));assertTrue(SkySourceDiskCache.read(directory,KEY,-1286,320).entry().isEmpty());
        // Header: magic/version +64 ASCII identity +min/max +visibility/exposure counts.
        raw=unzip(original);java.nio.ByteBuffer.wrap(raw).putInt(80,Integer.MAX_VALUE);Files.write(file,zip(raw));
        assertTrue(SkySourceDiskCache.read(directory,KEY,-1286,320).entry().isEmpty());
        try(var channel=Files.newByteChannel(file,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(32L*1024*1024);channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        assertTrue(SkySourceDiskCache.read(directory,KEY,-1286,320).entry().isEmpty());
    }
    @Test void illegalFractionsDuplicateCoordinatesAndExposurePayloadsRefuseBeforeWrite() {
        assertThrows(IllegalArgumentException.class,()->new SkySourceDiskCache.Visibility(0,0,0,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->new SkySourceDiskCache.Exposure(0,0,0,new byte[]{16},0));
        assertThrows(IllegalArgumentException.class,()->new SkySourceDiskCache.Exposure(0,0,0,new byte[16],0));
        var v=new SkySourceDiskCache.Visibility(1,0,-1,1);
        assertThrows(IllegalArgumentException.class,()->new SkySourceDiskCache.Entry(-1,2,List.of(v,v),List.of()));
        var c=new SkySourceDiskCache.Exposure(0,0,0,new byte[0],1);
        assertThrows(IllegalArgumentException.class,()->new SkySourceDiskCache.Entry(-1,2,List.of(),List.of(c,c)));
        assertThrows(IllegalArgumentException.class,()->new SkySourceDiskCache.Entry(-1,2,List.of(new SkySourceDiskCache.Visibility(0,2,0,1)),List.of()));
        var extreme=new SkySourceDiskCache.Entry(Integer.MIN_VALUE,Integer.MIN_VALUE+16,List.of(),
            List.of(new SkySourceDiskCache.Exposure(0,0,Integer.MIN_VALUE,new byte[0],Integer.MIN_VALUE+16)));
        assertNotNull(extreme);
    }
    @Test void checksummedDuplicatePayloadAndExtraExpandedDataAreRejected() throws Exception {
        var e=new SkySourceDiskCache.Entry(-10,10,List.of(
            new SkySourceDiskCache.Visibility(-16,-1,-17,.25),
            new SkySourceDiskCache.Visibility(16,1,17,.75)),List.of());
        SkySourceDiskCache.write(directory,KEY,e);Path file=cacheFile();byte[] original=Files.readAllBytes(file);
        byte[] raw=unzip(original);
        // Give the second record the same XYZ as the first and repair the checksum:
        // integrity alone must not make a duplicate query acceptable.
        System.arraycopy(raw,88,raw,108,12);
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(raw,raw.length-32));
        System.arraycopy(hash,0,raw,raw.length-32,32);Files.write(file,zip(raw));
        assertTrue(SkySourceDiskCache.read(directory,KEY,-10,10).entry().isEmpty());
        // A highly compressed oversized expansion after a valid snapshot is a safe miss.
        raw=unzip(original);
        try(var gz=new GZIPOutputStream(Files.newOutputStream(file))) {
            gz.write(raw);byte[] zeros=new byte[8192];
            for(int i=0;i<4097;i++)gz.write(zeros);
        }
        assertTrue(Files.size(file)<32L*1024*1024);
        assertTrue(SkySourceDiskCache.read(directory,KEY,-10,10).entry().isEmpty());
    }
    private Path cacheFile() throws IOException {try(var f=Files.list(directory)){return f.filter(p->p.getFileName().toString().endsWith(".sky.gz")).findFirst().orElseThrow();}}
    private static byte[] unzip(byte[] b)throws IOException {try(var in=new GZIPInputStream(new ByteArrayInputStream(b))){return in.readAllBytes();}}
    private static byte[] zip(byte[] b)throws IOException {var out=new ByteArrayOutputStream();try(var gz=new GZIPOutputStream(out)){gz.write(b);}return out.toByteArray();}
}
