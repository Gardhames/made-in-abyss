package dev.madeinabyss.kernel.tick;

import dev.madeinabyss.kernel.BlockCoordinate;
import java.util.Objects;

public record SavedCubeTick(Kind kind, String type, BlockCoordinate position, long delay, int priority, long order) {
    public enum Kind { BLOCK, FLUID }
    public SavedCubeTick { validate(kind, type, position, priority); }
    static void validate(Kind kind, String type, BlockCoordinate position, int priority) {
        Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(position, "position"); Objects.requireNonNull(type, "type");
        if (!type.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("canonical namespaced tick type required");
        if (priority < -3 || priority > 3) throw new IllegalArgumentException("tick priority must be -3..3");
    }
}
