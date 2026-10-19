package dev.madeinabyss.forge.probe.deep.mixin;
import dev.madeinabyss.forge.probe.client.GeneratedDrawCounters;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo @Mixin(targets="net.irisshaders.iris.gl.IrisRenderSystem",remap=false)
public abstract class GeneratedIrisUniformCountersMixin {
    @Inject(method={"uniformMatrix4fv","uniform1f","uniform2f","uniform2i","uniform3f","uniform3i","uniform4f","uniform4i"},at=@At("HEAD"),remap=false)
    private static void mia$uniform(CallbackInfo ci){GeneratedDrawCounters.add(GeneratedDrawCounters.UNIFORM_UPLOADS,1);}
}
