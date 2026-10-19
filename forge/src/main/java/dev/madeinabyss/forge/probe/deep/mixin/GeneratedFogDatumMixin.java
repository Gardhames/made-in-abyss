package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.client.GeneratedPreviewAtmosphere;
import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import dev.madeinabyss.forge.probe.deep.CameraFogPresentation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** The accepted cube preview has a deeper void datum than its unchanged native column facade. */
@Mixin(value=FogRenderer.class,priority=2000)
public abstract class GeneratedFogDatumMixin {
    /** DH also reads fluid from its RETURN fog hook, outside Forge's hook. Admit
     * the complete fog update before either callback; retain previous render state. */
    @Inject(method={"setupColor","setupFog"},at=@At("HEAD"),cancellable=true)
    private static void mia$holdPendingFogUpdate(CallbackInfo callback) {
        var client=Minecraft.getInstance();
        if(ClientDeepRuntime.generatedSessionIdentity(client.level)==null)return;
        var camera=client.gameRenderer.getMainCamera();
        if(!CameraFogPresentation.readable(client.level,List.of(camera.getBlockPosition())))callback.cancel();
    }
    @Redirect(method = "setupColor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinBuildHeight()I"))
    private static int mia$generatedVoidDatum(ClientLevel queriedLevel, Camera camera, float partialTick,
                                               ClientLevel fogLevel, int renderDistance, float bossColorModifier) {
        return queriedLevel == fogLevel ? GeneratedPreviewAtmosphere.minimum(queriedLevel, camera)
                : queriedLevel.getMinBuildHeight();
    }
}
