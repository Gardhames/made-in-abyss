package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalSurfaceOwnerBatchTest {
    private static final Comparator<SurfaceQuadPlan.PageKey> ORDER=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x)
            .thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
    private static TerrainLodColumns.Box box(long x,long xx){return new TerrainLodColumns.Box(x,1,1,xx,2,2,TerrainMaterial.ROCK);}
    private static List<TerrainLodColumns.Box> query(IncrementalSurfacePages pages,boolean local,int maximum,Set<TerrainLodColumns.Box> claimed){
        var method=assertDoesNotThrow(()->IncrementalSurfacePages.class.getMethod(local?"missingLocalOwners":"missingBackgroundOwners",Comparator.class,int.class,Set.class));
        try{@SuppressWarnings("unchecked") var result=(List<TerrainLodColumns.Box>)method.invoke(pages,ORDER,maximum,claimed);return result;}
        catch(InvocationTargetException failed){if(failed.getCause() instanceof RuntimeException cause)throw cause;throw new AssertionError(failed.getCause());}
        catch(IllegalAccessException failure){throw new AssertionError(failure);}
    }
    private record Fixture(IncrementalSurfacePages pages,TerrainLodColumns.Box left,TerrainLodColumns.Box shared,TerrainLodColumns.Box local,TerrainLodColumns.Box right){}
    private static Fixture fixture(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var left=box(-513,-512);var shared=box(-129,-127);var local=box(1,2);var right=box(513,514);
        pages.update(List.of(),List.of(left,shared,shared,local,right));
        pages.update(List.of(),List.of(),List.of(new TerrainLodClipper.BoundsLong(-128,1,1,-127,2,2),new TerrainLodClipper.BoundsLong(0,1,1,2,2,2)),false);
        return new Fixture(pages,left,shared,local,right);
    }
    @Test void aggregateRoleBatchesCrossPagesWithoutChangingOnePageQueriesOrDuplicatingSharedOwner(){
        var f=fixture();var p=f.pages();
        assertEquals(List.of(f.shared()),p.missingLocalPageOwners(ORDER,100,Set.of()));
        assertEquals(List.of(f.left()),p.missingBackgroundPageOwners(ORDER,100,Set.of()));
        assertEquals(List.of(f.shared(),f.local()),query(p,true,100,Set.of()));
        assertEquals(List.of(f.left(),f.right()),query(p,false,100,Set.of()));
        assertEquals(List.of(f.shared()),p.missingLocalPageOwners(ORDER,100,Set.of()));
        assertEquals(List.of(f.left()),p.missingBackgroundPageOwners(ORDER,100,Set.of()));
        assertEquals(4,((Number)p.status().get("visibleMissing")).intValue());
    }
    @Test void boundedClaimsAndExactCacheRestorePreserveThePhysicalRolePartition(){
        var f=fixture();var p=f.pages();
        assertEquals(List.of(f.shared()),query(p,true,1,Set.of()));
        assertEquals(List.of(f.local()),query(p,true,100,Set.of(f.shared())));
        assertEquals(List.of(f.right()),query(p,false,100,Set.of(f.left())));
        assertEquals(List.of(),query(p,false,100,Set.of(f.left(),f.right())));
        p.setLightLookup(b->b.equals(f.shared())||b.equals(f.left())?new SurfaceQuadPlan.Owner(b,13,2,0xff7e7b77):null);
        assertEquals(List.of(f.local()),query(p,true,100,Set.of()));assertEquals(List.of(f.right()),query(p,false,100,Set.of()));
        assertEquals(2,((Number)p.status().get("visibleMissing")).intValue());
        assertEquals(1,((Number)p.status().get("localVisibleMissing")).intValue());
        assertThrows(IllegalArgumentException.class,()->query(p,true,0,Set.of()));
        assertThrows(NullPointerException.class,()->query(p,false,1,null));
    }
    @Test void sharedBackgroundOwnerOccursOnceAcrossNegativePageSeamAndMultisetOccurrences(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var shared=box(-129,-127);var remote=box(257,258);
        pages.update(List.of(),List.of(shared,shared,remote));
        assertEquals(List.of(shared,remote),query(pages,false,10,Set.of()));
        assertEquals(List.of(remote),query(pages,false,10,Set.of(shared)));assertTrue(query(pages,true,10,Set.of()).isEmpty());
        var result=query(pages,false,10,Set.of());assertThrows(UnsupportedOperationException.class,()->result.add(shared));
    }
}
