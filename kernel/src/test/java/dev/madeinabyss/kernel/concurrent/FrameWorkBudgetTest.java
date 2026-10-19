package dev.madeinabyss.kernel.concurrent;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class FrameWorkBudgetTest {
    @Test void resumableWorkStopsAtBudgetAndContinuesNextFrame() {
        var now=new AtomicLong();int completed=0;
        while(completed<10){var budget=new FrameWorkBudget(now::get,3);int thisFrame=0;while(completed<10&&budget.available()){now.incrementAndGet();completed++;thisFrame++;}assertTrue(thisFrame<=3);}
        assertEquals(10,completed);
    }
    @Test void oneLongNativeUploadIsMeasuredAndDoesNotAdmitAnother() {
        var now=new AtomicLong();var budget=new FrameWorkBudget(now::get,3);
        assertTrue(budget.available());now.addAndGet(5);assertFalse(budget.available());assertEquals(5,budget.elapsed());
        assertThrows(IllegalArgumentException.class,()->new FrameWorkBudget(now::get,0));
    }
    @Test void nanoTimeWrapDoesNotSilentlyGrantAnUnlimitedFrame() {
        var now=new AtomicLong(Long.MAX_VALUE-1);var budget=new FrameWorkBudget(now::get,3);
        now.addAndGet(2);assertTrue(budget.available());now.incrementAndGet();assertFalse(budget.available());
    }
}
