package dev.madeinabyss.kernel.terrain.natural;
import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DenseEcologyOverlayTest {
    private static TerrainSample plane(int x,int y,int z){return new TerrainSample.Known(y==0?TerrainMaterial.GRASS:y<0?TerrainMaterial.ROCK:TerrainMaterial.AIR);}
    @Test void denserMixedGrovesRemainRootedAndDeterministicAcrossSignedCells() {
        var dense=new DenseEcologyOverlay(827,DenseEcologyOverlayTest::plane,(x,y,z)->false,(x,y,z)->true);
        var old=new GlobalEcologyOverlay(827,DenseEcologyOverlayTest::plane,(x,y,z)->false,(x,y,z)->true);
        int trees=0,oldTrees=0;var species=new java.util.HashSet<NaturalBlock>();int[] quadrants=new int[4];
        for(int x=-128;x<128;x++)for(int z=-128;z<128;z++) {
            var a=dense.sampleOverride(x,1,z);assertEquals(a,dense.sampleOverride(x,1,z));
            if(a.isPresent()&&a.get().name().endsWith("LOG")){trees++;species.add(a.get());quadrants[(x<0?0:2)+(z<0?0:1)]++;assertEquals(TerrainMaterial.GRASS,((TerrainSample.Known)plane(x,0,z)).material());}
            if(old.sampleOverride(x,1,z).filter(b->b.name().endsWith("LOG")).isPresent())oldTrees++;
        }
        assertTrue(trees>oldTrees*2,"dense="+trees+" old="+oldTrees);assertTrue(species.size()>=2);
        for(int n:quadrants)assertTrue(n>0);assertTrue(dense.cachedPlanCount()<=1024);
    }
    @Test void routesUnknownAndFloatingSupportsNeverReceivePlants() {
        var protectedAll=new DenseEcologyOverlay(827,DenseEcologyOverlayTest::plane,(x,y,z)->true,(x,y,z)->true);
        var unknown=new DenseEcologyOverlay(827,(x,y,z)->new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER),(x,y,z)->false,(x,y,z)->true);
        var floating=new DenseEcologyOverlay(827,(x,y,z)->new TerrainSample.Known(y==0?TerrainMaterial.GRASS:TerrainMaterial.AIR),(x,y,z)->false,(x,y,z)->true);
        for(int x=-48;x<48;x++)for(int z=-48;z<48;z++) {
            assertTrue(protectedAll.sampleOverride(x,1,z).isEmpty());assertTrue(unknown.sampleOverride(x,1,z).isEmpty());
            assertTrue(floating.sampleOverride(x,1,z).isEmpty());
        }
    }
    @Test void denserWallVinesRemainAttachedAndFarQueriesDoNotSampleTheSource() {
        var wall=new DenseEcologyOverlay(827,(x,y,z)->new TerrainSample.Known(x<=0?TerrainMaterial.ROCK:TerrainMaterial.AIR),(x,y,z)->false,(x,y,z)->true);
        int vines=0;
        for(int y=-160;y<0;y++)for(int z=-64;z<64;z++) {
            var v=wall.sampleOverride(1,y,z);if(v.isPresent()&&v.get().name().startsWith("VINE")){assertEquals(NaturalBlock.VINE_WEST,v.get());vines++;}
        }
        assertTrue(vines>500);var calls=new java.util.concurrent.atomic.AtomicInteger();
        var pruned=new DenseEcologyOverlay(827,(x,y,z)->{calls.incrementAndGet();return plane(x,y,z);},(x,y,z)->false,(x,y,z)->false);
        for(int x=-64;x<64;x++)assertTrue(pruned.sampleOverride(x,500,0).isEmpty());assertEquals(0,calls.get());
    }
}
