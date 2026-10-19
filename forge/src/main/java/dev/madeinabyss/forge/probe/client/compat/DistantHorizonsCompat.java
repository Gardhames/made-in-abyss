package dev.madeinabyss.forge.probe.client.compat;

import com.google.gson.JsonObject;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only optional bootstrap. No DH types occur in its descriptors or common entrypoints. */
@Mod.EventBusSubscriber(modid=MadeInAbyssProbe.MOD_ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class DistantHorizonsCompat {
    public interface Bridge {
        void initialize();
        void prepareConnection();
        JsonObject status();
        default JsonObject status(JsonObject parameters){return status();}
        JsonObject begin(Minecraft client,JsonObject parameters);
        void end(String reason);
        void tick(Minecraft client);
        void close();
        void shutdownBackground();
    }
    private static Bridge bridge;
    private static boolean attempted;
    private static String error;
    private static long emptyLogoutEvents,realLogoutEvents;
    private DistantHorizonsCompat() {}

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {event.enqueueWork(DistantHorizonsCompat::initialize);}
    private static void initialize() {
        if(attempted || !Boolean.getBoolean("mia.compat.distantHorizons"))return;
        attempted=true;
        if(!ModList.get().isLoaded("distanthorizons"))return;
        try {
            // Do not resolve the DH-linked implementation until Forge confirms the optional mod exists.
            var implementation=switch(System.getProperty("mia.dh.pipeline","legacy")) {
                case "legacy" -> "DhPreviewAdapter";
                case "surfaces" -> "AbyssLodAdapter";
                default -> throw new IllegalArgumentException("Unknown Made in Abyss LOD pipeline");
            };
            bridge=(Bridge)Class.forName("dev.madeinabyss.forge.probe.client.compat."+implementation)
                    .getDeclaredConstructor().newInstance();
            bridge.initialize();
            dev.madeinabyss.forge.probe.client.compat.lightgpu.ClientGpuLight.prepare(Minecraft.getInstance());
            // JVM shutdown has no render-thread GL context. Normal logout owns GPU/API cleanup.
            Runtime.getRuntime().addShutdownHook(new Thread(()->{if(bridge!=null)bridge.shutdownBackground();},"mia-dh-preview-stop"));
        } catch(ReflectiveOperationException|LinkageError|RuntimeException failure) {
            error=failure.toString();if(bridge!=null)bridge.close();bridge=null;
        }
    }
    public static JsonObject status() {
        return status(new JsonObject());
    }
    public static JsonObject status(JsonObject parameters) {
        var out=bridge==null?new JsonObject():bridge.status(parameters);
        out.addProperty("enabled",Boolean.getBoolean("mia.compat.distantHorizons"));
        out.addProperty("dhPresent",ModList.get().isLoaded("distanthorizons"));
        out.addProperty("bootstrapAttempted",attempted);
        out.addProperty("pipelineSelection",System.getProperty("mia.dh.pipeline","legacy"));
        out.addProperty("emptyLogoutEvents",emptyLogoutEvents);
        out.addProperty("realLogoutEvents",realLogoutEvents);
        if(error!=null)out.addProperty("bootstrapError",error);
        return out;
    }
    public static JsonObject control(Minecraft client,JsonObject parameters) {
        initialize();
        String operation=parameters.get("operation").getAsString();
        if("end".equals(operation)){if(bridge!=null)bridge.end("explicit end");return status();}
        if(!"begin".equals(operation))throw new IllegalArgumentException("Expected begin or end");
        if(bridge==null)throw new IllegalStateException("Optional DH development adapter unavailable: "+status());
        return bridge.begin(client,parameters);
    }
    @Mod.EventBusSubscriber(modid=MadeInAbyssProbe.MOD_ID,value=Dist.CLIENT)
    public static final class ClientEvents {
        @SubscribeEvent public static void render(TickEvent.RenderTickEvent event){if(event.phase==TickEvent.Phase.START)dev.madeinabyss.forge.probe.client.compat.lightgpu.ClientGpuLight.frame();}
        @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
            if(event.phase==TickEvent.Phase.END && bridge!=null)bridge.tick(Minecraft.getInstance());
        }
        @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            // Forge47.4.20 also emits this event while creating/connecting a world, with a null player.
            // Only a real departing player owns world-exit cleanup; the empty event prepares the next connection.
            if(event.getPlayer()==null) {
                emptyLogoutEvents++;
                if(bridge!=null)bridge.prepareConnection();
            } else {
                realLogoutEvents++;
                if(bridge!=null)bridge.end("client disconnect");
                dev.madeinabyss.forge.probe.client.compat.lightgpu.ClientGpuLight.end();
            }
        }
    }
}
