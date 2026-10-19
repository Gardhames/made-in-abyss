package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AbyssLodSparseSurfaceTest {
    private static final List<TerrainLodRefinement.Band> BANDS=List.of(new TerrainLodRefinement.Band(384,1),
            new TerrainLodRefinement.Band(768,2),new TerrainLodRefinement.Band(1280,4),new TerrainLodRefinement.Band(1920,8));
    @Test void broadSurfaceDescriptorReachesRealTwoMetreWallWithoutReservingFourHundredMillionEmptyVoxels() {
        var world=new TerrainLodColumns.Bounds(-1280,-1286,-1280,1279,319,1279);
        var descriptor=assertDoesNotThrow(()->AbyssLodSurfacePriority.describe(world,-64,-352,176,BANDS,120_000_000,262144),
                "A geometry-only384/1 tier cannot consume or refuse453M full-volume samples before selecting surfaces");
        var wall=new TerrainLodColumns.Box(-80,-512,768,80,-256,784,TerrainMaterial.ROCK);
        var selected=AbyssLodSurfacePriority.prefix(world,descriptor.geometry(),List.of(wall),List.of(),-60,-338,180,0,0,1,512);
        assertFalse(selected.isEmpty());assertTrue(selected.stream().allMatch(r->r.horizontalStep()==2));
        long actual=0;for(var r:selected){var b=r.bounds();assertTrue(b.minZ()<=783&&b.maxZInclusive()>=768);
            var raw=TerrainLodColumns.build((x,y,z)->new TerrainSample.Known(x>=-80&&x<80&&y>=-512&&y<-256&&z>=768&&z<784
                    ?TerrainMaterial.ROCK:TerrainMaterial.AIR),b,new TerrainLodColumns.Parameters(r.horizontalStep(),r.sampledVoxels(),16384));
            assertFalse(raw.boxes().isEmpty(),"Proxy surface selection reaches actual non-AIR source geometry");actual+=raw.sampledVoxels();}
        assertTrue(actual<120_000_000,"Only selected exact source lattices enter the actual reserve");
    }
    @Test void sparseAdmissionKeepsRealCachedTilesAndDefersOnlyUncachedJobsThatDoNotFit() {
        var jobs=new ArrayList<TerrainLodRefinement.Region>();
        for(int x=-64;x<16;x+=16)jobs.add(new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds(x,-64,-16,x+15,-1,-1),2));
        var cache=new BoundedReuseCache<TerrainLodColumns.Bounds,TerrainLodColumns.Result>(1_000_000,16,r->128+80L*r.boxes().size());
        for(int i=0;i<2;i++){var region=jobs.get(i);cache.put(region.bounds(),TerrainLodColumns.build((x,y,z)->new TerrainSample.Known(
                y==-1?TerrainMaterial.WATER:TerrainMaterial.AIR),region.bounds(),new TerrainLodColumns.Parameters(2,4096,1000)));}
        var admitted=AbyssLodSurfacePriority.admitSamples(jobs,8192,r->cache.get(r.bounds())!=null);
        assertEquals(jobs.subList(0,4),admitted.accepted(),"Cached exact data and the two affordable fresh lattices progress");
        assertEquals(List.of(jobs.get(4)),admitted.deferred());assertEquals(8192,admitted.freshSamples());
        long freshCalls=0;for(var region:admitted.accepted())if(cache.get(region.bounds())==null){
            var raw=TerrainLodColumns.build((x,y,z)->new TerrainSample.Known(y==-1?TerrainMaterial.WATER:TerrainMaterial.AIR),
                    region.bounds(),new TerrainLodColumns.Parameters(2,4096,1000));freshCalls+=raw.sampledVoxels();}
        assertEquals(admitted.freshSamples(),freshCalls,"Reservation describes actual uncached source work, not world-volume metadata");
    }
}
