package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainLodRefinementTest {
    private static final List<TerrainLodRefinement.Band> BANDS=List.of(
            new TerrainLodRefinement.Band(32,1),new TerrainLodRefinement.Band(64,2),
            new TerrainLodRefinement.Band(128,4),new TerrainLodRefinement.Band(256,8));
    private static final TerrainLodColumns.Bounds WORLD=new TerrainLodColumns.Bounds(-720,-1286,-720,719,320,719);

    // Catches global high-resolution sampling and wrong XYZ shell cost, independently hand-derived.
    @Test void boundedFirstLayerDetailCosts3473408SamplesAndKeepsEveryIntegerY() {
        var p=TerrainLodRefinement.plan(WORLD,0,-800,0,BANDS,3_473_408,32);
        assertEquals(3_473_408,p.sampledVoxels());
        assertEquals(new TerrainLodClipper.BoundsLong(-256,-1056,-256,256,-544,256),p.replacement().orElseThrow());
        assertEquals(19,p.regions().size());
        assertEquals(List.of(1,2,4,8),p.regions().stream().map(TerrainLodRefinement.Region::horizontalStep).distinct().toList());
        long calls=0;
        for(var r:p.regions()) {
            var result=TerrainLodColumns.build((x,y,z)->new TerrainSample.Known(TerrainMaterial.AIR),
                    r.bounds(),new TerrainLodColumns.Parameters(r.horizontalStep(),r.sampledVoxels(),1));
            calls+=result.sampledVoxels();
        }
        assertEquals(3_473_408,calls);
    }

    // Catches overlapping shells, holes and choosing horizontal distance without vertical distance.
    @Test void clippedSignedSceneHasExactlyOneRegionPerVoxelWithThreeDimensionalDetail() {
        var world=new TerrainLodColumns.Bounds(-21,-39,-19,21,3,19);
        var p=TerrainLodRefinement.plan(world,-1,-1,-1,List.of(
                new TerrainLodRefinement.Band(16,1),new TerrainLodRefinement.Band(32,2)),1_000_000,20);
        for(int x=-22;x<=22;x++) for(int y=-40;y<=4;y++) for(int z=-20;z<=20;z++) {
            final int xx=x,yy=y,zz=z;
            var hits=p.regions().stream().filter(r->contains(r.bounds(),xx,yy,zz)).toList();
            boolean inside=x>=-21 && x<=15 && y>=-39 && y<=3 && z>=-19 && z<=15;
            assertEquals(inside?1:0,hits.size(),x+","+y+","+z);
            if(inside) assertEquals(x<0 && y<0 && y>=-32 && z<0 ?1:2,hits.get(0).horizontalStep());
        }
        assertEquals(p,TerrainLodRefinement.plan(world,-16,-16,-16,List.of(
                new TerrainLodRefinement.Band(16,1),new TerrainLodRefinement.Band(32,2)),1_000_000,20));
        assertNotEquals(p,TerrainLodRefinement.plan(world,0,-1,-1,List.of(
                new TerrainLodRefinement.Band(16,1),new TerrainLodRefinement.Band(32,2)),1_000_000,20));
        assertThrows(UnsupportedOperationException.class,p.regions()::clear);
    }

    // Catches old16m midpoint losing an actual narrow1m ledge and vertical material boundaries.
    @Test void nearestOneMetreRefinementRecoversNarrowDeepLedgeMissedByStep16() {
        var world=new TerrainLodColumns.Bounds(-16,-900,0,-1,-896,15);
        dev.madeinabyss.kernel.terrain.trail.TrailNetwork.SampleSource source=(x,y,z)->
                new TerrainSample.Known(x==-1 && z==0 ? (y==-898?TerrainMaterial.GRASS:
                        y<-898?TerrainMaterial.ROCK:TerrainMaterial.AIR):TerrainMaterial.AIR);
        assertTrue(TerrainLodColumns.build(source,world,new TerrainLodColumns.Parameters(16,5,10)).boxes().isEmpty());
        var p=TerrainLodRefinement.plan(world,-1,-898,0,BANDS,10_000,32);
        var boxes=new java.util.ArrayList<TerrainLodColumns.Box>();
        for(var r:p.regions()) boxes.addAll(TerrainLodColumns.build(source,r.bounds(),
                new TerrainLodColumns.Parameters(r.horizontalStep(),r.sampledVoxels(),1000)).boxes());
        assertEquals(List.of(new TerrainLodColumns.Box(-1,-900,0,0,-898,1,TerrainMaterial.ROCK),
                new TerrainLodColumns.Box(-1,-898,0,0,-897,1,TerrainMaterial.GRASS)),boxes);
        assertThrows(TerrainLodColumns.UnknownSampleException.class,()->TerrainLodColumns.build(
                (x,y,z)->new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER),
                p.regions().get(0).bounds(),new TerrainLodColumns.Parameters(1,10_000,1000)));
    }

    // Catches intMAX+1 narrowing and negative floor division rounding toward zero.
    @Test void extremeSignedEndpointsStayInsideWorldAndEmptyIntersectionsHaveNoReplacement() {
        var world=new TerrainLodColumns.Bounds(Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,
                Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE);
        var p=TerrainLodRefinement.plan(world,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,BANDS,1,32);
        assertEquals(1,p.sampledVoxels());
        assertEquals(1,p.regions().size());
        assertEquals(world,p.regions().get(0).bounds());
        assertEquals(new TerrainLodClipper.BoundsLong(Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,
                2147483648L,-2147483647L,-2147483647L),p.replacement().orElseThrow());
        var empty=TerrainLodRefinement.plan(new TerrainLodColumns.Bounds(1000,1000,1000,1001,1001,1001),
                0,0,0,BANDS,1,32);
        assertEquals(0,empty.sampledVoxels()); assertTrue(empty.regions().isEmpty()); assertTrue(empty.replacement().isEmpty());
    }

    // Catches truncation, budget multiplication overflow and unsupported nonnested band shapes.
    @Test void refusesInsufficientBudgetsAndInvalidBandsWithoutPartialPlans() {
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->TerrainLodRefinement.plan(WORLD,0,-800,0,BANDS,3_473_407,32));
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->TerrainLodRefinement.plan(WORLD,0,-800,0,BANDS,3_473_408,18));
        assertThrows(IllegalArgumentException.class,()->new TerrainLodRefinement.Band(15,1));
        assertThrows(IllegalArgumentException.class,()->new TerrainLodRefinement.Band(16,3));
        assertThrows(IllegalArgumentException.class,()->TerrainLodRefinement.plan(WORLD,0,0,0,List.of(),1,32));
        assertThrows(IllegalArgumentException.class,()->TerrainLodRefinement.plan(WORLD,0,0,0,List.of(
                new TerrainLodRefinement.Band(32,2),new TerrainLodRefinement.Band(16,4)),1_000_000,32));
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->TerrainLodRefinement.plan(
                new TerrainLodColumns.Bounds(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE),
                0,0,0,List.of(new TerrainLodRefinement.Band(2147483632,1)),Long.MAX_VALUE,32));
    }
    private static boolean contains(TerrainLodColumns.Bounds b,int x,int y,int z) {
        return x>=b.minX() && x<=b.maxXInclusive() && y>=b.minY() && y<=b.maxYInclusive() && z>=b.minZ() && z<=b.maxZInclusive();
    }
}
