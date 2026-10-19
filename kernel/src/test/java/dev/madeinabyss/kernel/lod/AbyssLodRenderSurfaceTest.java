package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AbyssLodRenderSurfaceTest {
    private static TerrainLodColumns.Box box(long x,long y,long z,long ex,long ey,long ez){return new TerrainLodColumns.Box(x,y,z,ex,ey,ez,TerrainMaterial.ROCK);}
    private static List<TerrainLodColumns.Box> wall(int width){
        var out=new ArrayList<TerrainLodColumns.Box>();
        // Alternating opaque materials are real vertical runs; homogeneous columns would merge.
        for(int x=0;x<width;x++)for(int z=0;z<width;z++)for(int y=0;y<width;y++)out.add(new TerrainLodColumns.Box(x-18,y-35,z+9,x-17,y-34,z+10,(y&1)==0?TerrainMaterial.ROCK:TerrainMaterial.SOIL));
        return List.copyOf(out);
    }
    @Test void negativeFineWallKeepsExactExteriorAndRemovesAtLeastHalfTheOwners(){
        var input=wall(12);var raw=new TerrainLodColumns.Result(input,1728);var before=List.copyOf(raw.boxes());
        var generated=TerrainLodColumns.build((x,y,z)->new dev.madeinabyss.kernel.terrain.TerrainSample.Known(((y+35)&1)==0?TerrainMaterial.ROCK:TerrainMaterial.SOIL),
            new TerrainLodColumns.Bounds(-18,-35,9,-7,-24,20),new TerrainLodColumns.Parameters(1,1728,1728));
        assertEquals(generated,raw,"Fixture is an actual RAW column build, not an unmerged homogeneous cube-volume surrogate");
        var result=AbyssLodRenderSurface.cull(raw.boxes(),100_000);
        assertEquals(1728,result.opaqueInput());assertEquals(1000,result.removed(),"All10³ interior owners have six fully covered faces");
        assertEquals(728,result.kept().size());assertTrue(result.kept().size()*2<input.size(),"Representative solid wall must free at least2x BOX/light budget");
        assertFalse(result.budgetRefused());assertEquals(before,raw.boxes(),"RAW/state/fine provenance remains unchanged; culling is presentation only");
        for(int i=0;i<result.kept().size();i++){assertSame(input.get(result.keptIndices().get(i)),result.kept().get(i));if(i>0)assertTrue(result.keptIndices().get(i)>result.keptIndices().get(i-1));}
        assertThrows(UnsupportedOperationException.class,()->result.kept().clear());
    }
    @Test void waterNeverOccludesAndNativeHoleRestoresPreviouslyBuriedOwner(){
        var input=new ArrayList<>(wall(3));var center=input.get(13);input.set(13,new TerrainLodColumns.Box(center.minX(),center.minY(),center.minZ(),center.maxXExclusive(),center.maxYExclusive(),center.maxZExclusive(),TerrainMaterial.WATER));
        var water=AbyssLodRenderSurface.cull(input,10_000);assertEquals(1,water.keptWater());assertEquals(0,water.removed());assertEquals(input,water.kept());
        input=new ArrayList<>(wall(3));center=input.get(13);input.remove(22); // +X whole face absent: exact committed native cut or missing tile.
        var opened=AbyssLodRenderSurface.cull(input,10_000);assertTrue(opened.kept().contains(center),"Absent/UNKNOWN geometry must not count as opaque face coverage");
        assertEquals(0,opened.removed());assertEquals(input,opened.kept());
    }
    @Test void fullSplitFaceCoverageIsRequiredAndOneMissingQuarterKeepsTheOwner(){
        var owner=box(-16,-16,-16,0,0,0);var input=new ArrayList<TerrainLodColumns.Box>();input.add(owner);
        input.add(box(-32,-16,-16,-16,0,0));input.add(box(-16,-32,-16,0,-16,0));input.add(box(-16,0,-16,0,16,0));
        input.add(box(-16,-16,-32,0,0,-16));input.add(box(-16,-16,0,0,0,16));
        for(int y=-16;y<0;y+=8)for(int z=-16;z<0;z+=8)input.add(box(0,y,z,16,y+8,z+8));
        var closed=AbyssLodRenderSurface.cull(input,10_000);assertFalse(closed.kept().contains(owner),"Four exact adjacent rectangles jointly cover the sixth16x16 face");assertEquals(1,closed.removed());
        input.remove(input.size()-1);var opened=AbyssLodRenderSurface.cull(input,10_000);assertTrue(opened.kept().contains(owner),"Bounding rectangle alone cannot certify a missing8x8 quarter");assertEquals(0,opened.removed());
    }
    @Test void insufficientFaceBudgetReturnsEveryRawOwnerWithoutPartialRemoval(){
        var input=wall(3);var result=AbyssLodRenderSurface.cull(input,5);
        assertTrue(result.budgetRefused());assertEquals(input,result.kept());assertEquals(0,result.removed());
        assertTrue(result.examinedFaces()<=5,"Preflight/coverage work must obey the caller budget");
        for(int i=0;i<input.size();i++){assertEquals(i,result.keptIndices().get(i));assertSame(input.get(i),result.kept().get(i));}
    }
}
