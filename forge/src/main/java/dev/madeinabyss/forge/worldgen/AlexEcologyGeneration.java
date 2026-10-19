package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;
import dev.madeinabyss.kernel.cube.ExactXYZStateCache;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import dev.madeinabyss.kernel.terrain.natural.AlexEcologyOverlay;
import dev.madeinabyss.kernel.terrain.natural.AlexEcologyOverlay.Voxel;
import dev.madeinabyss.kernel.terrain.natural.NaturalProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Optional ORIGINAL communities over the immutable v11 block source. No native feature writes. */
public final class AlexEcologyGeneration {
    @FunctionalInterface public interface Source {BlockState sample(int x,int y,int z);}
    private final Source source;
    private final AlexEcologyOverlay overlay;
    private final AlexEcologyBlockStates palette;
    private final Map<BlockState,Voxel> types=new ConcurrentHashMap<>();
    // Primitive entries only: bounded per worker, no callback/source retention in ThreadLocal values.
    private final ThreadLocal<ExactXYZStateCache> baseline=ThreadLocal.withInitial(()->new ExactXYZStateCache(16384));
    public AlexEcologyGeneration(JsonObject recipe,Source source,CaveExpansionGeneration caves,NaturalProtection protection) {
        if(recipe.get("schema").getAsBigDecimal().intValueExact()!=1||!"ORIGINAL".equals(recipe.get("provenance").getAsString())
                ||!algorithmSha256().equals(recipe.get("algorithmSha256").getAsString()))throw new IllegalArgumentException("Explicit matching ORIGINAL Alex ecology recipe required");
        this.source=Objects.requireNonNull(source);Objects.requireNonNull(caves,"cave parent required");
        palette=new AlexEcologyBlockStates(recipe.get("alexAssets").getAsBoolean());
        overlay=new AlexEcologyOverlay(recipe.get("seed").getAsBigDecimal().longValueExact(),this::voxel,
                (x,y,z)->protection.contains(x,y,z)||caves.caves().routeProtectedAt(x,y,z),
                (x,y,z)->{var f=caves.interiorFeature(x,y,z);return new AlexEcologyOverlay.Feature(f.cave(),f.entranceDistance());});
    }
    public BlockState sample(int x,int y,int z,BlockState old) {
        baseline.get().put(x,y,z,Block.getId(old));
        // Native deep terrain currently supports solid/cutout models only. Leave the real
        // rock intact where the recipe proposes transparent amber, until a sorted block
        // translucent pass is implemented; never introduce invisible collision geometry.
        return overlay.sampleOverride(x,y,z,type(old))
                .filter(d->d!=AlexEcologyOverlay.Decoration.AMBER).map(palette::state).orElse(old);
    }
    public BlockState baselineBlock(int x,int y,int z) {
        var cache=baseline.get();int id=cache.get(x,y,z);if(id>=0)return Block.stateById(id);
        var state=source.sample(x,y,z);cache.put(x,y,z,Block.getId(state));return state;
    }
    private Voxel voxel(int x,int y,int z) {
        try{return type(baselineBlock(x,y,z));}catch(TerrainUnavailableException unavailable){return Voxel.UNKNOWN;}
    }
    private Voxel type(BlockState state){return types.computeIfAbsent(state,AlexEcologyGeneration::classify);}
    private static Voxel classify(BlockState state) {
        if(state.isAir())return Voxel.AIR;
        if(!state.getFluidState().isEmpty())return Voxel.WATER;
        if(state.is(Blocks.MOSS_BLOCK)||state.is(Blocks.GRASS_BLOCK))return Voxel.GRASS;
        if(state.is(BlockTags.DIRT))return Voxel.SOIL;
        var material=WildernessBlockStates.material(state);
        if(state.is(Blocks.STONE)||state.is(Blocks.CALCITE)||state.is(Blocks.DRIPSTONE_BLOCK)
                ||material==dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK)return Voxel.ROCK;
        if(!state.hasBlockEntity()&&state.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).isEmpty())return Voxel.PLANT;
        return Voxel.OBSTACLE;
    }
    public AlexEcologyOverlay overlay(){return overlay;}
    public Collection<BlockState> palette(){return palette.palette();}
    public static String algorithmSha256(){return TerrainLodDiskCache.codeIdentity(AlexEcologyOverlay.class,AlexEcologyGeneration.class,AlexEcologyBlockStates.class,dev.madeinabyss.kernel.terrain.natural.EcologyMaskFields.class);}
    public static JsonObject proposal(long seed,boolean external){var r=new JsonObject();r.addProperty("schema",1);r.addProperty("provenance","ORIGINAL");r.addProperty("seed",seed);r.addProperty("alexAssets",external);r.addProperty("algorithmSha256",algorithmSha256());return r;}
}
