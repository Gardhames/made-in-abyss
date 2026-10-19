package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.light.VoxelSkyLight;

import java.util.Optional;

/** Bounded visual first view; solve halo and submitted draw coverage are separate. */
public final class NativeFirstViewPlan {
    private static final int DRAW_SIZE = 48, SOLVE_SIZE = 80;
    private static final int DRAW_HALF = DRAW_SIZE / 2, DRAW_BEFORE = 16, SOLVE_BEFORE = 32;
    private static final long CELLS = (long) SOLVE_SIZE * SOLVE_SIZE * SOLVE_SIZE;
    private static final long PEAK_BYTES = CELLS * 26;
    private NativeFirstViewPlan() { }

    public enum Stage { NEAR, FULL }

    public record Plan(Stage stage, int requestedRadius, int effectiveRadius,
                       int anchorX, int anchorY, int anchorZ, long authorityEpoch,
                       VoxelSkyLight.Bounds solveBounds, VoxelSkyLight.Bounds drawBounds,
                       long cells, long peakBytes) { }

    @FunctionalInterface
    public interface StateKnown {
        boolean contains(int x, int y, int z);
    }

    /** Optional first view refuses when a full known, signed, bounded halo is unavailable. */
    public static Optional<Plan> near(int chunks, double x, double y, double z,
                                      int minimumY, int maximumY, long authorityEpoch, int maxCells) {
        if (chunks < 2 || chunks > 128 || !signedCoordinate(x) || !signedCoordinate(y)
                || !signedCoordinate(z) || minimumY >= maximumY || y < minimumY || y >= maximumY
                || maxCells < CELLS || maxCells > NativeRenderDistance.MAX_CELLS) return Optional.empty();
        int ax = anchor(x), ay = anchor(y), az = anchor(z);
        if (!signedHalo(ax) || !signedHalo(ay) || !signedHalo(az)
                || (long) ay - SOLVE_BEFORE < minimumY || (long) ay + SOLVE_SIZE-SOLVE_BEFORE > maximumY)
            return Optional.empty();
        var solve = new VoxelSkyLight.Bounds(ax - SOLVE_BEFORE, ay - SOLVE_BEFORE, az - SOLVE_BEFORE,
                SOLVE_SIZE, SOLVE_SIZE, SOLVE_SIZE);
        var draw = new VoxelSkyLight.Bounds(ax - DRAW_BEFORE, ay - DRAW_BEFORE, az - DRAW_BEFORE,
                DRAW_SIZE, DRAW_SIZE, DRAW_SIZE);
        return Optional.of(new Plan(Stage.NEAR, chunks * 16, DRAW_HALF, ax, ay, az, authorityEpoch,
                solve, draw, CELLS, PEAK_BYTES));
    }

    /**
     * Requires current authority, all water/model state support within DRAW expanded by two,
     * and exact SKY/BLOCK at all face/water light positions within DRAW expanded by one.
     * This certifies the current flat solid/water stencil, not arbitrary extended light callbacks.
     */
    public static Optional<Coverage> certify(Plan plan, long currentEpoch,
                                              VoxelSkyLight.Result sky, VoxelSkyLight.Result block,
                                              StateKnown states) {
        if (!validNearPlan(plan) || plan.authorityEpoch() != currentEpoch || sky == null || block == null
                || states == null || !plan.solveBounds().equals(sky.bounds())
                || !plan.solveBounds().equals(block.bounds())) return Optional.empty();
        var d = plan.drawBounds();
        for (int ly = -2; ly < DRAW_SIZE + 2; ly++) {
            int y = d.minY() + ly;
            for (int lz = -2; lz < DRAW_SIZE + 2; lz++) {
                int z = d.minZ() + lz;
                for (int lx = -2; lx < DRAW_SIZE + 2; lx++) {
                    int x = d.minX() + lx;
                    if (!states.contains(x, y, z)) return Optional.empty();
                    // Omitted boundary ambient seeds can still reach the state-only two-cell halo.
                    if (lx >= -1 && lx <= DRAW_SIZE && ly >= -1 && ly <= DRAW_SIZE
                            && lz >= -1 && lz <= DRAW_SIZE
                            && (sky.value(x, y, z).isEmpty() || block.value(x, y, z).isEmpty()))
                        return Optional.empty();
                }
            }
        }
        return Optional.of(new Coverage(plan));
    }

    private static boolean validNearPlan(Plan p) {
        return p != null && p.stage() == Stage.NEAR && p.requestedRadius() >= 32
                && p.requestedRadius() <= 128 * 16 && p.requestedRadius() % 16 == 0
                && p.effectiveRadius() == DRAW_HALF && p.cells() == CELLS && p.peakBytes() == PEAK_BYTES
                && p.anchorX() % 16 == 0 && p.anchorY() % 16 == 0 && p.anchorZ() % 16 == 0
                && signedHalo(p.anchorX()) && signedHalo(p.anchorY()) && signedHalo(p.anchorZ())
                && matches(p.solveBounds(), p.anchorX(), p.anchorY(), p.anchorZ(), SOLVE_SIZE,SOLVE_BEFORE)
                && matches(p.drawBounds(), p.anchorX(), p.anchorY(), p.anchorZ(), DRAW_SIZE,DRAW_BEFORE);
    }

    private static boolean matches(VoxelSkyLight.Bounds b, int ax, int ay, int az, int size,int before) {
        return b != null && b.sizeX() == size && b.sizeY() == size && b.sizeZ() == size
                && b.minX() == (long) ax - before && b.minY() == (long) ay - before
                && b.minZ() == (long) az - before;
    }

    private static boolean signedHalo(int anchor) {
        return (long) anchor - SOLVE_BEFORE >= Integer.MIN_VALUE
                && (long) anchor + SOLVE_SIZE-SOLVE_BEFORE - 1 <= Integer.MAX_VALUE;
    }

    private static boolean signedCoordinate(double value) {
        return Double.isFinite(value) && Math.floor(value) >= Integer.MIN_VALUE
                && Math.floor(value) <= Integer.MAX_VALUE;
    }

    private static int anchor(double value) {
        return Math.floorDiv((int) Math.floor(value), 16) * 16;
    }

    private static boolean contains(VoxelSkyLight.Bounds b, double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && x >= b.minX() && x < (long) b.minX() + b.sizeX()
                && y >= b.minY() && y < (long) b.minY() + b.sizeY()
                && z >= b.minZ() && z < (long) b.minZ() + b.sizeZ();
    }

    public static final class Coverage {
        private final Plan plan;
        private Coverage(Plan plan) { this.plan = plan; }
        public Plan plan() { return plan; }
        public boolean containsDraw(int x, int y, int z) { return contains(plan.drawBounds(), x, y, z); }
        public boolean coversCamera(double x, double y, double z, long currentEpoch) {
            return plan.authorityEpoch() == currentEpoch && contains(plan.drawBounds(), x, y, z);
        }
    }
}
