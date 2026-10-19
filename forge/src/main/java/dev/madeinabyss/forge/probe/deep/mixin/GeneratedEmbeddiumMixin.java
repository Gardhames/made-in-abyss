package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.server.level.BlockDestructionProgress;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.SortedSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppress only host draws: outer LevelRenderer still fires DH hooks and Forge render stages. */
@Pseudo
@Mixin(targets="me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer",remap=false)
public abstract class GeneratedEmbeddiumMixin {
    @Inject(method="drawChunkLayer",at=@At("HEAD"),cancellable=true,remap=false)
    private void mia$generatedTerrain(RenderType layer,PoseStack poses,double x,double y,double z,CallbackInfo callback){
        if(ClientDeepRuntime.generatedSession(Minecraft.getInstance().level))callback.cancel();
    }
    @Inject(method="renderBlockEntities",at=@At("HEAD"),cancellable=true,remap=false)
    private void mia$generatedBlockEntities(PoseStack poses,RenderBuffers buffers,Long2ObjectMap<SortedSet<BlockDestructionProgress>> damage,
                                          Camera camera,float partial,CallbackInfo callback){
        if(ClientDeepRuntime.generatedSession(Minecraft.getInstance().level))callback.cancel();
    }
    // Oculus shadows invoke these private overloads directly, bypassing the Camera overload above.
    @Inject(method={
            "renderBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderBuffers;Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;FLnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDDLnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;)V",
            "renderGlobalBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderBuffers;Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;FLnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDDLnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;)V"
    },at=@At("HEAD"),cancellable=true,remap=false,require=2)
    private void mia$retainHostShadowBlockEntities(CallbackInfo callback){
        if(ClientDeepRuntime.generatedSession(Minecraft.getInstance().level)){
            dev.madeinabyss.forge.probe.client.GeneratedFrameDiagnostics.hostBlockEntityPassSkipped();
            callback.cancel();
        }
    }
}
