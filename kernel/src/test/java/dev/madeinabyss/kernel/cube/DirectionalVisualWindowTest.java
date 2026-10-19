package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DirectionalVisualWindowTest {
    // Catches truncation of negative block coordinates and aliases in a lossy packed key.
    @Test void negativeFacesProduce243IndependentKeysWithDirectionalDepth() {
        var keys = DirectionalVisualWindow.keys(new BlockCoordinate(-1, -1, -1));
        assertEquals(243, keys.size());
        assertTrue(keys.contains(new CubeKey(-2, -22, -2)));
        assertTrue(keys.contains(new CubeKey(0, 4, 0)));
        assertTrue(keys.contains(new CubeKey(-1, -1, -1)));
        assertTrue(keys.stream().allMatch(k -> k.x() >= -2 && k.x() <= 0 && k.y() >= -22 && k.y() <= 4 && k.z() >= -2 && k.z() <= 0));
        assertEquals(9, keys.stream().filter(k -> k.y() == -22).count());
        assertEquals(9, keys.stream().filter(k -> k.y() == 4).count());
        assertEquals(keys, DirectionalVisualWindow.keys(new BlockCoordinate(-16, -16, -16)));
        assertFalse(keys.contains(new CubeKey(-1, 5, -1)));
        assertFalse(keys.contains(new CubeKey(-1, -23, -1)));
    }

    // Catches rebuilding a whole column instead of changing the four incoming/outgoing depth slabs.
    @Test void fourSectionVerticalShiftChanges36KeysAndKeeps207() {
        var low = DirectionalVisualWindow.keys(new BlockCoordinate(0, 16, 0));
        var high = DirectionalVisualWindow.keys(new BlockCoordinate(0, 80, 0));
        var removed = difference(low, high);
        var added = difference(high, low);
        assertEquals(36, removed.size());
        assertEquals(36, added.size());
        assertEquals(207, intersection(low, high).size());
        assertTrue(removed.stream().allMatch(k -> k.y() >= -20 && k.y() <= -17));
        assertTrue(added.stream().allMatch(k -> k.y() >= 7 && k.y() <= 10));
    }

    // Catches incorrect window deltas at negative X/Z section faces.
    @Test void horizontalSectionShiftChanges81KeysAndKeeps162() {
        var origin = DirectionalVisualWindow.keys(new BlockCoordinate(-1, -20001, -1));
        for (var shifted : new BlockCoordinate[]{new BlockCoordinate(-17, -20001, -1), new BlockCoordinate(-1, -20001, -17)}) {
            var next = DirectionalVisualWindow.keys(shifted);
            assertEquals(81, difference(origin, next).size());
            assertEquals(81, difference(next, origin).size());
            assertEquals(162, intersection(origin, next).size());
        }
    }

    // Catches too-small downward prefetch as a falling camera crosses negative section faces before reanchoring.
    @Test void downwardDriftUnder64BlocksKeeps256BelowAnd64AboveVisible() {
        for (int anchorY : new int[]{-1, -16, -17, 0, 16, 79, -20001}) {
            var keys = DirectionalVisualWindow.keys(new BlockCoordinate(-1, anchorY, -1));
            for (int drift = 0; drift < 64; drift++) {
                int cameraY = anchorY - drift;
                for (int visibleY : new int[]{cameraY - 256, cameraY + 64}) {
                    assertTrue(keys.contains(new CubeKey(-1, Math.floorDiv(visibleY, 16), -1)),
                            "visible vertical endpoint must stay resident at anchor=" + anchorY + ", downward drift=" + drift);
                }
            }
        }
    }

    // Catches silently clamping/truncating a visual window at the block-int domain or wrapping section origins.
    @Test void blockIntEdgesRejectIncompleteWindowsButAcceptLastCompleteWindows() {
        for (var center : new BlockCoordinate[]{new BlockCoordinate(Integer.MIN_VALUE, 0, 0),new BlockCoordinate(Integer.MAX_VALUE, 0, 0),
                new BlockCoordinate(0, 0, Integer.MIN_VALUE),new BlockCoordinate(0, 0, Integer.MAX_VALUE),
                new BlockCoordinate(0, Integer.MIN_VALUE + 335, 0),new BlockCoordinate(0, Integer.MAX_VALUE - 79, 0)}) {
            assertThrows(IllegalArgumentException.class, () -> DirectionalVisualWindow.keys(center));
        }
        assertThrows(NullPointerException.class, () -> DirectionalVisualWindow.keys(null));
        var bottom = DirectionalVisualWindow.keys(new BlockCoordinate(Integer.MIN_VALUE + 16, Integer.MIN_VALUE + 336, Integer.MIN_VALUE + 16));
        var top = DirectionalVisualWindow.keys(new BlockCoordinate(Integer.MAX_VALUE - 16, Integer.MAX_VALUE - 80, Integer.MAX_VALUE - 16));
        assertEquals(243, bottom.size()); assertEquals(243, top.size());
        assertTrue(bottom.contains(new CubeKey(-134217728, -134217728, -134217728)));
        assertTrue(top.contains(new CubeKey(134217727, 134217727, 134217727)));
        for (var window : java.util.List.of(bottom, top)) for (CubeKey key : window) {
            for (int section : new int[]{key.x(), key.y(), key.z()}) {
                long start = (long) section * 16;
                assertTrue(start >= Integer.MIN_VALUE && start + 15 <= Integer.MAX_VALUE, "every complete 16-cube axis must fit block ints");
            }
        }
    }

    private static Set<CubeKey> difference(Set<CubeKey> a, Set<CubeKey> b) { var result = new HashSet<>(a); result.removeAll(b); return result; }
    private static Set<CubeKey> intersection(Set<CubeKey> a, Set<CubeKey> b) { var result = new HashSet<>(a); result.retainAll(b); return result; }
}
