package dev.madeinabyss.forge.probe.deep;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeResidencyService;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** Server-thread ORIGINAL inert visual fixture. No physics owners, loads or disk writes. */
public final class DeepVisualRuntime {
    private static final int MAX_SCENES = 4;
    private static final Map<UUID, Scene> SCENES = new HashMap<>();
    private static boolean registered;

    private DeepVisualRuntime() {}

    private static final class Scene {
        final UUID token;
        final Level level;
        final CubeResidencyService residency;
        final StableCubeStore store;
        final Function<CubeKey, CubeSnapshot> generator;
        final BlockCoordinate initial;
        final ToIntFunction<CubeKey> minimumCubeY;
        int publishedMinimumCubeY;
        CubeKey center;
        CubeKey desiredCenter;
        long targetGeneration,desiredNanos,lastSendNanos,lastSendTargetGeneration;
        int missingIncoming;
        Set<CubeKey> sent = Set.of();
        final Set<CubeKey> dirty = new HashSet<>();
        long revision,deferredWindows;
        int packetBytes;
        String error = "";

        Scene(ServerPlayer player, UUID token, CubeResidencyService residency, StableCubeStore store,
              Function<CubeKey, CubeSnapshot> generator, BlockCoordinate initial, ToIntFunction<CubeKey> minimumCubeY) {
            this.token = Objects.requireNonNull(token);
            level = player.level();
            this.residency = Objects.requireNonNull(residency);
            this.store = Objects.requireNonNull(store);
            this.generator = Objects.requireNonNull(generator);
            this.initial = Objects.requireNonNull(initial);
            this.minimumCubeY = minimumCubeY;
        }
    }

    public static void register() {
        if (registered) return;
        registered = true;
        DeepVisualNetwork.register();
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) ->
                SCENES.entrySet().removeIf(entry -> entry.getValue().level.getServer() == event.getServer()));
    }

    public static void start(ServerPlayer player, UUID token, CubeResidencyService residency,
                             StableCubeStore store, Function<CubeKey, CubeSnapshot> generator,
                             BlockCoordinate initial) {
        start(player, token, residency, store, generator, initial, Integer.MIN_VALUE / 16);
    }
    public static void start(ServerPlayer player, UUID token, CubeResidencyService residency,
                             StableCubeStore store, Function<CubeKey, CubeSnapshot> generator,
                             BlockCoordinate initial, int minimumCubeY) {
        start(player,token,residency,store,generator,initial,center->minimumCubeY);
    }
    public static void start(ServerPlayer player, UUID token, CubeResidencyService residency,
                             StableCubeStore store, Function<CubeKey, CubeSnapshot> generator,
                             BlockCoordinate initial, ToIntFunction<CubeKey> minimumCubeY) {
        if (SCENES.containsKey(player.getUUID())) throw new IllegalStateException("Visual scene already attached");
        if (SCENES.size() >= MAX_SCENES) throw new IllegalStateException("Visual scene budget exceeded");
        var scene = new Scene(player, token, residency, store, generator, initial, minimumCubeY);
        SCENES.put(player.getUUID(), scene);
        replace(player, scene, CubeKey.fromBlock(initial));
    }

    public static void update(ServerPlayer player) {
        var scene = SCENES.get(player.getUUID());
        if (scene == null) return;
        if (player.level() != scene.level) { release(player); return; }
        CubeKey desired = CubeKey.fromBlock(new BlockCoordinate(player.blockPosition().getX(),
                player.blockPosition().getY(), player.blockPosition().getZ()));
        // Even before the first complete visual window arrives, the actual current camera
        // supersedes the admission anchor. The original target must not survive a teleport.
        desire(scene,desired);
        long vertical = scene.center == null ? 0 : (long) desired.y() - scene.center.y();
        // The smaller upward buffer needs an earlier refresh; this does not certify ascent physics.
        if (scene.center == null || desired.x() != scene.center.x() || desired.z() != scene.center.z()
                || vertical <= -4 || vertical >= 2 || !scene.dirty.isEmpty()) replace(player, scene, desired, scene.residency.loader() != null);
    }

    /** Requests visual windows around extrapolated cube centers (nearest first) before they are demanded. */
    public static void prefetch(ServerPlayer player, List<CubeKey> centers) {
        var scene = SCENES.get(player.getUUID());
        if (scene == null || scene.residency.loader() == null) return;
        var loader = scene.residency.loader();
        for (int i = 0; i < centers.size(); i++) {
            var center = centers.get(i);
            int minimumCubeY = scene.minimumCubeY.applyAsInt(center);
            if (minimumCubeY > center.y()) continue;
            for (CubeKey key : DeepVisualNetwork.windowKeys(center, minimumCubeY))
                if (!scene.sent.contains(key) && !scene.residency.space().isLoaded(key)) loader.request(key, 1 + i);
        }
    }

    /** A clean already-sent visual cube needs no speculative reload; physics still resolves independently. */
    static boolean alreadySent(ServerPlayer player,CubeKey key){
        var scene=SCENES.get(player.getUUID());
        return scene!=null&&scene.level==player.level()&&scene.sent.contains(key)&&!scene.dirty.contains(key);
    }

    private static void replace(ServerPlayer player, Scene scene, CubeKey center) { replace(player, scene, center, false); }
    private static void desire(Scene scene,CubeKey center){
        if(center.equals(scene.desiredCenter))return;
        scene.desiredCenter=center;scene.targetGeneration++;scene.desiredNanos=System.nanoTime();
    }
    /** With defer, any incoming cube that is not resident or prepared keeps the previous window this tick. */
    private static void replace(ServerPlayer player, Scene scene, CubeKey center, boolean defer) {
        try {
            desire(scene,center);
            int minimumCubeY=scene.minimumCubeY.applyAsInt(center);
            Set<CubeKey> desired = DeepVisualNetwork.windowKeys(center, minimumCubeY);
            List<CubeKey> incoming = new ArrayList<>(desired);
            incoming.removeAll(scene.sent);
            for (CubeKey key : scene.dirty) if (desired.contains(key) && !incoming.contains(key)) incoming.add(key);
            List<CubeKey> removed = new ArrayList<>(scene.sent);
            removed.removeAll(desired);
            List<CubeSnapshot> snapshots = new ArrayList<>();
            int missing = 0;
            for (CubeKey key : incoming) {
                var snapshot = defer ? prepared(scene, key) : resolve(scene, key);
                if (snapshot == null) missing++; else snapshots.add(snapshot);
            }
            scene.missingIncoming=missing;
            if (missing > 0) { scene.deferredWindows++; return; }
            List<byte[]> frames = DeepVisualNetwork.frames(player, snapshots);
            var window = new DeepVisualNetwork.Window(scene.token, Math.incrementExact(scene.revision),
                    scene.center == null, center, List.copyOf(removed), frames, minimumCubeY);
            DeepVisualNetwork.send(player, window);
            // All resolver reads, generation, encoding and enqueue validation succeeded first.
            scene.sent = Set.copyOf(desired);
            scene.center = center;
            scene.publishedMinimumCubeY=minimumCubeY;
            scene.revision = window.revision();
            scene.packetBytes = frames.stream().mapToInt(frame -> frame.length).sum();
            scene.lastSendNanos=System.nanoTime();
            scene.lastSendTargetGeneration=scene.targetGeneration;
            scene.dirty.clear();
            scene.error = "";
        } catch (IOException | RuntimeException error) { scene.error = error.toString(); }
    }

    /** Resident or version-valid prepared snapshot; otherwise requests it and returns null. */
    private static CubeSnapshot prepared(Scene scene, CubeKey key) throws IOException {
        var resident = scene.residency.space().snapshot(key);
        if (resident.isPresent()) return resident.orElseThrow();
        var loader = scene.residency.loader();
        var ready = loader.peek(key);
        if (ready.isPresent()) return ready.orElseThrow();
        loader.request(key, 1); // The current 27-cube physical ring always precedes full visual work.
        return null;
    }

    private static CubeSnapshot resolve(Scene scene, CubeKey key) throws IOException {
        var resident = scene.residency.space().snapshot(key);
        CubeSnapshot snapshot;
        if (resident.isPresent()) snapshot = resident.orElseThrow();
        else {
            var saved = scene.store.read(key); // errors cannot fall through to generation
            snapshot = saved.isPresent() ? saved.orElseThrow() : scene.generator.apply(key);
        }
        if (snapshot == null || !key.equals(snapshot.key())) throw new IllegalArgumentException("Visual snapshot coordinate mismatch");
        return snapshot;
    }

    /** Refresh an edited resident cube without changing any owner's physics window. */
    public static void changed(Level level, CubeKey key) {
        if (level.isClientSide || level.getServer() == null) return;
        for (var entry : SCENES.entrySet()) {
            var scene = entry.getValue();
            if (scene.level != level || !scene.sent.contains(key)) continue;
            var player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.level() != level) continue;
            try {
                List<byte[]> frames = DeepVisualNetwork.frames(player, List.of(resolve(scene, key)));
                var window = new DeepVisualNetwork.Window(scene.token, Math.incrementExact(scene.revision),
                        false, scene.center, List.of(), frames, scene.publishedMinimumCubeY);
                DeepVisualNetwork.send(player, window);
                scene.revision = window.revision();
                scene.packetBytes = frames.get(0).length;
                scene.dirty.remove(key);
                if (scene.dirty.isEmpty()) scene.error = "";
            } catch (IOException | RuntimeException error) {
                scene.dirty.add(key);
                scene.error = error.toString();
            }
        }
    }

    public static void release(ServerPlayer player) { SCENES.remove(player.getUUID()); }

    public static JsonObject status(ServerPlayer player) {
        var report = new JsonObject();
        var scene = SCENES.get(player.getUUID());
        report.addProperty("active", scene != null);
        report.addProperty("scenes", SCENES.size());
        report.addProperty("maxScenes", MAX_SCENES);
        report.addProperty("maxCubes", DeepVisualNetwork.MAX_CUBES);
        report.addProperty("viewDown", 256);
        report.addProperty("viewUp", 64);
        if (scene != null) {
            report.addProperty("token", scene.token.toString());
            report.addProperty("revision", scene.revision);
            report.addProperty("cubeCount", scene.sent.size());
            report.addProperty("minimumCubeY", scene.publishedMinimumCubeY);
            report.addProperty("clippedUnknownCubes", DeepVisualNetwork.MAX_CUBES - scene.sent.size());
            report.addProperty("frameBytes", scene.packetBytes);
            report.addProperty("pendingChangedCubes", scene.dirty.size());
            report.addProperty("error", scene.error);
            report.addProperty("deferredWindows", scene.deferredWindows);
            report.addProperty("targetGeneration",scene.targetGeneration);
            report.addProperty("missingIncomingCubes",scene.missingIncoming);
            if(scene.desiredCenter!=null){
                var desired=new JsonObject();desired.addProperty("x",scene.desiredCenter.x());desired.addProperty("y",scene.desiredCenter.y());desired.addProperty("z",scene.desiredCenter.z());
                desired.addProperty("ageMillis",(System.nanoTime()-scene.desiredNanos)/1e6);report.add("desiredCube",desired);
                report.addProperty("lastSendTargetGeneration",scene.lastSendTargetGeneration);
                report.addProperty("lastSendElapsedMillis",scene.lastSendTargetGeneration!=scene.targetGeneration?-1:(scene.lastSendNanos-scene.desiredNanos)/1e6);
            }
            report.add("asyncCubeSaving", DeepCubeRuntime.saveStatus(scene.residency));
            var loader = scene.residency.loader();
            if (loader != null) {
                var m = loader.metrics();var load = scene.residency.loadMetrics();
                var async = new JsonObject();
                async.addProperty("requests", m.requests());async.addProperty("generated", m.generated());async.addProperty("read", m.read());
                async.addProperty("accepted", m.accepted());async.addProperty("stale", m.stale());async.addProperty("evicted", m.evicted());
                async.addProperty("failures", m.failures());async.addProperty("ready", m.ready());async.addProperty("inFlight", m.inFlight());
                async.addProperty("lastFailure", m.lastFailure());
                async.addProperty("queued",m.queued());async.addProperty("running",m.running());
                async.addProperty("interestOwners",m.interestOwners());async.addProperty("interestCubes",m.interestCubes());
                async.addProperty("interestRevision",m.interestRevision());async.addProperty("cancelledQueued",m.cancelledQueued());
                async.addProperty("cancelledRunning",m.cancelledRunning());async.addProperty("cancelledExits",m.cancelledExits());
                async.addProperty("reprioritized",m.reprioritized());async.addProperty("maxQueueWaitMillis",m.maxQueueWaitMillis());
                async.addProperty("deferredPhysicsMoves", load.deferredMoves());async.addProperty("preparedPhysicsLoads", load.preparedLoads());
                async.addProperty("ownerThreadPhysicsLoads", load.ownerThreadLoads());
                report.add("asyncCubeLoading", async);
                var trace=new JsonArray();var events=loader.traces();
                for(int i=Math.max(0,events.size()-64);i<events.size();i++){
                    var event=events.get(i);var cube=new JsonObject();cube.addProperty("x",event.key().x());cube.addProperty("y",event.key().y());cube.addProperty("z",event.key().z());
                    cube.addProperty("taskSequence",event.taskSequence());cube.addProperty("priority",event.priority());cube.addProperty("phase",event.phase());
                    cube.addProperty("elapsedMillis",event.elapsedMillis());cube.addProperty("durationMillis",event.durationMillis());trace.add(cube);
                }
                report.add("cubeLoadTrace",trace);
            }
            if (scene.center != null) {
                report.addProperty("centerX", scene.center.x());
                report.addProperty("centerY", scene.center.y());
                report.addProperty("centerZ", scene.center.z());
            }
        }
        return report;
    }
}
