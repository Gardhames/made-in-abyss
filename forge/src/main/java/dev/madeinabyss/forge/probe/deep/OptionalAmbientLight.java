package dev.madeinabyss.forge.probe.deep;

import net.minecraft.world.level.Level;

/** Optional ambient work cannot infer darkness from an unavailable BLOCK input. */
public final class OptionalAmbientLight {
    private OptionalAmbientLight() { }
    public static boolean tick(Level level, Runnable operation) {
        return OptionalLightWork.run(level, OptionalLightWork.Kind.AMBIENT, operation);
    }
    public static long skippedTicks() {return OptionalLightWork.skipped(OptionalLightWork.Kind.AMBIENT);}
}
