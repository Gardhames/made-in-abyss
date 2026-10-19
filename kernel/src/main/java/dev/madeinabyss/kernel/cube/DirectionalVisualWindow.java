package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** Independent visual residency keys, separate from the 27-cube physics window. */
public final class DirectionalVisualWindow {
    private DirectionalVisualWindow() {}
    public static Set<CubeKey> keys(BlockCoordinate center) {
        Objects.requireNonNull(center, "center");
        CubeKey section = CubeKey.fromBlock(center);
        int minX = Math.addExact(section.x(), -1), maxX = Math.addExact(section.x(), 1);
        int minY = Math.addExact(section.y(), -21), maxY = Math.addExact(section.y(), 5);
        int minZ = Math.addExact(section.z(), -1), maxZ = Math.addExact(section.z(), 1);
        requireCompleteBlockAxis(minX, maxX);
        requireCompleteBlockAxis(minY, maxY);
        requireCompleteBlockAxis(minZ, maxZ);
        var keys = new HashSet<CubeKey>(324);
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            keys.add(new CubeKey(x, y, z));
        }
        return Set.copyOf(keys);
    }

    private static void requireCompleteBlockAxis(int firstSection, int lastSection) {
        long firstBlock = (long) firstSection * 16;
        long lastBlock = (long) lastSection * 16 + 15;
        if (firstBlock < Integer.MIN_VALUE || lastBlock > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Complete visual window exceeds block-int coordinate domain");
    }
}
