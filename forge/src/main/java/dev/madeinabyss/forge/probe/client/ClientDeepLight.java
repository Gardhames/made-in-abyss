package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepLightNetwork;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.CubeLightInput;
import dev.madeinabyss.kernel.light.ObserverLightWindow;
import net.minecraft.world.level.Level;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Main-thread scalar cache bound to the exact physical mirror/session. Keeps no strong Level reference. */
public final class ClientDeepLight {
    private static UUID session;
    private static boolean enabled, available;
    private static WeakReference<Level> level = new WeakReference<>(null);
    private static long geometryRevision = -1, revision = -1;
    private static Set<CubeKey> core = Set.of(), desired = Set.of();
    private static List<CubeLightInput> published = List.of();
    private static DeepLightNetwork.Snapshot pending;
    private static String error = "", lastCause = "";
    private ClientDeepLight() { }

    public static void begin(UUID token, boolean active) {
        clear(); session = token; enabled = active;
        DeepLightNetwork.receiver(packet->ClientWindowPreparation.after(()->receive(packet)));
    }

    public static void geometry(Level current, long nextRevision, Set<CubeKey> keys) {
        if (!enabled || session == null) return;
        var nextDesired = ObserverLightWindow.keys(keys);
        core = Set.copyOf(keys); desired = nextDesired;
        level = new WeakReference<>(current); geometryRevision = nextRevision;
        markUnavailable("Awaiting scalar inputs for current geometry", "geometry_changed");
        if (pending != null && pending.geometryRevision() == geometryRevision) {
            var packet = pending; pending = null; receive(packet);
        } else if (pending != null && pending.geometryRevision() < geometryRevision) pending = null;
    }

    public static void receive(DeepLightNetwork.Snapshot packet) {
        if (!enabled || session == null || !session.equals(packet.session()) || packet.revision() <= revision) return;
        if (packet.geometryRevision() < geometryRevision) return;
        if (packet.geometryRevision() > geometryRevision) {
            // One bounded future packet; the geometry channel may be delivered later on the main thread.
            if (pending == null || packet.geometryRevision() > pending.geometryRevision()
                    || (packet.geometryRevision() == pending.geometryRevision() && packet.revision() > pending.revision())) pending = packet;
            markUnavailable("Scalar packet awaits its geometry revision", "future_geometry");
            return;
        }
        if (pending != null && pending.geometryRevision() > geometryRevision && packet.revision() < pending.revision()) return;
        var current = level.get();
        if (current == null) { clear(); return; }
        if (!packet.available()) {
            markUnavailable(packet.error(), "server_unavailable"); revision = packet.revision(); return;
        }
        try {
            var keys = new HashSet<CubeKey>();
            for (var input : packet.inputs()) {
                long firstY = (long) input.key().y() * 16;
                if (!desired.contains(input.key()) || firstY < DeepCubeDomain.TECHNICAL_BOTTOM
                        || firstY + 15 >= current.getMinBuildHeight() || !keys.add(input.key())) {
                    throw new IllegalArgumentException("Invalid light dependency key " + input.key());
                }
            }
            for (var key : core) {
                long firstY = (long) key.y() * 16;
                if (firstY >= DeepCubeDomain.TECHNICAL_BOTTOM && firstY + 15 < current.getMinBuildHeight()
                        && !keys.contains(key)) throw new IllegalArgumentException("Missing current deep geometry light input " + key);
            }
            var attachment = DeepCubeDomain.lookup(current).orElseThrow();
            // Adapter validates and owns its defensive copy before publishing the generation.
            attachment.blockLight().dependencies(packet.inputs(), true);
            published = packet.inputs(); revision = packet.revision(); available = true;
            error = ""; lastCause = "scalar_snapshot";
        } catch (RuntimeException failure) {
            markUnavailable(failure.toString(), "invalid_scalar_snapshot");
            // A newer invalid snapshot also consumes its revision. Replaying an older valid
            // packet must not restore scalar inputs that this observation already invalidated.
            revision = packet.revision();
        }
    }

    private static void markUnavailable(String reason, String cause) {
        available = false; error = reason; lastCause = cause;
        var current = level.get();
        if (current != null) DeepCubeDomain.lookup(current).ifPresent(a -> a.blockLight().dependencies(List.of(), false));
    }

    public static void invalidGeometry(String reason) {
        if (enabled && session != null) markUnavailable(reason, "invalid_geometry_snapshot");
    }

    public static JsonObject status() {
        var result = new JsonObject();
        result.addProperty("active", enabled && session != null && level.get() != null);
        result.addProperty("available", available);
        result.addProperty("geometryRevision", geometryRevision); result.addProperty("revision", revision);
        result.addProperty("desiredCubes", desired.size()); result.addProperty("inputCubes", published.size());
        result.addProperty("error", error); result.addProperty("lastCause", lastCause);
        var keys = new JsonArray();
        for (var input : published) {
            var key = new JsonArray(); key.add(input.key().x()); key.add(input.key().y()); key.add(input.key().z()); keys.add(key);
        }
        result.add("inputKeys", keys);
        return result;
    }

    public static void clear() {
        session = null; enabled = false; available = false; level = new WeakReference<>(null);
        geometryRevision = -1; revision = -1; core = Set.of(); desired = Set.of();
        published = List.of(); pending = null; error = ""; lastCause = "";
    }
}
