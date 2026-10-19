package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Refuses optional spawn candidates whose deep neighborhood is not resident. */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Inject(method="spawnForChunk",at=@At("HEAD"),cancellable=true)
    private static void mia$retainHostSpawnSimulation(net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.chunk.LevelChunk chunk,NaturalSpawner.SpawnState state,
            boolean friendly,boolean hostile,boolean persistent,org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
        // Native selection reads fortress/biome support BEFORE canSpawnAtBody. The replaced host
        // must not start that process; canon creature admission is a separate cube-owned system.
        if(DeepCubeDomain.generated(level)) callback.cancel();
    }
    // Forge adds this named body and redirects SpawnPlacements.Type.canSpawnAt to it.
    @Inject(method = "canSpawnAtBody", remap = false, at = @At("HEAD"), cancellable = true)
    private static void mia$requireSpawnNeighborhood(SpawnPlacements.Type placement, LevelReader reader,
                                                    BlockPos pos, EntityType<?> type,
                                                    CallbackInfoReturnable<Boolean> result) {
        if (!(reader instanceof Level level) || DeepCubeDomain.lookup(level).isEmpty()) return;
        int nativeMin = DeepCubeDomain.managedTop(level);
        // Include the native seam candidate itself: its supporting block may be deep.
        if ((long) pos.getY() - 1 >= nativeMin) return;
        long minX = (long) pos.getX() - 1, maxX = (long) pos.getX() + 1;
        long minY = (long) pos.getY() - 1, maxY = (long) pos.getY() + 1;
        long minZ = (long) pos.getZ() - 1, maxZ = (long) pos.getZ() + 1;
        if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE
                || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE
                || minY < DeepCubeDomain.TECHNICAL_BOTTOM || maxY > Integer.MAX_VALUE) {
            result.setReturnValue(false);
            return;
        }
        int deepMax = (int) Math.min(maxY, (long) nativeMin - 1);
        boolean known = DeepCubeDomain.loadedRange(level, (int) minX, (int) minY, (int) minZ,
                (int) maxX, deepMax, (int) maxZ);
        if (known && maxY >= nativeMin) {
            known = DeepCubeDomain.nativeColumnsLoaded(level, (int) minX, (int) minZ, (int) maxX, (int) maxZ);
        }
        if (!known) result.setReturnValue(false);
    }
}
