package dev.madeinabyss.forge.probe.deep.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class ClientMiningParticleMixin {
    @Inject(method="addBlockHitEffects(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;)V",at=@At("HEAD"),cancellable=true,remap=false)
    private void mia$noDeepHitDecoration(BlockPos pos,BlockHitResult hit,CallbackInfo ci) {
        var level=Minecraft.getInstance().level;
        if(Boolean.getBoolean("mia.cube.playerProbe") && level!=null && pos.getY()<level.getMinBuildHeight())ci.cancel();
    }
}
