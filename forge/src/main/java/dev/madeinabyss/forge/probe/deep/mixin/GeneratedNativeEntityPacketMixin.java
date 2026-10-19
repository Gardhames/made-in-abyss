package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemIdentity;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Late packets for retained host mobs inspect native support; cube players and items retain cube routing. */
@Mixin(ClientPacketListener.class)
public abstract class GeneratedNativeEntityPacketMixin {
    @Redirect(method={"handleMoveEntity","handleTeleportEntity"},at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/Entity;setOnGround(Z)V"),require=2)
    private void mia$hostEntitySupport(Entity entity,boolean onGround) {
        if(DeepCubeDomain.generated(entity.level()) && !(entity instanceof Player)
                && !DeepManagedItemIdentity.managed(entity))
            DeepCubeDomain.readNativeHost(entity.level(),()->entity.setOnGround(onGround));
        else entity.setOnGround(onGround);
    }
}
