package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.OptionalLightWork;
import net.minecraft.client.gui.Gui;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiLightAvailabilityMixin {
    @Inject(method="updateVignetteBrightness",at=@At("HEAD"),cancellable=true)
    private void mia$retainVignetteOnUnknownLight(Entity entity, CallbackInfo callback) {
        if(entity==null || !DeepCubeDomain.blockLightEnabled(entity.level())) return;
        // Exactly the native eye sample. Cancellation preserves the previous vignette state.
        var pos=BlockPos.containing(entity.getX(),entity.getEyeY(),entity.getZ());
        if(!OptionalLightWork.run(entity.level(),OptionalLightWork.Kind.VIGNETTE,
                ()->entity.level().getMaxLocalRawBrightness(pos))) callback.cancel();
    }
}
