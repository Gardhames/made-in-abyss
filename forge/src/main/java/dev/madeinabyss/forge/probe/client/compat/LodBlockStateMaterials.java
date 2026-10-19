package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.worldgen.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.natural.NaturalBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.*;

/** One server-safe material path for both native adapters and their headless LOD audits. */
public final class LodBlockStateMaterials {
    private static final Map<BlockState,TerrainMaterial> MATERIALS=create();
    private LodBlockStateMaterials(){}
    private static Map<BlockState,TerrainMaterial> create(){
        var map=new HashMap<BlockState,TerrainMaterial>();
        map.put(Blocks.AIR.defaultBlockState(),TerrainMaterial.AIR);
        map.put(Blocks.STONE.defaultBlockState(),TerrainMaterial.ROCK);
        map.put(Blocks.DIRT.defaultBlockState(),TerrainMaterial.SOIL);
        map.put(Blocks.GRASS_BLOCK.defaultBlockState(),TerrainMaterial.GRASS);
        map.put(Blocks.WATER.defaultBlockState(),TerrainMaterial.WATER);
        map.put(Blocks.OAK_LOG.defaultBlockState(),TerrainMaterial.SOIL);
        map.put(Blocks.OAK_PLANKS.defaultBlockState(),TerrainMaterial.SOIL);
        map.put(Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,true),TerrainMaterial.GRASS);
        map.put(Blocks.POPPY.defaultBlockState(),TerrainMaterial.AIR);
        map.put(Blocks.GRASS.defaultBlockState(),TerrainMaterial.AIR);
        map.put(Blocks.DANDELION.defaultBlockState(),TerrainMaterial.AIR);
        for(var block:List.of(Blocks.GRAVEL,Blocks.COBBLESTONE,Blocks.MOSSY_COBBLESTONE,
                Blocks.ANDESITE,Blocks.TUFF,Blocks.DEEPSLATE,Blocks.SAND))map.put(block.defaultBlockState(),TerrainMaterial.ROCK);
        map.put(Blocks.COARSE_DIRT.defaultBlockState(),TerrainMaterial.SOIL);
        for(var kind:NaturalBlock.values())map.put(NaturalBlockStates.state(kind),switch(kind){
            case OAK_LOG,BIRCH_LOG,SPRUCE_LOG->TerrainMaterial.SOIL;
            case OAK_LEAVES,BIRCH_LEAVES,SPRUCE_LEAVES,MOSS->TerrainMaterial.GRASS;
            default->TerrainMaterial.AIR;
        });
        return Map.copyOf(map);
    }
    /** Nullable enumeration helper; actual sampled/visited states always use material(). */
    public static TerrainMaterial findMaterial(BlockState state){
        var material=MATERIALS.get(state);
        if(material==null)material=WildernessBlockStates.material(state);
        if(material==null)material=CaveInteriorBlockStates.material(state);
        if(material==null)material=AlexEcologyBlockStates.material(state);
        if(material==null)material=LivingAbyssBlockStates.material(state);
        return material;
    }
    /** Same mapping as material(Block.stateById(id)), memoised per registry id: LOD sampling classifies
     * hundreds of millions of voxels and the identity-hash map probe was a measurable share of it.
     * Unsupported states are never cached, so they keep throwing on every call. */
    private static volatile TerrainMaterial[] byId=new TerrainMaterial[0];
    public static TerrainMaterial materialById(int id){
        var known=byId;if(id>=0&&id<known.length){var cached=known[id];if(cached!=null)return cached;}
        var material=material(net.minecraft.world.level.block.Block.stateById(id));
        if(id>=0&&id<=1<<20)synchronized(LodBlockStateMaterials.class){
            var table=byId;if(id>=table.length)table=java.util.Arrays.copyOf(table,Math.max(id+1,Math.max(64,table.length*2)));
            table[id]=material;byId=table;
        }
        return material;
    }
    public static TerrainMaterial material(BlockState state){
        var material=findMaterial(state);
        if(material==null)throw new IllegalArgumentException("Unsupported visited/native state: "+state+"; whole preview refused");
        return material;
    }
}
