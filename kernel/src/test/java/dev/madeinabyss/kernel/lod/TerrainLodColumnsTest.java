package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static dev.madeinabyss.kernel.terrain.TerrainMaterial.*;

class TerrainLodColumnsTest {
    private TerrainSample known(TerrainMaterial material) { return new TerrainSample.Known(material); }

    // Catches merging across air/material changes, or using tile starts instead of lower middles.
    @Test void boundedTilesKeepDistinctVerticalIntervalsAndOmitAir() {
        var calls = new java.util.ArrayList<String>();
        TrailNetwork.SampleSource source = (x,y,z) -> {
            calls.add(x+","+y+","+z);
            return known(switch(y) { case -4,-3,0 -> ROCK; case -2 -> SOIL; case -1 -> GRASS; default -> AIR; });
        };
        var result = TerrainLodColumns.build(source,
                new TerrainLodColumns.Bounds(-3,-4,-2,1,2,-1), new TerrainLodColumns.Parameters(4,14,8));
        assertEquals(14,result.sampledVoxels());
        assertEquals(List.of(
                new TerrainLodColumns.Box(-3,-4,-2,1,-2,0,ROCK),
                new TerrainLodColumns.Box(-3,-2,-2,1,-1,0,SOIL),
                new TerrainLodColumns.Box(-3,-1,-2,1,0,0,GRASS),
                new TerrainLodColumns.Box(-3,0,-2,1,1,0,ROCK),
                new TerrainLodColumns.Box(1,-4,-2,2,-2,0,ROCK),
                new TerrainLodColumns.Box(1,-2,-2,2,-1,0,SOIL),
                new TerrainLodColumns.Box(1,-1,-2,2,0,0,GRASS),
                new TerrainLodColumns.Box(1,0,-2,2,1,0,ROCK)),result.boxes());
        assertEquals("-2,-4,-2",calls.get(0));
        assertEquals("1,2,-2",calls.get(13));
        assertThrows(UnsupportedOperationException.class,()->result.boxes().clear());
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->TerrainLodColumns.build(source,
                new TerrainLodColumns.Bounds(-3,-4,-2,1,2,-1),new TerrainLodColumns.Parameters(4,14,7)));
    }

    // Catches UNKNOWN-as-AIR and losing the failed global signed coordinate after earlier boxes.
    @Test void unavailableRefusesEntireSceneWithExactCoordinate() {
        var failure = assertThrows(TerrainLodColumns.UnknownSampleException.class,()->TerrainLodColumns.build(
                (x,y,z)-> y==-5000 ? new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER)
                        : known(y==-5001 ? SOIL : ROCK),
                new TerrainLodColumns.Bounds(-7,-5002,19,-7,-4998,19),new TerrainLodColumns.Parameters(1,5,5)));
        assertEquals(-7,failure.x()); assertEquals(-5000,failure.y()); assertEquals(19,failure.z());
        assertEquals(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER,failure.reason());
    }

    // Catches int endpoint wrap, midpoint sign bugs, workload multiplication overflow and late preflight.
    @Test void extremeEndpointsRemainGlobalAndImpossibleWorkIsRejectedBeforeSampling() {
        var result=TerrainLodColumns.build((x,y,z)->known(ROCK),
                new TerrainLodColumns.Bounds(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,
                        Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE),new TerrainLodColumns.Parameters(64,1,1));
        assertEquals(List.of(new TerrainLodColumns.Box(2147483647L,2147483647L,-2147483648L,
                2147483648L,2147483648L,-2147483647L,ROCK)),result.boxes());
        var calls=new AtomicInteger();
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->TerrainLodColumns.build((x,y,z)-> {
            calls.incrementAndGet();return known(AIR);
        },new TerrainLodColumns.Bounds(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,
                Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE),new TerrainLodColumns.Parameters(1,Long.MAX_VALUE,1)));
        assertEquals(0,calls.get());
        assertThrows(IllegalArgumentException.class,()->new TerrainLodColumns.Parameters(65,1,1));
        assertThrows(IllegalArgumentException.class,()->new TerrainLodColumns.Parameters(0,1,1));
        assertThrows(IllegalArgumentException.class,()->new TerrainLodColumns.Parameters(1,0,1));
        assertThrows(IllegalArgumentException.class,()->new TerrainLodColumns.Parameters(1,1,0));
        assertThrows(IllegalArgumentException.class,()->new TerrainLodColumns.Bounds(1,0,0,0,0,0));
    }

    // Catches scalar heightmaps filling the cavity and 12-bit Y conversion on the real v5 habitat.
    @Test void actualV5HabitatPreservesGrassSoilAndOpenCavityAtGlobalDepth() {
        var sampler=new ParametricTerrainSampler(new TerrainParameters(20261003,
                new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
                new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),
                new TerrainParameters.Noise(140,20,60,70)));
        var bounds=new TerrainLodColumns.Bounds(-590,-920,80,-586,-850,84);
        var params=new TerrainLodColumns.Parameters(1,1775,1000);
        var result=TerrainLodColumns.build(sampler::sample,bounds,params);
        assertEquals(result,TerrainLodColumns.build(sampler::sample,bounds,params));
        assertEquals(1775,result.sampledVoxels());
        assertTrue(result.boxes().stream().anyMatch(b->b.material()==GRASS));
        assertTrue(result.boxes().stream().anyMatch(b->b.material()==SOIL));
        assertEquals(known(AIR),sampler.sample(-590,-864,80));
        assertTrue(result.boxes().stream().noneMatch(b->b.minX()<=-590 && b.maxXExclusive()>-590
                && b.minZ()<=80 && b.maxZExclusive()>80 && b.minY()<=-864 && b.maxYExclusive()>-864));
        assertTrue(result.boxes().stream().allMatch(b->b.minY()>=-920 && b.maxYExclusive()<=-849));
        for(var box:result.boxes()) for(long y=box.minY();y<box.maxYExclusive();y++)
            assertEquals(known(box.material()),sampler.sample((int)box.minX(),(int)y,(int)box.minZ()));
    }
}
