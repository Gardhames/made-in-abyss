package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.cube.DeepCreativeEdits;
import dev.madeinabyss.kernel.terrain.natural.NaturalBlock;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.stream.Collectors;

/** Native model admission only; independent of the persisted island algorithm/geometry identity. */
public final class IslandSurfaceBlockStates {
    private static final Set<BlockState> ISLAND=Set.of(
            Blocks.AIR.defaultBlockState(),Blocks.WATER.defaultBlockState(),Blocks.SAND.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),Blocks.COARSE_DIRT.defaultBlockState(),Blocks.GRASS_BLOCK.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(),Blocks.DEEPSLATE.defaultBlockState(),Blocks.ANDESITE.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(),Blocks.STONE.defaultBlockState(),Blocks.GRASS.defaultBlockState(),
            Blocks.FERN.defaultBlockState(),Blocks.OAK_LOG.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true));
    private static final Set<BlockState> NATURAL=Arrays.stream(NaturalBlock.values())
            .map(NaturalBlockStates::state).collect(Collectors.toUnmodifiableSet());
    private IslandSurfaceBlockStates(){}
    public static Set<BlockState> palette(){return ISLAND;}
    /** Both mesh paths use this same predicate; caller retains fluid/render-shape/BE guards. */
    public static boolean admitsNative(BlockState state,boolean generated){
        if(state==null)return false;
        if(DeepCreativeEdits.inert(state)||state.is(Blocks.DIRT)||state.is(Blocks.GRASS_BLOCK))return true;
        return generated&&(ISLAND.contains(state)||HabitatBlockStates.palette().contains(state)||NATURAL.contains(state)
                ||WildernessBlockStates.isKnown(state)||CaveInteriorBlockStates.isKnown(state)||AlexEcologyBlockStates.isKnown(state)||LivingAbyssBlockStates.isKnown(state));
    }
    /** Waterlogged plants own both a block model and native liquid quads. */
    public static boolean admitsNativeModel(BlockState state,boolean generated){return admitsNative(state,generated)&&!state.hasBlockEntity()&&state.getRenderShape()==RenderShape.MODEL;}
}
