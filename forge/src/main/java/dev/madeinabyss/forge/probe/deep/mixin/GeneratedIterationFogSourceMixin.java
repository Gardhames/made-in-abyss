package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.client.GeneratedIterationFogAdapter;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional Oculus client source hook; no archive, physical uniform or DH modification. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.shaderpack.programs.ProgramSource",remap=false)
public abstract class GeneratedIterationFogSourceMixin {
    @Shadow public abstract String getName();
    @Unique private String mia$fogOriginal;
    @Unique private String mia$fogAdapted;
    @Inject(method="getFragmentSource",at=@At("RETURN"),cancellable=true,remap=false,require=0)
    private void mia$fogSource(CallbackInfoReturnable<Optional<String>> callback) {
        var source=callback.getReturnValue();if(source.isEmpty())return;
        String original=source.get();
        if(original==mia$fogOriginal){callback.setReturnValue(Optional.of(mia$fogAdapted));return;}
        var result=GeneratedIterationFogAdapter.transform(this,getName(),original);
        if(result.cacheable()){mia$fogOriginal=original;mia$fogAdapted=result.source();}
        if(result.source()!=original)callback.setReturnValue(Optional.of(result.source()));
    }
}
