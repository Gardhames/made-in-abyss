package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact ordered values, including physical owners, light, provenance and byte budgets. */
class T3LodOracleTest {
    static final Comparator<SurfaceQuadPlan.PageKey> ORDER=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x)
            .thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
    static TerrainLodColumns.Box box(long x,long y,long z,long dx,long dy,long dz,TerrainMaterial material) {
        return new TerrainLodColumns.Box(x,y,z,x+dx,y+dy,z+dz,material);
    }
    static SurfaceQuadPlan.Owner light(TerrainLodColumns.Box box) {
        int hash=box.hashCode();return new SurfaceQuadPlan.Owner(box,Math.floorMod(hash,16),Math.floorMod(hash/17,16),0xff000000|(hash&0xffffff));
    }
    static TerrainLodClipper.BoundsLong footprint(TerrainLodColumns.Box b) {
        return new TerrainLodClipper.BoundsLong(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());
    }
    @Test void randomizedPageTransactionsMatchOldOrderedGeometryLightRolesAndBudgets() {
        var executor=Executors.newFixedThreadPool(3);
        try {
            for(boolean shared:new boolean[]{false,true})for(int metres:new int[]{64,128}) {
                var random=new Random(0x7343+metres+(shared?1:0));
                var actual=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),metres,shared);
                var old=new T3ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),metres,shared);
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
            var a=new IncrementalSurfacePages(limits,64,true);var b=new T3ReferenceIncrementalSurfacePages(limits,64,true);
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
        var b=new T3ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128,true);
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
    @Test void randomizedSurfacePriorityMatchesOldEveryJobAndTieAtSmallAndUnboundedCaps() {
        var random=new Random(0x7072696f);
        for(int trial=0;trial<90;trial++) {
            int offset=trial%15==0?Integer.MAX_VALUE-1024:trial%15==1?Integer.MIN_VALUE: -512;
            var world=new TerrainLodColumns.Bounds(offset,-1024,-513,offset+1023,255,510);
            var regions=List.of(new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds(offset,-1024,-513,offset+510,255,510),1),
                    new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds(offset+512,-1024,-513,offset+1023,255,510),4));
            var plan=new TerrainLodRefinement.Plan(regions,Optional.empty(),0);
            var raw=new ArrayList<TerrainLodColumns.Box>();
            for(int i=0;i<24;i++)raw.add(box(offset+random.nextInt(64)*16L,-1024+random.nextInt(16)*64L,-512+random.nextInt(64)*16L,
                    1+random.nextInt(17),1+random.nextInt(129),1+random.nextInt(17),TerrainMaterial.values()[1+random.nextInt(4)]));
            if(trial%3==0)raw.addAll(List.copyOf(raw));
            int step=new int[]{1,2,16,64}[trial%4],cap=new int[]{1,8,9,32,128,262144}[trial%6];
            var actualIndex=AbyssLodSurfacePriority.index(raw,100000,step,world);
            var oldIndex=T3ReferenceAbyssLodSurfacePriority.index(raw,100000,step,world);
            var cuts=trial%2==0?List.of(new TerrainLodClipper.BoundsLong(offset+256,-800,-128,offset+600L,-300,128)):List.<TerrainLodClipper.BoundsLong>of();
            double x=offset+512+(trial%2==0?0:.125),y=-512.75,z=trial-45.5;
            double fx=random.nextDouble()-.5,fy=random.nextDouble()-.5,fz=random.nextDouble()-.5;
            assertEquals(T3ReferenceAbyssLodSurfacePriority.prefix(world,plan,oldIndex,cuts,x,y,z,fx,fy,fz,cap),
                    AbyssLodSurfacePriority.prefix(world,plan,actualIndex,cuts,x,y,z,fx,fy,fz,cap),"ordered jobs trial="+trial);
        }
    }
    @Test void randomizedSurfaceExtractionMatchesOldOwnersFaceOrderAndBudgetRefusal() {
        var random=new Random(0x65787472);
        for(int trial=0;trial<150;trial++) {
            var boxes=new ArrayList<TerrainLodColumns.Box>();
            for(int i=0,n=random.nextInt(150);i<n;i++)boxes.add(box(random.nextInt(33)-16,random.nextInt(65)-64,random.nextInt(33)-16,
                    1+random.nextInt(8),1+random.nextInt(16),1+random.nextInt(8),TerrainMaterial.values()[1+random.nextInt(4)]));
            if(trial%4==0)boxes.addAll(List.copyOf(boxes));
            int limit=trial%3==0?1+random.nextInt(50):100000;
            SurfaceQuadPlan.Geometry a=null,b=null;Throwable ae=null,be=null;
            try{a=SurfaceQuadPlan.extractForMerge(boxes,limit,96L*limit);}catch(IllegalArgumentException failure){ae=failure;}
            try{b=T3ReferenceSurfaceExtraction.extract(boxes,limit);}catch(IllegalArgumentException failure){be=failure;}
            assertEquals(be==null,ae==null);if(ae!=null){assertEquals(be.getClass(),ae.getClass());assertEquals(be.getMessage(),ae.getMessage());}
            else {assertEquals(b,a);var geometry=a;var expected=b;assertEquals(SurfaceQuadPlan.prepare(expected,id->light(expected.owners().get(id))),
                    SurfaceQuadPlan.prepare(geometry,id->light(geometry.owners().get(id))));}
        }
    }
}
