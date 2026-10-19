package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import dev.madeinabyss.forge.probe.deep.CameraFogPresentation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** A teleport can precede the cube packet. Admit the world frame before native,
 * Forge, DH or Oculus shadow passes can read the new camera's fluid. */
@Mixin(value=LevelRenderer.class,priority=2000)
public abstract class GeneratedWorldFrameAdmissionMixin {
    @Inject(method="renderLevel",at=@At("HEAD"),cancellable=true)
    private void mia$waitForCameraCube(CallbackInfo callback) {
        var client=Minecraft.getInstance();
        if(ClientDeepRuntime.generatedSessionIdentity(client.level)==null)return;
        boolean readable=CameraFogPresentation.readable(client.level,List.of(client.gameRenderer.getMainCamera().getBlockPosition()));
        dev.madeinabyss.forge.probe.client.GeneratedFrameDiagnostics.world(readable);
        if(!readable)callback.cancel();
    }
}
