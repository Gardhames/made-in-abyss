package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import dev.madeinabyss.forge.probe.deep.CameraFogPresentation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import com.mojang.blaze3d.shaders.FogShape;
import net.minecraftforge.client.ForgeHooksClient;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** Forge 47.4.20 has two additional fluid reads outside Camera#getFluidInCamera. */
@Mixin(value=ForgeHooksClient.class,remap=false)
public abstract class GeneratedForgeFogAdmissionMixin {
    @Unique private static boolean mia$pending(Camera camera,ClientLevel level) {
        return ClientDeepRuntime.generatedSessionIdentity(level)!=null
                && !CameraFogPresentation.readable(level,List.of(camera.getBlockPosition()));
    }
    @Inject(method="getFogColor",at=@At("HEAD"),cancellable=true)
    private static void mia$holdFogColor(Camera camera,float partialTick,ClientLevel level,int renderDistance,
                                        float darken,float red,float green,float blue,CallbackInfoReturnable<Vector3f> callback) {
        // Keep the already computed presentation for this frame. No fabricated FluidState
        // reaches native fluid extensions or the authoritative block/physics consumers.
        if(mia$pending(camera,level))callback.setReturnValue(new Vector3f(red,green,blue));
    }
    @Inject(method="onFogRender",at=@At("HEAD"),cancellable=true)
    private static void mia$holdFogDistances(FogRenderer.FogMode mode,FogType type,Camera camera,float partialTick,
                                            float renderDistance,float nearDistance,float farDistance,FogShape shape,CallbackInfo callback) {
        if(camera.getEntity()!=null && camera.getEntity().level() instanceof ClientLevel level && mia$pending(camera,level))callback.cancel();
    }
}
