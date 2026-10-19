package dev.madeinabyss.kernel.terrain.natural;
import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RetainedWaterSamplerTest {
    private static TerrainSample plane(int x,int y,int z){return new TerrainSample.Known(y==0?TerrainMaterial.GRASS:y<0?TerrainMaterial.ROCK:TerrainMaterial.AIR);}
    @Test void seededFinitePoolsHaveClosedBanksFloorsAndKnownHeadroomAcrossNegativeCells() {
        var water=new RetainedWaterSampler(827,RetainedWaterSamplerTest::plane,(x,y,z)->false,(x,y,z)->true);
        int count=0;var sites=new java.util.HashSet<String>();
        for(int x=-144;x<144;x++)for(int z=-144;z<144;z++)for(int y=-1;y<=0;y++) {
            var sample=water.sample(x,y,z);assertEquals(sample,water.sample(x,y,z));
            if(sample instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.WATER) {
                count++;var site=water.siteAt(x,y,z).orElseThrow();sites.add(site.x()+","+site.y()+","+site.z());
                var floor=water.sample(x,y-1,z);assertTrue(floor instanceof TerrainSample.Known);
                assertNotEquals(TerrainMaterial.AIR,((TerrainSample.Known)floor).material());
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})assertNotEquals(TerrainMaterial.AIR,((TerrainSample.Known)water.sample(x+d[0],y,z+d[1])).material());
                assertEquals(TerrainMaterial.AIR,((TerrainSample.Known)water.sample(x,1,z)).material());
            }
        }
        assertTrue(count>100);assertTrue(sites.size()>5);assertTrue(water.cachedPlanCount()<=1024);
    }
    @Test void unsupportedOpenRimsAndProtectedFeaturesRejectWholePool() {
        var empty=new RetainedWaterSampler(827,(x,y,z)->new TerrainSample.Known(TerrainMaterial.AIR),(x,y,z)->false,(x,y,z)->true);
        var guarded=new RetainedWaterSampler(827,RetainedWaterSamplerTest::plane,(x,y,z)->true,(x,y,z)->true);
        for(int x=-48;x<48;x++)for(int z=-48;z<48;z++)assertEquals(plane(x,0,z),guarded.sample(x,0,z));
        for(int x=-48;x<48;x++)for(int z=-48;z<48;z++)assertEquals(new TerrainSample.Known(TerrainMaterial.AIR),empty.sample(x,0,z));
    }
    @Test void aSingleMissingDeepFloorRejectsTheWholeFeatureAndClippedBandNeverTruncatesWater() {
        var original=new RetainedWaterSampler(827,RetainedWaterSamplerTest::plane,(x,y,z)->false,(x,y,z)->true);
        RetainedWaterSampler.PoolSite chosen=null;
        for(int x=-144;x<144&&chosen==null;x++)for(int z=-144;z<144&&chosen==null;z++)chosen=original.siteAt(x,0,z).orElse(null);
        assertNotNull(chosen);var site=chosen;
        var damaged=new RetainedWaterSampler(827,(x,y,z)->x==site.x()&&z==site.z()&&y==-2?new TerrainSample.Known(TerrainMaterial.AIR):plane(x,y,z),(x,y,z)->false,(x,y,z)->true);
        var clipped=new RetainedWaterSampler(827,RetainedWaterSamplerTest::plane,(x,y,z)->false,(x,y,z)->!(x==site.x()&&y==-1&&z==site.z()));
        for(int x=site.x()-12;x<=site.x()+12;x++)for(int z=site.z()-12;z<=site.z()+12;z++) {
            assertEquals(plane(x,0,z),damaged.sample(x,0,z));assertEquals(plane(x,0,z),clipped.sample(x,0,z));
        }
    }
}
