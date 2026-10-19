package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import dev.madeinabyss.kernel.tick.SavedCubeTick;
import dev.madeinabyss.kernel.tick.CubeScheduledTicks;
import static org.junit.jupiter.api.Assertions.*;

class FileCubeStoreTest {
    @TempDir Path root;

    // Catches silently dropping scheduled work when using the legacy numeric diagnostic format.
    @Test void numericFormatRefusesScheduledTicksAndPreservesPreviousFrame() throws IOException {
        var store = new FileCubeStore(root); var key = new CubeKey(-1,-1250,-2);
        var position = new BlockCoordinate(-1,-20000,-17);
        var oldStates = new int[4096]; oldStates[4095]=31;
        store.write(new CubeSnapshot(key,oldStates)); byte[] previous=Files.readAllBytes(store.path(key));
        var changedStates=oldStates.clone(); changedStates[4095]=47;
        var tick=new SavedCubeTick(SavedCubeTick.Kind.FLUID,"minecraft:water",position,5,0,3);
        assertThrows(IOException.class, () -> store.write(new CubeSnapshot(key,changedStates,List.of(tick))));
        assertArrayEquals(previous,Files.readAllBytes(store.path(key)));
        assertArrayEquals(oldStates,store.read(key).orElseThrow().states());
    }

    // Catches losing resident edits/queued ticks or unloading after a rejected diagnostic save.
    @Test void rejectedScheduledTickSaveLeavesResidentStateAndQueueIntact() throws IOException {
        var store=new FileCubeStore(root); var key=new CubeKey(-1,-1250,-2);
        store.write(new CubeSnapshot(key,new int[4096])); byte[] previous=Files.readAllBytes(store.path(key));
        var space=new SparseCubeSpace(); var manager=new PersistentCubeSpace(space,store);
        manager.load(key); var position=new BlockCoordinate(-1,-20000,-17); space.setBlock(position,47);
        var tick=new CubeScheduledTicks.Tick(SavedCubeTick.Kind.FLUID,"minecraft:water",position,15,0,3);
        space.scheduledTicks().advanceTime(10); space.scheduledTicks().schedule(tick);
        assertThrows(IOException.class, () -> manager.saveAndUnload(key));
        assertEquals(47,space.block(position).orElseThrow()); assertTrue(space.isLoaded(key));
        assertEquals(1,space.scheduledTicks().pendingCount()); assertEquals(List.of(tick),space.scheduledTicks().due(15,1));
        assertArrayEquals(previous,Files.readAllBytes(store.path(key)));
    }

    @Test void freshSpaceRestoresFourDepthsAfterSaveAndUnload() throws IOException {
        var first = new SparseCubeSpace();
        var writer = new PersistentCubeSpace(first, new FileCubeStore(root));
        int[] depths = {64, -4096, -15904, -20000};
        for (int index = 0; index < depths.length; index++) {
            var position = new BlockCoordinate(-17, depths[index], -1);
            writer.load(CubeKey.fromBlock(position));
            first.setBlock(position, 101 + index);
            assertTrue(writer.saveAndUnload(CubeKey.fromBlock(position)));
        }
        assertEquals(0, first.loadedCubeCount());
        var second = new SparseCubeSpace();
        var reader = new PersistentCubeSpace(second, new FileCubeStore(root));
        for (int index = 0; index < depths.length; index++) {
            var position = new BlockCoordinate(-17, depths[index], -1);
            assertTrue(second.block(position).isEmpty());
            reader.load(CubeKey.fromBlock(position));
            assertEquals(101 + index, second.block(position).orElseThrow());
        }
        assertEquals(4, second.loadedCubeCount());
    }

    @Test void reloadingAlreadyResidentCubePreservesUnsavedEdit() throws IOException {
        var space = new SparseCubeSpace();
        var manager = new PersistentCubeSpace(space, new FileCubeStore(root));
        var position = new BlockCoordinate(0, -20000, 0);
        var key = CubeKey.fromBlock(position);
        manager.load(key);
        space.setBlock(position, 7);
        manager.load(key);
        assertEquals(7, space.block(position).orElseThrow());
        assertEquals(1, space.loadedCubeCount());
    }

    @Test void savingOverExistingSnapshotRestoresLatestConstruction() throws IOException {
        var store = new FileCubeStore(root);
        var key = new CubeKey(-2, -1250, -1);
        int[] first = new int[4096];
        first[0] = 7;
        store.write(new CubeSnapshot(key, first));
        first[0] = 8;
        first[4095] = 9;
        store.write(new CubeSnapshot(key, first));
        assertArrayEquals(first, new FileCubeStore(root).read(key).orElseThrow().states());
    }

    @Test void failedDiskWriteKeepsCubeResidentWithItsEdits() throws IOException {
        var notDirectory = root.resolve("file-instead-of-directory");
        Files.writeString(notDirectory, "existing data");
        var space = new SparseCubeSpace();
        var manager = new PersistentCubeSpace(space, new FileCubeStore(notDirectory));
        var position = new BlockCoordinate(0, -20000, 0);
        var key = CubeKey.fromBlock(position);
        space.loadEmpty(key);
        space.setBlock(position, 17);
        assertThrows(IOException.class, () -> manager.saveAndUnload(key));
        assertEquals(17, space.block(position).orElseThrow());
        assertEquals(1, space.loadedCubeCount());
        assertEquals("existing data", Files.readString(notDirectory));
    }

    @Test void payloadCorruptionIsRejectedWithoutRegeneratingAir() throws IOException {
        var store = new FileCubeStore(root);
        var key = new CubeKey(0, -1250, 0);
        int[] states = new int[4096];
        Arrays.fill(states, 9);
        store.write(new CubeSnapshot(key, states));
        byte[] corrupt = Files.readAllBytes(store.path(key));
        corrupt[corrupt.length - 8] ^= 1;
        Files.write(store.path(key), corrupt);
        var space = new SparseCubeSpace();
        var manager = new PersistentCubeSpace(space, store);
        assertThrows(IOException.class, () -> manager.load(key));
        assertFalse(space.isLoaded(key));
        assertArrayEquals(corrupt, Files.readAllBytes(store.path(key)));
    }

    @Test void truncatedSnapshotIsRejectedRatherThanTreatedAsMissing() throws IOException {
        var store = new FileCubeStore(root);
        var key = new CubeKey(0, -1250, 0);
        store.write(new CubeSnapshot(key, new int[4096]));
        Files.write(store.path(key), Arrays.copyOf(Files.readAllBytes(store.path(key)), 20));
        assertThrows(IOException.class, () -> store.read(key));
    }

    @Test void snapshotRenamedToAnotherCoordinateCannotLoadThere() throws IOException {
        var store = new FileCubeStore(root);
        var key = new CubeKey(0, -1250, 0);
        var other = new CubeKey(0, -994, 0);
        store.write(new CubeSnapshot(key, new int[4096]));
        Files.copy(store.path(key), store.path(other));
        assertThrows(IOException.class, () -> store.read(other));
    }

    @Test void unknownFormatVersionDoesNotLoadOrModifyData() throws IOException {
        var store = new FileCubeStore(root);
        var key = new CubeKey(0, -1250, 0);
        store.write(new CubeSnapshot(key, new int[4096]));
        byte[] future = Files.readAllBytes(store.path(key));
        // Format contract: magic then big-endian int version. Version migration must be explicit.
        future[7] = 99;
        Files.write(store.path(key), future);
        assertThrows(IOException.class, () -> store.read(key));
        assertArrayEquals(future, Files.readAllBytes(store.path(key)));
    }

    @Test void missingSnapshotAndUnloadedSaveAreExplicitlyAbsent() throws IOException {
        var store = new FileCubeStore(root);
        var key = new CubeKey(0, -1250, 0);
        assertTrue(store.read(key).isEmpty());
        assertFalse(new PersistentCubeSpace(new SparseCubeSpace(), store).saveAndUnload(key));
        assertFalse(Files.exists(store.path(key)));
    }
}
