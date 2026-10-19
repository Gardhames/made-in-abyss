package dev.madeinabyss.kernel.terrain.habitat;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import dev.madeinabyss.kernel.terrain.ParametricTerrainSampler;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HabitatUnderstoryTest {
    private static final int FLOOR=-20000;
    private static HabitatOverlay.Patch patch(int x,int z,long seed) {
        return new HabitatOverlay.Patch("edge",x,z,80,80,FLOOR,seed);
    }
    private static TrailNetwork.SampleSource flat() {
        return (x,y,z)->new TerrainSample.Known(y>FLOOR?TerrainMaterial.AIR:
                y==FLOOR?TerrainMaterial.GRASS:y>=FLOOR-2?TerrainMaterial.SOIL:TerrainMaterial.ROCK);
    }
    private static List<HabitatOverlay.Placement> understory(HabitatOverlay plan) {
        return plan.placements().stream().filter(p->p.featureId().contains("/shrub-") || p.featureId().contains("/rock-group-")).toList();
    }

    @Test void fertileForestMarginsGainLowRootedShrubsAndNaturalStoneGroups() {
        var plan=HabitatOverlay.plan(flat(),List.of(patch(-80,-80,91)));
        var added=understory(plan);
        assertTrue(added.stream().filter(p->p.featureId().contains("/shrub-")).map(HabitatOverlay.Placement::featureId).distinct().count()>=2,"missing low understory");
        assertTrue(added.stream().filter(p->p.featureId().contains("/rock-group-")).map(HabitatOverlay.Placement::featureId).distinct().count()>=2,"missing stone groups");
        assertTrue(added.stream().allMatch(p->p.y()>FLOOR && p.y()<=FLOOR+5),"small margin features leave view open");
        assertTrue(added.stream().noneMatch(p->Math.abs(p.x()+40)<8 && Math.abs(p.z()+40)<8),"walking clearing remains open");
        assertTrue(added.stream().allMatch(p->p.x()+80<26 || p.x()+80>=54),"features stay at forest margins");
        var groups=new HashMap<String,List<HabitatOverlay.Placement>>();
        added.forEach(p->groups.computeIfAbsent(p.featureId(),key->new ArrayList<>()).add(p));
        for(var group:groups.values()) {
            assertConnected(group);
            if(group.get(0).featureId().contains("/shrub-")) {
                assertTrue(group.stream().anyMatch(p->p.block()==HabitatBlock.LOG));
                assertTrue(group.stream().anyMatch(p->p.block()==HabitatBlock.LEAVES));
            } else assertTrue(group.stream().allMatch(p->p.block()==HabitatBlock.ROCK));
            for(var p:group) if(p.block()==HabitatBlock.ROCK && p.y()==FLOOR+1)
                assertEquals(TerrainMaterial.GRASS,((TerrainSample.Known)flat().sample(p.x(),p.y()-1,p.z())).material());
        }
    }

    @Test void supportedMicroreliefAndDenseGrassEnrichFlatGroveWithoutClosingClearing() {
        var plan=HabitatOverlay.plan(flat(),List.of(patch(0,0,91)));
        var relief=plan.placements().stream().filter(p->p.featureId().contains("/microrelief-")).toList();
        assertFalse(relief.isEmpty(),"missing supported ground variation");
        assertTrue(relief.stream().allMatch(p->p.y()>=FLOOR+1 && p.y()<=FLOOR+2));
        assertTrue(relief.stream().noneMatch(p->Math.abs(p.x()-40)<10 && Math.abs(p.z()-40)<10));
        for(var p:relief) assertTrue(p.block()==HabitatBlock.GRASS || p.block()==HabitatBlock.SOIL);
        var grass=plan.placements().stream().filter(p->p.block().name().equals("TALL_GRASS")).toList();
        assertTrue(grass.size()>300,"a grove needs actual dense ground cover");
        for(var p:grass) {
            var support=plan.sample(p.x(),p.y()-1,p.z());
            assertTrue(support.map(b->b==HabitatBlock.GRASS || b==HabitatBlock.SOIL).orElse(p.y()==FLOOR+1));
        }
        assertTrue(plan.summary().trees()>=16,"microrelief must not reject most grove trees");
        var treeTops=plan.placements().stream().filter(p->p.block()==HabitatBlock.LOG && p.featureId().contains("/tree-")).collect(java.util.stream.Collectors.groupingBy(HabitatOverlay.Placement::featureId,java.util.stream.Collectors.maxBy(Comparator.comparingInt(HabitatOverlay.Placement::y))));
        assertTrue(treeTops.values().stream().map(o->o.orElseThrow().y()).distinct().count()>=3,"mixed canopy heights");
    }

    @Test void undergrowthAndRocksAvoidEntireExistingTreeRuinAndWaterColumns() {
        var plan=HabitatOverlay.plan(flat(),List.of(patch(0,0,91)));
        var added=understory(plan);assertFalse(added.isEmpty());
        var prior=plan.placements().stream().filter(p->p.featureId().contains("/tree-") || p.featureId().contains("/ruin") || p.featureId().contains("/pond-stream")).toList();
        for(var p:added) assertTrue(prior.stream().noneMatch(q->q.x()==p.x() && q.z()==p.z()),"no decoration over tree/ruin/water columns");
    }

    @Test void protectedRoutesRejectWholeUnderstoryFeaturesWithoutPartialRemnants() {
        var original=HabitatOverlay.plan(flat(),List.of(patch(0,0,91)));
        var added=understory(original);assertFalse(added.isEmpty());
        var protectedPoint=added.get(0);
        var blocked=HabitatOverlay.plan(flat(),List.of(patch(0,0,91)),
                (x,y,z)->x==protectedPoint.x() && z==protectedPoint.z());
        assertTrue(blocked.placements().stream().noneMatch(p->p.featureId().equals(protectedPoint.featureId())),"atomic rejection of blocked group");
        assertTrue(blocked.summary().rejections().stream().anyMatch(r->r.featureId().equals(protectedPoint.featureId())));
    }

    @Test void marginFeaturesAreDeterministicAcrossSignedCoordinatesAndRespondToSeed() {
        var positive=HabitatOverlay.plan(flat(),List.of(patch(0,0,91)));
        var negative=HabitatOverlay.plan(flat(),List.of(patch(-80,-80,91)));
        var points=understory(positive);assertFalse(points.isEmpty());
        assertEquals(points,understory(negative).stream().map(p->new HabitatOverlay.Placement(p.x()+80,p.y(),p.z()+80,p.block(),p.featureId())).toList());
        assertNotEquals(points,understory(HabitatOverlay.plan(flat(),List.of(patch(0,0,92)))));
        var roots=points.stream().filter(p->p.block()==HabitatBlock.LOG && p.y()==FLOOR+1).toList();
        assertTrue(roots.stream().map(p->Math.floorMod(p.z(),4)).distinct().count()>1,"no regular four-metre grid");
    }

    @Test void actualThreeMeasuredSlopingHabitatsKeepGrovesGroundCoverAndMicrorelief() {
        var sampler=new ParametricTerrainSampler(new TerrainParameters(20261003,
                new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
                new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),
                new TerrainParameters.Noise(140,20,60,70)));
        var patches=List.of(new HabitatOverlay.Patch("west-grove",-609,60,80,80,-884,20261004),
                new HabitatOverlay.Patch("east-wetland",545,-160,80,80,-1128,20261005),
                new HabitatOverlay.Patch("north-clearing",40,-469,80,80,-556,20261006));
        for(var patch:patches) {
            var plan=HabitatOverlay.plan(sampler::sample,List.of(patch));
            assertTrue(plan.summary().trees()>=10,patch.id()+" retains a dense grove on actual slopes");
            assertTrue(plan.placements().stream().filter(p->p.block().name().equals("TALL_GRASS")).count()>200,patch.id()+" has supported grass");
            assertTrue(plan.placements().stream().anyMatch(p->p.featureId().contains("/microrelief-")),patch.id()+" has ground variation");
        }
    }

    private static void assertConnected(List<HabitatOverlay.Placement> group) {
        var remaining=new HashSet<>(group);var queue=new ArrayDeque<HabitatOverlay.Placement>();
        queue.add(group.get(0));remaining.remove(group.get(0));
        while(!queue.isEmpty()) {var current=queue.remove();for(var next:new ArrayList<>(remaining))
            if(Math.abs(next.x()-current.x())+Math.abs(next.y()-current.y())+Math.abs(next.z()-current.z())==1) {remaining.remove(next);queue.add(next);}}
        assertTrue(remaining.isEmpty(),"all group voxels connect to grounded feature");
    }
}
