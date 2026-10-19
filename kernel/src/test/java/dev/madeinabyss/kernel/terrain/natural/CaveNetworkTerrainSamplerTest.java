package dev.madeinabyss.kernel.terrain.natural;

import dev.madeinabyss.kernel.terrain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaveNetworkTerrainSamplerTest {
    private static final TerrainSample ROCK=new TerrainSample.Known(TerrainMaterial.ROCK);
    private static final TerrainSample AIR=new TerrainSample.Known(TerrainMaterial.AIR);
    private static TerrainParameters parameters(){return new TerrainParameters(20261003,
        new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
        new TerrainParameters.Cavity(500,64,-1286,500,180,170,0),new TerrainParameters.Noise(140,0,0,0));}
    private static TerrainSample source(int x,int y,int z){return Math.hypot(x,z)>=500?ROCK:AIR;}
    private static CaveNetworkTerrainSampler sampler(){return new CaveNetworkTerrainSampler(parameters(),CaveNetworkParameters.proposal(827),CaveNetworkTerrainSamplerTest::source,(x,y,z)->false);}
    @Test void graphHasTrueInteriorLoopsVerticalLinksAndRepeatedPortals(){
        var s=sampler();assertTrue(s.segments().size()>100);assertTrue(s.portals().size()>32);
        int interior=0,vertical=0,diagonal=0,fractures=0;
        for(var edge:s.segments()){
            if(edge.kind()==CaveNetworkTerrainSampler.Kind.FRACTURE){fractures++;continue;}
            if(Math.hypot(edge.a().x(),edge.a().z())>516)interior++;
            if(Math.abs(edge.a().y()-edge.b().y())>3)vertical++;
            if(Math.abs(edge.a().x()-edge.b().x())>1&&Math.abs(edge.a().z()-edge.b().z())>1)diagonal++;
        }
        assertTrue(interior>20);assertTrue(vertical>20);assertTrue(diagonal>20);assertTrue(fractures>100);
    }
    @Test void portalsConnectThroughActualCarvedVoxelsRatherThanDisconnectedDiagnosticNodes(){
        var s=sampler();var first=s.portals().get(0);
        var pair=s.portals().stream().filter(p->p.network()==first.network()).toList();assertTrue(pair.size()>=2);
        var from=entrance(pair.get(0).position());var target=entrance(pair.get(1).position());
        var visited=new HashSet<String>();var queue=new ArrayDeque<int[]>();queue.add(from);boolean found=false;
        while(!queue.isEmpty()&&visited.size()<150000){
            var q=queue.remove();if(!visited.add(Arrays.toString(q)))continue;
            if(Arrays.equals(q,target)){found=true;break;}
            for(var d:NEIGHBOURS){int x=q[0]+d[0],y=q[1]+d[1],z=q[2]+d[2];
                if(Math.abs(x-from[0])>90||Math.abs(y-from[1])>90||Math.abs(z-from[2])>90||Math.hypot(x,z)<499)continue;
                if(s.sample(x,y,z).equals(AIR))queue.add(new int[]{x,y,z});
            }
        }
        assertTrue(found,"two wall portals must share walkable carved interior");
    }
    @Test void carvingIsSubtractiveBoundedAndPreservesMouthUnknownMasksAndWaterBarriers(){
        var s=sampler();var edge=s.segments().stream().filter(e->e.kind()==CaveNetworkTerrainSampler.Kind.CAVE&&Math.hypot(e.a().x(),e.a().z())>510).findFirst().orElseThrow();
        var q=voxel(edge.a());assertEquals(AIR,s.sample(q[0],q[1],q[2]));
        var protectedAll=new CaveNetworkTerrainSampler(parameters(),CaveNetworkParameters.proposal(827),CaveNetworkTerrainSamplerTest::source,(x,y,z)->true);
        assertEquals(ROCK,protectedAll.sample(q[0],q[1],q[2]));
        var wet=new CaveNetworkTerrainSampler(parameters(),CaveNetworkParameters.proposal(827),(x,y,z)->x==q[0]+1&&y==q[1]&&z==q[2]?new TerrainSample.Known(TerrainMaterial.WATER):source(x,y,z),(x,y,z)->false);
        assertEquals(ROCK,wet.sample(q[0],q[1],q[2]));
        var unavailable=new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER);
        assertSame(unavailable,new CaveNetworkTerrainSampler(parameters(),CaveNetworkParameters.proposal(827),(x,y,z)->unavailable,(x,y,z)->false).sample(q[0],q[1],q[2]));
        for(int y:new int[]{-1287,-1286,-2,0,64,100})assertEquals(source(501,y,0),s.sample(501,y,0));
        for(int x=537;x<570;x++)assertEquals(ROCK,s.sample(x,q[1],0));
        for(int x=470;x<498;x++)assertEquals(AIR,s.sample(x,q[1],0));
    }
    @Test void negativeCoordinatesSectionOrderAndIndependentInstancesStayExact(){
        var a=sampler();var b=sampler();var points=new ArrayList<int[]>();var expected=new ArrayList<TerrainSample>();
        for(int x=-537;x<-494;x+=2)for(int z=-32;z<=32;z+=3)for(int y=-260;y<-210;y+=3){points.add(new int[]{x,y,z});expected.add(a.sample(x,y,z));}
        var order=new ArrayList<Integer>();for(int i=0;i<points.size();i++)order.add(i);Collections.shuffle(order,new Random(11));
        for(int i:order){var q=points.get(i);assertEquals(expected.get(i),b.sample(q[0],q[1],q[2]));var f=b.featureAt(q[0],q[1],q[2]);assertTrue(Double.isFinite(f.distance())&&Double.isFinite(f.penetration()));}
        assertEquals(a.segments(),b.segments());
    }
    private static final int[][] NEIGHBOURS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static int[] voxel(CaveNetworkTerrainSampler.Point p){return new int[]{(int)Math.round(p.x()),(int)Math.round(p.y()),(int)Math.round(p.z())};}
    private static int[] entrance(CaveNetworkTerrainSampler.Point p){double r=Math.hypot(p.x(),p.z());return new int[]{(int)Math.round(p.x()*(r+3)/r),(int)Math.round(p.y()),(int)Math.round(p.z()*(r+3)/r)};}
    public static void main(String[] args){var t=new CaveNetworkTerrainSamplerTest();t.graphHasTrueInteriorLoopsVerticalLinksAndRepeatedPortals();t.portalsConnectThroughActualCarvedVoxelsRatherThanDisconnectedDiagnosticNodes();t.carvingIsSubtractiveBoundedAndPreservesMouthUnknownMasksAndWaterBarriers();t.negativeCoordinatesSectionOrderAndIndependentInstancesStayExact();System.out.println("4 CaveNetworkTerrainSampler tests passed");}
}
