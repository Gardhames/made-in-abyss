package dev.madeinabyss.kernel.ticket;

import dev.madeinabyss.kernel.BlockCoordinate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SweptCubeWindowTest {
    private static final int BOTTOM = -24000;
    private static final int VANILLA = -64;

    // Catches rejecting the actual terminal-fall sweep+8 halo at negative section faces.
    @Test void terminalDescentFitsAtAllDepthBoundariesAndSectionPhases() {
        for (int depth : new int[]{-64, -4096, -15904, -20000}) {
            for (double phase : new double[]{0, .0001, 15.999}) {
                double feet = depth + phase;
                var center = new BlockCoordinate(0, (int)Math.floor(feet + .9), 0);
                assertTrue(SweptCubeWindow.fits(center, -8.3, feet - 3.92 - 8, -8.3,
                        8.3, feet + 1.8 + 8, 8.3, BOTTOM, VANILLA), "depth=" + depth + " phase=" + phase);
            }
        }
    }

    // Catches truncating -0.01 to section0 rather than section-1, and losing safe side sweeps.
    @Test void fastSideSweepFitsAtNegativeHorizontalFaces() {
        assertTrue(SweptCubeWindow.fits(new BlockCoordinate(-1, -20000, -1),
                -8.31, -20008, -8.31, 15.79, -19990.2, 8.29, BOTTOM, VANILLA));
        assertTrue(SweptCubeWindow.fits(new BlockCoordinate(-16, -20000, -16),
                -31.8, -20008, -24.3, -7.7, -19990.2, -7.7, BOTTOM, VANILLA));
    }

    // Catches admitting a sweep beyond the exact27 cubes, including conservative closed endpoints.
    @Test void boundaryOutsideWindowAndOversizedMotionAreRejected() {
        var center = new BlockCoordinate(-1, -20000, -1);
        assertFalse(SweptCubeWindow.fits(center, -8, -20008, -8, 16, -19990, 8, BOTTOM, VANILLA));
        assertFalse(SweptCubeWindow.fits(center, -32.001, -20008, -8, 8, -19990, 8, BOTTOM, VANILLA));
        assertFalse(SweptCubeWindow.fits(center, -8, -20033, -8, 8, -19990, 8, BOTTOM, VANILLA));
        assertTrue(SweptCubeWindow.fits(center, -32, -20008, -8, 15.999, -19990, 8, BOTTOM, VANILLA));
    }

    // Catches asking deep residency for ordinary space or dropping normal surface ticks.
    @Test void ordinaryBoundsNeedNoDeepWindowWhileCrossingBoundsAreClipped() {
        assertTrue(SweptCubeWindow.fits(new BlockCoordinate(0, 64, 0),
                -8, 56, -8, 8, 74, 8, BOTTOM, VANILLA));
        assertTrue(SweptCubeWindow.fits(new BlockCoordinate(0, 64, 0),
                -8, -64, -8, 8, 74, 8, BOTTOM, VANILLA));
        assertTrue(SweptCubeWindow.fits(new BlockCoordinate(0, -60, 0),
                -8, -68, -8, 8, -50, 8, BOTTOM, VANILLA));
        assertFalse(SweptCubeWindow.fits(new BlockCoordinate(0, 64, 0),
                -8, -65, -8, 8, 74, 8, BOTTOM, VANILLA));
    }

    // Catches rejecting an expanded halo below technical bottom or covering no-deep intersections.
    @Test void bottomClipsTheHaloAndWhollyOutsideDeepBoundsNeedNoDeepCube() {
        assertTrue(SweptCubeWindow.fits(new BlockCoordinate(0, BOTTOM, 0),
                -8, BOTTOM - 8, -8, 8, BOTTOM + 10, 8, BOTTOM, VANILLA));
        assertTrue(SweptCubeWindow.fits(new BlockCoordinate(0, BOTTOM - 1, 0),
                -8, BOTTOM - 20, -8, 8, BOTTOM - .01, 8, BOTTOM, VANILLA));
        // This checks only cube coverage. The caller separately enforces the physical bottom.
    }

    // Catches int overflow while constructing a radius1 window at signed block extremes.
    @Test void outermostSectionsCannotProduceAFixedRadiusOneWindow() {
        assertFalse(SweptCubeWindow.fits(new BlockCoordinate(Integer.MIN_VALUE, -20000, 0),
                Integer.MIN_VALUE, -20008, -8, Integer.MIN_VALUE + 2.0, -19990, 8, BOTTOM, VANILLA));
        assertFalse(SweptCubeWindow.fits(new BlockCoordinate(Integer.MAX_VALUE, -20000, 0),
                Integer.MAX_VALUE - 2.0, -20008, -8, Integer.MAX_VALUE, -19990, 8, BOTTOM, VANILLA));
        assertTrue(SweptCubeWindow.fits(new BlockCoordinate(Integer.MIN_VALUE + 16, -20000, 0),
                Integer.MIN_VALUE + 8.0, -20008, -8, Integer.MIN_VALUE + 24.0, -19990, 8, BOTTOM, VANILLA));
        assertTrue(SweptCubeWindow.fits(new BlockCoordinate(Integer.MAX_VALUE - 16, -20000, 0),
                Integer.MAX_VALUE - 24.0, -20008, -8, Integer.MAX_VALUE + .5, -19990, 8, BOTTOM, VANILLA));
    }

    // Catches casts/saturation accepting NaN,infinity, reversed or unrepresentable bounds.
    @Test void malformedBoundsAndDomainReturnFalseWithoutThrowing() {
        var center = new BlockCoordinate(0, -20000, 0);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                (double)Integer.MAX_VALUE + 1, (double)Integer.MIN_VALUE - 1}) {
            for (int axis = 0; axis < 6; axis++) {
                double[] bounds = {-8, -20008, -8, 8, -19990, 8};
                bounds[axis] = invalid;
                assertFalse(SweptCubeWindow.fits(center, bounds[0], bounds[1], bounds[2],
                        bounds[3], bounds[4], bounds[5], BOTTOM, VANILLA));
            }
        }
        assertFalse(SweptCubeWindow.fits(center, 8, -20008, -8, -8, -19990, 8, BOTTOM, VANILLA));
        assertFalse(SweptCubeWindow.fits(center, -8, -19990, -8, 8, -20008, 8, BOTTOM, VANILLA));
        assertFalse(SweptCubeWindow.fits(center, -8, -20008, 8, 8, -19990, -8, BOTTOM, VANILLA));
        assertFalse(SweptCubeWindow.fits(center, -8, -20008, -8, 8, -19990, 8, VANILLA, BOTTOM));
        assertFalse(SweptCubeWindow.fits(null, -8, -20008, -8, 8, -19990, 8, BOTTOM, VANILLA));
    }
}
