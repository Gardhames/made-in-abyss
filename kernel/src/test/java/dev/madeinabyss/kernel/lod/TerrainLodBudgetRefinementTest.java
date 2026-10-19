package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TerrainLodBudgetRefinementTest {
    @Test void closeVerticalTileIsNotRejectedWithExpensiveFarTileInSameXZColumn() {
        var base=List.of(new TerrainLodColumns.Box(-16,-128,-16,0,0,0,TerrainMaterial.ROCK));
        var near=new TerrainLodBudgetRefinement.Tile(new TerrainLodClipper.BoundsLong(-16,-32,-16,0,-16,0),List.of(
                new TerrainLodColumns.Box(-16,-32,-16,-8,-16,0,TerrainMaterial.SOIL),
                new TerrainLodColumns.Box(-8,-32,-16,0,-16,0,TerrainMaterial.GRASS)));
        var farBoxes=new java.util.ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<8;i++)farBoxes.add(new TerrainLodColumns.Box(-16,-128+i*2,-16,0,-126+i*2,0,i%2==0?TerrainMaterial.SOIL:TerrainMaterial.GRASS));
        var far=new TerrainLodBudgetRefinement.Tile(new TerrainLodClipper.BoundsLong(-16,-128,-16,0,-112,0),farBoxes);
        var a=TerrainLodBudgetRefinement.select(base,List.of(far,near),-15,-24,-15,4);
        var b=TerrainLodBudgetRefinement.select(base,List.of(near,far),-15,-24,-15,4);
        assertEquals(a,b);assertEquals(1,a.acceptedCells());assertEquals(1,a.rejectedCells());assertEquals(4,a.boxes().size());
        assertEquals(TerrainMaterial.SOIL,at(a.boxes(),-15,-24,-15));assertEquals(TerrainMaterial.ROCK,at(a.boxes(),-15,-120,-15));
    }
    @Test void overBudgetOneMetreTileUsesTwoMetreSampledFallbackBeforeBaseEight() {
        var bounds=new TerrainLodClipper.BoundsLong(-16,-80,-16,0,-64,0);
        var base=List.of(new TerrainLodColumns.Box(-16,-80,-16,0,-64,0,TerrainMaterial.ROCK));
        var fine=new java.util.ArrayList<TerrainLodColumns.Box>();
        for(int z=-16;z<0;z++)for(int x=-16;x<0;x++)fine.add(new TerrainLodColumns.Box(x,-80,z,x+1,-64,z+1,x%2==0?TerrainMaterial.SOIL:TerrainMaterial.GRASS));
        var tile=new TerrainLodBudgetRefinement.Tile(bounds,fine,1,List.of());
        var result=TerrainLodBudgetRefinement.select(base,List.of(tile),-1,-72,-1,64);
        assertEquals(1,result.acceptedCells());assertEquals(0,result.rejectedCells());assertEquals(64,result.boxes().size());
        assertTrue(result.boxes().stream().allMatch(b->b.maxXExclusive()-b.minX()==2&&b.maxZExclusive()-b.minZ()==2&&b.material()==TerrainMaterial.SOIL));
        var four=TerrainLodBudgetRefinement.select(base,List.of(tile),-1,-72,-1,16);
        assertEquals(1,four.acceptedCells());assertEquals(16,four.boxes().size());
        assertTrue(four.boxes().stream().allMatch(b->b.maxXExclusive()-b.minX()==4&&b.maxZExclusive()-b.minZ()==4));
    }
    @Test void fallbackSamplesRawGeometryBeforeClippingOwnershipHoles() {
        var bounds=new TerrainLodClipper.BoundsLong(-16,-80,-16,0,-64,0);
        var excluded=new TerrainLodClipper.BoundsLong(-16,-80,-16,-15,-64,0);
        var base=List.of(new TerrainLodColumns.Box(-15,-80,-16,0,-64,0,TerrainMaterial.ROCK));
        var fine=new java.util.ArrayList<TerrainLodColumns.Box>();
        for(int z=-16;z<0;z++)for(int x=-16;x<0;x++)fine.add(new TerrainLodColumns.Box(x,-80,z,x+1,-64,z+1,TerrainMaterial.SOIL));
        var tile=new TerrainLodBudgetRefinement.Tile(bounds,fine,1,List.of(excluded));
        var out=TerrainLodBudgetRefinement.select(base,List.of(tile),-1,-72,-1,64);
        assertEquals(1,out.acceptedCells());assertEquals(TerrainMaterial.SOIL,at(out.boxes(),-15,-72,-15));
        assertTrue(out.boxes().stream().noneMatch(b->b.minX()<excluded.maxXExclusive()&&b.maxXExclusive()>excluded.minX()));
    }
    private static TerrainLodColumns.Box box(long x,long end,TerrainMaterial m) {
        return new TerrainLodColumns.Box(x,-64,-16,end,0,0,m);
    }
    private static TerrainLodBudgetRefinement.Tile detail(long x) {
        return new TerrainLodBudgetRefinement.Tile(new TerrainLodClipper.BoundsLong(x,-64,-16,x+16,0,0),
                List.of(box(x,x+8,TerrainMaterial.SOIL),box(x+8,x+16,TerrainMaterial.GRASS)));
    }
    @Test void overflowKeepsFarCoverageAndAdmitsNearestNegativeCellFirst() {
        var base=List.of(box(-32,-16,TerrainMaterial.ROCK),box(-16,0,TerrainMaterial.ROCK),box(0,16,TerrainMaterial.ROCK));
        var out=TerrainLodBudgetRefinement.select(base,List.of(detail(0),detail(-32),detail(-16)),-31,-40,-10,4);
        assertEquals(4,out.boxes().size());assertEquals(1,out.acceptedCells());assertEquals(2,out.rejectedCells());
        assertEquals(TerrainMaterial.SOIL,material(out.boxes(),-31));
        assertEquals(TerrainMaterial.ROCK,material(out.boxes(),-15));
        assertEquals(TerrainMaterial.ROCK,material(out.boxes(),15));
    }
    @Test void rejectedAirReplacementNeverCutsBaseAndAcceptedAirReallyRemovesIt() {
        var base=List.of(box(-16,0,TerrainMaterial.ROCK));
        var empty=new TerrainLodBudgetRefinement.Tile(new TerrainLodClipper.BoundsLong(-16,-64,-16,0,0,0),List.of());
        assertTrue(TerrainLodBudgetRefinement.select(base,List.of(empty),-1,-1,-1,1).boxes().isEmpty());
        var expensive=detail(-16);
        assertEquals(base,TerrainLodBudgetRefinement.select(base,List.of(expensive),-1,-1,-1,1).boxes());
    }
    @Test void verticalCompactionIsCountedBeforeBudgetAndOrderIsDeterministic() {
        var base=List.of(box(-16,0,TerrainMaterial.ROCK));
        var bounds=new TerrainLodClipper.BoundsLong(-16,-64,-16,0,0,0);
        var tile=new TerrainLodBudgetRefinement.Tile(bounds,List.of(
            new TerrainLodColumns.Box(-16,-32,-16,0,0,0,TerrainMaterial.SOIL),
            new TerrainLodColumns.Box(-16,-64,-16,0,-32,0,TerrainMaterial.SOIL)));
        var out=TerrainLodBudgetRefinement.select(base,List.of(tile),-1,-1,-1,1);
        assertEquals(List.of(box(-16,0,TerrainMaterial.SOIL)),out.boxes());assertEquals(1,out.acceptedCells());
    }
    @Test void nativeSeamFragmentsConsumeTheSameFinalBudgetAsFineGeometry() {
        var seam=new TerrainLodClipper.BoundsLong(-12,-48,-16,-4,-16,0);
        var base=TerrainLodClipper.subtract(List.of(box(-16,0,TerrainMaterial.ROCK)),List.of(seam),10);
        var raw=detail(-16);
        var visible=new TerrainLodBudgetRefinement.Tile(raw.bounds(),TerrainLodClipper.subtract(raw.boxes(),List.of(seam),10));
        var selected=TerrainLodBudgetRefinement.select(base,List.of(visible),-16,-32,-16,base.size());
        assertEquals(base.size(),selected.boxes().size());assertEquals(1,selected.rejectedCells());
        for(var b:selected.boxes())assertFalse(b.minX()<seam.maxXExclusive()&&b.maxXExclusive()>seam.minX()
            &&b.minY()<seam.maxYExclusive()&&b.maxYExclusive()>seam.minY());
    }
    @Test void refusedCandidateKeepsCommittedSceneAndRejectsItsLateReadiness() {
        var groups=new RenderGroupHandoff<String>();groups.beginTarget(1);groups.stage(1,"base");
        groups.markDrawn(1,"base");groups.promote();groups.beginTarget(2);groups.stage(2,"over-budget");
        assertEquals("over-budget",groups.beginTarget(2));
        groups.markDrawn(2,"over-budget");assertFalse(groups.ready());
        assertEquals("base",groups.displayed());assertEquals(1,groups.size());
        groups.beginTarget(3);groups.stage(3,"bounded-high");groups.markDrawn(3,"bounded-high");
        assertEquals("base",groups.promote());assertEquals("bounded-high",groups.displayed());
    }
    @Test void closestNonoverlappingPrefixKeepsItsActualGridInCompleteSelectionAtTightCaps() {
        var first=new TerrainLodClipper.BoundsLong(-16,-64,-16,0,-48,0);
        var adjacent=new TerrainLodClipper.BoundsLong(-16,-48,-16,0,-32,0);
        var farAir=new TerrainLodClipper.BoundsLong(16,-64,-16,32,-48,0);
        var emptyCell=new TerrainLodClipper.BoundsLong(64,-64,-16,80,-48,0);
        var lowerAir=new TerrainLodClipper.BoundsLong(-16,-128,-16,0,-112,0);
        var fine=new java.util.ArrayList<TerrainLodColumns.Box>();var later=new java.util.ArrayList<TerrainLodColumns.Box>();
        for(int z=-16;z<0;z++)for(int x=-16;x<0;x++) {
            var material=x%2==0?TerrainMaterial.SOIL:TerrainMaterial.GRASS;
            fine.add(new TerrainLodColumns.Box(x,-64,z,x+1,-48,z+1,material));
            later.add(new TerrainLodColumns.Box(x,-48,z,x+1,-32,z+1,material));
        }
        var complete=List.of(new TerrainLodBudgetRefinement.Tile(emptyCell,List.of(),4,List.of()),
                new TerrainLodBudgetRefinement.Tile(adjacent,later,1,List.of()),new TerrainLodBudgetRefinement.Tile(lowerAir,List.of(),4,List.of()),
                new TerrainLodBudgetRefinement.Tile(first,fine,1,List.of()),new TerrainLodBudgetRefinement.Tile(farAir,List.of(),4,List.of()));
        var regions=complete.stream().map(tile->{var b=tile.bounds();return new TerrainLodRefinement.Region(
                new TerrainLodColumns.Bounds((int)b.minX(),(int)b.minY(),(int)b.minZ(),(int)b.maxXExclusive()-1,(int)b.maxYExclusive()-1,(int)b.maxZExclusive()-1),tile.horizontalStep());}).toList();
        var plan=new TerrainLodRefinement.Plan(regions,java.util.Optional.empty(),regions.stream().mapToLong(TerrainLodRefinement.Region::sampledVoxels).sum());
        var closest=ColdPreviewStages.detailPrefix(plan,-1,-60,-1,1);
        assertEquals(1,closest.size(),"Startup must choose a bounded prefix before sampling geometry");
        assertEquals(new TerrainLodColumns.Bounds(-16,-64,-16,-1,-49,-1),closest.get(0).bounds());
        var overlapping=new TerrainLodRefinement.Plan(List.of(regions.get(0),regions.get(0)),java.util.Optional.empty(),0);
        assertTrue(ColdPreviewStages.detailPrefix(overlapping,-1,-60,-1,1).isEmpty(),"An arbitrary overlapping plan cannot receive the independent-prefix certificate");
        var base=List.of(new TerrainLodColumns.Box(-16,-128,-16,0,0,0,TerrainMaterial.ROCK),new TerrainLodColumns.Box(16,-64,-16,32,-48,0,TerrainMaterial.ROCK));
        var prefix=List.of(complete.stream().filter(tile->tile.bounds().equals(first)).findFirst().orElseThrow());
        int[] caps={24,80,300},steps={4,2,1};
        for(int i=0;i<caps.length;i++) {
            var early=TerrainLodBudgetRefinement.select(base,prefix,-1,-60,-1,caps[i]);
            var terminal=TerrainLodBudgetRefinement.select(base,complete,-1,-60,-1,caps[i]);
            assertEquals(1,early.rawAcceptedReplacements().size());var admitted=early.rawAcceptedReplacements().get(0);
            assertEquals(steps[i],admitted.horizontalStep());
            assertEquals(admitted,terminal.rawAcceptedReplacements().stream().filter(tile->tile.bounds().equals(first)).findFirst().orElseThrow(),
                    "Later disjoint vertical tiles/AIR/empty cells must never revisit the earlier admitted grid");
        }
    }
    private static TerrainMaterial material(List<TerrainLodColumns.Box> boxes,long x) {
        return boxes.stream().filter(b->x>=b.minX()&&x<b.maxXExclusive()&&-40>=b.minY()&&-40<b.maxYExclusive()).findFirst().orElseThrow().material();
    }
    private static TerrainMaterial at(List<TerrainLodColumns.Box> boxes,long x,long y,long z) {
        return boxes.stream().filter(b->x>=b.minX()&&x<b.maxXExclusive()&&y>=b.minY()&&y<b.maxYExclusive()&&z>=b.minZ()&&z<b.maxZExclusive()).findFirst().orElseThrow().material();
    }
}
