package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.cube.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class T13PackedDiskOracleTest {
    @TempDir Path directory;
    @Test void packedRawTileAndWholeGeometryProduceIdenticalOldCodecBytes()throws Exception {
        var bounds=new TerrainLodColumns.Bounds(-16,-64,-16,-1,-49,-1);var limits=new TerrainLodColumns.Parameters(1,4096,10000);
        var plain=TerrainLodColumns.build((x,y,z)->new TerrainSample.Known((y&1)==0?TerrainMaterial.ROCK:TerrainMaterial.SOIL),bounds,limits);
        var packed=CompactLodLists.result(plain);assertEquals(4096,packed.boxes().size());
        String identity=TerrainLodDiskCache.identity("T13-exact-packed");
        var key=new TerrainTileDiskCache.Key(-16,-64,-16,-1,-49,-1,1);var oldTile=directory.resolve("old-tile");var newTile=directory.resolve("new-tile");
        var oldKey=new T3ReferenceTerrainTileDiskCache.Key(-16,-64,-16,-1,-49,-1,1);
        T3ReferenceTerrainTileDiskCache.save(oldTile,identity,Map.of(oldKey,plain.boxes()));TerrainTileDiskCache.save(newTile,identity,Map.of(key,packed.boxes()));
        assertArrayEquals(Files.readAllBytes(TerrainLodDiskCache.file(oldTile,identity)),Files.readAllBytes(TerrainLodDiskCache.file(newTile,identity)));
        var a=new LinkedHashMap<TerrainLodColumns.Box,TerrainLodDiskCache.Light>();var b=new LinkedHashMap<TerrainLodColumns.Box,TerrainLodDiskCache.Light>();
        for(int i=0;i<plain.boxes().size();i++){var light=new TerrainLodDiskCache.Light(i&15,(i>>>4)&15);a.put(plain.boxes().get(i),light);b.put(packed.boxes().get(i),light);}
        var oldGeometry=directory.resolve("old-geometry");var newGeometry=directory.resolve("new-geometry");
        TerrainLodDiskCache.saveScene(oldGeometry,identity,new TerrainLodDiskCache.Entry(plain,a),bounds,limits,4096);
        TerrainLodDiskCache.saveScene(newGeometry,identity,new TerrainLodDiskCache.Entry(packed,b),bounds,limits,4096);
        assertArrayEquals(Files.readAllBytes(TerrainLodDiskCache.file(oldGeometry,identity)),Files.readAllBytes(TerrainLodDiskCache.file(newGeometry,identity)));
        var scope=new ExactBoxLightDiskCache.Scope("T13-source","T13-light",bounds,10000);
        ExactBoxLightDiskCache.save(oldGeometry,scope,a);ExactBoxLightDiskCache.save(newGeometry,scope,b);
        assertArrayEquals(Files.readAllBytes(ExactBoxLightDiskCache.file(oldGeometry,scope)),Files.readAllBytes(ExactBoxLightDiskCache.file(newGeometry,scope)));
    }
    @Test void cubePaletteProducesExactLegacyDiagnosticBytes()throws Exception {
        var random=new Random(0x13c0dec);var store=new FileCubeStore(directory);var key=new CubeKey(-19,-2048,23);
        for(int palette:new int[]{1,2,3,17,128,4096}){
            int[] states=new int[4096];for(int i=0;i<4096;i++)states[i]=random.nextInt(palette)*100003;
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(0x4d494133);out.writeInt(1);out.writeInt(key.x());out.writeInt(key.y());out.writeInt(key.z());out.writeInt(4096);
            for(int state:states)out.writeInt(state);var crc=new CRC32();crc.update(bytes.toByteArray());out.writeInt((int)crc.getValue());out.close();
            store.write(new CubeSnapshot(key,states));assertArrayEquals(bytes.toByteArray(),Files.readAllBytes(store.path(key)));assertArrayEquals(states,store.read(key).orElseThrow().states());
        }
    }
}
