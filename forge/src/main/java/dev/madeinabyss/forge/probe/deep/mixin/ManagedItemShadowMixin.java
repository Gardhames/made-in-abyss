package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemIdentity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native shadow reads ChunkAccess directly, which has no cube Y routing. */
@Mixin(EntityRenderDispatcher.class)
public abstract class ManagedItemShadowMixin {
    @Inject(method="renderShadow",at=@At("HEAD"),cancellable=true)
    private static void mia$skipUnsupportedDeepItemShadow(PoseStack poses,MultiBufferSource buffers,Entity entity,float strength,float partial,LevelReader level,float radius,CallbackInfo callback){
        if(DeepManagedItemIdentity.managed(entity) && DeepCubeDomain.isDeep(entity.level(),entity.getY())){DeepManagedItemIdentity.recordShadowSkip(entity);callback.cancel();}
    }
}
