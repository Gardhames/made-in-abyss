package dev.madeinabyss.kernel.lod;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalSurfaceIndexRetentionTest {
    @Test void sharedMetadataMustFitBudgetBeforeAnyOwnerIsInstalled(){
        var limits=new SurfaceQuadPages.Limits(4,1000,300,1000,100000,1000,100000);var index=new IncrementalSurfacePages(limits,128,true);
        var box=new TerrainLodColumns.Box(2,2,2,3,3,3,dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK);
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->index.update(List.of(),List.of(box)),"a sparse shared group includes signature/key metadata");
        assertEquals(0,index.status().get("uniqueOwners"));assertEquals(0L,index.status().get("retainedIndexBytes"));assertTrue(index.targetKeys().isEmpty());
    }
    private static final Comparator<SurfaceQuadPlan.PageKey> ORDER=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
    private static void samePublications(IncrementalSurfacePages baseline,IncrementalSurfacePages shared){
        for(var index:List.of(baseline,shared))for(var box:index.missingOwners(ORDER,10000))index.supplyLight(box,new SurfaceQuadPlan.Owner(box,11,2,box.material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER?0xb43f9fb0:0xff788577));
        SurfaceQuadPlan.PageSet a=null,b=null;int pending;
        do{var expected=baseline.preparePartial(a,2,ORDER);var actual=shared.preparePartial(b,2,ORDER);assertEquals(expected,actual,"every face, owner, scalar and publication must remain exact");a=expected.published();b=actual.published();pending=actual.pendingPages();}while(pending>0);
        assertEquals(baseline.status().get("rawFaces"),shared.status().get("rawFaces"));assertEquals(baseline.status().get("visibleOwners"),shared.status().get("visibleOwners"));
    }
    @Test void dedupMatchesLegacyFacesLightMultisetAndRetirementAcrossNegativePages(){
        var legacy=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128,false);var shared=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128,true);var boxes=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<360;i++){long x=(i%60-30)*9L,y=-129-(i/60)*3L,z=(i%7-3)*43L;boxes.add(new TerrainLodColumns.Box(x,y,z,x+2,y+2,z+2,i%9==0?dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER:dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK));}
        for(var index:List.of(legacy,shared))index.update(List.of(),boxes);samePublications(legacy,shared);
        var duplicates=List.copyOf(boxes.subList(0,60));for(var index:List.of(legacy,shared))index.update(List.of(),duplicates);boxes.addAll(duplicates);samePublications(legacy,shared);
        for(var index:List.of(legacy,shared))index.invalidatePrepared();samePublications(legacy,shared);
        var removal=List.copyOf(boxes.subList(0,90));for(var index:List.of(legacy,shared))index.update(removal,List.of());for(var box:removal)boxes.remove(box);samePublications(legacy,shared);
        var before=shared.status();assertThrows(IllegalArgumentException.class,()->shared.update(List.of(new TerrainLodColumns.Box(1000,0,0,1001,1,1,dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK)),List.of()));assertEquals(before,shared.status(),"failed staging must leave shared ownership unchanged");
        for(var index:List.of(legacy,shared))index.update(boxes,List.of());samePublications(legacy,shared);assertEquals(0,shared.status().get("membershipArrays"));assertEquals(0,shared.status().get("canonicalPageKeys"));
    }
    @Test void repeatedPageMembershipRetainsLessThan150KBAndReleasesAfterRemoval(){
        var index=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var boxes=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<2000;i++)boxes.add(new TerrainLodColumns.Box(2+i%50,2+i/50,2,3+i%50,3+i/50,3,dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK));
        index.update(List.of(),boxes);
        assertEquals(2000,index.status().get("uniqueOwners"));
        assertTrue(((Number)index.status().get("retainedIndexBytes")).longValue()<150000,"shared immutable one-page memberships must meet retained index budget");
        index.update(boxes,List.of());assertEquals(0,index.status().get("uniqueOwners"));assertEquals(0L,index.status().get("retainedIndexBytes"));
    }
}
