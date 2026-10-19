package dev.madeinabyss.forge.probe.deep.mixin;
import dev.madeinabyss.forge.probe.client.GeneratedDrawCounters;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo @Mixin(targets={"net.irisshaders.iris.pipeline.programs.ExtendedShader","net.irisshaders.iris.pipeline.programs.FallbackShader"},remap=false)
public abstract class GeneratedExtendedApplyCountersMixin {
    @Inject(method="apply",at=@At("HEAD"),remap=false)private void mia$apply(CallbackInfo ci){GeneratedDrawCounters.add(GeneratedDrawCounters.APPLIES,1);}
}
