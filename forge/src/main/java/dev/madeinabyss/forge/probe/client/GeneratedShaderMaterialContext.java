package dev.madeinabyss.forge.probe.client;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional Oculus 1.8 block context, matching its native MixinChunkRebuildTask.
 * No shader classes are linked when Oculus is absent. This supplies material IDs,
 * never changes geometry, textures or SKY/BLOCK values. Render-thread use only. */
public final class GeneratedShaderMaterialContext {
    private static boolean resolved, available, active;
    private static long revision;
    public static long revision(){return revision;}
    private static Class<?> sensitiveBuffer;
    private static Object settings, stateIds;
    private static Method getIds, extendedFormat, lookup, beginBlock, endBlock;
    private static final IdentityHashMap<Object, Short> ids = new IdentityHashMap<>();
    private static long begins, ends, mapped, unmapped, failures;
    private static long frameBegins, frameEnds, frameMapped, frameUnmapped;
    private static int lastId = -1, lastX, lastY, lastZ;
    private static String error = "";

    private GeneratedShaderMaterialContext() {}

    /** Refresh the pack-owned map every frame: shader reload can replace its identity. */
    public static void prepareFrame() {
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.SHADER_MATERIAL_PREP);
        try{prepareFrameMeasured();}finally{GeneratedFrameCosts.end(timing);}
    }
    private static void prepareFrameMeasured() {
        frameBegins = frameEnds = frameMapped = frameUnmapped = 0;
        boolean previous=active;active = false;
        if (!resolved) resolve();
        if (!available) return;
        try {
            Object current = getIds.invoke(settings);
            if (current != stateIds) { stateIds = current; ids.clear();revision++; }
            active = stateIds != null && (Boolean) extendedFormat.invoke(settings);
            if(active!=previous)revision++;
        } catch (ReflectiveOperationException | RuntimeException failure) { failed(failure); }
    }

    private static void resolve() {
        resolved = true;
        try {
            ClassLoader loader = GeneratedShaderMaterialContext.class.getClassLoader();
            sensitiveBuffer = Class.forName("net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder", false, loader);
            Class<?> type = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings", false, loader);
            settings = type.getField("INSTANCE").get(null);
            getIds = type.getMethod("getBlockStateIds");
            extendedFormat = type.getMethod("shouldUseExtendedVertexFormat");
            lookup = Class.forName("it.unimi.dsi.fastutil.objects.Object2IntMap", false, loader)
                    .getMethod("getOrDefault", Object.class, int.class);
            beginBlock = sensitiveBuffer.getMethod("beginBlock", short.class, short.class, int.class, int.class, int.class);
            endBlock = sensitiveBuffer.getMethod("endBlock");
            available = true;
        } catch (ClassNotFoundException absent) {
            // Ordinary no-Oculus rendering remains valid and does not count as a fault.
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { failed(failure); }
    }

    /** Caller must invoke end in finally exactly when this returns true. */
    public static boolean begin(Object consumer, Object state, int x, int y, int z) {
        return begin(consumer, state, (short) -1, x, y, z);
    }

    /** Native fluid context uses type1 and fluidState.createLegacyBlock(). */
    public static boolean beginFluid(Object consumer, Object state, int x, int y, int z) {
        return begin(consumer, state, (short) 1, x, y, z);
    }

    private static boolean begin(Object consumer, Object state, short renderType, int x, int y, int z) {
        if (!active || !sensitiveBuffer.isInstance(consumer)) return false;
        short id;
        try {
            Short cached = ids.get(state);
            if (cached == null) {
                // Exactly the native Oculus fallback/cast; never assign our own material IDs.
                id = (short) ((Number) lookup.invoke(stateIds, state, -1)).intValue();
                if (ids.size() < 4096) ids.put(state, id);
            } else id = cached;
        } catch (ReflectiveOperationException | RuntimeException failure) { failed(failure); return false; }
        try {
            // Native model render type is -1 (liquids use 1). Signed XYZ uses local &15.
            beginBlock.invoke(consumer, id, renderType, x & 15, y & 15, z & 15);
            begins++; frameBegins++;
            if (id == -1) { unmapped++; frameUnmapped++; } else { mapped++; frameMapped++; }
            lastId = id; lastX = x & 15; lastY = y & 15; lastZ = z & 15;
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // A future implementation may mutate before failing. Clear that scope too.
            try { endBlock.invoke(consumer); } catch (ReflectiveOperationException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            failed(failure);
            return false;
        }
    }

    public static void end(Object consumer) {
        try { endBlock.invoke(consumer); ends++; frameEnds++; }
        catch (ReflectiveOperationException | RuntimeException failure) { failed(failure); }
    }

    private static void failed(Throwable failure) {
        failures++; active = available = false;
        error = failure.toString();
        if (failure.getCause() != null) error += ": " + failure.getCause();
    }

    public static Map<String, Object> status() {
        var out = new LinkedHashMap<String, Object>();
        out.put("apiAvailable", available); out.put("active", active); out.put("mapAvailable", stateIds != null);
        out.put("begins", begins); out.put("ends", ends); out.put("mapped", mapped); out.put("unmapped", unmapped);
        out.put("frameBegins", frameBegins); out.put("frameEnds", frameEnds);
        out.put("frameMapped", frameMapped); out.put("frameUnmapped", frameUnmapped);
        out.put("lastMaterialId", lastId); out.put("lastLocalX", lastX); out.put("lastLocalY", lastY); out.put("lastLocalZ", lastZ);
        out.put("failures", failures); out.put("error", error);
        return out;
    }
}
