package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepSurvivalPlacement;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerSurvivalPlacementMixin {
    @Inject(method="useItemOn",at=@At("HEAD"),cancellable=true)
    private void mia$survivalPlace(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,
                                   CallbackInfoReturnable<InteractionResult> result) {
        if(player.gameMode.getGameModeForPlayer()==GameType.SURVIVAL && DeepCubeDomain.belowVanilla(level,hit.getBlockPos())
                && !DeepSurvivalPlacement.active(level))
            result.setReturnValue(DeepSurvivalPlacement.lookup(level)
                    .map(engine->engine.place(player,level,stack,hand,hit)).orElse(InteractionResult.FAIL));
    }
}
