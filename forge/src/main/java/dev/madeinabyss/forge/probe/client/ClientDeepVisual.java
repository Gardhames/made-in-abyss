package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import dev.madeinabyss.forge.cube.CubeBlockLightAccess;
import dev.madeinabyss.forge.cube.CubeBlockView;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepVisualNetwork;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.concurrent.OrderedOwnerPreparation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Main-thread bounded visual cache. Never attached to Level's physics CubeDomain. */
public final class ClientDeepVisual {
    private static UUID token;
    private static long revision = -1;
    private static long generation;
    private static int minimumCubeY = Integer.MIN_VALUE / 16;
    private static WeakReference<Level> level = new WeakReference<>(null);
    private static SparseCubeSpace space;
    private static Set<CubeKey> keys = Set.of();
    private static JsonObject report = new JsonObject();
    private static boolean geometryAvailable;
    private static CubeBlockLightAccess lightCache;
    private static WeakReference<SparseCubeSpace> lightPhysical = new WeakReference<>(null);
    private static WeakReference<SparseCubeSpace> lightVisual = new WeakReference<>(null);
    private static long lightPhysicalGeneration = -1, lightVisualRevision = -1;

    private ClientDeepVisual() {}

    public static void begin(UUID session) {
        begin(session, NativeClientBridge.current());
    }

    public static void begin(UUID session, Level current) {
        clear();
        token = session;
        level = new WeakReference<>(current);
        report.addProperty("token", session.toString());
        report.addProperty("completed", false);
    }

    public static void receive(DeepVisualNetwork.Window window) {
        var current=NativeClientBridge.current();var requested=new WeakReference<Level>(current);long admittedGeneration=generation;
        ClientWindowPreparation.submit(current,GeneratedFrameCosts.Stage.VISUAL_APPLY,
                ()->admittedGeneration==generation?prepare(requested.get(),window):null);
    }

    public static void receive(Level current, DeepVisualNetwork.Window window) {
        ClientWindowPreparation.inline(GeneratedFrameCosts.Stage.VISUAL_APPLY,()->prepare(current,window));
    }
    private static OrderedOwnerPreparation.Work<ClientWindowFrames.Decoded> prepare(Level current, DeepVisualNetwork.Window window) {
        if (token == null || !token.equals(window.token()) || window.revision() <= revision) return null;
        if (current == null || current != level.get()) return null;
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.WINDOW_VALIDATE);
        try {
            DeepVisualNetwork.validate(window);
            if (window.full() ? revision != -1 || window.revision() != 1
                    : space == null || window.revision() != Math.incrementExact(revision))
                throw new IllegalArgumentException("Visual revision/full sequence mismatch");
            Integer localMinimum=ClientGeneratedLight.visualMinimumCubeY(current,window.token(),window.center());
            if(localMinimum!=null ? window.minimumCubeY()!=localMinimum : !window.full() && window.minimumCubeY()!=minimumCubeY)
                throw new IllegalArgumentException("Visual domain differs from admitted local recipe");
            Set<CubeKey> expected = DeepVisualNetwork.windowKeys(window.center(), window.minimumCubeY());
            Set<CubeKey> removed = new HashSet<>(window.removed());
            if (removed.size() != window.removed().size() || !keys.containsAll(removed))
                throw new IllegalArgumentException("Duplicate or unknown visual removals");
            Set<CubeKey> requiredRemoved = new HashSet<>(keys);
            requiredRemoved.removeAll(expected);
            if (!removed.equals(requiredRemoved)) throw new IllegalArgumentException("Visual removal set mismatch");
            var biomes = current.registryAccess().registryOrThrow(Registries.BIOME);
            long admittedGeneration=generation,baseRevision=revision;var requested=new WeakReference<>(current);
            return new OrderedOwnerPreparation.Work<>(check->ClientWindowFrames.decode(window.frames(),biomes,expected,check),(decoded,error)->{
                var target=requested.get();
                if(admittedGeneration!=generation || baseRevision!=revision || target==null || target!=level.get())return;
                if(error!=null){refuse(target,error);return;}
                publish(target,window,expected,removed,decoded);
            });
        } catch(RuntimeException error){refuse(current,error);return null;}
        finally{GeneratedFrameCosts.end(timing);}
    }
    private static void publish(Level current,DeepVisualNetwork.Window window,Set<CubeKey> expected,Set<CubeKey> removed,ClientWindowFrames.Decoded prepared) {
        try {
            int buildTiming=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.WINDOW_BUILD);
            SparseCubeSpace candidate;
            try {
            var decoded=new HashMap<CubeKey,CubeSnapshot>();
            for(var snapshot:prepared.snapshots())decoded.put(snapshot.key(),snapshot);
            candidate = new SparseCubeSpace();
            var completeWindow = new HashMap<CubeKey,CubeSnapshot>();
            for (CubeKey key : expected) {
                CubeSnapshot snapshot = decoded.get(key);
                if (snapshot == null && !window.full() && !removed.contains(key))
                    snapshot = space.snapshot(key).orElse(null);
                if (snapshot == null) throw new IllegalArgumentException("Missing visual data");
                completeWindow.put(key,snapshot);
            }
            candidate.loadAll(completeWindow);
            if (candidate.loadedCubeCount() != expected.size())
                throw new IllegalArgumentException("Incomplete visual cache");
            }finally{GeneratedFrameCosts.end(buildTiming);}
            // Only publish after every native frame, membership and sequence check succeeds.
            space = candidate;
            keys = Set.copyOf(expected);
            revision = window.revision();
            minimumCubeY = window.minimumCubeY();
            geometryAvailable = true;
            clearLight();
            report.addProperty("completed", true);
            report.addProperty("revision", revision);
            report.addProperty("cubeCount", keys.size());
            report.addProperty("minimumCubeY", minimumCubeY);
            report.addProperty("packetFrameBytes", prepared.bytes());
            report.addProperty("centerX", window.center().x());
            report.addProperty("centerY", window.center().y());
            report.addProperty("centerZ", window.center().z());
            report.remove("error");
            ClientWindowPreparation.decoded(prepared);
            int notifyTiming=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.WINDOW_NOTIFY);
            try{if(current.isClientSide)NativeClientBridge.generatedVisual(current,candidate);}
            finally{GeneratedFrameCosts.end(notifyTiming);}
        } catch (RuntimeException error) {refuse(current,error);}
    }
    private static void refuse(Level current,RuntimeException error) {
            // Preserve known geometry, but a failed newer observation cannot keep its old light current.
            // Do not consume geometry revision: a valid retry of the next contiguous delta can recover.
            geometryAvailable = false;
            clearLight();
            report.addProperty("error", error.toString());
            if(current.isClientSide)NativeClientBridge.invalidGeneratedGeometry(error.toString());
    }

    public static void clear() {
        generation++;
        token = null;
        revision = -1;
        minimumCubeY = Integer.MIN_VALUE / 16;
        level = new WeakReference<>(null);
        space = null;
        keys = Set.of();
        geometryAvailable = false;
        clearLight();
        report = new JsonObject();
    }

    public static Optional<SparseCubeSpace> space(Level requested) {
        return requested != null && requested == level.get() ? Optional.ofNullable(space) : Optional.empty();
    }
    public static Set<CubeKey> keys(Level requested) {
        return requested != null && requested == level.get() && space != null ? keys : Set.of();
    }
    public static long revision() { return revision; }
    /** Render-only derived input mirror: never attached to Level and never grants physical residency. */
    public static CubeBlockLightAccess light(Level requested, SparseCubeSpace physical) {
        if (requested == null || requested != level.get() || token == null || space == null || !geometryAvailable)
            throw new IllegalStateException("Visual BLOCK inputs are unavailable for the current scene");
        Objects.requireNonNull(physical, "physical");
        long generation = physical.contentGeneration();
        if (lightCache != null && lightVisual.get() == space && lightVisualRevision == revision
                && lightPhysical.get() == physical && lightPhysicalGeneration == generation) return lightCache;
        // The input keyset is fixed to the received visual scene. New physical windows cannot grow it.
        var composite = new SparseCubeSpace();
        var completeWindow = new HashMap<CubeKey,CubeSnapshot>();
        for (CubeKey key : keys) {
            long firstY = (long) key.y() * 16;
            if (firstY < DeepCubeDomain.TECHNICAL_BOTTOM || firstY + 15 >= DeepCubeDomain.managedTop(requested)) continue;
            var snapshot = physical.snapshot(key);
            if (snapshot.isEmpty()) snapshot = space.snapshot(key);
            completeWindow.put(key,snapshot.orElseThrow(() -> new IllegalStateException("Missing current visual cube " + key)));
        }
        composite.loadAll(completeWindow);
        if (physical.contentGeneration() != generation) {
            clearLight();
            throw new IllegalStateException("Physical content changed during visual light capture");
        }
        var view = new CubeBlockView(composite, DeepCubeDomain.TECHNICAL_BOTTOM,
                DeepCubeDomain.managedTop(requested) - DeepCubeDomain.TECHNICAL_BOTTOM);
        var candidate = CubeBlockLightAccess.visual(composite, view);
        lightCache = candidate;
        lightVisual = new WeakReference<>(space); lightVisualRevision = revision;
        lightPhysical = new WeakReference<>(physical); lightPhysicalGeneration = generation;
        return candidate;
    }
    public static boolean observationFailed() {return report.has("error");}
    public static JsonObject status() {
        var status = report.deepCopy();
        var physical = lightPhysical.get();
        boolean current = geometryAvailable && lightCache != null && lightVisual.get() == space
                && lightVisualRevision == revision && physical != null
                && lightPhysicalGeneration == physical.contentGeneration();
        status.addProperty("lightGeometryAvailable", geometryAvailable);
        status.addProperty("lightCacheAvailable", current && lightCache.computedCubes() > 0);
        status.addProperty("lightCompiledCubes", !current ? 0 : lightCache.computedCubes());
        status.addProperty("lightRecomputations", lightCache == null ? 0 : lightCache.recomputations());
        return status;
    }

    private static void clearLight() {
        lightCache = null;
        lightPhysical = new WeakReference<>(null); lightVisual = new WeakReference<>(null);
        lightPhysicalGeneration = lightVisualRevision = -1;
    }

    /** Keep client-only classes outside the generic cache verifier on a dedicated server. */
    private static final class NativeClientBridge {
        static Level current() { return Minecraft.getInstance().level; }
        static void generatedVisual(Level current,SparseCubeSpace candidate){ClientGeneratedLight.visual(current,candidate);}
        static void invalidGeneratedGeometry(String reason){ClientGeneratedLight.invalidGeometry(reason);}
    }
}
