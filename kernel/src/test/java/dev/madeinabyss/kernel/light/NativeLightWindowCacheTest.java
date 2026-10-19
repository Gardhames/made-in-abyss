package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.cube.CubeKey;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeLightWindowCacheTest {
    @Test void exactSnapshotRetainsUncertaintyAndDoesNotShareArrays(){
        var bounds=new VoxelSkyLight.Bounds(-2,-4098,-2,5,5,5);byte[] opacity=new byte[125],seeds=new byte[125];
        var original=VoxelSkyLight.solve(bounds,opacity,seeds,125);
        byte[] lower=original.lowerSnapshot(),upper=original.upperSnapshot();
        var restored=VoxelSkyLight.restore(bounds,lower,upper,125);
        Arrays.fill(lower,(byte)15);Arrays.fill(upper,(byte)15);
        for(int x=-3;x<=3;x++)for(int y=-4099;y<=-4093;y++)for(int z=-3;z<=3;z++)assertEquals(original.value(x,y,z),restored.value(x,y,z));
        assertTrue(restored.value(-2,-4098,-2).isEmpty());
        byte[] invalid=new byte[125];invalid[0]=16;assertThrows(IllegalArgumentException.class,()->VoxelSkyLight.restore(bounds,new byte[125],invalid,125));
        assertThrows(IllegalArgumentException.class,()->VoxelSkyLight.restore(bounds,new byte[124],new byte[125],125));
        invalid[0]=1;assertThrows(IllegalArgumentException.class,()->VoxelSkyLight.restore(bounds,invalid,new byte[125],125));
        assertThrows(IllegalArgumentException.class,()->VoxelSkyLight.restore(new VoxelSkyLight.Bounds(Integer.MAX_VALUE,0,0,2,1,1),new byte[2],new byte[2],2));
    }
    @Test void diskRoundtripPreservesBothLightsFullDensityAndIdentity() throws Exception {
        var bounds=new VoxelSkyLight.Bounds(-16,-32,16,32,32,32);var cubes=new HashMap<CubeKey,int[]>();
        for(int y=-2;y<0;y++)for(int z=1;z<3;z++)for(int x=-1;x<1;x++){int[] ids=new int[4096];Arrays.fill(ids,7+x-y+z);cubes.put(new CubeKey(x,y,z),ids);}
        byte[] opacity=new byte[32768],seeds=new byte[32768];seeds[17000]=15;
        var sky=VoxelSkyLight.solve(bounds,opacity,seeds,32768);var block=VoxelSkyLight.solveBlock(bounds,opacity,seeds,32768);
        var entry=new NativeLightWindowCache.Entry(bounds,sky,block,cubes);var dir=Files.createTempDirectory("native-window-");String key="b".repeat(64);
        NativeLightWindowCache.write(dir,key,entry);var read=NativeLightWindowCache.read(dir,key,bounds,32768,100);
        assertEquals("HIT",read.status());var restored=read.entry().orElseThrow();
        for(int y=-32;y<0;y++)for(int z=16;z<48;z++)for(int x=-16;x<16;x++){assertEquals(sky.value(x,y,z),restored.sky().value(x,y,z));assertEquals(block.value(x,y,z),restored.block().value(x,y,z));}
        for(var e:cubes.entrySet())assertArrayEquals(e.getValue(),restored.cubes().get(e.getKey()));
        var mutable=restored.cubes();mutable.values().iterator().next()[0]=999;assertNotEquals(999,restored.cubes().values().iterator().next()[0]);
        assertTrue(NativeLightWindowCache.read(dir,"c".repeat(63)+"b",bounds,32768,100).entry().isEmpty(),"same slot with different full identity is a miss");
        assertTrue(NativeLightWindowCache.read(dir,key,new VoxelSkyLight.Bounds(-15,-32,16,32,32,32),32768,100).entry().isEmpty(),"bounds are checked independently of key");
        assertTrue(NativeLightWindowCache.read(dir,key,bounds,32768,8).entry().isEmpty(),"state palette bound rejects stale registry IDs");
        var file=NativeLightWindowCache.file(dir,key);var bytes=Files.readAllBytes(file);bytes[bytes.length/2]^=1;Files.write(file,bytes);
        assertTrue(NativeLightWindowCache.read(dir,key,bounds,32768,100).entry().isEmpty(),"corruption is optional-cache miss");
        cubes.remove(new CubeKey(-1,-2,1));assertThrows(IllegalArgumentException.class,()->new NativeLightWindowCache.Entry(bounds,sky,block,cubes));
    }
    @Test void unalignedWindowAndIntegerEndpointsRetainOnlyCompleteDensity() throws Exception {
        var bounds=new VoxelSkyLight.Bounds(-17,Integer.MIN_VALUE,Integer.MAX_VALUE-16,33,16,17);int cells=VoxelSkyLight.cellCount(bounds,10000);
        byte[] opacity=new byte[cells],seeds=new byte[cells];Arrays.fill(opacity,(byte)15);
        var sky=VoxelSkyLight.solve(bounds,opacity,seeds,10000);var block=VoxelSkyLight.solveBlock(bounds,opacity,seeds,10000);
        var cubes=new HashMap<CubeKey,int[]>();for(int x=-1;x<=0;x++){int[] states=new int[4096];Arrays.fill(states,1);cubes.put(new CubeKey(x,-134217728,134217727),states);}
        var dir=Files.createTempDirectory("native-endpoints-");String key="a".repeat(64);
        NativeLightWindowCache.write(dir,key,new NativeLightWindowCache.Entry(bounds,sky,block,cubes));
        var result=NativeLightWindowCache.read(dir,key,bounds,10000,2).entry().orElseThrow();
        assertEquals(2,result.cubes().size());assertEquals(sky.value(0,Integer.MIN_VALUE,Integer.MAX_VALUE),result.sky().value(0,Integer.MIN_VALUE,Integer.MAX_VALUE));
        var file=NativeLightWindowCache.file(dir,key);var valid=Files.readAllBytes(file);Files.write(file,Arrays.copyOf(valid,valid.length-8));
        assertTrue(NativeLightWindowCache.read(dir,key,bounds,10000,2).entry().isEmpty(),"truncated cache is a miss");
        Files.write(file,valid);var unsupported=Files.createTempFile("native-no-directory-",".tmp");
        assertThrows(java.io.IOException.class,()->NativeLightWindowCache.write(unsupported,key,new NativeLightWindowCache.Entry(bounds,sky,block,cubes)));
        assertArrayEquals(valid,Files.readAllBytes(file),"failed write cannot damage prior snapshot");
    }
}
