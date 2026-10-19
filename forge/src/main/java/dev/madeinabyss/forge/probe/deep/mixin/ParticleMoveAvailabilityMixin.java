package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.OptionalLightWork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Check the actual displacement before native particle collision reads. */
@Mixin(Particle.class)
public abstract class ParticleMoveAvailabilityMixin {
    @Inject(method="move(DDD)V",at=@At("HEAD"),cancellable=true)
    private void mia$boundParticleMove(double x,double y,double z,CallbackInfo callback) {
        var level=Minecraft.getInstance().level;var particle=(Particle)(Object)this;
        if(level!=null && DeepCubeDomain.lookup(level).isPresent()
                && !DeepCubeDomain.allowsMove(level,particle.getBoundingBox().expandTowards(x,y,z))) {
            particle.remove();OptionalLightWork.recordParticleBoundary();callback.cancel();
        }
    }
}
