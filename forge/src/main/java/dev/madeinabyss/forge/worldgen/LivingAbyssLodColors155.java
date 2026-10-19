package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.Arrays;

/** Derived LOD presentation only. Kept outside the persisted world-generation SHA closure.
 * RGB values are measured means of the vanilla 1.20.1 block textures (see a2-texture-colors.py).
 * Existing profiles and every non-lithology owner retain their original colour function. */
public final class LivingAbyssLodColors155 {
    private LivingAbyssLodColors155(){}
    private static final Map<Block,Integer> ROCK=Map.ofEntries(
        Map.entry(Blocks.STONE,0x7e7e7e),Map.entry(Blocks.ANDESITE,0x888889),Map.entry(Blocks.CALCITE,0xdfe0dd),
        Map.entry(Blocks.WHITE_TERRACOTTA,0xd2b2a1),Map.entry(Blocks.DIORITE,0xbdbcbd),Map.entry(Blocks.TERRACOTTA,0x985e44),
        Map.entry(Blocks.YELLOW_TERRACOTTA,0xba8523),Map.entry(Blocks.TUFF,0x6c6d67),Map.entry(Blocks.DRIPSTONE_BLOCK,0x866c5d),
        Map.entry(Blocks.GRANITE,0x956756),Map.entry(Blocks.DEEPSLATE,0x505053),Map.entry(Blocks.COBBLED_DEEPSLATE,0x4d4d51),
        Map.entry(Blocks.BLACKSTONE,0x2a2429));
    /** Canonical lexical order makes equal-area ties independent of registry IDs. */
    private static final List<Block> ORDER=List.of(Blocks.ANDESITE,Blocks.BLACKSTONE,Blocks.CALCITE,Blocks.COBBLED_DEEPSLATE,
        Blocks.DEEPSLATE,Blocks.DIORITE,Blocks.DRIPSTONE_BLOCK,Blocks.GRANITE,Blocks.STONE,Blocks.TERRACOTTA,Blocks.TUFF,Blocks.WHITE_TERRACOTTA,Blocks.YELLOW_TERRACOTTA);
    private static final Map<Block,Integer> INDEX=index();
    private static Map<Block,Integer> index(){var out=new HashMap<Block,Integer>();for(int i=0;i<ORDER.size();i++)out.put(ORDER.get(i),i);return Map.copyOf(out);}
    private static final ThreadLocal<int[]> COUNTS=ThreadLocal.withInitial(()->new int[ORDER.size()]);
    public static int lod(GeneratedSkySource source,TerrainLodColumns.Box box){
        var recipe=source==null?null:source.livingRecipe();
        if(recipe!=null&&recipe.revision().startsWith("15.5")&&box.material()==TerrainMaterial.ROCK){
            if(recipe.revision().startsWith("15.5-stage-b-")){
                int dominant=dominant(source,box);if(dominant!=0)return dominant;
            }
            int x=Math.toIntExact(box.minX()+(box.maxXExclusive()-box.minX()-1)/2),
                y=Math.toIntExact(box.minY()+(box.maxYExclusive()-box.minY()-1)/2),
                z=Math.toIntExact(box.minZ()+(box.maxZExclusive()-box.minZ()-1)/2);
            if(LivingAbyssColors.inShaft(x,y,z)){
                // Exact state lookup also honours complete saved overrides. Material/opacity alone
                // cannot distinguish calcite, terracotta and the other solid ROCK lithologies.
                var rgb=ROCK.get(Block.stateById(source.stateIdFast(x,y,z)).getBlock());
                if(rgb!=null)return 0xff000000|rgb;
            }
        }
        return LivingAbyssColors.lod(source,box);
    }
    /** One vote per square metre of the clipped horizontal column footprint.
     * Saved whole cubes use the same authoritative state API as the old point colour.
     * An unavailable/non-lithology footprint retains that original fallback. */
    private static int dominant(GeneratedSkySource source,TerrainLodColumns.Box box){
        long sx=box.maxXExclusive()-box.minX(),sz=box.maxZExclusive()-box.minZ();if(sx>64||sz>64)return 0;
        int y=Math.toIntExact(box.minY()+(box.maxYExclusive()-box.minY()-1)/2);
        var counts=COUNTS.get();Arrays.fill(counts,0);int best=-1,maximum=0;
        for(long x=box.minX();x<box.maxXExclusive();x++)for(long z=box.minZ();z<box.maxZExclusive();z++){
            int xx=Math.toIntExact(x),zz=Math.toIntExact(z);if(y<source.knownLowerY(xx,zz))return 0;
            var block=Block.stateById(source.stateIdFast(xx,y,zz)).getBlock();if(block==Blocks.AIR||block==Blocks.CAVE_AIR)continue;
            var index=INDEX.get(block);if(index==null)return 0;counts[index]++;
        }
        for(int i=0;i<counts.length;i++)if(counts[i]>maximum){maximum=counts[i];best=i;}
        return best<0?0:0xff000000|ROCK.get(ORDER.get(best));
    }
}
