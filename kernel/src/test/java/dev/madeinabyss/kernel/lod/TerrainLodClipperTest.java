package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TerrainLodClipperTest {
    @Test void movedNativeHoleReclipsRawGeometryAndRestoresOldHoleAcrossNegativeCubeBoundary() {
        var raw=List.of(new TerrainLodColumns.Box(-48,-80,-32,32,-48,16,TerrainMaterial.ROCK));
        var oldHole=new TerrainLodClipper.BoundsLong(-32,-80,-16,0,-48,0);
        var newHole=new TerrainLodClipper.BoundsLong(-16,-80,-16,16,-48,0);
        var oldScene=TerrainLodClipper.subtract(raw,List.of(oldHole),64);
        var refreshed=TerrainLodClipper.subtract(raw,List.of(newHole),64);
        var staleRefresh=TerrainLodClipper.subtract(oldScene,List.of(newHole),64);
        assertNotEquals(refreshed,staleRefresh,"old clipped scene cannot supply newly exposed terrain");
        for(int x=-49;x<=32;x++)for(int y=-81;y<=-48;y++)for(int z=-33;z<=16;z++) {
            boolean original=x>=-48&&x<32&&y>=-80&&y<-48&&z>=-32&&z<16;
            boolean nativeNow=x>=-16&&x<16&&y>=-80&&y<-48&&z>=-16&&z<0;
            assertEquals(original&&!nativeNow?1:0,occupancy(refreshed,x,y,z),"moved exclusion oracle XYZ "+x+","+y+","+z);
        }
        assertEquals(1,occupancy(refreshed,-17,-64,-1),"negative boundary newly exposed by native departure restored");
        assertEquals(0,occupancy(staleRefresh,-17,-64,-1));
        assertEquals(0,occupancy(refreshed,-16,-64,-1),"current native hole starts at exact half-open boundary");
        assertEquals(raw,List.of(new TerrainLodColumns.Box(-48,-80,-32,32,-48,16,TerrainMaterial.ROCK)));
    }
    private static int occupancy(List<TerrainLodColumns.Box> boxes,int x,int y,int z) {
        int count=0;for(var b:boxes)if(x>=b.minX()&&x<b.maxXExclusive()&&y>=b.minY()&&y<b.maxYExclusive()&&z>=b.minZ()&&z<b.maxZExclusive())count++;
        return count;
    }
    // A refresh-sized performance observation: all far geometry must survive the near cube roster exactly.
    @Test void farSceneOutside243NearCubesPreserves40535BoxesWithMeasuredRefreshTime() {
        var input=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<40535;i++) input.add(new TerrainLodColumns.Box(10000L+16L*i,-1286,64,
                10016L+16L*i,321,80,TerrainMaterial.ROCK));
        var exclusions=new ArrayList<TerrainLodClipper.BoundsLong>();
        for(int x=-4;x<=4;x++) for(int y=-1;y<=1;y++) for(int z=-4;z<=4;z++)
            exclusions.add(new TerrainLodClipper.BoundsLong(x*16L,-896+y*16L,z*16L,
                    x*16L+16,-880+y*16L,z*16L+16));
        long started=System.nanoTime();
        var result=TerrainLodClipper.subtract(input,exclusions,40535);
        long elapsed=System.nanoTime()-started;
        assertEquals(input,result);
        System.out.printf("LOD_CLIPPER_FAR_SCENE boxes=%d exclusions=%d elapsedMs=%.3f%n",
                input.size(),exclusions.size(),elapsed/1_000_000.0);
    }

    // Catches the current whole-interval omission when a single near cube intersects a far wall.
    @Test void nearCubeRemovesOnlyItsIntervalFromLongNegativeDepthWall() {
        var wall=new TerrainLodColumns.Box(-16,-1286,64,0,321,80,TerrainMaterial.ROCK);
        var result=TerrainLodClipper.subtract(List.of(wall),
                List.of(new TerrainLodClipper.BoundsLong(-16,-896,64,0,-880,80)),2);
        assertEquals(List.of(
                new TerrainLodColumns.Box(-16,-1286,64,0,-896,80,TerrainMaterial.ROCK),
                new TerrainLodColumns.Box(-16,-880,64,0,321,80,TerrainMaterial.ROCK)),result);
        assertThrows(UnsupportedOperationException.class,result::clear);
    }

    // Catches duplicated slabs, off-by-one seams and overlapping exclusions reintroducing removed voxels.
    @Test void overlappingExclusionsHaveExactDisjointRemainingVoxelOccupancy() {
        var source=new TerrainLodColumns.Box(-3,-8,-4,4,4,3,TerrainMaterial.SOIL);
        var exclusions=List.of(new TerrainLodClipper.BoundsLong(-2,-6,-3,2,1,1),
                new TerrainLodClipper.BoundsLong(0,-3,-1,5,5,4));
        var result=TerrainLodClipper.subtract(List.of(source),exclusions,64);
        assertEquals(result,TerrainLodClipper.subtract(List.of(source),exclusions,64));
        int retained=0;
        for(int x=-4;x<=5;x++) for(int y=-9;y<=5;y++) for(int z=-5;z<=4;z++) {
            boolean original=x>=-3 && x<4 && y>=-8 && y<4 && z>=-4 && z<3;
            boolean cutA=x>=-2 && x<2 && y>=-6 && y<1 && z>=-3 && z<1;
            boolean cutB=x>=0 && x<5 && y>=-3 && y<5 && z>=-1 && z<4;
            int occupancy=0;
            for(var b:result) if(x>=b.minX() && x<b.maxXExclusive() && y>=b.minY() && y<b.maxYExclusive()
                    && z>=b.minZ() && z<b.maxZExclusive()) occupancy++;
            assertEquals(original && !cutA && !cutB ? 1 : 0,occupancy,"XYZ "+x+","+y+","+z);
            retained+=occupancy;
        }
        assertTrue(retained>0);
        assertTrue(result.stream().allMatch(b->b.material()==TerrainMaterial.SOIL));
    }

    // Catches treating touching faces as overlaps and narrowing INT_MAX exclusive endpoints to int.
    @Test void touchingFacesAndExtremeLongEndpointsRemainExact() {
        var source=new TerrainLodColumns.Box(Integer.MIN_VALUE,-5000,2147483646L,
                (long)Integer.MIN_VALUE+2,-4998,2147483648L,TerrainMaterial.WATER);
        var edge=new TerrainLodClipper.BoundsLong((long)Integer.MIN_VALUE+2,-5000,2147483646L,
                (long)Integer.MIN_VALUE+3,-4998,2147483648L);
        assertEquals(List.of(source),TerrainLodClipper.subtract(List.of(source),List.of(edge),1));
        assertEquals(List.of(new TerrainLodColumns.Box(Integer.MIN_VALUE,-5000,2147483646L,
                (long)Integer.MIN_VALUE+2,-4998,2147483647L,TerrainMaterial.WATER)),
                TerrainLodClipper.subtract(List.of(source),List.of(new TerrainLodClipper.BoundsLong(
                        Long.MIN_VALUE,Long.MIN_VALUE,2147483647L,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE)),1));
        assertTrue(TerrainLodClipper.subtract(List.of(source),List.of(new TerrainLodClipper.BoundsLong(
                Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE)),1).isEmpty());
    }

    // Catches silent fragment truncation or mutation of caller-owned scene/exclusions on refusal.
    @Test void fragmentBudgetRefusesAtomicallyAndInputsRemainReusable() {
        var box=new TerrainLodColumns.Box(-2,-2,-2,2,2,2,TerrainMaterial.GRASS);
        var input=new ArrayList<>(List.of(box));
        var hole=new TerrainLodClipper.BoundsLong(-1,-1,-1,1,1,1);
        var exclusions=new ArrayList<>(List.of(hole));
        assertThrows(TerrainLodColumns.BudgetExceededException.class,
                ()->TerrainLodClipper.subtract(input,exclusions,5));
        assertEquals(List.of(box),input); assertEquals(List.of(hole),exclusions);
        assertEquals(6,TerrainLodClipper.subtract(input,exclusions,6).size());
        assertThrows(IllegalArgumentException.class,()->TerrainLodClipper.subtract(input,exclusions,0));
        assertThrows(IllegalArgumentException.class,()->new TerrainLodClipper.BoundsLong(0,0,0,0,1,1));
        assertEquals(List.of(),TerrainLodClipper.subtract(List.of(),exclusions,1));
    }
}
