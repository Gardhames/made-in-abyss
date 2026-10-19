package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import dev.madeinabyss.forge.probe.deep.OptionalLightWork;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Oculus replaces vanilla's hand pass; preflight both solid and translucent paths before GL changes. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pathways.HandRenderer",remap=false)
public abstract class GeneratedShaderHandMixin {
    @Inject(method={"renderSolid","renderTranslucent"},at=@At("HEAD"),cancellable=true,remap=false)
    private void mia$knownHandLight(PoseStack poses,float partialTick,Camera camera,GameRenderer renderer,
                                   @Coerce Object pipeline,CallbackInfo callback){
        var client=Minecraft.getInstance();var entity=camera.getEntity();
        if(entity==null||!ClientDeepRuntime.generatedSession(client.level))return;
        // Oculus samples the camera entity at this frame's partial tick, not the player's
        // previous tick. Across a light boundary those positions can have different availability.
        // Cancel before Oculus changes ACTIVE, pipeline phase, projection or its pose stack.
        if(!OptionalLightWork.run(entity.level(),OptionalLightWork.Kind.HANDS,
                ()->client.getEntityRenderDispatcher().getPackedLightCoords(entity,partialTick)))callback.cancel();
    }
}
