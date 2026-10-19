package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import dev.madeinabyss.forge.probe.client.ClientGeneratedLight;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native rain geometry keeps its ordinary buffers; inputs come from the same generated scene. */
@Mixin(LevelRenderer.class)
public abstract class GeneratedWeatherMixin {
    @Inject(method="renderSnowAndRain",at=@At("HEAD"),cancellable=true)
    private void mia$preflightRain(LightTexture texture,float partial,double x,double y,double z,CallbackInfo callback){
        var client=Minecraft.getInstance();if(!ClientDeepRuntime.generatedAccepted(client.level))return;
        var source=ClientGeneratedLight.source();
        if(source==null){callback.cancel();return;}
        if(client.level.getRainLevel(partial)<=0)return;
        int cx=(int)Math.floor(x),cy=(int)Math.floor(y),cz=(int)Math.floor(z);
        // Native fancy weather uses radius10. Refuse the optional pass before any GL changes.
        for(int zz=cz-10;zz<=cz+10;zz++)for(int xx=cx-10;xx<=cx+10;xx++){
            var exposure=ClientGeneratedLight.rainHeight(xx,zz);
            if(exposure.isEmpty()){callback.cancel();return;}
            int height=exposure.getAsInt(),sampleY=Math.max(cy,height);
            if(height<=cy+10 && ClientGeneratedLight.packedLight(xx,sampleY,zz).isEmpty()){callback.cancel();return;}
        }
    }
    @Redirect(method="renderSnowAndRain",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private int mia$generatedRainHeight(Level level,Heightmap.Types type,int x,int z){
        if(ClientDeepRuntime.generatedAccepted(level))return ClientGeneratedLight.rainHeight(x,z).orElseThrow();
        return level.getHeight(type,x,z);
    }
    @Redirect(method="renderSnowAndRain",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"),require=2)
    private int mia$generatedRainLight(BlockAndTintGetter view,BlockPos pos){
        if(ClientDeepRuntime.generatedAccepted(Minecraft.getInstance().level))
            return ClientGeneratedLight.packedLight(pos.getX(),pos.getY(),pos.getZ()).orElseThrow();
        return LevelRenderer.getLightColor(view,pos);
    }
    @Inject(method="tickRain",at=@At("HEAD"),cancellable=true)
    private void mia$preflightRainParticles(Camera camera,CallbackInfo callback){
        var level=Minecraft.getInstance().level;if(!ClientDeepRuntime.generatedAccepted(level))return;
        var source=ClientGeneratedLight.source();var space=DeepCubeDomain.space(level);
        if(source==null||space.isEmpty()){callback.cancel();return;}
        if(level.getRainLevel(1)<=0)return;
        var pos=camera.getBlockPosition();
        for(int z=pos.getZ()-10;z<=pos.getZ()+10;z++)for(int x=pos.getX()-10;x<=pos.getX()+10;x++){
            var exposure=ClientGeneratedLight.rainHeight(x,z);
            if(exposure.isEmpty()){callback.cancel();return;}
            int y=exposure.getAsInt();
            if(Math.abs((long)y-pos.getY())<=10 && !DeepCubeDomain.knownGeneratedHalo(level,space.get(),new AABB(new BlockPos(x,y-1,z)),2)){
                callback.cancel();return;
            }
        }
    }
    @Redirect(method="tickRain",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/LevelReader;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"),require=2)
    private BlockPos mia$generatedParticleHeight(LevelReader view,Heightmap.Types type,BlockPos pos){
        if(ClientDeepRuntime.generatedAccepted(Minecraft.getInstance().level))return new BlockPos(pos.getX(),ClientGeneratedLight.rainHeight(pos.getX(),pos.getZ()).orElseThrow(),pos.getZ());
        return view.getHeightmapPos(type,pos);
    }
}
