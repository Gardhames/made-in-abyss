package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native chunk completion must process its saved neighbors, fluids, writes and block entities together. */
@Mixin(ChunkMap.class)
public abstract class GeneratedNativeChunkCompletionMixin {
    @Redirect(method="lambda$prepareTickingChunk$41",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/chunk/LevelChunk;postProcessGeneration()V"))
    private void mia$completeNativeChunk(LevelChunk chunk) {
        if(DeepCubeDomain.generated(chunk.getLevel()))
            DeepCubeDomain.readNativeHost(chunk.getLevel(),chunk::postProcessGeneration);
        else chunk.postProcessGeneration();
    }
}
