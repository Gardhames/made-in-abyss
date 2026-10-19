package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.living.LivingAbyssFeatureBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.*;

/** v15 native-to-LOD admission only; independent of the generator and persisted recipe identity.
 * The composed parent palettes retain their existing Wilderness/CaveInterior/Alex admission. */
public final class LivingAbyssBlockStates {
    private static final Map<BlockState,TerrainMaterial> STATES=create();
    private static final BlockState[] FEATURES=features();
    private LivingAbyssBlockStates(){}
    private static Map<BlockState,TerrainMaterial> create(){
        var states=new HashMap<BlockState,TerrainMaterial>();
        put(states,TerrainMaterial.ROCK,Blocks.STONE,Blocks.ANDESITE,Blocks.CALCITE,
                Blocks.DRIPSTONE_BLOCK,Blocks.WHITE_TERRACOTTA,Blocks.TUFF,
                Blocks.MOSSY_COBBLESTONE,Blocks.DEEPSLATE,Blocks.STONE_BRICKS,Blocks.COBBLESTONE,Blocks.GRAVEL,
                Blocks.MOSSY_STONE_BRICKS,Blocks.CRACKED_STONE_BRICKS,Blocks.CHISELED_STONE_BRICKS,
                Blocks.BROWN_MUSHROOM_BLOCK,Blocks.RED_MUSHROOM_BLOCK,Blocks.MUSHROOM_STEM,Blocks.SAND,Blocks.CLAY);
        put(states,TerrainMaterial.ROCK,Blocks.DIORITE,Blocks.GRANITE,Blocks.TERRACOTTA,Blocks.YELLOW_TERRACOTTA,Blocks.COBBLED_DEEPSLATE,Blocks.BLACKSTONE);
        put(states,TerrainMaterial.SOIL,Blocks.MUD);
        put(states,TerrainMaterial.SOIL,Blocks.DIRT,Blocks.ROOTED_DIRT,Blocks.COARSE_DIRT,Blocks.OAK_LOG,Blocks.BIRCH_LOG,Blocks.DARK_OAK_LOG,Blocks.MANGROVE_ROOTS);
        put(states,TerrainMaterial.GRASS,Blocks.GRASS_BLOCK,Blocks.MOSS_BLOCK,Blocks.MOSS_CARPET);
        for(var leaves:List.of(Blocks.OAK_LEAVES,Blocks.BIRCH_LEAVES,Blocks.DARK_OAK_LEAVES,Blocks.AZALEA_LEAVES,Blocks.FLOWERING_AZALEA_LEAVES))for(int distance=1;distance<=7;distance++)
            states.put(leaves.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,true).setValue(BlockStateProperties.DISTANCE,distance),TerrainMaterial.GRASS);
        put(states,TerrainMaterial.AIR,Blocks.AIR);
        put(states,TerrainMaterial.AIR,Blocks.GRASS,Blocks.FERN,Blocks.HANGING_ROOTS,Blocks.OXEYE_DAISY,Blocks.AZURE_BLUET,
                Blocks.LILY_OF_THE_VALLEY,Blocks.CORNFLOWER,Blocks.BLUE_ORCHID,Blocks.DANDELION);
        for(var b:List.of(Blocks.TALL_GRASS,Blocks.LARGE_FERN))for(var half:net.minecraft.world.level.block.state.properties.DoubleBlockHalf.values())
            states.put(b.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,half),TerrainMaterial.AIR);
        for(var thickness:List.of(net.minecraft.world.level.block.state.properties.DripstoneThickness.BASE,net.minecraft.world.level.block.state.properties.DripstoneThickness.MIDDLE,net.minecraft.world.level.block.state.properties.DripstoneThickness.FRUSTUM,net.minecraft.world.level.block.state.properties.DripstoneThickness.TIP))
            states.put(Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(BlockStateProperties.VERTICAL_DIRECTION,net.minecraft.core.Direction.DOWN).setValue(BlockStateProperties.DRIPSTONE_THICKNESS,thickness),TerrainMaterial.AIR);
        for(var thickness:List.of(net.minecraft.world.level.block.state.properties.DripstoneThickness.BASE,net.minecraft.world.level.block.state.properties.DripstoneThickness.MIDDLE,net.minecraft.world.level.block.state.properties.DripstoneThickness.FRUSTUM,net.minecraft.world.level.block.state.properties.DripstoneThickness.TIP))
            states.put(Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(BlockStateProperties.VERTICAL_DIRECTION,net.minecraft.core.Direction.UP).setValue(BlockStateProperties.DRIPSTONE_THICKNESS,thickness),TerrainMaterial.AIR);
        states.put(Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(net.minecraft.core.Direction.UP),true),TerrainMaterial.AIR);
        put(states,TerrainMaterial.AIR,Blocks.CAVE_VINES,Blocks.CAVE_VINES_PLANT); // section24 mouth curtains, berries=false
        for(var block:List.of(Blocks.WATER,Blocks.SEAGRASS,Blocks.TALL_SEAGRASS,Blocks.KELP,Blocks.KELP_PLANT))
            for(var state:block.getStateDefinition().getPossibleStates())states.put(state,TerrainMaterial.WATER);
        for(var block:List.of(Blocks.LILY_PAD,Blocks.SUGAR_CANE))for(var state:block.getStateDefinition().getPossibleStates())states.put(state,TerrainMaterial.AIR);
        for(var block:List.of(Blocks.OAK_LOG,Blocks.DARK_OAK_LOG,Blocks.BIRCH_LOG))for(var state:block.getStateDefinition().getPossibleStates())states.put(state,TerrainMaterial.SOIL);
        // All five face flags, including UP and combinations absent from a finite sampler run.
        for(var vine:Blocks.VINE.getStateDefinition().getPossibleStates())states.put(vine,TerrainMaterial.GRASS);
        // These four states already belong to NaturalBlock's omitted plant palette.
        // Preserve their native semantics; the other v15 drape combinations use green boxes.
        for(var face:List.of(VineBlock.NORTH,VineBlock.SOUTH,VineBlock.EAST,VineBlock.WEST))
            states.put(Blocks.VINE.defaultBlockState().setValue(face,true),TerrainMaterial.AIR);
        return Map.copyOf(states);
    }
    private static void put(Map<BlockState,TerrainMaterial> states,TerrainMaterial material,Block... blocks){
        for(var block:blocks)states.put(block.defaultBlockState(),material);
    }
    public static Collection<BlockState> palette(){return STATES.keySet();}
    public static boolean isKnown(BlockState state){return STATES.containsKey(state);}
    public static TerrainMaterial material(BlockState state){return STATES.get(state);}
    private static BlockState[] features(){
        var states=new BlockState[256];
        for(var b:LivingAbyssFeatureBlock.values()){
            Block block=switch(b){
                case NONE->null;case OAK_LOG->Blocks.OAK_LOG;case OAK_LEAVES->Blocks.OAK_LEAVES;case DARK_OAK_LOG->Blocks.DARK_OAK_LOG;
                case DARK_OAK_LEAVES,INNER_LEAVES->Blocks.DARK_OAK_LEAVES;case MOSS->Blocks.MOSS_BLOCK;case MANGROVE_ROOTS->Blocks.MANGROVE_ROOTS;
                case HANGING_ROOTS->Blocks.HANGING_ROOTS;case BIRCH_LOG->Blocks.BIRCH_LOG;case BIRCH_LEAVES->Blocks.BIRCH_LEAVES;case GLOW_UP->Blocks.GLOW_LICHEN;
                case GRASS->Blocks.GRASS_BLOCK;case DIRT->Blocks.DIRT;case ROOTED_DIRT->Blocks.ROOTED_DIRT;case COARSE_DIRT->Blocks.COARSE_DIRT;case MOSS_CARPET->Blocks.MOSS_CARPET;
                case STONE->Blocks.STONE;case ANDESITE->Blocks.ANDESITE;case CALCITE->Blocks.CALCITE;case MOSSY_COBBLESTONE->Blocks.MOSSY_COBBLESTONE;case COBBLESTONE->Blocks.COBBLESTONE;case GRAVEL->Blocks.GRAVEL;
                case STONE_BRICKS->Blocks.STONE_BRICKS;case MOSSY_STONE_BRICKS->Blocks.MOSSY_STONE_BRICKS;case CRACKED_STONE_BRICKS->Blocks.CRACKED_STONE_BRICKS;case CHISELED_STONE_BRICKS->Blocks.CHISELED_STONE_BRICKS;
                case SHORT_GRASS->Blocks.GRASS;case TALL_GRASS_LOWER,TALL_GRASS_UPPER->Blocks.TALL_GRASS;case FERN->Blocks.FERN;case LARGE_FERN_LOWER,LARGE_FERN_UPPER->Blocks.LARGE_FERN;
                case OXEYE_DAISY->Blocks.OXEYE_DAISY;case AZURE_BLUET->Blocks.AZURE_BLUET;case LILY_OF_THE_VALLEY->Blocks.LILY_OF_THE_VALLEY;case CORNFLOWER->Blocks.CORNFLOWER;case BLUE_ORCHID->Blocks.BLUE_ORCHID;case DANDELION->Blocks.DANDELION;
                case AZALEA_LEAVES->Blocks.AZALEA_LEAVES;case FLOWERING_AZALEA_LEAVES->Blocks.FLOWERING_AZALEA_LEAVES;
                case BROWN_MUSHROOM_BLOCK->Blocks.BROWN_MUSHROOM_BLOCK;case RED_MUSHROOM_BLOCK->Blocks.RED_MUSHROOM_BLOCK;case MUSHROOM_STEM->Blocks.MUSHROOM_STEM;
                case DRIP_BASE,DRIP_MIDDLE,DRIP_TIP,DRIP_FRUSTUM->Blocks.POINTED_DRIPSTONE;case AIR_CUT->Blocks.AIR;
                case WATER_SOURCE,WATER_FALLING->Blocks.WATER;case SAND->Blocks.SAND;case CLAY->Blocks.CLAY;case MUD->Blocks.MUD;
                case SEAGRASS->Blocks.SEAGRASS;case TALL_SEAGRASS_LOWER,TALL_SEAGRASS_UPPER->Blocks.TALL_SEAGRASS;
                case KELP->Blocks.KELP;case KELP_PLANT->Blocks.KELP_PLANT;case LILY_PAD->Blocks.LILY_PAD;case SUGAR_CANE->Blocks.SUGAR_CANE;
                case TUFF->Blocks.TUFF;case DRIPSTONE->Blocks.DRIPSTONE_BLOCK;case WHITE_TERRACOTTA->Blocks.WHITE_TERRACOTTA;
                case OAK_LOG_X,OAK_LOG_Z->Blocks.OAK_LOG;
                case CAVE_VINES->Blocks.CAVE_VINES;case CAVE_VINES_PLANT->Blocks.CAVE_VINES_PLANT;
            };
            if(block==null)continue;var state=block.defaultBlockState();
            if(block instanceof LeavesBlock)state=state.setValue(BlockStateProperties.PERSISTENT,true);
            if(b==LivingAbyssFeatureBlock.GLOW_UP)state=state.setValue(MultifaceBlock.getFaceProperty(net.minecraft.core.Direction.UP),true);
            if(b==LivingAbyssFeatureBlock.WATER_FALLING)state=state.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL,8);
            if(b==LivingAbyssFeatureBlock.TALL_SEAGRASS_UPPER)state=state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
            if(b==LivingAbyssFeatureBlock.KELP)state=state.setValue(BlockStateProperties.AGE_25,25);
            if(b==LivingAbyssFeatureBlock.OAK_LOG_X||b==LivingAbyssFeatureBlock.OAK_LOG_Z)state=state.setValue(BlockStateProperties.AXIS,b==LivingAbyssFeatureBlock.OAK_LOG_X?net.minecraft.core.Direction.Axis.X:net.minecraft.core.Direction.Axis.Z);
            if(b==LivingAbyssFeatureBlock.TALL_GRASS_UPPER||b==LivingAbyssFeatureBlock.LARGE_FERN_UPPER)state=state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
            if(block==Blocks.POINTED_DRIPSTONE)state=state.setValue(BlockStateProperties.VERTICAL_DIRECTION,net.minecraft.core.Direction.DOWN).setValue(BlockStateProperties.DRIPSTONE_THICKNESS,
                b==LivingAbyssFeatureBlock.DRIP_BASE?net.minecraft.world.level.block.state.properties.DripstoneThickness.BASE:b==LivingAbyssFeatureBlock.DRIP_MIDDLE?net.minecraft.world.level.block.state.properties.DripstoneThickness.MIDDLE:b==LivingAbyssFeatureBlock.DRIP_FRUSTUM?net.minecraft.world.level.block.state.properties.DripstoneThickness.FRUSTUM:net.minecraft.world.level.block.state.properties.DripstoneThickness.TIP);
            if(!isKnown(state))throw new IllegalStateException("Missing explicit v15 feature state "+state);states[b.ordinal()]=state;
        }
        for(int mask=1;mask<32;mask++)states[128+mask]=Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH,(mask&1)!=0).setValue(VineBlock.SOUTH,(mask&2)!=0).setValue(VineBlock.WEST,(mask&4)!=0).setValue(VineBlock.EAST,(mask&8)!=0).setValue(VineBlock.UP,(mask&16)!=0);
        return states;
    }
    public static BlockState feature(int code){if(code<0||code>=FEATURES.length||code!=0&&FEATURES[code]==null)throw new IllegalArgumentException("Unknown v15 stamp code "+code);return FEATURES[code];}
}
