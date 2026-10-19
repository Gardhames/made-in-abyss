package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AbyssLodFineLedgerTest {
    private static final List<TerrainLodRefinement.Band> BANDS=List.of(new TerrainLodRefinement.Band(128,1),new TerrainLodRefinement.Band(256,2));
    private static AbyssLodFineLedger.Grid grid(int step,boolean empty){var b=new TerrainLodColumns.Bounds(-16,-64,0,-1,-1,15);
        var raw=TerrainLodColumns.build((x,y,z)->new TerrainSample.Known(empty?TerrainMaterial.AIR:
                x==-15&&z==1&&y>=-3?TerrainMaterial.WATER:TerrainMaterial.AIR),b,new TerrainLodColumns.Parameters(step,16384,1000));
        return new AbyssLodFineLedger.Grid("source",b,step,raw);}
    private static TerrainLodColumns.Box background(){return new TerrainLodColumns.Box(-32,-64,0,16,0,16,TerrainMaterial.ROCK);}
    @Test void newCameraTargetCannotForgetPromotedCloseOneMetreGrid(){
        var ledger=new AbyssLodFineLedger(100000);var fine=grid(1,false);
        ledger.promote(ledger.prepare("source",List.of(fine),-17,-2,1,BANDS));
        var next=ledger.prepare("source",List.of(),-16,-2,1,BANDS);
        assertEquals(List.of(fine),next.grids(),"Cube-boundary request retains exact promoted grid and source provenance");
        assertEquals(List.of(fine),ledger.displayed(),"Preparing a candidate alone never retires displayed quality");
    }
    @Test void compositionRestoresRawFineAfterNativeHoleAndIncludesSampledAirOwnership(){
        var fine=grid(1,false);var candidate=new AbyssLodFineLedger(100000).prepare("source",List.of(fine),-17,-2,1,BANDS);
        var cut=fine.volume();var hidden=AbyssLodFineLedger.compose(List.of(background()),candidate,List.of(cut),1000,4000);
        assertFalse(hidden.stream().anyMatch(b->b.material()==TerrainMaterial.WATER));
        var restored=AbyssLodFineLedger.compose(List.of(background()),candidate,List.of(),1000,4000);
        assertTrue(restored.stream().anyMatch(b->b.material()==TerrainMaterial.WATER&&b.minX()==-15&&b.maxXExclusive()==-14));
        assertFalse(restored.stream().anyMatch(b->b.material()==TerrainMaterial.ROCK&&b.minX()<=-10&&b.maxXExclusive()>-10),"Sampled AIR within fine volume must remove coarse rock too");
    }
    @Test void actualBoxOverflowRefusesWholeCompositionRatherThanCoarseningFine(){
        var fine=grid(1,false);var candidate=new AbyssLodFineLedger(100000).prepare("source",List.of(fine),-17,-2,1,BANDS);
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->AbyssLodFineLedger.compose(List.of(background()),candidate,List.of(),1,4));
        var ledger=new AbyssLodFineLedger(1);
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->ledger.prepare("source",List.of(fine),0,0,0,BANDS));
        assertThrows(IllegalArgumentException.class,()->new AbyssLodFineLedger(100000).prepare("other",List.of(fine),0,0,0,BANDS));
    }
}
