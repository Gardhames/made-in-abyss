package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.tick.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static dev.madeinabyss.kernel.tick.SavedCubeTick.Kind.FLUID;

class CubeTickResidencyTest {
    static final BlockCoordinate P = new BlockCoordinate(-1,-20000,-17);
    static final CubeKey K = new CubeKey(-1,-1250,-2);
    static CubeSnapshot filled(CubeKey k) {
        var ticks = new ArrayList<SavedCubeTick>();
        for(int i=0;i<512;i++) ticks.add(new SavedCubeTick(FLUID,"minecraft:water",new BlockCoordinate(k.x()*16+i%16,k.y()*16+i/256,k.z()*16+(i/16)%16),5,0,i));
        return new CubeSnapshot(k,new int[4096],ticks);
    }
    // Catches double activation for shared owners or countdown while cube is absent.
    @Test void sharedOwnersLoadOnceAndCleanRestartRestoresFrozenDelay() throws IOException {
        var store=new MemoryStore(); var s=new CubeResidencyService(store,CubeTickResidencyTest::filled,1,1,2);
        s.space().scheduledTicks().advanceTime(10); s.move("a",P,0,0); s.move("b",P,0,0);
        assertEquals(512,s.space().scheduledTicks().pendingCount()); s.release("a");
        assertEquals(512,s.space().scheduledTicks().pendingCount()); s.release("b"); assertEquals(0,s.space().scheduledTicks().pendingCount());
        var fresh=new CubeResidencyService(store,k->{throw new AssertionError("no regeneration");},1,1,1);
        fresh.space().scheduledTicks().advanceTime(1000); fresh.move("a",P,0,0);
        assertTrue(fresh.space().scheduledTicks().due(1004,1).isEmpty()); assertEquals(1005,fresh.space().scheduledTicks().due(1005,1).get(0).triggerTick());
    }
    // Catches transient queue cap failures when final union fits exactly.
    @Test void fullGlobalQueueCanAtomicallyMoveWithoutTransientOverflow() throws IOException {
        var s=new CubeResidencyService(new MemoryStore(),CubeTickResidencyTest::filled,1,8,8);
        for(int i=0;i<8;i++) s.move("p"+i,new BlockCoordinate(i*16,0,0),0,0);
        assertEquals(4096,s.space().scheduledTicks().pendingCount()); s.move("p0",new BlockCoordinate(128,0,0),0,0);
        assertEquals(4096,s.space().scheduledTicks().pendingCount()); assertFalse(s.space().isLoaded(new CubeKey(0,0,0))); assertTrue(s.space().isLoaded(new CubeKey(8,0,0)));
    }
    // Catches partial incoming publication after final-union queue validation failure.
    @Test void globalBudgetFailurePreservesDemandResidentDataAndQueue() throws IOException {
        var s=new CubeResidencyService(new MemoryStore(),CubeTickResidencyTest::filled,1,9,9);
        for(int i=0;i<8;i++) s.move("p"+i,new BlockCoordinate(i*16,0,0),0,0);
        var before=s.snapshot();
        assertThrows(IllegalArgumentException.class,()->s.move("extra",new BlockCoordinate(128,0,0),0,0));
        assertEquals(before,s.snapshot()); assertEquals(8,s.space().loadedCubeCount()); assertEquals(4096,s.space().scheduledTicks().pendingCount());
        assertTrue(s.ownerWindow("extra").isEmpty());
    }
    // Catches publishing early incoming cubes while a later snapshot has overflowing saved delay.
    @Test void invalidIncomingTimeLeavesEntirePriorResidencyUnchanged() throws IOException {
        var s=new CubeResidencyService(new MemoryStore(),k -> {
            if(k.x()==1) return new CubeSnapshot(k,new int[4096],List.of(new SavedCubeTick(FLUID,"minecraft:water",new BlockCoordinate(16,k.y()*16,k.z()*16),Long.MAX_VALUE,0,0)));
            return filled(k);
        },27,27,1);
        s.move("a",new BlockCoordinate(-64,0,0),0,0); var before=s.snapshot(); s.space().scheduledTicks().advanceTime(1);
        assertThrows(ArithmeticException.class,()->s.move("a",new BlockCoordinate(0,0,0),1,0));
        assertEquals(before,s.snapshot()); assertEquals(1,s.space().loadedCubeCount()); assertEquals(512,s.space().scheduledTicks().pendingCount());
    }
    static class MemoryStore implements CubeStore {
        final Map<CubeKey,CubeSnapshot> data=new HashMap<>();
        public Optional<CubeSnapshot> read(CubeKey key){return Optional.ofNullable(data.get(key));}
        public void write(CubeSnapshot snapshot){data.put(snapshot.key(),snapshot);}
    }
}
