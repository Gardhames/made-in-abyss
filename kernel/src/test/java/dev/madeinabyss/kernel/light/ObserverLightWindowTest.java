package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ObserverLightWindowTest {
    @Test void haloHas125KeysAndDoesNotEnlargeOrMutateGeometry27() {
        var core = core(new BlockCoordinate(0, -20000, 0));
        var before = Set.copyOf(core);
        var desired = ObserverLightWindow.keys(core);
        assertEquals(125, desired.size());
        assertTrue(desired.containsAll(core));
        assertEquals(98, difference(desired, core).size());
        assertEquals(before, core);
        assertEquals(27, core.size());
    }

    @Test void negativeFloorAndDeepSeamsPreserveIndependentSectionKeys() {
        for (int y : new int[]{-64, -4096, -4097, -15904, -20000, -20001}) {
            var center = CubeKey.fromBlock(new BlockCoordinate(-1, y, -1));
            var desired = ObserverLightWindow.keys(core(new BlockCoordinate(-1, y, -1)));
            assertEquals(125, desired.size());
            assertTrue(desired.contains(new CubeKey(center.x() - 2, center.y() - 2, center.z() - 2)));
            assertTrue(desired.contains(new CubeKey(center.x() + 2, center.y() + 2, center.z() + 2)));
            assertFalse(desired.contains(new CubeKey(center.x(), center.y() + 3, center.z())));
        }
        assertNotEquals(ObserverLightWindow.keys(core(new BlockCoordinate(0, -20001, 0))),
                ObserverLightWindow.keys(core(new BlockCoordinate(0, -15905, 0))));
    }

    @Test void externalSourceWithin14BlocksIsASeparateObserverDependency() {
        var core = core(new BlockCoordinate(0, -20000, 0));
        var edge = new BlockCoordinate(31, -20000, 8);
        var source = new BlockCoordinate(32, -20000, 8);
        assertTrue(core.contains(CubeKey.fromBlock(edge)));
        assertFalse(core.contains(CubeKey.fromBlock(source)));
        assertTrue(ObserverLightWindow.keys(core).contains(CubeKey.fromBlock(source)));
        // A resident-only resolver must intersect, not silently generate/load missing halo keys.
        var resident = new HashSet<>(core);
        resident.add(CubeKey.fromBlock(source));
        var inputs = new HashSet<>(ObserverLightWindow.keys(core));
        inputs.retainAll(resident);
        assertEquals(28, inputs.size());
        assertEquals(97, difference(ObserverLightWindow.keys(core), inputs).size());
    }

    @Test void oneSectionMovementChanges25DependenciesButOnlyNineGeometryKeys() {
        var firstCore = core(new BlockCoordinate(-1, -20001, -1));
        var secondCore = core(new BlockCoordinate(0, -20001, -1));
        var first = ObserverLightWindow.keys(firstCore);
        var second = ObserverLightWindow.keys(secondCore);
        assertEquals(25, difference(first, second).size());
        assertEquals(25, difference(second, first).size());
        assertEquals(100, intersection(first, second).size());
        assertEquals(9, difference(firstCore, secondCore).size());
    }

    @Test void fourIndependentDepthsBoundDesiredBindings500AndPhysics108() {
        var physical = new HashSet<CubeKey>();
        var dependencies = new HashSet<CubeKey>();
        int bindings = 0;
        for (int y : new int[]{64, -4096, -15904, -20000}) {
            var core = core(new BlockCoordinate(-1, y, -1));
            var desired = ObserverLightWindow.keys(core);
            physical.addAll(core);
            dependencies.addAll(desired);
            bindings += desired.size();
        }
        assertEquals(108, physical.size());
        assertEquals(500, bindings);
        assertEquals(500, dependencies.size());
    }

    @Test void snapshotIsImmutableAndRejectsMalformedCoreWithoutMutatingIt() {
        var core = core(new BlockCoordinate(0, -20000, 0));
        var desired = ObserverLightWindow.keys(core);
        assertThrows(UnsupportedOperationException.class, desired::clear);
        core.clear();
        assertEquals(125, desired.size());
        assertThrows(IllegalArgumentException.class, () -> ObserverLightWindow.keys(Set.of()));
        var malformed = core(new BlockCoordinate(0, -20000, 0));
        malformed.remove(new CubeKey(-1, -1251, -1));
        var incomplete = Set.copyOf(malformed);
        assertThrows(IllegalArgumentException.class, () -> ObserverLightWindow.keys(malformed));
        assertEquals(incomplete, malformed);
        malformed.add(new CubeKey(9, -1250, 9));
        assertEquals(27, malformed.size());
        assertThrows(IllegalArgumentException.class, () -> ObserverLightWindow.keys(malformed));
    }

    @Test void rejectsHaloOutsideFullIntBlockDomainAndAcceptsNearestSafeEdges() {
        for (var center : List.of(new CubeKey(-134217727, 0, 0), new CubeKey(0, 134217726, 0),
                new CubeKey(0, 0, 134217727))) {
            var invalid = core(center);
            var before = Set.copyOf(invalid);
            assertThrows(IllegalArgumentException.class, () -> ObserverLightWindow.keys(invalid));
            assertEquals(before, invalid);
        }
        assertEquals(125, ObserverLightWindow.keys(core(new CubeKey(-134217726, 134217725, 0))).size());
    }

    private static Set<CubeKey> core(BlockCoordinate position) { return core(CubeKey.fromBlock(position)); }
    private static Set<CubeKey> core(CubeKey center) {
        var result = new HashSet<CubeKey>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            result.add(new CubeKey(center.x() + x, center.y() + y, center.z() + z));
        }
        return result;
    }
    private static Set<CubeKey> difference(Set<CubeKey> a, Set<CubeKey> b) {
        var result = new HashSet<>(a); result.removeAll(b); return result;
    }
    private static Set<CubeKey> intersection(Set<CubeKey> a, Set<CubeKey> b) {
        var result = new HashSet<>(a); result.retainAll(b); return result;
    }
}
