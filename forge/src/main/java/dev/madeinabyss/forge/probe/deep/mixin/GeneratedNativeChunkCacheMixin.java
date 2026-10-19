package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native cache maintenance/broadcasts own native data; generated host spawning/ticking remain paused. */
@Mixin(ServerChunkCache.class)
public abstract class GeneratedNativeChunkCacheMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow private void tickChunks() {throw new AssertionError("Mixin shadow");}

    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerChunkCache;tickChunks()V"))
    private void mia$nativeChunkMaintenance(ServerChunkCache source) {
        if(DeepCubeDomain.generated(level))DeepCubeDomain.readNativeHost(level,this::tickChunks);
        else tickChunks();
    }
}
