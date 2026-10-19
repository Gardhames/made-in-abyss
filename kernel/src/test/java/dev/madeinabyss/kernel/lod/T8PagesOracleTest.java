package dev.madeinabyss.kernel.lod;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.madeinabyss.kernel.lod.T3LodOracleTest.*;
class T8PagesOracleTest {
    @Test void randomizedCompositionDeltasFeedExactlyTheSameFullOrderedPages() throws Exception {
        var a=new IncrementalLodComposition(20000,40000,10000000);var b=new T8ReferenceIncrementalLodComposition(20000,40000,10000000);
        var ap=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64);var bp=new T8ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64);
        ap.setLightLookup(T3LodOracleTest::light);bp.setLightLookup(T3LodOracleTest::light);
        var random=new Random(0x813339);var pool=new ArrayList<AbyssLodFineLedger.Grid>();
        for(int i=0;i<32;i++){
            int x=(random.nextInt(8)-4)*16,y=(random.nextInt(8)-4)*16,z=(random.nextInt(8)-4)*16;
            var bounds=new TerrainLodColumns.Bounds(x,y,z,x+15,y+15,z+15);int step=1<<(i%3);
            var boxes=i%7==0?List.<TerrainLodColumns.Box>of():List.of(box(x,y,z,16,16,16,TerrainMaterial.values()[1+i%4]));
            pool.add(new AbyssLodFineLedger.Grid("oracle",bounds,step,new TerrainLodColumns.Result(boxes,new TerrainLodRefinement.Region(bounds,step).sampledVoxels())));
        }
        SurfaceQuadPlan.PageSet ar=null,br=null;
        for(int turn=0;turn<100;turn++){
            var grids=new ArrayList<AbyssLodFineLedger.Grid>();for(int i=0,n=random.nextInt(14);i<n;i++)grids.add(pool.get(random.nextInt(pool.size())));
            var candidate=new AbyssLodFineLedger.Candidate(grids,0);
            var roots=turn%5==0?List.of(box(-64,-64,-64,128,128,128,TerrainMaterial.ROCK)):List.of(box(-64,-64,-64,128,128,128,TerrainMaterial.SOIL));
            var cuts=turn%3==0?List.of(new TerrainLodClipper.BoundsLong(-1,-65,-17,17,1,1)):List.<TerrainLodClipper.BoundsLong>of();
            var x=a.update(roots,candidate,cuts);var y=b.update(roots,candidate,cuts);
            for(var field:y.getClass().getRecordComponents())assertEquals(field.getAccessor().invoke(y),x.getClass().getMethod(field.getName()).invoke(x),field.getName());
            ap.update(x.removed(),x.added(),x.fineFootprints(),true);bp.update(y.removed(),y.added(),y.fineFootprints(),true);
            var aa=ap.preparePartial(ar,100000,ORDER);var bb=bp.preparePartial(br,100000,ORDER);assertEquals(bb,aa);assertEquals(bp.status(),ap.status());ar=aa.published();br=bb.published();
        }
    }
    @Test void randomizedPageTransactionsMatchOldOrderedGeometryLightRolesAndBudgets() {
        var executor=Executors.newFixedThreadPool(3);
        try {
            for(boolean shared:new boolean[]{false,true})for(int metres:new int[]{64,128}) {
                var random=new Random(0x7343+metres+(shared?1:0));
                var actual=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),metres,shared);
                var old=new T8ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),metres,shared);
                var pool=new ArrayList<TerrainLodColumns.Box>();
                for(int i=0;i<90;i++)pool.add(box((random.nextInt(9)-4)*64L+random.nextInt(3)-1,
                        (random.nextInt(7)-5)*64L+random.nextInt(3)-1,(random.nextInt(9)-4)*64L+random.nextInt(3)-1,
                        1+random.nextInt(33),1+random.nextInt(65),1+random.nextInt(33),TerrainMaterial.values()[1+random.nextInt(4)]));
                // Signed page seams and duplicate geometry with different materials are deliberate.
                pool.add(box(-129,-65,-1,2,66,2,TerrainMaterial.ROCK));
                pool.add(box(-129,-65,-1,2,66,2,TerrainMaterial.WATER));
                var live=new ArrayList<TerrainLodColumns.Box>();
                SurfaceQuadPlan.PageSet actualRetained=null,oldRetained=null;
                for(int turn=0;turn<140;turn++) {
                    var savedSnapshot=actual.currentPages();var savedValues=new HashSet<>(savedSnapshot);
                    var removed=new ArrayList<TerrainLodColumns.Box>();var added=new ArrayList<TerrainLodColumns.Box>();
                    for(int i=0,n=random.nextInt(5);i<n&&!live.isEmpty();i++)removed.add(live.remove(random.nextInt(live.size())));
                    for(int i=0,n=random.nextInt(6);i<n;i++){var b=pool.get(random.nextInt(pool.size()));added.add(b);live.add(b);}
                    var local=turn%5==0?List.of(footprint(pool.get(random.nextInt(pool.size())))):List.<TerrainLodClipper.BoundsLong>of();
                    boolean localGeometry=turn%3==0,parallel=turn%2==0;
                    actual.update(removed,added,local,localGeometry,parallel?executor:null,parallel?3:1);
                    old.update(removed,added,local,localGeometry,parallel?executor:null,parallel?3:1);
                    assertEquals(savedValues,savedSnapshot,"Previously returned current-page snapshots must remain frozen");
                    assertThrows(UnsupportedOperationException.class,()->savedSnapshot.add(new SurfaceQuadPlan.PageKey(999,999,999)));
                    assertEquals(old.status(),actual.status(),"metadata turn="+turn);
                    assertEquals(old.targetKeys(),actual.targetKeys());assertEquals(old.currentPages(),actual.currentPages());
                    var missing=actual.missingOwners(ORDER,10000);assertEquals(old.missingOwners(ORDER,10000),missing);
                    var claimed=new HashSet<TerrainLodColumns.Box>();for(int i=0;i<missing.size();i+=3)claimed.add(missing.get(i));
                    assertEquals(old.missingPageOwners(ORDER,7,claimed),actual.missingPageOwners(ORDER,7,claimed));
                    assertEquals(old.missingLocalOwners(ORDER,13,claimed),actual.missingLocalOwners(ORDER,13,claimed));
                    assertEquals(old.missingBackgroundOwners(ORDER,13,claimed),actual.missingBackgroundOwners(ORDER,13,claimed));
                    assertEquals(old.missingLocalPageOwners(ORDER,13,claimed),actual.missingLocalPageOwners(ORDER,13,claimed));
                    assertEquals(old.missingBackgroundPageOwners(ORDER,13,claimed),actual.missingBackgroundPageOwners(ORDER,13,claimed));
                    for(var b:missing)if(random.nextBoolean()){actual.supplyLight(b,light(b));old.supplyLight(b,light(b));}
                    if(turn%23==0){actual.invalidatePrepared();old.invalidatePrepared();}
                    int cap=1+random.nextInt(15);
                    var a=actual.preparePartial(actualRetained,cap,ORDER,parallel?executor:null,parallel?3:1,false);
                    var b=old.preparePartial(oldRetained,cap,ORDER,parallel?executor:null,parallel?3:1,false);
                    assertEquals(b,a,"Complete ordered page/quad/owner/light/provenance/bytes turn="+turn);
                    assertEquals(old.status(),actual.status());actualRetained=a.published();oldRetained=b.published();
                }
            }
        } finally {executor.shutdownNow();}
    }
    @Test void pageBudgetRefusalsMatchOldAndLeaveMembershipForecastReusable() {
        for(long indexBudget:new long[]{600,1200,4000,9000}) {
            var limits=new SurfaceQuadPages.Limits(1000,10000,indexBudget,10000,960000,10000,760000);
            var a=new IncrementalSurfacePages(limits,64,true);var b=new T8ReferenceIncrementalSurfacePages(limits,64,true);
            var live=new ArrayList<TerrainLodColumns.Box>();
            for(int i=0;i<60;i++) {
                var next=box(i%5*64L-129,-65+(i/5%3)*64L,i/15*64L-65,1+i%4,2,2,TerrainMaterial.ROCK);
                var remove=i%3==0&&!live.isEmpty()?List.of(live.get(0)):List.<TerrainLodColumns.Box>of();
                var before=a.status();Throwable ae=null,be=null;
                try{a.update(remove,List.of(next,next));}catch(IllegalArgumentException failure){ae=failure;}
                try{b.update(remove,List.of(next,next));}catch(IllegalArgumentException failure){be=failure;}
                assertEquals(be==null,ae==null);if(ae!=null){assertEquals(be.getClass(),ae.getClass());assertEquals(be.getMessage(),ae.getMessage());assertEquals(before,a.status());}
                else {for(var old:remove)live.remove(old);live.add(next);live.add(next);}
                assertEquals(b.status(),a.status());assertEquals(b.targetKeys(),a.targetKeys());
            }
        }
    }
    @Test void pageExtremeSignedCoordinatesAndCancellationMatchOld() {
        var a=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128,true);
        var b=new T8ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128,true);
        var boxes=List.of(box(Integer.MIN_VALUE,-20480,Integer.MAX_VALUE-1L,16,16,1,TerrainMaterial.ROCK),
                box(-1,-1,-1,1,1,1,TerrainMaterial.WATER));
        a.update(List.of(),boxes);b.update(List.of(),boxes);
        for(var value:a.missingOwners(ORDER,100)){a.supplyLight(value,light(value));b.supplyLight(value,light(value));}
        assertEquals(b.preparePartial(null,100,ORDER),a.preparePartial(null,100,ORDER));
        Thread.currentThread().interrupt();
        try{assertThrows(CancellationException.class,()->a.update(boxes,List.of()));assertThrows(CancellationException.class,()->b.update(boxes,List.of()));}
        finally {Thread.interrupted();}
        assertEquals(b.status(),a.status());
    }
}
