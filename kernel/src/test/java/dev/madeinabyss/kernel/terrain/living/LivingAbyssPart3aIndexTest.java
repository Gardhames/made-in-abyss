package dev.madeinabyss.kernel.terrain.living;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LivingAbyssPart3aIndexTest {
    private static Class<?> type(String s){return assertDoesNotThrow(()->Class.forName("dev.madeinabyss.kernel.terrain.living."+s));}
    @Test void bakedVoxelIndexKeepsNegativeCoordinatesAndBothSidesOfCubeSeams()throws Exception{
        var b=type("LivingAbyssVoxelPlan$Builder").getConstructor().newInstance();var put=b.getClass().getMethod("put",int.class,int.class,int.class,int.class);
        int[][] pts={{-17,-33,-16},{-16,-32,-17},{-1,-1,-1},{0,0,0},{15,15,15},{16,16,16}};
        for(int i=0;i<pts.length;i++)put.invoke(b,pts[i][0],pts[i][1],pts[i][2],i+1);
        var baked=b.getClass().getMethod("freeze").invoke(b);var at=baked.getClass().getMethod("at",int.class,int.class,int.class);
        for(int i=pts.length-1;i>=0;i--)assertEquals(i+1,at.invoke(baked,pts[i][0],pts[i][1],pts[i][2]));
        assertEquals(0,at.invoke(baked,-18,-33,-16));
        put.invoke(b,-17,-33,-16,30);assertEquals(1,at.invoke(baked,-17,-33,-16),"builder cannot mutate the frozen index");
        var clear=assertDoesNotThrow(()->b.getClass().getMethod("clear"));clear.invoke(b);
        for(int i=0;i<pts.length;i++)assertEquals(i+1,at.invoke(baked,pts[i][0],pts[i][1],pts[i][2]),"releasing builder memory cannot erase the immutable index");
    }
    @Test void blueNoisePlacementRespectsMasksAndMinimumDistanceAcrossSignedCellBoundaries()throws Exception{
        var points=new ArrayList<int[]>();for(int x=-40;x<40;x++)for(int z=-30;z<30;z++)if(x<-4||x>4)points.add(new int[]{x,-500,z});
        var method=type("LivingAbyssPoisson").getMethod("place",List.class,double.class,long.class,int.class);
        @SuppressWarnings("unchecked") var actual=(List<int[]>)method.invoke(null,points,7.5,-20261003L,500);
        assertTrue(actual.size()>25);for(var q:actual)assertTrue(q[0]<-4||q[0]>4,"no occupied clearing");
        for(int i=0;i<actual.size();i++)for(int j=0;j<i;j++)assertTrue(Math.hypot(actual.get(i)[0]-actual.get(j)[0],actual.get(i)[2]-actual.get(j)[2])>=7.5,"Poisson minimum spacing");
        @SuppressWarnings("unchecked") var again=(List<int[]>)method.invoke(null,points,7.5,-20261003L,500);assertEquals(actual.size(),again.size());for(int i=0;i<actual.size();i++)assertArrayEquals(actual.get(i),again.get(i));
    }
    @Test void ruinStairUsesOneBlockRisersTwoBlockTreadsAndDecayAtTheFoot()throws Exception{
        var t=type("LivingAbyssRuinStair");var build=t.getMethod("build",double[].class);double[] prof=new double[64];for(int i=0;i<prof.length;i++)prof[i]=-520+i*.37+Math.sin(i*.4)*.3;
        int[] stair=(int[])build.invoke(null,(Object)prof);int previousRise=-2,rises=0;
        for(int i=1;i<stair.length;i++){assertTrue(stair[i]>=stair[i-1]&&stair[i]<=stair[i-1]+1);if(stair[i]>stair[i-1]){assertTrue(i-previousRise>=2);previousRise=i;rises++;}}
        assertTrue(rises>15);var decay=t.getMethod("decay",double.class);assertEquals(1.0,(double)decay.invoke(null,0.0));assertEquals(0.0,(double)decay.invoke(null,1.0));assertEquals(.217637640824031,(double)decay.invoke(null,.5),1e-12);
    }
}
