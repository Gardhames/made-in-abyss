package dev.madeinabyss.kernel.concurrent;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class FrameCostTraceTest {
    @Test void nestedTimingsAreInclusiveAndExclusiveWithoutDoubleCounting() {
        var now=new AtomicLong();var trace=new FrameCostTrace(now::get,3,4);
        trace.finishFrame(100,1,0);int outer=trace.begin(0);now.addAndGet(2);
        int inner=trace.begin(1);now.addAndGet(5);trace.end(inner);now.addAndGet(3);trace.end(outer);
        trace.finishFrame(101,1,2);var frame=trace.snapshot(0).get(0);
        assertEquals(10,frame.durationNanos());
        assertArrayEquals(new long[]{10,5,0},frame.inclusiveNanos());
        assertArrayEquals(new long[]{5,5,0},frame.exclusiveNanos());
        assertArrayEquals(new int[]{1,1,0},frame.calls());
    }
    @Test void workBeforeRenderAndZeroWorkFramesRemainInTheFrameDenominator() {
        var now=new AtomicLong(-100);var trace=new FrameCostTrace(now::get,2,8);
        trace.finishFrame(100,9,0);int token=trace.begin(0);now.addAndGet(4);trace.end(token);now.addAndGet(6);
        trace.finishFrame(101,10,1);now.addAndGet(20);trace.finishFrame(102,10,2);
        var frames=trace.snapshot(0);assertEquals(2,frames.size());
        assertEquals(10,frames.get(0).durationNanos());assertEquals(4,frames.get(0).exclusiveNanos()[0]);
        assertEquals(20,frames.get(1).durationNanos());assertEquals(0,frames.get(1).exclusiveNanos()[0]);
        assertEquals(10,frames.get(0).target());assertEquals(1,frames.get(0).state());
    }
    @Test void boundedRingAndSnapshotCursorDoNotDuplicateOrExposeMutableStorage() {
        var now=new AtomicLong();var trace=new FrameCostTrace(now::get,1,2);trace.finishFrame(0,0,0);
        for(int i=1;i<=4;i++){int t=trace.begin(0);now.addAndGet(i);trace.end(t);trace.finishFrame(i,i,i);}
        var frames=trace.snapshot(0);assertEquals(2,frames.size());assertEquals(3,frames.get(0).sequence());
        frames.get(0).exclusiveNanos()[0]=999;
        assertEquals(3,trace.snapshot(0).get(0).exclusiveNanos()[0]);
        assertEquals(1,trace.snapshot(3).size());assertTrue(trace.snapshot(4).isEmpty());
    }
    @Test void badNestingIsRefusedAndClockWrapPreservesTheDuration() {
        var now=new AtomicLong(Long.MAX_VALUE-3);var trace=new FrameCostTrace(now::get,2,2);trace.finishFrame(0,0,0);
        int a=trace.begin(0),b=trace.begin(1);assertThrows(IllegalStateException.class,()->trace.end(a));
        now.addAndGet(8);trace.end(b);trace.end(a);trace.finishFrame(1,0,0);
        assertEquals(8,trace.snapshot(0).get(0).durationNanos());
        assertArrayEquals(new long[]{0,8},trace.snapshot(0).get(0).exclusiveNanos());
    }
}
