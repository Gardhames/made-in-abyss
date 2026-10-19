package dev.madeinabyss.kernel.tick;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.madeinabyss.kernel.tick.SavedCubeTick.Kind.*;

class CubeScheduledTicksTest {
    private static final BlockCoordinate P = new BlockCoordinate(-1, -20000, -17);
    private static final CubeKey K = new CubeKey(-1, -1250, -2);
    private static CubeScheduledTicks.Tick tick(BlockCoordinate p, long due, int priority, long order) {
        return new CubeScheduledTicks.Tick(FLUID, "minecraft:water", p, due, priority, order);
    }
    // Catches local-coordinate identity, replacement of duplicates, and destructive due inspection.
    @Test void fullSignedCoordinatesDoNotAliasAndFirstDuplicateWins() {
        var q = new CubeScheduledTicks(); var first = tick(P, 15, 1, 7);
        assertTrue(q.schedule(first)); assertFalse(q.schedule(tick(P, 2, -3, -9)));
        assertTrue(q.schedule(tick(new BlockCoordinate(-1, -15904, -17), 15, 1, 7)));
        assertTrue(q.schedule(new CubeScheduledTicks.Tick(BLOCK, "minecraft:water", P, 15, 1, 7)));
        assertEquals(3, q.pendingCount()); assertTrue(q.due(14, 10).isEmpty());
        assertEquals(3, q.due(15, 10).size()); assertEquals(3, q.pendingCount());
        assertTrue(q.due(15, 10).contains(first));
    }
    // Catches priority/order inversions and unstable tie order on signed coordinates.
    @Test void dueSelectionUsesTimePriorityOrderAndCoordinates() {
        var q = new CubeScheduledTicks();
        var a = tick(new BlockCoordinate(-2,-20000,-17), 5, 0, 3);
        var b = tick(P, 5, 0, 3); var c = tick(new BlockCoordinate(0,-20000,-17), 5, -1, 9);
        var d = tick(new BlockCoordinate(1,-20000,-17), 4, 3, 99);
        q.schedule(b); q.schedule(a); q.schedule(c); q.schedule(d);
        assertEquals(List.of(d,c,a), q.due(10,3));
        assertEquals(List.of(d,c,a,b), q.due(10,10));
    }
    // Catches consuming parent state before successful dispatch or losing self-reschedules.
    @Test void forkConsumesBeforeSelfScheduleAndCommitsOnlyExplicitly() {
        var q = new CubeScheduledTicks(); var original = tick(P, 10, 0, 0); q.schedule(original);
        var discarded = q.copy(); assertTrue(discarded.consume(original));
        assertTrue(discarded.schedule(tick(P,15,0,1)));
        assertEquals(List.of(original),q.due(10,1));
        var committed = q.copy(); committed.consume(original); committed.schedule(tick(P,15,0,1));
        q.replaceWith(committed); assertTrue(q.due(10,1).isEmpty()); assertEquals(15,q.due(15,1).get(0).triggerTick());
    }
    // Catches lost updates and committing a fork from another queue.
    @Test void staleAndForeignForksAreRejectedWithoutChangingParent() {
        var q = new CubeScheduledTicks(); q.schedule(tick(P,10,0,0)); var fork = q.copy();
        q.schedule(tick(new BlockCoordinate(-2,-20000,-17),11,0,1));
        assertThrows(IllegalStateException.class, () -> q.replaceWith(fork));
        assertThrows(IllegalStateException.class, () -> q.replaceWith(new CubeScheduledTicks().copy()));
        assertEquals(2,q.pendingCount());
    }
    // Catches freezing absolute time, dropping overdue ticks or unloading without removing queue state.
    @Test void unloadFreezesRemainingDelayAcrossClockJumpIncludingOverdue() {
        var s = new SparseCubeSpace(); s.loadEmpty(K); s.scheduledTicks().advanceTime(10);
        s.scheduledTicks().schedule(tick(P,15,0,4));
        var saved = s.unload(K).orElseThrow(); assertEquals(5,saved.ticks().get(0).delay());
        assertEquals(0,s.scheduledTicks().pendingCount()); s.scheduledTicks().advanceTime(1000); s.load(saved);
        assertTrue(s.scheduledTicks().due(1004,1).isEmpty()); assertEquals(1005,s.scheduledTicks().due(1005,1).get(0).triggerTick());
        s.scheduledTicks().advanceTime(1010); assertEquals(-5,s.snapshot(K).orElseThrow().ticks().get(0).delay());
        assertThrows(IllegalArgumentException.class, () -> s.scheduledTicks().advanceTime(1009));
    }
    // Catches noncanonical identifiers, bad priority and mutable/cross-cube/duplicate saved data.
    @Test void savedAndLiveTicksValidateAndSnapshotsDefensivelyFreeze() {
        for (String type : List.of("water","Minecraft:water","minecraft:","minecraft:water ")) {
            assertThrows(IllegalArgumentException.class, () -> new SavedCubeTick(FLUID,type,P,0,0,0));
            assertThrows(IllegalArgumentException.class, () -> new CubeScheduledTicks.Tick(FLUID,type,P,0,0,0));
        }
        assertThrows(IllegalArgumentException.class, () -> new SavedCubeTick(FLUID,"minecraft:water",P,0,4,0));
        var t = new SavedCubeTick(FLUID,"minecraft:water",P,-5,-3,Long.MIN_VALUE);
        var list = new ArrayList<>(List.of(t)); var snap = new CubeSnapshot(K,new int[4096],list); list.clear();
        assertEquals(List.of(t),snap.ticks()); assertThrows(UnsupportedOperationException.class, () -> snap.ticks().clear());
        assertThrows(IllegalArgumentException.class, () -> new CubeSnapshot(K,new int[4096],List.of(t,t)));
        assertThrows(IllegalArgumentException.class, () -> new CubeSnapshot(new CubeKey(0,0,0),new int[4096],List.of(t)));
        var over = new ArrayList<SavedCubeTick>();
        for(int i=0;i<513;i++) over.add(new SavedCubeTick(FLUID,"minecraft:water",new BlockCoordinate(-16+i%16,-20000+i/256,-32+(i/16)%16),0,0,i));
        assertThrows(IllegalArgumentException.class, () -> new CubeSnapshot(K,new int[4096],over));
    }
    // Catches unbounded queue growth and partial mutation on cap failures.
    @Test void perCubeAndGlobalBudgetsRejectWithoutPartialMutation() {
        var q = new CubeScheduledTicks();
        for(int i=0;i<512;i++) q.schedule(tick(new BlockCoordinate(i%16,-20000+i/256,-32+(i/16)%16),1,0,i));
        assertThrows(IllegalArgumentException.class, () -> q.schedule(tick(new BlockCoordinate(0,-19998,-32),1,0,999)));
        assertEquals(512,q.pendingCount());
        var all = new CubeScheduledTicks();
        for(int cube=0;cube<8;cube++) for(int i=0;i<512;i++) all.schedule(tick(new BlockCoordinate(cube*16+i%16,i/256,(i/16)%16),1,0,i));
        assertThrows(IllegalArgumentException.class, () -> all.schedule(tick(new BlockCoordinate(128,0,0),1,0,0)));
        assertEquals(4096,all.pendingCount());
    }
    // Catches native staged writes committing an earlier cell before a later UNKNOWN or invalid ID.
    @Test void batchPrevalidatesAllWritesAndPublishesOneGeneration() {
        var s = new SparseCubeSpace(); s.loadEmpty(K); long generation=s.contentGeneration();
        var writes=new LinkedHashMap<BlockCoordinate,Integer>(); writes.put(P,7); writes.put(new BlockCoordinate(4095,-20000,-17),8);
        assertThrows(IllegalStateException.class, () -> s.applyBatch(writes)); assertEquals(0,s.block(P).orElseThrow()); assertEquals(generation,s.contentGeneration());
        writes.clear(); writes.put(P,7); writes.put(new BlockCoordinate(-2,-20000,-17),-1);
        assertThrows(IllegalArgumentException.class, () -> s.applyBatch(writes)); assertEquals(0,s.block(P).orElseThrow());
        writes.put(new BlockCoordinate(-2,-20000,-17),8); s.applyBatch(writes);
        assertEquals(7,s.block(P).orElseThrow()); assertEquals(8,s.block(new BlockCoordinate(-2,-20000,-17)).orElseThrow()); assertEquals(generation+1,s.contentGeneration());
    }
}

