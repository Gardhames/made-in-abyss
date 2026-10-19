package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.OptionalLightWork;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class EntityLightAvailabilityMixin {
    /** Adapt only the final draw argument; dispatcher/probe/Level queries retain physical SKY. */
    @ModifyArg(method="renderEntity",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),index=8)
    private int mia$presentEntitySky(int packed) {
        if (!dev.madeinabyss.forge.probe.client.ClientDeepRuntime.generatedAccepted(Minecraft.getInstance().level)) return packed;
        return dev.madeinabyss.forge.probe.client.GeneratedShaderLightPresentation.presentPacked(packed);
    }
    @Inject(method="renderEntity",at=@At("HEAD"),cancellable=true)
    private void mia$skipUnknownEntityLight(Entity entity, double cameraX, double cameraY, double cameraZ,
            float partialTick, PoseStack poses, MultiBufferSource buffers, CallbackInfo callback) {
        if(DeepCubeDomain.generated(entity.level()) && !(entity instanceof net.minecraft.world.entity.player.Player)
                && !DeepManagedItemIdentity.managed(entity)) {callback.cancel();return;}
        if(!DeepCubeDomain.blockLightEnabled(entity.level())) return;
        if(!OptionalLightWork.run(entity.level(),OptionalLightWork.Kind.ENTITY,
                ()->Minecraft.getInstance().getEntityRenderDispatcher().getPackedLightCoords(entity,partialTick))) {
            DeepManagedItemIdentity.recordLightSkip(entity);callback.cancel();
        }
    }
}
