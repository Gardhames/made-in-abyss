package dev.madeinabyss.kernel.light;

import java.util.Arrays;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelSkyLightTest {
    @Test void opaqueBlockEmitterLightsAdjacentAir() {
        var f = new Fixture(-3, -4098, -2, 7, 5, 5);
        f.cell(1, 2, 2, 15, 15);
        f.cell(2, 2, 2, 0, 0);
        var result = VoxelSkyLight.solveBlock(f.bounds, f.opacity, f.sky, f.opacity.length);
        light(result, -2, -4096, 0, 15);
        light(result, -1, -4096, 0, 14);
        light(result, 0, -4096, 0, 0);
    }

    @Test void blockLightUnknownFringeCannotBecomeConfirmedDarkness() {
        var f = new Fixture(0, -20001, 0, 3, 3, 3);
        f.cell(0, 1, 1, 0, 0);
        var result = VoxelSkyLight.solveBlock(f.bounds, f.opacity, f.sky, f.opacity.length);
        assertEquals(OptionalInt.empty(), result.value(0, -20000, 1));
        light(result, 1, -20000, 1, 0);
    }

    @Test void directSkyRemainsFifteenThroughoutDeepNegativeColumn() {
        var f = new Fixture(-2, -20017, -2, 5, 36, 5);
        for (int y = 0; y < 36; y++) f.cell(2, y, 2, 0, 15);
        var result = f.solve();
        light(result, 0, -20017, 0, 15);
        light(result, 0, -20016, 0, 15);
        light(result, 0, -20001, 0, 15);
        light(result, 0, -19982, 0, 15);
    }

    @Test void roofShadowLosesOneLevelPerSidewaysStepFromOpenColumn() {
        var f = new Fixture(-4, -18, -2, 9, 5, 5);
        for (int y = 1; y <= 3; y++) f.cell(1, y, 2, 0, 15);
        for (int x = 2; x <= 7; x++) f.cell(x, 2, 2, 0, 0);
        var result = f.solve();
        light(result, -3, -16, 0, 15);
        light(result, -2, -16, 0, 14);
        light(result, 0, -16, 0, 12);
        light(result, 3, -16, 0, 9);
        light(result, 0, -15, 0, 0); // the roof itself
    }

    @Test void opaqueWallStopsSkyInsteadOfLettingItEscapeAnOpaqueSeed() {
        var f = new Fixture(0, -4098, 0, 7, 5, 5);
        f.cell(1, 2, 2, 0, 15);
        f.cell(2, 2, 2, 0, 0);
        f.cell(3, 2, 2, 15, 15); // contradictory input cannot light a solid block
        f.cell(4, 2, 2, 0, 0);
        var result = f.solve();
        light(result, 2, -4096, 2, 14);
        light(result, 3, -4096, 2, 0);
        light(result, 4, -4096, 2, 0);
    }

    @Test void partialOpacityUsesDestinationAttenuationAndAirStillCostsOne() {
        var f = new Fixture(0, 0, 0, 7, 5, 5);
        f.cell(1, 2, 2, 0, 15);
        f.cell(2, 2, 2, 3, 0);
        f.cell(3, 2, 2, 0, 0);
        f.cell(4, 2, 2, 1, 0);
        var result = f.solve();
        light(result, 2, 2, 2, 12);
        light(result, 3, 2, 2, 11);
        light(result, 4, 2, 2, 10);
    }

    @Test void unknownBoundaryStaysUncertainUntilPossibleExternalSkyHasDecayed() {
        var f = new Fixture(-15, -4111, -15, 31, 31, 31);
        Arrays.fill(f.opacity, (byte) 0);
        var result = f.solve();
        assertEquals(OptionalInt.empty(), result.value(-15, -4096, 0));
        assertEquals(OptionalInt.empty(), result.value(-2, -4096, 0)); // possible level 1
        light(result, -1, -4096, 0, 0); // any boundary source is >= 15 steps away
        light(result, 0, -4096, 0, 0);
        assertEquals(OptionalInt.empty(), result.value(-16, -4096, 0));
    }

    @Test void knownDirectSkyWinsEvenAtUnknownBoundaryButOpaqueBoundaryIsDark() {
        var f = new Fixture(0, 0, 0, 3, 3, 3);
        f.cell(0, 1, 1, 0, 15);
        var result = f.solve();
        light(result, 0, 1, 1, 15);
        light(result, 0, 0, 0, 0);
    }

    @Test void fullIntegerEndpointsRemainDistinctWithoutPackedCoordinateAliasing() {
        var f = new Fixture(Integer.MAX_VALUE - 2, Integer.MIN_VALUE, Integer.MIN_VALUE, 3, 3, 3);
        f.cell(1, 1, 1, 0, 15);
        var result = f.solve();
        light(result, Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1, 15);
        light(result, Integer.MAX_VALUE, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1, 0);
        assertEquals(OptionalInt.empty(), result.value(Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1));
        assertEquals(OptionalInt.empty(), result.value(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, Integer.MIN_VALUE + 1));
    }

    @Test void rejectsInvalidExtentsProductsAndBudgetsBeforeTouchingInputArrays() {
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(new VoxelSkyLight.Bounds(0, 0, 0, 0, 1, 1), null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(new VoxelSkyLight.Bounds(Integer.MAX_VALUE, 0, 0, 2, 1, 1), null, null, 2));
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(new VoxelSkyLight.Bounds(0, Integer.MAX_VALUE, 0, 1, 2, 1), null, null, 2));
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(new VoxelSkyLight.Bounds(0, 0, Integer.MAX_VALUE, 1, 1, 2), null, null, 2));
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(new VoxelSkyLight.Bounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), null, null, 8_000_000));
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(new VoxelSkyLight.Bounds(0, 0, 0, 201, 201, 201), null, null, 8_000_000));
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(new VoxelSkyLight.Bounds(0, 0, 0, 2, 2, 2), null, null, 7));
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(new VoxelSkyLight.Bounds(0, 0, 0, 1, 1, 1), null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(new VoxelSkyLight.Bounds(0, 0, 0, 1, 1, 1), null, null, 8_000_001));
    }

    @Test void rejectsWrongArraySizesAndNonScalarValues() {
        var bounds = new VoxelSkyLight.Bounds(0, 0, 0, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(bounds, new byte[0], new byte[1], 1));
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(bounds, new byte[1], new byte[2], 1));
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(bounds, new byte[]{16}, new byte[]{0}, 1));
        assertThrows(IllegalArgumentException.class, () -> VoxelSkyLight.solve(bounds, new byte[]{0}, new byte[]{-1}, 1));
    }

    @Test void solveDoesNotMutateInputsAndResultSurvivesTheirReuse() {
        var f = new Fixture(0, 0, 0, 5, 5, 5);
        f.cell(1, 2, 2, 0, 15);
        f.cell(2, 2, 2, 0, 0);
        byte[] originalOpacity = f.opacity.clone(), originalSky = f.sky.clone();
        var result = f.solve();
        assertArrayEquals(originalOpacity, f.opacity);
        assertArrayEquals(originalSky, f.sky);
        Arrays.fill(f.opacity, (byte) 15);
        Arrays.fill(f.sky, (byte) 0);
        light(result, 2, 2, 2, 14);
        light(f.solve(), 2, 2, 2, 0);
    }

    private static void light(VoxelSkyLight.Result result, int x, int y, int z, int expected) {
        assertEquals(OptionalInt.of(expected), result.value(x, y, z), "light at " + x + "," + y + "," + z);
    }

    private static final class Fixture {
        final VoxelSkyLight.Bounds bounds;
        final byte[] opacity, sky;
        Fixture(int x, int y, int z, int sx, int sy, int sz) {
            bounds = new VoxelSkyLight.Bounds(x, y, z, sx, sy, sz);
            opacity = new byte[sx * sy * sz];
            sky = new byte[opacity.length];
            Arrays.fill(opacity, (byte) 15);
        }
        void cell(int x, int y, int z, int opacityValue, int directSky) {
            int index = x + bounds.sizeX() * (z + bounds.sizeZ() * y);
            opacity[index] = (byte) opacityValue;
            sky[index] = (byte) directSky;
        }
        VoxelSkyLight.Result solve() { return VoxelSkyLight.solve(bounds, opacity, sky, opacity.length); }
    }
}
