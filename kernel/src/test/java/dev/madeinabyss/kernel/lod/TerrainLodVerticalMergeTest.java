package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TerrainLodVerticalMergeTest {
    private static TerrainLodColumns.Box box(long y,long end,TerrainMaterial material) {
        return new TerrainLodColumns.Box(-2,y,-2,0,end,0,material);
    }
    // Catches uploading one box per16Y cache tile and rejecting scenes whose compacted geometry fits.
    @Test void mergesUnorderedContiguousNegativeYFragmentsBeforeFinalBudget() {
        var rock=TerrainMaterial.ROCK;
        var merged=TerrainLodVerticalMerge.merge(List.of(box(-16,0,rock),box(-48,-32,rock),box(-32,-16,rock)),1);
        assertEquals(List.of(box(-48,0,rock)),merged);
    }
    // Catches compaction filling air, smearing materials or widening narrower refinement tiles.
    @Test void occupancyMaterialsAndDistinctHorizontalBoundsRemainExact() {
        var input=List.of(box(-6,-4,TerrainMaterial.ROCK),box(-4,-2,TerrainMaterial.SOIL),box(0,2,TerrainMaterial.ROCK),
                new TerrainLodColumns.Box(-2,2,-2,-1,4,0,TerrainMaterial.ROCK),box(-2,-1,TerrainMaterial.SOIL));
        var merged=TerrainLodVerticalMerge.merge(input,4);
        assertEquals(4,merged.size());
        for(int x=-3;x<=1;x++)for(int y=-7;y<=4;y++)for(int z=-3;z<=1;z++) {
            final int px=x,py=y,pz=z;
            var before=input.stream().filter(b->contains(b,px,py,pz)).map(TerrainLodColumns.Box::material).toList();
            var after=merged.stream().filter(b->contains(b,px,py,pz)).map(TerrainLodColumns.Box::material).toList();
            assertEquals(before,after,"XYZ "+x+","+y+","+z);
        }
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->TerrainLodVerticalMerge.merge(input,3));
    }
    private static boolean contains(TerrainLodColumns.Box b,int x,int y,int z) {
        return x>=b.minX()&&x<b.maxXExclusive()&&y>=b.minY()&&y<b.maxYExclusive()&&z>=b.minZ()&&z<b.maxZExclusive();
    }
}
