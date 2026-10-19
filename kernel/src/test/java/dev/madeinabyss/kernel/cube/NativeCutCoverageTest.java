package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.lod.TerrainLodClipper.BoundsLong;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeCutCoverageTest {
    // Surface-only checking would accept these cuts and leave AIR/peripheral LOD uncut.
    @Test void centralObjectAndInscribedSquareCannotProveFullRequestedAirVolume() {
        assertFalse(cover(0,0,64,-600,-216,List.of(box(-1,-600,-1,2,-215,2))));
        assertFalse(cover(0,0,64,-600,-216,List.of(box(-43,-600,-43,43,-215,43))));
        assertTrue(cover(0,0,64,-600,-216,List.of(box(-64,-600,-64,64,-215,64))));
    }

    // A radius cap/endpoint error would reject the complete largest supported window.
    @Test void completeBoundingRectangleCoversMaximumRadiusAndBottomDepth() {
        assertTrue(cover(.25,-.75,128,-20000,-19745,
                List.of(box(-129,-20000,-130,129,-19744,129))));
    }

    // Requiring one whole box or considering cuts in input order breaks valid unions.
    @Test void adjoiningXAndYSlabsCoverNegativeFractionalTargetInAnyOrder() {
        var cuts=List.of(box(0,-19998,-3,3,-19994,3),box(-3,-20000,-3,0,-19998,3),
                box(0,-20000,-3,3,-19998,3),box(-3,-19998,-3,0,-19994,3));
        assertTrue(cover(-.25,.25,2,-20000,-19995,cuts));
        var overlapped=new ArrayList<>(cuts);
        overlapped.add(box(-3,-19999,-3,3,-19996,3));overlapped.add(cuts.get(0));
        assertTrue(cover(-.25,.25,2,-20000,-19995,overlapped));
    }

    // Merging endpoints without checking an actual gap falsely certifies missing voxels.
    @Test void oneVoxelVerticalGapCannotBeHiddenByOuterCuts() {
        assertFalse(cover(-.25,.25,2,-20000,-19995,List.of(
                box(-3,-20000,-3,3,-19998,3),box(-3,-19997,-3,3,-19994,3))));
    }

    // Coverage from one XZ column must never advance the Y coverage of its neighbor.
    @Test void neighboringColumnsCannotLendTheirVerticalCoverage() {
        assertFalse(cover(.5,.5,1,-4,3,List.of(
                box(-1,-4,-1,0,0,2),box(0,0,-1,2,4,2))));
    }

    // Centers on the circle are included; bounding-square corner columns are excluded.
    @Test void onlyFiveVoxelCentersBelongToUnitCircleIncludingItsBoundary() {
        var cross=cross(0,0,-16,1);
        assertTrue(cover(.5,.5,1,-16,0,cross));
        assertFalse(cover(.5,.5,1,-16,0,cross.subList(0,4)),
                "The omitted tangent column is still part of the requested cylinder");
    }

    // Truncating negative coordinates or flooring the camera before distance loses a voxel.
    @Test void negativeHalfCoordinateUsesActualVoxelCentersAndHalfOpenCuts() {
        var cross=cross(-1,-1,-20000,-19999);
        assertTrue(cover(-.5,-.5,1,-20000,-20000,cross));
        assertFalse(cover(-.5,-.5,1,-20000,-20000,cross.subList(0,4)));
        assertFalse(cover(-.5,-.5,1,-20000,-20000,
                List.of(box(-2,-20000,-2,0,-19999,1))),
                "A max-exclusive X endpoint of zero excludes the whole x=0 column");
    }

    // Inclusive native maxY must be converted to a half-open cut endpoint without losing Y.
    @Test void finalInclusiveVoxelLayerRequiresMaxYPlusOne() {
        assertFalse(cover(.5,.5,1,-16,0,List.of(box(-1,-16,-1,2,0,2))));
        assertTrue(cover(.5,.5,1,-16,0,List.of(box(-1,-16,-1,2,1,2))));
    }

    // A gap belonging to an unrelated XZ box cannot stop an otherwise complete Y union.
    @Test void irrelevantCutDoesNotIntroduceAGapInTheCoveredColumn() {
        assertTrue(cover(.5,.5,1,-4,3,List.of(
                box(-1,-4,-1,2,0,2),box(100,-1,100,101,1,101),
                box(-1,0,-1,2,4,2))));
    }

    // Signed origins at both edges are valid; the half-open endpoint uses long arithmetic.
    @Test void signedCoordinateEdgeVoxelsAndVerticalEndpointsDoNotWrap() {
        long lo=Integer.MIN_VALUE,hi=Integer.MAX_VALUE;
        assertTrue(cover(lo+1.5,.5,1,Integer.MIN_VALUE,Integer.MIN_VALUE,
                List.of(box(lo,lo,-1,lo+3,lo+1,2))));
        assertTrue(cover(hi-.5,.5,1,Integer.MAX_VALUE,Integer.MAX_VALUE,
                List.of(box(hi-2,hi,-1,hi+1,hi+1,2))));
    }

    // A clipped/wrapped circle would claim complete coverage beyond signed voxel origins.
    @Test void requestedColumnOutsideSignedDomainRefusesEvenWithHugeCuts() {
        long lo=Integer.MIN_VALUE,hi=Integer.MAX_VALUE;
        var all=List.of(box(lo,lo,lo,hi+1,hi+1,hi+1));
        assertFalse(cover(lo+.5,.5,1,-1,0,all));
        assertFalse(cover(hi+.5,.5,1,-1,0,all));
        assertFalse(cover(.5,lo+.5,1,-1,0,all));
        assertFalse(cover(.5,hi+.5,1,-1,0,all));
    }

    // Invalid inputs must refuse; neither overflow nor a null cut can certify any volume.
    @Test void invalidBudgetCentersHeightsAndCutsRefuse() {
        var cut=List.of(box(-256,-20000,-256,256,321,256));
        for(double x:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.MAX_VALUE})
            assertFalse(cover(x,0,64,-600,-216,cut));
        assertFalse(cover(0,Double.NaN,64,-600,-216,cut));
        assertFalse(cover(0,0,0,-600,-216,cut));
        assertFalse(cover(0,0,129,-600,-216,cut));
        assertFalse(cover(0,0,64,1,0,cut));
        assertFalse(cover(0,0,64,-600,-216,List.of()));
        assertFalse(cover(0,0,64,-600,-216,null));
        assertFalse(cover(0,0,64,-600,-216,Arrays.asList(cut.get(0),null)));
        assertFalse(cover(0,0,64,-600,-216,List.of(
                box(Long.MIN_VALUE,-600,-256,Long.MAX_VALUE,-215,256))));
    }

    private static List<BoundsLong> cross(long x,long z,long minY,long maxYExclusive) {
        return List.of(box(x,minY,z,x+1,maxYExclusive,z+1),
                box(x-1,minY,z,x,maxYExclusive,z+1),box(x+1,minY,z,x+2,maxYExclusive,z+1),
                box(x,minY,z-1,x+1,maxYExclusive,z),box(x,minY,z+1,x+1,maxYExclusive,z+2));
    }
    private static boolean cover(double x,double z,int radius,int minY,int maxY,List<BoundsLong> cuts) {
        return NativeCutCoverage.covers(x,z,radius,minY,maxY,cuts);
    }
    private static BoundsLong box(long x0,long y0,long z0,long x1,long y1,long z1) {
        return new BoundsLong(x0,y0,z0,x1,y1,z1);
    }
}
