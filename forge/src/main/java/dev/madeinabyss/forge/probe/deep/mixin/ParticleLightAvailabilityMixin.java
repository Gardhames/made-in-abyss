package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.OptionalLightWork;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancel the optional quad before vertices are emitted when its actual native light sample is UNKNOWN. */
@Mixin(SingleQuadParticle.class)
public abstract class ParticleLightAvailabilityMixin extends Particle {
    protected ParticleLightAvailabilityMixin(ClientLevel level,double x,double y,double z) {super(level,x,y,z);}
    @Inject(method="render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V",at=@At("HEAD"),cancellable=true)
    private void mia$skipUnknownParticle(VertexConsumer consumer,Camera camera,float partialTick,CallbackInfo callback) {
        if(DeepCubeDomain.blockLightEnabled(level)
                && !OptionalLightWork.run(level,OptionalLightWork.Kind.PARTICLE,()->getLightColor(partialTick))) callback.cancel();
    }
}
