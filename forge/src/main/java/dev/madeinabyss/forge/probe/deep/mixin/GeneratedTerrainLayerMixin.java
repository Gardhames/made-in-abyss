package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;

/** The accepted generated scene replaces terrain visually, while ordinary saved chunks remain intact. */
@Mixin(LevelRenderer.class)
public abstract class GeneratedTerrainLayerMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method="compileChunks",at=@At("HEAD"),cancellable=true)
    private void mia$retainHostModelCompilation(net.minecraft.client.Camera camera,CallbackInfo callback) {
        // Native chunks are hidden in this preview. Keep their dirty/model-data queues until returning home.
        if(ClientDeepRuntime.generatedSession(minecraft.level)) callback.cancel();
    }

    @Redirect(method="renderLevel",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"),require=2)
    private void mia$retainHostBlockEntityRendering(BlockEntityRenderDispatcher dispatcher,BlockEntity blockEntity,
                                                     float partialTick,PoseStack poses,MultiBufferSource buffers) {
        // Cube chest models render separately at AFTER_BLOCK_ENTITIES. Keep that stage intact.
        if(!ClientDeepRuntime.generatedSession(minecraft.level)) dispatcher.render(blockEntity,partialTick,poses,buffers);
    }

    @Redirect(method="renderChunkLayer",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V"))
    private void mia$generatedTerrainOnly(VertexBuffer buffer) {
        // Keep the complete layer method: DH's HEAD injection and Forge's layer stage both need it.
        if(!ClientDeepRuntime.generatedSession(minecraft.level))buffer.draw();
    }
}
