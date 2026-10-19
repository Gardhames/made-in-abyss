package dev.madeinabyss.kernel.ticket;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CubeTicketsTest {
    // Catches collapsing all depths into a horizontal chunk ticket.
    @Test void fourPlayersAtSameXZKeepIndependentDepthWindows() {
        var tickets = new CubeTickets(27, 108, 4);
        int[] depths = {64, -4096, -15904, -20000};
        for (int i = 0; i < depths.length; i++) {
            var delta = tickets.move("p" + i, new BlockCoordinate(0, depths[i], 0), 1, 1);
            assertEquals(27, delta.added().size());
            assertTrue(delta.removed().isEmpty());
        }
        assertEquals(108, tickets.snapshot().size());
        assertEquals(4, tickets.ownerCount());
        for (int sectionY : new int[]{4, -256, -994, -1250}) {
            assertEquals(1, tickets.snapshot().get(new CubeKey(0, sectionY, 0)));
        }
        assertFalse(tickets.snapshot().containsKey(new CubeKey(0, -600, 0)));
    }

    // Catches per-owner unload that ignores another owner's demand.
    @Test void lastOwnerAloneReleasesSharedCube() {
        var tickets = new CubeTickets(1, 2, 2);
        var key = new CubeKey(-1, -1250, 0);
        var position = new BlockCoordinate(-1, -20000, 0);
        assertEquals(Set.of(key), tickets.move("a", position, 0, 0).added());
        assertTrue(tickets.move("b", position, 0, 0).added().isEmpty());
        assertEquals(Map.of(key, 2), tickets.snapshot());
        assertTrue(tickets.remove("a").removed().isEmpty());
        assertEquals(Map.of(key, 1), tickets.snapshot());
        assertEquals(Set.of(key), tickets.remove("b").removed());
        assertTrue(tickets.snapshot().isEmpty());
        assertEquals(0, tickets.ownerCount());
        assertEquals(new CubeTickets.Delta(Set.of(), Set.of()), tickets.remove("b"));
    }

    // Catches truncating negative block coordinates or computing a whole-world delta.
    @Test void negativeSectionCrossingProducesOnlyTheTwoChangedSlabs() {
        var tickets = new CubeTickets(27, 27, 1);
        tickets.move("a", new BlockCoordinate(-1, -1, -1), 1, 0);
        var delta = tickets.move("a", new BlockCoordinate(-17, -1, -1), 1, 0);
        assertEquals(Set.of(new CubeKey(-3, -1, -2), new CubeKey(-3, -1, -1),
                new CubeKey(-3, -1, 0)), delta.added());
        assertEquals(Set.of(new CubeKey(0, -1, -2), new CubeKey(0, -1, -1),
                new CubeKey(0, -1, 0)), delta.removed());
        assertEquals(9, tickets.snapshot().size());
        assertEquals(new CubeTickets.Delta(Set.of(), Set.of()),
                tickets.move("a", new BlockCoordinate(-32, -16, -16), 1, 0));
    }

    // Catches accumulating traversed cubes or retaining an entire tall column.
    @Test void fastDescentKeepsOnlyTheCurrentWindow() {
        var tickets = new CubeTickets(45, 45, 1);
        tickets.move("a", new BlockCoordinate(0, 64, 0), 1, 2);
        var delta = tickets.move("a", new BlockCoordinate(0, -20000, 0), 1, 2);
        assertEquals(45, delta.added().size());
        assertEquals(45, delta.removed().size());
        assertEquals(45, tickets.snapshot().size());
        assertTrue(tickets.snapshot().keySet().stream().allMatch(k -> k.y() >= -1252 && k.y() <= -1248));
        assertEquals(45, tickets.remove("a").removed().size());
        assertTrue(tickets.snapshot().isEmpty());
    }

    // Catches using the owner diff as global diff when windows partially overlap.
    @Test void movingAnOwnerPreservesOtherOwnersOverlapAndRefcounts() {
        var tickets = new CubeTickets(3, 6, 2);
        tickets.move("a", new BlockCoordinate(0, 0, 0), 0, 1);
        tickets.move("b", new BlockCoordinate(0, 16, 0), 0, 1);
        assertEquals(Map.of(new CubeKey(0, -1, 0), 1, new CubeKey(0, 0, 0), 2,
                new CubeKey(0, 1, 0), 2, new CubeKey(0, 2, 0), 1), tickets.snapshot());
        var delta = tickets.move("a", new BlockCoordinate(0, 48, 0), 0, 1);
        assertEquals(Set.of(new CubeKey(0, 3, 0), new CubeKey(0, 4, 0)), delta.added());
        assertEquals(Set.of(new CubeKey(0, -1, 0)), delta.removed());
        assertEquals(2, tickets.snapshot().get(new CubeKey(0, 2, 0)));
        assertEquals(1, tickets.snapshot().get(new CubeKey(0, 0, 0)));
    }

    // Catches leaked mutable views, including views changed by later moves.
    @Test void snapshotsAndDeltasAreImmutableDetachedValues() {
        var tickets = new CubeTickets(1, 2, 1);
        var first = new CubeKey(0, 0, 0);
        var delta = tickets.move("a", new BlockCoordinate(0, 0, 0), 0, 0);
        var snapshot = tickets.snapshot();
        var window = tickets.ownerWindow("a");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertThrows(UnsupportedOperationException.class, () -> window.clear());
        assertThrows(UnsupportedOperationException.class, () -> delta.added().clear());
        assertThrows(UnsupportedOperationException.class, () -> delta.removed().add(first));
        tickets.move("a", new BlockCoordinate(16, 0, 0), 0, 0);
        assertEquals(Map.of(first, 1), snapshot);
        assertEquals(Set.of(first), window);
        assertEquals(Set.of(first), delta.added());
        assertEquals(Set.of(), tickets.ownerWindow("absent"));
    }

    // Catches committing partial refcounts before validation, or integer volume overflow.
    @Test void rejectedMovesLeaveExistingDemandUnchanged() {
        var tickets = new CubeTickets(27, 27, 2);
        var position = new BlockCoordinate(0, -20000, 0);
        tickets.move("a", position, 1, 1);
        var before = tickets.snapshot();
        assertThrows(IllegalArgumentException.class, () -> tickets.move("a", position, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> tickets.move("a", position, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> tickets.move("a", position, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> tickets.move("a", position, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> tickets.move("a", new BlockCoordinate(Integer.MAX_VALUE, 0, 0), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> tickets.move("a", new BlockCoordinate(0, Integer.MIN_VALUE, 0), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> tickets.move("b", new BlockCoordinate(0, 64, 0), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> tickets.move(" ", position, 0, 0));
        assertThrows(NullPointerException.class, () -> tickets.move(null, position, 0, 0));
        assertThrows(NullPointerException.class, () -> tickets.move("a", null, 0, 0));
        assertEquals(before, tickets.snapshot());
        assertEquals(before.keySet(), tickets.ownerWindow("a"));
        assertEquals(1, tickets.ownerCount());
        assertTrue(tickets.ownerWindow("b").isEmpty());
    }

    // Catches allowing unbounded owner maps despite bounded active union.
    @Test void ownerBudgetRejectsANewOwnerButAllowsTheExistingOwnerToMove() {
        var tickets = new CubeTickets(1, 1, 1);
        tickets.move("a", new BlockCoordinate(0, 0, 0), 0, 0);
        assertThrows(IllegalArgumentException.class, () -> tickets.move("b", new BlockCoordinate(0, 0, 0), 0, 0));
        tickets.move("a", new BlockCoordinate(0, -20000, 0), 0, 0);
        assertEquals(Map.of(new CubeKey(0, -1250, 0), 1), tickets.snapshot());
    }

    // Catches off-by-one domain guards rejecting legal outermost signed-int block sections.
    @Test void zeroRadiusAcceptsBothSignedIntBlockExtremes() {
        var tickets = new CubeTickets(1, 2, 2);
        tickets.move("min", new BlockCoordinate(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE), 0, 0);
        tickets.move("max", new BlockCoordinate(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), 0, 0);
        assertEquals(Map.of(new CubeKey(-134217728, -134217728, -134217728), 1,
                new CubeKey(134217727, 134217727, 134217727), 1), tickets.snapshot());
    }

    // Catches invalid budgets silently creating an unlimited or unusable manager.
    @Test void invalidBudgetsAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new CubeTickets(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CubeTickets(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CubeTickets(1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CubeTickets(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
