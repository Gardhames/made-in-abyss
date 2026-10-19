package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepChestEngine;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepSurvivalPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelChestMixin {
    @Inject(method="addFreshEntity",at=@At("HEAD"),cancellable=true)
    private void mia$captureNativeSurvivalDrop(net.minecraft.world.entity.Entity entity,CallbackInfoReturnable<Boolean> result) {
        var level=(ServerLevel)(Object)this;
        if(DeepSurvivalPlacement.active(level))result.setReturnValue(DeepSurvivalPlacement.lookup(level).orElseThrow().captureDrop(entity));
    }
    @Inject(method="destroyBlockProgress",at=@At("HEAD"),cancellable=true)
    private void mia$noPackedMiningCracks(int id,BlockPos pos,int progress,CallbackInfo ci) {
        if(DeepCubeDomain.belowVanilla((ServerLevel)(Object)this,pos))ci.cancel();
    }
    @Inject(method="levelEvent",at=@At("HEAD"),cancellable=true)
    private void mia$noPackedMiningEvent(net.minecraft.world.entity.player.Player player,int id,BlockPos pos,int data,CallbackInfo ci) {
        if(DeepSurvivalPlacement.active((ServerLevel)(Object)this))ci.cancel();
    }
    @Inject(method="sendBlockUpdated",at=@At("HEAD"),cancellable=true)
    private void mia$survivalNoPackedUpdate(BlockPos pos,net.minecraft.world.level.block.state.BlockState before,
                                           net.minecraft.world.level.block.state.BlockState after,int flags,CallbackInfo ci) {
        if(DeepSurvivalPlacement.active((ServerLevel)(Object)this)) ci.cancel();
    }
    @Inject(method="blockEvent",at=@At("HEAD"),cancellable=true)
    private void mia$chestEvent(BlockPos pos,Block block,int id,int value,CallbackInfo ci) {
        var level=(ServerLevel)(Object)this;
        if(DeepCubeDomain.isDeep(level,pos)) {
            DeepChestEngine.lookup(level).orElseThrow(()->new UnsupportedOperationException("deep block events unavailable")).blockEvent(level,pos,block,id,value);
            ci.cancel();
        }
    }
    @Inject(method="gameEvent",at=@At("HEAD"),cancellable=true)
    private void mia$chestDecorativeEvent(GameEvent event,Vec3 point,GameEvent.Context context,CallbackInfo ci) {
        var ownedLevel=(ServerLevel)(Object)this;
        if(DeepSurvivalPlacement.active(ownedLevel) && (event==GameEvent.BLOCK_PLACE || event==GameEvent.BLOCK_DESTROY)) {
            // Retain the Forge event; cube sculk/listener dispatch is outside this bounded placement scope.
            net.minecraftforge.common.ForgeHooks.onVanillaGameEvent(ownedLevel,event,point,context);
            ci.cancel();return;
        }
        // Preserve existing Forge/native movement events. Chest owns only opener decoration.
        if(event!=GameEvent.CONTAINER_OPEN && event!=GameEvent.CONTAINER_CLOSE)return;
        var level=(ServerLevel)(Object)this;var pos=BlockPos.containing(point);
        if(DeepCubeDomain.isDeep(level,pos)) {
            if(DeepChestEngine.lookup(level).orElseThrow(()->new UnsupportedOperationException("deep game events unavailable")).get(level,pos)==null)
                throw new UnsupportedOperationException("unsupported deep game event");
            // Bounded chest scope has no sculk/game-event listener system. Never fall through into native chunks.
            ci.cancel();
        }
    }
}
