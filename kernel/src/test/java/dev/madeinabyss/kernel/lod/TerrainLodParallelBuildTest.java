package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainLodParallelBuildTest {
    @Test void negativeRaggedBoundsSplitOnlyAtOriginalTileEdges() {
        var b=new TerrainLodColumns.Bounds(-23,-17,-9,19,5,13);
        var slabs=TerrainLodParallelBuild.splitX(b,8,4);
        assertEquals(4,slabs.size());int next=b.minX();
        for(var s:slabs) {
            assertEquals(next,s.minX());assertEquals(0,Math.floorMod(s.minX()-b.minX(),8));
            assertEquals(b.minY(),s.minY());assertEquals(b.maxYInclusive(),s.maxYInclusive());
            assertEquals(b.minZ(),s.minZ());assertEquals(b.maxZInclusive(),s.maxZInclusive());
            next=s.maxXInclusive()+1;
        }
        assertEquals(20,next);
    }
    @Test void serialEqualityAtNegativeAndExtremeCoordinatesAndUnevenBoxDistribution() {
        var executor=Executors.newFixedThreadPool(4);
        TrailNetwork.SampleSource source=(x,y,z)->new TerrainSample.Known(y<Math.floorMod(x+z,7)-10?TerrainMaterial.ROCK:TerrainMaterial.AIR);
        try {
            for(var b:List.of(new TerrainLodColumns.Bounds(-23,-17,-9,19,5,13),
                    new TerrainLodColumns.Bounds(Integer.MAX_VALUE-40,-16,Integer.MIN_VALUE,Integer.MAX_VALUE,16,Integer.MIN_VALUE+20))) {
                var p=new TerrainLodColumns.Parameters(8,100000,100000);
                assertEquals(TerrainLodColumns.build(source,b,p),TerrainLodParallelBuild.build(source,b,p,4,executor));
            }
        } finally {executor.shutdownNow();}
    }
    @Test void aggregateBoxBudgetCannotBeMultipliedByWorkersAndUnknownIsRefused() {
        var executor=Executors.newFixedThreadPool(4);var b=new TerrainLodColumns.Bounds(-24,-5,-8,23,5,7);
        try {
            assertThrows(TerrainLodColumns.BudgetExceededException.class,()->TerrainLodParallelBuild.build(
                    (x,y,z)->new TerrainSample.Known(TerrainMaterial.ROCK),b,new TerrainLodColumns.Parameters(8,100000,10),4,executor));
            assertThrows(TerrainLodColumns.UnknownSampleException.class,()->TerrainLodParallelBuild.build(
                    (x,y,z)->new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER),b,
                    new TerrainLodColumns.Parameters(8,100000,100),4,executor));
            var calls=new AtomicInteger();
            assertThrows(TerrainLodColumns.BudgetExceededException.class,()->TerrainLodParallelBuild.build(
                    (x,y,z)->{calls.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.ROCK);},b,
                    new TerrainLodColumns.Parameters(8,1,100),4,executor));
            assertEquals(0,calls.get());
        } finally {executor.shutdownNow();}
    }
}
