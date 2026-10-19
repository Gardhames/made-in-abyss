package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.item.ItemStack;

/** Blocks unsupported block interaction before packed vanilla prediction/packets are touched. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin implements ClientDeepRuntime.MiningClientAccess {
    @Shadow private boolean isDestroying;
    @Shadow private float destroyProgress;
    @Shadow private float destroyTicks;
    @Shadow private int destroyDelay;
    @Shadow private BlockPos destroyBlockPos;
    @Shadow private ItemStack destroyingItem;
    @Override public void mia$resetMining(){if(Minecraft.getInstance().level!=null&&!ClientDeepRuntime.miningCoordinate(destroyBlockPos))return;isDestroying=false;destroyProgress=0;destroyTicks=0;destroyBlockPos=new BlockPos(-1,-1,-1);destroyingItem=ItemStack.EMPTY;}
    @Override public com.google.gson.JsonObject mia$miningState(){
        var r=new com.google.gson.JsonObject();r.addProperty("isDestroying",isDestroying);r.addProperty("destroyProgress",destroyProgress);r.addProperty("destroyTicks",destroyTicks);r.addProperty("destroyDelay",destroyDelay);
        var p=new com.google.gson.JsonArray();p.add(destroyBlockPos.getX());p.add(destroyBlockPos.getY());p.add(destroyBlockPos.getZ());r.add("target",p);r.addProperty("toolDamage",destroyingItem.getDamageValue());return r;
    }
    @Inject(method="tick",at=@At("HEAD"))
    private void mia$miningLifecycle(CallbackInfo ci){ClientDeepRuntime.miningTick();}
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void mia$blockDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> result) {
        if (ClientDeepRuntime.miningCoordinate(pos)) result.setReturnValue(false);
    }
    @Inject(method = {"startDestroyBlock", "continueDestroyBlock"}, at = @At("HEAD"), cancellable = true)
    private void mia$blockDig(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> result) {
        if (ClientDeepRuntime.miningCoordinate(pos)) {
            var client=Minecraft.getInstance();
            if(client.player!=null && client.player.isCreative())result.setReturnValue(ClientDeepRuntime.breakBlock(pos,direction));
            else if(!ClientDeepRuntime.admitMining(pos))result.setReturnValue(false);
            // Known survival target: let actual native START/continue fields and tool progress run.
        }
    }
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void mia$blockPlace(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> result) {
        if (DeepCubeDomain.belowVanilla(player.level(), hit.getBlockPos())) result.setReturnValue(
                ClientDeepRuntime.place(hit,hand)?InteractionResult.SUCCESS:InteractionResult.FAIL);
    }
}
