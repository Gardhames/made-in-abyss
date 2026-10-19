package dev.madeinabyss.kernel.light.gpu;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.volume.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackedSceneFilesTest {
    @TempDir Path path;
    private static void words(Path p,IntBuffer data)throws Exception{var bytes=ByteBuffer.allocate(data.remaining()*4).order(ByteOrder.nativeOrder());while(data.hasRemaining())bytes.putInt(data.get());Files.write(p,bytes.array());}
    @Test void fileViewMatchesIndependentCompiledSceneAtNegativeBordersAndExteriorRules()throws Exception{
        var pages=new HashMap<VoxelVolumeArchive.PageKey,VoxelBrick[]>();
        for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++){
            if(x==0&&y==0&&z==0)continue;
            var key=VoxelVolumeArchive.PageKey.ofBrick(x,y,z);var slots=pages.computeIfAbsent(key,k->new VoxelBrick[512]);
            if(x==-1&&y==-1&&z==-1){int[] values=new int[4096];Arrays.fill(values,15);values[0]=3;slots[VoxelVolumeArchive.PageKey.slot(x,y,z)]=VoxelBrick.opacityOnly(values);}
            else slots[VoxelVolumeArchive.PageKey.slot(x,y,z)]=VoxelBrick.uniform(-1,15);
        }
        var archived=new HashMap<VoxelVolumeArchive.PageKey,VoxelVolumeArchive.Page>();pages.forEach((k,v)->archived.put(k,new VoxelVolumeArchive.Page(v,new long[512])));
        var volume=PackedLightVolume.compile(new CubeKey(-1,-1,-1),new CubeKey(1,1,1),archived,true);
        var halo=PackedHeightHalo.build(-64,-64,128,(x,z)->8+Math.floorMod(x+z,19),2);
        var oracle=new ClosedLightScene(volume,halo,32,-16,-64,921600,1);
        words(path.resolve("directory.words"),volume.directory());words(path.resolve("roots.words"),volume.roots());words(path.resolve("opacity.words"),volume.data());
        words(path.resolve("halo-meta.words"),halo.descriptors());words(path.resolve("halo.words"),halo.data());
        var props=new Properties();props.setProperty("closed","true");props.setProperty("dimensions",Arrays.toString(volume.dimensions()).replaceAll("[\\[\\] ]",""));
        props.setProperty("skyY","32");props.setProperty("coreLower","-16");props.setProperty("parentLower","-64");props.setProperty("knownRadiusSquared","921600");props.setProperty("waterOpacity","1");props.setProperty("haloMin","-64");props.setProperty("haloSide","128");
        try(var out=Files.newOutputStream(path.resolve("snapshot.properties"))){props.store(out,"fixture");}
        try(var file=new PackedSceneFiles(path)){
            assertEquals(3,file.opacity(-16,-16,-16));assertEquals(15,file.opacity(0,0,0));assertEquals(-1,file.canonicalOpacity(0,0,0));
            var random=new Random(0x155L);for(int i=0;i<12000;i++){int x=random.nextInt(200)-100,y=random.nextInt(130)-80,z=random.nextInt(200)-100;assertEquals(oracle.opacity(x,y,z),file.opacity(x,y,z),x+","+y+","+z);}
            int[] dir=file.directory();dir[0]=123;assertNotEquals(123,file.directory()[0]);
        }
        Files.delete(path.resolve("opacity.words"));
    }
}
