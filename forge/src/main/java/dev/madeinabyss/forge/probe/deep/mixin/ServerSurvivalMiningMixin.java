package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepSurvivalPlacement;
import dev.madeinabyss.forge.probe.deep.DeepCubeRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserves native hardness/progress/START/STOP/ABORT; guards ownership before native reads. */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerSurvivalMiningMixin implements DeepCubeRuntime.ServerMiningAccess {
    @Shadow protected ServerLevel level;
    @Shadow @Final protected ServerPlayer player;
    @Shadow private boolean isDestroyingBlock;
    @Shadow private boolean hasDelayedDestroy;
    @Shadow private int lastSentState;
    @Unique private BlockPos mia$deepTarget;
    @Unique private void mia$cancelDeep() {isDestroyingBlock=false;hasDelayedDestroy=false;lastSentState=-1;mia$deepTarget=null;}
    @Override public void mia$cancelMining(){if(mia$deepTarget!=null)mia$cancelDeep();}
    @Override public boolean mia$miningActive(){return mia$deepTarget!=null && (isDestroyingBlock||hasDelayedDestroy);}
    @Inject(method="handleBlockBreakAction",at=@At("HEAD"),cancellable=true)
    private void mia$admitNativeMining(BlockPos pos,ServerboundPlayerActionPacket.Action action,Direction direction,int maxHeight,int sequence,CallbackInfo ci) {
        if(!DeepCubeDomain.belowVanilla(level,pos))return;
        if(action==ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) {
            if(mia$deepTarget==null || DeepCubeDomain.space(level).orElseThrow().block(
                    new dev.madeinabyss.kernel.BlockCoordinate(pos.getX(),pos.getY(),pos.getZ())).isEmpty()) {mia$cancelDeep();ci.cancel();}
            return;
        }
        var engine=DeepSurvivalPlacement.lookup(level);
        if(engine.isEmpty() || !engine.get().readyForMining(player,pos) || !DeepCubeRuntime.miningContextReady(player,pos)) {mia$cancelDeep();DeepCubeRuntime.miningCancelled(player,"unavailable_mining_context");ci.cancel();return;}
        mia$deepTarget=pos.immutable();
    }
    @Inject(method="handleBlockBreakAction",at=@At("RETURN"))
    private void mia$abortNativeMining(BlockPos pos,ServerboundPlayerActionPacket.Action action,Direction direction,int maxHeight,int sequence,CallbackInfo ci) {
        if(action==ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK && mia$deepTarget!=null){mia$cancelDeep();DeepCubeRuntime.miningCancelled(player,"aborted");}
    }
    @Inject(method="tick",at=@At("HEAD"))
    private void mia$knownMiningTick(CallbackInfo ci) {
        if(mia$deepTarget==null)return;
        if(DeepSurvivalPlacement.lookup(level).map(e->e.readyForMining(player,mia$deepTarget)).orElse(false) && DeepCubeRuntime.miningContextReady(player,mia$deepTarget))return;
        mia$cancelDeep(); // Native tick then advances its ordinary clock, with both deep mining flags clear.
        DeepCubeRuntime.miningCancelled(player,"unavailable_mining_context");
    }
    @Inject(method="destroyBlock",at=@At("HEAD"),cancellable=true)
    private void mia$nativeMiningCommit(BlockPos pos,CallbackInfoReturnable<Boolean> result) {
        if(player.gameMode.getGameModeForPlayer()==GameType.SURVIVAL && DeepCubeDomain.belowVanilla(level,pos)
                && !DeepSurvivalPlacement.active(level)) {
            var engine=DeepSurvivalPlacement.lookup(level);boolean accepted=engine.map(e->e.mine(player,pos)).orElse(false);
            DeepCubeRuntime.miningCompleted(player,pos,accepted,engine.map(DeepSurvivalPlacement::lastError).orElse("mining_unavailable"));
            result.setReturnValue(accepted);
        }
    }
}
