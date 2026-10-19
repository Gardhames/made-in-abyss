package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.tick.SavedCubeTick;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CubeBlockEntitiesTest {
    static final BlockCoordinate P = new BlockCoordinate(-1,-20000,-17);
    static final CubeKey K = CubeKey.fromBlock(P);
    static SavedCubeBlockEntity saved(BlockCoordinate p,int value) {
        return new SavedCubeBlockEntity(p,"minecraft:chest",new byte[]{(byte)value});
    }
    static List<SavedCubeBlockEntity> filled(CubeKey key,int count) {
        var result=new ArrayList<SavedCubeBlockEntity>();
        for(int i=0;i<count;i++) result.add(saved(new BlockCoordinate(key.x()*16+i%16,key.y()*16,key.z()*16+i/16),i));
        return result;
    }
    @Test void bytesAndListAreImmutableWithValueEquality() {
        byte[] bytes={1,2}; var entity=new SavedCubeBlockEntity(P,"minecraft:chest",bytes);
        bytes[0]=99; var read=entity.data(); read[1]=99;
        var equal=new SavedCubeBlockEntity(P,"minecraft:chest",new byte[]{1,2});
        assertEquals(equal,entity); assertEquals(equal.hashCode(),entity.hashCode()); assertArrayEquals(new byte[]{1,2},entity.data());
        var list=new ArrayList<>(List.of(entity)); var snapshot=new CubeSnapshot(K,new int[4096],List.of(),list); list.clear();
        assertEquals(List.of(entity),snapshot.blockEntities()); assertThrows(UnsupportedOperationException.class,()->snapshot.blockEntities().clear());
    }
    @Test void strictTypesPayloadSizeAndOldConstructors() {
        for(String type:List.of("chest","Minecraft:chest","minecraft:",":chest","minecraft:bad type","minecraft:chest\n"))
            assertThrows(IllegalArgumentException.class,()->new SavedCubeBlockEntity(P,type,new byte[0]));
        assertEquals(65536,new SavedCubeBlockEntity(P,"minecraft:chest",new byte[65536]).data().length);
        assertThrows(IllegalArgumentException.class,()->new SavedCubeBlockEntity(P,"minecraft:chest",new byte[65537]));
        assertTrue(new CubeSnapshot(K,new int[4096]).blockEntities().isEmpty());
        assertTrue(new CubeSnapshot(K,new int[4096],List.of()).blockEntities().isEmpty());
    }
    @Test void snapshotValidatesNegativeCubeEdgesDuplicatesAndBudget() {
        var a=saved(new BlockCoordinate(-16,-20000,-32),1); var b=saved(new BlockCoordinate(-1,-19985,-17),2);
        assertEquals(2,new CubeSnapshot(K,new int[4096],List.of(),List.of(a,b)).blockEntities().size());
        for(var p:List.of(new BlockCoordinate(0,-20000,-17),new BlockCoordinate(-1,-20001,-17),new BlockCoordinate(-1,-19984,-17),new BlockCoordinate(-1,-20000,-16)))
            assertThrows(IllegalArgumentException.class,()->new CubeSnapshot(K,new int[4096],List.of(),List.of(saved(p,1))));
        assertThrows(IllegalArgumentException.class,()->new CubeSnapshot(K,new int[4096],List.of(),List.of(a,saved(a.position(),2))));
        assertEquals(64,new CubeSnapshot(K,new int[4096],List.of(),filled(K,64)).blockEntities().size());
        assertThrows(IllegalArgumentException.class,()->new CubeSnapshot(K,new int[4096],List.of(),filled(K,65)));
    }
    @Test void placementEditRemovalAndUnloadPreserveStateAndPayload() {
        var s=new SparseCubeSpace(); s.loadEmpty(K); var e=saved(P,1); long start=s.contentGeneration();
        s.applyBlockEntity(P,7,e); assertEquals(start+1,s.contentGeneration()); assertEquals(7,s.block(P).orElseThrow()); assertEquals(e,s.blockEntity(P).orElseThrow());
        s.setBlockEntity(saved(P,2)); assertEquals(start+2,s.contentGeneration());
        s.setBlock(P,7); assertEquals(start+2,s.contentGeneration()); assertEquals(saved(P,2),s.blockEntity(P).orElseThrow());
        var snapshot=s.unload(K).orElseThrow(); assertEquals(List.of(saved(P,2)),snapshot.blockEntities()); assertTrue(s.block(P).isEmpty()); assertTrue(s.blockEntity(P).isEmpty());
        s.load(snapshot); s.removeBlockEntity(P); assertTrue(s.blockEntity(P).isEmpty()); assertEquals(7,s.block(P).orElseThrow());
        s.setBlockEntity(e); s.setBlock(P,8); assertTrue(s.blockEntity(P).isEmpty());
        s.applyBlockEntity(P,9,e); s.applyBlockEntity(P,0,null); assertTrue(s.blockEntity(P).isEmpty()); assertEquals(0,s.block(P).orElseThrow());
    }
    @Test void invalidAtomicEditsAndBatchLeaveBothOldStateAndPayload() {
        var s=new SparseCubeSpace(); s.loadEmpty(K); var e=saved(P,4); s.applyBlockEntity(P,7,e); long generation=s.contentGeneration();
        assertThrows(IllegalArgumentException.class,()->s.applyBlockEntity(P,-1,saved(P,5)));
        assertThrows(IllegalArgumentException.class,()->s.applyBlockEntity(P,8,saved(new BlockCoordinate(-2,-20000,-17),5)));
        var absent=new BlockCoordinate(0,0,0);
        assertThrows(IllegalStateException.class,()->s.applyBlockEntity(absent,8,saved(absent,5)));
        assertThrows(IllegalStateException.class,()->s.setBlockEntity(saved(absent,5)));
        assertThrows(IllegalStateException.class,()->s.removeBlockEntity(absent));
        assertThrows(IllegalStateException.class,()->s.applyBatch(Map.of(P,8,absent,9)));
        assertEquals(7,s.block(P).orElseThrow()); assertEquals(e,s.blockEntity(P).orElseThrow()); assertEquals(generation,s.contentGeneration());
    }
    @Test void perCubeCapRejectedBeforeStateChangeButReplacementAndRemovalWork() {
        var s=new SparseCubeSpace(); var entities=filled(K,64); s.load(new CubeSnapshot(K,new int[4096],List.of(),entities));
        long generation=s.contentGeneration();
        assertThrows(IllegalArgumentException.class,()->s.applyBlockEntity(P,7,saved(P,4)));
        assertEquals(0,s.block(P).orElseThrow()); assertTrue(s.blockEntity(P).isEmpty()); assertEquals(generation,s.contentGeneration());
        var p=entities.get(0).position(); s.applyBlockEntity(p,7,saved(p,99)); assertEquals(64,s.snapshot(K).orElseThrow().blockEntities().size());
        s.removeBlockEntity(p); s.applyBlockEntity(P,7,saved(P,4)); assertEquals(64,s.snapshot(K).orElseThrow().blockEntities().size());
    }
    @Test void generationOverflowLeavesStateAndPayloadIntact() throws ReflectiveOperationException {
        var s=new SparseCubeSpace(); s.loadEmpty(K); var e=saved(P,4); s.applyBlockEntity(P,7,e);
        var field=SparseCubeSpace.class.getDeclaredField("contentGeneration"); field.setAccessible(true); field.setLong(s,Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->s.applyBlockEntity(P,8,saved(P,5)));
        assertThrows(ArithmeticException.class,()->s.setBlockEntity(saved(P,5)));
        assertThrows(ArithmeticException.class,()->s.removeBlockEntity(P));
        assertThrows(ArithmeticException.class,()->s.setBlock(P,8));
        assertThrows(ArithmeticException.class,()->s.unload(K));
        assertEquals(7,s.block(P).orElseThrow()); assertEquals(e,s.blockEntity(P).orElseThrow()); assertEquals(Long.MAX_VALUE,s.contentGeneration());
    }
    @Test void numericStoreRefusesPayloadWithoutOverwritingAndKeepsResidency(@TempDir Path directory) throws IOException {
        var store=new FileCubeStore(directory); var old=new CubeSnapshot(K,new int[4096]); store.write(old); byte[] file=Files.readAllBytes(store.path(K));
        var s=new SparseCubeSpace(); s.loadEmpty(K); var e=saved(P,3); s.applyBlockEntity(P,7,e);
        assertThrows(IOException.class,()->new PersistentCubeSpace(s,store).saveAndUnload(K));
        assertArrayEquals(file,Files.readAllBytes(store.path(K))); assertTrue(s.isLoaded(K)); assertEquals(e,s.blockEntity(P).orElseThrow());
    }
    @Test void readWriteAndInvalidIncomingFailuresPreserveOldEntityAndDemand() throws IOException {
        var store=new CubeTickResidencyTest.MemoryStore(); var service=new CubeResidencyService(store,k->new CubeSnapshot(k,new int[4096]),1,1,1);
        service.move("p",P,0,0); var e=saved(P,8); service.space().applyBlockEntity(P,7,e); var demand=service.snapshot();
        var bad=new CubeKey(K.x()+1,K.y(),K.z());
        store.data.put(bad,new CubeSnapshot(bad,new int[4096],List.of(new SavedCubeTick(SavedCubeTick.Kind.FLUID,"minecraft:water",new BlockCoordinate(bad.x()*16,bad.y()*16,bad.z()*16),Long.MAX_VALUE,0,0)),List.of(saved(new BlockCoordinate(bad.x()*16,bad.y()*16,bad.z()*16),1))));
        service.space().scheduledTicks().advanceTime(1);
        assertThrows(ArithmeticException.class,()->service.move("p",new BlockCoordinate(bad.x()*16,bad.y()*16,bad.z()*16),0,0));
        assertEquals(demand,service.snapshot()); assertEquals(e,service.space().blockEntity(P).orElseThrow()); assertTrue(store.data.get(K)==null);
        service.release("p"); assertEquals(e,store.data.get(K).blockEntities().get(0));
        var fresh=new CubeResidencyService(store,k->{throw new AssertionError("must restore");},1,1,1); fresh.move("p",P,0,0); assertEquals(e,fresh.space().blockEntity(P).orElseThrow());
        var broken=new CubeStore(){ public Optional<CubeSnapshot> read(CubeKey k)throws IOException{throw new IOException("read failure");} public void write(CubeSnapshot s)throws IOException{throw new IOException("save failure");} };
        var fail=new CubeResidencyService(broken,k->new CubeSnapshot(k,new int[4096]),1,1,1);
        assertThrows(IOException.class,()->fail.move("p",P,0,0)); assertEquals(0,fail.space().loadedCubeCount());
        var failWrite=new CubeStore(){public Optional<CubeSnapshot> read(CubeKey k){return Optional.of(new CubeSnapshot(k,new int[4096]));} public void write(CubeSnapshot s)throws IOException{throw new IOException("save failure");}};
        var retained=new CubeResidencyService(failWrite,k->{throw new AssertionError();},1,1,1); retained.move("p",P,0,0); retained.space().applyBlockEntity(P,7,e);
        assertThrows(IOException.class,()->retained.release("p")); assertEquals(e,retained.space().blockEntity(P).orElseThrow()); assertEquals(Set.of(K),retained.ownerWindow("p"));
    }
    @Test void laterReadFailureRetainsPreviouslyLoadedEntity() throws IOException {
        var store=new CubeStore() {
            public Optional<CubeSnapshot> read(CubeKey key) throws IOException {
                if(!key.equals(K)) throw new IOException("incoming cube unreadable");
                return Optional.of(new CubeSnapshot(key,new int[4096]));
            }
            public void write(CubeSnapshot snapshot) { throw new AssertionError("must stage reads before save"); }
        };
        var service=new CubeResidencyService(store,k->{throw new AssertionError("do not regenerate unreadable data");},1,1,1);
        service.move("p",P,0,0); var entity=saved(P,8); service.space().applyBlockEntity(P,7,entity);
        var demand=service.snapshot(); long generation=service.space().contentGeneration();
        assertThrows(IOException.class,()->service.move("p",new BlockCoordinate(0,-20000,-17),0,0));
        assertEquals(demand,service.snapshot()); assertEquals(generation,service.space().contentGeneration());
        assertEquals(7,service.space().block(P).orElseThrow()); assertEquals(entity,service.space().blockEntity(P).orElseThrow());
    }
}
