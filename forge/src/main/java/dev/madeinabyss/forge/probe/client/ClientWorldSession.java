package dev.madeinabyss.forge.probe.client;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.WorldSessionNetwork;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

@Mod.EventBusSubscriber(modid=MadeInAbyssProbe.MOD_ID,value=Dist.CLIENT)
public final class ClientWorldSession {
    private static WorldSessionNetwork.Offer pending;
    private static com.google.gson.JsonObject dhParameters;
    private static boolean dhAttempted;
    private static String dhError="";
    private ClientWorldSession() { }
    @SubscribeEvent public static void tagsUpdated(net.minecraftforge.event.TagsUpdatedEvent event){
        // Both integrated reload and remote tag packets can change shared physical block tags.
        // execute is inline on the client owner and safely queues a server-origin event.
        Minecraft.getInstance().execute(ClientGeneratedLight::verifyPhysicalResources);
    }
    public static void offer(WorldSessionNetwork.Offer offer){if(pending!=null)throw new IllegalStateException("Repeated native world admission offer");pending=offer;}
    public static com.google.gson.JsonObject status(){var out=new com.google.gson.JsonObject();out.addProperty("offerPending",pending!=null);out.addProperty("autoDhRequested",dhParameters!=null);out.addProperty("autoDhAttempted",dhAttempted);out.addProperty("autoDhError",dhError);return out;}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){if(event.getPlayer()==null)return;pending=null;dhParameters=null;dhAttempted=false;dhError="";ClientDeepRuntime.disconnect();}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END)return;
        var client=Minecraft.getInstance();
        if(pending==null){beginDh(client);return;}
        if(client.level==null||client.player==null||client.getConnection()==null||!client.level.dimension().location().toString().equals(pending.dimension()))return;
        var offer=pending;pending=null;
        try {
            ClientDeepRuntime.beginWorld(client,offer);
            var manifest=com.google.gson.JsonParser.parseString(offer.manifest()).getAsJsonObject();
            dhParameters=manifest.has("nativeWorldView")?manifest.getAsJsonObject("nativeWorldView").deepCopy():null;
            dhAttempted=false;dhError="";
            WorldSessionNetwork.ready(offer);
        }
        catch(RuntimeException refused){client.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal("Native Abyss world admission failed: "+refused.getMessage()));}
    }
    private static void beginDh(Minecraft client){
        if(dhParameters==null||dhAttempted||!ClientDeepRuntime.generatedAccepted(client.level)||ClientGeneratedLight.source()==null||ClientGeneratedLight.geometryObservationsPending())return;
        var status=dev.madeinabyss.forge.probe.client.compat.DistantHorizonsCompat.status();
        if(!status.get("enabled").getAsBoolean()||!status.get("dhPresent").getAsBoolean())return;
        if(!status.has("initialized")||!status.get("initialized").getAsBoolean())return;
        dhAttempted=true;
        try {var parameters=dhParameters.deepCopy();parameters.addProperty("operation","begin");dev.madeinabyss.forge.probe.client.compat.DistantHorizonsCompat.control(client,parameters);}
        catch(RuntimeException refused){dhError=refused.toString();com.mojang.logging.LogUtils.getLogger().error("Native world optional DH admission refused; cube authority retained",refused);}
    }
}
