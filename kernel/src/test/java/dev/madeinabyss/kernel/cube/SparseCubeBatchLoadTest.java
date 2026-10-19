package dev.madeinabyss.kernel.cube;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SparseCubeBatchLoadTest {
    @Test void batchPreservesSavedTicksAndBlockEntities() {
        var key=new CubeKey(-1,-1250,0);var position=new dev.madeinabyss.kernel.BlockCoordinate(-1,-20000,1);
        var tick=new dev.madeinabyss.kernel.tick.SavedCubeTick(dev.madeinabyss.kernel.tick.SavedCubeTick.Kind.BLOCK,"minecraft:stone",position,23,0,7);
        var entity=new SavedCubeBlockEntity(position,"minecraft:chest",new byte[]{1,2,3});
        var snapshot=new CubeSnapshot(key,new int[4096],List.of(tick),List.of(entity));
        var batch=new SparseCubeSpace();batch.loadAll(Map.of(key,snapshot));
        var sequential=new SparseCubeSpace();sequential.load(snapshot);
        assertEquals(sequential.snapshot(key).orElseThrow().ticks(),batch.snapshot(key).orElseThrow().ticks());
        assertEquals(List.of(entity),batch.snapshot(key).orElseThrow().blockEntities());
    }
    @Test void wholeSignedWindowMatchesSequentialSnapshotsAndGeneration() {
        var snapshots=new LinkedHashMap<CubeKey,CubeSnapshot>();
        for(int y=-18;y<=1;y++)for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            var key=new CubeKey(x,y,z);int[] states=new int[4096];
            for(int i=0;i<states.length;i++)states[i]=Math.floorMod(i+x*31+y*17+z,19);
            snapshots.put(key,new CubeSnapshot(key,states));
        }
        var sequential=new SparseCubeSpace();for(var snapshot:snapshots.values())sequential.load(snapshot);
        var batch=new SparseCubeSpace();batch.loadAll(snapshots);
        assertEquals(sequential.residentKeys(),batch.residentKeys());
        assertEquals(sequential.contentGeneration(),batch.contentGeneration());
        for(var key:snapshots.keySet())assertArrayEquals(sequential.snapshot(key).orElseThrow().states(),batch.snapshot(key).orElseThrow().states());
        snapshots.clear();assertEquals(500,batch.loadedCubeCount());
    }
    @Test void badMemberRefusesEntireBatchAndPreservesExistingState() {
        var space=new SparseCubeSpace();var old=new CubeKey(-1,-1250,-1);space.loadEmpty(old);
        var added=new CubeKey(0,-1250,-1);var wrong=new CubeKey(1,-1250,-1);
        var invalid=new LinkedHashMap<CubeKey,CubeSnapshot>();
        invalid.put(added,new CubeSnapshot(added,new int[4096]));invalid.put(wrong,new CubeSnapshot(old,new int[4096]));
        assertThrows(IllegalArgumentException.class,()->space.loadAll(invalid));
        assertEquals(Set.of(old),space.residentKeys());assertEquals(1,space.contentGeneration());
        invalid.put(wrong,new CubeSnapshot(wrong,new int[4096]));invalid.put(old,new CubeSnapshot(old,new int[4096]));
        assertThrows(IllegalStateException.class,()->space.loadAll(invalid));
        assertEquals(Set.of(old),space.residentKeys());assertEquals(1,space.contentGeneration());
        space.loadAll(Map.of());assertEquals(1,space.contentGeneration());
    }
}
