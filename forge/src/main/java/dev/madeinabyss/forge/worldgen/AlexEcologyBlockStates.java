package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.natural.AlexEcologyOverlay.Decoration;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Registered, unchanged author assets; independent of the historical wilderness palette. */
public final class AlexEcologyBlockStates {
    private static volatile Map<BlockState,TerrainMaterial> active=Map.of();
    private final Map<Decoration,BlockState> states=new EnumMap<>(Decoration.class);

    public AlexEcologyBlockStates(boolean external) {
        if(external) {
            String version=ModList.get().getModContainerById("alexscaves")
                    .map(m->m.getModInfo().getVersion().toString()).orElse("absent");
            if(!version.equals("2.0.2")) throw new IllegalArgumentException("Ecology assets require Alex's Caves 2.0.2; found "+version);
        }
        states.put(Decoration.MOSS,Blocks.MOSS_BLOCK.defaultBlockState());
        states.put(Decoration.MUD,Blocks.PACKED_MUD.defaultBlockState());
        states.put(Decoration.LIMESTONE,external?required("limestone"):Blocks.CALCITE.defaultBlockState());
        states.put(Decoration.GALENA,external?required("galena"):Blocks.DEEPSLATE.defaultBlockState());
        states.put(Decoration.COPROLITH,external?required("coprolith"):Blocks.TUFF.defaultBlockState());
        states.put(Decoration.AMBER,external?required("amber"):Blocks.ORANGE_STAINED_GLASS.defaultBlockState());
        states.put(Decoration.PEWEN_PINES,external?required("pewen_pines"):Blocks.FERN.defaultBlockState());
        states.put(Decoration.UNDERWEED,external?required("underweed"):Blocks.MOSS_CARPET.defaultBlockState());
        BlockState curly=external?required("curly_fern").setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH):Blocks.LARGE_FERN.defaultBlockState();
        states.put(Decoration.CURLY_LOWER,curly.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.LOWER));
        states.put(Decoration.CURLY_UPPER,curly.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER));
        BlockState cycad=external?required("cycad"):Blocks.AZALEA.defaultBlockState();
        if(external) {
            var top=cycad.getBlock().getStateDefinition().getProperty("top");
            if(!(top instanceof BooleanProperty flag)) throw new IllegalArgumentException("Missing cycad boolean top property");
            cycad=cycad.setValue(flag,true);
        }
        states.put(Decoration.CYCAD_TOP,cycad);
        BlockState pewen=external?required("pewen_log"):Blocks.SPRUCE_LOG.defaultBlockState();
        BlockState thorn=external?required("thornwood_log"):Blocks.DARK_OAK_LOG.defaultBlockState();
        states.put(Decoration.PEWEN_LOG_X,pewen.setValue(RotatedPillarBlock.AXIS,Direction.Axis.X));
        states.put(Decoration.PEWEN_LOG_Z,pewen.setValue(RotatedPillarBlock.AXIS,Direction.Axis.Z));
        states.put(Decoration.THORNWOOD_LOG_X,thorn.setValue(RotatedPillarBlock.AXIS,Direction.Axis.X));
        states.put(Decoration.THORNWOOD_LOG_Z,thorn.setValue(RotatedPillarBlock.AXIS,Direction.Axis.Z));
        states.put(Decoration.VINE_BODY,external?required("archaic_vine_plant"):Blocks.WEEPING_VINES_PLANT.defaultBlockState());
        states.put(Decoration.VINE_HEAD,(external?required("archaic_vine"):Blocks.WEEPING_VINES.defaultBlockState()).setValue(BlockStateProperties.AGE_25,25));
        var mapping=new HashMap<BlockState,TerrainMaterial>();
        for(var entry:states.entrySet()) {
            if(entry.getValue().hasBlockEntity()) throw new IllegalArgumentException("Ecology palette refuses unmanaged block entity "+entry.getValue());
            mapping.put(entry.getValue(),switch(entry.getKey()) {
                case MOSS -> TerrainMaterial.GRASS;
                case MUD,PEWEN_LOG_X,PEWEN_LOG_Z,THORNWOOD_LOG_X,THORNWOOD_LOG_Z -> TerrainMaterial.SOIL;
                case LIMESTONE,GALENA,COPROLITH,AMBER -> TerrainMaterial.ROCK;
                default -> TerrainMaterial.AIR;
            });
        }
        // Monotonic union: loading fallback/another profile cannot invalidate live author states.
        synchronized(AlexEcologyBlockStates.class) {
            var union=new HashMap<>(active);
            for(var entry:mapping.entrySet()) {
                var previous=union.putIfAbsent(entry.getKey(),entry.getValue());
                if(previous!=null&&previous!=entry.getValue()) throw new IllegalArgumentException("Conflicting ecology material "+entry.getKey());
            }
            active=Map.copyOf(union);
        }
    }
    private static BlockState required(String name) {
        var key=new ResourceLocation("alexscaves",name);
        if(!ForgeRegistries.BLOCKS.containsKey(key)) throw new IllegalArgumentException("Missing required author block "+key);
        return Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(key)).defaultBlockState();
    }
    public BlockState state(Decoration decoration) { return Objects.requireNonNull(states.get(decoration),"Unmapped ecology decoration "+decoration); }
    public Collection<BlockState> palette() { return Set.copyOf(states.values()); }
    public static boolean isKnown(BlockState state) { return active.containsKey(state); }
    public static TerrainMaterial material(BlockState state) { return active.get(state); }
}
