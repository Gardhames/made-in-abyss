package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.authlib.GameProfile;
import dev.madeinabyss.forge.probe.deep.RemotePlayerAvailability;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep native remote interpolation/animation while refusing unavailable environmental super ticks. */
@Mixin(AbstractClientPlayer.class)
public abstract class ClientRemotePlayerAvailabilityMixin extends Player {
    protected ClientRemotePlayerAvailabilityMixin(Level level, BlockPos pos, float yaw, GameProfile profile) {
        super(level, pos, yaw, profile);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;tick()V"))
    private void mia$knownRemoteEnvironment(Player receiver) {
        if ((Object) this instanceof RemotePlayer remote) {
            // Match the next position interpolation performed by native RemotePlayer.aiStep.
            Vec3 interpolationStep = lerpSteps > 0
                    ? new Vec3((lerpX - getX()) / lerpSteps, (lerpY - getY()) / lerpSteps, (lerpZ - getZ()) / lerpSteps)
                    : Vec3.ZERO;
            if (!RemotePlayerAvailability.canTickEnvironment(level(), getBoundingBox(), interpolationStep, getDeltaMovement())) {
                RemotePlayerAvailability.recordSkippedEnvironmentTick();
                remote.aiStep();
                RemotePlayerAvailability.recordRemoteInterpolationTick();
                return;
            }
            RemotePlayerAvailability.recordKnownEnvironmentTick();
        }
        // LocalPlayer and ordinary/known remotes keep the original Player.tick path.
        // AbstractClientPlayer has already copied its private previous-delta snapshot;
        // outer RemotePlayer.tick still calls calculateEntityAnimation after this returns.
        super.tick();
    }
}
