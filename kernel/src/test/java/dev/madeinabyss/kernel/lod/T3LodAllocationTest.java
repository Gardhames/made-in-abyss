package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class T3LodAllocationTest {
    private static volatile Object sink;
    static List<TerrainLodColumns.Box> denseBoxes(int n) {
        var out=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<n;i++)out.add(T3LodOracleTest.box((i%32)*3L-48,-128+(i/1024)*8L,(i/32%32)*3L-48,1,4,1,TerrainMaterial.ROCK));
        return List.copyOf(out);
    }
    static List<TerrainLodColumns.Box> priorityBoxes() {
        var out=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<128;i++)out.add(T3LodOracleTest.box(-1008+i*16L,-960,-1008,8,1024,2016,TerrainMaterial.ROCK));
        return List.copyOf(out);
    }
    static TerrainLodRefinement.Plan priorityPlan() {
        var b=new TerrainLodColumns.Bounds(-1024,-1024,-1024,1023,255,1023);
        return new TerrainLodRefinement.Plan(List.of(new TerrainLodRefinement.Region(b,1)),Optional.empty(),0);
    }
    @Test void oversubscribedPriorityDoesNotAllocateDiscardedTreeNodes()throws Exception {
        var raw=priorityBoxes();var plan=priorityPlan();var bounds=plan.regions().get(0).bounds();
        var index=AbyssLodSurfacePriority.index(raw,100000,16);var oldIndex=T3ReferenceAbyssLodSurfacePriority.index(raw,100000,16);
        T3TileCodecOracleTest.Work a=()->sink=AbyssLodSurfacePriority.prefix(bounds,plan,index,List.of(),.125,-512.5,.75,.25,0,1,128);
        T3TileCodecOracleTest.Work b=()->sink=T3ReferenceAbyssLodSurfacePriority.prefix(bounds,plan,oldIndex,List.of(),.125,-512.5,.75,.25,0,1,128);
        for(int i=0;i<5;i++){a.run();b.run();}
        long before=T3TileCodecOracleTest.allocated(b,5),after=T3TileCodecOracleTest.allocated(a,5);
        assertTrue(after<before*.95,"Rejected candidates must avoid tree churn: old="+before+" actual="+after);
    }
    @Test void surfaceExtractionUsesPrimitiveOriginalOwnerRemap()throws Exception {
        var boxes=denseBoxes(1024);
        T3TileCodecOracleTest.Work a=()->sink=SurfaceQuadPlan.extractForMerge(boxes,100000,9600000);
        T3TileCodecOracleTest.Work b=()->sink=T3ReferenceSurfaceExtraction.extract(boxes,100000);
        for(int i=0;i<8;i++){a.run();b.run();}
        long before=T3TileCodecOracleTest.allocated(b,8),after=T3TileCodecOracleTest.allocated(a,8);
        assertTrue(after<before*.999,"Surface original-owner remap must avoid boxing: old="+before+" actual="+after);
    }
    @Test void waterOnlyExtractionDoesNotAllocateAnUnusedOpaqueRemap()throws Exception {
        var boxes=denseBoxes(1024).stream().map(b->new TerrainLodColumns.Box(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive(),TerrainMaterial.WATER)).toList();
        T3TileCodecOracleTest.Work a=()->sink=SurfaceQuadPlan.extractForMerge(boxes,100000,9600000);
        T3TileCodecOracleTest.Work b=()->sink=T3ReferenceSurfaceExtraction.extract(boxes,100000);
        for(int i=0;i<16;i++){a.run();b.run();}
        long before=T3TileCodecOracleTest.allocated(b,16),after=T3TileCodecOracleTest.allocated(a,16);
        assertTrue(after<=before+1024,"WATER must not pay an unused O(owner-count) array: old="+before+" actual="+after);
    }
    @Test void footprintOnlyUpdateDoesNotCloneRemoteMembershipsOrCurrentPages()throws Exception {
        var actual=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64,true);
        var old=new T3ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64,true);
        var boxes=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<1024;i++)boxes.add(T3LodOracleTest.box(i*64L+1,-10,1,1,1,1,TerrainMaterial.ROCK));
        actual.update(List.of(),boxes);old.update(List.of(),boxes);
        actual.setLightLookup(T3LodOracleTest::light);old.setLightLookup(T3LodOracleTest::light);
        actual.preparePartial(null,10000,T3LodOracleTest.ORDER);old.preparePartial(null,10000,T3LodOracleTest.ORDER);
        var cursor=new int[]{0};
        T3TileCodecOracleTest.Work a=()->actual.update(List.of(),List.of(),List.of(new TerrainLodClipper.BoundsLong(-1000000L+cursor[0]++*128,-10,1,-999999L+(cursor[0]-1)*128,-9,2)),false);
        T3TileCodecOracleTest.Work b=()->old.update(List.of(),List.of(),List.of(new TerrainLodClipper.BoundsLong(-2000000L+cursor[0]++*128,-10,1,-1999999L+(cursor[0]-1)*128,-9,2)),false);
        for(int i=0;i<20;i++){a.run();b.run();}
        long before=T3TileCodecOracleTest.allocated(b,20),after=T3TileCodecOracleTest.allocated(a,20);
        assertTrue(after<before/4,"Local footprint metadata must avoid remote copies: old="+before+" actual="+after);
    }
    @Test void lodKnownMaterialsAreCanonicalImmutableSamples()throws Exception {
        // Reflection makes the initial missing-factory RED an assertion, not a compiler error.
        Class<?> type=assertDoesNotThrow(()->Class.forName("dev.madeinabyss.kernel.lod.LodTerrainSamples"),"Missing reusable LOD terrain samples");
        var known=type.getMethod("known",TerrainMaterial.class);
        for(var material:TerrainMaterial.values()) {
            var sample=known.invoke(null,material);assertEquals(new TerrainSample.Known(material),sample);
            for(int i=0;i<100;i++)assertSame(sample,known.invoke(null,material));
        }
    }
}
