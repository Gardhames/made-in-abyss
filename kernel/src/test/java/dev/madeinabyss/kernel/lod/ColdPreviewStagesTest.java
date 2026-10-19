package dev.madeinabyss.kernel.lod;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ColdPreviewStagesTest {
    @Test void nearBoundsKeepRequestedLatticeAtNegativeCoordinatesAndCountActualSamples() {
        var world=new TerrainLodColumns.Bounds(-1003,-1286,-1001,1000,319,1000);
        var plan=ColdPreviewStages.near(world,-17.5,-661.5,-31.5,200000).orElseThrow();
        var b=plan.bounds();
        assertEquals(0,Math.floorMod(b.minX()-world.minX(),16));
        assertEquals(0,Math.floorMod(b.minZ()-world.minZ(),16));
        assertTrue(b.minX()<=-178&&b.maxXInclusive()>=142);
        assertTrue(b.minY()>=world.minY()&&b.maxYInclusive()<=world.maxYInclusive());
        assertEquals(new TerrainLodRefinement.Region(b,16).sampledVoxels(),plan.samples());
        assertTrue(plan.samples()<=200000);
        assertTrue(ColdPreviewStages.near(world,-17.5,-661.5,-31.5,plan.samples()-1).isEmpty());
    }
    @Test void clippingSignedEdgesDoesNotClaimUnknownOutsideRequestedWorld() {
        var world=new TerrainLodColumns.Bounds(Integer.MIN_VALUE,-20,Integer.MIN_VALUE,Integer.MIN_VALUE+1000,20,Integer.MIN_VALUE+1000);
        var b=ColdPreviewStages.near(world,Integer.MIN_VALUE,-20,Integer.MIN_VALUE,200000).orElseThrow().bounds();
        assertEquals(world.minX(),b.minX());assertEquals(world.minZ(),b.minZ());assertEquals(-20,b.minY());
        assertTrue(b.maxXInclusive()<=world.maxXInclusive());assertTrue(b.maxYInclusive()<=world.maxYInclusive());
        var tiny=new TerrainLodColumns.Bounds(-5,-5,-5,5,5,5);
        assertTrue(ColdPreviewStages.near(tiny,0,0,0,200000).isEmpty());
    }
    @Test void ownerAcknowledgementKeepsStartupGuardUntilFullOverviewAndCancelCannotResume() {
        var state=new ColdPreviewStages(true);
        assertEquals(ColdPreviewStages.Stage.NEAR_PREVIEW,state.stage());assertTrue(state.guarded());
        assertThrows(IllegalStateException.class,state::fullDisplayed);
        state.nearDisplayed();assertEquals(ColdPreviewStages.Stage.FULL_PREVIEW,state.stage());assertTrue(state.guarded());
        state.fullDisplayed();assertEquals(ColdPreviewStages.Stage.REQUESTED,state.stage());assertFalse(state.guarded());
        state.cancel();assertEquals(ColdPreviewStages.Stage.CANCELLED,state.stage());
        assertThrows(IllegalStateException.class,state::nearDisplayed);assertThrows(IllegalStateException.class,state::fullDisplayed);
        var bypass=new ColdPreviewStages(false);bypass.bypass();assertFalse(bypass.guarded());
    }
    @Test void actualHandoffKeepsNearUntilFullHasDrawnAndNeverOwnsMoreThanTwoScenes() {
        var state=new ColdPreviewStages(true);var groups=new RenderGroupHandoff<String>();
        var ack=new java.util.concurrent.CompletableFuture<Long>();
        groups.beginTarget(1);groups.stage(1,"near");assertFalse(ack.isDone());
        assertThrows(IllegalStateException.class,groups::promote);
        groups.markDrawn(1,"near");groups.promote();state.nearDisplayed();groups.beginTarget(2);ack.complete(2L);
        groups.stage(ack.join(),"full");assertEquals("near",groups.displayed());assertEquals(2,groups.size());
        assertThrows(IllegalStateException.class,groups::promote);
        groups.markDrawn(2,"full");assertEquals("near",groups.promote());state.fullDisplayed();
        groups.beginTarget(3);groups.stage(3,"requested");assertEquals(2,groups.size());
        state.cancel();assertEquals(2,groups.clear().size());assertEquals(0,groups.size());
    }
    @Test void optionalEarlyDetailRequiresItsOwnOwnerAcknowledgementBeforeFullOverview() {
        var state=new ColdPreviewStages(true,true);
        assertThrows(IllegalStateException.class,state::nearDetailDisplayed);
        state.nearDisplayed();assertEquals(ColdPreviewStages.Stage.NEAR_DETAIL,state.stage());assertTrue(state.guarded());
        assertThrows(IllegalStateException.class,state::fullDisplayed);
        state.nearDetailDisplayed();assertEquals(ColdPreviewStages.Stage.FULL_PREVIEW,state.stage());assertTrue(state.guarded());
        state.fullDisplayed();assertEquals(ColdPreviewStages.Stage.REQUESTED,state.stage());assertFalse(state.guarded());
        var cancelled=new ColdPreviewStages(true,true);cancelled.nearDisplayed();cancelled.cancel();
        assertThrows(IllegalStateException.class,cancelled::nearDetailDisplayed);assertThrows(IllegalStateException.class,cancelled::fullDisplayed);
        var noNear=new ColdPreviewStages(false,true);assertEquals(ColdPreviewStages.Stage.FULL_PREVIEW,noNear.stage());
        var unavailable=new ColdPreviewStages(true,true);unavailable.nearDisplayed();unavailable.skipNearDetail();
        assertEquals(ColdPreviewStages.Stage.FULL_PREVIEW,unavailable.stage());assertTrue(unavailable.guarded());
    }
    @Test void earlyDetailHandoffHoldsEachDrawnSceneUntilNextOwnerAckWithinTwoScenes() {
        var state=new ColdPreviewStages(true,true);var groups=new RenderGroupHandoff<String>();
        var nearAck=new java.util.concurrent.CompletableFuture<Long>();
        var detailAck=new java.util.concurrent.CompletableFuture<Long>();
        groups.beginTarget(1);groups.stage(1,"near");assertFalse(nearAck.isDone());
        groups.markDrawn(1,"near");groups.promote();state.nearDisplayed();groups.beginTarget(2);nearAck.complete(2L);
        groups.stage(nearAck.join(),"early32");assertEquals("near",groups.displayed());assertEquals(2,groups.size());
        assertFalse(detailAck.isDone());assertThrows(IllegalStateException.class,groups::promote);
        groups.markDrawn(2,"early32");assertEquals("near",groups.promote());state.nearDetailDisplayed();
        groups.beginTarget(3);detailAck.complete(3L);groups.stage(detailAck.join(),"fullWithEarly32");
        assertEquals("early32",groups.displayed());assertEquals(2,groups.size());assertTrue(state.guarded());
        groups.markDrawn(3,"fullWithEarly32");assertEquals("early32",groups.promote());state.fullDisplayed();
        assertEquals("fullWithEarly32",groups.displayed());assertEquals(1,groups.size());
    }
    @Test void optionalEarlyBudgetReservesWorstFreshPrefixAgainstAChangedCompletePlan() {
        long fixed=41113600+10278400+169344L,detail=60000000,worstPrefix=128L*16*64*16;
        assertTrue(ColdPreviewStages.earlyFitsBudget(fixed,detail,128,120000000));
        assertTrue(ColdPreviewStages.earlyFitsBudget(fixed,detail,128,fixed+detail+worstPrefix));
        assertFalse(ColdPreviewStages.earlyFitsBudget(fixed,detail,128,fixed+detail+worstPrefix-1));
        assertFalse(ColdPreviewStages.earlyFitsBudget(Long.MAX_VALUE,1,128,Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->ColdPreviewStages.earlyFitsBudget(-1,detail,128,120000000));
        assertThrows(IllegalArgumentException.class,()->ColdPreviewStages.earlyFitsBudget(fixed,detail,0,120000000));
    }
}
