package dev.madeinabyss.kernel.light.gpu;
import dev.madeinabyss.kernel.cube.CubeKey;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class ClosedLightSceneTest {
    ClosedLightScene scene(){return new ClosedLightScene(PackedLightVolume.compile(new CubeKey(-1,-1,-1),new CubeKey(0,0,0),Map.of(),false),PackedHeightHalo.build(-32,-32,64,(x,z)->-10,2),32,-16,-5000,256,1);}
    @Test void exactIntegerTopSeparatesTerrainWaterAndSky(){var s=scene();assertEquals(15,s.opacity(16,-10,0));assertEquals(1,s.opacity(16,-9,0));assertEquals(1,s.opacity(16,0,0));assertEquals(0,s.opacity(16,1,0));}
    @Test void unboundedKnownExteriorSkyNeedsNoHaloEntry(){var s=scene();assertEquals(0,s.opacity(2000,1,0));assertEquals(0,s.opacity(-2000,1,0));assertEquals(0,s.opacity(0,32,0));}
    @Test void missingNegativeExteriorAndUnavailableInteriorStayUnknown(){var s=scene();assertEquals(-1,s.opacity(5000,-1,0));assertEquals(-1,s.opacity(0,-17,0));assertEquals(-1,s.opacity(16,-5001,0));assertEquals(-1,s.opacity(0,0,0));}
    @Test void highIslandTerrainBelowTheCoreFloorIsStillKnownOpaque(){var volume=PackedLightVolume.compile(new CubeKey(-2,-1,-2),new CubeKey(1,0,1),Map.of(),false);var s=new ClosedLightScene(volume,PackedHeightHalo.build(-32,-32,64,(x,z)->290,2),512,-16,-5000,256,1);assertEquals(15,s.opacity(16,-17,0));assertEquals(0,s.opacity(16,310,0));assertEquals(-1,s.opacity(16,294,0));}
}
