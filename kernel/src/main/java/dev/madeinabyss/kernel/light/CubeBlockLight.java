package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/** Bounded block light only. Directional face occlusion is outside this scalar input contract. */
public final class CubeBlockLight {
    /** Allocation safety cap for this bounded solve, not a gameplay/world depth limit. */
    public static final int MAX_CUBES = 256;
    private CubeBlockLight() { }

    public static Result solve(Collection<CubeLightInput> inputs, int maxCubes) {
        Objects.requireNonNull(inputs, "inputs");
        if (maxCubes < 1 || maxCubes > MAX_CUBES) {
            throw new IllegalArgumentException("maxCubes must be 1.." + MAX_CUBES);
        }
        if (inputs.size() > maxCubes) throw new IllegalArgumentException("cube budget exceeded");
        var copies = List.copyOf(inputs);
        if (copies.size() > maxCubes) throw new IllegalArgumentException("cube budget exceeded");
        var indices = new HashMap<CubeKey, Integer>();
        byte[][] emission = new byte[copies.size()][];
        byte[][] opacity = new byte[copies.size()][];
        for (int c = 0; c < copies.size(); c++) {
            var input = copies.get(c);
            if (indices.putIfAbsent(input.key(), c) != null) {
                throw new IllegalArgumentException("duplicate cube " + input.key());
            }
            emission[c] = input.emission();
            opacity[c] = input.opacity();
        }

        int[][] adjacent = new int[copies.size()][6];
        for (int c = 0; c < copies.size(); c++) {
            var key = copies.get(c).key();
            adjacent[c][0] = indices.getOrDefault(new CubeKey(key.x() - 1, key.y(), key.z()), -1);
            adjacent[c][1] = indices.getOrDefault(new CubeKey(key.x() + 1, key.y(), key.z()), -1);
            adjacent[c][2] = indices.getOrDefault(new CubeKey(key.x(), key.y() - 1, key.z()), -1);
            adjacent[c][3] = indices.getOrDefault(new CubeKey(key.x(), key.y() + 1, key.z()), -1);
            adjacent[c][4] = indices.getOrDefault(new CubeKey(key.x(), key.y(), key.z() - 1), -1);
            adjacent[c][5] = indices.getOrDefault(new CubeKey(key.x(), key.y(), key.z() + 1), -1);
        }

        byte[][] lower = copyArrays(emission);
        byte[][] upper = copyArrays(emission);
        for (int c = 0; c < upper.length; c++) {
            for (int local = 0; local < 4096; local++) {
                int x = local & 15, y = local >>> 8, z = (local >>> 4) & 15;
                boolean touchesUnknown = (x == 0 && adjacent[c][0] < 0)
                        || (x == 15 && adjacent[c][1] < 0)
                        || (y == 0 && adjacent[c][2] < 0)
                        || (y == 15 && adjacent[c][3] < 0)
                        || (z == 0 && adjacent[c][4] < 0)
                        || (z == 15 && adjacent[c][5] < 0);
                if (touchesUnknown) {
                    // An unknown neighbor may emit 15; opacity belongs to this destination cell.
                    upper[c][local] = (byte) Math.max(upper[c][local], 15 - Math.max(1, opacity[c][local]));
                }
            }
        }
        propagate(lower, opacity, adjacent);
        propagate(upper, opacity, adjacent);
        return new Result(Map.copyOf(indices), lower, upper);
    }

    private static byte[][] copyArrays(byte[][] source) {
        byte[][] copy = new byte[source.length][];
        for (int c = 0; c < source.length; c++) copy[c] = source[c].clone();
        return copy;
    }

    private static void propagate(byte[][] light, byte[][] opacity, int[][] adjacent) {
        var queue = new CellQueue(light.length * 4096);
        for (int c = 0; c < light.length; c++) {
            for (int local = 0; local < 4096; local++) {
                if (light[c][local] > 0) queue.add((c << 12) | local);
            }
        }
        while (!queue.isEmpty()) {
            int cell = queue.remove();
            int c = cell >>> 12, local = cell & 4095;
            int x = local & 15, y = local >>> 8, z = (local >>> 4) & 15;
            int source = light[c][local];
            if (source <= 1) continue;
            offer(light, opacity, queue, x > 0 ? c : adjacent[c][0], x > 0 ? local - 1 : local + 15, source);
            offer(light, opacity, queue, x < 15 ? c : adjacent[c][1], x < 15 ? local + 1 : local - 15, source);
            offer(light, opacity, queue, y > 0 ? c : adjacent[c][2], y > 0 ? local - 256 : local + 3840, source);
            offer(light, opacity, queue, y < 15 ? c : adjacent[c][3], y < 15 ? local + 256 : local - 3840, source);
            offer(light, opacity, queue, z > 0 ? c : adjacent[c][4], z > 0 ? local - 16 : local + 240, source);
            offer(light, opacity, queue, z < 15 ? c : adjacent[c][5], z < 15 ? local + 16 : local - 240, source);
        }
    }

    private static void offer(byte[][] light, byte[][] opacity, CellQueue queue, int c, int local, int source) {
        if (c < 0) return;
        int candidate = source - Math.max(1, opacity[c][local]);
        if (candidate > light[c][local]) {
            light[c][local] = (byte) candidate;
            queue.add((c << 12) | local);
        }
    }

    /** Ring storage and pending bits allow at most one queued entry per known cell. */
    private static final class CellQueue {
        private final int[] cells;
        private final boolean[] pending;
        private int head, tail, size;

        CellQueue(int capacity) {
            cells = new int[capacity];
            pending = new boolean[capacity];
        }

        boolean isEmpty() { return size == 0; }

        void add(int cell) {
            if (pending[cell]) return;
            cells[tail] = cell;
            tail = (tail + 1) % cells.length;
            pending[cell] = true;
            size++;
        }

        int remove() {
            int cell = cells[head];
            head = (head + 1) % cells.length;
            pending[cell] = false;
            size--;
            return cell;
        }
    }

    public static final class Result {
        private final Map<CubeKey, Integer> indices;
        private final byte[][] lower;
        private final byte[][] upper;

        private Result(Map<CubeKey, Integer> indices, byte[][] lower, byte[][] upper) {
            this.indices = indices;
            this.lower = lower;
            this.upper = upper;
        }

        /** Empty means absent or uncertain, never an inferred AIR/darkness value. */
        public OptionalInt value(BlockCoordinate position) {
            Objects.requireNonNull(position, "position");
            var c = indices.get(CubeKey.fromBlock(position));
            if (c == null) return OptionalInt.empty();
            int local = Math.floorMod(position.x(), 16) | (Math.floorMod(position.z(), 16) << 4)
                    | (Math.floorMod(position.y(), 16) << 8);
            return lower[c][local] == upper[c][local] ? OptionalInt.of(lower[c][local]) : OptionalInt.empty();
        }

        public Set<CubeKey> keys() { return indices.keySet(); }
    }
}
