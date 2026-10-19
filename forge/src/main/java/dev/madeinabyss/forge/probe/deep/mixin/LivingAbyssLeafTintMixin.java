package dev.madeinabyss.forge.probe.deep.mixin;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.madeinabyss.forge.probe.client.ClientGeneratedLight;
import dev.madeinabyss.forge.worldgen.LivingAbyssColors;

/** Vanilla birch's fixed tint bypasses the biome callback; v15 needs the approved foliage RGB. */
@Mixin(BlockColors.class)
public abstract class LivingAbyssLeafTintMixin {
    @Inject(method="getColor(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;I)I",at=@At("HEAD"),cancellable=true)
    private void mia$v15LeafTint(BlockState state,BlockAndTintGetter view,BlockPos pos,int index,CallbackInfoReturnable<Integer> callback){
        if(pos==null||!(state.getBlock() instanceof LeavesBlock)||!LivingAbyssColors.inShaft(pos.getX(),pos.getY(),pos.getZ()))return;
        var source=ClientGeneratedLight.source();var recipe=source==null?null:source.livingRecipe();
        if(recipe!=null&&recipe.biomeColors()!=null)callback.setReturnValue(recipe.biomeColors().foliageRgb());
    }
}
