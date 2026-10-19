package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalSurfacePagesTest {
    private static final Comparator<SurfaceQuadPlan.PageKey> ORDER=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
    private static TerrainLodColumns.Box box(long x,long y,long z,long xx,long yy,long zz){return new TerrainLodColumns.Box(x,y,z,xx,yy,zz,TerrainMaterial.ROCK);}
    private static TerrainLodClipper.BoundsLong footprint(TerrainLodColumns.Box b){return new TerrainLodClipper.BoundsLong(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());}
    private static SurfaceQuadPlan.Owner light(TerrainLodColumns.Box box,int sky){return new SurfaceQuadPlan.Owner(box,sky,2,0xff7e7b77);}
    private static SurfaceQuadPlan.Prepared page(SurfaceQuadPlan.PageSet pages,SurfaceQuadPlan.PageKey key){return pages.pages().stream().filter(p->p.key().equals(key)).findFirst().orElseThrow().prepared();}
    private static void supplyAll(IncrementalSurfacePages pages){for(var box:pages.missingOwners(ORDER,1000))pages.supplyLight(box,light(box,9));}
    private static Set<String> pixels(SurfaceQuadPlan.PageSet pages){var out=new HashSet<String>();for(var page:pages.pages())for(var f:page.prepared().faces())for(long u=f.u0();u<f.u1();u++)for(long v=f.v0();v<f.v1();v++)out.add(f.axis()+":"+f.positive()+":"+f.plane()+":"+u+":"+v);return out;}
    @Test void sharedNegativeSeamOwnerIsRequestedOnceAndOldPagesRemainUntilExactReplacement(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var original=box(-129,1,1,-127,2,2);pages.update(List.of(),List.of(original));
        assertEquals(List.of(original),pages.missingOwners(ORDER,1));pages.supplyLight(original,light(original,13));
        var first=pages.preparePartial(null,1,ORDER);assertEquals(1,first.published().pages().size());assertEquals(1,first.pendingPages());
        var complete=pages.preparePartial(first.published(),1,ORDER);assertEquals(0,complete.pendingPages());assertEquals(2,complete.currentKeys().size());
        for(var p:complete.published().pages())for(var owner:p.prepared().owners()){assertEquals(original,owner.box());assertEquals(13,owner.sky());}
        var replacement=box(-129,1,1,-127,3,2);pages.update(List.of(original),List.of(replacement));var held=pages.preparePartial(complete.published(),2,ORDER);
        assertTrue(held.currentKeys().isEmpty());for(var p:complete.published().pages())assertSame(p.prepared(),page(held.published(),p.key()));
        pages.supplyLight(replacement,light(replacement,10));var next=pages.preparePartial(held.published(),2,ORDER);assertEquals(0,next.pendingPages());
        var oracle=new SurfaceQuadPages(SurfaceQuadPages.Limits.defaults(),128);var expected=oracle.prepare(oracle.geometry(List.of(replacement)),i->light(replacement,10));assertEquals(pixels(expected),pixels(next.published()));
        pages.update(List.of(replacement),List.of());var empty=pages.preparePartial(next.published(),2,ORDER);assertTrue(empty.published().pages().isEmpty());assertEquals(0,empty.pendingPages());
    }
    @Test void localOppositeNeighbourUpdateReextractsOnlyDirtyKeysAndKeepsRemotePrepared(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var left=box(112,1,1,128,17,17);var distant=box(257,1,1,273,17,17);var neighbour=box(128,1,1,144,17,17);
        pages.update(List.of(),List.of(left,distant));supplyAll(pages);var before=pages.preparePartial(null,100,ORDER);var remote=new SurfaceQuadPlan.PageKey(2,0,0);long extracted=((Number)pages.status().get("dirtyKeyExtracts")).longValue();
        pages.update(List.of(),List.of(neighbour));assertEquals(List.of(neighbour),pages.missingOwners(ORDER,100));pages.supplyLight(neighbour,light(neighbour,9));var after=pages.preparePartial(before.published(),100,ORDER);
        assertSame(page(before.published(),remote),page(after.published(),remote));assertTrue(((Number)pages.status().get("dirtyKeyExtracts")).longValue()-extracted<=2);
        assertFalse(after.published().pages().stream().flatMap(p->p.prepared().faces().stream()).anyMatch(f->f.axis()==0&&f.plane()==128&&f.u0()<17&&f.v0()<17));
        assertEquals(0L,((Number)pages.status().get("geometryCalls")).longValue());
        long stable=((Number)pages.status().get("dirtyKeyExtracts")).longValue();pages.update(List.of(left),List.of(left));pages.preparePartial(after.published(),100,ORDER);assertEquals(stable,((Number)pages.status().get("dirtyKeyExtracts")).longValue());
    }
    @Test void buriedPhysicalOwnerHasNoLightDebtUntilAVisibleFaceAppears(){
        var center=box(1,1,1,2,2,2);var neighbour=box(0,1,1,1,2,2);var boxes=List.of(center,neighbour,box(2,1,1,3,2,2),box(1,0,1,2,1,2),box(1,2,1,2,3,2),box(1,1,0,2,2,1),box(1,1,2,2,2,3));
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);pages.update(List.of(),boxes);var missing=pages.missingOwners(ORDER,100);assertEquals(6,missing.size());assertEquals(6,((Number)pages.status().get("visibleOwners")).intValue());assertFalse(missing.contains(center));
        for(var b:missing)pages.supplyLight(b,light(b,9));var old=pages.preparePartial(null,100,ORDER);assertEquals(0,((Number)pages.status().get("visibleMissing")).intValue());
        pages.update(List.of(neighbour),List.of());assertEquals(6,((Number)pages.status().get("visibleOwners")).intValue());assertEquals(List.of(center),pages.missingOwners(ORDER,100));var held=pages.preparePartial(old.published(),100,ORDER);assertFalse(held.currentKeys().contains(new SurfaceQuadPlan.PageKey(0,0,0)));
        pages.supplyLight(center,light(center,13));assertEquals(0,pages.preparePartial(held.published(),100,ORDER).pendingPages());
    }
    @Test void multisetAndIncidenceCapFailuresAreAtomic(){
        var limits=new SurfaceQuadPages.Limits(10,2,1_000_000,1000,1_000_000,1000,76000);var pages=new IncrementalSurfacePages(limits,128);var original=box(1,1,1,2,2,2);
        pages.update(List.of(),List.of(original));assertEquals(1,((Number)pages.status().get("owners")).intValue());supplyAll(pages);var old=pages.preparePartial(null,100,ORDER);var keys=pages.targetKeys();
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->pages.update(List.of(),List.of(box(0,1,1,512,2,2))));assertEquals(keys,pages.targetKeys());assertEquals(1,((Number)pages.status().get("owners")).intValue());
        assertThrows(IllegalArgumentException.class,()->pages.update(List.of(box(8,1,1,9,2,2)),List.of()));
        var again=pages.preparePartial(old.published(),100,ORDER);assertSame(old.published().pages().get(0).prepared(),again.published().pages().get(0).prepared());
        assertThrows(IllegalArgumentException.class,()->pages.supplyLight(original,light(box(8,1,1,9,2,2),9)));
    }
    @Test void cacheRestoreAndResourceInvalidationKeepIndexButReplacePreparedShade(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var original=box(1,1,1,2,2,2);var color=new int[]{0xff112233};pages.setLightLookup(b->new SurfaceQuadPlan.Owner(b,12,2,color[0]));pages.update(List.of(),List.of(original));
        assertTrue(pages.missingOwners(ORDER,100).isEmpty());var old=pages.preparePartial(null,100,ORDER);assertEquals(1,old.published().pages().size());long extracted=((Number)pages.status().get("dirtyKeyExtracts")).longValue();
        color[0]=0xff445566;pages.invalidatePrepared();assertTrue(pages.missingOwners(ORDER,100).isEmpty());var next=pages.preparePartial(old.published(),100,ORDER);assertNotSame(old.published().pages().get(0).prepared(),next.published().pages().get(0).prepared());
        assertEquals(12,next.published().pages().get(0).prepared().owners().get(0).sky());assertEquals(color[0],next.published().pages().get(0).prepared().owners().get(0).rgba());assertEquals(extracted,((Number)pages.status().get("dirtyKeyExtracts")).longValue());
    }
    @Test void missingPageOwnersDoesNotMixUnrelatedDistantScalarIntoFirstReadyPage(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var near=box(1,1,1,2,2,2);var distant=box(513,1,1,514,2,2);
        pages.update(List.of(),List.of(near,distant));
        assertEquals(List.of(near),pages.missingPageOwners(ORDER,1024));
        pages.supplyLight(near,light(near,12));
        var partial=pages.preparePartial(null,16,ORDER);
        assertEquals(Set.of(new SurfaceQuadPlan.PageKey(0,0,0)),partial.currentKeys());
        assertEquals(List.of(distant),pages.missingPageOwners(ORDER,1024));
    }    @Test void claimedNegativeSeamOwnerCannotBlockIndependentPageOrBeSelectedTwice(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var shared=box(-129,1,1,-127,2,2);var independent=box(1,1,1,2,2,2);
        pages.update(List.of(),List.of(shared,shared,independent));
        assertEquals(List.of(shared),pages.missingPageOwners(ORDER,1024,Set.of()));
        var claimed=new HashSet<TerrainLodColumns.Box>();claimed.add(shared);
        assertEquals(List.of(independent),pages.missingPageOwners(ORDER,1024,claimed));
        claimed.add(independent);assertTrue(pages.missingPageOwners(ORDER,1024,claimed).isEmpty());
        pages.supplyLight(independent,light(independent,12));
        assertEquals(Set.of(new SurfaceQuadPlan.PageKey(0,0,0)),pages.preparePartial(null,16,ORDER).currentKeys());
        assertEquals(List.of(shared),pages.missingPageOwners(ORDER,1024));
        pages.supplyLight(shared,light(shared,11));assertTrue(pages.missingPageOwners(ORDER,1024,Set.of()).isEmpty());
        assertEquals(0,((Number)pages.status().get("visibleMissing")).intValue());
    }
    @Test void containsOwnerFollowsLastMultisetOccurrenceAndExactRemovedBounds(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var original=box(-128,-1,-128,-127,0,-127);var replacement=box(-128,-1,-128,-126,0,-127);
        pages.update(List.of(),List.of(original,original));assertTrue(pages.containsOwner(original));
        pages.update(List.of(original),List.of());assertTrue(pages.containsOwner(original));
        pages.update(List.of(original),List.of(replacement));assertFalse(pages.containsOwner(original));assertTrue(pages.containsOwner(replacement));
        assertThrows(IllegalArgumentException.class,()->pages.supplyLight(original,light(original,12)));
        pages.update(List.of(replacement),List.of());assertFalse(pages.containsOwner(replacement));
    }    @Test void localNegativeSharedOwnerDebtIsUniqueAndIndependentOfGlobalFarDebt(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var global=box(513,1,1,514,2,2);var local=box(-129,1,1,-127,2,2);
        pages.update(List.of(),List.of(global),List.of(),false);
        assertEquals(0,((Number)pages.status().get("localVisibleMissing")).intValue());
        pages.update(List.of(),List.of(local,local),List.of(),true);
        assertEquals(2,((Number)pages.status().get("visibleMissing")).intValue());
        assertEquals(1,((Number)pages.status().get("localVisibleMissing")).intValue());
        assertEquals(2,((Number)pages.status().get("localPages")).intValue());
        pages.supplyLight(local,light(local,12));assertEquals(0,((Number)pages.status().get("localVisibleMissing")).intValue());
        assertEquals(1,((Number)pages.status().get("visibleMissing")).intValue());
        pages.update(List.of(local),List.of(),List.of(),false);assertEquals(2,((Number)pages.status().get("localPages")).intValue());
        pages.update(List.of(local),List.of(),List.of(),false);assertEquals(0,((Number)pages.status().get("localPages")).intValue());
    }
    @Test void simultaneousFarAndAirFootprintTagsRevealExistingBuriedLocalOwner(){
        var center=box(-2,-2,-2,-1,-1,-1);var cut=box(-3,-2,-2,-2,-1,-1);
        var boxes=List.of(center,cut,box(-1,-2,-2,0,-1,-1),box(-2,-3,-2,-1,-2,-1),box(-2,-1,-2,-1,0,-1),box(-2,-2,-3,-1,-1,-2),box(-2,-2,-1,-1,-1,0));
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);pages.update(List.of(),boxes,List.of(),false);supplyAll(pages);
        var far=box(1025,1,1,1026,2,2);pages.update(List.of(cut),List.of(far),List.of(footprint(cut)),false);
        assertEquals(1,((Number)pages.status().get("localVisibleMissing")).intValue());
        assertEquals(2,((Number)pages.status().get("visibleMissing")).intValue());
        assertTrue(pages.missingOwners(ORDER,100).contains(center));assertFalse(boxes.stream().filter(b->!b.equals(center)&&!b.equals(cut)).anyMatch(b->pages.missingOwners(ORDER,100).contains(b)));
        pages.supplyLight(center,light(center,12));assertEquals(0,((Number)pages.status().get("localVisibleMissing")).intValue());
    }
    @Test void footprintOnlyTagsAreAtomicAndResourceInvalidationRestoresLocalDebt(){
        var limits=new SurfaceQuadPages.Limits(10,2,1000000,1000,1000000,1000,76000);var pages=new IncrementalSurfacePages(limits,128);
        var original=box(-2,-2,-2,-1,-1,-1);pages.update(List.of(),List.of(original));
        pages.update(List.of(),List.of(),List.of(footprint(original)),false);assertEquals(1,((Number)pages.status().get("localVisibleMissing")).intValue());
        pages.supplyLight(original,light(original,12));assertEquals(0,((Number)pages.status().get("localVisibleMissing")).intValue());
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->pages.update(List.of(),List.of(box(0,1,1,512,2,2)),List.of(),true));
        assertEquals(1,((Number)pages.status().get("localPages")).intValue());assertEquals(0,((Number)pages.status().get("localVisibleMissing")).intValue());
        pages.invalidatePrepared();assertEquals(1,((Number)pages.status().get("localVisibleMissing")).intValue());
        pages.setLightLookup(b->light(b,12));assertTrue(pages.missingOwners(ORDER,100).isEmpty());assertEquals(0,((Number)pages.status().get("localVisibleMissing")).intValue());
        pages.update(List.of(original),List.of(),List.of(),false);assertEquals(0,((Number)pages.status().get("localPages")).intValue());assertEquals(0,((Number)pages.status().get("localVisibleMissing")).intValue());
    }    @Test void absentAirFootprintRemembersSignedLocalPageForLaterBulkFarAndChargesAtomicBudget(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var air=new TerrainLodClipper.BoundsLong(-128,-128,-128,-112,-64,-112);
        pages.update(List.of(),List.of(),List.of(air),false);
        assertEquals(1,((Number)pages.status().get("knownLocalKeysCount")).intValue());assertEquals(0,((Number)pages.status().get("localPages")).intValue());
        var later=box(-127,-127,-127,-126,-126,-126);pages.update(List.of(),List.of(later),List.of(),false);
        assertEquals(1,((Number)pages.status().get("localVisibleMissing")).intValue());assertEquals(1,((Number)pages.status().get("localPages")).intValue());
        pages.update(List.of(later),List.of());assertEquals(0,((Number)pages.status().get("localPages")).intValue());assertEquals(1,((Number)pages.status().get("knownLocalKeysCount")).intValue());
        var limited=new IncrementalSurfacePages(new SurfaceQuadPages.Limits(10,100,63,1000,1000000,1000,76000),128);
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->limited.update(List.of(),List.of(),List.of(air),false));
        assertEquals(0,((Number)limited.status().get("knownLocalKeysCount")).intValue());assertEquals(0,((Number)limited.status().get("retainedIndexBytes")).longValue());
    }    @Test void missingLocalPageOwnersExcludesHigherPriorityGlobalDebtAndBypassesClaimedSharedOwner(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var global=box(-513,1,1,-512,2,2);var shared=box(-129,1,1,-127,2,2);var local=box(1,1,1,2,2,2);
        pages.update(List.of(),List.of(global));pages.update(List.of(),List.of(shared,shared,local),List.of(),true);
        assertEquals(List.of(global),pages.missingPageOwners(ORDER,1024,Set.of()));
        assertEquals(List.of(global),pages.missingBackgroundPageOwners(ORDER,1024,Set.of()));
        assertTrue(pages.missingBackgroundPageOwners(ORDER,1024,Set.of(global)).isEmpty());
        assertEquals(List.of(shared),pages.missingLocalPageOwners(ORDER,1024,Set.of()));
        assertEquals(List.of(local),pages.missingLocalPageOwners(ORDER,1024,Set.of(shared)));
        assertTrue(pages.missingLocalPageOwners(ORDER,1024,Set.of(shared,local)).isEmpty());
        pages.setLightLookup(b->b.equals(shared)?light(shared,12):null);
        assertEquals(List.of(local),pages.missingLocalPageOwners(ORDER,1024,Set.of()));
        pages.supplyLight(local,light(local,12));assertTrue(pages.missingLocalPageOwners(ORDER,1024,Set.of()).isEmpty());
        assertEquals(0,((Number)pages.status().get("localVisibleMissing")).intValue());assertEquals(1,((Number)pages.status().get("visibleMissing")).intValue());
    }    @Test void unlitLocalPredicateFollowsSignedSharedMultisetHydrationResourceAndRemoval(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var shared=box(-129,1,1,-127,2,2);
        pages.update(List.of(),List.of(shared,shared));assertFalse(pages.isUnlitLocalOwner(shared));
        pages.update(List.of(),List.of(),List.of(new TerrainLodClipper.BoundsLong(-128,1,1,-127,2,2)),false);assertTrue(pages.isUnlitLocalOwner(shared));
        pages.supplyLight(shared,light(shared,12));assertFalse(pages.isUnlitLocalOwner(shared));
        pages.invalidatePrepared();assertTrue(pages.isUnlitLocalOwner(shared));
        pages.setLightLookup(b->light(b,12));assertTrue(pages.missingLocalPageOwners(ORDER,1024,Set.of()).isEmpty());assertFalse(pages.isUnlitLocalOwner(shared));
        pages.update(List.of(shared),List.of());assertFalse(pages.isUnlitLocalOwner(shared));pages.invalidatePrepared();assertTrue(pages.isUnlitLocalOwner(shared));
        pages.update(List.of(shared),List.of());assertFalse(pages.isUnlitLocalOwner(shared));assertFalse(pages.isUnlitLocalOwner(box(1,1,1,2,2,2)));
    }
    @Test void backgroundPageCannotReclaimUnlitPhysicalOwnerSharedWithLocalPage(){
        var pages=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var shared=box(-129,1,1,-127,2,2);var background=box(-140,1,1,-139,2,2);
        pages.update(List.of(),List.of(shared,background));
        pages.update(List.of(),List.of(),List.of(new TerrainLodClipper.BoundsLong(-128,1,1,-127,2,2)),false);
        assertEquals(Set.of(shared,background),new HashSet<>(pages.missingPageOwners(ORDER,1024,Set.of())));
        assertEquals(List.of(background),pages.missingBackgroundPageOwners(ORDER,1024,Set.of()));
        assertEquals(List.of(shared),pages.missingLocalPageOwners(ORDER,1024,Set.of()));
        assertTrue(pages.missingBackgroundPageOwners(ORDER,1024,Set.of(background)).isEmpty());
    }}
