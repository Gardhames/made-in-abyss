package dev.madeinabyss.forge.probe.deep.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native renderer cracks use BlockPos.asLong; deep decoration is unsupported. */
@Mixin(ClientLevel.class)
public abstract class ClientMiningCrackMixin {
    @Inject(method="destroyBlockProgress",at=@At("HEAD"),cancellable=true)
    private void mia$noPackedCracks(int id,BlockPos pos,int progress,CallbackInfo ci) {
        if(Boolean.getBoolean("mia.cube.playerProbe") && pos.getY()<((ClientLevel)(Object)this).getMinBuildHeight())ci.cancel();
    }
}
