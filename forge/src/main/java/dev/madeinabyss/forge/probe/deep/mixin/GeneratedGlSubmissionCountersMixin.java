package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.madeinabyss.forge.probe.client.GeneratedDrawCounters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Installed only for -Dmia.render.drawCounters=true. Counts driver submission boundaries. */
@Mixin(GlStateManager.class)
public abstract class GeneratedGlSubmissionCountersMixin {
    @Inject(method="_drawElements",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL11;glDrawElements(IIIJ)V",remap=false))
    private static void mia$draw(CallbackInfo ci){GeneratedDrawCounters.add(GeneratedDrawCounters.DRAWS,1);}
    @Inject(method="_glBindFramebuffer",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL30;glBindFramebuffer(II)V",remap=false))
    private static void mia$fbo(CallbackInfo ci){GeneratedDrawCounters.add(GeneratedDrawCounters.FRAMEBUFFER_BINDS,1);}
    @Inject(method="_glUseProgram",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL20;glUseProgram(I)V",remap=false))
    private static void mia$program(CallbackInfo ci){GeneratedDrawCounters.add(GeneratedDrawCounters.PROGRAM_BINDS,1);}
    @Inject(method={"_glUniform1","_glUniform1i","_glUniform2","_glUniform3","_glUniform4","_glUniformMatrix2","_glUniformMatrix3","_glUniformMatrix4"},at=@At("HEAD"))
    private static void mia$uniform(CallbackInfo ci){GeneratedDrawCounters.add(GeneratedDrawCounters.UNIFORM_UPLOADS,1);}
}
