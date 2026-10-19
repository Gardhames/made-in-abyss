package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;

/** Light-only one-cube halo around the contiguous geometry27; creates no residency tickets. */
public final class ObserverLightWindow {
    public static final int CORE_CUBES = 27;
    public static final int DESIRED_CUBES = 125;
    private ObserverLightWindow() { }
    public static Set<CubeKey> keys(Set<CubeKey> geometryCore) {
        var core = Set.copyOf(Objects.requireNonNull(geometryCore, "geometryCore"));
        if (core.size() != CORE_CUBES) throw new IllegalArgumentException("Expected geometry27");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var key : core) {
            minX = Math.min(minX, key.x()); maxX = Math.max(maxX, key.x());
            minY = Math.min(minY, key.y()); maxY = Math.max(maxY, key.y());
            minZ = Math.min(minZ, key.z()); maxZ = Math.max(maxZ, key.z());
        }
        if ((long) maxX - minX != 2 || (long) maxY - minY != 2 || (long) maxZ - minZ != 2) {
            throw new IllegalArgumentException("Expected contiguous Cartesian geometry3x3x3");
        }
        requireHaloAxis(minX, maxX); requireHaloAxis(minY, maxY); requireHaloAxis(minZ, maxZ);
        var desired = new HashSet<CubeKey>();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 1; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) desired.add(new CubeKey(x, y, z));
            }
        }
        return Set.copyOf(desired);
    }

    private static void requireHaloAxis(int minimum, int maximum) {
        if ((long) minimum - 1 < -134217728L || (long) maximum + 1 > 134217727L) {
            throw new IllegalArgumentException("Full light halo exceeds signed int block coordinate domain");
        }
    }
}
