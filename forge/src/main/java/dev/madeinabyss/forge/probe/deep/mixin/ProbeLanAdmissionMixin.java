package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.authlib.GameProfile;
import dev.madeinabyss.forge.probe.client.ProbeLanBridge;
import net.minecraft.client.server.IntegratedPlayerList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.net.SocketAddress;

/** Additional pre-admission restriction on the one explicit client-owned diagnostic bridge. */
@Mixin(IntegratedPlayerList.class)
public abstract class ProbeLanAdmissionMixin {
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void mia$loopbackProbeRoster(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> result) {
        IntegratedPlayerList list = (IntegratedPlayerList) (Object) this;
        Component rejection = ProbeLanBridge.rejection(list.getServer(), address, profile);
        if (rejection != null) result.setReturnValue(rejection);
    }
}
