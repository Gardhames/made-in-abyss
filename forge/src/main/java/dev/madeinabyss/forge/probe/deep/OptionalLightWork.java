package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.cube.CubeBlockLightAccess;
import net.minecraft.world.level.Level;

import java.util.Objects;

/** Optional consumer guard; strict explicit light queries retain their unavailable contract. */
public final class OptionalLightWork {
    public enum Kind { AMBIENT, VIGNETTE, HANDS, DEBUG, ENTITY, PARTICLE, PARTICLE_BOUNDARY, ANIMATION }
    private static final long[] skips = new long[Kind.values().length];
    private OptionalLightWork() { }

    public static boolean run(Level level, Kind kind, Runnable operation) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operation, "operation");
        try {
            operation.run();
            return true;
        } catch (CubeBlockLightAccess.UnavailableLightException unknown) {
            if (!DeepCubeDomain.blockLightEnabled(level)) throw unknown;
            // Abort this optional consumer. Never substitute a numeric light value for unknown input.
            skips[kind.ordinal()]++;
            return false;
        }
    }

    public static long skipped(Kind kind) { return skips[Objects.requireNonNull(kind, "kind").ordinal()]; }
    public static void recordParticleBoundary() { skips[Kind.PARTICLE_BOUNDARY.ordinal()]++; }
}
