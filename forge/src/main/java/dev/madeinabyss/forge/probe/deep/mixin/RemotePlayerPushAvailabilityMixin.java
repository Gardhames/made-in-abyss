package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.authlib.GameProfile;
import dev.madeinabyss.forge.probe.deep.RemotePlayerAvailability;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Remote native lerp/swing/bob proceeds; its optional push query requires a known post-lerp neighborhood. */
@Mixin(RemotePlayer.class)
public abstract class RemotePlayerPushAvailabilityMixin extends AbstractClientPlayer {
    protected RemotePlayerPushAvailabilityMixin(ClientLevel level, GameProfile profile) { super(level, profile); }

    @Redirect(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/RemotePlayer;pushEntities()V"))
    private void mia$knownRemotePushNeighbors(RemotePlayer remote) {
        // Native typed player query includes the source itself. Its pushability predicate
        // reads feet/climbability before filtering out non-local client players.
        if (!RemotePlayerAvailability.canTickEnvironment(remote.level(), remote.getBoundingBox(), Vec3.ZERO, Vec3.ZERO)) {
            RemotePlayerAvailability.recordSkippedPushTick();
            return;
        }
        super.pushEntities();
    }
}
