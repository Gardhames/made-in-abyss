package dev.madeinabyss.forge.probe.deep.mixin;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.madeinabyss.forge.probe.client.ClientGeneratedLight;
import dev.madeinabyss.forge.worldgen.LivingAbyssColors;

/** Native column tint above the deep-cube window, including the first 64m of shaft. */
@Mixin(ClientLevel.class)
public abstract class LivingAbyssNativeTintMixin {
    @Inject(method="getBlockTint",at=@At("HEAD"),cancellable=true)
    private void mia$v15NativeTint(BlockPos pos,ColorResolver resolver,CallbackInfoReturnable<Integer> callback){
        var source=ClientGeneratedLight.source();var recipe=source==null?null:source.livingRecipe();
        if(recipe==null||recipe.biomeColors()==null||!LivingAbyssColors.inShaft(pos.getX(),pos.getY(),pos.getZ()))return;
        var level=(ClientLevel)(Object)this;var original=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
            .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value();
        callback.setReturnValue(resolver.getColor(LivingAbyssColors.biome(original,recipe.biomeColors()),pos.getX(),pos.getZ()));
    }
}
