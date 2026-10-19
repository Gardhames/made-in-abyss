package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ExactBoxLightDiskCacheTest {
    @TempDir Path directory;
    private final TerrainLodColumns.Bounds bounds=new TerrainLodColumns.Bounds(-80,-1286,-80,79,319,79);
    private ExactBoxLightDiskCache.Scope scope(String source,String code){return new ExactBoxLightDiskCache.Scope(source,code,bounds,400_000);}
    private final TerrainLodColumns.Box clipped=new TerrainLodColumns.Box(-17,-65,-7,-13,-61,-3,TerrainMaterial.ROCK);
    private final TerrainLodColumns.Box water=new TerrainLodColumns.Box(2,-945,4,3,-944,5,TerrainMaterial.WATER);
    private Map<TerrainLodColumns.Box,TerrainLodDiskCache.Light> values(){return Map.of(clipped,new TerrainLodDiskCache.Light(7,0),water,new TerrainLodDiskCache.Light(2,11));}
    @Test void newReaderReusesExactSignedClippedScalarDictionaryWithoutClaimingGeometry()throws Exception {
        var scope=scope("saved-whole-cube-source","original32-closure");
        ExactBoxLightDiskCache.save(directory,scope,values());
        var fresh=ExactBoxLightDiskCache.load(directory,scope);
        assertEquals("HIT",fresh.status());assertEquals(values(),fresh.values());
        assertFalse(fresh.values().containsKey(new TerrainLodColumns.Box(-17,-65,-7,-12,-61,-3,TerrainMaterial.ROCK)));
        assertThrows(UnsupportedOperationException.class,()->fresh.values().clear());
        assertTrue(TerrainLodDiskCache.loadScene(directory,scope.key(),bounds,new TerrainLodColumns.Parameters(16,10_000_000,400_000),256).entry().isEmpty());
    }
    @Test void wrongSourceLightingClosureAndCorruptionNeverSupplyNumericLight()throws Exception {
        var scope=scope("saved-A","32-ray-A");ExactBoxLightDiskCache.save(directory,scope,values());
        assertTrue(ExactBoxLightDiskCache.load(directory,scope("saved-B","32-ray-A")).values().isEmpty());
        assertTrue(ExactBoxLightDiskCache.load(directory,scope("saved-A","32-ray-B")).values().isEmpty());
        var file=ExactBoxLightDiskCache.file(directory,scope);byte[] data=Files.readAllBytes(file);data[data.length/2]^=1;Files.write(file,data);
        assertTrue(ExactBoxLightDiskCache.load(directory,scope).values().isEmpty());
    }
    @Test void overBudgetAndOutOfScopeDictionaryRefusesBeforeWriting() {
        var tiny=new ExactBoxLightDiskCache.Scope("source","32-ray",bounds,1);
        assertThrows(IllegalArgumentException.class,()->ExactBoxLightDiskCache.save(directory,tiny,values()));
        var outside=new TerrainLodColumns.Box(80,-65,0,81,-64,1,TerrainMaterial.ROCK);
        assertThrows(IllegalArgumentException.class,()->ExactBoxLightDiskCache.save(directory,scope("source","32-ray"),Map.of(outside,new TerrainLodDiskCache.Light(15,0))));
    }
    @Test void validDifferentScopeFileAndTruncationStayUnknown()throws Exception {
        var original=scope("saved-A","original32");ExactBoxLightDiskCache.save(directory,original,values());
        var other=scope("saved-B","original32");var copy=directory.resolve("copied-page");Files.createDirectories(copy);
        Files.copy(ExactBoxLightDiskCache.file(directory,original),ExactBoxLightDiskCache.file(copy,other));
        var mismatch=ExactBoxLightDiskCache.load(copy,other);
        assertEquals("KEY_MISS",mismatch.status());assertTrue(mismatch.values().isEmpty());
        var originalFile=ExactBoxLightDiskCache.file(directory,original);var bytes=Files.readAllBytes(originalFile);
        Files.write(originalFile,java.util.Arrays.copyOf(bytes,bytes.length-1));
        assertTrue(ExactBoxLightDiskCache.load(directory,original).status().startsWith("INVALID"));
        assertTrue(ExactBoxLightDiskCache.load(directory,original).values().isEmpty());
    }
    @Test void canceledSavePreservesPriorAtomicFileAndCanceledReadSuppliesNothing()throws Exception {
        var authority=scope("saved-A","original32");ExactBoxLightDiskCache.save(directory,authority,values());
        var file=ExactBoxLightDiskCache.file(directory,authority);var before=Files.readAllBytes(file);
        Thread.currentThread().interrupt();
        try{
            assertThrows(java.util.concurrent.CancellationException.class,()->ExactBoxLightDiskCache.save(directory,authority,values()));
            assertThrows(java.util.concurrent.CancellationException.class,()->ExactBoxLightDiskCache.load(directory,authority));
        }finally{Thread.interrupted();}
        assertArrayEquals(before,Files.readAllBytes(file));assertEquals(values(),ExactBoxLightDiskCache.load(directory,authority).values());
        try(var files=Files.list(directory)){assertFalse(files.anyMatch(p->p.toString().endsWith(".tmp")));}
    }
}
