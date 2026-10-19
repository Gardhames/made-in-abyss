package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalLodCompositionTest {
    private static TerrainLodColumns.Box box(long x,long y,long z,long x1,long y1,long z1,TerrainMaterial m){return new TerrainLodColumns.Box(x,y,z,x1,y1,z1,m);}
    private static AbyssLodFineLedger.Grid grid(int x,int y,int z,int step,TerrainMaterial material){
        var bounds=new TerrainLodColumns.Bounds(x,y,z,x+15,y+15,z+15);
        var boxes=material==TerrainMaterial.AIR?List.<TerrainLodColumns.Box>of():List.of(box(x,y,z,x+16,y+16,z+16,material));
        return new AbyssLodFineLedger.Grid("source",bounds,step,new TerrainLodColumns.Result(boxes,new TerrainLodRefinement.Region(bounds,step).sampledVoxels()));
    }
    private static AbyssLodFineLedger.Candidate fine(AbyssLodFineLedger.Grid... grids){return new AbyssLodFineLedger.Candidate(List.of(grids),0);}
    private static Map<TerrainLodColumns.Box,Integer> multi(List<TerrainLodColumns.Box> boxes){var out=new HashMap<TerrainLodColumns.Box,Integer>();for(var b:boxes)out.merge(b,1,Integer::sum);return out;}
    private static void apply(Map<TerrainLodColumns.Box,Integer> result,IncrementalLodComposition.Delta delta){
        for(var b:delta.removed()){assertTrue(result.getOrDefault(b,0)>0,"Removed BOX must actually exist");result.compute(b,(k,n)->n==1?null:n-1);}
        for(var b:delta.added())result.merge(b,1,Integer::sum);
        assertEquals(delta.totalBoxes(),result.values().stream().mapToInt(Integer::intValue).sum());
    }
    private static void compare(IncrementalLodComposition engine,Map<TerrainLodColumns.Box,Integer> result,List<TerrainLodColumns.Box> roots,AbyssLodFineLedger.Candidate fine,List<TerrainLodClipper.BoundsLong> cuts){
        var delta=engine.update(roots,fine,cuts);apply(result,delta);var oracle=AbyssLodFineLedger.composeParts(roots,fine,cuts,10000,20000);
        assertEquals(multi(oracle.boxes()),result);assertEquals(oracle.background().size(),delta.backgroundBoxes());
    }
    @Test void signedAirWaterEqualStepAndMultipleHeightsMatchUntouchedOperator(){
        var roots=List.of(box(-32,-64,-16,32,64,16,TerrainMaterial.ROCK),box(-32,-64,-16,32,64,16,TerrainMaterial.ROCK));
        var engine=new IncrementalLodComposition(10000,20000,1000000);var result=new HashMap<TerrainLodColumns.Box,Integer>();
        var coarse=grid(-16,-64,-16,4,TerrainMaterial.SOIL);var water=grid(-16,-64,-16,1,TerrainMaterial.WATER);var air=grid(-16,0,-16,1,TerrainMaterial.AIR);
        compare(engine,result,roots,fine(coarse,water,air),List.of());
        compare(engine,result,roots,fine(water,coarse,air),List.of());
        compare(engine,result,roots,fine(grid(-16,-64,-16,1,TerrainMaterial.ROCK),water,air),List.of());
        compare(engine,result,roots,fine(water,grid(-16,-64,-16,1,TerrainMaterial.ROCK),air),List.of());
        compare(engine,result,roots,fine(air),List.of());
    }
    @Test void oneFineCellChangeTouchesOnlyIndexedFarRootsAndDoesNotReindex(){
        var roots=List.of(box(-64,-32,-32,0,32,32,TerrainMaterial.ROCK),box(64,-32,-32,128,32,32,TerrainMaterial.ROCK));
        var engine=new IncrementalLodComposition(10000,20000,1000000);var result=new HashMap<TerrainLodColumns.Box,Integer>();
        apply(result,engine.update(roots,fine(),List.of()));
        var candidate=fine(grid(-16,0,0,1,TerrainMaterial.WATER));var delta=engine.update(roots,candidate,List.of());apply(result,delta);
        assertEquals(1,delta.recomputedFineCells());assertEquals(1,delta.recomputedFarRoots());assertEquals(0,delta.rootIndexBuilds());
        assertEquals(multi(AbyssLodFineLedger.composeParts(roots,candidate,List.of(),10000,20000).boxes()),result);
        var unchanged=engine.update(roots,candidate,List.of());assertTrue(unchanged.removed().isEmpty()&&unchanged.added().isEmpty());
        assertEquals(0,unchanged.recomputedFineCells());assertEquals(0,unchanged.recomputedFarRoots());
    }
    @Test void nativeCutsRestorationAndBackdropReplacementMatchOriginal32And64Owners(){
        var roots=List.of(box(-64,-64,-64,0,0,0,TerrainMaterial.ROCK),box(0,-64,0,32,0,32,TerrainMaterial.WATER));
        var engine=new IncrementalLodComposition(10000,20000,1000000);var result=new HashMap<TerrainLodColumns.Box,Integer>();var f=fine(grid(-16,-16,-16,1,TerrainMaterial.WATER));
        var a=new TerrainLodClipper.BoundsLong(-40,-40,-40,8,-8,8);var b=new TerrainLodClipper.BoundsLong(-20,-60,-20,20,-4,20);
        compare(engine,result,roots,f,List.of(a,b));compare(engine,result,roots,f,List.of(b,a));compare(engine,result,roots,f,List.of());
        compare(engine,result,List.of(roots.get(1)),f,List.of(a));compare(engine,result,List.of(),fine(),List.of());
    }
    @Test void rejectedBoxCapRollsBackAllPersistentState(){
        var roots=List.of(box(-16,0,0,16,16,16,TerrainMaterial.ROCK));var engine=new IncrementalLodComposition(1,32,100000);var result=new HashMap<TerrainLodColumns.Box,Integer>();
        apply(result,engine.update(roots,fine(),List.of()));
        var cut=new TerrainLodClipper.BoundsLong(-2,2,2,2,14,14);
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->engine.update(roots,fine(),List.of(cut)));
        var unchanged=engine.update(roots,fine(),List.of());apply(result,unchanged);assertTrue(unchanged.removed().isEmpty()&&unchanged.added().isEmpty());
        assertEquals(multi(roots),result);
    }
    @Test void incidenceBudgetRejectsBeforeRetainingUnboundedCoordinates(){
        var engine=new IncrementalLodComposition(1000,2000,256);
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->engine.update(List.of(box(-1000000,0,-1000000,1000000,16,1000000,TerrainMaterial.ROCK)),fine(),List.of()));
    }
    @Test void downstreamPageRefusalRestoresOnlyChangedStateAndRetryProducesIdenticalDelta(){
        var roots=List.of(box(-32,0,-16,32,32,16,TerrainMaterial.ROCK));var engine=new IncrementalLodComposition(10000,20000,1000000);
        var result=new HashMap<TerrainLodColumns.Box,Integer>();apply(result,engine.update(roots,fine(),List.of()));
        var checkpoint=engine.checkpoint();var change=fine(grid(-16,0,-16,1,TerrainMaterial.WATER));
        var first=engine.update(roots,change,List.of());engine.restore(checkpoint);
        var retry=engine.update(roots,change,List.of());assertEquals(first,retry,"Downstream failure must not consume a composition change or alter its identity");
        apply(result,retry);assertEquals(multi(AbyssLodFineLedger.composeParts(roots,change,List.of(),10000,20000).boxes()),result);
        var old=engine.checkpoint();var replacement=List.of(box(64,0,0,128,16,64,TerrainMaterial.ROCK));
        var replacing=engine.update(replacement,fine(),List.of());engine.restore(old);
        assertEquals(replacing,engine.update(replacement,fine(),List.of()),"Bootstrap/root replacement also rolls back by reference");
        assertThrows(IllegalArgumentException.class,()->engine.restore(new IncrementalLodComposition(1,1,1000).checkpoint()));
        var stale=engine.checkpoint();engine.update(replacement,fine(),List.of());engine.update(replacement,fine(),List.of());
        assertThrows(IllegalArgumentException.class,()->engine.restore(stale));
    }
    @Test void localMetadataSurvivesSimultaneousFarRebuildAndAirOnlyRemoval(){
        var root=box(-32,-16,-16,32,32,16,TerrainMaterial.ROCK);var roots=List.of(root,root);
        var engine=new IncrementalLodComposition(10000,20000,1000000);var result=new HashMap<TerrainLodColumns.Box,Integer>();
        apply(result,engine.update(roots,fine(),List.of()));var water=grid(-16,0,-16,1,TerrainMaterial.WATER);
        var replacement=List.of(root,box(-64,-16,-32,0,32,0,TerrainMaterial.ROCK));var checkpoint=engine.checkpoint();
        var delta=engine.update(replacement,fine(water),List.of());
        assertTrue(delta.fineRemoved().isEmpty());assertEquals(water.raw().boxes(),delta.fineAdded());assertEquals(List.of(water.volume()),delta.fineFootprints());
        assertEquals(1,delta.rootIndexBuilds());engine.restore(checkpoint);assertEquals(delta,engine.update(replacement,fine(water),List.of()));
        apply(result,delta);assertEquals(multi(AbyssLodFineLedger.composeParts(replacement,fine(water),List.of(),10000,20000).boxes()),result);
        var air=grid(-16,0,-16,1,TerrainMaterial.AIR);var removedWater=engine.update(replacement,fine(air),List.of());apply(result,removedWater);
        assertEquals(water.raw().boxes(),removedWater.fineRemoved());assertTrue(removedWater.fineAdded().isEmpty());assertEquals(List.of(air.volume()),removedWater.fineFootprints());
        var removedAir=engine.update(replacement,fine(),List.of());apply(result,removedAir);
        assertTrue(removedAir.fineRemoved().isEmpty()&&removedAir.fineAdded().isEmpty());assertEquals(List.of(air.volume()),removedAir.fineFootprints(),"AIR owns a cut even though it owns zero rendered BOXs");
        assertEquals(multi(replacement),result);
    }
    @Test void localMetadataUsesComposedNativeClippedFineOutputsAndPreservesDuplicates(){
        var b=new TerrainLodColumns.Bounds(-16,-16,-16,-1,-1,-1);var value=box(-16,-16,-16,0,0,0,TerrainMaterial.WATER);
        var duplicate=new AbyssLodFineLedger.Grid("source",b,1,new TerrainLodColumns.Result(List.of(value,value),4096));var f=fine(duplicate);
        var cut=new TerrainLodClipper.BoundsLong(-12,-12,-12,-4,-4,-4);var engine=new IncrementalLodComposition(10000,20000,1000000);
        var delta=engine.update(List.of(),f,List.of(cut));
        assertEquals(AbyssLodFineLedger.composeParts(List.of(),f,List.of(cut),10000,20000).fine(),delta.fineAdded());
        assertEquals(List.of(duplicate.volume()),delta.fineFootprints());assertEquals(2,multi(delta.fineAdded()).values().stream().mapToInt(Integer::intValue).max().orElseThrow());
        var changedCut=engine.update(List.of(),f,List.of());assertEquals(delta.fineAdded(),changedCut.fineRemoved());assertEquals(List.of(value,value),changedCut.fineAdded());
        var noChange=engine.update(List.of(),f,List.of());assertTrue(noChange.fineRemoved().isEmpty()&&noChange.fineAdded().isEmpty()&&noChange.fineFootprints().isEmpty());
    }
}
