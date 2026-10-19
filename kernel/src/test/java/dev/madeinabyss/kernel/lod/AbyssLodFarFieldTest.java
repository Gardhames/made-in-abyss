package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AbyssLodFarFieldTest {
    @Test void actualBoxCapRebuildsSource32MidpointAndChargesBothAttempts(){
        var positions=new ArrayList<String>();var b=new TerrainLodColumns.Bounds(0,1024,0,31,1039,31);
        var built=AbyssLodFarField.build((x,y,z)->{positions.add(x+","+y+","+z);return new TerrainSample.Known(
                x==7&&z==7?TerrainMaterial.SOIL:TerrainMaterial.ROCK);},b,1000,1);
        assertEquals(32,built.step());assertEquals(80,built.chargedSamples());
        assertEquals(TerrainMaterial.ROCK,built.geometry().boxes().get(0).material(),"True32m midpoint15 differs from original16m midpoint7");
        assertTrue(positions.contains("7,1024,7")&&positions.contains("15,1039,15"));
    }
    @Test void everyAuthoritativePositiveYIsVisitedAndUnknownFloorNeverTriggersCoarserFallback(){
        var seen=new HashSet<Integer>();var b=new TerrainLodColumns.Bounds(-32,1024,-32,-1,1029,-1);
        var built=AbyssLodFarField.build((x,y,z)->{seen.add(y);return new TerrainSample.Known(TerrainMaterial.AIR);},b,1000,10);
        assertEquals(Set.of(1024,1025,1026,1027,1028,1029),seen);assertEquals(24,built.geometry().sampledVoxels());
        assertThrows(TerrainLodColumns.UnknownSampleException.class,()->AbyssLodFarField.build(
                (x,y,z)->new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER),b,1000,1));
        int[] calls={0};assertThrows(TerrainLodColumns.BudgetExceededException.class,()->AbyssLodFarField.build(
                (x,y,z)->{calls[0]++;return new TerrainSample.Known(TerrainMaterial.ROCK);},
                new TerrainLodColumns.Bounds(-32,-64,-32,-1,-1,-1),100,1));
        assertEquals(0,calls[0]);
    }
}
