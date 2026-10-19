package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.cube.NativeLightRevision;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
public abstract class NativeLightSectionMixin implements NativeLightRevision {
    @Unique private long mia$lightRevision;
    @Unique private boolean mia$lightReady = true;

    @Override public long mia$lightContentRevision() { return mia$lightRevision; }
    @Override public boolean mia$lightInputReady() { return mia$lightReady; }

    // The four-argument setter delegates here. Native palette publication precedes Level callbacks.
    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"))
    private void mia$beforeState(int x, int y, int z, BlockState state, boolean locked, CallbackInfoReturnable<BlockState> result) {
        if (((LevelChunkSection) (Object) this).getBlockState(x, y, z) != state)
            mia$lightRevision = Math.incrementExact(mia$lightRevision);
    }

    // Client chunk packets reuse sections and bypass the block setter. A failed/partial read stays unavailable.
    @Inject(method = "read", at = @At("HEAD"))
    private void mia$beforePacket(FriendlyByteBuf buffer, CallbackInfo result) {
        mia$lightRevision = Math.incrementExact(mia$lightRevision);
        mia$lightReady = false;
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void mia$afterPacket(FriendlyByteBuf buffer, CallbackInfo result) { mia$lightReady = true; }
}
