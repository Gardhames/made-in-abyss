package dev.madeinabyss.kernel.light;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LodBoxSkyLightTest {
    private static final TerrainLodColumns.Box CEILING=new TerrainLodColumns.Box(-10,-64,-10,-8,-60,-8,TerrainMaterial.ROCK);
    @Test void undersideOnlyKnownDiffuseAirIlluminatesNegativeCeiling() {
        assertEquals(8,LodBoxSkyLight.maximum(CEILING,(x,y,z)->y==-65?0:15,(x,y,z)->0,(x,y,z)->y==-65?8:0));
    }
    @Test void unknownBottomNeighborRemainsUnavailableAndSignedOverflowFails() {
        var unknown=new IllegalStateException("UNKNOWN_BOTTOM");
        assertSame(unknown,assertThrows(IllegalStateException.class,()->LodBoxSkyLight.maximum(CEILING,
                (x,y,z)->{if(y==-65)throw unknown;return 15;},(x,y,z)->0,(x,y,z)->0)));
        var edge=new TerrainLodColumns.Box(Integer.MIN_VALUE,-64,-10,(long)Integer.MIN_VALUE+1,-60,-8,TerrainMaterial.ROCK);
        assertThrows(ArithmeticException.class,()->LodBoxSkyLight.maximum(edge,(x,y,z)->15,(x,y,z)->0,(x,y,z)->0));
    }
    @Test void declaredLowerBoundaryDoesNotProbeOutsideKnownGeometry() {
        var unknown=new IllegalStateException("OUTSIDE_DECLARED_GEOMETRY");
        LodBoxSkyLight.Sampler opacity=(x,y,z)->{if(y==-65)throw unknown;return 15;};
        assertEquals(0,LodBoxSkyLight.maximum(CEILING,-64,opacity,(x,y,z)->0,(x,y,z)->0));
        assertSame(unknown,assertThrows(IllegalStateException.class,()->LodBoxSkyLight.maximum(CEILING,-65,opacity,(x,y,z)->0,(x,y,z)->0)));
    }
}
