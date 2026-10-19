package dev.madeinabyss.kernel.lod;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RenderGroupHandoffTest {
    private static RenderGroupHandoff<String> displayed() {
        var groups=new RenderGroupHandoff<String>();groups.beginTarget(1);groups.stage(1,"old");groups.markDrawn(1,"old");groups.promote();return groups;
    }
    // Removing displayed geometry at worker submission or registration reproduces the user's missing whole scene.
    @Test void retainsDisplayedAcrossWorkerAndGpuDelayUntilReplacementActuallyDraws() {
        var groups=displayed();assertNull(groups.beginTarget(2));assertEquals("old",groups.displayed());
        groups.stage(2,"new");assertEquals(2,groups.size());assertFalse(groups.ready());
        assertThrows(IllegalStateException.class,groups::promote);assertEquals("old",groups.displayed());
        groups.markDrawn(2,"new");assertEquals("old",groups.displayed());
        assertEquals("old",groups.promote());assertEquals("new",groups.displayed());assertEquals(1,groups.size());
    }
    // Catches late postRender promoting a stale target after residency or an actual block edit supersedes it.
    @Test void supersedingResidencyOrGeometryRequestCancelsOnlyReplacementAndRejectsLateDraw() {
        var groups=displayed();groups.beginTarget(2);groups.stage(2,"residency");
        assertEquals("residency",groups.beginTarget(3));assertEquals("old",groups.displayed());
        groups.markDrawn(2,"residency");assertFalse(groups.ready());
        groups.stage(3,"edited");groups.markDrawn(2,"residency");assertFalse(groups.ready());
        groups.markDrawn(3,"edited");assertEquals("old",groups.promote());assertEquals("edited",groups.displayed());
    }
    // Catches unbounded pending allocations and duplicate cleanup at preview/session end.
    @Test void boundedPairAndExactCleanupRemainValidAfterDrawBeforePromotion() {
        var groups=displayed();groups.beginTarget(2);groups.stage(2,"new");
        assertThrows(IllegalStateException.class,()->groups.stage(2,"third"));assertEquals(2,groups.size());
        groups.markDrawn(2,"new");assertEquals(List.of("old","new"),groups.clear());
        assertEquals(0,groups.size());assertEquals(List.of(),groups.clear());groups.markDrawn(2,"new");assertFalse(groups.ready());
    }
}
