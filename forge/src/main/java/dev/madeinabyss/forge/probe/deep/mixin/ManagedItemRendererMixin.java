package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemIdentity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the actual native item renderer; no replacement geometry or light value. */
@Mixin(ItemEntityRenderer.class)
public abstract class ManagedItemRendererMixin {
    @Inject(method="render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",at=@At("HEAD"))
    private void mia$nativeItemCall(ItemEntity item,float yaw,float partial,PoseStack poses,MultiBufferSource buffers,int light,CallbackInfo callback){DeepManagedItemIdentity.recordRenderCall(item);}
    @Inject(method="render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",at=@At("RETURN"))
    private void mia$nativeItemReturned(ItemEntity item,float yaw,float partial,PoseStack poses,MultiBufferSource buffers,int light,CallbackInfo callback){DeepManagedItemIdentity.recordRendered(item);}
}
