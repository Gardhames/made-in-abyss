package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.terrain.habitat.HabitatBlock;
import java.util.Collection;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Exact native palette shared by cube generation, lighting and the optional habitat profile. */
public final class HabitatBlockStates {
    private static final Map<HabitatBlock,BlockState> STATES = Map.ofEntries(
            Map.entry(HabitatBlock.AIR, Blocks.AIR.defaultBlockState()),
            Map.entry(HabitatBlock.ROCK, Blocks.STONE.defaultBlockState()),
            Map.entry(HabitatBlock.SOIL, Blocks.DIRT.defaultBlockState()),
            Map.entry(HabitatBlock.GRASS, Blocks.GRASS_BLOCK.defaultBlockState()),
            Map.entry(HabitatBlock.WATER, Blocks.WATER.defaultBlockState()),
            Map.entry(HabitatBlock.LOG, Blocks.OAK_LOG.defaultBlockState()),
            Map.entry(HabitatBlock.LEAVES, Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,true)),
            Map.entry(HabitatBlock.FLOWER, Blocks.POPPY.defaultBlockState()),
            Map.entry(HabitatBlock.TALL_GRASS, Blocks.GRASS.defaultBlockState()),
            Map.entry(HabitatBlock.GRAVEL, Blocks.GRAVEL.defaultBlockState()),
            Map.entry(HabitatBlock.PLANKS, Blocks.OAK_PLANKS.defaultBlockState()),
            Map.entry(HabitatBlock.COBBLE, Blocks.COBBLESTONE.defaultBlockState()));
    private HabitatBlockStates() { }
    public static BlockState state(HabitatBlock block) { return java.util.Objects.requireNonNull(STATES.get(block)); }
    public static Collection<BlockState> palette() { return STATES.values(); }
}
