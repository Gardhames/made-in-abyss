package dev.madeinabyss.forge.probe;

/** Exact local-development identities; never enables probes on its own. */
public final class DevProbeAccess {
    private DevProbeAccess() {}
    public static boolean allowed(String name, boolean enabled) {
        return enabled && ("Dev".equals(name) || "MiaNative02".equals(name)
                || "MiaNative03".equals(name) || "MiaNative04".equals(name));
    }
}
