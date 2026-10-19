package dev.madeinabyss.kernel;

import dev.madeinabyss.kernel.light.ProvisionalSkyPages;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProvisionalSkyPagesTest {
    @Test void fourProbeAggregatesUseTheSameSamplesAndIntegerMedianRoundsHalfUp(){
        assertEquals(15,ProvisionalSkyPages.aggregate(new int[]{15,0,13,3},ProvisionalSkyPages.Aggregation.MAX));
        assertEquals(13,ProvisionalSkyPages.aggregate(new int[]{15,0,13,3},ProvisionalSkyPages.Aggregation.SECOND_BRIGHTEST));
        assertEquals(8,ProvisionalSkyPages.aggregate(new int[]{15,0,13,3},ProvisionalSkyPages.Aggregation.MEDIAN));
        assertEquals(0,ProvisionalSkyPages.aggregate(new int[]{0,0,0,15},ProvisionalSkyPages.Aggregation.SECOND_BRIGHTEST));
        assertEquals(8,ProvisionalSkyPages.aggregate(new int[]{0,15},ProvisionalSkyPages.Aggregation.MEDIAN));
        assertEquals(7,ProvisionalSkyPages.aggregate(new int[]{7},ProvisionalSkyPages.Aggregation.SECOND_BRIGHTEST));
        assertEquals(0,ProvisionalSkyPages.aggregate(new int[0],ProvisionalSkyPages.Aggregation.MEDIAN));
        assertThrows(IllegalArgumentException.class,()->ProvisionalSkyPages.aggregate(new int[]{16},ProvisionalSkyPages.Aggregation.MAX));
    }
    @Test void secondBrightestEvaluatesFourOpenPointsAndPrioritizesTheBrightOutlierSymmetrically(){
        var calls=new AtomicInteger();var plan=new ProvisionalSkyPages((x,y,z)->0,(x,y,z)->calls.getAndIncrement()==0?15:0,
            VIEW,128,p->true,ProvisionalSkyPages.Aggregation.SECOND_BRIGHTEST);
        var g=geometry(box(64));plan.prepare(List.of(page(0,g)),null,1);
        assertEquals(4,calls.get());assertEquals(0,plan.sky(box(64)));
        assertEquals(15,plan.priority(new SurfaceQuadPlan.PageKey(0,0,0)),"A high open sample must prioritize a dark aggregate too");
    }
    @Test void deferredTransitionRetainsGeometryAndVisibleQuotaCanEndBeforeOffscreenProbes(){
        var plan=new ProvisionalSkyPages((x,y,z)->0,(x,y,z)->14,VIEW,128);
        var enhanced=new SurfaceProvisionalLight(){
            public void prepare(List<Page> pages,java.util.concurrent.ExecutorService executor,int n){plan.prepare(pages,executor,n);}
            public Object dependencies(SurfaceQuadPlan.Geometry g){return plan.dependencies(g);}
            public long revision(Object d){return plan.revision(d);}
            public SurfaceQuadPlan.Owner apply(TerrainLodColumns.Box b){return new SurfaceQuadPlan.Owner(b,plan.sky(b),0,0xffffffff);}
        };
        var index=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var visible=box(64);var behind=box(640);
        index.update(List.of(),List.of(visible,box(96),behind));
        var order=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
        var old=index.preparePartial(null,100,order,null,1,true,b->new SurfaceQuadPlan.Owner(b,0,0,0xffffffff));
        assertEquals(0,plan.stats().openChecks());
        java.util.function.Predicate<SurfaceQuadPlan.PageKey> inView=p->p.x()<2;
        assertTrue(index.pendingProvisional(enhanced,inView));
        var partial=old;int rounds=0;
        while(index.pendingProvisional(enhanced,inView)&&rounds++<30){
            partial=index.preparePartial(partial.published(),1,order,null,1,true,enhanced);
            assertEquals(old.published().faces(),partial.published().faces());
        }
        assertFalse(index.pendingProvisional(enhanced,inView));assertTrue(index.pendingProvisional(enhanced,p->true));
        assertTrue(partial.published().pages().stream().flatMap(p->p.prepared().owners().stream()).anyMatch(o->o.box().equals(behind)&&o.sky()==0));
        index.supplyLight(visible,new SurfaceQuadPlan.Owner(visible,2,0,0xffffffff));
        assertFalse(index.pendingProvisional(enhanced,inView),"Exact adoption must not reopen the probe-only quota");
        for(int i=0;i<30&&(partial.pendingPages()>0||i==0);i++)partial=index.preparePartial(partial.published(),1,order,null,1,true,enhanced);
        var finalPages=index.preparePartial(partial.published(),100,order,null,1,true,enhanced).published();
        assertTrue(finalPages.pages().stream().flatMap(p->p.prepared().owners().stream()).filter(o->o.box().equals(visible)).allMatch(o->o.sky()==2));
    }
    @Test void pointProbeUsesOriginalThirtyTwoRaysAndDirectSkyAtSignedBoundaries(){
        for(int x:new int[]{-129,-128,-1,0,127,128})for(int direct:new int[]{0,3,12}){
            dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity opacity=(px,py,pz)->
                py==4&&Math.floorMod(px+pz,7)<3?15:py>=8&&py<11?1:0;
            int expected=Math.max(direct,dev.madeinabyss.kernel.light.SkyVisibility.diffuseLevel(
                dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(x,-3,-129,24,100000,opacity)));
            assertEquals(expected,ProvisionalSkyPages.pointSky(x,-3,-129,24,100000,opacity,direct));
        }
    }
    @Test void pointProbeDoesNotInventDarknessForUnknownOrAnExhaustedRay(){
        assertThrows(IllegalStateException.class,()->ProvisionalSkyPages.pointSky(-1,-1,-1,24,100000,
            (x,y,z)->{throw new IllegalStateException("UNKNOWN");},0));
        assertThrows(IllegalStateException.class,()->ProvisionalSkyPages.pointSky(-1,-1,-1,24,1,(x,y,z)->0,0));
        assertEquals(15,ProvisionalSkyPages.pointSky(-1,-1,-1,24,1,(x,y,z)->{throw new AssertionError("direct sky already exact");},15));
        assertThrows(IllegalArgumentException.class,()->ProvisionalSkyPages.pointSky(0,0,0,24,1,(x,y,z)->0,-1));
    }
    private static final ProvisionalSkyPages.View VIEW=new ProvisionalSkyPages.View(100,8,8,-1,0,0,90,16.0/9);
    private static TerrainLodColumns.Box box(int x){return new TerrainLodColumns.Box(x,0,0,x+16,16,16,TerrainMaterial.ROCK);}
    private static SurfaceQuadPlan.Geometry geometry(TerrainLodColumns.Box... boxes){return SurfaceQuadPlan.extractForMerge(List.of(boxes),1000,1000000);}
    private static SurfaceProvisionalLight.Page page(int x,SurfaceQuadPlan.Geometry geometry){return new SurfaceProvisionalLight.Page(new SurfaceQuadPlan.PageKey(x,0,0),geometry);}
    private static int opacity(int x,int y,int z){return x<32||x>=64&&x<80&&y>=0&&y<16&&z>=0&&z<16?15:0;}
    @Test void firstBuriedOwnerCannotMakeOpenVisibleOwnerInSameSourcePageBlack(){
        var plan=new ProvisionalSkyPages(ProvisionalSkyPagesTest::opacity,(x,y,z)->15,VIEW,128);
        plan.prepare(List.of(page(0,geometry(box(0),box(64)))),null,1);
        assertEquals(15,plan.sky(box(64)));assertEquals(15,plan.sky(box(0)));
    }
    @Test void maximumStopsAtFifteenAndNeverSamplesAnOpaquePoint(){
        var calls=new AtomicInteger();var plan=new ProvisionalSkyPages(ProvisionalSkyPagesTest::opacity,(x,y,z)->{
            assertEquals(0,opacity(x,y,z));calls.incrementAndGet();return 15;},VIEW,128);
        plan.prepare(List.of(page(0,geometry(box(0),box(64)))),null,1);
        assertEquals(15,plan.sky(box(64)));assertEquals(1,calls.get());
    }
    @Test void nonSaturatedPageUsesAtMostFourOpenProbesAndReusesResult(){
        var calls=new AtomicInteger();var plan=new ProvisionalSkyPages((x,y,z)->0,(x,y,z)->{calls.incrementAndGet();return 8;},VIEW,128);
        var g=geometry(box(64));plan.prepare(List.of(page(0,g)),null,1);
        assertEquals(8,plan.sky(box(64)));assertEquals(4,calls.get());
        plan.prepare(List.of(page(0,g)),null,1);assertEquals(4,calls.get());
    }
    @Test void changedGeometryInvalidatesDependentPresentationWithoutChangingExactLight(){
        var p=new ProvisionalSkyPages(ProvisionalSkyPagesTest::opacity,(x,y,z)->15,VIEW,128);
        var closed=geometry(box(0));p.prepare(List.of(page(0,closed)),null,1);
        Object deps=p.dependencies(closed);long before=p.revision(deps);assertEquals(0,p.sky(box(0)));
        p.prepare(List.of(page(0,geometry(box(0),box(64)))),null,1);
        assertEquals(15,p.sky(box(0)));assertNotEquals(before,p.revision(deps));
    }
    @Test void priorityTreatsFalseBrightAndFalseDarkSymmetrically(){
        var high=new ProvisionalSkyPages((x,y,z)->0,(x,y,z)->15,VIEW,128);var low=new ProvisionalSkyPages((x,y,z)->0,(x,y,z)->0,VIEW,128);
        var g=geometry(box(64));var key=new SurfaceQuadPlan.PageKey(0,0,0);
        high.prepare(List.of(page(0,g)),null,1);low.prepare(List.of(page(0,g)),null,1);
        high.noteExact(box(64),0);low.noteExact(box(64),15);
        assertEquals(15,high.priority(key));assertEquals(high.priority(key),low.priority(key));
    }
    @Test void negativeSourcePageBoundaryDoesNotShareItsSky(){
        var plan=new ProvisionalSkyPages((x,y,z)->0,(x,y,z)->x<-128?3:12,VIEW,128);
        var left=new TerrainLodColumns.Box(-160,0,0,-144,16,16,TerrainMaterial.ROCK);
        var right=new TerrainLodColumns.Box(-112,0,0,-96,16,16,TerrainMaterial.ROCK);
        plan.prepare(List.of(page(-2,geometry(left)),page(-1,geometry(right))),null,1);
        assertEquals(3,plan.sky(left));assertEquals(12,plan.sky(right));
    }
    @Test void unknownProbeRefusesWholeBatchAndRetryCanStillSucceed(){
        var unknown=new AtomicBoolean(true);
        var plan=new ProvisionalSkyPages((x,y,z)->{if(unknown.get())throw new IllegalStateException("UNKNOWN");return 0;},(x,y,z)->15,VIEW,128);
        var g=geometry(box(64));assertThrows(IllegalStateException.class,()->plan.prepare(List.of(page(0,g)),null,1));
        assertEquals(0,plan.stats().pages());unknown.set(false);plan.prepare(List.of(page(0,g)),null,1);assertEquals(15,plan.sky(box(64)));
    }
    @Test void pageIndexUpdatesAllDependentPagesButExactOwnerAlwaysWins(){
        var plan=new ProvisionalSkyPages(ProvisionalSkyPagesTest::opacity,(x,y,z)->15,VIEW,128);
        var provisional=new SurfaceProvisionalLight(){
            public void prepare(List<Page> pages,java.util.concurrent.ExecutorService executor,int n){plan.prepare(pages,executor,n);}
            public Object dependencies(SurfaceQuadPlan.Geometry g){return plan.dependencies(g);}
            public long revision(Object deps){return plan.revision(deps);}
            public SurfaceQuadPlan.Owner apply(TerrainLodColumns.Box b){return new SurfaceQuadPlan.Owner(b,plan.sky(b),0,0xffffffff);}
        };
        var index=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var tall=new TerrainLodColumns.Box(0,0,0,16,200,16,TerrainMaterial.ROCK);
        index.update(List.of(),List.of(tall));
        Comparator<SurfaceQuadPlan.PageKey> order=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
        var before=index.preparePartial(null,100,order,null,1,true,provisional).published();
        assertTrue(before.pages().stream().flatMap(p->p.prepared().owners().stream()).allMatch(o->o.sky()==0));
        index.update(List.of(),List.of(box(64)));
        var after=index.preparePartial(before,100,order,null,1,true,provisional).published();
        assertTrue(after.pages().stream().flatMap(p->p.prepared().owners().stream()).allMatch(o->o.sky()==15));
        index.supplyLight(tall,new SurfaceQuadPlan.Owner(tall,0,0,0xffffffff));
        var exact=index.preparePartial(after,100,order,null,1,true,provisional).published();
        assertTrue(exact.pages().stream().flatMap(p->p.prepared().owners().stream()).filter(o->o.box().equals(tall)).allMatch(o->o.sky()==0));
    }
    @Test void visibleHighErrorPageCanTakePriorityOverAnUnrelatedNearPage(){
        var index=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var near=box(64);var far=box(640);index.update(List.of(),List.of(near,far));
        Comparator<SurfaceQuadPlan.PageKey> order=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x);
        assertEquals(List.of(far),index.missingPriorityOwners(order,1,Set.of(),p->p.x()==5));
        assertTrue(index.missingPriorityOwners(order,1,Set.of(far),p->p.x()==5).isEmpty());
    }
    @Test void darkPageNextToBrightPageIsAlsoAnErrorPriorityCandidate(){
        var plan=new ProvisionalSkyPages((x,y,z)->0,(x,y,z)->x<128?0:15,VIEW,128);
        plan.prepare(List.of(page(0,geometry(box(64))),page(1,geometry(box(192)))),null,1);
        assertEquals(15,plan.priority(new SurfaceQuadPlan.PageKey(0,0,0)));
    }
    @Test void missingPlanIsNotPublishedAsKnownDarkness(){
        var plan=new ProvisionalSkyPages((x,y,z)->0,(x,y,z)->8,VIEW,128);
        assertThrows(IllegalStateException.class,()->plan.sky(box(64)));
    }
    @Test void viewChangeInvalidatesTheDependentPresentationToken(){
        var plan=new ProvisionalSkyPages((x,y,z)->0,(x,y,z)->8,VIEW,128);var g=geometry(box(64));
        plan.prepare(List.of(page(0,g)),null,1);Object deps=plan.dependencies(g);long before=plan.revision(deps);
        plan.view(new ProvisionalSkyPages.View(32,8,8,1,0,0,90,16.0/9));assertNotEquals(before,plan.revision(deps));
    }
    @Test void pageAdmissionRefusesBeforeRetiringAStillDisplayedPlan(){
        var plan=new ProvisionalSkyPages((x,y,z)->0,(x,y,z)->8,VIEW,1);
        plan.prepare(List.of(page(0,geometry(box(64)))),null,1);
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->plan.prepare(List.of(page(1,geometry(box(192)))),null,1));
        assertEquals(8,plan.sky(box(64)));
    }
    @Test void probesOutsideDeclaredDomainAreOmittedWithoutInventingUnknownDarkness(){
        var plan=new ProvisionalSkyPages((x,y,z)->{if(y<0)throw new IllegalStateException("below declared domain");return 0;},(x,y,z)->8,VIEW,128,p->p.y()>=0);
        plan.prepare(List.of(page(0,geometry(box(64)))),null,1);assertEquals(8,plan.sky(box(64)));
    }
    @Test void immutableUnchangedCandidatesReuseTheirOpenProof(){
        var plan=new ProvisionalSkyPages((x,y,z)->0,(x,y,z)->8,VIEW,128);var g=geometry(box(64));
        plan.prepare(List.of(page(0,g)),null,1);long reads=plan.stats().openChecks();
        plan.prepare(List.of(page(0,g)),null,1);assertEquals(reads,plan.stats().openChecks());
    }
}
