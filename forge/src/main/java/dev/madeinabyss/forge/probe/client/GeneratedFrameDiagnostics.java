package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL30;

/** Read-only frame evidence for the development client; never repairs or changes GL state. */
@Mod.EventBusSubscriber(modid=MadeInAbyssProbe.MOD_ID,value=Dist.CLIENT)
public final class GeneratedFrameDiagnostics {
    private static long worldCalls, cancelledWorld, guiCalls, wrongGuiTarget, staleWorldAtGui;
    private static long skippedHostBlockEntityPasses;
    public static void hostBlockEntityPassSkipped(){skippedHostBlockEntityPasses++;}
    private static int drawTarget, mainTarget;
    private static String pipelineWorld="unavailable";
    public static void world(boolean readable){worldCalls++;if(!readable)cancelledWorld++;}
    @SubscribeEvent public static void gui(RenderGuiEvent.Post event){
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.FRAME_DIAGNOSTICS);
        try{guiMeasured(event);}finally{GeneratedFrameCosts.end(timing);}
    }
    private static void guiMeasured(RenderGuiEvent.Post event){
        var mc=Minecraft.getInstance();if(!ClientDeepRuntime.generatedSession(mc.level))return;
        guiCalls++;drawTarget=GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);mainTarget=mc.getMainRenderTarget().frameBufferId;
        if(drawTarget!=mainTarget)wrongGuiTarget++;
        try{
            var iris=Class.forName("net.irisshaders.iris.Iris");var manager=iris.getMethod("getPipelineManager").invoke(null);
            var optional=(java.util.Optional<?>)manager.getClass().getMethod("getPipeline").invoke(manager);
            if(optional.isPresent()){
                var pipeline=optional.get();var field=pipeline.getClass().getDeclaredField("isRenderingWorld");field.setAccessible(true);
                boolean active=field.getBoolean(pipeline);pipelineWorld=Boolean.toString(active);if(active)staleWorldAtGui++;
            }
        }catch(ReflectiveOperationException absent){pipelineWorld="unavailable";}
    }
    public static JsonObject status(){
        var mc=Minecraft.getInstance();var r=new JsonObject();r.addProperty("worldCalls",worldCalls);r.addProperty("cancelledWorld",cancelledWorld);
        r.addProperty("guiCalls",guiCalls);r.addProperty("wrongGuiTarget",wrongGuiTarget);r.addProperty("staleWorldAtGui",staleWorldAtGui);
        r.addProperty("drawTarget",drawTarget);r.addProperty("mainTarget",mainTarget);r.addProperty("pipelineWorldAtGui",pipelineWorld);
        r.addProperty("skippedHostBlockEntityPasses",skippedHostBlockEntityPasses);
        r.addProperty("hideGui",mc.options.hideGui);r.addProperty("generatedSession",ClientDeepRuntime.generatedSession(mc.level));
        r.addProperty("generatedAccepted",ClientDeepRuntime.generatedAccepted(mc.level));return r;
    }
}
