package dev.madeinabyss.kernel.terrain.natural;

import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GlobalEcologyOverlayTest {
    private static TerrainSample known(TerrainMaterial m) { return new TerrainSample.Known(m); }
    private static final GlobalEcologyOverlay.Source FLAT = (x,y,z) -> known(y <= -20 ? TerrainMaterial.GRASS : TerrainMaterial.AIR);
    private GlobalEcologyOverlay flat(long seed) { return new GlobalEcologyOverlay(seed,FLAT,(x,y,z)->false,(x,y,z)->y>=-20&&y<=-8); }
    @Test void globalGroundCoverIsDiverseAndDistributedAcrossSignedQuadrants() {
        var e=flat(827);var kinds=EnumSet.noneOf(NaturalBlock.class);int[] hits=new int[4];int empty=0;
        for(int x=-128;x<128;x++)for(int z=-128;z<128;z++) {
            var b=e.sampleOverride(x,-19,z);if(b.isPresent()){kinds.add(b.get());hits[(x<0?0:2)+(z<0?0:1)]++;}else empty++;
        }
        for(int h:hits)assertTrue(h>100,"all quadrants have cover beyond three finite patches");
        assertTrue(empty>10000,"clearings and sparse ground remain");assertTrue(kinds.size()>=4,"community diversity");
    }
    @Test void vegetationRequiresSupportHeadroomAndKnownAirAndPreservesWater() {
        for(TerrainMaterial m:List.of(TerrainMaterial.AIR,TerrainMaterial.WATER)) {
            var e=new GlobalEcologyOverlay(827,(x,y,z)->known(m),(x,y,z)->false,(x,y,z)->true);
            for(int x=-24;x<24;x++)for(int z=-24;z<24;z++)assertTrue(e.sampleOverride(x,-19,z).isEmpty());
        }
        var unknown=new GlobalEcologyOverlay(827,(x,y,z)->new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER),(x,y,z)->false,(x,y,z)->true);
        assertTrue(unknown.sampleOverride(0,-19,0).isEmpty());
        var roof=new GlobalEcologyOverlay(827,(x,y,z)->known(y==-20||y==-18?TerrainMaterial.GRASS:TerrainMaterial.AIR),(x,y,z)->false,(x,y,z)->true);
        for(int x=-32;x<32;x++)for(int z=-32;z<32;z++)assertTrue(roof.sampleOverride(x,-19,z).isEmpty(),"no ground plant under a one-block roof");
    }
    @Test void masksRoutesAndQueryPruningAreEffective() {
        var n=new AtomicInteger();var e=new GlobalEcologyOverlay(827,(x,y,z)->{n.incrementAndGet();return FLAT.sample(x,y,z);},(x,y,z)->Math.abs(x)<4,(x,y,z)->y>=-20&&y<=-8);
        assertTrue(e.sampleOverride(500,-800,500).isEmpty());assertEquals(0,n.get());
        for(int z=-100;z<100;z++)for(int y=-20;y<-8;y++)assertTrue(e.sampleOverride(0,y,z).isEmpty());
        assertEquals(0,n.get(),"protected route never samples source");
        assertTrue(e.masks(0,-20,0).moisture()>=0&&e.masks(0,-20,0).moisture()<=1);
    }
    @Test void treesAreRootedAndDoNotCrossProtectedFootprints() {
        var e=flat(827);int roots=0;var logs=EnumSet.of(NaturalBlock.OAK_LOG,NaturalBlock.BIRCH_LOG,NaturalBlock.SPRUCE_LOG);
        for(int x=-100;x<100;x++)for(int z=-100;z<100;z++)if(e.sampleOverride(x,-19,z).filter(logs::contains).isPresent()) {
            roots++;assertEquals(TerrainMaterial.GRASS,((TerrainSample.Known)FLAT.sample(x,-20,z)).material());
            assertTrue(e.sampleOverride(x,-18,z).filter(logs::contains).isPresent());
        }
        assertTrue(roots>3,"finite seeded groves across whole plane");
        var protectedAll=new GlobalEcologyOverlay(827,FLAT,(x,y,z)->true,(x,y,z)->true);
        assertTrue(protectedAll.sampleOverride(50,-19,50).isEmpty());
    }
    @Test void wallVinesHaveActualAttachmentDirectionAndPatchGaps() {
        var e=new GlobalEcologyOverlay(827,(x,y,z)->known(x<=0?TerrainMaterial.ROCK:TerrainMaterial.AIR),(x,y,z)->false,(x,y,z)->x>=0&&x<=1);
        int vines=0,gaps=0,moss=0;
        for(int y=-90;y<-10;y++)for(int z=-100;z<100;z++) {
            var b=e.sampleOverride(1,y,z);if(b.isPresent()){assertEquals(NaturalBlock.VINE_WEST,b.get());vines++;}else gaps++;
            if(e.sampleOverride(0,y,z).filter(v->v==NaturalBlock.MOSS).isPresent())moss++;
        }
        assertTrue(vines>20&&gaps>20);assertTrue(moss>20);
    }
    @Test void signedCellSeamsAndCacheEvictionDoNotChangeOutput() {
        var a=flat(827);var b=flat(827);var coordinates=new ArrayList<int[]>();
        for(int x=-35;x<35;x++)for(int y=-20;y<-8;y++)for(int z=-35;z<35;z++)coordinates.add(new int[]{x,y,z});
        Collections.reverse(coordinates);for(var c:coordinates)b.sampleOverride(c[0],c[1],c[2]);
        for(var c:coordinates)assertEquals(a.sampleOverride(c[0],c[1],c[2]),b.sampleOverride(c[0],c[1],c[2]));
        for(int x=-20000;x<20000;x+=16)for(int y=-19;y<-8;y++)a.sampleOverride(x,y,9);
        assertTrue(a.cachedPlanCount()<=GlobalEcologyOverlay.MAX_CACHED_PLANS);
        for(var c:coordinates)assertEquals(b.sampleOverride(c[0],c[1],c[2]),a.sampleOverride(c[0],c[1],c[2]));
    }
    @Test void signedIntegerEdgesNeverWrapToOtherTerrain() {
        var e=flat(827);for(int x:List.of(Integer.MIN_VALUE,Integer.MAX_VALUE))for(int z:List.of(Integer.MIN_VALUE,Integer.MAX_VALUE))assertDoesNotThrow(()->e.sampleOverride(x,-19,z));
    }
    @Test void moistureExposureAndClearingMasksAreCoherentAcrossNegativeCellSeams() {
        var e=flat(827);double min=1,max=0;
        for(int x=-800;x<=800;x++) {
            var a=e.masks(x,-20,-33);var b=e.masks(x+1,-20,-33);
            assertTrue(Math.abs(a.moisture()-b.moisture())<.025);
            assertTrue(Math.abs(a.exposure()-b.exposure())<.025);
            min=Math.min(min,a.moisture());max=Math.max(max,a.moisture());
        }
        assertTrue(max-min>.3,"wet and dry communities differ across global field");
    }
    @Test void allVineOrientationsDescribeTheActualSolidNeighbour() {
        NaturalBlock[] expected={NaturalBlock.VINE_NORTH,NaturalBlock.VINE_SOUTH,NaturalBlock.VINE_EAST,NaturalBlock.VINE_WEST};
        for(int orientation=0;orientation<4;orientation++) {
            final int o=orientation;
            var e=new GlobalEcologyOverlay(827,(x,y,z)->known((o==0?z<=0:o==1?z>=0:o==2?x>=0:x<=0)?TerrainMaterial.ROCK:TerrainMaterial.AIR),(x,y,z)->false,(x,y,z)->true);
            int hits=0;
            for(int y=-96;y<-16;y++)for(int along=-80;along<80;along++) {
                int x=o<2?along:o==2?-1:1,z=o<2?(o==0?1:-1):along;
                var b=e.sampleOverride(x,y,z);if(b.isPresent()){assertEquals(expected[o],b.get());hits++;}
            }
            assertTrue(hits>20,"wall vegetation on each exposure");
        }
    }
    @Test void conservativeEnvelopeClippingOneLeafRejectsTheWholeTree() {
        var original=flat(827);int rootX=0,rootZ=0;boolean found=false;
        for(int x=-100;x<100&&!found;x++)for(int z=-100;z<100&&!found;z++) {
            var kind=original.sampleOverride(x,-19,z);if(kind.isPresent()&&kind.get().name().endsWith("_LOG")){rootX=x;rootZ=z;found=true;}
        }
        assertTrue(found);int height=1;while(original.sampleOverride(rootX,-20+height+1,rootZ).filter(v->v.name().endsWith("_LOG")).isPresent())height++;
        final int clippedX=rootX+3,clippedY=-20+height-2,clippedZ=rootZ;
        assertTrue(original.sampleOverride(clippedX,clippedY,clippedZ).filter(v->v.name().endsWith("_LEAVES")).isPresent());
        var clipped=new GlobalEcologyOverlay(827,FLAT,(x,y,z)->false,(x,y,z)->y>=-20&&y<=-8&&!(x==clippedX&&y==clippedY&&z==clippedZ));
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy=1;dy<=10;dy++) {
            var kind=clipped.sampleOverride(rootX+dx,-20+dy,rootZ+dz);
            assertFalse(kind.filter(v->v.name().endsWith("_LOG")||v.name().endsWith("_LEAVES")).isPresent(),"cannot publish a tree whose complete crown exceeds the conservative envelope");
        }
    }
}
