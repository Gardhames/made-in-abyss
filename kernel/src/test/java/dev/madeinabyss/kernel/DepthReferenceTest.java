package dev.madeinabyss.kernel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DepthReferenceTest {
    @Test void depthUsesGlobalSurfaceDatum() {
        assertEquals(20000L, new DepthReference(64).depthAt(-19936));
        assertEquals(-36L, new DepthReference(64).depthAt(100));
    }

    @Test void extremeCoordinatesDoNotOverflowSignedInteger() {
        assertEquals(4294967295L, new DepthReference(Integer.MAX_VALUE).depthAt(Integer.MIN_VALUE));
    }
}
