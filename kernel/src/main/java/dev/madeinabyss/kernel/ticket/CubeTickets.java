package dev.madeinabyss.kernel.ticket;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Independent section residency demand, with radii measured in 16-block cubes.
 * This is a demand ledger, not a loader: a released ticket does not discard or save cube data.
 * Each owner has exactly one rectangular window. Calls are serialized and rejected moves
 * leave both ownership and reference counts unchanged.
 */
public final class CubeTickets {
    // Kernel allocation guard, not a measured Minecraft simulation budget.
    private static final long MAX_REFERENCES = 1_048_576;
    private static final int MIN_SECTION = Math.floorDiv(Integer.MIN_VALUE, 16);
    private static final int MAX_SECTION = Math.floorDiv(Integer.MAX_VALUE, 16);
    private final int maxWindowCubes;
    private final int maxActiveCubes;
    private final int maxOwners;
    private final Map<String, Set<CubeKey>> owners = new HashMap<>();
    private final Map<CubeKey, Integer> references = new HashMap<>();

    /**
     * Explicit budgets must be positive. The worst-case number of owner/cube references
     * and active union are additionally capped at 1,048,576 to bound kernel allocations.
     */
    public CubeTickets(int maxWindowCubes, int maxActiveCubes, int maxOwners) {
        if (maxWindowCubes <= 0 || maxActiveCubes <= 0 || maxOwners <= 0
                || (long) maxWindowCubes * maxOwners > MAX_REFERENCES
                || maxActiveCubes > MAX_REFERENCES) {
            throw new IllegalArgumentException("Positive bounded ticket budgets are required");
        }
        this.maxWindowCubes = maxWindowCubes;
        this.maxActiveCubes = maxActiveCubes;
        this.maxOwners = maxOwners;
    }

    /** Replaces an owner's window and returns only changes to the global active union. */
    public synchronized Delta move(String owner, BlockCoordinate position,
                                   int horizontalRadius, int verticalRadius) {
        MovePlan plan = planMove(owner, position, horizontalRadius, verticalRadius);
        for (CubeKey key : plan.released()) release(key);
        for (CubeKey key : plan.acquired()) references.merge(key, 1, Integer::sum);
        owners.put(owner, plan.next());
        return plan.delta();
    }

    private MovePlan planMove(String owner, BlockCoordinate position, int horizontalRadius, int verticalRadius) {
        validateOwner(owner);
        Objects.requireNonNull(position, "position");
        if (!owners.containsKey(owner) && owners.size() >= maxOwners) {
            throw new IllegalArgumentException("Owner budget exceeded");
        }
        Set<CubeKey> next = window(position, horizontalRadius, verticalRadius);
        Set<CubeKey> previous = owners.getOrDefault(owner, Set.of());
        Set<CubeKey> acquired = difference(next, previous);
        Set<CubeKey> released = difference(previous, next);
        Set<CubeKey> added = new HashSet<>();
        Set<CubeKey> removed = new HashSet<>();
        for (CubeKey key : acquired) {
            if (!references.containsKey(key)) added.add(key);
        }
        for (CubeKey key : released) {
            if (references.get(key) == 1) removed.add(key);
        }
        long activeAfter = (long) references.size() + added.size() - removed.size();
        if (activeAfter > maxActiveCubes) {
            throw new IllegalArgumentException("Active cube budget exceeded");
        }
        return new MovePlan(next, acquired, released, new Delta(added, removed));
    }

    /** Disconnect releases demand; shared cubes remain active until their last owner leaves. */
    public synchronized Delta remove(String owner) {
        Delta delta = previewRemove(owner);
        Set<CubeKey> previous = owners.getOrDefault(owner, Set.of());
        for (CubeKey key : previous) release(key);
        owners.remove(owner);
        return delta;
    }

    public synchronized Map<CubeKey, Integer> snapshot() { return Map.copyOf(references); }

    public synchronized Set<CubeKey> ownerWindow(String owner) {
        validateOwner(owner);
        return owners.getOrDefault(owner, Set.of());
    }

    public synchronized int ownerCount() { return owners.size(); }

    public synchronized Delta previewMove(String owner, BlockCoordinate position, int horizontalRadius, int verticalRadius) {
        return planMove(owner, position, horizontalRadius, verticalRadius).delta();
    }

    public synchronized Delta previewRemove(String owner) {
        validateOwner(owner);
        Set<CubeKey> removed = new HashSet<>();
        for (CubeKey key : owners.getOrDefault(owner, Set.of())) {
            if (references.get(key) == 1) removed.add(key);
        }
        return new Delta(Set.of(), removed);
    }

    private record MovePlan(Set<CubeKey> next, Set<CubeKey> acquired, Set<CubeKey> released, Delta delta) { }

    private Set<CubeKey> window(BlockCoordinate position, int horizontalRadius, int verticalRadius) {
        if (horizontalRadius < 0 || verticalRadius < 0) {
            throw new IllegalArgumentException("Radii cannot be negative");
        }
        long width = 2L * horizontalRadius + 1;
        long height = 2L * verticalRadius + 1;
        // Check individual factors first: int-max radii must not overflow a volume product.
        if (width > maxWindowCubes || height > maxWindowCubes
                || width * width * height > maxWindowCubes) {
            throw new IllegalArgumentException("Window cube budget exceeded");
        }
        CubeKey center = CubeKey.fromBlock(position);
        validateRange(center.x(), horizontalRadius);
        validateRange(center.y(), verticalRadius);
        validateRange(center.z(), horizontalRadius);
        Set<CubeKey> result = new HashSet<>();
        for (int x = center.x() - horizontalRadius; x <= center.x() + horizontalRadius; x++) {
            for (int y = center.y() - verticalRadius; y <= center.y() + verticalRadius; y++) {
                for (int z = center.z() - horizontalRadius; z <= center.z() + horizontalRadius; z++) {
                    result.add(new CubeKey(x, y, z));
                }
            }
        }
        return Set.copyOf(result);
    }

    private static void validateRange(int center, int radius) {
        if ((long) center - radius < MIN_SECTION || (long) center + radius > MAX_SECTION) {
            throw new IllegalArgumentException("Window exceeds signed-int block coordinate domain");
        }
    }

    private static void validateOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) throw new IllegalArgumentException("Owner cannot be blank");
    }

    private static Set<CubeKey> difference(Set<CubeKey> left, Set<CubeKey> right) {
        Set<CubeKey> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private void release(CubeKey key) {
        int remaining = references.get(key) - 1;
        if (remaining == 0) references.remove(key);
        else references.put(key, remaining);
    }

    /** Newly demanded and no-longer-demanded cubes; neither set is an owner-local diff. */
    public record Delta(Set<CubeKey> added, Set<CubeKey> removed) {
        public Delta { added = Set.copyOf(added); removed = Set.copyOf(removed); }
    }
}
