package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfacePageSummaryTest {
    private static SurfaceQuadPlan.Prepared mixedSurface() {
        var rock=new TerrainLodColumns.Box(-129,-2,-1,-128,-1,0,TerrainMaterial.ROCK);
        var water=new TerrainLodColumns.Box(-125,-2,-1,-124,-1,0,TerrainMaterial.WATER);
        return SurfaceQuadPlan.prepare(SurfaceQuadPlan.extract(List.of(rock,water),100,7600),
                i->new SurfaceQuadPlan.Owner(i==0?rock:water,11,2,i==0?0xff7e7b77:0xb4355ea3));
    }
    private static int count(SurfaceQuadPlan.Page page,String name) {
        var method=assertDoesNotThrow(()->SurfaceQuadPlan.Page.class.getMethod(name),
                "Immutable pages must expose validated material counts for aggregate reuse");
        return assertDoesNotThrow(()->((Number)method.invoke(page)).intValue());
    }
    @Test void immutablePageSummarizesOpaqueAndWaterWithoutChangingPreparedOrder() {
        var prepared=mixedSurface();var page=new SurfaceQuadPlan.Page(new SurfaceQuadPlan.PageKey(-2,-1,-1),prepared);
        assertEquals(6,count(page,"opaqueFaces"));assertEquals(6,count(page,"waterFaces"));assertSame(prepared,page.prepared());
        var set=new SurfaceQuadPlan.PageSet(List.of(page),12L*64,12L*76,12,6,6);
        assertSame(page,set.pages().get(0));assertEquals(prepared,set.pages().get(0).prepared());
    }
    @Test void explicitPageCountsCannotForgeMaterialOrAggregateAdmission() {
        var constructor=assertDoesNotThrow(()->SurfaceQuadPlan.Page.class.getConstructor(
                SurfaceQuadPlan.PageKey.class,SurfaceQuadPlan.Prepared.class,int.class,int.class),
                "Cached page summaries must validate caller-provided counts");
        var failure=assertThrows(InvocationTargetException.class,()->constructor.newInstance(
                new SurfaceQuadPlan.PageKey(-2,-1,-1),mixedSurface(),12,0));
        assertInstanceOf(IllegalArgumentException.class,failure.getCause());
    }
    @Test void pageSetStillRejectsForgedTotalsAndDuplicateCanonicalKeys() {
        var page=new SurfaceQuadPlan.Page(new SurfaceQuadPlan.PageKey(-2,-1,-1),mixedSurface());
        assertThrows(IllegalArgumentException.class,()->new SurfaceQuadPlan.PageSet(List.of(page),768,912,12,12,0));
        assertThrows(IllegalArgumentException.class,()->new SurfaceQuadPlan.PageSet(List.of(page,page),1536,1824,24,12,12));
    }
}
