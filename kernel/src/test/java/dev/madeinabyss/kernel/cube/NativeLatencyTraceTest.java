package dev.madeinabyss.kernel.cube;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLatencyTraceTest {
    @Test void firstFullWorldIsJournaledOncePerCommittedCoverageAndIgnoresShadowOrRetainedDraw() {
        var trace=new NativeLatencyTrace();trace.begin(1,0,0,0,-330,0,64,"NORMAL","TELEPORT");
        trace.commit(1,4,false,1);trace.draw(1,4,new CubeKey(0,-21,0),8,true,false,2);
        trace.commit(1,5,true,3);trace.draw(1,5,new CubeKey(0,-21,0),8,false,true,4);
        trace.retainedDraw(1,new CubeKey(0,-21,0),8,true,5);
        trace.draw(1,5,new CubeKey(0,-21,0),8,true,true,6_000_000);
        trace.draw(1,5,new CubeKey(0,-21,0),8,true,true,7_000_000);
        var full=trace.events().stream().filter(e->e.stage().equals("FIRST_FULL_NATIVE_WORLD_DRAW")).toList();
        assertEquals(1,full.size());assertEquals(6,full.get(0).millis());assertEquals(5,full.get(0).revision());
        trace.commit(1,6,true,8_000_000);trace.draw(1,6,new CubeKey(0,-21,0),8,true,true,9_000_000);
        assertEquals(2,trace.events().stream().filter(e->e.stage().equals("FIRST_FULL_NATIVE_WORLD_DRAW")).count());
    }
    @Test void retainedCoherentCubeCanSatisfyNearbyDrawWithoutCompletingNewFullWindow() {
        var trace=new NativeLatencyTrace();trace.begin(1,0,0,0,-330,0,64,"NORMAL","ANCHOR");
        trace.commit(1,4,false,1);trace.begin(2,100_000_000L,0,16,-330,0,64,"NORMAL","ANCHOR");
        retained(trace,2,new CubeKey(0,-21,0),12,false,101_000_000L);
        assertEquals(-1,trace.targets().get(1).nativeMillis());
        retained(trace,2,new CubeKey(0,-21,0),12,true,102_000_000L);
        assertEquals(2,trace.targets().get(1).nativeMillis());assertEquals(-1,trace.targets().get(1).handoffMillis());
    }
    private static void retained(NativeLatencyTrace trace,long generation,CubeKey cube,int faces,boolean coherent,long nano){
        trace.retainedDraw(generation,cube,faces,coherent,nano);
    }
    @Test void retiredAlreadyExitedJobIsZeroAndNewFullCohortDoesNotReuseNearExit() {
        var trace=new NativeLatencyTrace();trace.begin(1,0,0,0,-330,0,64,"NORMAL","TELEPORT");
        trace.workerExited(1,100_000_000L);trace.cancel(1,500_000_000L);
        assertEquals(0,trace.targets().get(0).cancellationMillis());
        trace.begin(2,600_000_000L,0,500,-330,0,64,"NORMAL","TELEPORT");
        queued(trace,2,610_000_000L);trace.workerExited(2,800_000_000L);queued(trace,2,900_000_000L);
        trace.cancel(2,1_000_000_000L);assertEquals(-1,trace.targets().get(1).cancellationMillis(),"An earlier NEAR exit cannot acknowledge active FULL cancellation");
        trace.workerExited(2,1_020_000_000L);assertEquals(20,trace.targets().get(1).cancellationMillis());
    }
    private static void queued(NativeLatencyTrace trace,long generation,long nano){
        try{NativeLatencyTrace.class.getMethod("workQueued",long.class,long.class).invoke(trace,generation,nano);}
        catch(NoSuchMethodException baseline){/* Existing client has no active-cohort ledger. */}
        catch(ReflectiveOperationException failed){throw new AssertionError(failed);}
    }
    @Test void legitimateNegativeNanoOriginDoesNotLookReadyAndPreservesFirstDrawAndCancellation() {
        var trace=new NativeLatencyTrace();trace.begin(1,-1_000_000_000L,1234,0,-330,0,64,"NORMAL","TELEPORT");
        assertEquals(-1,trace.targets().get(0).nativeMillis());
        trace.commit(1,4,false,-990_000_000L);
        trace.draw(1,4,new CubeKey(0,-21,0),12,true,false,-900_000_000L);
        trace.draw(1,4,new CubeKey(0,-21,0),12,true,false,-800_000_000L);
        assertEquals(100,trace.targets().get(0).nativeMillis());
        trace.cancel(1,-700_000_000L);trace.workerExited(1,-650_000_000L);
        assertTrue(trace.targets().get(0).cancelled());assertEquals(50,trace.targets().get(0).cancellationMillis());
    }
    @Test void cutAckBeforeNearbyFullDrawWaitsForBothAndUsesLaterActualTimestamp() {
        var trace=new NativeLatencyTrace();trace.begin(1,0,0,0,-330,0,64,"NORMAL","TELEPORT");
        trace.commit(1,7,true,1);trace.cutAck(1,7,true,100_000_000L);
        trace.draw(1,7,new CubeKey(5,-21,0),10,true,true,150_000_000L);
        assertEquals(-1,trace.targets().get(0).handoffMillis(),"a distant FULL submission is not near native draw");
        trace.draw(1,7,new CubeKey(0,-21,0),10,true,true,200_000_000L);
        assertEquals(200,trace.targets().get(0).handoffMillis());
        trace.commit(1,8,true,210_000_000L);assertEquals(-1,trace.targets().get(0).cutWorldAckMillis());
    }
    @Test void coldIntentDoesNotClaimFreshCachesAndInvalidBeginCannotRetireCurrentTrace() {
        var trace=new NativeLatencyTrace();trace.begin(1,0,0,0,-330,0,64,"BYPASS","TELEPORT");
        assertFalse(trace.targets().get(0).sourceCachesFresh());assertFalse(trace.targets().get(0).diskRestoreBypassed());
        assertThrows(IllegalArgumentException.class,()->trace.begin(2,1,0,1e30,-330,0,64,"NORMAL","TELEPORT"));
        trace.commit(1,4,false,1);trace.draw(1,4,new CubeKey(0,-21,0),12,true,false,200_000_000L);
        assertEquals(200,trace.targets().get(0).nativeMillis());
    }
    @Test void measuresAppliedTeleportToSpecificNearCubeAndFullCutAck() {
        var trace=new NativeLatencyTrace();trace.begin(1,1_000_000_000L,1234,-.1,-330,500,64,"NORMAL","TELEPORT");
        trace.commit(1,4,false,1_100_000_000L);
        trace.draw(1,4,new CubeKey(-1,-21,31),12,false,false,1_150_000_000L);
        assertEquals(-1,trace.targets().get(0).nativeMillis());
        trace.draw(1,4,new CubeKey(-1,-21,31),12,true,false,1_200_000_000L);
        var t=trace.targets().get(0);assertEquals(200,t.nativeMillis());assertEquals(12,t.nativeFaces());
        assertEquals(java.util.List.of(-1,-21,31),t.nativeCube());
        trace.cutAck(1,4,true,1_250_000_000L);assertEquals(-1,trace.targets().get(0).handoffMillis());
        trace.commit(1,5,true,1_300_000_000L);
        trace.draw(1,5,new CubeKey(-1,-21,31),10,true,true,1_350_000_000L);
        trace.cutAck(1,4,true,1_400_000_000L);assertEquals(-1,trace.targets().get(0).handoffMillis());
        trace.cutAck(1,5,false,1_450_000_000L);assertEquals(-1,trace.targets().get(0).handoffMillis());
        trace.cutAck(1,5,true,1_500_000_000L);assertEquals(500,trace.targets().get(0).handoffMillis());
    }
    @Test void supersededTargetsCannotReceiveStaleWorldDrawAndHistoryIsBounded() {
        var trace=new NativeLatencyTrace();trace.begin(1,0,0,0,-330,0,64,"NORMAL","TELEPORT");
        trace.commit(1,4,false,1);trace.cancel(1,10);trace.begin(2,20,0,500,-330,0,64,"NORMAL","TELEPORT");
        trace.draw(1,4,new CubeKey(0,-21,0),10,true,false,30);
        assertEquals(-1,trace.targets().get(0).nativeMillis());assertTrue(trace.targets().get(0).cancelled());
        trace.workerExited(1,1_000_010);assertEquals(1,trace.targets().get(0).cancellationMillis());
        trace.commit(2,5,false,21);trace.draw(2,5,new CubeKey(0,-21,0),10,true,false,22);
        assertEquals(-1,trace.targets().get(1).nativeMillis(),"an old off-camera cube cannot satisfy nearby draw");
        for(int i=3;i<=200;i++)trace.begin(i,i,0,500,-330,0,64,"NORMAL","ANCHOR");
        assertTrue(trace.targets().size()<=128);assertEquals(200,trace.targets().get(trace.targets().size()-1).targetGeneration());
    }
}
