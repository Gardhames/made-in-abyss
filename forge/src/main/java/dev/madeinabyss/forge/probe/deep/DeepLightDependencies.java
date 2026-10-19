package dev.madeinabyss.forge.probe.deep;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.CubeLightInput;
import dev.madeinabyss.kernel.light.ObserverLightWindow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Resident-only observer scalar inputs. No store reads, generation, writes or residency tickets. */
public final class DeepLightDependencies {
    public static final int MAX_SCENES = 4;
    public static final int MAX_DESIRED_BINDINGS = 500;
    private static final Map<UUID, Scene> SCENES = new HashMap<>();
    private DeepLightDependencies() { }

    private static final class Scene {
        final UUID token;
        final ServerLevel level;
        Set<CubeKey> core, desired;
        long geometryRevision, revision;
        boolean available;
        List<CubeLightInput> sent = List.of();
        String error = "", lastCause = "";

        Scene(UUID token, ServerLevel level, long geometryRevision, Set<CubeKey> core, Set<CubeKey> desired) {
            this.token = token; this.level = level; this.geometryRevision = geometryRevision;
            this.core = core; this.desired = desired;
        }
    }

    /** Called after geometry27 is sent, including a new replacement after movement. */
    public static void geometry(ServerPlayer player, UUID token, long geometryRevision, Set<CubeKey> core) {
        var desired = ObserverLightWindow.keys(core);
        var previous = SCENES.get(player.getUUID());
        if (previous == null && SCENES.size() >= MAX_SCENES) throw new IllegalStateException("Light scene budget exceeded");
        if (previous == null || !previous.token.equals(token) || previous.level != player.level()) {
            previous = new Scene(token, player.serverLevel(), geometryRevision, Set.copyOf(core), desired);
            SCENES.put(player.getUUID(), previous);
        } else {
            previous.geometryRevision = geometryRevision;
            previous.core = Set.copyOf(core); previous.desired = desired;
        }
        sync(player, previous, "geometry_changed");
    }

    /** Content changes fan out to every observer whose light halo intersects the changed cube. */
    public static void changed(ServerLevel level, CubeKey changed) {
        for (var entry : SCENES.entrySet()) {
            var scene = entry.getValue();
            if (scene.level != level || !scene.desired.contains(changed)) continue;
            var player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.level() == level) sync(player, scene, "resident_edit");
        }
    }

    /** Load/unload may change unknown boundaries for other stationary owners. */
    public static void residencyChanged(ServerLevel level, Set<CubeKey> changedKeys) {
        residencyChanged(level, changedKeys, null);
    }

    public static void residencyChanged(ServerLevel level, Set<CubeKey> changedKeys, UUID alreadySynced) {
        if (changedKeys.isEmpty()) return;
        for (var entry : SCENES.entrySet()) {
            if (entry.getKey().equals(alreadySynced)) continue;
            var scene = entry.getValue();
            if (scene.level != level || java.util.Collections.disjoint(scene.desired, changedKeys)) continue;
            var player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.level() == level) sync(player, scene, "residency_changed");
        }
    }

    private static void sync(ServerPlayer player, Scene scene, String cause) {
        long nextRevision = Math.incrementExact(scene.revision);
        try {
            var attachment = DeepCubeDomain.lookup(scene.level).orElseThrow();
            long generation = attachment.space().contentGeneration();
            var inputs = attachment.blockLight().deepInputs().stream()
                    .filter(input -> scene.desired.contains(input.key()))
                    .sorted(java.util.Comparator.comparingInt((CubeLightInput input) -> input.key().x())
                            .thenComparingInt(input -> input.key().y()).thenComparingInt(input -> input.key().z())).toList();
            var inputKeys = new java.util.HashSet<CubeKey>();
            for (var input : inputs) {
                if (!inputKeys.add(input.key()) || !attachment.space().isLoaded(input.key())) {
                    throw new IllegalArgumentException("Invalid resident light input " + input.key());
                }
            }
            for (var key : scene.core) {
                long firstY = (long) key.y() * 16;
                if (firstY >= DeepCubeDomain.TECHNICAL_BOTTOM && firstY + 15 < scene.level.getMinBuildHeight()
                        && !inputKeys.contains(key)) throw new IllegalStateException("Missing deep core light input " + key);
            }
            if (attachment.space().contentGeneration() != generation) throw new IllegalStateException("Light inputs changed during staging");
            var snapshot = new DeepLightNetwork.Snapshot(scene.token, scene.geometryRevision, nextRevision, true, "", inputs);
            DeepLightNetwork.send(player, snapshot);
            scene.sent = inputs; scene.revision = nextRevision; scene.available = true;
            scene.error = ""; scene.lastCause = cause;
        } catch (RuntimeException failure) {
            scene.available = false; scene.error = failure.toString(); scene.lastCause = cause + "_failed";
            String wireError = scene.error.length() > 512 ? scene.error.substring(0, 512) : scene.error;
            // A failed update must invalidate the client, not leave an old result falsely current.
            try {
                DeepLightNetwork.send(player, new DeepLightNetwork.Snapshot(scene.token, scene.geometryRevision,
                        nextRevision, false, wireError, List.of()));
                scene.revision = nextRevision;
            } catch (RuntimeException sendFailure) {
                scene.error += "; invalidation send failed: " + sendFailure;
            }
        }
    }

    public static void release(ServerPlayer player) { SCENES.remove(player.getUUID()); }
    public static void clear(MinecraftServer server) { SCENES.values().removeIf(scene -> scene.level.getServer() == server); }

    public static JsonObject status(ServerPlayer player) {
        var result = new JsonObject();
        var scene = SCENES.get(player.getUUID());
        result.addProperty("active", scene != null);
        result.addProperty("scenes", SCENES.size());
        result.addProperty("totalDesiredBindings", SCENES.values().stream().mapToInt(s -> s.desired.size()).sum());
        result.addProperty("totalInputBindings", SCENES.values().stream().mapToInt(s -> s.sent.size()).sum());
        if (scene != null) {
            result.addProperty("available", scene.available); result.addProperty("geometryRevision", scene.geometryRevision);
            result.addProperty("revision", scene.revision); result.addProperty("desiredCubes", scene.desired.size());
            result.addProperty("inputCubes", scene.sent.size()); result.addProperty("error", scene.error);
            result.addProperty("lastCause", scene.lastCause);
            var keys = new JsonArray();
            for (var input : scene.sent) {
                var key = new JsonArray(); key.add(input.key().x()); key.add(input.key().y()); key.add(input.key().z()); keys.add(key);
            }
            result.add("inputKeys", keys);
        }
        return result;
    }
}
