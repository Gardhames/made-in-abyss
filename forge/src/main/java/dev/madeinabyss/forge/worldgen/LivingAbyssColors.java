package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.terrain.living.LivingAbyssRecipe;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Explicit per-profile shaft biome effects shared by native tint and both DH paths. */
public final class LivingAbyssColors {
    private LivingAbyssColors(){}
    private static final Map<Biome,Map<LivingAbyssRecipe.BiomeColors,Biome>> CACHE=Collections.synchronizedMap(new WeakHashMap<>());
    public static boolean inShaft(int x,int y,int z){return y<0&&y>=-1424&&(double)x*x+(double)z*z<960.0*960;}
    public static Biome biome(Biome original,LivingAbyssRecipe.BiomeColors colors){
        if(colors==null)return original;
        synchronized(CACHE){return CACHE.computeIfAbsent(original,k->new HashMap<>()).computeIfAbsent(colors,k->new Biome.BiomeBuilder()
            .hasPrecipitation(original.hasPrecipitation()).temperature(original.getBaseTemperature()).downfall(original.getModifiedClimateSettings().downfall())
            .specialEffects(new BiomeSpecialEffects.Builder().fogColor(original.getFogColor()).skyColor(original.getSkyColor())
                .waterFogColor(original.getWaterFogColor()).waterColor(colors.waterRgb()).grassColorOverride(colors.grassRgb()).foliageColorOverride(colors.foliageRgb()).build())
            .mobSpawnSettings(original.getMobSettings()).generationSettings(original.getGenerationSettings()).build());}
    }
    public static int legacyLod(TerrainLodColumns.Box box){return switch(box.material()){
        case ROCK->0xff7e7b77;case SOIL->0xff745337;case GRASS->0xff688a41;case WATER->0xb4355ea3;case AIR->throw new IllegalArgumentException("Air has no color");};}
    public static int lod(GeneratedSkySource source,TerrainLodColumns.Box box){
        var recipe=source==null?null:source.livingRecipe();int x=Math.toIntExact(box.minX()+(box.maxXExclusive()-box.minX()-1)/2),
            y=Math.toIntExact(box.minY()+(box.maxYExclusive()-box.minY()-1)/2),z=Math.toIntExact(box.minZ()+(box.maxZExclusive()-box.minZ()-1)/2);
        if(recipe==null||recipe.biomeColors()==null||!inShaft(x,y,z))return legacyLod(box);
        var c=recipe.biomeColors();
        return switch(box.material()){
            case WATER -> 0xb4000000|c.waterRgb();
            case GRASS -> {var state=Block.stateById(source.stateIdForDerivedGeometry(x,y,z));
                boolean leaves=state.getBlock() instanceof LeavesBlock||state.is(Blocks.VINE);
                yield 0xff000000|(recipe.part3a()?(leaves?0x43702b:0x4e7d33):(leaves?c.foliageRgb():c.grassRgb()));}
            case SOIL -> {var state=Block.stateById(source.stateIdForDerivedGeometry(x,y,z));
                boolean trunk=state.is(net.minecraft.tags.BlockTags.LOGS)||state.is(Blocks.MANGROVE_ROOTS);
                yield recipe.part3b()?0xff000000|(trunk?0x4a3b2c:0x5b4b3c):legacyLod(box);}
            default -> legacyLod(box);
        };
    }
}
