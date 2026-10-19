package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.natural.NaturalBlock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Optional registered author assets. Never extracts or repackages Alex's Caves resources. */
public final class WildernessBlockStates {
    private static volatile Map<BlockState,TerrainMaterial> active=Map.of();
    private final boolean external;
    private final Map<NaturalBlock,BlockState> decorations=new EnumMap<>(NaturalBlock.class);
    private final BlockState limestone;
    public WildernessBlockStates(boolean external){
        this.external=external;
        for(var kind:NaturalBlock.values())decorations.put(kind,NaturalBlockStates.state(kind));
        if(external){
            String version=ModList.get().getModContainerById("alexscaves").map(m->m.getModInfo().getVersion().toString()).orElse("absent");
            if(!version.equals("2.0.2"))throw new IllegalArgumentException("Explicit wilderness assets need Alex's Caves 2.0.2; found "+version);
            limestone=required("limestone");
            decorations.put(NaturalBlock.OAK_LOG,Blocks.JUNGLE_LOG.defaultBlockState());
            decorations.put(NaturalBlock.OAK_LEAVES,required("ancient_leaves").setValue(LeavesBlock.PERSISTENT,true));
            decorations.put(NaturalBlock.SPRUCE_LOG,required("pewen_log"));
            decorations.put(NaturalBlock.FERN,required("pewen_pines"));
            var map=new HashMap<BlockState,TerrainMaterial>();map.put(limestone,TerrainMaterial.ROCK);
            for(var e:decorations.entrySet())map.put(e.getValue(),switch(e.getKey()){
                case OAK_LOG,BIRCH_LOG,SPRUCE_LOG->TerrainMaterial.SOIL;
                case OAK_LEAVES,BIRCH_LEAVES,SPRUCE_LEAVES,MOSS->TerrainMaterial.GRASS;
                default->TerrainMaterial.AIR;
            });
            active=Map.copyOf(map);
        }else limestone=Blocks.STONE.defaultBlockState();
    }
    private static BlockState required(String name){
        var key=new ResourceLocation("alexscaves",name);
        if(!ForgeRegistries.BLOCKS.containsKey(key))throw new IllegalArgumentException("Missing required author block "+key);
        return Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(key)).defaultBlockState();
    }
    public BlockState state(NaturalBlock kind){return decorations.get(kind);}
    public BlockState rock(int x,int y,int z){return external&&Math.floorMod(Math.floorDiv(y,13)+Math.floorDiv(x,47)+Math.floorDiv(z,53),5)<3?limestone:Blocks.STONE.defaultBlockState();}
    public Collection<BlockState> palette(){var list=new HashSet<>(decorations.values());list.add(limestone);return Set.copyOf(list);}
    public static boolean isKnown(BlockState state){return active.containsKey(state);}
    public static TerrainMaterial material(BlockState state){return active.get(state);}
}
