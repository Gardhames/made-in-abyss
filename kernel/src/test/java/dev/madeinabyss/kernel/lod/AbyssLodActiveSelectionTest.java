package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AbyssLodActiveSelectionTest {
    private static final List<TerrainLodRefinement.Band> BANDS=List.of(
            new TerrainLodRefinement.Band(96,1),new TerrainLodRefinement.Band(192,2),
            new TerrainLodRefinement.Band(384,4),new TerrainLodRefinement.Band(512,8));

    @Test void boundedCurrentWitnessDoesNotExpandEveryTierToRetainVisitedDistantHistory() {
        var ledger=new AbyssLodFineLedger(1_000_000);
        var oldWall=grid(768,-256,1,false);
        var close=grid(-16,-16,1,false);
        var currentWitness=grid(-16,512,1,false);
        ledger.promote(ledger.prepare("source",List.of(oldWall),775,-2,-249,BANDS));
        var selected=AbyssLodActiveSelection.select(ledger,"source",List.of(close,currentWitness),List.of(currentWitness),
                -1,-2,-1,BANDS,1024);
        assertTrue(selected.candidate().grids().contains(close));
        assertTrue(selected.candidate().grids().contains(currentWitness),
                "The bounded current stationary witness remains1m even outside camera bands");
        assertFalse(selected.candidate().grids().contains(oldWall),
                "A past witness distance cannot expand every tier and keep a distant visited wall active forever");
        assertEquals(List.of(oldWall),ledger.displayed(),"Selection does not retire the old scene before successful promotion");
    }

    @Test void capPressureDefersDistantOptionalFineAndAllowsCurrentPromotionWithoutLosingClosePins() {
        var ledger=new AbyssLodFineLedger(1_000_000);
        var close=grid(-16,-16,1,false);
        var optional=grid(320,-16,8,true);
        var newClose=grid(0,-16,1,false);
        ledger.promote(ledger.prepare("source",List.of(close,optional),-1,-2,-1,BANDS));
        var selected=AbyssLodActiveSelection.select(ledger,"source",List.of(newClose),List.of(),-1,-2,-1,BANDS,0);
        var backdrop=List.of(new TerrainLodColumns.Box(-32,-64,-32,352,0,16,TerrainMaterial.ROCK));
        assertThrows(TerrainLodColumns.BudgetExceededException.class,
                ()->AbyssLodFineLedger.composeParts(backdrop,selected.candidate(),List.of(),64,4096),
                "Fixture must reproduce the actual legacy BOX refusal, not merely compare metadata");
        var admitted=assertDoesNotThrow(()->AbyssLodActiveSelection.composeParts(selected,backdrop,List.of(),64,4096),
                "Distant optional history cannot prevent a positive current-target scene");
        assertTrue(admitted.candidate().grids().containsAll(List.of(close,newClose)),"Already close1m and new1m both survive");
        assertFalse(admitted.candidate().grids().contains(optional));
        assertTrue(admitted.deferredGrids()>0);
        assertTrue(admitted.composition().boxes().size()<=64&&!admitted.composition().fine().isEmpty());
        for(var grid:List.of(close,newClose)) {
            var sample=grid.raw().boxes().get(0);
            assertTrue(admitted.composition().fine().contains(sample),"Exact close RAW box is preserved after cap admission");
            assertFalse(admitted.composition().background().stream().anyMatch(b->contains(b,grid.bounds().minX()+8,-2,grid.bounds().minZ()+8)),
                    "Complete sampled AIR ownership still clips coarse backdrop; deferral cannot create rock in close AIR");
        }
        assertEquals(List.of(close,optional),ledger.displayed(),"Admission alone does not retire active promises");
        ledger.promote(admitted.candidate());
        assertEquals(admitted.candidate().grids(),ledger.displayed(),"Successful owner promotion can advance the current target");
    }

    @Test void requestedEligibilityStagesFreshTiersWithoutChangingAcknowledgedHistory() {
        var old=grid(-16,-16,1,false);var requested=grid(-16,-16,4,false);var ledger=new AbyssLodFineLedger(1_000_000);
        ledger.promote(new AbyssLodFineLedger.Candidate(List.of(old),128+80L*old.raw().boxes().size()));
        var previous=ledger.displayed();
        var historical=AbyssLodActiveSelection.select(ledger,"source",List.of(requested),List.of(),-8,-2,-8,BANDS,0);
        assertEquals(List.of(old),historical.candidate().grids(),"Default history policy remains unchanged");
        var fresh=AbyssLodActiveSelection.select(ledger,"source",List.of(old,requested),List.of(),-8,-2,-8,BANDS,0,g->g.step()==4);
        assertEquals(List.of(requested),fresh.candidate().grids(),"Both old RAW and displayed history obey the current requested plan");
        assertSame(previous,ledger.displayed(),"Selection cannot retire WORLD history before ACK");
    }

    @Test void canonicalAcquisitionFragmentPreservesOriginalBoxOwnershipWhenFullCellArrivesFirst() {
        var bounds=new TerrainLodColumns.Bounds(-16,-44,-16,-1,-1,-1);
        var fullBounds=new TerrainLodColumns.Bounds(-16,-64,-16,-1,-1,-1);
        dev.madeinabyss.kernel.terrain.trail.TrailNetwork.SampleSource rock=(x,y,z)->new TerrainSample.Known(TerrainMaterial.ROCK);
        var full=new AbyssLodFineLedger.Grid("source",fullBounds,2,TerrainLodColumns.build(rock,fullBounds,new TerrainLodColumns.Parameters(2,16384,1000)));
        var fragment=new AbyssLodFineLedger.Grid("source",bounds,2,TerrainLodColumns.build(rock,bounds,new TerrainLodColumns.Parameters(2,16384,1000)));
        var coldLedger=new AbyssLodFineLedger(1_000_000);var warmLedger=new AbyssLodFineLedger(1_000_000);
        warmLedger.promote(new AbyssLodFineLedger.Candidate(List.of(full),128+80L*full.raw().boxes().size()));var previous=warmLedger.displayed();
        var cold=AbyssLodActiveSelection.select(coldLedger,"source",List.of(fragment,full),List.of(),-8,-2,-8,BANDS,0,g->true,g->g==fragment);
        var warm=AbyssLodActiveSelection.select(warmLedger,"source",List.of(fragment),List.of(),-8,-2,-8,BANDS,0,g->true,g->g==fragment);
        assertEquals(List.of(fragment,full),cold.candidate().grids());
        assertEquals(cold.candidate(),warm.candidate(),"A late canonical fragment must survive same-step full-cell history in the original composition order");
        var coldBoxes=AbyssLodFineLedger.composeParts(List.of(),cold.candidate(),List.of(),1000,4000).fine();
        var warmBoxes=AbyssLodFineLedger.composeParts(List.of(),warm.candidate(),List.of(),1000,4000).fine();
        assertEquals(coldBoxes,warmBoxes,"Exact BOX ownership, not just occupied volume, determines the original light scalars");
        assertTrue(warmBoxes.containsAll(fragment.raw().boxes()),"Canonical clipped BOX owners retain their original endpoints");
        assertTrue(warmBoxes.stream().anyMatch(b->b.minY()==-64&&b.maxYExclusive()==-44),"Full-cell remainder is preserved");
        assertSame(previous,warmLedger.displayed(),"Staging cannot change the acknowledged WORLD ledger");
    }

    private static AbyssLodFineLedger.Grid grid(int x,int z,int step,boolean fragmented) {
        var bounds=new TerrainLodColumns.Bounds(x,-64,z,x+15,-1,z+15);
        var raw=TerrainLodColumns.build((bx,y,bz)->new TerrainSample.Known(fragmented
                ?(Math.floorMod(y,2)==0?TerrainMaterial.ROCK:TerrainMaterial.WATER)
                :bx==x+1&&bz==z+1&&y==-1?TerrainMaterial.WATER:TerrainMaterial.AIR),
                bounds,new TerrainLodColumns.Parameters(step,16384,1000));
        return new AbyssLodFineLedger.Grid("source",bounds,step,raw);
    }
    private static boolean contains(TerrainLodColumns.Box b,long x,long y,long z) {
        return x>=b.minX()&&x<b.maxXExclusive()&&y>=b.minY()&&y<b.maxYExclusive()&&z>=b.minZ()&&z<b.maxZExclusive();
    }
}
