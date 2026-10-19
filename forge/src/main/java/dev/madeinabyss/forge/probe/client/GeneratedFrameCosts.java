package dev.madeinabyss.forge.probe.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.concurrent.FrameCostTrace;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Opt-in END-to-END owner wall times; never synchronizes GL or changes render state. */
@Mod.EventBusSubscriber(modid=MadeInAbyssProbe.MOD_ID,value=Dist.CLIENT)
public final class GeneratedFrameCosts {
    public enum Stage {
        NATIVE_EVENT,NATIVE_OWNER,NATIVE_MODELS,NATIVE_UPLOAD,NATIVE_VBO,NATIVE_COMMIT,NATIVE_DRAW,
        FLUID_DRAW,SURFACE_DRAW,SURFACE_SHADOW,SURFACE_UPLOAD,SURFACE_ENCODE,SURFACE_VBO,SURFACE_SORT,
        DH_TICK,DH_SETUP,DH_BEFORE_RENDER,LIGHT_UPDATE,OBSERVATION_APPLY,PHYSICAL_APPLY,VISUAL_APPLY,
        SHADER_LIGHT_PREP,SHADER_MATERIAL_PREP,CONTROL,INVENTORY_APPLY,FRAME_DIAGNOSTICS,
        WINDOW_VALIDATE,WINDOW_BUILD,WINDOW_NOTIFY,WINDOW_DECODE
    }
    private static final boolean ENABLED=Boolean.getBoolean("mia.render.frameTimers");
    private static final int CAPACITY=8192;
    private static final FrameCostTrace TRACE=ENABLED?new FrameCostTrace(System::nanoTime,Stage.values().length,CAPACITY):null;
    public static final long OWNER_BUDGET_NANOS=ownerBudget();
    private GeneratedFrameCosts(){}
    private static long ownerBudget(){
        double millis=Double.parseDouble(System.getProperty("mia.render.ownerBudgetMillis","3"));
        if(!Double.isFinite(millis)||millis<0.1||millis>100)throw new IllegalArgumentException("mia.render.ownerBudgetMillis must be 0.1..100");
        return Math.round(millis*1_000_000);
    }
    public static int begin(Stage stage){return ENABLED?TRACE.begin(stage.ordinal()):-1;}
    public static boolean enabled(){return ENABLED;}
    public static void end(int token){if(token>=0)TRACE.end(token);}
    @SubscribeEvent public static void frame(TickEvent.RenderTickEvent event){
        if(!ENABLED||event.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null){TRACE.reset();return;}
        TRACE.finishFrame(System.currentTimeMillis(),ClientGeneratedLight.targetGeneration(),DeepCubeRenderer.nativeFrameState());
    }
    public static JsonObject status(JsonObject parameters){
        var out=new JsonObject();out.addProperty("enabled",ENABLED);out.addProperty("ownerBudgetMillis",OWNER_BUDGET_NANOS/1e6);
        out.add("windowPreparation",ClientWindowPreparation.status());
        if(!ENABLED)return out;
        if(parameters.has("reset")&&parameters.get("reset").getAsBoolean())TRACE.reset();
        long after=parameters.has("afterFrame")?parameters.get("afterFrame").getAsLong():0;
        out.addProperty("capacity",CAPACITY);out.addProperty("oldestFrame",TRACE.oldestSequence());out.addProperty("latestFrame",TRACE.sequence());
        out.addProperty("droppedFrames",Math.max(0,TRACE.oldestSequence()-Math.max(0,after)-1));
        out.addProperty("interval","RenderTick END to END; includes packet/tasks/ticks before rendering; wall time, not GPU time");
        out.addProperty("stateBits","0=NONE,1=NEAR,2=FULL; mask4=native pending; mask8=native UPLOAD");
        var gson=new Gson();out.add("stages",gson.toJsonTree(Stage.values()));out.add("frames",gson.toJsonTree(TRACE.snapshot(after)));return out;
    }
}
