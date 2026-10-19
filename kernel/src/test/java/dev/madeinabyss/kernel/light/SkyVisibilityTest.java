package dev.madeinabyss.kernel.light;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkyVisibilityTest {
    @Test void previewUsesEightExactRaysAndDoesNotAlterFinalThirtyTwo() {
        var previewReads=new java.util.concurrent.atomic.AtomicInteger();var finalReads=new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(0,SkyVisibility.visibleFractionPreview(-17,-4096,-33,-4080,1000,(x,y,z)->{previewReads.incrementAndGet();return 15;}));
        assertEquals(0,SkyVisibility.visibleFraction(-17,-4096,-33,-4080,1000,(x,y,z)->{finalReads.incrementAndGet();return 15;}));
        assertEquals(8,previewReads.get());assertEquals(32,finalReads.get());
        assertEquals(0,SkyVisibility.visibleFractionPreview(0,-4096,0,-4080,1000,(x,y,z)->y==-4093?15:0));
    }
    @Test void previewPreservesPartialTransmissionAndSignedTranslation() {
        double partial=SkyVisibility.visibleFractionPreview(-1,0,-1,16,1000,(x,y,z)->y==3&&x<0&&z<2?7:0);
        double moved=SkyVisibility.visibleFractionPreview(-18,-20000,-4098,-19984,1000,(x,y,z)->y==-19997&&x< -17&&z< -4095?7:0);
        assertEquals(partial,moved);assertTrue(partial>0&&partial<1);
        double roof=SkyVisibility.visibleFractionPreview(0,0,0,16,1000,(x,y,z)->y==3?7:0);
        assertTrue(roof>0&&roof<=8.0/15+1e-12);
    }
    @Test void previewNeverConvertsUnknownBudgetOrInvalidOpacityToLight() {
        assertThrows(IllegalStateException.class,()->SkyVisibility.visibleFractionPreview(0,0,0,20,1000,(x,y,z)->{throw new IllegalStateException("UNKNOWN");}));
        assertThrows(IllegalStateException.class,()->SkyVisibility.visibleFractionPreview(0,0,0,20,1,(x,y,z)->0));
        assertThrows(IllegalArgumentException.class,()->SkyVisibility.visibleFractionPreview(0,0,0,20,0,(x,y,z)->0));
        assertThrows(IllegalArgumentException.class,()->SkyVisibility.visibleFractionPreview(0,0,0,20,1000,(x,y,z)->-1));
    }
    @Test void segmentRejectsNarrowVoxelMissedByQuarterVoxelSampling() {
        assertFalse(SkyVisibility.isOpenSegment(0, 0, 0, 31, 30, 0, 100,
                (x,y,z) -> x == 24 && y == 24 && z == 0 ? 15 : 0));
    }

    @Test void segmentTranslationToNegativeDeepCoordinatesPreservesOcclusion() {
        assertFalse(SkyVisibility.isOpenSegment(-17, -20000, -4097, 14, -19970, -4097, 100,
                (x,y,z) -> x == 7 && y == -19976 && z == -4097 ? 15 : 0));
        assertTrue(SkyVisibility.isOpenSegment(-17, -20000, -4097, 14, -19970, -4097, 100,
                (x,y,z) -> 0));
    }

    @Test void segmentRejectsPartialOpacityAndIncludesBothEndpointVoxels() {
        assertFalse(SkyVisibility.isOpenSegment(-2, -4097, 0, 2, -4097, 0, 10,
                (x,y,z) -> x == 0 ? 1 : 0));
        assertFalse(SkyVisibility.isOpenSegment(-2, -4097, 0, 2, -4097, 0, 10,
                (x,y,z) -> x == -2 ? 15 : 0));
        assertFalse(SkyVisibility.isOpenSegment(-2, -4097, 0, 2, -4097, 0, 10,
                (x,y,z) -> x == 2 ? 15 : 0));
        assertTrue(SkyVisibility.isOpenSegment(0, 0, 0, 0, 0, 0, 1, (x,y,z) -> 0));
    }

    @Test void segmentTraversesDescendingNegativeAxesAndSkipsZeroLengthCornerContacts() {
        assertFalse(SkyVisibility.isOpenSegment(2, 2, 2, -2, -2, -2, 10,
                (x,y,z) -> x == 0 && y == 0 && z == 0 ? 15 : 0));
        assertTrue(SkyVisibility.isOpenSegment(2, 2, 2, -2, -2, -2, 10,
                (x,y,z) -> x == 0 && y == 1 && z == 1 ? 15 : 0));
    }

    @Test void segmentPreservesUnknownAndBudgetFailuresInsteadOfAllowingReuse() {
        assertThrows(IllegalStateException.class, () -> SkyVisibility.isOpenSegment(0, 0, 0, 3, 0, 0, 2, (x,y,z) -> 0));
        assertThrows(IllegalStateException.class, () -> SkyVisibility.isOpenSegment(0, 0, 0, 3, 0, 0, 10,
                (x,y,z) -> { if (x == 1) throw new IllegalStateException("UNKNOWN"); return 0; }));
        assertThrows(IllegalArgumentException.class, () -> SkyVisibility.isOpenSegment(0, 0, 0, 3, 0, 0, 0, (x,y,z) -> 0));
        assertThrows(IllegalArgumentException.class, () -> SkyVisibility.isOpenSegment(0, 0, 0, 3, 0, 0, 10, (x,y,z) -> -1));
    }

    @Test void unobstructedHemisphereHasFullIrradianceButDiffuseStaysBelowDirectSky() {
        double visible = SkyVisibility.visibleFraction(-17, -20000, -33, -19980, 1000, (x,y,z) -> 0);
        assertEquals(1.0, visible);
        assertEquals(14, SkyVisibility.diffuseLevel(visible));
    }

    @Test void oneVoxelOpaqueRoofBlocksEveryRayEvenBetweenDistantMarchSamples() {
        assertEquals(0.0, SkyVisibility.visibleFraction(0, -4096, 0, -4080, 1000,
                (x,y,z) -> y == -4093 ? 15 : 0));
    }

    @Test void overhangReceivesDiffuseSkyThroughLateralOpening() {
        double visible = SkyVisibility.visibleFraction(-1, -4096, 0, -4080, 1000,
                (x,y,z) -> y == -4093 && x < 0 ? 15 : 0);
        assertTrue(visible > 0.0 && visible < 1.0, "some upward rays escape past the overhang edge");
        assertTrue(SkyVisibility.diffuseLevel(visible) > 0);
        assertTrue(SkyVisibility.diffuseLevel(visible) <= 14);
    }

    @Test void signedDeepTranslationPreservesVisibilityAndDeterministicSampling() {
        double origin = SkyVisibility.visibleFraction(-1, 0, -1, 16, 1000,
                (x,y,z) -> y == 3 && x < 0 && z < 2 ? 15 : 0);
        double translated = SkyVisibility.visibleFraction(-18, -20000, -4098, -19984, 1000,
                (x,y,z) -> y == -19997 && x < -17 && z < -4095 ? 15 : 0);
        assertEquals(origin, translated);
        assertEquals(origin, SkyVisibility.visibleFraction(-1, 0, -1, 16, 1000,
                (x,y,z) -> y == 3 && x < 0 && z < 2 ? 15 : 0));
    }

    @Test void partialRoofTransmitsItsRemainingFractionOncePerRay() {
        double visible = SkyVisibility.visibleFraction(0, 0, 0, 12, 1000, (x,y,z) -> y == 3 ? 7 : 0);
        // Oblique rays may traverse several voxels in the roof; transmission cannot exceed 8/15.
        assertTrue(visible > 0.0);
        assertTrue(visible <= 8.0 / 15.0 + 1e-12);
    }

    @Test void diffuseMappingPreservesDarknessAndUsesNonlinearScalarResponse() {
        assertEquals(0, SkyVisibility.diffuseLevel(0.0));
        assertEquals(5, SkyVisibility.diffuseLevel(0.1));
        assertEquals(9, SkyVisibility.diffuseLevel(0.25));
        assertEquals(12, SkyVisibility.diffuseLevel(0.5));
        assertEquals(14, SkyVisibility.diffuseLevel(1.0));
    }

    @Test void unknownGeometryAndExhaustedRayBudgetAreNeverConvertedToDarkness() {
        assertThrows(IllegalStateException.class, () -> SkyVisibility.visibleFraction(0, 0, 0, 20, 1, (x,y,z) -> 0));
        assertThrows(IllegalStateException.class, () -> SkyVisibility.visibleFraction(0, 0, 0, 20, 1000,
                (x,y,z) -> { throw new IllegalStateException("UNKNOWN"); }));
    }

    @Test void overflowingWorldCoordinatesFailInsteadOfWrappingToOtherGeometry() {
        assertThrows(IllegalStateException.class, () -> SkyVisibility.visibleFraction(Integer.MAX_VALUE, -1, 0, 2, 1000, (x,y,z) -> 0));
    }

    @Test void invalidBudgetOpacityAndIrradianceAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SkyVisibility.visibleFraction(0, 0, 0, 20, 0, (x,y,z) -> 0));
        assertThrows(IllegalArgumentException.class, () -> SkyVisibility.visibleFraction(0, 0, 0, 20, 1000, (x,y,z) -> 16));
        assertThrows(IllegalArgumentException.class, () -> SkyVisibility.diffuseLevel(-0.1));
        assertThrows(IllegalArgumentException.class, () -> SkyVisibility.diffuseLevel(1.1));
        assertThrows(IllegalArgumentException.class, () -> SkyVisibility.diffuseLevel(Double.NaN));
    }
}
