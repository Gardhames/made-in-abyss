package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AbyssLodGridResidencyTest {
    private static final TerrainLodColumns.Bounds BOUNDS=new TerrainLodColumns.Bounds(-16,-64,-16,-1,-1,-1);
    private static AbyssLodFineLedger.Grid grid(){
        var box=new TerrainLodColumns.Box(-16,-64,-16,0,0,0,TerrainMaterial.ROCK);
        return new AbyssLodFineLedger.Grid("source",BOUNDS,1,new TerrainLodColumns.Result(List.of(box),16384));
    }
    @Test void theSameImmutableGridCanRemainDisplayedWhileItsAcquisitionViewFitsTheUnchangedCap(){
        var g=grid();var view=new AbyssLodFineLedger.Candidate(List.of(g),208);
        assertEquals(208,AbyssLodGridResidency.retainedBytes(view,view));
        assertTrue(AbyssLodGridResidency.retainedBytes(view,view)<=208);
    }
    @Test void overlappingViewsChargeOnlyThreeSignedBoundaryGridsAndRespectTheExactByteBoundary(){
        var a=grid();var b=grid();var c=grid();
        var acquiring=new AbyssLodFineLedger.Candidate(List.of(a,b),416);
        var displayed=new AbyssLodFineLedger.Candidate(List.of(b,c),416);
        long bytes=AbyssLodGridResidency.retainedBytes(acquiring,displayed);
        assertEquals(624,bytes);assertTrue(bytes<=624);assertFalse(bytes<=623);
    }
    @Test void distinctEqualGeometryIsStillChargedWhenItsImmutablePayloadIsNotShared(){
        var a=grid();var b=grid();assertNotSame(a.raw().boxes(),b.raw().boxes());
        assertEquals(416,AbyssLodGridResidency.retainedBytes(new AbyssLodFineLedger.Candidate(List.of(a),208),new AbyssLodFineLedger.Candidate(List.of(b),208)));
    }
    @Test void distinctGridWrappersChargeTheirMetadataButShareTheSameRawPayloadOnlyOnce(){
        var a=grid();var b=new AbyssLodFineLedger.Grid("source",BOUNDS,1,a.raw());
        assertNotSame(a,b);assertSame(a.raw().boxes(),b.raw().boxes());
        assertEquals(336,AbyssLodGridResidency.retainedBytes(new AbyssLodFineLedger.Candidate(List.of(a),208),new AbyssLodFineLedger.Candidate(List.of(b),208)));
    }
    @Test void repeatedReferencesWithinAViewCannotTurnSharedRawIntoPhantomResidentBytes(){
        var g=grid();
        assertEquals(208,AbyssLodGridResidency.retainedBytes(new AbyssLodFineLedger.Candidate(List.of(g,g),416),new AbyssLodFineLedger.Candidate(List.of(g),208)));
    }
    @Test void completeAirGridsShareMetadataOnlyWhenTheGridItselfIsShared(){
        var empty=new TerrainLodColumns.Result(List.of(),16384);var g=new AbyssLodFineLedger.Grid("source",BOUNDS,1,empty);
        var a=new AbyssLodFineLedger.Candidate(List.of(g),128);var nothing=new AbyssLodFineLedger.Candidate(List.of(),0);
        assertEquals(128,AbyssLodGridResidency.retainedBytes(a,a));assertEquals(128,AbyssLodGridResidency.retainedBytes(a,nothing));
    }
}
