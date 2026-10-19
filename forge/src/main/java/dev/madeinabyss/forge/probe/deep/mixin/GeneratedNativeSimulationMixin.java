package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepCubeRuntime;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Pause the isolated host world's native simulation while a generated preview supplies its terrain. */
@Mixin(ServerLevel.class)
public abstract class GeneratedNativeSimulationMixin {
    @Inject(method="tickChunk",at=@At("HEAD"),cancellable=true)
    private void mia$retainHostChunks(LevelChunk chunk,int randomTickSpeed,CallbackInfo callback) {
        if(DeepCubeDomain.generated((ServerLevel)(Object)this)) callback.cancel();
    }
    @Inject(method="tickCustomSpawners",at=@At("HEAD"),cancellable=true)
    private void mia$retainHostCustomSpawners(CallbackInfo callback) {
        if(DeepCubeDomain.generated((ServerLevel)(Object)this)) callback.cancel();
    }
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V"),require=2)
    private <T> void mia$retainHostScheduledTicks(LevelTicks<T> ticks,long time,int budget,BiConsumer<BlockPos,T> consumer) {
        // Skip the scheduler, preserving pending events; cancelling individual callbacks would delete them.
        if(!DeepCubeDomain.generated((ServerLevel)(Object)this)) ticks.tick(time,budget,consumer);
    }
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void mia$retainHostEntities(EntityTickList entities,Consumer<Entity> consumer) {
        if(!DeepCubeDomain.generated((ServerLevel)(Object)this)) {entities.forEach(consumer);return;}
        // Filter before checkDespawn and AI; unowned saved host mobs must remain intact.
        entities.forEach(entity->{if(mia$owned(entity)) consumer.accept(entity);});
    }
    @Inject(method="runBlockEvents",at=@At("HEAD"),cancellable=true)
    private void mia$retainHostBlockEvents(CallbackInfo callback) {
        if(DeepCubeDomain.generated((ServerLevel)(Object)this)) callback.cancel();
    }
    @Inject(method="tickPassenger",at=@At("HEAD"),cancellable=true)
    private void mia$retainHostPassenger(Entity vehicle,Entity passenger,CallbackInfo callback) {
        if(DeepCubeDomain.generated((ServerLevel)(Object)this) && !mia$owned(passenger)) callback.cancel();
    }
    private static boolean mia$owned(Entity entity) {
        return entity instanceof ServerPlayer player && DeepCubeRuntime.active(player) || DeepManagedItemIdentity.managed(entity);
    }
}
