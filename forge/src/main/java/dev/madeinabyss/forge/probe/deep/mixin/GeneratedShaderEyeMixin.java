package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import dev.madeinabyss.forge.probe.client.ClientGeneratedLight;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Eye adaptation is presentation state: hold its last sample while a light batch is pending.
 * This never supplies guessed values to Level, block meshes, or gameplay light queries. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.uniforms.CommonUniforms",remap=false)
public abstract class GeneratedShaderEyeMixin {
    @Unique private static Vector2i mia$lastEye = new Vector2i();
    @Inject(method="getEyeBrightness",at=@At("HEAD"),cancellable=true,remap=false)
    private static void mia$availableEye(CallbackInfoReturnable<Vector2i> callback) {
        var client=Minecraft.getInstance();
        // Cubes, teleport and the server admission reply can arrive in different frames.
        if(client.cameraEntity==null||!ClientDeepRuntime.generatedSession(client.level))return;
        var eye=BlockPos.containing(client.cameraEntity.getX(),client.cameraEntity.getEyeY(),client.cameraEntity.getZ());
        var light=ClientGeneratedLight.packedLight(eye.getX(),eye.getY(),eye.getZ());
        if(light.isPresent()) {
            int packed=light.getAsInt();
            mia$lastEye=new Vector2i(packed&0xffff,(packed>>>16)&0xffff);
        }
        callback.setReturnValue(new Vector2i(mia$lastEye));
    }
    @Inject(method="getEyeBrightness",at=@At("RETURN"),remap=false)
    private static void mia$rememberEye(CallbackInfoReturnable<Vector2i> callback) {
        mia$lastEye=new Vector2i(callback.getReturnValue());
    }
}
