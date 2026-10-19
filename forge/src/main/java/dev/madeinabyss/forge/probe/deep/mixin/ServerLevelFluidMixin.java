package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepFluidEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native server owns this override; deep water notifications remain inside an explicit transaction. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelFluidMixin {
    @Inject(method="updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V",at=@At("HEAD"),cancellable=true)
    private void mia$waterNeighbors(BlockPos pos,Block source,CallbackInfo callback) {
        var level=(ServerLevel)(Object)this;
        if(DeepCubeDomain.isDeep(level,pos)) {
            DeepFluidEngine.lookup(level).orElseThrow(() -> new UnsupportedOperationException("deep fluid scheduler is not attached"))
                    .neighbors(level,pos,source);
            callback.cancel();
        }
    }
}
