package dev.madeinabyss.kernel.cube;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedCubeRetentionTest {
    private static int[] states(int value) { var a=new int[4096]; java.util.Arrays.fill(a,value); return a; }
    @Test void explorationBeyond8192CubesDoesNotRetainProceduralCopies() {
        Map<CubeKey,int[]> retained=Map.of(),previous=Map.of();
        var pristine=states(1);
        for(int x=-4500;x<4500;x++) {
            var window=Map.of(new CubeKey(x,-27,31),pristine);
            var result=GeneratedCubeRetention.merge(retained,window,previous,k->pristine,8192);
            assertFalse(result.geometryChanged());
            assertTrue(result.retained().isEmpty());
            retained=result.retained();previous=window;
        }
    }
    @Test void savedEditsSurviveLeavingAndReturningIncludingRestorationToProcedural() {
        var key=new CubeKey(-1,-1,-1);var pristine=states(1);var edit=states(2);
        var first=GeneratedCubeRetention.merge(Map.of(),Map.of(key,edit),Map.of(),k->pristine,2);
        assertTrue(first.geometryChanged());assertArrayEquals(edit,first.retained().get(key));
        var away=GeneratedCubeRetention.merge(first.retained(),Map.of(new CubeKey(1,0,0),pristine),Map.of(key,edit),k->pristine,2);
        assertFalse(away.geometryChanged());assertArrayEquals(edit,away.retained().get(key));
        var restored=GeneratedCubeRetention.merge(away.retained(),Map.of(key,pristine),Map.of(),k->pristine,2);
        assertTrue(restored.geometryChanged());assertArrayEquals(pristine,restored.retained().get(key));
        assertArrayEquals(edit,first.retained().get(key));
    }
    @Test void fullSavedInventoryStillAllowsUnchangedExplorationButNeverEvictsEdits() {
        var saved=Map.of(new CubeKey(-1,-2,-3),states(7));var key=new CubeKey(1,2,3);var pristine=states(0);
        var result=GeneratedCubeRetention.merge(saved,Map.of(key,pristine),Map.of(),k->pristine,1);
        assertEquals(saved,result.retained());assertFalse(result.geometryChanged());
        assertThrows(IllegalArgumentException.class,()->GeneratedCubeRetention.merge(saved,Map.of(key,states(9)),Map.of(),k->pristine,1));
        assertEquals(1,saved.size());assertEquals(7,saved.values().iterator().next()[0]);
    }
    @Test void overlappingPristineWindowsAvoidRepeatedGenerationButChangesAreCompared() {
        var key=new CubeKey(0,-4096,0);var pristine=states(0);var calls=new AtomicInteger();
        var result=GeneratedCubeRetention.merge(Map.of(),Map.of(key,pristine.clone()),Map.of(key,pristine),k->{calls.incrementAndGet();return pristine;},2);
        assertEquals(0,calls.get());assertFalse(result.geometryChanged());
        result=GeneratedCubeRetention.merge(Map.of(),Map.of(key,states(3)),Map.of(key,pristine),k->{calls.incrementAndGet();return pristine;},2);
        assertEquals(1,calls.get());assertTrue(result.geometryChanged());assertEquals(3,result.retained().get(key)[0]);
    }
    @Test void incomingPhysicalObservationOverridesSavedSnapshotAndOlderVisualState() {
        var key=new CubeKey(-32,-17,0);var saved=Map.of(key,states(2));
        var incoming=new HashMap<CubeKey,int[]>();incoming.put(key,states(3));incoming.putAll(Map.of(key,states(4)));
        var result=GeneratedCubeRetention.merge(saved,incoming,Map.of(),k->states(0),1);
        assertTrue(result.geometryChanged());assertEquals(4,result.retained().get(key)[4095]);assertEquals(2,saved.get(key)[4095]);
        assertThrows(UnsupportedOperationException.class,()->result.retained().clear());
    }
    @Test void preInventoryPristineReversionWinsOverOlderSavedEdit() {
        var key=new CubeKey(-1,-65,-1);var pristine=states(0);
        var disk=Map.of(key,states(5));var preInventory=Map.of(key,pristine);
        var result=GeneratedCubeRetention.merge(disk,preInventory,Map.of(),k->pristine,1);
        assertArrayEquals(pristine,result.retained().get(key));assertTrue(result.geometryChanged());
        var unchanged=GeneratedCubeRetention.merge(result.retained(),preInventory,preInventory,k->pristine,1);
        assertFalse(unchanged.geometryChanged());
    }
}
