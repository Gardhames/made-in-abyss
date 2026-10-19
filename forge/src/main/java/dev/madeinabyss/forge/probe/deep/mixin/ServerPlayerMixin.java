package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeRuntime;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method={"tick","doTick"},at=@At("HEAD"),cancellable=true)
    private void mia$retainPlayerWindow(CallbackInfo callback) {
        if(!DeepCubeRuntime.prepare((ServerPlayer)(Object)this)) callback.cancel();
    }
}
