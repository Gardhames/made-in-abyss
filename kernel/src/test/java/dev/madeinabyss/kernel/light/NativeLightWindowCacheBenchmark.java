package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.nio.file.*;
import java.util.*;

/** Standalone I/O cost only; does not measure source sampling, ambient rays or Minecraft loading. */
public final class NativeLightWindowCacheBenchmark {
    public static void main(String[] args)throws Exception {
        var bounds=new VoxelSkyLight.Bounds(-64,-4096,-64,128,64,128);int cells=VoxelSkyLight.cellCount(bounds,2000000);
        var lower=new byte[cells];var upper=new byte[cells];Arrays.fill(upper,(byte)14);
        var sky=VoxelSkyLight.restore(bounds,lower,upper,2000000);var block=VoxelSkyLight.restore(bounds,lower,upper,2000000);
        var cubes=new HashMap<CubeKey,int[]>();
        for(int y=-256;y<-252;y++)for(int z=-4;z<4;z++)for(int x=-4;x<4;x++){var ids=new int[4096];for(int i=0;i<4096;i++)ids[i]=(i+x+y+z)&63;cubes.put(new CubeKey(x,y,z),ids);}
        var entry=new NativeLightWindowCache.Entry(bounds,sky,block,cubes);var dir=Path.of(args[0]);String identity="e".repeat(64);
        long writeStarted=System.nanoTime();NativeLightWindowCache.write(dir,identity,entry);long write=System.nanoTime()-writeStarted;
        long[] reads=new long[5];
        for(int i=0;i<reads.length;i++){long start=System.nanoTime();var hit=NativeLightWindowCache.read(dir,identity,bounds,2000000,64);reads[i]=System.nanoTime()-start;if(hit.entry().isEmpty())throw new AssertionError(hit.status());
            if(hit.entry().get().sky().value(0,-4095,0).isPresent())throw new AssertionError("uncertainty lost");}
        Arrays.sort(reads);System.out.println("cells="+cells+" cubes="+cubes.size()+" compressedBytes="+Files.size(NativeLightWindowCache.file(dir,identity))+" writeMillis="+write/1000000.0+" medianReadMillis="+reads[2]/1000000.0);
    }
}
