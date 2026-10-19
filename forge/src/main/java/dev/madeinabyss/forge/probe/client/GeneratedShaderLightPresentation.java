package dev.madeinabyss.forge.probe.client;

import dev.madeinabyss.kernel.light.IterationSkyResponse;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Render-thread optional, exact-pack presentation bridge. Physical light queries stay unchanged. */
public final class GeneratedShaderLightPresentation {
    private static boolean resolved, available, active;
    private static boolean enabled = true;
    private static Method getConfig, shadersEnabled, currentPack, fallback, getManager, getPipeline;
    private static long skySubmissions, packedSubmissions, changedSubmissions;
    private static long revision;
    private static String pack = "", pipeline = "", error = "";
    private GeneratedShaderLightPresentation() {}

    public static void enabled(boolean value) { enabled = value; prepareFrame(); }

    public static void prepareFrame() {
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.SHADER_LIGHT_PREP);
        try{prepareFrameMeasured();}finally{GeneratedFrameCosts.end(timing);}
    }
    private static void prepareFrameMeasured() {
        boolean previous = active;
        active = false;
        if (!resolved) resolve();
        if (!available) { if (previous) revision++; return; }
        try {
            Object config = getConfig.invoke(null);
            pack = String.valueOf(currentPack.invoke(null));
            Object manager = getManager.invoke(null);
            Optional<?> current = (Optional<?>) getPipeline.invoke(manager);
            pipeline = current.map(p -> p.getClass().getName()).orElse("none");
            active = enabled && (Boolean) shadersEnabled.invoke(config) && !(Boolean) fallback.invoke(null)
                    && pack.equals("iterationT 3.2.0.zip")
                    && pipeline.equals("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
        } catch (ReflectiveOperationException | RuntimeException failure) { error = failure.toString(); }
        if (active != previous) revision++;
    }

    /** DH may include this stamp in its group-light refresh key. */
    public static long revision() { return revision; }

    private static void resolve() {
        resolved = true;
        try {
            ClassLoader loader = GeneratedShaderLightPresentation.class.getClassLoader();
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris", false, loader);
            getConfig = iris.getMethod("getIrisConfig");
            shadersEnabled = getConfig.getReturnType().getMethod("areShadersEnabled");
            currentPack = iris.getMethod("getCurrentPackName"); fallback = iris.getMethod("isFallback");
            getManager = iris.getMethod("getPipelineManager");
            getPipeline = getManager.getReturnType().getMethod("getPipeline");
            available = true;
        } catch (ClassNotFoundException absent) {
            // Installed Oculus is optional; vanilla submits its original light.
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { error = failure.toString(); }
    }

    public static int presentSky(int physicalSky) {
        skySubmissions++;
        int result = active ? IterationSkyResponse.presentSky(physicalSky) : physicalSky;
        if (result != physicalSky) changedSubmissions++;
        return result;
    }

    public static int presentPacked(int physicalPacked) {
        packedSubmissions++;
        int result = active ? IterationSkyResponse.presentPacked(physicalPacked) : physicalPacked;
        if (result != physicalPacked) changedSubmissions++;
        return result;
    }

    public static Map<String, Object> status() {
        var out = new LinkedHashMap<String, Object>();
        out.put("enabled", enabled); out.put("active", active); out.put("apiAvailable", available);
        out.put("revision", revision);
        out.put("pack", pack); out.put("pipeline", pipeline); out.put("error", error);
        out.put("skySubmissions", skySubmissions); out.put("packedSubmissions", packedSubmissions);
        out.put("changedSubmissions", changedSubmissions);
        return out;
    }
}
