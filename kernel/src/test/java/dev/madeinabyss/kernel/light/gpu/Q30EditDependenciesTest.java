package dev.madeinabyss.kernel.light.gpu;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.madeinabyss.kernel.light.gpu.Q30EditDependencies.*;

final class Q30EditDependenciesTest {
    private static final long Q=1L<<30;
    private static final List<Direction> DIRS=List.of(new Direction(0,Q,0),new Direction(Q,Q,0),new Direction(-Q,Q,Q),new Direction(Q,Q/2,-Q));
    @Test void negativeDiagonalUsesTheSameAllAxisTieRuleAsExactDda(){
        var p=new Point(-1,0,-1);var d=new Direction(-Q,Q,0);
        assertTrue(rayCrosses(p,d,new Box(-2,1,-1,-1,2,0),8));
        assertFalse(rayCrosses(p,d,new Box(-1,1,-1,0,2,0),8));
        assertFalse(rayCrosses(p,d,new Box(-2,0,-1,-1,1,0),8));
    }
    @Test void verticalRayDoesNotTurnIntoANeighborColumn(){
        var p=new Point(-8,-20,-8);var d=new Direction(0,Q,0);
        assertTrue(rayCrosses(p,d,new Box(-8,-1,-8,-7,0,-7),32));
        assertFalse(rayCrosses(p,d,new Box(-7,-1,-8,-6,0,-7),32));
        assertFalse(rayCrosses(p,d,new Box(-8,-21,-8,-7,-20,-7),32));
    }
    @Test void originInsideChangedCellIsADependencyButTheSkyEndpointIsNot(){
        assertTrue(rayCrosses(new Point(0,0,0),DIRS.get(0),new Box(0,0,0,1,1,1),10));
        assertFalse(rayCrosses(new Point(0,0,0),DIRS.get(0),new Box(0,10,0,1,11,1),10));
        assertFalse(rayCrosses(new Point(0,10,0),DIRS.get(0),new Box(0,10,0,1,11,1),10));
    }
    @Test void globalCoordinatesNearSignedIntegerLimitsDoNotOverflow(){
        int start=Integer.MAX_VALUE-6;
        assertTrue(rayCrosses(new Point(start,0,Integer.MIN_VALUE+3),DIRS.get(1),new Box((long)start+4,4,(long)Integer.MIN_VALUE+3,(long)start+5,5,(long)Integer.MIN_VALUE+4),10));
    }
    @Test void boundedInverseEnumerationEqualsAnIndependentDdaWalk(){
        var grid=new Grid(-32,-32,-32,9,9,9);var random=new Random(0x155beefL);
        for(int attempt=0;attempt<12;attempt++){
            int x=random.nextInt(64)-32,y=random.nextInt(56)-24,z=random.nextInt(64)-32;
            var box=new Box(x,y,z,x+1,y+1,z+1);var expected=new BitSet();
            for(int i=0;i<grid.count();i++){var p=grid.point(i);for(var d:DIRS)if(walk(p,d,box,32)){expected.set(i);break;}}
            assertArrayEquals(expected.stream().toArray(),affectedNodes(grid,List.of(box),DIRS,32),"inverse enumeration must neither miss nor add nodes");
        }
    }
    @Test void inverseEnumerationCoalescesOverlappingDirtyRegions(){
        var grid=new Grid(-16,-16,-16,5,5,5);var box=new Box(-1,-1,-1,1,1,1);
        assertArrayEquals(affectedNodes(grid,List.of(box),DIRS,24),affectedNodes(grid,List.of(box,box),DIRS,24));
        assertArrayEquals(new int[0],affectedNodes(grid,List.of(),DIRS,24));
    }
    @Test void removingAnUpperOccluderUsesTheOldHigherEndpointForInvalidation(){
        var grid=new Grid(0,312,0,1,4,1);var dirty=new Box(0,350,0,1,351,1);
        assertArrayEquals(new int[]{0,1,2,3},affectedNodes(grid,List.of(dirty),List.of(DIRS.get(0)),352));
        assertArrayEquals(new int[0],affectedNodes(grid,List.of(dirty),List.of(DIRS.get(0)),320));
    }
    /** Independent voxel stepping, not the slab/intersection or inverse-enumeration implementation. */
    private static boolean walk(Point origin,Direction direction,Box box,int sky){
        int[] p={origin.x(),origin.y(),origin.z()};long[] d={direction.x(),direction.y(),direction.z()},n={1,1,1};
        for(int visited=0;visited<4096&&p[1]<sky;visited++){
            if(p[0]>=box.minX()&&p[0]<box.maxX()&&p[1]>=box.minY()&&p[1]<box.maxY()&&p[2]>=box.minZ()&&p[2]<box.maxZ())return true;
            int axis=-1;for(int a=0;a<3;a++)if(d[a]!=0&&(axis<0||n[a]*Math.abs(d[axis])<n[axis]*Math.abs(d[a])))axis=a;
            if(axis<0)throw new AssertionError();long numerator=n[axis],denominator=Math.abs(d[axis]);boolean[] ties=new boolean[3];
            for(int a=0;a<3;a++)ties[a]=d[a]!=0&&n[a]*denominator==numerator*Math.abs(d[a]);
            for(int a=0;a<3;a++)if(ties[a]){p[a]+=Long.signum(d[a]);n[a]+=2;}
        }
        return false;
    }
}
