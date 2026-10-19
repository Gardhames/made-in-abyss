package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.util.Collections;
import java.lang.ref.WeakReference;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.SavedCubeBlockEntity;
import dev.madeinabyss.forge.cube.ChestBlockEntityCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Common-side metadata adapter, with no client-only classes in Level mixins. */
public final class DeepChestClientMirror {
    private static final Map<Level,WeakReference<Cache>> CACHES=Collections.synchronizedMap(new WeakHashMap<>());
    private DeepChestClientMirror() {}
    /** Opaque, immutable coverage derived from the exact snapshots used to build a private candidate.
     * No Level, native block entity, live residency or metadata decoding is touched here.
     */
    public static final class ExpectedBlocks {
        private final Map<BlockPos,Boolean> entities;
        private ExpectedBlocks(Map<BlockPos,Boolean> entities){this.entities=Map.copyOf(entities);}
    }
    public static ExpectedBlocks expectedBlocks(List<CubeSnapshot> snapshots,Runnable checkpoint) {
        var entities=new HashMap<BlockPos,Boolean>();
        for(var snapshot:snapshots) {
            checkpoint.run();var key=snapshot.key();var states=snapshot.states();
            for(int i=0;i<states.length;i++) {
                var state=Block.stateById(states[i]);
                if(state.hasBlockEntity())entities.put(new BlockPos(key.x()*16+(i&15),key.y()*16+(i>>8),key.z()*16+(i>>4&15)),state.is(Blocks.CHEST));
            }
        }
        return new ExpectedBlocks(entities);
    }
    public static Cache prepare(Level level,SparseCubeSpace space,List<DeepChestPackets.Mirror> metadata) {
        return prepare(level,space,metadata,null);
    }
    /** Supplied coverage must describe the immutable snapshots from which this candidate was built. */
    public static Cache prepare(Level level,SparseCubeSpace space,List<DeepChestPackets.Mirror> metadata,ExpectedBlocks expected) {
        var decoded=new HashMap<BlockPos,ChestBlockEntity>();var payloads=new HashMap<BlockPos,byte[]>();
        if(metadata.size()>DeepChestPackets.MAX_MIRRORS)throw new IllegalArgumentException("chest mirror budget");
        for(var mirror:metadata) {
            var pos=mirror.position();var id=space.block(coordinate(pos)).orElseThrow(()->new IllegalArgumentException("chest metadata outside resident window"));
            if(id!=mirror.stateId() || decoded.containsKey(pos))throw new IllegalArgumentException("chest metadata mismatch/duplicate");
            try{decoded.put(pos,ChestBlockEntityCodec.decode(new SavedCubeBlockEntity(coordinate(pos),"minecraft:chest",mirror.updateNbt()),Block.stateById(id)));payloads.put(pos,mirror.updateNbt());}
            catch(java.io.IOException invalid){throw new IllegalArgumentException("invalid native chest mirror",invalid);}
        }
        if(expected!=null) {
            // Preserve failure precedence: metadata construction/validation still happens first.
            for(var entry:expected.entities.entrySet())
                if(!entry.getValue() || !decoded.containsKey(entry.getKey()))throw new IllegalArgumentException("resident block entity lacks supported metadata");
        } else for(var key:space.residentKeys()) {
            int[] states=space.snapshot(key).orElseThrow().states();
            for(int i=0;i<states.length;i++)if(Block.stateById(states[i]).hasBlockEntity()) {
                var pos=new BlockPos(key.x()*16+(i&15),key.y()*16+(i>>8),key.z()*16+(i>>4&15));
                if(!Block.stateById(states[i]).is(Blocks.CHEST) || !decoded.containsKey(pos))throw new IllegalArgumentException("resident block entity lacks supported metadata");
            }
        }
        return new Cache(level,space,decoded,payloads);
    }
    public static void publish(Level level,Cache cache) {
        if(cache.level.get()!=level)throw new IllegalArgumentException("wrong chest mirror level");
        var previous=lookup(level);
        if(previous!=null)for(var entry:cache.live.entrySet()) {
            var old=previous.live.get(entry.getKey());
            if(old!=null && !old.isRemoved() && old.getBlockState()==entry.getValue().getBlockState()) {
                var temporary=entry.getValue();
                // Server window metadata contains only the name and empty visual slots. Do not reload
                // the retained native BE: its final lid controller must survive metadata revisions.
                old.setCustomName(temporary.getCustomName());old.clearContent();
                temporary.setRemoved();temporary.setLevel(null);entry.setValue(old);
            }
        }
        for(var chest:cache.live.values())chest.setLevel(level);
        if(previous!=null)for(var entry:previous.live.entrySet())if(cache.live.get(entry.getKey())!=entry.getValue()){entry.getValue().setRemoved();entry.getValue().setLevel(null);}
        CACHES.put(level,new WeakReference<>(cache));
    }
    public static ChestBlockEntity get(Level level,BlockPos pos) {
        var cache=lookup(level);if(cache==null)throw new IllegalStateException("native chest mirror unavailable");
        int id=cache.space.block(coordinate(pos)).orElseThrow(()->new IllegalStateException("unknown cube block entity"));
        var state=Block.stateById(id);var chest=cache.live.get(pos);
        if(state.hasBlockEntity() && chest==null)throw new UnsupportedOperationException("unsupported native block entity mirror");return chest;
    }
    public static Cache lookup(Level level){var ref=CACHES.get(level);return ref==null?null:ref.get();}
    public static void clear(Level level) {var cache=lookup(level);CACHES.remove(level);if(cache!=null)for(var chest:cache.live.values()){chest.setRemoved();chest.setLevel(null);}}
    private static BlockCoordinate coordinate(BlockPos pos){return new BlockCoordinate(pos.getX(),pos.getY(),pos.getZ());}
    public static final class Cache {
        private final WeakReference<Level> level;
        private final SparseCubeSpace space;
        private final Map<BlockPos,ChestBlockEntity> live;
        private final Map<BlockPos,byte[]> payloads;
        private Cache(Level level,SparseCubeSpace space,Map<BlockPos,ChestBlockEntity> live,Map<BlockPos,byte[]> payloads){this.level=new WeakReference<>(level);this.space=space;this.live=live;this.payloads=payloads;}
        public List<ChestBlockEntity> chests() {return List.copyOf(live.values());}
    }
}
