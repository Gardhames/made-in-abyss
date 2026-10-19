package dev.madeinabyss.forge.probe.deep.mixin;
import dev.madeinabyss.forge.probe.client.GeneratedDrawCounters;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ShaderInstance.class)
public abstract class GeneratedShaderApplyCountersMixin {
    @Inject(method="apply",at=@At("HEAD"))private void mia$apply(CallbackInfo ci){GeneratedDrawCounters.add(GeneratedDrawCounters.APPLIES,1);}
}
