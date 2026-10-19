package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class CubeBlockLightTest {
    @Test void emissionEscapesOpaqueSourceAndAttenuatesByDestinationWithMinimumOne() {
        var fixture = new Fixture();
        var source = pos(8, -4088, 8);
        fixture.block(source, 15, 15);
        fixture.block(pos(9, -4088, 8), 0, 0);
        fixture.block(pos(10, -4088, 8), 0, 3);
        fixture.block(pos(11, -4088, 8), 0, 0);
        var result = fixture.solve();
        light(result, source, 15);
        light(result, pos(9, -4088, 8), 14);
        light(result, pos(10, -4088, 8), 11);
        light(result, pos(11, -4088, 8), 10);
        light(result, pos(8, -4089, 8), 0);
    }

    @Test void freshSolveRemovesSourceAndOldResultRemainsAnImmutableSnapshot() {
        var fixture = new Fixture();
        var source = pos(8, -20008, 8);
        var neighbor = pos(9, -20008, 8);
        fixture.block(source, 15, 0);
        fixture.block(neighbor, 0, 0);
        var before = fixture.solve();
        fixture.block(source, 0, 0);
        var after = fixture.solve();
        light(before, neighbor, 14);
        light(after, source, 0);
        light(after, neighbor, 0);
    }

    @Test void alternateEmitterSurvivesRemovalWithoutSummingSourceLevels() {
        var fixture = new Fixture();
        for (int x = 6; x <= 10; x++) fixture.block(pos(x, -4088, 8), 0, 0);
        fixture.block(pos(6, -4088, 8), 15, 0);
        fixture.block(pos(10, -4088, 8), 12, 0);
        light(fixture.solve(), pos(8, -4088, 8), 13);
        fixture.block(pos(6, -4088, 8), 0, 0);
        light(fixture.solve(), pos(8, -4088, 8), 10);
    }

    @Test void propagationCrossesAllThreeNegativeSectionFacesWithoutTruncation() {
        var source = pos(-1, -4097, -1);
        for (var neighbor : List.of(pos(0, -4097, -1), pos(-1, -4096, -1), pos(-1, -4097, 0))) {
            var fixture = new Fixture();
            fixture.block(source, 15, 0);
            fixture.block(neighbor, 0, 0);
            var result = fixture.solve();
            light(result, source, 15);
            light(result, neighbor, 14);
            assertEquals(2, result.keys().size());
        }
    }

    @Test void deepYSeamAnd4096AliasHaveIndependentLightingAndRemoval() {
        var fixture = new Fixture();
        var low = pos(-8, -20001, -8);
        var alias = pos(-8, -15905, -8);
        fixture.block(low, 15, 0);
        fixture.block(pos(-8, -20000, -8), 0, 0);
        fixture.block(alias, 0, 0);
        fixture.block(pos(-8, -15904, -8), 0, 0);
        var first = fixture.solve();
        light(first, pos(-8, -20000, -8), 14);
        light(first, alias, 0);
        light(first, pos(-8, -15904, -8), 0);
        fixture.block(low, 0, 0);
        fixture.block(alias, 15, 0);
        var second = fixture.solve();
        light(second, pos(-8, -20000, -8), 0);
        light(second, pos(-8, -15904, -8), 14);
    }

    @Test void unknownAirBoundaryCannotBePublishedAsDarkButOpaqueWallCan() {
        var fixture = new Fixture();
        var boundary = pos(0, -4088, 8);
        fixture.block(boundary, 0, 0);
        var result = fixture.solve();
        assertEquals(OptionalInt.empty(), result.value(boundary));
        light(result, pos(1, -4088, 8), 0);
        light(result, pos(8, -4088, 8), 0);
        assertEquals(OptionalInt.empty(), result.value(pos(-1, -4088, 8)));
    }

    @Test void knownStrongEmitterConfirmsLightEvenNextToUnknownBoundary() {
        var fixture = new Fixture();
        fixture.block(pos(0, -4088, 8), 15, 0);
        fixture.block(pos(1, -4088, 8), 0, 0);
        var result = fixture.solve();
        light(result, pos(0, -4088, 8), 15);
        light(result, pos(1, -4088, 8), 14);
    }

    @Test void inputAndOutputRemainImmutableAfterArrayAndCollectionMutation() {
        var key = new CubeKey(0, -256, 0);
        var emission = new byte[4096];
        var opacity = new byte[4096];
        Arrays.fill(opacity, (byte) 15);
        emission[index(pos(8, -4088, 8))] = 15;
        var input = new CubeLightInput(key, emission, opacity);
        emission[index(pos(8, -4088, 8))] = 0;
        opacity[index(pos(8, -4088, 8))] = 0;
        input.emission()[index(pos(8, -4088, 8))] = 0;
        input.opacity()[index(pos(8, -4088, 8))] = 0;
        var inputs = new ArrayList<>(List.of(input));
        var result = CubeBlockLight.solve(inputs, 1);
        inputs.clear();
        light(result, pos(8, -4088, 8), 15);
        assertEquals(15, input.emission()[index(pos(8, -4088, 8))]);
        assertEquals(15, input.opacity()[index(pos(8, -4088, 8))]);
        assertEquals(java.util.Set.of(key), result.keys());
        assertThrows(UnsupportedOperationException.class, () -> result.keys().clear());
    }

    @Test void rejectsMalformedLightArraysAndUnsignedValuesOutsideNibbleRange() {
        var key = new CubeKey(0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new CubeLightInput(key, new byte[4095], new byte[4096]));
        assertThrows(IllegalArgumentException.class, () -> new CubeLightInput(key, new byte[4096], new byte[4097]));
        for (byte invalid : new byte[]{16, -1}) {
            var values = new byte[4096];
            values[4095] = invalid;
            assertThrows(IllegalArgumentException.class, () -> new CubeLightInput(key, values, new byte[4096]));
            assertThrows(IllegalArgumentException.class, () -> new CubeLightInput(key, new byte[4096], values));
        }
    }

    @Test void rejectsDuplicateKeysAndInvalidBudgetsWithoutChangingPreviousSnapshot() {
        var fixture = new Fixture();
        fixture.block(pos(8, 8, 8), 15, 15);
        var inputs = fixture.inputs();
        var previous = CubeBlockLight.solve(inputs, 1);
        assertThrows(IllegalArgumentException.class, () -> CubeBlockLight.solve(inputs, 0));
        assertThrows(IllegalArgumentException.class, () -> CubeBlockLight.solve(inputs, -1));
        assertThrows(IllegalArgumentException.class, () -> CubeBlockLight.solve(List.of(inputs.get(0), inputs.get(0)), 2));
        var other = new CubeLightInput(new CubeKey(1, 0, 0), new byte[4096], new byte[4096]);
        assertThrows(IllegalArgumentException.class, () -> CubeBlockLight.solve(List.of(inputs.get(0), other), 1));
        light(previous, pos(8, 8, 8), 15);
    }

    @Test void fullIntBlockExtremesDoNotOverflowNeighborCoordinates() {
        var fixture = new Fixture();
        var low = pos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        var high = pos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        fixture.block(low, 15, 15);
        fixture.block(high, 7, 15);
        var result = fixture.solve();
        light(result, low, 15);
        light(result, high, 7);
        light(result, pos(Integer.MIN_VALUE + 1, Integer.MIN_VALUE, Integer.MIN_VALUE), 0);
    }

    @Test void rejectsCubeOriginsOutsideFullIntBlockDomain() {
        for (var key : List.of(new CubeKey(134217728, 0, 0), new CubeKey(0, -134217729, 0), new CubeKey(0, 0, Integer.MAX_VALUE))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CubeLightInput(key, new byte[4096], new byte[4096]));
        }
    }

    private static void light(CubeBlockLight.Result result, BlockCoordinate position, int expected) {
        assertEquals(OptionalInt.of(expected), result.value(position), "light at " + position);
    }

    private static BlockCoordinate pos(int x, int y, int z) { return new BlockCoordinate(x, y, z); }
    private static int index(BlockCoordinate p) {
        return Math.floorMod(p.x(), 16) | (Math.floorMod(p.z(), 16) << 4) | (Math.floorMod(p.y(), 16) << 8);
    }

    private static final class Fixture {
        private final Map<CubeKey, byte[][]> cubes = new HashMap<>();

        void block(BlockCoordinate p, int emission, int opacity) {
            var arrays = cubes.computeIfAbsent(CubeKey.fromBlock(p), key -> {
                var opaque = new byte[4096];
                Arrays.fill(opaque, (byte) 15);
                return new byte[][]{new byte[4096], opaque};
            });
            arrays[0][index(p)] = (byte) emission;
            arrays[1][index(p)] = (byte) opacity;
        }

        List<CubeLightInput> inputs() {
            return cubes.entrySet().stream()
                    .map(e -> new CubeLightInput(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        }

        CubeBlockLight.Result solve() { return CubeBlockLight.solve(inputs(), 8); }
    }
}
