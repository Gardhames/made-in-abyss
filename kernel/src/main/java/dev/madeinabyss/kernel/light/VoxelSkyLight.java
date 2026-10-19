package dev.madeinabyss.kernel.light;

import java.util.Objects;
import java.util.OptionalInt;

/** Bounded scalar SKY solve. Dense local index: x + sizeX * (z + sizeZ * y). */
public final class VoxelSkyLight {
    /** Allocation safety bound, not a limit on world depth or world coordinates. */
    public static final int MAX_CELLS = 8_000_000;
    private static final Runnable NO_CHECK=()->{};
    private VoxelSkyLight() { }

    /** Sizes are positive; every included global coordinate must fit a signed int. */
    public record Bounds(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) { }
    public static int cellCount(Bounds bounds,int maxCells){return preflight(Objects.requireNonNull(bounds),maxCells,40_000_000);}
    /** Restores exact lower/upper uncertainty, with independent arrays and bounded validation. */
    public static Result restore(Bounds bounds,byte[] lower,byte[] upper,int maxCells) {
        int count=cellCount(bounds,maxCells);Objects.requireNonNull(lower);Objects.requireNonNull(upper);
        if(lower.length!=count||upper.length!=count)throw new IllegalArgumentException("Snapshot length must equal bounds volume");
        var lo=lower.clone();var hi=upper.clone();
        for(int i=0;i<count;i++)if(lo[i]<0||lo[i]>15||hi[i]<lo[i]||hi[i]>15)throw new IllegalArgumentException("Invalid light uncertainty interval");
        return new Result(bounds,lo,hi);
    }

    /** BLOCK emission escapes its source even when the source itself has opacity 15. */
    public static Result solveBlock(Bounds bounds, byte[] opacity, byte[] emissions, int maxCells) {
        return solveScalar(bounds, opacity, emissions, maxCells, false);
    }

    /** Explicit opt-in large native scene budget. The ordinary API retains its 8M ceiling. */
    public static Result solveExpanded(Bounds bounds,byte[] opacity,byte[] seeds,int maxCells) {
        return solveScalar(bounds,opacity,seeds,maxCells,true,40_000_000);
    }
    public static Result solveBlockExpanded(Bounds bounds,byte[] opacity,byte[] seeds,int maxCells) {
        return solveScalar(bounds,opacity,seeds,maxCells,false,40_000_000);
    }
    public static Result solveExpanded(Bounds bounds,byte[] opacity,byte[] seeds,int maxCells,Runnable checkpoint) {
        return solveScalar(bounds,opacity,seeds,maxCells,true,40_000_000,checkpoint);
    }
    public static Result solveBlockExpanded(Bounds bounds,byte[] opacity,byte[] seeds,int maxCells,Runnable checkpoint) {
        return solveScalar(bounds,opacity,seeds,maxCells,false,40_000_000,checkpoint);
    }

    /**
     * Direct sky must already reflect the entire vertical column up to open sky, including
     * geometry outside this volume. An unobstructed column supplies 15 at every open voxel;
     * propagation here only models attenuated indirect light. Opacity 15 always blocks SKY.
     * Unknown outside neighbors may supply 15, but crossing into this volume costs at least 1.
     * Inputs are never mutated; the returned snapshot retains neither input array.
     */
    public static Result solve(Bounds bounds, byte[] opacity, byte[] directSky, int maxCells) {
        return solveScalar(bounds, opacity, directSky, maxCells, true);
    }

    private static Result solveScalar(Bounds bounds, byte[] opacity, byte[] seeds, int maxCells, boolean sky) {
        return solveScalar(bounds,opacity,seeds,maxCells,sky,MAX_CELLS);
    }
    private static Result solveScalar(Bounds bounds, byte[] opacity, byte[] seeds, int maxCells, boolean sky,int hardMaximum) {
        return solveScalar(bounds,opacity,seeds,maxCells,sky,hardMaximum,NO_CHECK);
    }
    private static Result solveScalar(Bounds bounds, byte[] opacity, byte[] seeds, int maxCells, boolean sky,int hardMaximum,Runnable checkpoint) {
        Objects.requireNonNull(checkpoint);checkpoint.run();
        Objects.requireNonNull(bounds, "bounds");
        int count = preflight(bounds, maxCells,hardMaximum);
        Objects.requireNonNull(opacity, "opacity");
        Objects.requireNonNull(seeds, "seeds");
        if (opacity.length != count || seeds.length != count) {
            throw new IllegalArgumentException("input length must equal bounds volume");
        }
        for (int i = 0; i < count; i++) {
            if((i&1023)==0)checkpoint.run();
            if (opacity[i] < 0 || opacity[i] > 15 || seeds[i] < 0 || seeds[i] > 15) {
                throw new IllegalArgumentException("opacity and seeds must be 0..15");
            }
        }
        byte[] lower = new byte[count];
        byte[] upper = new byte[count];
        int sx = bounds.sizeX(), sz = bounds.sizeZ(), plane = sx * sz;
        for (int i = 0; i < count; i++) {
            if((i&1023)==0)checkpoint.run();
            int seed = sky && opacity[i] == 15 ? 0 : seeds[i];
            lower[i] = (byte) seed;
            int x = i % sx, y = i / plane, z = (i % plane) / sx;
            boolean boundary = x == 0 || x == sx - 1 || y == 0 || y == bounds.sizeY() - 1
                    || z == 0 || z == sz - 1;
            upper[i] = (byte) (boundary ? Math.max(seed, 15 - Math.max(1, opacity[i])) : seed);
        }
        var queue = new CellQueue(count);
        propagate(lower, opacity, bounds, queue,checkpoint);
        propagate(upper, opacity, bounds, queue,checkpoint);
        checkpoint.run();
        return new Result(bounds, lower, upper);
    }

    private static int preflight(Bounds bounds, int maxCells,int hardMaximum) {
        if (maxCells < 1 || maxCells > hardMaximum) {
            throw new IllegalArgumentException("maxCells must be 1.." + hardMaximum);
        }
        validateAxis(bounds.minX(), bounds.sizeX());
        validateAxis(bounds.minY(), bounds.sizeY());
        validateAxis(bounds.minZ(), bounds.sizeZ());
        // Compare before multiplying, including products too large even for a long.
        long count = bounds.sizeX();
        if (count > maxCells / (long) bounds.sizeY()) {
            throw new IllegalArgumentException("cell budget exceeded");
        }
        count *= bounds.sizeY();
        if (count > maxCells / (long) bounds.sizeZ()) {
            throw new IllegalArgumentException("cell budget exceeded");
        }
        return (int) (count * bounds.sizeZ());
    }

    private static void validateAxis(int minimum, int size) {
        if (size <= 0 || (long) minimum + size - 1 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid or overflowing bounds axis");
        }
    }

    private static void propagate(byte[] light, byte[] opacity, Bounds bounds, CellQueue queue,Runnable checkpoint) {
        for (int i = 0; i < light.length; i++) {
            if((i&1023)==0)checkpoint.run();
            if (light[i] > 1) queue.add(i);
        }
        int sx = bounds.sizeX(), sz = bounds.sizeZ(), plane = sx * sz;
        int visits=0;while (!queue.isEmpty()) {
            if((visits++&1023)==0)checkpoint.run();
            int cell = queue.remove();
            int source = light[cell];
            int x = cell % sx, y = cell / plane, z = (cell % plane) / sx;
            if (x > 0) offer(light, opacity, queue, cell - 1, source);
            if (x < sx - 1) offer(light, opacity, queue, cell + 1, source);
            if (z > 0) offer(light, opacity, queue, cell - sx, source);
            if (z < sz - 1) offer(light, opacity, queue, cell + sx, source);
            if (y > 0) offer(light, opacity, queue, cell - plane, source);
            if (y < bounds.sizeY() - 1) offer(light, opacity, queue, cell + plane, source);
        }
    }

    private static void offer(byte[] light, byte[] opacity, CellQueue queue, int destination, int source) {
        int next = source - Math.max(1, opacity[destination]);
        if (next > light[destination]) {
            light[destination] = (byte) next;
            if (next > 1) queue.add(destination);
        }
    }

    /** One ring slot per cell; pending bits prohibit duplicates even after repeated improvements. */
    private static final class CellQueue {
        private final int[] cells;
        private final long[] pending;
        private int head, tail, size;
        CellQueue(int count) {
            cells = new int[count];
            pending = new long[(count + 63) / 64];
        }
        boolean isEmpty() { return size == 0; }
        void add(int cell) {
            int word = cell >>> 6;
            long bit = 1L << (cell & 63);
            if ((pending[word] & bit) != 0) return;
            pending[word] |= bit;
            cells[tail] = cell;
            if (++tail == cells.length) tail = 0;
            size++;
        }
        int remove() {
            int cell = cells[head];
            if (++head == cells.length) head = 0;
            pending[cell >>> 6] &= ~(1L << (cell & 63));
            size--;
            return cell;
        }
    }

    public static final class Result {
        private final Bounds bounds;
        private final byte[] lower, upper;
        private Result(Bounds bounds, byte[] lower, byte[] upper) {
            this.bounds = bounds;
            this.lower = lower;
            this.upper = upper;
        }
        public Bounds bounds(){return bounds;}
        public byte[] lowerSnapshot(){return lower.clone();}
        public byte[] upperSnapshot(){return upper.clone();}
        /** Empty means outside the volume or uncertain, never inferred darkness. */
        public OptionalInt value(int x, int y, int z) {
            long localX = (long) x - bounds.minX();
            long localY = (long) y - bounds.minY();
            long localZ = (long) z - bounds.minZ();
            if (localX < 0 || localX >= bounds.sizeX() || localY < 0 || localY >= bounds.sizeY()
                    || localZ < 0 || localZ >= bounds.sizeZ()) return OptionalInt.empty();
            int index = (int) (localX + bounds.sizeX() * (localZ + bounds.sizeZ() * localY));
            return lower[index] == upper[index] ? OptionalInt.of(lower[index]) : OptionalInt.empty();
        }
    }
}
