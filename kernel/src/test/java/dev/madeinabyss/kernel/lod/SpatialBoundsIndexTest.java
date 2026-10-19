package dev.madeinabyss.kernel.lod;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class SpatialBoundsIndexTest {
    @Test void matchesLinearOracleAcrossNegativeCoordinatesAndBoundaries() {
        var items=new ArrayList<SpatialBoundsIndex.Bounds>();var random=new Random(917);
        for(int i=0;i<4000;i++) {
            double x=random.nextInt(800)-400,y=random.nextInt(800)-20400,z=random.nextInt(800)-400;
            items.add(new SpatialBoundsIndex.Bounds(x,y,z,x+1+random.nextInt(32),y+1+random.nextInt(200),z+1+random.nextInt(32)));
        }
        var index=SpatialBoundsIndex.create(items,128);
        for(int query=0;query<100;query++) {
            double x=random.nextInt(800)-400,y=random.nextInt(800)-20400,z=random.nextInt(800)-400;
            var bounds=new SpatialBoundsIndex.Bounds(x,y,z,x+64,y+100,z+64);
            var expected=new ArrayList<Integer>();
            for(int i=0;i<items.size();i++)if(items.get(i).intersects(bounds))expected.add(i);
            assertArrayEquals(expected.stream().mapToInt(Integer::intValue).toArray(),index.query(bounds,4001).indices());
        }
        var bounds=new SpatialBoundsIndex.Bounds(-16,-20000,-16,0,-19984,0);
        var seam=SpatialBoundsIndex.create(List.of(bounds,new SpatialBoundsIndex.Bounds(0,-20000,-16,16,-19984,0)),1);
        assertArrayEquals(new int[]{0},seam.query(bounds,3).indices());
    }

    @Test void prunesRemoteNodesAndReturnsStableOriginalOrder() {
        var items=new ArrayList<SpatialBoundsIndex.Bounds>();
        for(int i=0;i<8192;i++)items.add(new SpatialBoundsIndex.Bounds(i*8,-20000,0,i*8+4,-19900,4));
        var index=SpatialBoundsIndex.create(items,128);
        var query=new SpatialBoundsIndex.Bounds(8,-20000,0,12,-19900,4);
        var result=index.query(query,10);
        assertArrayEquals(new int[]{1},result.indices());assertTrue(result.testedItems()<=128);
        assertTrue(result.visitedNodes()<index.nodeCount());
        var all=index.query(new SpatialBoundsIndex.Bounds(-1,-20001,-1,65536,-19899,5),9000);
        for(int i=0;i<8192;i++)assertEquals(i,all.indices()[i]);
    }

    @Test void distinguishesExplicitResultCapAndEmptyIndex() {
        var bounds=new SpatialBoundsIndex.Bounds(-1,-1,-1,1,1,1);
        var index=SpatialBoundsIndex.create(List.of(bounds,bounds,bounds),1);
        assertFalse(index.query(bounds,3).overflow());assertTrue(index.query(bounds,2).overflow());
        assertEquals(2,index.query(bounds,2).indices().length);
        assertEquals(0,SpatialBoundsIndex.create(List.of(),128).query(bounds,1).visitedNodes());
    }

    @Test void validatesFiniteBoundsAndLeafBudget() {
        assertThrows(IllegalArgumentException.class,()->new SpatialBoundsIndex.Bounds(Double.NaN,0,0,1,1,1));
        assertThrows(IllegalArgumentException.class,()->new SpatialBoundsIndex.Bounds(1,0,0,1,1,1));
        assertThrows(IllegalArgumentException.class,()->SpatialBoundsIndex.create(List.of(),0));
        var large=new SpatialBoundsIndex.Bounds(Integer.MIN_VALUE,-22000,Integer.MIN_VALUE,Integer.MAX_VALUE,320,Integer.MAX_VALUE);
        assertEquals(1,SpatialBoundsIndex.create(List.of(large),128).query(large,2).indices().length);
    }
}
