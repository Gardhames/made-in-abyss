package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.tick.CubeScheduledTicks;
import dev.madeinabyss.kernel.tick.SavedCubeTick;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.world.level.redstone.NeighborUpdater;
import java.util.*;

/** Explicit bounded native water callbacks. Authoritative block/queue mutations commit together on one owner thread. */
public final class DeepFluidEngine {
    private static final Map<Level,DeepFluidEngine> ENGINES=Collections.synchronizedMap(new WeakHashMap<>());
    private static final int SCAN=4096, QUOTA=256, HORIZONTAL=6, VERTICAL=3;
    private final SparseCubeSpace space;
    private final Set<CubeKey> changed=new HashSet<>();
    private Transaction transaction;
    private long attempted,completed,stale,deferred,unsupported,rolledBack,nextOrder;
    private String lastError="";
    private long lastStep=Long.MIN_VALUE;
    public record Stats(long clock,int pending,long attempted,long completed,long stale,long deferred,
                        long unsupported,long rolledBack,long nextOrder) {}
    private DeepFluidEngine(SparseCubeSpace space) {
        this.space=Objects.requireNonNull(space);
        nextOrder=refreshOrder(space.scheduledTicks(),nextOrder);
    }
    public static DeepFluidEngine attach(ServerLevel level,SparseCubeSpace space) {
        Objects.requireNonNull(level); space.scheduledTicks().assertOwner();
        if(DeepCubeDomain.space(level).orElseThrow()!=space) throw new IllegalArgumentException("fluid engine requires matching attached cube space");
        if(ENGINES.containsKey(level)) throw new IllegalStateException("fluid engine already attached");
        var engine=new DeepFluidEngine(space); ENGINES.put(level,engine); return engine;
    }
    public static void detach(Level level) { var engine=ENGINES.remove(level); if(engine!=null) engine.space.scheduledTicks().assertOwner(); }
    public static Optional<DeepFluidEngine> lookup(Level level) { return level==null?Optional.empty():Optional.ofNullable(ENGINES.get(level)); }
    public static Optional<BlockState> overlay(Level level,BlockPos pos) {
        var engine=ENGINES.get(level);
        if(engine==null || engine.transaction==null) return Optional.empty();
        return Optional.of(engine.read(pos));
    }
    /** Called only by explicit fixture/controller edits; callbacks use actual Level.setBlock dispatch. */
    public boolean edit(ServerLevel level,BlockPos pos,BlockState state) {
        requireAttachment(level); Objects.requireNonNull(state);
        if(!DeepCubeDomain.isDeep(level,pos) || (!state.is(Blocks.AIR) && !water(state))) { unsupported++; return false; }
        if(transaction!=null) throw new IllegalStateException("nested fluid edit");
        var readiness=preflight(level,pos);
        if(readiness!=Readiness.KNOWN) { countReadiness(readiness); return false; }
        try {
            transaction=new Transaction(pos,space.scheduledTicks().copy(),refreshOrder(space.scheduledTicks(),nextOrder));
            boolean result=level.setBlock(pos,state,2);
            // The explicit edit API supplies neighbor notification for removal, while its native seed uses flags2.
            if(result) neighbors(level,pos,Blocks.WATER);
            commit(); return result;
        } catch(RuntimeException exception) {
            recordRollback(exception); return false;
        } finally { transaction=null; }
    }
    /** Both inherited native overloads route here; ordinary positions never enter this method. */
    public void schedule(Level level,BlockPos pos,Fluid fluid,int delay,TickPriority priority) {
        requireAttachment(level); Objects.requireNonNull(fluid); Objects.requireNonNull(priority);
        if(!DeepCubeDomain.isDeep(level,pos)) throw new IllegalArgumentException("not a deep fluid position");
        if(fluid!=Fluids.WATER && fluid!=Fluids.FLOWING_WATER) throw new UnsupportedOperationException("only exact native water tick types supported");
        // Scheduling need not match current state: stale source/flowing ticks are discarded at dispatch like native.
        var coordinate=coordinate(pos);
        BlockState state=transaction==null?stored(pos):read(pos);
        if(!supported(state)) throw new UnsupportedOperationException("unsupported fluid schedule target");
        var queue=transaction==null?space.scheduledTicks():transaction.queue;
        long order=transaction==null?refreshOrder(queue,nextOrder):transaction.nextOrder;
        long following=Math.incrementExact(order);
        var tick=new CubeScheduledTicks.Tick(SavedCubeTick.Kind.FLUID,BuiltInRegistries.FLUID.getKey(fluid).toString(),coordinate,
                Math.addExact(queue.clock(),delay),priority.getValue(),order);
        queue.schedule(tick);
        if(transaction==null) nextOrder=following; else transaction.nextOrder=following;
    }
    /** Level four-argument write hook; no native chunks, BE registration or native tick container writes. */
    public boolean write(Level level,BlockPos pos,BlockState state,int flags,int depth) {
        requireAttachment(level);
        if(transaction==null) return false;
        if((flags & ~(1|2|4|16|32|64))!=0 || depth<0) throw new UnsupportedOperationException("unsupported water write flags");
        if(!state.is(Blocks.AIR) && !water(state)) throw new UnsupportedOperationException("native fluid replacement outside AIR/WATER");
        BlockState previous=read(pos);
        if(previous==state) return false;
        transaction.writes.put(pos.immutable(),state);
        boolean moving=(flags&64)!=0;
        previous.onRemove(level,pos,state,moving);
        state.onPlace(level,pos,previous,moving);
        if((flags&1)!=0) neighbors(level,pos,previous.getBlock());
        if((flags&16)==0 && depth>0) {
            // Actual BlockState.updateShape callbacks, without escaping into native deferred NeighborUpdater queues.
            for(Direction direction:Direction.values()) {
                BlockPos other=pos.relative(direction); BlockState neighbor=read(other);
                BlockState replacement=neighbor.updateShape(direction.getOpposite(),state,level,other,pos);
                if(replacement!=neighbor) write(level,other,replacement,flags&~(1|32),depth-1);
            }
        }
        return true;
    }
    /** ServerLevel owns the override. Keep all callback reads inside the preflighted transaction. */
    public void neighbors(Level level,BlockPos pos,Block source) {
        requireAttachment(level);
        if(transaction==null) throw new IllegalStateException("deep neighbor notification outside explicit fluid transaction");
        for(Direction direction:NeighborUpdater.UPDATE_ORDER) {
            BlockPos other=pos.relative(direction); BlockState state=read(other);
            state.neighborChanged(level,other,source,pos,false);
        }
    }
    public void step(ServerLevel level,long now) {
        requireAttachment(level); var queue=space.scheduledTicks(); queue.advanceTime(now);
        if(now==lastStep) return;
        if(transaction!=null) throw new IllegalStateException("nested fluid dispatch");
        lastStep=now;
        var due=queue.due(now,SCAN); int dispatched=0;
        for(var tick:due) {
            if(dispatched>=QUOTA) break;
            if(tick.kind()!=SavedCubeTick.Kind.FLUID || !waterType(tick.type())) { unsupported++; continue; }
            BlockPos pos=new BlockPos(tick.position().x(),tick.position().y(),tick.position().z());
            var readiness=preflight(level,pos);
            if(readiness!=Readiness.KNOWN) { countReadiness(readiness); continue; }
            dispatched++; attempted++;
            try {
                transaction=new Transaction(pos,queue.copy(),refreshOrder(queue,nextOrder));
                if(!transaction.queue.consume(tick)) throw new IllegalStateException("due tick changed during frozen dispatch");
                Fluid expected=BuiltInRegistries.FLUID.get(new ResourceLocation(tick.type()));
                var state=read(pos).getFluidState();
                boolean matches=state.is(expected);
                if(matches) state.tick(level,pos); // exact actual NMS implementation, never a copied fluid algorithm
                commit();
                if(matches) completed++; else stale++;
            } catch(RuntimeException exception) { recordRollback(exception); }
            finally { transaction=null; }
        }
    }
    public Set<CubeKey> drainChanged() { space.scheduledTicks().assertOwner(); var result=Set.copyOf(changed); changed.clear(); return result; }
    public Stats stats() { var q=space.scheduledTicks(); return new Stats(q.clock(),q.pendingCount(),attempted,completed,stale,deferred,unsupported,rolledBack,nextOrder); }
    public String lastError() { space.scheduledTicks().assertOwner(); return lastError; }
    private void recordRollback(RuntimeException exception) {
        rolledBack++;
        lastError=exception.getClass().getSimpleName()+": "+String.valueOf(exception.getMessage());
    }
    /** Cubes can restore saved ticks after attachment; new callbacks must follow their persisted sequence. */
    private static long refreshOrder(CubeScheduledTicks queue,long candidate) {
        for(var tick:queue.due(Long.MAX_VALUE,SCAN)) {
            if(tick.order()>=candidate) candidate=Math.incrementExact(tick.order());
        }
        return candidate;
    }
    private void commit() {
        var q=space.scheduledTicks(); var writes=new HashMap<BlockCoordinate,Integer>(); var actual=new HashSet<CubeKey>();
        for(var entry:transaction.writes.entrySet()) {
            var coordinate=coordinate(entry.getKey()); int id=Block.getId(entry.getValue());
            if(space.block(coordinate).orElseThrow()!=id) { writes.put(coordinate,id); actual.add(CubeKey.fromBlock(coordinate)); }
        }
        q.validateReplacement(transaction.queue); // all queue validation happens before authoritative block writes
        space.applyBatch(writes); q.replaceWith(transaction.queue); nextOrder=transaction.nextOrder; changed.addAll(actual);
    }
    private BlockState read(BlockPos pos) {
        space.scheduledTicks().assertOwner();
        if(transaction==null) throw new IllegalStateException("missing fluid transaction");
        long dx=(long)pos.getX()-transaction.origin.getX(),dy=(long)pos.getY()-transaction.origin.getY(),dz=(long)pos.getZ()-transaction.origin.getZ();
        if(Math.abs(dx)>HORIZONTAL || Math.abs(dz)>HORIZONTAL || Math.abs(dy)>VERTICAL)
            throw new UnsupportedOperationException("native fluid callback exceeded preflight bounds");
        BlockState result=transaction.writes.get(pos);
        return result==null?stored(pos):result;
    }
    private BlockState stored(BlockPos pos) {
        int id=space.block(coordinate(pos)).orElseThrow(() -> new IllegalStateException("fluid cube is not resident: "+pos));
        BlockState state=Block.BLOCK_STATE_REGISTRY.byId(id);
        if(state==null) throw new IllegalStateException("unknown native fluid state ID");
        return state;
    }
    private Readiness preflight(Level level,BlockPos origin) {
        long x=origin.getX(),y=origin.getY(),z=origin.getZ();
        if(x-HORIZONTAL<Integer.MIN_VALUE || x+HORIZONTAL>Integer.MAX_VALUE || z-HORIZONTAL<Integer.MIN_VALUE || z+HORIZONTAL>Integer.MAX_VALUE
                || y-VERTICAL<DeepCubeDomain.TECHNICAL_BOTTOM || y+VERTICAL>=level.getMinBuildHeight()) return Readiness.UNSUPPORTED;
        for(int dx=-HORIZONTAL;dx<=HORIZONTAL;dx++) for(int dy=-VERTICAL;dy<=VERTICAL;dy++) for(int dz=-HORIZONTAL;dz<=HORIZONTAL;dz++) {
            var p=new BlockPos((int)x+dx,(int)y+dy,(int)z+dz);
            if(space.block(coordinate(p)).isEmpty()) return Readiness.UNKNOWN;
            if(!supported(stored(p))) return Readiness.UNSUPPORTED;
        }
        return Readiness.KNOWN;
    }
    private void countReadiness(Readiness readiness) { if(readiness==Readiness.UNKNOWN) deferred++; else unsupported++; }
    private void requireAttachment(Level level) {
        space.scheduledTicks().assertOwner();
        if(ENGINES.get(level)!=this || DeepCubeDomain.space(level).orElse(null)!=space) throw new IllegalStateException("stale fluid attachment");
    }
    private static boolean supported(BlockState state) {
        return state.is(Blocks.AIR) || water(state) || state.is(Blocks.STONE) || state.is(Blocks.BEDROCK)
                || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF) || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.CALCITE) || state.is(Blocks.DIORITE) || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.TERRACOTTA) || state.is(Blocks.ORANGE_TERRACOTTA)
                || state.is(Blocks.YELLOW_TERRACOTTA) || state.is(Blocks.MOSSY_COBBLESTONE) || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.GOLD_BLOCK) || state.is(Blocks.DIAMOND_BLOCK) || state.is(Blocks.EMERALD_BLOCK) || state.is(Blocks.GLOWSTONE);
    }
    private static boolean water(BlockState state) {
        return state.is(Blocks.WATER) && (state.getFluidState().is(Fluids.WATER) || state.getFluidState().is(Fluids.FLOWING_WATER));
    }
    private static boolean waterType(String type) { return type.equals("minecraft:water") || type.equals("minecraft:flowing_water"); }
    private static BlockCoordinate coordinate(BlockPos pos) { return new BlockCoordinate(pos.getX(),pos.getY(),pos.getZ()); }
    private enum Readiness { KNOWN, UNKNOWN, UNSUPPORTED }
    // No Level reference here or in any engine field: WeakHashMap values cannot keep their keys alive.
    private static final class Transaction {
        final BlockPos origin; final CubeScheduledTicks queue; final Map<BlockPos,BlockState> writes=new HashMap<>(); long nextOrder;
        Transaction(BlockPos origin,CubeScheduledTicks queue,long nextOrder) { this.origin=origin.immutable(); this.queue=queue; this.nextOrder=nextOrder; }
    }
}
