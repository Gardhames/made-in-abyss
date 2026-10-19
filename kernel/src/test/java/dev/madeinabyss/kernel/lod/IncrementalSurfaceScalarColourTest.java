package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalSurfaceScalarColourTest {
    private static final Comparator<SurfaceQuadPlan.PageKey> ORDER=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
    private static final TerrainLodColumns.Box BOX=new TerrainLodColumns.Box(-129,-1,-2,-127,1,-1,TerrainMaterial.ROCK);
    @Test void exactScalarReusesPreparedColourAcrossSignedPageSeamWithoutSamplingAgain(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);pages.update(List.of(),List.of(BOX));
        var provisional=pages.preparePartial(null,100,ORDER,null,1,false,b->new SurfaceQuadPlan.Owner(b,1,2,0xff123456));
        var value=pages.scalarOwner(BOX,13,2,b->{throw new AssertionError("Exact adoption resampled immutable prepared colour");});
        pages.supplyLight(BOX,value);var exact=pages.preparePartial(provisional.published(),100,ORDER);
        assertEquals(0,((Number)pages.status().get("visibleMissing")).intValue());
        for(var p:exact.published().pages())for(var owner:p.prepared().owners()){assertEquals(13,owner.sky());assertEquals(2,owner.block());assertEquals(0xff123456,owner.rgba());}
    }
    @Test void resourceInvalidationAndLastOccurrenceRemovalDiscardOldColour(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);pages.update(List.of(),List.of(BOX,BOX));
        var before=pages.preparePartial(null,100,ORDER,null,1,false,b->new SurfaceQuadPlan.Owner(b,1,2,0xff123456));
        pages.invalidatePrepared();
        assertEquals(0xffabcdef,pages.scalarOwner(BOX,13,2,b->0xffabcdef).rgba());
        var after=pages.preparePartial(before.published(),100,ORDER,null,1,false,b->new SurfaceQuadPlan.Owner(b,1,2,0xffabcdef));
        assertEquals(0xffabcdef,pages.scalarOwner(BOX,13,2,b->{throw new AssertionError("Fresh resource colour not retained");}).rgba());
        pages.update(List.of(BOX),List.of());assertEquals(0xffabcdef,pages.scalarOwner(BOX,13,2,b->{throw new AssertionError("Live multiset colour lost");}).rgba());
        pages.update(List.of(BOX),List.of());pages.update(List.of(),List.of(BOX));
        assertEquals(0xff987654,pages.scalarOwner(BOX,13,2,b->0xff987654).rgba());
    }
    @Test void aFailedProvisionalBatchCannotPublishItsColour(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);pages.update(List.of(),List.of(BOX));
        assertThrows(IllegalStateException.class,()->pages.preparePartial(null,100,ORDER,null,1,false,b->{throw new IllegalStateException("canceled preparation");}));
        assertEquals(0xff123456,pages.scalarOwner(BOX,13,2,b->0xff123456).rgba());
    }
}
