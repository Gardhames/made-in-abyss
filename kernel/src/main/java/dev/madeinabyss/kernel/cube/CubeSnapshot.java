package dev.madeinabyss.kernel.cube;

import java.util.Objects;
import java.util.List;
import java.util.HashSet;
import dev.madeinabyss.kernel.tick.CubeScheduledTicks;
import dev.madeinabyss.kernel.tick.SavedCubeTick;

/** Diagnostic storage IDs only; registry/palette migration is a separate engine concern. */
public final class CubeSnapshot {
    public static final int BLOCK_COUNT = 4096;
    private final CubeKey key;
    private final CubeStates states;
    private final List<SavedCubeTick> ticks;
    private final List<SavedCubeBlockEntity> blockEntities;

    public CubeSnapshot(CubeKey key, int[] states) {
        this(key,states,List.of());
    }
    public CubeSnapshot(CubeKey key, int[] states, List<SavedCubeTick> ticks) {
        this(key, states, ticks, List.of());
    }
    public CubeSnapshot(CubeKey key, int[] states, List<SavedCubeTick> ticks, List<SavedCubeBlockEntity> blockEntities) {
        this(Objects.requireNonNull(key),CubeStates.copyOf(states),ticks,blockEntities,false);
    }
    CubeSnapshot(CubeKey key, CubeStates states, List<SavedCubeTick> ticks, List<SavedCubeBlockEntity> blockEntities) {
        this(key,states,ticks,blockEntities,true);
    }
    private CubeSnapshot(CubeKey key,CubeStates states,List<SavedCubeTick> ticks,List<SavedCubeBlockEntity> blockEntities,boolean copy) {
        this.key = Objects.requireNonNull(key);
        this.states = copy?states.snapshotCopy():states;
        this.ticks=List.copyOf(ticks);
        if(this.ticks.size()>CubeScheduledTicks.MAX_PER_CUBE) throw new IllegalArgumentException("too many saved ticks");
        var unique=new HashSet<Object>();
        for(var tick:this.ticks) {
            if(!key.equals(CubeKey.fromBlock(tick.position()))) throw new IllegalArgumentException("tick outside snapshot cube");
            if(!unique.add(List.of(tick.kind(),tick.type(),tick.position()))) throw new IllegalArgumentException("duplicate saved tick");
        }
        this.blockEntities = List.copyOf(blockEntities);
        if(this.blockEntities.size()>SavedCubeBlockEntity.MAX_PER_CUBE) throw new IllegalArgumentException("too many saved block entities");
        var positions = new HashSet<dev.madeinabyss.kernel.BlockCoordinate>();
        for(var entity:this.blockEntities) {
            if(!key.equals(CubeKey.fromBlock(entity.position()))) throw new IllegalArgumentException("block entity outside snapshot cube");
            if(!positions.add(entity.position())) throw new IllegalArgumentException("duplicate saved block entity position");
        }
    }
    public CubeKey key() { return key; }
    public int[] states() { return states.toArray(); }
    CubeStates copyStates() { return states.snapshotCopy(); }
    public List<SavedCubeTick> ticks() { return ticks; }
    public List<SavedCubeBlockEntity> blockEntities() { return blockEntities; }
}
