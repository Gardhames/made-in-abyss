package dev.madeinabyss.kernel.lod;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class TerrainLodDiskCacheTest {
    @TempDir Path directory;
    final String key="a".repeat(64);
    final TerrainLodColumns.Bounds bounds=new TerrainLodColumns.Bounds(-8,-32,-8,7,31,7);
    final TerrainLodColumns.Parameters limits=new TerrainLodColumns.Parameters(8,256,100);
    TerrainLodDiskCache.Entry fixture(){
        var box=new TerrainLodColumns.Box(-8,-32,-8,0,-16,0,TerrainMaterial.ROCK);
        return new TerrainLodDiskCache.Entry(new TerrainLodColumns.Result(List.of(box),256),Map.of(box,new TerrainLodDiskCache.Light(3,7)));
    }
    @Test void roundTripRetainsExactNegativeGeometryAndPhysicalLights() throws Exception {
        var expected=fixture();TerrainLodDiskCache.save(directory,key,expected,bounds,limits);
        var read=TerrainLodDiskCache.load(directory,key,bounds,limits);
        assertEquals("HIT",read.status());assertEquals(expected,read.entry().orElseThrow());
        assertThrows(UnsupportedOperationException.class,()->read.entry().orElseThrow().lights().clear());
    }
    @Test void corruptionTruncationAndDifferentVisitedIdentityCannotSupplyData() throws Exception {
        TerrainLodDiskCache.save(directory,key,fixture(),bounds,limits);var file=TerrainLodDiskCache.file(directory,key);
        var bytes=Files.readAllBytes(file);bytes[bytes.length/2]^=1;Files.write(file,bytes);
        assertTrue(TerrainLodDiskCache.load(directory,key,bounds,limits).entry().isEmpty());
        Files.write(file,Arrays.copyOf(bytes,12));assertTrue(TerrainLodDiskCache.load(directory,key,bounds,limits).entry().isEmpty());
        TerrainLodDiskCache.save(directory,key,fixture(),bounds,limits);
        assertTrue(TerrainLodDiskCache.load(directory,"e".repeat(64),bounds,limits).entry().isEmpty());
        assertTrue(TerrainLodDiskCache.load(directory,key,bounds,new TerrainLodColumns.Parameters(8,255,100)).entry().isEmpty());
    }
    @Test void fixedSlotsStayBoundedAndIllegalGeometryNeverWrites() throws Exception {
        for(int i=0;i<12;i++)TerrainLodDiskCache.save(directory,Integer.toHexString(i).repeat(64),fixture(),bounds,limits);
        try(var files=Files.list(directory)){assertTrue(files.count()<=4);}
        var outside=new TerrainLodColumns.Box(-16,-32,-8,-8,-16,0,TerrainMaterial.ROCK);
        var invalid=new TerrainLodDiskCache.Entry(new TerrainLodColumns.Result(List.of(outside),256),Map.of());
        assertThrows(IllegalArgumentException.class,()->TerrainLodDiskCache.save(directory,key,invalid,bounds,limits));
    }
    @Test void finalSceneAllowsExactMixedAndClippedBoxesButRequiresCompleteRawLights() throws Exception {
        var box=new TerrainLodColumns.Box(-7,-31,-7,-3,-26,-6,TerrainMaterial.ROCK);
        var scene=new TerrainLodDiskCache.Entry(new TerrainLodColumns.Result(List.of(box),256),Map.of(box,new TerrainLodDiskCache.Light(4,1)));
        TerrainLodDiskCache.saveScene(directory,key,scene,bounds,limits,256);
        assertEquals(scene,TerrainLodDiskCache.loadScene(directory,key,bounds,limits,256).entry().orElseThrow());
        assertTrue(TerrainLodDiskCache.loadScene(directory,key,bounds,limits,255).entry().isEmpty());
        assertThrows(IllegalArgumentException.class,()->TerrainLodDiskCache.saveScene(directory,key,
                new TerrainLodDiskCache.Entry(scene.base(),Map.of()),bounds,limits,256));
    }
    @Test void exactViewIdentityIsOrderIndependentAndChangesAfterCameraOrSavedMirrorEdit() {
        var a=new dev.madeinabyss.kernel.cube.CubeKey(-1,-4,2);var b=new dev.madeinabyss.kernel.cube.CubeKey(2,-5,-1);
        var first=new LinkedHashMap<dev.madeinabyss.kernel.cube.CubeKey,byte[]>();first.put(a,new byte[]{1,2,3});first.put(b,new byte[]{4,5,6});
        var reverse=new LinkedHashMap<dev.madeinabyss.kernel.cube.CubeKey,byte[]>();reverse.put(b,new byte[]{4,5,6});reverse.put(a,new byte[]{1,2,3});
        var original=TerrainLodDiskCache.viewIdentity(key,"source","high","bands",-1.5,-66.25,3.0,16,first);
        assertEquals(original,TerrainLodDiskCache.viewIdentity(key,"source","high","bands",-1.5,-66.25,3.0,16,reverse));
        assertNotEquals(original,TerrainLodDiskCache.viewIdentity(key,"source","high","bands",Math.nextUp(-1.5),-66.25,3.0,16,first));
        assertNotEquals(original,TerrainLodDiskCache.viewIdentity(key,"edited-source","high","bands",-1.5,-66.25,3.0,16,first));
        first.get(a)[1]=8;
        assertNotEquals(original,TerrainLodDiskCache.viewIdentity(key,"source","high","bands",-1.5,-66.25,3.0,16,first));
    }
}
