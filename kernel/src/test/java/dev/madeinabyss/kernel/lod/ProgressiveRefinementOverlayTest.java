package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProgressiveRefinementOverlayTest {
    private static final TerrainLodColumns.Box NEAR=new TerrainLodColumns.Box(-32,-80,-32,0,-64,0,TerrainMaterial.ROCK);
    private static final TerrainLodColumns.Box FAR=new TerrainLodColumns.Box(32,-80,32,64,-64,64,TerrainMaterial.ROCK);
    private static final TerrainLodClipper.BoundsLong FIRST=new TerrainLodClipper.BoundsLong(-32,-80,-32,-16,-64,-16);
    private static final TerrainLodColumns.Box SOIL=new TerrainLodColumns.Box(-32,-80,-32,-16,-64,-16,TerrainMaterial.SOIL);
    private static TerrainLodBudgetRefinement.Tile tile(TerrainLodClipper.BoundsLong bounds,List<TerrainLodColumns.Box> boxes) {
        return new TerrainLodBudgetRefinement.Tile(bounds,boxes,4,List.of());
    }
    @Test void acceptedNegativeTileKeepsUntouchedCoarseKeysAndExplicitLightOwnership() {
        var out=ProgressiveRefinementOverlay.select(List.of(NEAR,FAR),List.of(tile(FIRST,List.of(SOIL))),-31,-72,-31,128);
        assertTrue(out.preservedBackdrop());assertEquals(1,out.acceptedCells());assertEquals(0,out.rejectedCells());
        assertEquals(List.of(SOIL),out.detail());assertTrue(out.background().contains(FAR));
        assertEquals(3,out.background().size());assertEquals(4,out.boxes().size());
        assertEquals(TerrainMaterial.SOIL,at(out.boxes(),-31,-72,-31));
        assertEquals(TerrainMaterial.ROCK,at(out.boxes(),-1,-72,-1));
        assertNoOverlap(out.background(),out.detail());
    }
    @Test void acceptedAirRemovesOnlyItsVolumeAndNeverCreatesADetailBox() {
        var out=ProgressiveRefinementOverlay.select(List.of(NEAR,FAR),List.of(tile(FIRST,List.of())),-31,-72,-31,128);
        assertTrue(out.preservedBackdrop());assertEquals(1,out.acceptedCells());assertTrue(out.detail().isEmpty());
        assertNull(at(out.boxes(),-31,-72,-31));assertEquals(TerrainMaterial.ROCK,at(out.boxes(),-1,-72,-1));
        assertTrue(out.background().contains(FAR));
    }
    @Test void refusedReplacementPreservesItsBackdropWithoutCuttingAHole() {
        var base=new TerrainLodColumns.Box(-16,-80,-16,0,-64,0,TerrainMaterial.ROCK);
        var bounds=new TerrainLodClipper.BoundsLong(-16,-80,-16,0,-64,0);
        var costly=new TerrainLodBudgetRefinement.Tile(bounds,List.of(
                new TerrainLodColumns.Box(-16,-80,-16,-8,-64,0,TerrainMaterial.SOIL),
                new TerrainLodColumns.Box(-8,-80,-16,0,-64,0,TerrainMaterial.GRASS)));
        var out=ProgressiveRefinementOverlay.select(List.of(base),List.of(costly),-1,-72,-1,1);
        assertEquals(0,out.acceptedCells());assertEquals(1,out.rejectedCells());
        assertEquals(List.of(base),out.background());assertTrue(out.detail().isEmpty());
        assertEquals(TerrainMaterial.ROCK,at(out.boxes(),-1,-72,-1));
    }
    @Test void laterPrefixKeepsCompletedTileKeyAndClipsNativeOwnershipBeforeAdmission() {
        var cut=new TerrainLodClipper.BoundsLong(-32,-80,-32,-24,-64,-16);
        var visibleBackdrop=TerrainLodClipper.subtract(List.of(NEAR,FAR),List.of(cut),128);
        var visibleTile=new TerrainLodBudgetRefinement.Tile(FIRST,List.of(SOIL),4,List.of(cut));
        var second=new TerrainLodClipper.BoundsLong(-16,-80,-32,0,-64,-16);
        var prefix=ProgressiveRefinementOverlay.select(visibleBackdrop,List.of(visibleTile),-31,-72,-31,128);
        var next=ProgressiveRefinementOverlay.select(visibleBackdrop,List.of(visibleTile,tile(second,List.of())),-31,-72,-31,128);
        assertEquals(prefix.detail(),next.detail());assertTrue(next.background().contains(FAR));
        assertNull(at(next.boxes(),-31,-72,-31));assertNull(at(next.boxes(),-1,-72,-31));
        assertEquals(TerrainMaterial.SOIL,at(next.boxes(),-20,-72,-31));assertNoOverlap(next.background(),next.detail());
    }
    @Test void extraUnmergedDetailFallsBackToBoundedExactSelectionWithoutAHole() {
        var pieces=new java.util.ArrayList<TerrainLodColumns.Box>();
        for(int y=-80;y<-64;y+=4)pieces.add(new TerrainLodColumns.Box(-32,y,-32,-16,y+4,-16,TerrainMaterial.SOIL));
        var out=ProgressiveRefinementOverlay.select(List.of(NEAR),List.of(tile(FIRST,pieces)),-31,-72,-31,4);
        assertFalse(out.preservedBackdrop());assertTrue(out.boxes().size()<=4);assertEquals(1,out.acceptedCells());
        assertEquals(TerrainMaterial.SOIL,at(out.boxes(),-31,-72,-31));assertEquals(TerrainMaterial.ROCK,at(out.boxes(),-1,-72,-1));
    }
    @Test void replacementProvenanceRetainsActualSampledFallbackInsteadOfRawFineGeometry() {
        var bounds=new TerrainLodClipper.BoundsLong(-16,-80,-16,0,-64,0);
        var base=new TerrainLodColumns.Box(-16,-80,-16,0,-64,0,TerrainMaterial.ROCK);
        var fine=new java.util.ArrayList<TerrainLodColumns.Box>();
        for(int z=-16;z<0;z++)for(int x=-16;x<0;x++)fine.add(new TerrainLodColumns.Box(x,-80,z,x+1,-64,z+1,x%2==0?TerrainMaterial.SOIL:TerrainMaterial.GRASS));
        var out=ProgressiveRefinementOverlay.select(List.of(base),List.of(new TerrainLodBudgetRefinement.Tile(bounds,fine,1,List.of())),-1,-72,-1,64);
        assertTrue(out.preservedBackdrop());assertEquals(1,out.acceptedTwoMetre());assertTrue(out.background().isEmpty());
        assertEquals(64,out.detail().size());assertTrue(out.detail().stream().allMatch(b->b.maxXExclusive()-b.minX()==2&&b.maxZExclusive()-b.minZ()==2&&b.material()==TerrainMaterial.SOIL));
    }
    @Test void forcedRawRebaseKeepsFineKeysWhenPanoramaBackdropChanges() {
        var fine=new TerrainLodBudgetRefinement.Tile(FIRST,List.of(SOIL),1,List.of());
        var out=ProgressiveRefinementOverlay.rebase(List.of(NEAR,FAR),List.of(fine),List.of(),List.of(),-31,-72,-31,128).orElseThrow();
        assertTrue(out.preservedBackdrop());assertEquals(List.of(SOIL),out.detail());assertTrue(out.background().contains(FAR));
        assertEquals(List.of(fine),out.rawAcceptedReplacements());assertNoOverlap(out.background(),out.detail());
        assertEquals(TerrainMaterial.SOIL,at(out.boxes(),-31,-72,-31));assertEquals(TerrainMaterial.ROCK,at(out.boxes(),-1,-72,-1));
    }
    @Test void rawAirAndChangedNativeHolesAreReappliedToNewBackdropBeforePromotion() {
        var oldCut=new TerrainLodClipper.BoundsLong(-32,-80,-32,-24,-64,-16);
        var newCut=new TerrainLodClipper.BoundsLong(-24,-80,-32,-16,-64,-16);
        var solid=tile(FIRST,List.of(SOIL));
        var early=ProgressiveRefinementOverlay.select(TerrainLodClipper.subtract(List.of(NEAR),List.of(oldCut),128),
                List.of(new TerrainLodBudgetRefinement.Tile(FIRST,List.of(SOIL),4,List.of(oldCut))),-31,-72,-31,128);
        assertEquals(List.of(solid),early.rawAcceptedReplacements(),"Retained provenance must precede native clipping");
        var airBounds=new TerrainLodClipper.BoundsLong(-16,-80,-32,0,-64,-16);var air=tile(airBounds,List.of());
        var pinned=new java.util.ArrayList<>(early.rawAcceptedReplacements());pinned.add(air);
        var out=ProgressiveRefinementOverlay.rebase(List.of(NEAR,FAR),pinned,List.of(),List.of(newCut),-31,-72,-31,128).orElseThrow();
        assertEquals(TerrainMaterial.SOIL,at(out.boxes(),-31,-72,-31),"Old native hole must recover raw detail");
        assertNull(at(out.boxes(),-20,-72,-31));assertNull(at(out.boxes(),-1,-72,-31),"Accepted AIR must remove new coarse backdrop");
        assertTrue(out.rawAcceptedReplacements().contains(air));assertNoOverlap(out.background(),out.detail());
    }
    @Test void forcedRebasePinsTheAdmittedCoarsenedRawGeometryRatherThanFineInput() {
        var bounds=new TerrainLodClipper.BoundsLong(-16,-80,-16,0,-64,0);
        var base=new TerrainLodColumns.Box(-16,-80,-16,0,-64,0,TerrainMaterial.ROCK);
        var fine=new java.util.ArrayList<TerrainLodColumns.Box>();
        for(int z=-16;z<0;z++)for(int x=-16;x<0;x++)fine.add(new TerrainLodColumns.Box(x,-80,z,x+1,-64,z+1,x%2==0?TerrainMaterial.SOIL:TerrainMaterial.GRASS));
        var early=ProgressiveRefinementOverlay.select(List.of(base),List.of(new TerrainLodBudgetRefinement.Tile(bounds,fine,1,List.of())),-1,-72,-1,64);
        assertEquals(1,early.rawAcceptedReplacements().size());var accepted=early.rawAcceptedReplacements().get(0);
        assertEquals(2,accepted.horizontalStep());assertEquals(early.detail(),accepted.boxes());assertTrue(accepted.exclusions().isEmpty());
        var out=ProgressiveRefinementOverlay.rebase(List.of(base,FAR),List.of(accepted),List.of(),List.of(),-1,-72,-1,128).orElseThrow();
        assertEquals(early.detail(),out.detail());assertEquals(TerrainMaterial.SOIL,at(out.boxes(),-15,-72,-15));
        assertEquals(List.of(accepted),out.rawAcceptedReplacements());assertNoOverlap(out.background(),out.detail());
    }
    @Test void forcedRebaseRefusesCapOverflowSoOwnerCanHoldItsDrawnDetail() {
        var pin=new TerrainLodBudgetRefinement.Tile(FIRST,List.of(SOIL),1,List.of());
        assertTrue(ProgressiveRefinementOverlay.rebase(List.of(NEAR,FAR),List.of(pin),List.of(),List.of(),-31,-72,-31,1).isEmpty());
    }
    @Test void everyPartialPrefixKeepsPinnedDetailWhileAllowingFinerReplacement() {
        var pin=new TerrainLodBudgetRefinement.Tile(FIRST,List.of(SOIL),2,List.of());
        var coarser=new TerrainLodBudgetRefinement.Tile(FIRST,List.of(new TerrainLodColumns.Box(-32,-80,-32,-16,-64,-16,TerrainMaterial.GRASS)),4,List.of());
        var same=new TerrainLodBudgetRefinement.Tile(FIRST,List.of(),2,List.of());
        var secondBounds=new TerrainLodClipper.BoundsLong(-16,-80,-32,0,-64,-16);
        for(var candidates:List.of(List.<TerrainLodBudgetRefinement.Tile>of(),List.of(coarser),List.of(coarser,same),List.of(coarser,same,tile(secondBounds,List.of())))) {
            var out=ProgressiveRefinementOverlay.rebase(List.of(NEAR,FAR),List.of(pin),candidates,List.of(),-31,-72,-31,128).orElseThrow();
            assertEquals(TerrainMaterial.SOIL,at(out.boxes(),-31,-72,-31));assertTrue(out.detail().contains(SOIL));
            assertTrue(out.rawAcceptedReplacements().contains(pin));assertNoOverlap(out.background(),out.detail());
        }
        var finer=new TerrainLodBudgetRefinement.Tile(FIRST,List.of(),1,List.of());
        var upgraded=ProgressiveRefinementOverlay.rebase(List.of(NEAR,FAR),List.of(pin),List.of(finer),List.of(),-31,-72,-31,128).orElseThrow();
        assertNull(at(upgraded.boxes(),-31,-72,-31));assertTrue(upgraded.rawAcceptedReplacements().contains(finer));
        assertFalse(upgraded.rawAcceptedReplacements().contains(pin));assertNoOverlap(upgraded.background(),upgraded.detail());
    }
    @Test void nearAdmissionAloneCannotCertifyTheOriginalTerminalBaseBudget() {
        var base=new TerrainLodColumns.Box(-32,-80,-32,-16,-64,-16,TerrainMaterial.ROCK);
        var fine=new TerrainLodBudgetRefinement.Tile(FIRST,List.of(SOIL),1,List.of());
        var early=ProgressiveRefinementOverlay.select(List.of(base),List.of(fine),-31,-72,-31,4);
        assertEquals(1,early.acceptedCells());assertEquals(List.of(fine),early.rawAcceptedReplacements());
        var terminal=TerrainLodBudgetRefinement.select(List.of(base,FAR),List.of(fine),-31,-72,-31,4);
        assertEquals(0,terminal.acceptedCells(),"Full base16m partition consumes the budget before refinement admission");
        assertEquals(TerrainMaterial.ROCK,at(terminal.boxes(),-31,-72,-31));
        assertEquals(List.of(base,FAR),terminal.boxes());
    }
    @Test void currentTerminalCertificateAcceptsNativeOrEqualFinerOwnershipAndRejectsMissingOrCoarseVolumes() {
        var pin=new TerrainLodBudgetRefinement.Tile(FIRST,List.of(SOIL),2,List.of());
        assertTrue(ProgressiveRefinementOverlay.preserves(List.of(pin),List.of(pin),List.of(),128));
        assertTrue(ProgressiveRefinementOverlay.preserves(List.of(pin),List.of(new TerrainLodBudgetRefinement.Tile(FIRST,List.of(),1,List.of())),List.of(),128));
        assertFalse(ProgressiveRefinementOverlay.preserves(List.of(pin),List.of(tile(FIRST,List.of())),List.of(),128));
        assertFalse(ProgressiveRefinementOverlay.preserves(List.of(pin),List.of(),List.of(),128));
        var left=new TerrainLodClipper.BoundsLong(-32,-80,-32,-24,-64,-16);
        var right=new TerrainLodClipper.BoundsLong(-24,-80,-32,-16,-64,-16);
        assertTrue(ProgressiveRefinementOverlay.preserves(List.of(pin),List.of(new TerrainLodBudgetRefinement.Tile(right,List.of(),2,List.of())),List.of(left),128));
        assertFalse(ProgressiveRefinementOverlay.preserves(List.of(pin),List.of(),List.of(left),128));
        assertTrue(ProgressiveRefinementOverlay.preserves(List.of(pin),List.of(),List.of(FIRST),128));
    }
    private static TerrainMaterial at(List<TerrainLodColumns.Box> boxes,long x,long y,long z) {
        var matching=boxes.stream().filter(b->x>=b.minX()&&x<b.maxXExclusive()&&y>=b.minY()&&y<b.maxYExclusive()&&z>=b.minZ()&&z<b.maxZExclusive()).toList();
        assertTrue(matching.size()<=1,"Overlapping ownership at "+x+","+y+","+z);
        return matching.isEmpty()?null:matching.get(0).material();
    }
    private static void assertNoOverlap(List<TerrainLodColumns.Box> a,List<TerrainLodColumns.Box> b) {
        for(var x:a)for(var y:b)assertFalse(x.minX()<y.maxXExclusive()&&x.maxXExclusive()>y.minX()
                &&x.minY()<y.maxYExclusive()&&x.maxYExclusive()>y.minY()&&x.minZ()<y.maxZExclusive()&&x.maxZExclusive()>y.minZ());
    }
}
