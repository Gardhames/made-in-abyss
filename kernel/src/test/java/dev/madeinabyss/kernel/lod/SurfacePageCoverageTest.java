package dev.madeinabyss.kernel.lod;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfacePageCoverageTest {
    @Test void negative128SeamRequiresBothCurrentPageVersions(){
        var grid=new TerrainLodColumns.Bounds(-129,1,1,-127,1,1);
        var lower=new SurfaceQuadPlan.PageKey(-2,0,0);var upper=new SurfaceQuadPlan.PageKey(-1,0,0);
        var target=Set.of(lower,upper);
        assertFalse(SurfacePageCoverage.covers(grid,Set.of(lower),target,target,128));
        assertTrue(SurfacePageCoverage.covers(grid,target,target,target,128));
    }
    @Test void positiveBoundaryFaceAndHeldOldAbsentTargetPageBlockProofUntilCurrentOrRemoved(){
        var grid=new TerrainLodColumns.Bounds(127,1,1,127,1,1);
        var interior=new SurfaceQuadPlan.PageKey(0,0,0);var positiveFace=new SurfaceQuadPlan.PageKey(1,0,0);
        var both=Set.of(interior,positiveFace);
        assertFalse(SurfacePageCoverage.covers(grid,Set.of(interior),both,both,128),"Positive face at128 belongs to page1");
        assertFalse(SurfacePageCoverage.covers(grid,Set.of(interior),Set.of(interior),both,128),"A held old page is not known empty");
        assertTrue(SurfacePageCoverage.covers(grid,Set.of(interior),Set.of(interior),Set.of(interior),128),"Complete target geometry and no held old page prove empty");
        assertTrue(SurfacePageCoverage.covers(grid,both,both,both,128));
    }
}
