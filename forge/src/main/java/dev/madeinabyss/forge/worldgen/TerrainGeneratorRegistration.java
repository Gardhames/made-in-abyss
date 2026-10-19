package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;

/** Codec registration only: no default world preset, no existing-world substitution. */
@Mod.EventBusSubscriber(modid = MadeInAbyssProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class TerrainGeneratorRegistration {
    public static final ResourceLocation ID = new ResourceLocation(MadeInAbyssProbe.MOD_ID, "parameterized_first_layer");
    public static final ResourceLocation COMPOSED_ID = new ResourceLocation(MadeInAbyssProbe.MOD_ID, "composed_first_layer");
    private TerrainGeneratorRegistration() { }
    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(Registries.CHUNK_GENERATOR, ID, () -> ParameterizedTerrainChunkGenerator.CODEC);
        event.register(Registries.CHUNK_GENERATOR, COMPOSED_ID, () -> ComposedTerrainChunkGenerator.CODEC);
    }
}
