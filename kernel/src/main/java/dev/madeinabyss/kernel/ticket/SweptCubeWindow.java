package dev.madeinabyss.kernel.ticket;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;

/**
 * Fixed radius-one (27 cubes) preflight for caller-expanded body bounds.
 * Bounds must already include the swept movement and required halo. This checks coordinate
 * coverage only, not loaded residency. The deep part is clipped to [deepBottom, vanillaMinY):
 * ordinary blocks require the caller's native chunk check, and an expanded halo below the
 * technical bottom does not require impossible deep cubes. The caller must separately reject
 * a physical body outside its allowed vertical domain. Inputs and max bounds are conservative:
 * a closed maximum exactly on a section face requires the neighboring section.
 */
public final class SweptCubeWindow {
    private static final int MIN_SECTION = Math.floorDiv(Integer.MIN_VALUE, 16);
    private static final int MAX_SECTION = Math.floorDiv(Integer.MAX_VALUE, 16);
    private SweptCubeWindow() { }
    public static boolean fits(BlockCoordinate center,
                               double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ,
                               int deepBottom, int vanillaMinY) {
        if (center == null || deepBottom >= vanillaMinY
                || !representable(minX) || !representable(minY) || !representable(minZ)
                || !representable(maxX) || !representable(maxY) || !representable(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) return false;
        // With no deep intersection, no deep demand window is needed. This is not permission
        // to move below the physical bottom or through an absent ordinary-height chunk.
        if (minY >= vanillaMinY || maxY < deepBottom) return true;
        var section = CubeKey.fromBlock(center);
        if (!radiusOneRepresentable(section.x()) || !radiusOneRepresentable(section.y())
                || !radiusOneRepresentable(section.z())) return false;
        double clippedMinY = Math.max(minY, deepBottom);
        double clippedMaxY = Math.min(maxY, Math.nextDown((double)vanillaMinY));
        return covers(section.x(), minX, maxX)
                && covers(section.y(), clippedMinY, clippedMaxY)
                && covers(section.z(), minZ, maxZ);
    }

    private static boolean representable(double value) {
        return Double.isFinite(value) && value >= Integer.MIN_VALUE && value < (double)Integer.MAX_VALUE + 1;
    }

    private static boolean radiusOneRepresentable(int section) { return section > MIN_SECTION && section < MAX_SECTION; }

    private static boolean covers(int centerSection, double min, double max) {
        int minSection = Math.floorDiv((int)Math.floor(min), 16);
        int maxSection = Math.floorDiv((int)Math.floor(max), 16);
        return minSection >= centerSection - 1 && maxSection <= centerSection + 1;
    }
}
