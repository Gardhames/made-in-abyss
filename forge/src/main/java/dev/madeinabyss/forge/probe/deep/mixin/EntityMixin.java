package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemIdentity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import dev.madeinabyss.forge.cube.CubeBlockView;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method="remove",at=@At("HEAD"),cancellable=true)
    private void mia$stageNativePickupRemoval(Entity.RemovalReason reason,CallbackInfo ci) {
        if(dev.madeinabyss.forge.probe.deep.DeepManagedItemEngine.stageRemoval((Entity)(Object)this,reason))ci.cancel();
    }
    @Inject(method="shouldBeSaved",at=@At("HEAD"),cancellable=true)
    private void mia$exclusiveManagedItemPersistence(CallbackInfoReturnable<Boolean> result){
        Entity entity=(Entity)(Object)this;
        if(!entity.level().isClientSide && DeepManagedItemIdentity.managed(entity))result.setReturnValue(false);
    }
    @Inject(method="isAlwaysTicking",at=@At("HEAD"),cancellable=true)
    private void mia$managedClientItemTick(CallbackInfoReturnable<Boolean> result){
        Entity entity=(Entity)(Object)this;
        if(entity.level().isClientSide && DeepManagedItemIdentity.managed(entity))result.setReturnValue(true);
    }
    /** Optional inspection must stop on unavailable data, while required block/clip reads remain strict. */
    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void mia$pickKnownBlocks(double distance, float partialTick, boolean fluids, CallbackInfoReturnable<HitResult> result) {
        Entity entity = (Entity) (Object) this;
        if (DeepCubeDomain.lookup(entity.level()).isEmpty()) return;
        Vec3 from=entity.getEyePosition(partialTick),to=from.add(entity.getViewVector(partialTick).scale(distance));
        try {
            result.setReturnValue(entity.level().clip(new ClipContext(from,to,ClipContext.Block.OUTLINE,
                    fluids?ClipContext.Fluid.ANY:ClipContext.Fluid.NONE,entity)));
        } catch (CubeBlockView.MissingCubeException unavailable) {
            Vec3 backward=from.subtract(to);
            result.setReturnValue(BlockHitResult.miss(to,Direction.getNearest(backward.x,backward.y,backward.z),BlockPos.containing(to)));
        }
    }
    @Inject(method = "setPosRaw", at = @At("HEAD"))
    private void mia$invalidateRemoteSupport(double x, double y, double z, CallbackInfo result) {
        Entity entity = (Entity) (Object) this;
        if (entity.mainSupportingBlockPos.isEmpty() || DeepCubeDomain.lookup(entity.level()).isEmpty()) return;
        int vanillaMin = DeepCubeDomain.managedTop(entity.level());
        if (entity.getY() >= vanillaMin && y >= vanillaMin) return;
        var oldBounds = entity.getBoundingBox();
        var targetBounds = oldBounds.move(x - entity.getX(), y - entity.getY(), z - entity.getZ());
        if (!oldBounds.intersects(targetBounds)) {
            // Native setter clears both mainSupportingBlockPos and onGroundNoBlocks without querying absent cubes.
            // Native Entity.move recomputes support after setPos; overlapping small updates keep original semantics.
            entity.setOnGround(false);
        }
    }
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void mia$freezeUnknown(MoverType type, Vec3 delta, CallbackInfo result) {
        Entity entity = (Entity) (Object) this;
        if (!DeepCubeDomain.allowsMove(entity.level(), entity.getBoundingBox().expandTowards(delta))) {
            entity.setDeltaMovement(Vec3.ZERO);
            result.cancel();
        }
    }
    @Inject(method = "checkBelowWorld", at = @At("HEAD"), cancellable = true)
    private void mia$technicalVoidBoundary(CallbackInfo result) {
        Entity entity = (Entity) (Object) this;
        if (DeepCubeDomain.isDeep(entity.level(), entity.getY())) result.cancel();
    }
}
