package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepManagedItemEngine;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ManagedItemTakeMixin {
    @Inject(method="broadcast",at=@At("HEAD"),cancellable=true)
    private void mia$stageTakeUntilDurableRemoval(Entity entity,Packet<?> packet,CallbackInfo ci) {
        if(DeepManagedItemEngine.stageTake(entity,packet))ci.cancel();
    }
}
