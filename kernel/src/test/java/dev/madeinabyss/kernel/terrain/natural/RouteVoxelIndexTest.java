package dev.madeinabyss.kernel.terrain.natural;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteVoxelIndexTest {
    @Test void sparseSignedCellsRejectEmptySpaceAndPreserveOccupiedValues(){
        var entries=new HashMap<SupportedCaveTerrainSampler.Foot,Integer>();
        entries.put(new SupportedCaveTerrainSampler.Foot(-33,-65,-17),0);
        entries.put(new SupportedCaveTerrainSampler.Foot(-1,-33,0),32767);
        entries.put(new SupportedCaveTerrainSampler.Foot(32,-1,31),7);
        var index=new RouteVoxelIndex(entries);
        for(var e:entries.entrySet()){
            var q=e.getKey();assertTrue(index.mayContain(q.x(),q.y(),q.z()));
            assertEquals((int)e.getValue(),index.get(q.x(),q.y(),q.z()));
        }
        assertFalse(index.mayContain(-17,-49,-1),"An empty cell inside the route AABB must avoid the full hash lookup");
        assertFalse(index.mayContain(Integer.MIN_VALUE,-49,Integer.MAX_VALUE),"Outside signed AABB is certain empty space");
        assertTrue(index.occupancyCellCount()>0&&index.occupancyCellCount()<=RouteVoxelIndex.MAX_OCCUPANCY_CELLS);
        for(int x=-49;x<=49;x++)for(int y=-81;y<=16;y++)for(int z=-33;z<=47;z++){
            int expected=entries.getOrDefault(new SupportedCaveTerrainSampler.Foot(x,y,z),-1);
            assertEquals(expected,index.get(x,y,z));
            if(!index.mayContain(x,y,z))assertEquals(-1,expected,"Certified empty cells have no false negatives");
        }
        entries.clear();assertEquals(32767,index.get(-1,-33,0),"Occupancy and values own immutable input copies");
        var boundary=new RouteVoxelIndex(Map.of(new SupportedCaveTerrainSampler.Foot(-16,-16,-16),1,
                new SupportedCaveTerrainSampler.Foot(16,16,16),2));
        assertTrue(boundary.mayContain(-1,-1,-1));assertFalse(boundary.mayContain(0,0,0),"Signed -1/0 cell split is exact");
    }
    @Test void emptyRoutesCertifyEverySignedCellWithoutAllocation(){
        var index=new RouteVoxelIndex(Map.of());
        for(int x:new int[]{Integer.MIN_VALUE,-17,-16,-1,0,15,16,Integer.MAX_VALUE})
            for(int y:new int[]{Integer.MIN_VALUE,-1,0,Integer.MAX_VALUE})for(int z:new int[]{Integer.MIN_VALUE,-1,0,Integer.MAX_VALUE}){
                assertFalse(index.mayContain(x,y,z));assertEquals(-1,index.get(x,y,z));
            }
        assertEquals(0,index.occupancyCellCount());
    }
    @Test void occupancyLimitAndHugeSignedVolumesFallBackToExactHash(){
        var atLimit=new RouteVoxelIndex(Map.of(new SupportedCaveTerrainSampler.Foot(0,0,0),0,
                new SupportedCaveTerrainSampler.Foot(16*(RouteVoxelIndex.MAX_OCCUPANCY_CELLS-1),0,0),1));
        assertEquals(RouteVoxelIndex.MAX_OCCUPANCY_CELLS,atLimit.occupancyCellCount());
        assertFalse(atLimit.mayContain(16,0,0));assertEquals(0,atLimit.get(0,0,0));
        var overLimit=new RouteVoxelIndex(Map.of(new SupportedCaveTerrainSampler.Foot(0,0,0),0,
                new SupportedCaveTerrainSampler.Foot(16*RouteVoxelIndex.MAX_OCCUPANCY_CELLS,0,0),1));
        assertEquals(0,overLimit.occupancyCellCount());assertTrue(overLimit.mayContain(16,0,0));assertEquals(-1,overLimit.get(16,0,0));
        var huge=new RouteVoxelIndex(Map.of(new SupportedCaveTerrainSampler.Foot(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE),0,
                new SupportedCaveTerrainSampler.Foot(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE),9));
        assertEquals(0,huge.occupancyCellCount());assertTrue(huge.mayContain(0,0,0));
        assertEquals(0,huge.get(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE));
        assertEquals(9,huge.get(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));assertEquals(-1,huge.get(0,0,0));
    }
    @Test void fullSignedCoordinatesAndMissingNeighboursMatchExactMap(){
        var expected=new HashMap<SupportedCaveTerrainSampler.Foot,Integer>();
        int[] bounds={Integer.MIN_VALUE,Integer.MIN_VALUE+1,-65537,-65536,-33,-32,-1,0,31,32,65535,65536,Integer.MAX_VALUE};
        for(int x:bounds)for(int y:bounds)for(int z:bounds)expected.put(new SupportedCaveTerrainSampler.Foot(x,y,z),expected.size());
        var index=new RouteVoxelIndex(expected);
        for(var e:expected.entrySet()){var q=e.getKey();assertEquals((int)e.getValue(),index.get(q.x(),q.y(),q.z()));}
        var random=new Random(774);
        for(int i=0;i<50000;i++){int x=random.nextInt(),y=random.nextInt(),z=random.nextInt();assertEquals(expected.getOrDefault(new SupportedCaveTerrainSampler.Foot(x,y,z),-1).intValue(),index.get(x,y,z));}
        expected.clear();assertEquals(0,index.get(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE),"index owns immutable primitive copy");
    }
    @Test void clusteredDenseVoxelCoordinatesPreserveValuesAndEmptyLookup(){
        var expected=new HashMap<SupportedCaveTerrainSampler.Foot,Integer>();
        for(int x=-32;x<32;x++)for(int y=-65;y<-1;y++)for(int z=-8;z<8;z++)expected.put(new SupportedCaveTerrainSampler.Foot(x,y,z),(x&1)==0?0:32767);
        var index=new RouteVoxelIndex(expected);
        for(int x=-33;x<=32;x++)for(int y=-66;y<=-1;y++)for(int z=-9;z<=8;z++)assertEquals(expected.getOrDefault(new SupportedCaveTerrainSampler.Foot(x,y,z),-1).intValue(),index.get(x,y,z));
        assertEquals(-1,new RouteVoxelIndex(Map.of()).get(0,0,0));
    }
}
