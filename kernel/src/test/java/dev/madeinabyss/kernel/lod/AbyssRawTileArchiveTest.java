package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AbyssRawTileArchiveTest {
    @TempDir Path directory;
    private final TerrainTileDiskCache.Key solid=new TerrainTileDiskCache.Key(-32,-1280,-16,-17,-1217,-1,1);
    private final TerrainTileDiskCache.Key air=new TerrainTileDiskCache.Key(-16,-1280,-16,-1,-1217,-1,1);
    private List<TerrainLodColumns.Box> rock(){return List.of(new TerrainLodColumns.Box(-32,-1250,-16,-31,-1230,-15,TerrainMaterial.ROCK));}
    @Test void physicalReloadQuarantineFencesDirtyAndPersistedTilesAndPreservesOtherScopes()throws Exception {
        var other=new AbyssRawTileArchive(directory,"scope-B");other.put(solid,rock());other.checkpoint();
        var revoked=new AbyssRawTileArchive(directory,"scope-A");revoked.put(solid,rock());revoked.checkpoint();revoked.put(air,List.of());
        revoked.quarantine();
        assertThrows(java.util.concurrent.CancellationException.class,()->revoked.put(air,List.of()));
        var fresh=new AbyssRawTileArchive(directory,"scope-A");assertNull(fresh.get(solid));assertNull(fresh.get(air));
        assertEquals(rock(),new AbyssRawTileArchive(directory,"scope-B").get(solid));
        try(var children=java.nio.file.Files.list(directory)){assertTrue(children.anyMatch(p->p.getFileName().toString().contains(".quarantined-")));}
    }
    @Test void partialCheckpointSurvivesNewReaderAndPreservesCertifiedAirWithoutFullScene()throws Exception {
        var writer=new AbyssRawTileArchive(directory,"recipe-saved-cubes-and-code-A");
        writer.put(solid,rock());writer.put(air,List.of());writer.checkpoint();
        var reader=new AbyssRawTileArchive(directory,"recipe-saved-cubes-and-code-A");
        assertEquals(rock(),reader.get(solid));assertEquals(List.of(),reader.get(air));
        assertNull(reader.get(new TerrainTileDiskCache.Key(0,-1280,-16,15,-1217,-1,1)));
        assertThrows(UnsupportedOperationException.class,()->reader.get(solid).clear());
    }
    @Test void exactScopeAndStepKeepOtherPagesAndResolutionsIndependent()throws Exception {
        var writer=new AbyssRawTileArchive(directory,"scope-A");writer.put(solid,rock());writer.checkpoint();
        var distant=new TerrainTileDiskCache.Key(1024,-20480,-1040,1039,-20417,-1025,2);
        var distantBoxes=List.of(new TerrainLodColumns.Box(1024,-20480,-1040,1026,-20474,-1038,TerrainMaterial.SOIL));
        writer.put(distant,distantBoxes);writer.checkpoint();
        var reader=new AbyssRawTileArchive(directory,"scope-A");
        assertEquals(rock(),reader.get(solid));assertEquals(distantBoxes,reader.get(distant));
        assertNull(reader.get(new TerrainTileDiskCache.Key(-32,-1280,-16,-17,-1217,-1,2)));
        assertNull(new AbyssRawTileArchive(directory,"scope-B").get(solid));
    }
    @Test void malformedRawNeverBecomesAnArchivedKnownTile() {
        var writer=new AbyssRawTileArchive(directory,"scope-A");
        var outside=List.of(new TerrainLodColumns.Box(-33,-1250,-16,-32,-1230,-15,TerrainMaterial.ROCK));
        assertThrows(IllegalArgumentException.class,()->writer.put(solid,outside));
        assertNull(writer.get(solid));
    }
    @Test void boundedPageEvictionCheckpointsEarlierAirWithoutWaitingForSceneCompletion()throws Exception {
        var writer=new AbyssRawTileArchive(directory,"scope-A");
        for(int i=0;i<40;i++)writer.put(new TerrainTileDiskCache.Key(i*64,-1280,-16,i*64+15,-1217,-1,1),List.of());
        assertTrue((Integer)writer.status().get("retainedPages")<=32);
        assertTrue((Long)writer.status().get("retainedBytes")<=(Long)writer.status().get("maxRetainedBytes"));
        assertTrue((Long)writer.status().get("writtenPages")>=8);
        var reader=new AbyssRawTileArchive(directory,"scope-A");
        assertEquals(List.of(),reader.get(new TerrainTileDiskCache.Key(0,-1280,-16,15,-1217,-1,1)));
    }
    @Test void sparsePageReuseSpendsExistingByteBudgetInsteadOfThrashingAfterThirtyTwoPages()throws Exception {
        AbyssRawTileArchive writer;
        try{writer=AbyssRawTileArchive.class.getConstructor(Path.class,String.class,int.class).newInstance(directory,"sparse-pages",8192);}
        catch(NoSuchMethodException old){writer=new AbyssRawTileArchive(directory,"sparse-pages");}
        var keys=new java.util.ArrayList<TerrainTileDiskCache.Key>();
        for(int i=-40;i<40;i++){
            var key=new TerrainTileDiskCache.Key(i*64,-1280,-16,i*64+15,-1217,-1,1);keys.add(key);writer.put(key,List.of());
        }
        long reads=(Long)writer.status().get("diskReads");
        for(var key:keys)assertEquals(List.of(),writer.get(key));
        assertEquals(reads,writer.status().get("diskReads"),"Tiny certified AIR pages fit the existing16MiB bound and must not be re-read on a cyclic sweep");
        assertEquals(16L*1024*1024,writer.status().get("maxRetainedBytes"));
        assertTrue((Long)writer.status().get("retainedBytes")<=16L*1024*1024);
        writer.checkpoint();var reader=new AbyssRawTileArchive(directory,"sparse-pages");
        for(var key:keys)assertEquals(List.of(),reader.get(key));
    }
}
