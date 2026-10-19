package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepChestClientMirror;
import dev.madeinabyss.forge.probe.deep.DeepChestPackets;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Actual native renderer/ticker on a bounded metadata mirror; light remains diagnostic. */
@Mod.EventBusSubscriber(modid=MadeInAbyssProbe.MOD_ID,value=Dist.CLIENT)
public final class ClientDeepChest {
    private static BufferBuilder BUILDER=new BufferBuilder(262144);
    private static MultiBufferSource.BufferSource BUFFERS=MultiBufferSource.immediate(BUILDER);
    private static long frames,ticks,lidEvents,renderedFrames;
    private static long injectedFailures,discardedBuffers;
    private static boolean faultEnabled,faultHeld;
    private static int mirroredChests,drawnChests,animatedChests;
    private static double submittedMinY=Double.NaN,submittedMaxY=Double.NaN,maxOpenNess;
    private static String error="",renderer="";
    private ClientDeepChest() {}
    public static void clear(){if(BUILDER.building())discardBuffers();frames=ticks=lidEvents=renderedFrames=injectedFailures=discardedBuffers=0;faultEnabled=faultHeld=false;mirroredChests=drawnChests=animatedChests=0;submittedMinY=submittedMaxY=Double.NaN;maxOpenNess=0;error=renderer="";}
    public static void lidEvent(){lidEvents++;}
    public static JsonObject diagnosticFault(boolean enabled){
        if(!Boolean.getBoolean("mia.cube.playerProbe")||!Boolean.getBoolean("mia.client.automation"))throw new IllegalStateException("isolated native renderer probe required");
        faultEnabled=enabled;faultHeld=false;return status();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END)return;
        var client=Minecraft.getInstance();animatedChests=0;
        if(client.level==null||client.isPaused())return;
        var cache=DeepChestClientMirror.lookup(client.level);if(cache==null)return;
        var chests=cache.chests();
        if(chests.size()>DeepChestPackets.MAX_MIRRORS){error="native chest animation budget exceeded";return;}
        ticks++;
        for(var chest:chests)if(!chest.isRemoved()&&chest.getLevel()==client.level){
            ChestBlockEntity.lidAnimateTick(client.level,chest.getBlockPos(),chest.getBlockState(),chest);animatedChests++;
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;
        frames++;drawnChests=mirroredChests=0;submittedMinY=submittedMaxY=Double.NaN;maxOpenNess=0;error="";
        if(faultEnabled&&faultHeld){error="diagnostic chest render failure held";return;}
        var client=Minecraft.getInstance();if(!Boolean.getBoolean("mia.cube.playerProbe")||client.level==null)return;
        var cache=DeepChestClientMirror.lookup(client.level);if(cache==null)return;
        var chests=cache.chests();mirroredChests=chests.size();
        if(chests.size()>DeepChestPackets.MAX_MIRRORS){error="native chest render budget exceeded";return;}
        var camera=event.getCamera().getPosition();
        if(!Double.isFinite(camera.x)||!Double.isFinite(camera.y)||!Double.isFinite(camera.z)||camera.y>=client.level.getMinBuildHeight())return;
        // Native renderers manage their own nested poses; a failed callback must not damage the shared level stack.
        var pose=new PoseStack();pose.last().pose().set(event.getPoseStack().last().pose());pose.last().normal().set(event.getPoseStack().last().normal());
        try{
            for(var chest:chests){
                var pos=chest.getBlockPos();
                if(chest.isRemoved()||chest.getLevel()!=client.level||pos.getY()<DeepCubeDomain.TECHNICAL_BOTTOM||pos.getY()>=client.level.getMinBuildHeight())continue;
                double dx=pos.getX()+.5-camera.x,dy=pos.getY()+.5-camera.y,dz=pos.getZ()+.5-camera.z;
                if(dx*dx+dy*dy+dz*dz>24*24)continue;
                var nativeRenderer=client.getBlockEntityRenderDispatcher().getRenderer(chest);
                if(!(nativeRenderer instanceof ChestRenderer<?>))throw new IllegalStateException("Actual native ChestRenderer unavailable");
                renderer=nativeRenderer.getClass().getName();
                pose.pushPose();
                try{
                    pose.translate(pos.getX()-camera.x,pos.getY()-camera.y,pos.getZ()-camera.z);
                    // Dispatcher.render would query unsupported native chunk light. SINGLE combine uses supplied light.
                    nativeRenderer.render(chest,event.getPartialTick(),pose,BUFFERS,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);
                }finally{pose.popPose();}
                if(drawnChests++==0)submittedMinY=submittedMaxY=pos.getY();else{submittedMinY=Math.min(submittedMinY,pos.getY());submittedMaxY=Math.max(submittedMaxY,pos.getY());}
                maxOpenNess=Math.max(maxOpenNess,chest.getOpenNess(event.getPartialTick()));
                if(faultEnabled){faultHeld=true;injectedFailures++;throw new IllegalStateException("diagnostic failure after actual native chest vertices");}
            }
            BUFFERS.endBatch();if(drawnChests>0)renderedFrames++;
        }catch(RuntimeException unavailable){discardBuffers();error=unavailable.toString();drawnChests=0;submittedMinY=submittedMaxY=Double.NaN;}
    }

    private static void discardBuffers(){
        try{
            // discard() alone resets byte pointers, but leaves native building=true. End/release without uploading.
            if(BUILDER.building()){var abandoned=BUILDER.endOrDiscardIfEmpty();if(abandoned!=null)abandoned.release();}
            BUILDER.discard();
        }catch(RuntimeException incompleteNativeBatch){BUILDER=new BufferBuilder(262144);}
        BUFFERS=MultiBufferSource.immediate(BUILDER);discardedBuffers++;
    }

    public static JsonObject status(){
        var out=new JsonObject();out.addProperty("scope","native-dry-single-chest;FULL_BRIGHT_DIAGNOSTIC;physics-mirror-only");
        out.addProperty("frames",frames);out.addProperty("renderedFrames",renderedFrames);out.addProperty("ticks",ticks);out.addProperty("lidEvents",lidEvents);
        out.addProperty("mirroredChests",mirroredChests);out.addProperty("drawnChests",drawnChests);out.addProperty("animatedChests",animatedChests);
        if(Double.isFinite(submittedMinY)){out.addProperty("submittedMinY",submittedMinY);out.addProperty("submittedMaxY",submittedMaxY);}
        out.addProperty("injectedFailures",injectedFailures);out.addProperty("discardedBuffers",discardedBuffers);out.addProperty("bufferPending",BUILDER.building());
        out.addProperty("maxOpenNess",maxOpenNess);out.addProperty("maxLid",maxOpenNess);out.addProperty("renderer",renderer);out.addProperty("error",error);return out;
    }
}
