package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class T3TileCodecOracleTest {
    @TempDir Path directory;
    static final String ID="e".repeat(64);
    static Map<TerrainTileDiskCache.Key,List<TerrainLodColumns.Box>> denseTiles() {
        var key=new TerrainTileDiskCache.Key(-16,-64,-32,-1,-1,-17,1);
        var boxes=new ArrayList<TerrainLodColumns.Box>();
        for(int x=-16;x<0;x++)for(int z=-32;z<-16;z++)for(int y=-64;y<0;y+=4)
            boxes.add(new TerrainLodColumns.Box(x,y,z,x+1,y+4,z+1,TerrainMaterial.values()[1+Math.floorMod(y/4,4)]));
        return Map.of(key,List.copyOf(boxes));
    }
    static Map<T3ReferenceTerrainTileDiskCache.Key,List<TerrainLodColumns.Box>> oldKeys(Map<TerrainTileDiskCache.Key,List<TerrainLodColumns.Box>> input) {
        var result=new LinkedHashMap<T3ReferenceTerrainTileDiskCache.Key,List<TerrainLodColumns.Box>>();
        for(var e:input.entrySet()){var k=e.getKey();result.put(new T3ReferenceTerrainTileDiskCache.Key(k.x(),k.y(),k.z(),k.endX(),k.endY(),k.endZ(),k.step()),e.getValue());}
        return result;
    }
    static Map<TerrainTileDiskCache.Key,List<TerrainLodColumns.Box>> newKeys(Map<T3ReferenceTerrainTileDiskCache.Key,List<TerrainLodColumns.Box>> input) {
        var result=new LinkedHashMap<TerrainTileDiskCache.Key,List<TerrainLodColumns.Box>>();
        for(var e:input.entrySet()){var k=e.getKey();result.put(new TerrainTileDiskCache.Key(k.x(),k.y(),k.z(),k.endX(),k.endY(),k.endZ(),k.step()),e.getValue());}
        return result;
    }
    private void compareReads(String identity) {
        var a=TerrainTileDiskCache.load(directory,identity);var b=T3ReferenceTerrainTileDiskCache.load(directory,identity);
        assertEquals(b.status(),a.status());assertEquals(newKeys(b.tiles()),a.tiles());
        assertEquals(new ArrayList<>(newKeys(b.tiles()).keySet()),new ArrayList<>(a.tiles().keySet()),"Disk insertion order");
    }
    @Test void randomizedWritersProduceIdenticalBytesAndBothReadersPreserveExactOrder()throws Exception {
        var random=new Random(0x74696c65);
        for(int round=0;round<45;round++) {
            var tiles=new LinkedHashMap<TerrainTileDiskCache.Key,List<TerrainLodColumns.Box>>();
            for(int i=0,n=1+random.nextInt(15);i<n;i++) {
                int x=i%5==0?Integer.MIN_VALUE:i%5==1?Integer.MAX_VALUE-15:(random.nextInt(65)-32)*16;
                int z=(i-8)*16,y=(random.nextInt(20)-18)*64,step=1<<random.nextInt(5);
                var key=new TerrainTileDiskCache.Key(x,y,z,x+15,y+63,z+15,step);var boxes=new ArrayList<TerrainLodColumns.Box>();
                for(long xx=x;xx<=(long)x+15;xx+=step)for(long zz=z;zz<=(long)z+15;zz+=step)
                    if(random.nextBoolean())boxes.add(new TerrainLodColumns.Box(xx,y,zz,Math.min((long)x+16,xx+step),y+1+random.nextInt(64),Math.min((long)z+16,zz+step),TerrainMaterial.values()[1+random.nextInt(4)]));
                tiles.put(key,List.copyOf(boxes));
            }
            String identity=TerrainLodDiskCache.identity(ID,Integer.toString(round));
            Path old=directory.resolve("old");T3ReferenceTerrainTileDiskCache.save(old,identity,oldKeys(tiles));
            TerrainTileDiskCache.save(directory,identity,tiles);
            assertArrayEquals(Files.readAllBytes(TerrainLodDiskCache.file(old,identity)),Files.readAllBytes(TerrainLodDiskCache.file(directory,identity)));
            compareReads(identity);assertEquals(tiles,TerrainTileDiskCache.load(old,identity).tiles());
        }
    }
    private void writeBody(byte[] body)throws Exception {
        byte[] all=Arrays.copyOf(body,body.length+32);System.arraycopy(MessageDigest.getInstance("SHA-256").digest(body),0,all,body.length,32);
        Files.write(TerrainLodDiskCache.file(directory,ID),all);
    }
    @Test void rechecksummedTruncationsMutationsAndTrailingBytesKeepLegacyStatusAndValidation()throws Exception {
        TerrainTileDiskCache.save(directory,ID,denseTiles());byte[] valid=Files.readAllBytes(TerrainLodDiskCache.file(directory,ID));
        byte[] body=Arrays.copyOf(valid,valid.length-32);var random=new Random(0x454f46);
        for(int cut=0;cut<130;cut++){writeBody(Arrays.copyOf(body,cut));compareReads(ID);}
        for(int i=0;i<160;i++){byte[] changed=body.clone();int at=i<32?i:random.nextInt(changed.length);changed[at]^=(byte)(1+random.nextInt(255));writeBody(changed);compareReads(ID);}
        writeBody(Arrays.copyOf(body,body.length+1));compareReads(ID);
        Files.write(TerrainLodDiskCache.file(directory,ID),valid);valid[valid.length-1]^=1;
        Files.write(TerrainLodDiskCache.file(directory,ID),valid);compareReads(ID);
        Files.delete(TerrainLodDiskCache.file(directory,ID));compareReads(ID);
    }
    @Test void malformedModifiedUtfAndDuplicateKeysKeepLegacyErrors()throws Exception {
        TerrainTileDiskCache.save(directory,ID,denseTiles());var bytes=Files.readAllBytes(TerrainLodDiskCache.file(directory,ID));var body=Arrays.copyOf(bytes,bytes.length-32);
        body[10]=(byte)0xff;writeBody(body);compareReads(ID);
        // A valid alternate header identity must remain KEY_MISS even when its body is unusable.
        body[10]=(byte)'f';writeBody(body);compareReads(ID);assertEquals("KEY_MISS",TerrainTileDiskCache.load(directory,ID).status());
        var empty=Map.of(new TerrainTileDiskCache.Key(-16,-64,-16,-1,-1,-1,1),List.<TerrainLodColumns.Box>of());
        TerrainTileDiskCache.save(directory,ID,empty);bytes=Files.readAllBytes(TerrainLodDiskCache.file(directory,ID));body=Arrays.copyOf(bytes,bytes.length-32);
        int countOffset=8+2+64;body[countOffset+3]=2;byte[] duplicate=Arrays.copyOf(body,body.length+32);System.arraycopy(body,countOffset+4,duplicate,body.length,32);
        writeBody(duplicate);compareReads(ID);assertEquals("INVALID: Duplicate tile",TerrainTileDiskCache.load(directory,ID).status());
    }
    @FunctionalInterface interface Work {void run()throws Exception;}
    static long allocated(Work work,int repetitions)throws Exception {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId(),start=bean.getThreadAllocatedBytes(thread);
        for(int i=0;i<repetitions;i++)work.run();return (bean.getThreadAllocatedBytes(thread)-start)/repetitions;
    }
    @Test void denseDecodeAllocatesLessThanOldPerBoxEnumCloning()throws Exception {
        TerrainTileDiskCache.save(directory,ID,denseTiles());
        for(int i=0;i<12;i++){TerrainTileDiskCache.load(directory,ID);T3ReferenceTerrainTileDiskCache.load(directory,ID);}
        long old=allocated(()->T3ReferenceTerrainTileDiskCache.load(directory,ID),12);
        long actual=allocated(()->TerrainTileDiskCache.load(directory,ID),12);
        assertTrue(actual<old*.9,"Dense decoder allocation must fall >10%: old="+old+" actual="+actual);
    }
    @Test void denseWriterAvoidsGrowingAndCopyingTheWholeBody()throws Exception {
        var tiles=denseTiles();var oldTiles=oldKeys(tiles);var oldDirectory=directory.resolve("old");
        for(int i=0;i<5;i++){TerrainTileDiskCache.save(directory,ID,tiles);T3ReferenceTerrainTileDiskCache.save(oldDirectory,ID,oldTiles);}
        long old=allocated(()->T3ReferenceTerrainTileDiskCache.save(oldDirectory,ID,oldTiles),8);
        long actual=allocated(()->TerrainTileDiskCache.save(directory,ID,tiles),8);
        assertTrue(actual<old*.85,"Dense writer allocation must fall >15%: old="+old+" actual="+actual);
    }
}
