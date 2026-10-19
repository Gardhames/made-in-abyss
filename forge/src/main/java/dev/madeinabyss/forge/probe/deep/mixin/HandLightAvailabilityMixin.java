package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.OptionalLightWork;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class HandLightAvailabilityMixin {
    @Inject(method="renderItemInHand",at=@At("HEAD"),cancellable=true)
    private void mia$skipUnknownHandLight(PoseStack poses, Camera camera, float partialTick, CallbackInfo callback) {
        var client=Minecraft.getInstance();var player=client.player;
        if(player==null || !DeepCubeDomain.blockLightEnabled(player.level())) return;
        // Preflight before projection, pose and light state changes in this optional pass.
        if(!OptionalLightWork.run(player.level(),OptionalLightWork.Kind.HANDS,
                ()->client.getEntityRenderDispatcher().getPackedLightCoords(player,partialTick))) callback.cancel();
    }
}
