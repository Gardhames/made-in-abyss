package dev.madeinabyss.forge.probe.deep.mixin;
import dev.madeinabyss.forge.probe.client.GeneratedDrawCounters;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo @Mixin(targets="net.irisshaders.iris.compat.dh.IrisLodRenderProgram",remap=false)
public abstract class GeneratedDhUniformCountersMixin {
    // Matrix overloads go through RenderSystem/GlStateManager and are already counted there.
    @Inject(method={"setUniform(IF)V","setUniform(ILcom/seibel/distanthorizons/api/objects/math/DhApiVec3f;)V"},at=@At("HEAD"),remap=false)
    private void mia$uniform(CallbackInfo ci){GeneratedDrawCounters.add(GeneratedDrawCounters.UNIFORM_UPLOADS,1);}
    @Inject(method={"bind","fillUniformData","unbind"},at=@At("HEAD"),remap=false)
    private void mia$program(CallbackInfo ci){GeneratedDrawCounters.add(GeneratedDrawCounters.PROGRAM_BINDS,1);}
}
