package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.tick.CubeScheduledTicks;
import java.util.*;

/** Single-owner-thread diagnostic residency. Empty data and absent data are distinct. */
public final class SparseCubeSpace {
    private final CubeScheduledTicks scheduledTicks = new CubeScheduledTicks();
    private Map<CubeKey, CubeStates> cubes = new HashMap<>();
    private Map<CubeKey, Map<BlockCoordinate,SavedCubeBlockEntity>> blockEntities = new HashMap<>();
    private long contentGeneration;
    public CubeScheduledTicks scheduledTicks() { scheduledTicks.assertOwner(); return scheduledTicks; }

    public boolean loadEmpty(CubeKey key) {
        scheduledTicks.assertOwner(); Objects.requireNonNull(key);
        if(cubes.containsKey(key)) return false;
        load(new CubeSnapshot(key,new int[CubeSnapshot.BLOCK_COUNT])); return true;
    }
    public void load(CubeSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        commitTransition(stageTransition(Map.of(snapshot.key(),snapshot),Set.of()));
    }
    /** Validate and publish one complete window, copying resident maps/tick queues only once. */
    public void loadAll(Map<CubeKey,CubeSnapshot> snapshots) {
        Objects.requireNonNull(snapshots);
        commitTransition(stageTransition(snapshots,Set.of()));
    }
    /** Fully validate the final union before persistence or ownership changes. No queued tick runs here. */
    Transition stageTransition(Map<CubeKey,CubeSnapshot> incoming,Set<CubeKey> outgoing) {
        scheduledTicks.assertOwner();
        var next=new HashMap<>(cubes); var queue=scheduledTicks.copy();
        var nextEntities = new HashMap<>(blockEntities);
        for(var key:outgoing) {
            if(next.remove(key)==null) throw new IllegalStateException("outgoing cube absent: "+key);
            queue.removeCube(key);
            nextEntities.remove(key);
        }
        for(var entry:incoming.entrySet()) {
            var snapshot=Objects.requireNonNull(entry.getValue()); var key=entry.getKey();
            if(!key.equals(snapshot.key())) throw new IllegalArgumentException("snapshot coordinate mismatch");
            if(next.containsKey(key)) throw new IllegalStateException("cube already resident: "+key);
            next.put(key,snapshot.copyStates()); queue.load(key,snapshot.ticks());
            var entities = new HashMap<BlockCoordinate,SavedCubeBlockEntity>();
            for(var entity:snapshot.blockEntities()) entities.put(entity.position(),entity);
            nextEntities.put(key,entities);
        }
        long generation=Math.addExact(contentGeneration,Math.addExact((long)incoming.size(),outgoing.size()));
        scheduledTicks.validateReplacement(queue);
        return new Transition(this,next,nextEntities,queue,generation,contentGeneration);
    }
    void commitTransition(Transition staged) {
        scheduledTicks.assertOwner();
        if(staged.owner!=this || staged.expectedGeneration!=contentGeneration) throw new IllegalStateException("stale cube transition");
        scheduledTicks.validateReplacement(staged.queue);
        scheduledTicks.replaceWith(staged.queue); cubes=staged.cubes; contentGeneration=staged.generation;
        blockEntities=staged.blockEntities;
    }
    static final class Transition {
        final SparseCubeSpace owner; final Map<CubeKey,CubeStates> cubes; final CubeScheduledTicks queue;
        final Map<CubeKey,Map<BlockCoordinate,SavedCubeBlockEntity>> blockEntities;
        final long generation,expectedGeneration;
        Transition(SparseCubeSpace owner,Map<CubeKey,CubeStates> cubes,Map<CubeKey,Map<BlockCoordinate,SavedCubeBlockEntity>> blockEntities,CubeScheduledTicks queue,long generation,long expectedGeneration) {
            this.owner=owner;this.cubes=cubes;this.queue=queue;this.generation=generation;this.expectedGeneration=expectedGeneration;
            this.blockEntities=blockEntities;
        }
    }
    public boolean isLoaded(CubeKey key) { scheduledTicks.assertOwner(); return cubes.containsKey(key); }
    public int loadedCubeCount() { scheduledTicks.assertOwner(); return cubes.size(); }
    public long contentGeneration() { scheduledTicks.assertOwner(); return contentGeneration; }
    public Set<CubeKey> residentKeys() { scheduledTicks.assertOwner(); return Set.copyOf(cubes.keySet()); }
    public OptionalInt block(BlockCoordinate position) {
        scheduledTicks.assertOwner(); var states=cubes.get(CubeKey.fromBlock(position));
        return states==null?OptionalInt.empty():OptionalInt.of(states.get(index(position)));
    }
    public void setBlock(BlockCoordinate position,int state) { applyBatch(Map.of(position,state)); }
    public Optional<SavedCubeBlockEntity> blockEntity(BlockCoordinate position) {
        scheduledTicks.assertOwner(); Objects.requireNonNull(position);
        var entities=blockEntities.get(CubeKey.fromBlock(position));
        return entities==null?Optional.empty():Optional.ofNullable(entities.get(position));
    }
    public void setBlockEntity(SavedCubeBlockEntity entity) {
        Objects.requireNonNull(entity);
        applyBlockEntity(entity.position(), block(entity.position()).orElseThrow(()->new IllegalStateException("cannot edit unloaded cube")), entity);
    }
    public void removeBlockEntity(BlockCoordinate position) {
        scheduledTicks.assertOwner(); Objects.requireNonNull(position);
        var entities=residentEntities(position);
        if(!entities.containsKey(position)) return;
        long next=Math.incrementExact(contentGeneration);
        entities.remove(position); contentGeneration=next;
    }
    /** Placement and payload are one validated mutation. Null removes the payload. */
    public void applyBlockEntity(BlockCoordinate position,int state,SavedCubeBlockEntity entity) {
        scheduledTicks.assertOwner(); Objects.requireNonNull(position);
        if(state<0) throw new IllegalArgumentException("state IDs must be nonnegative");
        if(entity!=null && !position.equals(entity.position())) throw new IllegalArgumentException("block entity coordinate mismatch");
        var entities=residentEntities(position); var states=cubes.get(CubeKey.fromBlock(position));
        if(entity!=null && !entities.containsKey(position) && entities.size()>=SavedCubeBlockEntity.MAX_PER_CUBE)
            throw new IllegalArgumentException("too many saved block entities");
        if(states.get(index(position))==state && Objects.equals(entities.get(position),entity)) return;
        long next=Math.incrementExact(contentGeneration);
        states.set(index(position),state);
        if(entity==null) entities.remove(position); else entities.put(position,entity);
        contentGeneration=next;
    }
    private Map<BlockCoordinate,SavedCubeBlockEntity> residentEntities(BlockCoordinate position) {
        var entities=blockEntities.get(CubeKey.fromBlock(position));
        if(entities==null) throw new IllegalStateException("cannot edit unloaded cube: "+position);
        return entities;
    }
    /** One generation covers a native dispatch batch; every coordinate/ID/overflow is checked before mutation. */
    public void applyBatch(Map<BlockCoordinate,Integer> writes) {
        scheduledTicks.assertOwner(); Objects.requireNonNull(writes); var stable=Map.copyOf(writes); boolean changed=false;
        for(var entry:stable.entrySet()) {
            if(entry.getValue()<0) throw new IllegalArgumentException("state IDs must be nonnegative");
            var states=cubes.get(CubeKey.fromBlock(entry.getKey()));
            if(states==null) throw new IllegalStateException("cannot edit unloaded cube: "+entry.getKey());
            if(states.get(index(entry.getKey()))!=entry.getValue()) changed=true;
        }
        if(!changed) return; long next=Math.incrementExact(contentGeneration);
        for(var entry:stable.entrySet()) {
            var key=CubeKey.fromBlock(entry.getKey()); var states=cubes.get(key);
            if(states.get(index(entry.getKey()))!=entry.getValue()) blockEntities.get(key).remove(entry.getKey());
            states.set(index(entry.getKey()),entry.getValue());
        }
        contentGeneration=next;
    }
    public Optional<CubeSnapshot> snapshot(CubeKey key) {
        scheduledTicks.assertOwner(); var states=cubes.get(key);
        return states==null?Optional.empty():Optional.of(new CubeSnapshot(key,states,scheduledTicks.snapshot(key),List.copyOf(blockEntities.get(key).values())));
    }
    public Optional<CubeSnapshot> unload(CubeKey key) {
        var saved=snapshot(key); if(saved.isEmpty()) return saved;
        commitTransition(stageTransition(Map.of(),Set.of(key))); return saved;
    }
    private static int index(BlockCoordinate position) {
        return (Math.floorMod(position.y(),16)<<8)|(Math.floorMod(position.z(),16)<<4)|Math.floorMod(position.x(),16);
    }
}
