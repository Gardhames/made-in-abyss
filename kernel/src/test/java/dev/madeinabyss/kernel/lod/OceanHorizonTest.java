package dev.madeinabyss.kernel.lod;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OceanHorizonTest {
    @Test void fourDisjointSurfaceRectanglesCoverOceanWithoutCoveringIsland() {
        var b=new TerrainLodColumns.Bounds(-1280,-1286,-1280,1279,319,1279);
        var boxes=OceanHorizon.plan(b,12000,0);
        assertEquals(4,boxes.size());long area=0;
        for(var q:boxes){assertEquals(TerrainMaterial.WATER,q.material());assertEquals(0,q.minY());assertEquals(1,q.maxYExclusive());
            assertTrue(q.maxXExclusive()<=-1280||q.minX()>=1280||q.maxZExclusive()<=-1280||q.minZ()>=1280);
            area+=(q.maxXExclusive()-q.minX())*(q.maxZExclusive()-q.minZ());}
        assertEquals(24000L*24000-2560L*2560,area);
        for(int i=0;i<boxes.size();i++)for(int j=i+1;j<boxes.size();j++){
            var a=boxes.get(i);var c=boxes.get(j);assertTrue(a.maxXExclusive()<=c.minX()||c.maxXExclusive()<=a.minX()||a.maxZExclusive()<=c.minZ()||c.maxZExclusive()<=a.minZ());}
    }
    @Test void refusesAnEnvelopeThatCouldFloodTheIslandOrOverflow() {
        assertThrows(IllegalArgumentException.class,()->OceanHorizon.plan(new TerrainLodColumns.Bounds(-720,-1286,-720,719,319,719),12000,0));
        var b=new TerrainLodColumns.Bounds(-1280,-1286,-1280,1279,319,1279);
        assertThrows(IllegalArgumentException.class,()->OceanHorizon.plan(b,1000,0));
        assertThrows(IllegalArgumentException.class,()->OceanHorizon.plan(b,Integer.MAX_VALUE,0));
    }
}
