package dev.madeinabyss.kernel.light;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IterationSkyResponseTest {
    @Test void selectsClosestIrradianceAcrossEveryAvailableOutputLevel() {
        for (int physical = 0; physical <= 15; physical++) {
            double x = physical / 15.0, target = x / (4 - 3 * x);
            int result = IterationSkyResponse.presentSky(physical);
            double error = Math.abs(iterationResponse(result) - target);
            for (int alternative = 0; alternative <= 15; alternative++)
                assertTrue(error <= Math.abs(iterationResponse(alternative) - target) + 1e-12,
                        "Physical SKY " + physical + " has a closer alternative " + alternative);
        }
    }

    @Test void preservesEndpointsAndMonotonicityWithoutChangingBlockHalf() {
        assertEquals(0, IterationSkyResponse.presentSky(0));
        assertEquals(15, IterationSkyResponse.presentSky(15));
        int previous = -1;
        for (int sky = 0; sky <= 15; sky++) {
            int shown = IterationSkyResponse.presentSky(sky);
            assertTrue(shown >= previous && shown <= 15);
            previous = shown;
            for (int block = 0; block <= 15; block++) {
                int original = (sky << 20) | (block << 4);
                int result = IterationSkyResponse.presentPacked(original);
                assertEquals(original & 0xffff, result & 0xffff);
                assertEquals(shown, result >>> 20);
            }
        }
        // Preserve all low-half bits, not just typical multiples of sixteen.
        assertEquals(0xabcd, IterationSkyResponse.presentPacked((5 << 20) | 0xabcd) & 0xffff);
        assertThrows(IllegalArgumentException.class, () -> IterationSkyResponse.presentSky(-1));
        assertThrows(IllegalArgumentException.class, () -> IterationSkyResponse.presentSky(16));
    }

    private static double iterationResponse(int level) {
        double uv = (16 * level + 8) / 256.0;
        double n = Math.max(0, Math.min(1, uv * 1.103449 - 0.0689656));
        return Math.min(1, 1.95 * Math.pow(1 - Math.pow(1 - 0.9 * n, 0.7), 3));
    }
}
