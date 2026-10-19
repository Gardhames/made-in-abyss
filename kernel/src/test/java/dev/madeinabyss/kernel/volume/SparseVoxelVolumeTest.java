package dev.madeinabyss.kernel.volume;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.madeinabyss.kernel.volume.VoxelVolumeArchive.*;

class SparseVoxelVolumeTest {
    @TempDir Path root;
    static final Runnable CHECK=()->{};
    private final VoxelPalette palette=new VoxelPalette(List.of(
        new VoxelPalette.Entry("minecraft:air",0,0,true,false,true,TerrainMaterial.AIR),
        new VoxelPalette.Entry("minecraft:stone",15,0,false,false,false,TerrainMaterial.ROCK),
        new VoxelPalette.Entry("minecraft:water[level=0]",1,0,false,true,true,TerrainMaterial.WATER),
        new VoxelPalette.Entry("test:transparent",0,0,false,false,false,TerrainMaterial.SOIL)));
    private Authority authority(String source){return new Authority("fp","generation-sha","physical",source,"code");}
    private VoxelVolumeArchive archive(String source)throws IOException{return new VoxelVolumeArchive(root,authority(source),palette);}
    @Test void rawReusesTheOneCanonicalProducerAcrossStepsAndDiskReload()throws Exception {
        var calls=new AtomicInteger();var samples=new AtomicInteger();
        SparseVoxelVolume.Producer capture=(key,check)->{calls.incrementAndGet();int[] states=new int[4096];for(int i=0;i<4096;i++){samples.incrementAndGet();int x=key.x()*16+(i&15),y=key.y()*16+(i>>>8),z=key.z()*16+((i>>>4)&15);states[i]=Math.floorMod(x+y+z,4);}return palette.pack(states);};
        var volume=new SparseVoxelVolume(palette,archive("one"),capture,CHECK,1<<20);
        var bounds=new TerrainLodColumns.Bounds(-16,-16,-16,-1,-1,-1);
        var direct=(dev.madeinabyss.kernel.terrain.trail.TrailNetwork.SampleSource)(x,y,z)->new TerrainSample.Known(palette.entry(Math.floorMod((int)(x+y+z),4)).material());
        for(int step:new int[]{1,2,4,8}){
            var options=new TerrainLodColumns.Parameters(step,4096,10000);
            assertEquals(TerrainLodColumns.build(direct,bounds,options),TerrainLodColumns.build(volume.rawSource(CHECK),bounds,options));
        }
        assertEquals(1,calls.get());assertEquals(4096,samples.get());volume.checkpoint(CHECK);
        var reloaded=new SparseVoxelVolume(palette,archive("one"),(key,check)->{throw new AssertionError("A second generator sample is forbidden");},CHECK,1<<20);
        assertEquals(TerrainLodColumns.build(direct,bounds,new TerrainLodColumns.Parameters(1,4096,10000)),TerrainLodColumns.build(reloaded.rawSource(CHECK),bounds,new TerrainLodColumns.Parameters(1,4096,10000)));
        assertEquals(0L,reloaded.status().get("bricksBuilt"));assertEquals(1L,reloaded.status().get("bricksRestored"));
    }
    @Test void signedPagesAndBrickEdgesDoNotAlias()throws Exception {
        var volume=new SparseVoxelVolume(palette,archive("coords"),(key,check)->VoxelBrick.uniform(Math.floorMod(key.x()+key.y()*7+key.z()*17,4),palette.entry(Math.floorMod(key.x()+key.y()*7+key.z()*17,4)).opacity()),CHECK,8<<20);
        for(int x:new int[]{-129,-128,-17,-16,-1,0,15,16,127,128})for(int y:new int[]{-129,-1,0,128}){
            int z=-x;assertEquals(-1,volume.opacityIfResident(x,y,z));var key=new CubeKey(x>>4,y>>4,z>>4);var brick=volume.requireBrick(key,CHECK);
            assertEquals(brick.opacityAt(SparseVoxelVolume.index(x,y,z)),volume.opacityIfResident(x,y,z));
            var page=PageKey.ofBrick(key.x(),key.y(),key.z());int slot=PageKey.slot(key.x(),key.y(),key.z());
            assertEquals(key,new CubeKey(page.brickX(slot),page.brickY(slot),page.brickZ(slot)));
        }
    }
    @Test void staleSourcesAndPhysicalChangesCannotReuseOldPages()throws Exception {
        var first=archive("old");VoxelBrick[] slots=new VoxelBrick[512];slots[0]=VoxelBrick.uniform(1,15);first.write(new PageKey(0,0,0),new Page(slots,new long[512]));
        assertNull(archive("edited").read(new PageKey(0,0,0)).page());
        assertNotEquals(first.scope(),new Authority("fp","generation-sha","new-physical","old","code").scope(palette));
        assertNotEquals(first.scope(),new Authority("fp","generation-sha","physical","old","new-code").scope(palette));
        var retired=new SparseVoxelVolume(palette,first,(key,check)->VoxelBrick.uniform(1,15),()->{throw new CancellationException("retired");},1<<20);
        assertThrows(CancellationException.class,()->retired.requireBrick(new CubeKey(0,0,0),CHECK));
    }
    @Test void cancelledBuildPublishesNoPartialBrickAndCanRetry()throws Exception {
        var attempts=new AtomicInteger();var volume=new SparseVoxelVolume(palette,archive("cancel"),(key,check)->{if(attempts.incrementAndGet()==1)throw new CancellationException();return VoxelBrick.uniform(1,15);},CHECK,1<<20);
        assertThrows(CancellationException.class,()->volume.requireBrick(new CubeKey(-1,-1,-1),CHECK));assertEquals(-1,volume.opacityIfResident(-1,-1,-1));
        assertEquals(15,volume.requireBrick(new CubeKey(-1,-1,-1),CHECK).opacityAt(4095));assertEquals(1L,volume.status().get("bricksBuilt"));
    }
    @Test void unknownNeverTurnsIntoAirInRawOrGpuLayout()throws Exception {
        var volume=new SparseVoxelVolume(palette,archive("unknown"),(key,check)->VoxelBrick.uniform(-1,-1),CHECK,1<<20);
        assertThrows(TerrainLodColumns.UnknownSampleException.class,()->TerrainLodColumns.build(volume.rawSource(CHECK),new TerrainLodColumns.Bounds(-1,-1,-1,0,0,0),new TerrainLodColumns.Parameters(1,8,8)));
        assertEquals(-1,VoxelBrick.gpuOpacityAt(VoxelBrick.uniform(-1,-1).gpuWords(),127));
    }
    @Test void corruptionIsUnknownAndRebuiltRatherThanTrusted()throws Exception {
        var store=archive("corrupt");var key=new PageKey(-1,-2,3);VoxelBrick[] slots=new VoxelBrick[512];slots[511]=VoxelBrick.uniform(2,1);store.write(key,new Page(slots,new long[512]));
        var data=Files.readAllBytes(store.pageFile(key));data[data.length/2]^=64;Files.write(store.pageFile(key),data);
        var read=store.read(key);assertNull(read.page());assertTrue(read.status().startsWith("INVALID:"));
    }
    @Test void simultaneousRequestsCaptureExactlyOnce()throws Exception {
        var calls=new AtomicInteger();var volume=new SparseVoxelVolume(palette,archive("flight"),(key,check)->{calls.incrementAndGet();return VoxelBrick.uniform(1,15);},CHECK,1<<20);
        var pool=Executors.newFixedThreadPool(8);try{var jobs=new ArrayList<Callable<VoxelBrick>>();for(int i=0;i<40;i++)jobs.add(()->volume.requireBrick(new CubeKey(-1,0,7),CHECK));for(var f:pool.invokeAll(jobs))assertEquals(15,f.get().opacityAt(0));}finally{pool.shutdownNow();}
        assertEquals(1,calls.get());
    }
    @Test void paletteOrderIsStableAcrossRegistryOrder() {
        var reversed=new ArrayList<>(palette.entries());Collections.reverse(reversed);var next=new VoxelPalette(reversed);
        assertEquals(palette.identity(),next.identity());assertEquals(palette.entries(),next.entries());
    }
}
