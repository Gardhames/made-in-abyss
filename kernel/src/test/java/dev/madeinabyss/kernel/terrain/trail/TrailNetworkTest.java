package dev.madeinabyss.kernel.terrain.trail;

import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TrailNetworkTest {
    // Removing corridor cuts must fail this test; this fixture has exposed ends and a solid middle cliff.
    private final TrailNetwork.SampleSource cliff = (x,y,z) -> y < -20
            ? new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER)
            : new TerrainSample.Known(y <= -10 || (x == -2 && y <= -7)
                ? TerrainMaterial.ROCK : TerrainMaterial.AIR);
    private final TrailNetwork.Parameters metres = new TrailNetwork.Parameters(3,3,3,1.0,8);
    private List<TrailNetwork.Point> path() {
        return List.of(new TrailNetwork.Point(-4,-10,-4),new TrailNetwork.Point(-3,-10,-4),
                new TrailNetwork.Point(-2,-10,-4),new TrailNetwork.Point(-1,-10,-4),
                new TrailNetwork.Point(0,-10,-4));
    }
    @Test void cutsSupportedCorridorAndJoinsExposedEndsWithoutChangingOtherGround() {
        var network=TrailNetwork.verify(cliff,metres,List.of(new TrailNetwork.Route("neighbours",path())));
        var overlay=new TrailOverlay(cliff,network);
        assertEquals(new TerrainSample.Known(TerrainMaterial.AIR),overlay.sample(-2,-8,-4));
        assertEquals(new TerrainSample.Known(TerrainMaterial.ROCK),overlay.sample(-2,-10,-4));
        assertEquals(new TerrainSample.Known(TerrainMaterial.ROCK),overlay.sample(-2,-11,-4));
        assertEquals(cliff.sample(-2,-7,-2),overlay.sample(-2,-7,-2));
        assertEquals(cliff.sample(Integer.MIN_VALUE,-21,Integer.MAX_VALUE),
                overlay.sample(Integer.MIN_VALUE,-21,Integer.MAX_VALUE));
    }
    // Admitting a missing support, covered endpoint, UNKNOWN, or multi-block step must fail here.
    @Test void refusesBridgesUnknownEndpointsAndUnwalkableSteps() {
        TrailNetwork.SampleSource voidPatch=(x,y,z)->x==-2 && y==-11
                ? new TerrainSample.Known(TerrainMaterial.AIR):cliff.sample(x,y,z);
        assertThrows(IllegalArgumentException.class,()->TrailNetwork.verify(voidPatch,metres,
                List.of(new TrailNetwork.Route("bridge",path()))));
        assertThrows(IllegalArgumentException.class,()->TrailNetwork.verify(cliff,metres,
                List.of(new TrailNetwork.Route("covered",List.of(path().get(2),path().get(3))))));
        assertThrows(IllegalArgumentException.class,()->TrailNetwork.verify(cliff,metres,
                List.of(new TrailNetwork.Route("unknown",List.of(new TrailNetwork.Point(-3,-21,-4),
                        new TrailNetwork.Point(-2,-21,-4))))));
        assertThrows(IllegalArgumentException.class,()->TrailNetwork.verify(cliff,metres,
                List.of(new TrailNetwork.Route("drop",List.of(path().get(0),
                        new TrailNetwork.Point(-2,-12,-4),path().get(2))))));
        TrailNetwork.SampleSource deepWall=(x,y,z)->new TerrainSample.Known(
                y<=-10 || (Math.abs(x)<20 && y<=-7)?TerrainMaterial.ROCK:TerrainMaterial.AIR);
        var tunnel=new java.util.ArrayList<TrailNetwork.Point>();
        for(int x=-20;x<=20;x++)tunnel.add(new TrailNetwork.Point(x,-10,-4));
        assertThrows(IllegalArgumentException.class,()->TrailNetwork.verify(deepWall,
                new TrailNetwork.Parameters(1,3,3,1,8),List.of(new TrailNetwork.Route("deep-tunnel",tunnel))));
    }
    @Test void descendsSingleBlockStepsOnRealSupportedGeology() {
        TrailNetwork.SampleSource slope=(x,y,z)-> new TerrainSample.Known(
                y<=-10-Math.max(0,Math.min(2,x+3))?TerrainMaterial.ROCK:TerrainMaterial.AIR);
        var points=List.of(new TrailNetwork.Point(-3,-10,-4),new TrailNetwork.Point(-2,-11,-4),
                new TrailNetwork.Point(-1,-12,-4));
        var network=TrailNetwork.verify(slope,new TrailNetwork.Parameters(1,2,3,1,8),
                List.of(new TrailNetwork.Route("down",points)));
        var overlay=new TrailOverlay(slope,network);
        for(var p:points) {
            assertEquals(new TerrainSample.Known(TerrainMaterial.ROCK),overlay.sample(p.x(),p.floorY(),p.z()));
            assertEquals(new TerrainSample.Known(TerrainMaterial.AIR),overlay.sample(p.x(),p.floorY()+2,p.z()));
        }
        assertThrows(IllegalArgumentException.class,()->TrailNetwork.verify(slope,
                new TrailNetwork.Parameters(1,2,3,0.5,8),List.of(new TrailNetwork.Route("too-steep",points))));
    }
}
