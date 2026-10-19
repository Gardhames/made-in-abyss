package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemIdentity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guard the complete native item tick, including reads preceding Entity.move. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityTickAvailabilityMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void mia$knownWholeItemTick(CallbackInfo callback){
        var item=(ItemEntity)(Object)this;
        if(!DeepCubeDomain.isDeep(item.level(),item.getY()))return;
        // Native ServerEntity packets own client XYZ. Client prediction would diverge while
        // the authoritative item is paused or waiting for a known motion halo.
        if(item.level().isClientSide && DeepManagedItemIdentity.managed(item)){
            DeepManagedItemIdentity.recordAuthorityTickSkip(item);callback.cancel();return;
        }
        Vec3 velocity=item.getDeltaMovement();
        Vec3 prospective=item.isNoGravity()?velocity:velocity.add(0,-.04,0);
        var swept=item.getBoundingBox().expandTowards(velocity).expandTowards(prospective);
        if(!DeepCubeDomain.allowsMove(item.level(),swept)){DeepManagedItemIdentity.recordTickSkip(item);callback.cancel();}
    }
}
