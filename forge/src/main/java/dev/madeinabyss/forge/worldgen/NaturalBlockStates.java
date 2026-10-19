package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.terrain.natural.NaturalBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Native placeholder species; named vine direction is its actual neighboring support. */
public final class NaturalBlockStates {
    private NaturalBlockStates() {}
    public static BlockState state(NaturalBlock block) {
        return switch(block) {
            case OAK_LOG -> Blocks.OAK_LOG.defaultBlockState();
            case BIRCH_LOG -> Blocks.BIRCH_LOG.defaultBlockState();
            case SPRUCE_LOG -> Blocks.SPRUCE_LOG.defaultBlockState();
            case OAK_LEAVES -> Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
            case BIRCH_LEAVES -> Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
            case SPRUCE_LEAVES -> Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
            case GRASS_PLANT -> Blocks.GRASS.defaultBlockState();
            case FERN -> Blocks.FERN.defaultBlockState();
            case FLOWER_YELLOW -> Blocks.DANDELION.defaultBlockState();
            case FLOWER_WHITE -> Blocks.OXEYE_DAISY.defaultBlockState();
            case MOSS -> Blocks.MOSS_BLOCK.defaultBlockState();
            case VINE_NORTH -> Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH,true);
            case VINE_SOUTH -> Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH,true);
            case VINE_EAST -> Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST,true);
            case VINE_WEST -> Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST,true);
        };
    }
}
