package dev.madeinabyss.kernel.cube;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProceduralCubeDigestCacheTest {
    @TempDir Path directory;
    private static final String KEY="a".repeat(64);
    private static final CubeKey CUBE=new CubeKey(-17,-42,31);
    @Test void proceduralDigestCacheApiIsAvailable() {
        assertDoesNotThrow(() -> Class.forName("dev.madeinabyss.kernel.cube.ProceduralCubeDigestCache"));
    }
    public static void main(String[] args) {
        new ProceduralCubeDigestCacheTest().proceduralDigestCacheApiIsAvailable();
    }
    @Test void roundTripKeepsNegativeCoordinatesAndIndependentDigestOwnership() throws Exception {
        byte[] digest=new byte[32];digest[0]=19;
        var input=new HashMap<CubeKey,byte[]>();input.put(CUBE,digest);
        var snapshot=new ProceduralCubeDigestCache.Read(Optional.of(input),"HIT");
        digest[0]=0;input.clear();assertEquals(19,snapshot.entry().orElseThrow().get(CUBE)[0]);
        snapshot.entry().orElseThrow().get(CUBE)[0]=0;assertEquals(19,snapshot.entry().orElseThrow().get(CUBE)[0]);
        assertThrows(UnsupportedOperationException.class,()->snapshot.entry().orElseThrow().clear());
        ProceduralCubeDigestCache.write(directory,KEY,snapshot.entry().orElseThrow());
        var read=ProceduralCubeDigestCache.read(directory,KEY);assertEquals("HIT",read.status());
        assertArrayEquals(snapshot.entry().orElseThrow().get(CUBE),read.entry().orElseThrow().get(CUBE));
    }
    @Test void digestMatchesIndependentBigEndianOracleAndDetectsFirstLastVoxelChanges() throws Exception {
        int[] states=new int[4096];states[0]=0x01020304;states[4095]=173;
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){for(int state:states)out.writeInt(state);}
        byte[] oracle=MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        assertArrayEquals(oracle,ProceduralCubeDigestCache.digest(states));
        states[0]++;assertFalse(Arrays.equals(oracle,ProceduralCubeDigestCache.digest(states)));states[0]--;
        states[4095]++;assertFalse(Arrays.equals(oracle,ProceduralCubeDigestCache.digest(states)));states[4095]--;
        assertArrayEquals(oracle,ProceduralCubeDigestCache.digest(states));
        assertThrows(IllegalArgumentException.class,()->ProceduralCubeDigestCache.digest(new int[4095]));
        states[7]=-1;assertThrows(IllegalArgumentException.class,()->ProceduralCubeDigestCache.digest(states));
    }
    @Test void namespaceMismatchAndEightSlotsKeepUnrelatedFiles() throws Exception {
        assertEquals("MISS",ProceduralCubeDigestCache.read(directory,KEY).status());
        Files.writeString(directory.resolve("keep.txt"),"keep");
        ProceduralCubeDigestCache.write(directory,KEY,Map.of(CUBE,new byte[32]));
        // Simulates a changed profile/code/palette with the same bounded disk slot.
        assertEquals("KEY_MISS",ProceduralCubeDigestCache.read(directory,"b".repeat(63)+"a").status());
        for(int i=0;i<32;i++)ProceduralCubeDigestCache.write(directory,String.format("%064x",i),Map.of(CUBE,new byte[32]));
        try(var files=Files.list(directory)){assertEquals(8,files.filter(p->p.toString().endsWith(".pcd")).count());}
        assertEquals("keep",Files.readString(directory.resolve("keep.txt")));
        assertThrows(IllegalArgumentException.class,()->ProceduralCubeDigestCache.read(directory,"../escape"));
    }
    @Test void entryLimitsAndGlobalCubeBoundariesAreEnforcedBeforeReplacingFile() throws Exception {
        var entries=new HashMap<CubeKey,byte[]>();
        for(int i=0;i<8192;i++)entries.put(new CubeKey(i,-134217728,134217727),new byte[32]);
        ProceduralCubeDigestCache.write(directory,KEY,entries);
        assertEquals(8192,ProceduralCubeDigestCache.read(directory,KEY).entry().orElseThrow().size());
        byte[] before=Files.readAllBytes(file());entries.put(CUBE,new byte[32]);
        assertThrows(IllegalArgumentException.class,()->ProceduralCubeDigestCache.write(directory,KEY,entries));
        assertArrayEquals(before,Files.readAllBytes(file()));
        for(var cube:List.of(new CubeKey(-134217729,0,0),new CubeKey(0,134217728,0),new CubeKey(0,0,Integer.MIN_VALUE)))
            assertThrows(IllegalArgumentException.class,()->ProceduralCubeDigestCache.write(directory,KEY,Map.of(cube,new byte[32])));
        assertThrows(IllegalArgumentException.class,()->ProceduralCubeDigestCache.write(directory,KEY,Map.of(CUBE,new byte[31])));
    }
    @Test void signedAxesRemainDistinctAndWriterIsDeterministic() throws Exception {
        var entries=new LinkedHashMap<CubeKey,byte[]>();
        for(var cube:List.of(new CubeKey(-1,0,0),new CubeKey(0,-1,0),new CubeKey(0,0,-1),new CubeKey(0,0,0))){byte[] d=new byte[32];d[0]=(byte)entries.size();entries.put(cube,d);}
        ProceduralCubeDigestCache.write(directory,KEY,entries);byte[] before=Files.readAllBytes(file());
        var reverse=new LinkedHashMap<CubeKey,byte[]>();var keys=new ArrayList<>(entries.keySet());Collections.reverse(keys);for(var key:keys)reverse.put(key,entries.get(key));
        ProceduralCubeDigestCache.write(directory,KEY,reverse);assertArrayEquals(before,Files.readAllBytes(file()));
        var restored=ProceduralCubeDigestCache.read(directory,KEY).entry().orElseThrow();assertEquals(4,restored.size());
        entries.forEach((key,value)->assertArrayEquals(value,restored.get(key)));
    }
    @Test void corruptTruncatedOversizeAndChecksummedInvalidRecordsAreMisses() throws Exception {
        ProceduralCubeDigestCache.write(directory,KEY,Map.of(CUBE,new byte[32],new CubeKey(1,-2,-3),new byte[32]));
        byte[] original=Files.readAllBytes(file());
        Files.write(file(),Arrays.copyOf(original,original.length-1));assertMiss();
        byte[] bad=original.clone();bad[bad.length-1]^=1;Files.write(file(),bad);assertMiss();
        // Independent wire offsets: 8-byte version header +64 ASCII key +4 count;44 bytes/record.
        bad=original.clone();System.arraycopy(bad,76,bad,120,12);repairAndWrite(bad);assertMiss();
        bad=original.clone();ByteBuffer.wrap(bad).putInt(72,8193);repairAndWrite(bad);assertMiss();
        bad=original.clone();ByteBuffer.wrap(bad).putInt(76,Integer.MIN_VALUE);repairAndWrite(bad);assertMiss();
        bad=original.clone();ByteBuffer.wrap(bad).putInt(4,99);repairAndWrite(bad);assertMiss();
        bad=Arrays.copyOf(original,original.length+1);repairAndWrite(bad);assertMiss();
        Files.write(file(),new byte[1024*1024+1]);assertEquals("OVERSIZE",ProceduralCubeDigestCache.read(directory,KEY).status());
    }
    @Test void handwrittenEmptyEnvelopeLoadsWithoutWriter() throws Exception {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(0x4d495044);out.writeInt(1);out.write(KEY.getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.writeInt(0);}
        byte[] raw=bytes.toByteArray();bytes.write(MessageDigest.getInstance("SHA-256").digest(raw));Files.write(file(),bytes.toByteArray());
        var read=ProceduralCubeDigestCache.read(directory,KEY);assertEquals("HIT",read.status());assertTrue(read.entry().orElseThrow().isEmpty());
    }
    private Path file(){return directory.resolve("slot-2.pcd");}
    private void assertMiss(){assertTrue(ProceduralCubeDigestCache.read(directory,KEY).entry().isEmpty());}
    private void repairAndWrite(byte[] bytes)throws Exception {
        byte[] sha=MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes,bytes.length-32));
        System.arraycopy(sha,0,bytes,bytes.length-32,32);Files.write(file(),bytes);
    }
}
