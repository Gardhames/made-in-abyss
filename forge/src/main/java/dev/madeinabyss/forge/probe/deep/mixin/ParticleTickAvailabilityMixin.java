package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.OptionalLightWork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cosmetic particles cannot retain authority over a released or unknown physics cube. */
@Mixin(ParticleEngine.class)
public abstract class ParticleTickAvailabilityMixin {
    @Inject(method="tickParticle(Lnet/minecraft/client/particle/Particle;)V",at=@At("HEAD"),cancellable=true)
    private void mia$retireUnavailableParticle(Particle particle,CallbackInfo callback) {
        var level=Minecraft.getInstance().level;
        if(level!=null && DeepCubeDomain.lookup(level).isPresent() && !DeepCubeDomain.allowsMove(level,particle.getBoundingBox())) {
            particle.remove();OptionalLightWork.recordParticleBoundary();callback.cancel();
        }
    }
}
