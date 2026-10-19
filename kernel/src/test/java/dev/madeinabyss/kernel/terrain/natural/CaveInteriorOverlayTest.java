package dev.madeinabyss.kernel.terrain.natural;
import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CaveInteriorOverlayTest {
    private static TerrainSample value(TerrainMaterial m){return new TerrainSample.Known(m);}
    private static TerrainSample hall(int x,int y,int z){return value(y<=0||y>=7||x<=0?TerrainMaterial.ROCK:TerrainMaterial.AIR);}
    private static CaveInteriorOverlay overlay(long seed,CaveInteriorOverlay.Source source,double entrance,boolean passage){return new CaveInteriorOverlay(seed,source,(x,y,z)->false,(x,y,z)->new CaveInteriorOverlay.Feature(true,entrance,passage));}
    @Test void darkHallHasAttachedMineralFloorWallCeilingAndNoGreen(){
        var s=overlay(827,CaveInteriorOverlayTest::hall,80,false);var kinds=new java.util.HashSet<CaveInteriorOverlay.Decoration>();
        for(int x=0;x<96;x++)for(int z=-96;z<96;z++)for(int y=0;y<=7;y++){
            var v=s.sampleOverride(x,y,z);if(v.isEmpty())continue;var b=v.get();kinds.add(b);
            assertNotEquals(CaveInteriorOverlay.Decoration.MOSS,b);assertNotEquals(CaveInteriorOverlay.Decoration.FERN,b);
            if(b==CaveInteriorOverlay.Decoration.DRIPSTONE_UP)assertEquals(value(TerrainMaterial.ROCK),hall(x,y-1,z));
            if(b==CaveInteriorOverlay.Decoration.DRIPSTONE_DOWN)assertEquals(value(TerrainMaterial.ROCK),hall(x,y+1,z));
        }
        assertTrue(kinds.contains(CaveInteriorOverlay.Decoration.CALCITE));assertTrue(kinds.contains(CaveInteriorOverlay.Decoration.DRIPSTONE_BLOCK));
        assertTrue(kinds.contains(CaveInteriorOverlay.Decoration.DRIPSTONE_UP));assertTrue(kinds.contains(CaveInteriorOverlay.Decoration.DRIPSTONE_DOWN));
        assertTrue(kinds.contains(CaveInteriorOverlay.Decoration.LICHEN_WEST));
    }
    @Test void dampEntranceGreenHasSubstrateAndPassageKeepsAirClear(){
        var a=overlay(827,CaveInteriorOverlayTest::hall,2,false);var path=overlay(827,CaveInteriorOverlayTest::hall,2,true);int green=0;
        for(int x=-64;x<64;x++)for(int z=-64;z<64;z++){
            if(a.sampleOverride(x,0,z).filter(b->b==CaveInteriorOverlay.Decoration.MOSS).isPresent())green++;
            if(a.sampleOverride(x,1,z).filter(b->b==CaveInteriorOverlay.Decoration.FERN).isPresent()){green++;assertEquals(CaveInteriorOverlay.Decoration.MOSS,a.sampleOverride(x,0,z).orElseThrow());}
            for(int y=1;y<7;y++)if(hall(x,y,z).equals(value(TerrainMaterial.AIR)))assertTrue(path.sampleOverride(x,y,z).isEmpty());
        }
        assertTrue(green>30);
    }
    @Test void unknownWaterProtectionAndIntegerEdgesNeverWrapIntoSupports(){
        var unknown=overlay(1,(x,y,z)->new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER),0,false);
        var wet=overlay(1,(x,y,z)->value(TerrainMaterial.WATER),0,false);
        var protectedAll=new CaveInteriorOverlay(1,CaveInteriorOverlayTest::hall,(x,y,z)->true,(x,y,z)->new CaveInteriorOverlay.Feature(true,0,false));
        var floating=overlay(1,(x,y,z)->value(y==0?TerrainMaterial.ROCK:TerrainMaterial.AIR),0,false);
        for(int x=-64;x<64;x++){
            assertTrue(unknown.sampleOverride(x,1,0).isEmpty());assertTrue(wet.sampleOverride(x,1,0).isEmpty());assertTrue(protectedAll.sampleOverride(x,1,0).isEmpty());
            assertTrue(floating.sampleOverride(x,1,0).isEmpty());
        }
        var edge=overlay(1,(x,y,z)->{assertFalse(x==Integer.MIN_VALUE);return value(TerrainMaterial.AIR);},0,false);
        assertTrue(edge.sampleOverride(Integer.MAX_VALUE,1,0).isEmpty());
    }
    @Test void signedOrderAndSeedStayDeterministicAndFloorBasinHasRetainedWalls(){
        var source=(CaveInteriorOverlay.Source)(x,y,z)->value(y<=0?TerrainMaterial.ROCK:TerrainMaterial.AIR);
        var a=overlay(827,source,80,false);var b=overlay(827,source,80,false);var other=overlay(828,source,80,false);int water=0,different=0;
        for(int x=-96;x<96;x++)for(int z=-96;z<96;z++){
            var first=a.sampleOverride(x,0,z);assertEquals(first,b.sampleOverride(x,0,z));if(!first.equals(other.sampleOverride(x,0,z)))different++;
            if(first.filter(v->v==CaveInteriorOverlay.Decoration.WATER).isPresent()){
                water++;for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})assertNotEquals(CaveInteriorOverlay.Decoration.WATER,a.sampleOverride(x+d[0],0,z+d[1]).orElse(null));
            }
        }
        assertTrue(water>0);assertTrue(different>100);
    }
    @Test void floorSpikesNeverGrowOverComposedPoolWater(){
        var a=overlay(827,(x,y,z)->value(y<=0?TerrainMaterial.ROCK:TerrainMaterial.AIR),80,false);int pools=0;
        for(int x=-192;x<192;x++)for(int z=-192;z<192;z++)if(a.sampleOverride(x,0,z).filter(v->v==CaveInteriorOverlay.Decoration.WATER).isPresent()){
            pools++;assertNotEquals(CaveInteriorOverlay.Decoration.DRIPSTONE_UP,a.sampleOverride(x,1,z).orElse(null));
        }
        assertTrue(pools>100);
    }
    @Test void waterOnlyFastPathExactlyMatchesFullDressingAndRejectsNonAnchorsBeforeSource(){
        for(boolean passage:new boolean[]{false,true}){
            var s=overlay(827,CaveInteriorOverlayTest::hall,80,passage);
            for(int x=-32;x<64;x++)for(int z=-32;z<32;z++)for(int y=0;y<8;y++){
                var base=hall(x,y,z);assertEquals(s.sampleOverride(x,y,z,base).filter(v->v==CaveInteriorOverlay.Decoration.WATER).isPresent(),s.waterAt(x,y,z,base));
            }
        }
        var protectedAll=new CaveInteriorOverlay(827,CaveInteriorOverlayTest::hall,(x,y,z)->true,(x,y,z)->new CaveInteriorOverlay.Feature(true,80,false));
        var unknown=new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER);
        for(int x=-32;x<32;x++)for(int z=-32;z<32;z++){
            assertFalse(protectedAll.waterAt(x,0,z,hall(x,0,z)));assertFalse(protectedAll.waterAt(x,0,z,unknown));
        }
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var pruned=new CaveInteriorOverlay(827,(x,y,z)->{calls.incrementAndGet();return hall(x,y,z);},(x,y,z)->false,(x,y,z)->{calls.incrementAndGet();return new CaveInteriorOverlay.Feature(true,80,false);});
        // All jittered anchors are in local8m positions2..5; local0 and1 never host pools.
        for(int x=-32;x<32;x+=8)for(int z=-32;z<32;z+=8)assertFalse(pruned.waterAt(x,0,z,hall(x,0,z)));
        assertEquals(0,calls.get());
    }
}
