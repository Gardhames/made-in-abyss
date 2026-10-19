package dev.madeinabyss.kernel;

import dev.madeinabyss.kernel.light.*;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisibleLightAuditTest {
    @Test void provenVisiblePagePrecedesAnOccludedFrustumPageEvenBeforeItsErrorIsKnown(){
        var index=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var hidden=new TerrainLodColumns.Box(64,0,64,80,16,80,TerrainMaterial.ROCK);
        var visible=new TerrainLodColumns.Box(640,0,0,656,16,16,TerrainMaterial.ROCK);
        index.update(List.of(),List.of(hidden,visible));
        var nearest=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
        var samples=index.visibleAudit(new ProvisionalSkyPages.View(-100,8,8,1,0,0,90,16.0/9),nearest,16,p->p.x()>128&&p.z()<32);
        assertEquals(1,samples.size());assertEquals(visible,samples.get(0).box());
        var audit=new VisibleLightAudit("native-proof",samples,2,.95);
        var proof=samples.get(0).page();assertTrue(audit.visiblePages().contains(proof));
        assertEquals(3,audit.pagePriority(proof,0),"Unknown error on a genuinely visible page still needs refinement");
        assertEquals(14,audit.pagePriority(proof,14));
        assertEquals(0,audit.pagePriority(new SurfaceQuadPlan.PageKey(0,0,0),15),"FOV alone is not visibility");
        java.util.function.ToIntFunction<SurfaceQuadPlan.PageKey> score=p->audit.pagePriority(p,p.x()==0?15:0);
        var priority=Comparator.<SurfaceQuadPlan.PageKey>comparingInt(score).reversed().thenComparing(nearest);
        assertEquals(List.of(visible),index.missingPriorityOwners(priority,1,Set.of(),p->score.applyAsInt(p)>2));
    }
    @Test void worldQualityReadsThePublishedSnapshotRatherThanNewUnpublishedCacheValues(){
        var ss=samples();var index=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        index.update(List.of(),ss.stream().map(VisibleLightAudit.Sample::box).toList());
        var order=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
        var old=index.preparePartial(null,100,order,null,1,true,b->new SurfaceQuadPlan.Owner(b,14,0,0xffffffff));
        var a=new VisibleLightAudit("view",ss,2,.95);for(var s:ss)a.reference(s.box(),s==ss.get(0)?0:14);
        index.supplyLight(ss.get(0).box(),new SurfaceQuadPlan.Owner(ss.get(0).box(),0,0,0xffffffff));
        a.worldDraw(a.presentedFrame(1,old.published()),true,100);
        assertEquals(1,a.status().falseBright());assertEquals(4,a.status().displayed());
        var ready=index.preparePartial(old.published(),100,order,null,1,true,b->new SurfaceQuadPlan.Owner(b,14,0,0xffffffff));
        a.worldDraw(a.presentedFrame(2,ready.published()),true,200);assertEquals(1,a.status().correctFraction());
        assertEquals(200,a.status().firstCorrectWorldMillis());
    }
    private static List<VisibleLightAudit.Sample> samples(){var out=new ArrayList<VisibleLightAudit.Sample>();
        for(int x=0;x<4;x++){var b=new TerrainLodColumns.Box(x,0,0,x+1,1,1,TerrainMaterial.ROCK);out.add(new VisibleLightAudit.Sample(b,new SurfaceQuadPlan.PageKey(0,0,0),new ProvisionalSkyPages.Point(x,1,0)));}return List.copyOf(out);}
    @Test void reportsBothSignedErrorsAgainstTheSameImmutableReference(){
        var ss=samples();var audit=new VisibleLightAudit("view-a",ss,2,.95);int[] truth={0,4,9,15},shown={15,0,15,0};
        for(int i=0;i<4;i++)audit.reference(ss.get(i).box(),truth[i]);
        var f=audit.frame(1,s->shown[(int)s.box().minX()]);assertTrue(audit.worldDraw(f,true,100));
        assertEquals(2,audit.status().falseDark());assertEquals(2,audit.status().falseBright());
        assertEquals(.5,audit.status().falseDarkFraction());assertEquals(.5,audit.status().falseBrightFraction());
        assertEquals(-1,audit.status().firstCorrectWorldMillis());
    }
    @Test void unknownReferenceDoesNotTurnOneGoodSampleIntoNinetyFivePercent(){
        var ss=samples();var a=new VisibleLightAudit("view-a",ss,2,.95);a.reference(ss.get(0).box(),15);
        a.worldDraw(a.frame(1,s->15),true,100);assertEquals(.25,a.status().coverage());assertEquals(.25,a.status().correctFraction());assertEquals(-1,a.status().firstCorrectWorldMillis());
    }
    @Test void currentPositiveWorldAckSetsFirstCorrectTimeOnce(){
        var a=new VisibleLightAudit("view-a",samples(),2,.95);for(var s:a.samples())a.reference(s.box(),12);
        var ready=a.frame(5,s->10);assertFalse(a.worldDraw(ready,false,100));assertEquals(-1,a.status().firstCorrectWorldMillis());
        assertTrue(a.worldDraw(ready,true,200));assertEquals(200,a.status().firstCorrectWorldMillis());
        assertFalse(a.worldDraw(ready,true,300));assertTrue(a.worldDraw(a.frame(6,s->12),true,400));assertEquals(200,a.status().firstCorrectWorldMillis());
    }
    @Test void anotherViewCannotBorrowQualityAndMissingDisplayedOwnersRemainUnknown(){
        var a=new VisibleLightAudit("a",samples(),2,.95);var b=new VisibleLightAudit("b",samples(),2,.95);
        for(var s:a.samples())a.reference(s.box(),15);
        assertFalse(b.worldDraw(a.frame(1,s->15),true,10));
        a.worldDraw(a.frame(1,s->s.box().minX()==0?null:15),true,100);
        assertEquals(3,a.status().displayed());assertEquals(.75,a.status().correctFraction());assertEquals(-1,a.status().firstCorrectWorldMillis());
    }
    @Test void errorPriorityUsesAbsoluteDifferenceAndReferenceIsNeverSilentlyReplaced(){
        var ss=samples();var a=new VisibleLightAudit("a",ss,2,.95);int[] truth={0,15,8,8},shown={15,0,7,10};
        for(int i=0;i<4;i++)a.reference(ss.get(i).box(),truth[i]);a.worldDraw(a.frame(1,s->shown[(int)s.box().minX()]),true,100);
        assertEquals(List.of(ss.get(0).box(),ss.get(1).box()),a.priorityOwners());
        assertThrows(IllegalArgumentException.class,()->a.reference(ss.get(0).box(),1));
    }
    @Test void indexSamplesTheVisibleFaceAndCanPrioritizeItsOriginalOwner(){
        var index=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var box=new TerrainLodColumns.Box(64,0,0,80,16,16,TerrainMaterial.ROCK);index.update(List.of(),List.of(box));
        var view=new ProvisionalSkyPages.View(100,8,8,-1,0,0,90,16.0/9);
        var ss=index.visibleAudit(view,Comparator.comparingLong(SurfaceQuadPlan.PageKey::x),16,p->p.x()==80);
        assertEquals(1,ss.size());assertEquals(box,ss.get(0).box());assertEquals(new ProvisionalSkyPages.Point(80,7,7),ss.get(0).point());
        assertEquals(List.of(box),index.requestOwners(List.of(box),16,Set.of()));
        assertTrue(index.requestOwners(List.of(box),16,Set.of(box)).isEmpty());
    }
    @Test void aCrossBoundaryOwnerIsNotReportedBeforeItsActualFacePageIsReady(){
        var index=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var box=new TerrainLodColumns.Box(112,0,0,128,16,16,TerrainMaterial.ROCK);index.update(List.of(),List.of(box));
        var order=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
        var ss=index.visibleAudit(new ProvisionalSkyPages.View(160,8,8,-1,0,0,90,16.0/9),order,16,p->p.x()==128);
        assertEquals(1,ss.size());assertEquals(new SurfaceQuadPlan.PageKey(1,0,0),ss.get(0).page());
        var first=index.preparePartial(null,1,order,null,1,true,b->new SurfaceQuadPlan.Owner(b,14,0,0xffffffff));
        assertNull(index.presentedAuditSky(ss.get(0),first.currentKeys(),b->14));
        var ready=index.preparePartial(first.published(),10,order,null,1,true,b->new SurfaceQuadPlan.Owner(b,14,0,0xffffffff));
        assertEquals(14,index.presentedAuditSky(ss.get(0),ready.currentKeys(),b->14));
    }
}
