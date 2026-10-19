package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.OptionalAmbientLight;
import dev.madeinabyss.forge.probe.deep.RemotePlayerAvailability;
import dev.madeinabyss.forge.probe.deep.GeneratedLightAccess;
import net.minecraft.client.resources.sounds.AmbientSoundHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Redirect(method="tick",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/resources/sounds/AmbientSoundHandler;tick()V"))
    private void mia$skipUnknownAmbientLight(AmbientSoundHandler handler) {
        OptionalAmbientLight.tick(((LocalPlayer)(Object)this).level(),handler::tick);
    }

    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void mia$retainReceivedWindow(CallbackInfo callback) {
        var player=(LocalPlayer)(Object)this;
        if(!DeepCubeDomain.allowsMove(player.level(),player.getBoundingBox().expandTowards(player.getDeltaMovement()))
                || player.level().isRaining() && !GeneratedLightAccess.rainReady(player.level(),player.blockPosition())) {
            // Light/exposure preparation runs from the render owner, independently of this
            // tick. Keep the player still until both cube and weather reads are confirmed.
            RemotePlayerAvailability.recordSkippedEnvironmentTick();
            player.setDeltaMovement(Vec3.ZERO);callback.cancel();
        }
    }
}
