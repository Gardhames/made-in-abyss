package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.GeneratedLightAccess;
import dev.madeinabyss.forge.probe.deep.OptionalLightWork;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTickList;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemIdentity;
import java.util.function.Consumer;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Redirect(method="tickEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void mia$retainHostEntities(EntityTickList entities,Consumer<Entity> consumer) {
        if(!DeepCubeDomain.generated((ClientLevel)(Object)this)) {entities.forEach(consumer);return;}
        entities.forEach(entity->{if(entity instanceof Player || DeepManagedItemIdentity.managed(entity)) consumer.accept(entity);});
    }
    @Inject(method="tickPassenger",at=@At("HEAD"),cancellable=true)
    private void mia$retainHostPassengers(Entity vehicle,Entity passenger,CallbackInfo callback) {
        if(DeepCubeDomain.generated((ClientLevel)(Object)this) && !(passenger instanceof Player)
                && !DeepManagedItemIdentity.managed(passenger)) callback.cancel();
    }
    // Vanilla has already written its sampled XYZ into pos before this first block read.
    // Skip unavailable optional effects; never return fabricated AIR for an unknown cube.
    @Inject(method = "doAnimateTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            ordinal = 0), cancellable = true)
    private void mia$skipUnknownAnimation(int x, int y, int z, int range, RandomSource random,
                                          Block marker, BlockPos.MutableBlockPos pos, CallbackInfo result) {
        var level = (ClientLevel) (Object) this;
        if (!DeepCubeDomain.allowsMove(level, new AABB(pos))
                || level.isRaining() && !GeneratedLightAccess.rainReady(level,pos)) result.cancel();
    }

    // Block and fluid callbacks can request derived light/weather beyond the sampled point.
    // Only the established unavailable-light exception aborts these optional callbacks.
    @Redirect(method="doAnimateTick",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/block/Block;animateTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void mia$knownBlockAnimation(Block block,BlockState state,Level level,BlockPos pos,RandomSource random){
        if(!DeepCubeDomain.generated(level)){block.animateTick(state,level,pos,random);return;}
        OptionalLightWork.run(level,OptionalLightWork.Kind.ANIMATION,()->block.animateTick(state,level,pos,random));
    }
    @Redirect(method="doAnimateTick",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/material/FluidState;animateTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void mia$knownFluidAnimation(FluidState state,Level level,BlockPos pos,RandomSource random){
        if(!DeepCubeDomain.generated(level)){state.animateTick(level,pos,random);return;}
        OptionalLightWork.run(level,OptionalLightWork.Kind.ANIMATION,()->state.animateTick(level,pos,random));
    }
}
