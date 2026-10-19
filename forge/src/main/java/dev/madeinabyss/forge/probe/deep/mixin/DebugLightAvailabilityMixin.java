package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.OptionalLightWork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.world.level.LightLayer;
import net.minecraft.SharedConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugLightAvailabilityMixin {
    @Inject(method="getGameInformation",at=@At("HEAD"),cancellable=true)
    private void mia$keepDeepDebugCoordinates(CallbackInfoReturnable<List<String>> callback) {
        var client=Minecraft.getInstance();var entity=client.getCameraEntity();
        if(entity==null || !DeepCubeDomain.isDeep(entity.level(),entity.getY())) return;
        // Forge reads getGameInformation before drawGameInformation: guarding the draw is too late.
        // Coordinates remain available independently of optional, strictly known light inputs.
        var lines=new ArrayList<String>();
        lines.add("Minecraft "+SharedConstants.getCurrentVersion().getName()+" / Made in Abyss cubes");
        lines.add(client.fpsString);
        lines.add(client.levelRenderer.getChunkStatistics());
        lines.add(entity.level().dimension().location().toString());
        lines.add(String.format(Locale.ROOT,"XYZ: %.3f / %.3f / %.3f",entity.getX(),entity.getY(),entity.getZ()));
        var pos=entity.blockPosition();
        lines.add("Block: "+pos.getX()+" "+pos.getY()+" "+pos.getZ());
        lines.add("Cube: "+Math.floorDiv(pos.getX(),16)+" "+Math.floorDiv(pos.getY(),16)+" "+Math.floorDiv(pos.getZ(),16));
        lines.add(String.format(Locale.ROOT,"Facing: %s (%.1f / %.1f)",entity.getDirection(),entity.getYRot(),entity.getXRot()));
        if(DeepCubeDomain.blockLightEnabled(entity.level())) {
            int[] light={-1};
            boolean known=OptionalLightWork.run(entity.level(),OptionalLightWork.Kind.DEBUG,
                    ()->light[0]=entity.level().getBrightness(LightLayer.BLOCK,pos));
            lines.add(known?"BLOCK light: "+light[0]:"BLOCK light: unavailable (unknown inputs)");
        }else lines.add("BLOCK light: disabled");
        var sky=dev.madeinabyss.forge.probe.client.ClientGeneratedLight.packedLight(pos.getX(),pos.getY(),pos.getZ());
        lines.add(sky.isPresent()?"SKY light: "+((sky.getAsInt()>>>20)&15)+" (generated geometry)":"SKY light: unavailable (unknown inputs)");
        callback.setReturnValue(lines);
    }
}
