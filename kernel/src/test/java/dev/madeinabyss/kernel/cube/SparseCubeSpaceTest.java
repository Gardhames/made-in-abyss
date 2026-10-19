package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import org.junit.jupiter.api.Test;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SparseCubeSpaceTest {
    @Test void successfulResidentMutationsInvalidateCapturedGeneration() {
        var space = new SparseCubeSpace();
        var position = new BlockCoordinate(-17, -20001, -1);
        var key = new CubeKey(-2, -1251, -1);
        long absentGeneration = space.contentGeneration();

        assertTrue(space.loadEmpty(key));
        assertTrue(space.contentGeneration() > absentGeneration, "Loading known AIR must invalidate an unknown-data cache");
        long airGeneration = space.contentGeneration();
        var airSnapshot = space.snapshot(key).orElseThrow();

        space.setBlock(position, 15);
        assertEquals(15, space.block(position).orElseThrow());
        assertTrue(space.contentGeneration() > airGeneration, "A cache captured before an edit must become stale");
        assertEquals(0, airSnapshot.states()[4095]);
        long editedGeneration = space.contentGeneration();

        assertTrue(space.unload(key).isPresent());
        assertEquals(OptionalInt.empty(), space.block(position));
        assertTrue(space.contentGeneration() > editedGeneration, "Unloading must invalidate a known-data cache");
    }

    @Test void restoringSnapshotInvalidatesPreviouslyAbsentContent() {
        var space = new SparseCubeSpace();
        var position = new BlockCoordinate(-16, -20000, -16);
        var key = new CubeKey(-1, -1250, -1);
        int[] states = new int[4096];
        states[0] = 7;
        long absentGeneration = space.contentGeneration();

        space.load(new CubeSnapshot(key, states));

        assertEquals(7, space.block(position).orElseThrow());
        assertTrue(space.contentGeneration() > absentGeneration, "Snapshot load must invalidate the earlier unknown result");
    }

    @Test void failedAndNoOpMutationsKeepCapturedGenerationCurrent() {
        var space = new SparseCubeSpace();
        var position = new BlockCoordinate(0, -20000, 0);
        var key = new CubeKey(0, -1250, 0);
        space.loadEmpty(key);
        space.setBlock(position, 8);
        long capturedGeneration = space.contentGeneration();

        space.setBlock(position, 8);
        assertEquals(capturedGeneration, space.contentGeneration(), "Same-state edit is not a content change");
        assertFalse(space.loadEmpty(key));
        assertEquals(capturedGeneration, space.contentGeneration());
        assertTrue(space.unload(new CubeKey(0, -994, 0)).isEmpty());
        assertEquals(capturedGeneration, space.contentGeneration());
        assertThrows(IllegalStateException.class, () -> space.load(new CubeSnapshot(key, new int[4096])));
        assertThrows(IllegalArgumentException.class, () -> space.setBlock(position, -1));
        assertThrows(IllegalStateException.class, () -> space.setBlock(new BlockCoordinate(0, -15904, 0), 9));
        assertEquals(capturedGeneration, space.contentGeneration(), "Rejected mutations cannot invalidate valid cached content");
        space.snapshot(key).orElseThrow().states()[0] = 100;
        space.residentKeys();
        assertEquals(capturedGeneration, space.contentGeneration(), "Read-only snapshots cannot dirty residency");
        assertEquals(8, space.block(position).orElseThrow());
    }

    @Test void residentKeysAreAnImmutableSnapshotOfIndependentDepths() {
        var space = new SparseCubeSpace();
        var first = new CubeKey(-1, -1250, -1);
        var second = new CubeKey(-1, -994, -1);
        space.loadEmpty(first);
        Set<CubeKey> captured = space.residentKeys();
        assertEquals(Set.of(first), captured);
        assertThrows(UnsupportedOperationException.class, () -> captured.add(second));
        assertThrows(UnsupportedOperationException.class, () -> captured.remove(first));

        space.loadEmpty(second);
        space.unload(first);

        assertEquals(Set.of(first), captured, "Later residency changes must not mutate a captured dependency set");
        assertEquals(Set.of(second), space.residentKeys());
        assertEquals(1, space.loadedCubeCount());
    }

    @Test void negativeBoundaryHasIndependentHandCheckedKey() {
        assertEquals(new CubeKey(-2, -1251, 2), CubeKey.fromBlock(new BlockCoordinate(-17, -20001, 47)));
        assertEquals(new CubeKey(-1, -1250, 0), CubeKey.fromBlock(new BlockCoordinate(-1, -20000, 0)));
    }

    @Test void absentCubeIsUnknownAndLoadedEmptyCubeIsKnownAir() {
        var space = new SparseCubeSpace();
        var position = new BlockCoordinate(-1, -20000, -1);
        assertEquals(OptionalInt.empty(), space.block(position));
        space.loadEmpty(CubeKey.fromBlock(position));
        assertEquals(OptionalInt.of(0), space.block(position));
        assertEquals(OptionalInt.empty(), space.block(new BlockCoordinate(-1, -20001, -1)));
    }

    @Test void fourDepthsAtSameHorizontalPositionDoNotAlias() {
        var space = new SparseCubeSpace();
        int[] depths = {64, -4096, -15904, -20000};
        for (int index = 0; index < depths.length; index++) {
            var position = new BlockCoordinate(-17, depths[index], 31);
            space.loadEmpty(CubeKey.fromBlock(position));
            space.setBlock(position, 100 + index);
        }
        assertEquals(4, space.loadedCubeCount());
        for (int index = 0; index < depths.length; index++) {
            assertEquals(100 + index, space.block(new BlockCoordinate(-17, depths[index], 31)).orElseThrow());
        }
    }

    @Test void adjacentCellsAcrossAllSectionFacesRemainDistinct() {
        var space = new SparseCubeSpace();
        BlockCoordinate[] positions = {
                new BlockCoordinate(-17, -20001, -17), new BlockCoordinate(-16, -20001, -17),
                new BlockCoordinate(-17, -20000, -17), new BlockCoordinate(-17, -20001, -16),
                new BlockCoordinate(-1, -19985, -1), new BlockCoordinate(-16, -20000, -16)};
        for (int index = 0; index < positions.length; index++) {
            space.loadEmpty(CubeKey.fromBlock(positions[index]));
            space.setBlock(positions[index], 1 + index);
        }
        for (int index = 0; index < positions.length; index++) {
            assertEquals(1 + index, space.block(positions[index]).orElseThrow(), positions[index].toString());
        }
    }

    @Test void loadingEmptyAgainDoesNotEraseExistingConstruction() {
        var space = new SparseCubeSpace();
        var position = new BlockCoordinate(0, -20000, 0);
        var key = CubeKey.fromBlock(position);
        assertTrue(space.loadEmpty(key));
        space.setBlock(position, 7);
        assertFalse(space.loadEmpty(key));
        assertEquals(7, space.block(position).orElseThrow());
        assertThrows(IllegalStateException.class, () -> space.load(new CubeSnapshot(key, new int[4096])));
        assertEquals(7, space.block(position).orElseThrow());
    }

    @Test void editingUnloadedDataFailsWithoutCreatingAirCube() {
        var space = new SparseCubeSpace();
        assertThrows(IllegalStateException.class, () -> space.setBlock(new BlockCoordinate(0, -20000, 0), 2));
        assertEquals(0, space.loadedCubeCount());
    }

    @Test void unloadSnapshotRemainsIndependentOfSubsequentEdits() {
        var space = new SparseCubeSpace();
        var position = new BlockCoordinate(1, -19999, 1);
        var key = CubeKey.fromBlock(position);
        space.loadEmpty(key);
        space.setBlock(position, 9);
        var beforeEdit = space.snapshot(key).orElseThrow();
        space.setBlock(position, 10);
        var unloaded = space.unload(key).orElseThrow();
        assertEquals(OptionalInt.empty(), space.block(position));
        assertEquals(0, space.loadedCubeCount());
        assertTrue(space.unload(key).isEmpty());
        space.load(beforeEdit);
        assertEquals(9, space.block(position).orElseThrow());
        space.unload(key);
        space.load(unloaded);
        assertEquals(10, space.block(position).orElseThrow());
    }

    @Test void snapshotArraysCannotMutateSavedDataFromOutside() {
        int[] input = new int[4096];
        input[17] = 3;
        var snapshot = new CubeSnapshot(new CubeKey(0, -1250, 0), input);
        input[17] = 4;
        assertEquals(3, snapshot.states()[17]);
        int[] output = snapshot.states();
        output[17] = 5;
        assertEquals(3, snapshot.states()[17]);
    }

    @Test void invalidStatesAreRejectedBeforeChangingResidentData() {
        assertThrows(IllegalArgumentException.class, () -> new CubeSnapshot(new CubeKey(0, 0, 0), new int[4095]));
        var space = new SparseCubeSpace();
        var position = new BlockCoordinate(0, -20000, 0);
        space.loadEmpty(CubeKey.fromBlock(position));
        space.setBlock(position, 8);
        assertThrows(IllegalArgumentException.class, () -> space.setBlock(position, -1));
        assertEquals(8, space.block(position).orElseThrow());
        int[] states = new int[4096];
        states[4000] = -1;
        assertThrows(IllegalArgumentException.class, () -> new CubeSnapshot(new CubeKey(0, 0, 0), states));
    }

    @Test void extremeIntegerBlockCoordinatesKeepIndependentStorage() {
        var space = new SparseCubeSpace();
        var minimum = new BlockCoordinate(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        var maximum = new BlockCoordinate(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        space.loadEmpty(CubeKey.fromBlock(minimum));
        space.loadEmpty(CubeKey.fromBlock(maximum));
        space.setBlock(minimum, 10);
        space.setBlock(maximum, 11);
        assertEquals(10, space.block(minimum).orElseThrow());
        assertEquals(11, space.block(maximum).orElseThrow());
    }
}
