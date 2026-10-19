package dev.madeinabyss.kernel.terrain.habitat;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HabitatOverlayTest {
    private static final int FLOOR=-20000;
    private static HabitatOverlay.Patch patch(String id,int x,int z){return new HabitatOverlay.Patch(id,x,z,80,80,FLOOR,91);}
    private static TrailNetwork.SampleSource flat(){return (x,y,z)->new TerrainSample.Known(y>FLOOR?TerrainMaterial.AIR:y==FLOOR?TerrainMaterial.GRASS:y>=FLOOR-2?TerrainMaterial.SOIL:TerrainMaterial.ROCK);}

    @Test void flatSupportedHabitatContainsForestEdgesClearingWaterAndSmallRuin() {
        var plan=HabitatOverlay.plan(flat(),List.of(patch("a",-80,-80)));
        assertTrue(plan.summary().trees()>=6);
        assertTrue(plan.summary().flowers()>0);
        assertTrue(plan.summary().waterCells()>9,"pond plus stream");
        assertTrue(plan.summary().structureCells()>0);
        assertEquals(plan.placements().size(),plan.summary().placementCount());
        assertFalse(plan.placements().stream().anyMatch(p->p.block()==HabitatBlock.LOG && Math.abs(p.x()+40)<8 && Math.abs(p.z()+40)<8),"central clearing");
    }

    @Test void everyTreeHasGroundedRootAndConnectedTrunkBranchesAndCanopy() {
        var plan=HabitatOverlay.plan(flat(),List.of(patch("a",-80,-80)));
        var trees=new HashMap<String,List<HabitatOverlay.Placement>>();
        plan.placements().stream().filter(p->p.block()==HabitatBlock.LOG || p.block()==HabitatBlock.LEAVES).forEach(p->trees.computeIfAbsent(p.featureId(),k->new ArrayList<>()).add(p));
        assertFalse(trees.isEmpty());
        for(var tree:trees.values()) {
            var root=tree.stream().filter(p->p.block()==HabitatBlock.LOG).min(Comparator.comparingInt(HabitatOverlay.Placement::y)).orElseThrow();
            var support=resolved(plan,flat(),root.x(),root.y()-1,root.z());
            assertTrue(support==HabitatBlock.GRASS || support==HabitatBlock.SOIL,"root on composed fertile microrelief");
            var remaining=new HashSet<>(tree);var queue=new ArrayDeque<HabitatOverlay.Placement>();queue.add(root);remaining.remove(root);
            while(!queue.isEmpty()) {var current=queue.remove();for(var next:new ArrayList<>(remaining))if(Math.abs(next.x()-current.x())+Math.abs(next.y()-current.y())+Math.abs(next.z()-current.z())==1){remaining.remove(next);queue.add(next);}}
            assertTrue(remaining.isEmpty(),"no disconnected canopy or branch");
            assertTrue(tree.stream().anyMatch(p->p.block()==HabitatBlock.LEAVES));
            assertTrue(tree.stream().anyMatch(p->p.block()==HabitatBlock.LOG && p.x()!=root.x()),"horizontal rooted branches");
        }
    }

    @Test void flatPondAndChannelAreRecessedWithNaturalBanksAndSupportedBeds() {
        var base=flat();var plan=HabitatOverlay.plan(base,List.of(patch("a",0,0)));
        var water=plan.placements().stream().filter(p->p.block()==HabitatBlock.WATER).toList();
        assertTrue(water.size()>9);
        assertEquals(1,water.stream().map(HabitatOverlay.Placement::y).distinct().count(),"flat terrain must not gain forced raised water steps");
        assertTrue(water.stream().allMatch(p->p.y()<FLOOR));
        for(var p:water){
            assertTrue(solid(resolved(plan,base,p.x(),p.y()-1,p.z())));
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
                var neighbor=resolved(plan,base,p.x()+d[0],p.y(),p.z()+d[1]);
                assertTrue(solid(neighbor) || neighbor==HabitatBlock.WATER
                        || resolved(plan,base,p.x()+d[0],p.y()-1,p.z()+d[1])==HabitatBlock.WATER,"water must lead into contained lower channel or natural bank");
            }
        }
    }

    @Test void waterFeatureNeverBuildsArtificialBanksAboveExistingSurface() {
        var plan=HabitatOverlay.plan(flat(),List.of(patch("a",0,0)));
        var feature=plan.placements().stream().filter(p->p.featureId().contains("pond-stream")).toList();
        assertFalse(feature.isEmpty());
        assertTrue(feature.stream().noneMatch(p->p.y()>FLOOR && p.block()!=HabitatBlock.AIR),"no raised stone or gravel flume");
        assertTrue(feature.stream().noneMatch(p->p.block()==HabitatBlock.ROCK),"existing soil and grass serve as banks");
        assertTrue(feature.stream().anyMatch(p->p.block()==HabitatBlock.AIR && p.y()==FLOOR),"shallow surface excavation above recessed water");
    }

    @Test void flowersAlwaysKeepComposedFertileSupportAfterWaterExcavation() {
        var base=flat();var plan=HabitatOverlay.plan(base,List.of(patch("a",0,0)));
        var flowers=plan.placements().stream().filter(p->p.block()==HabitatBlock.FLOWER).toList();
        assertFalse(flowers.isEmpty());
        for(var flower:flowers){
            var below=resolved(plan,base,flower.x(),flower.y()-1,flower.z());
            assertTrue(below==HabitatBlock.GRASS || below==HabitatBlock.SOIL,"flowers cannot stand on pond water or carved AIR");
        }
    }

    @Test void streamDescendsOnlyWhereActualGroundProvidesGentleGradient() {
        TrailNetwork.SampleSource base=(x,y,z)->{
            int surface=FLOOR+Math.floorDiv(50-z,3);
            return new TerrainSample.Known(y>surface?TerrainMaterial.AIR:y==surface?TerrainMaterial.GRASS:y>=surface-2?TerrainMaterial.SOIL:TerrainMaterial.ROCK);
        };
        var plan=HabitatOverlay.plan(base,List.of(patch("a",0,0)));
        var water=plan.placements().stream().filter(p->p.block()==HabitatBlock.WATER).toList();
        assertTrue(water.size()>9);
        assertTrue(water.stream().map(HabitatOverlay.Placement::y).distinct().count()>1,"actual incline yields descending water");
        for(var p:water){
            int surface=FLOOR+Math.floorDiv(50-p.z(),3);
            assertTrue(p.y()<surface && surface-p.y()<=3,"bounded recessed channel");
            assertTrue(solid(resolved(plan,base,p.x(),p.y()-1,p.z())));
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
                var neighbor=resolved(plan,base,p.x()+d[0],p.y(),p.z()+d[1]);
                assertTrue(solid(neighbor) || neighbor==HabitatBlock.WATER
                        || resolved(plan,base,p.x()+d[0],p.y()-1,p.z()+d[1])==HabitatBlock.WATER,"natural bank or one-block downstream descent");
            }
        }
    }

    @Test void protectedRouteNeverGetsWritesEvenWhenItCrossesFeatureCandidates() {
        HabitatOverlay.ProtectedCells route=(x,y,z)->z>=8 && z<=31;
        var plan=HabitatOverlay.plan(flat(),List.of(patch("a",0,0)),route);
        assertFalse(plan.placements().isEmpty());
        assertTrue(plan.placements().stream().noneMatch(p->route.contains(p.x(),p.y(),p.z())));
        assertFalse(plan.summary().rejections().isEmpty());
    }

    @Test void unknownOrUnsupportedVoidProducesExplicitRejectionsWithoutWrites() {
        TrailNetwork.SampleSource unknown=(x,y,z)->new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER);
        var plan=HabitatOverlay.plan(unknown,List.of(patch("a",0,0)));
        assertTrue(plan.placements().isEmpty());
        assertFalse(plan.summary().rejections().isEmpty());
        TrailNetwork.SampleSource thin=(x,y,z)->new TerrainSample.Known(y==FLOOR?TerrainMaterial.GRASS:TerrainMaterial.AIR);
        var unsupported=HabitatOverlay.plan(thin,List.of(patch("a",0,0)));
        assertTrue(unsupported.placements().isEmpty());
        assertFalse(unsupported.summary().rejections().isEmpty());
    }

    @Test void planAndCubeSamplesAreIndependentOfPatchOrderAndReturnedListsAreSealed() {
        var a=patch("a",-80,-80);var b=patch("b",80,80);
        var first=HabitatOverlay.plan(flat(),List.of(a,b));var second=HabitatOverlay.plan(flat(),List.of(b,a));
        assertFalse(first.placements().isEmpty());assertEquals(first.placements(),second.placements());
        for(var p:first.placements())assertEquals(Optional.of(p.block()),first.sample(p.x(),p.y(),p.z()));
        assertThrows(UnsupportedOperationException.class,()->first.placements().clear());
        assertThrows(UnsupportedOperationException.class,()->first.summary().rejections().clear());
        assertEquals(Optional.empty(),first.sample(Integer.MIN_VALUE,FLOOR,Integer.MAX_VALUE));
    }

    @Test void ruinHasSupportedFootprintAndKnownHeadroomBeforePlacement() {
        var plan=HabitatOverlay.plan(flat(),List.of(patch("a",0,0)));
        var ruin=plan.placements().stream().filter(p->p.featureId().contains("ruin")).toList();
        assertFalse(ruin.isEmpty());
        assertTrue(ruin.stream().anyMatch(p->p.block()==HabitatBlock.COBBLE && p.y()==FLOOR+1));
        assertTrue(ruin.stream().allMatch(p->p.y()<=FLOOR+4));
        TrailNetwork.SampleSource roof=(x,y,z)->y==FLOOR+2?new TerrainSample.Known(TerrainMaterial.ROCK):flat().sample(x,y,z);
        var rejected=HabitatOverlay.plan(roof,List.of(patch("a",0,0)));
        assertFalse(rejected.placements().stream().anyMatch(p->p.featureId().contains("ruin")));
        assertFalse(rejected.summary().rejections().isEmpty());
    }

    @Test void boundsAndDuplicateIdsRejectBeforeCallingBaseSampler() {
        TrailNetwork.SampleSource forbidden=(x,y,z)->{throw new AssertionError("must preflight");};
        assertThrows(IllegalArgumentException.class,()->HabitatOverlay.plan(forbidden,List.of(new HabitatOverlay.Patch("a",Integer.MAX_VALUE,0,80,80,FLOOR,1))));
        assertThrows(IllegalArgumentException.class,()->HabitatOverlay.plan(forbidden,List.of(new HabitatOverlay.Patch("a",0,0,81,80,FLOOR,1))));
        assertThrows(IllegalArgumentException.class,()->HabitatOverlay.plan(forbidden,List.of(patch("a",0,0),patch("a",80,80))));
    }
    private static boolean solid(HabitatBlock b){return b==HabitatBlock.ROCK || b==HabitatBlock.GRAVEL || b==HabitatBlock.COBBLE || b==HabitatBlock.SOIL || b==HabitatBlock.GRASS;}
    private static HabitatBlock resolved(HabitatOverlay plan,TrailNetwork.SampleSource base,int x,int y,int z){
        return plan.sample(x,y,z).orElseGet(()->HabitatBlock.valueOf(((TerrainSample.Known)base.sample(x,y,z)).material().name()));
    }
}
