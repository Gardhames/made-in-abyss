package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.model.data.ModelDataManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Forge's native column model-data cache owns its queued native block entities, including particle texture queries. */
@Mixin(value=ModelDataManager.class,remap=false)
public abstract class GeneratedNativeModelDataMixin {
    @Shadow @Final private Level level;
    @Shadow private void refreshAt(ChunkPos position) {throw new AssertionError("Mixin shadow");}

    @Redirect(method="getAt(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/Map;",at=@At(value="INVOKE",
            target="Lnet/minecraftforge/client/model/data/ModelDataManager;refreshAt(Lnet/minecraft/world/level/ChunkPos;)V"))
    private void mia$nativeColumnModelData(ModelDataManager manager,ChunkPos position) {
        if(DeepCubeDomain.generated(level))DeepCubeDomain.readNativeHost(level,()->refreshAt(position));
        else refreshAt(position);
    }
}
