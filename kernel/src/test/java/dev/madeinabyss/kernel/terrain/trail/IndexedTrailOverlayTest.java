package dev.madeinabyss.kernel.terrain.trail;
import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class IndexedTrailOverlayTest {
    @Test void negativeWidthAndBendFootprintsMatchTheVerifiedOverlayOracle() {
        TrailNetwork.SampleSource base=(x,y,z)->new TerrainSample.Known(y<=0||x==-16&&z>=-17&&z<=-15&&y<=3?TerrainMaterial.ROCK:TerrainMaterial.AIR);
        var p=new TrailNetwork.Parameters(3,2,3,1,8);
        var route=new TrailNetwork.Route("bend",List.of(new TrailNetwork.Point(-17,0,-16),new TrailNetwork.Point(-16,0,-16),new TrailNetwork.Point(-15,0,-16),new TrailNetwork.Point(-15,0,-15),new TrailNetwork.Point(-15,0,-14)));
        var network=TrailNetwork.verify(base,p,List.of(route));var old=new TrailOverlay(base,network);var indexed=new IndexedTrailOverlay(base,network);
        for(int x=-22;x<=-10;x++)for(int z=-22;z<=-10;z++)for(int y=-3;y<=5;y++)assertEquals(old.sample(x,y,z),indexed.sample(x,y,z),x+","+y+","+z);
        assertEquals(TerrainMaterial.AIR,((TerrainSample.Known)indexed.sample(-16,2,-16)).material());
        assertEquals(TerrainMaterial.ROCK,((TerrainSample.Known)indexed.sample(-16,0,-16)).material());
        assertThrows(IllegalArgumentException.class,()->new IndexedTrailOverlay((x,y,z)->base.sample(x,y,z),network));
    }
    @Test void maximumOverlapFloorAndExtremeSignedEndpointsMatchOracle() {
        TrailNetwork.SampleSource base=(x,y,z)->new TerrainSample.Known(y<=0||x==0&&z==0&&y==1?TerrainMaterial.ROCK:TerrainMaterial.AIR);
        var a=new TrailNetwork.Route("low",List.of(new TrailNetwork.Point(-1,0,0),new TrailNetwork.Point(0,0,0),new TrailNetwork.Point(1,0,0)));
        var b=new TrailNetwork.Route("upper",List.of(new TrailNetwork.Point(0,1,0),new TrailNetwork.Point(0,0,1)));
        var p=new TrailNetwork.Parameters(1,1,3,1,8);var network=TrailNetwork.verify(base,p,List.of(a,b));
        var indexed=new IndexedTrailOverlay(base,network);var old=new TrailOverlay(base,network);
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=5;y++)assertEquals(old.sample(x,y,z),indexed.sample(x,y,z));
        assertEquals(TerrainMaterial.ROCK,((TerrainSample.Known)indexed.sample(0,1,0)).material());assertEquals(4,indexed.floorCount());
        for(int x:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE-1}) {
            var extreme=TrailNetwork.verify(base,p,List.of(new TrailNetwork.Route("edge",List.of(new TrailNetwork.Point(x,0,Integer.MIN_VALUE),new TrailNetwork.Point(x+1,0,Integer.MIN_VALUE)))));
            var actual=new IndexedTrailOverlay(base,extreme);var oracle=new TrailOverlay(base,extreme);
            for(int dx=0;dx<2;dx++)for(int y=-1;y<5;y++)assertEquals(oracle.sample(x+dx,y,Integer.MIN_VALUE),actual.sample(x+dx,y,Integer.MIN_VALUE));
        }
    }
    @Test void unknownSampleIdentityAndSingleSourceCallRemainAuthoritative() {
        var unknown=new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER);var calls=new java.util.concurrent.atomic.AtomicInteger();
        TrailNetwork.SampleSource base=(x,y,z)->{calls.incrementAndGet();return y<-2?unknown:new TerrainSample.Known(y<=0?TerrainMaterial.ROCK:TerrainMaterial.AIR);};
        var network=TrailNetwork.verify(base,new TrailNetwork.Parameters(1,1,3,1,8),List.of(new TrailNetwork.Route("r",List.of(new TrailNetwork.Point(-1,0,-1),new TrailNetwork.Point(0,0,-1)))));
        var indexed=new IndexedTrailOverlay(base,network);calls.set(0);
        assertSame(unknown,indexed.sample(-1,-3,-1));assertEquals(1,calls.get());
        calls.set(0);indexed.sample(Integer.MAX_VALUE,20000,Integer.MIN_VALUE);assertEquals(1,calls.get());
    }
}
