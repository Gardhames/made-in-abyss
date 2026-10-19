package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.natural.CaveInteriorOverlay.Decoration;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import java.util.*;

/** Stateless native cave palette; models and collision remain the registered vanilla assets. */
public final class CaveInteriorBlockStates {
    private static final Map<Decoration,BlockState> STATES=create();
    private static Map<Decoration,BlockState> create(){
        var m=new EnumMap<Decoration,BlockState>(Decoration.class);
        m.put(Decoration.CALCITE,Blocks.CALCITE.defaultBlockState());
        m.put(Decoration.DRIPSTONE_BLOCK,Blocks.DRIPSTONE_BLOCK.defaultBlockState());
        m.put(Decoration.MOSS,Blocks.MOSS_BLOCK.defaultBlockState());
        m.put(Decoration.FERN,Blocks.FERN.defaultBlockState());
        m.put(Decoration.WATER,Blocks.WATER.defaultBlockState());
        m.put(Decoration.DRIPSTONE_UP,Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(PointedDripstoneBlock.TIP_DIRECTION,Direction.UP).setValue(PointedDripstoneBlock.THICKNESS,DripstoneThickness.TIP));
        m.put(Decoration.DRIPSTONE_DOWN,Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(PointedDripstoneBlock.TIP_DIRECTION,Direction.DOWN).setValue(PointedDripstoneBlock.THICKNESS,DripstoneThickness.TIP));
        m.put(Decoration.LICHEN_NORTH,Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.NORTH),true));
        m.put(Decoration.LICHEN_SOUTH,Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.SOUTH),true));
        m.put(Decoration.LICHEN_EAST,Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.EAST),true));
        m.put(Decoration.LICHEN_WEST,Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.WEST),true));
        return Map.copyOf(m);
    }
    public static BlockState state(Decoration kind){return Objects.requireNonNull(STATES.get(kind));}
    public static Collection<BlockState> palette(){return STATES.values();}
    public static boolean isKnown(BlockState state){return STATES.containsValue(state);}
    public static TerrainMaterial material(BlockState state){
        if(!isKnown(state))return null;
        if(state.is(Blocks.WATER))return TerrainMaterial.WATER;
        if(state.is(Blocks.CALCITE)||state.is(Blocks.DRIPSTONE_BLOCK))return TerrainMaterial.ROCK;
        if(state.is(Blocks.MOSS_BLOCK))return TerrainMaterial.GRASS;
        return TerrainMaterial.AIR;
    }
}
