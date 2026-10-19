package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.cube.ChestBlockEntityCodec;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.*;
import dev.madeinabyss.kernel.tick.CubeScheduledTicks;
import dev.madeinabyss.kernel.tick.SavedCubeTick;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.TickPriority;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Consumer;

/** Explicit single-CHEST integration: native slots/menu/lid callbacks backed by signed cube NBT. */
public final class DeepChestEngine {
    private static final int MAX_LIVE=512;
    // Weak values prevent native BE.level from retaining the weak Level key. Runtime owns this engine.
    private static final Map<Level,WeakReference<DeepChestEngine>> ENGINES=Collections.synchronizedMap(new WeakHashMap<>());
    private final SparseCubeSpace space;
    private final Map<BlockPos,LiveChest> live=new HashMap<>();
    private final Set<CubeKey> changed=new HashSet<>();
    private Consumer<LidEvent> lidEvents=event -> {};
    private CubeScheduledTicks transactionQueue;
    private ClickTransaction clickTransaction;
    private long nextOrder,lastStep=Long.MIN_VALUE;
    private long completed,stale,deferred,rolledBack,refusedClicks;
    private String lastError="";
    public record LidEvent(BlockPos position,int id,int value) {
        public LidEvent { position=position.immutable(); }
    }
    public record Stats(int live,int pending,long completed,long stale,long deferred,long rolledBack,long nextOrder,long refusedClicks) {}
    private DeepChestEngine(SparseCubeSpace space) { this.space=space; nextOrder=refreshOrder(space.scheduledTicks(),0); }
    public static DeepChestEngine attach(ServerLevel level,SparseCubeSpace space) {
        Objects.requireNonNull(level); Objects.requireNonNull(space); space.scheduledTicks().assertOwner();
        if(DeepCubeDomain.space(level).orElse(null)!=space) throw new IllegalArgumentException("chest engine requires matching attached space");
        if(lookup(level).isPresent()) throw new IllegalStateException("chest engine already attached");
        var engine=new DeepChestEngine(space); ENGINES.put(level,new WeakReference<>(engine)); return engine;
    }
    public static Optional<DeepChestEngine> lookup(Level level) {
        if(level==null) return Optional.empty(); var ref=ENGINES.get(level); return ref==null?Optional.empty():Optional.ofNullable(ref.get());
    }
    public static void detach(Level level) {
        var engine=lookup(level).orElse(null); ENGINES.remove(level);
        if(engine!=null) {
            engine.space.scheduledTicks().assertOwner();
            for(var chest:List.copyOf(engine.live.values())) engine.invalidate(level,chest);
            engine.live.clear(); engine.lidEvents=event -> {};
        }
    }
    public void onLidEvent(Consumer<LidEvent> consumer) { space.scheduledTicks().assertOwner(); lidEvents=Objects.requireNonNull(consumer); }
    /** Known non-BE cells return null; UNKNOWN and unsupported/mismatched BE data stay explicit. */
    public ChestBlockEntity get(Level level,BlockPos pos) {
        requireAttachment(level); requireDeep(level,pos); reconcile(level);
        BlockState state=stored(pos); var record=space.blockEntity(coordinate(pos));
        if(!state.is(Blocks.CHEST)) {
            if(state.hasBlockEntity()) throw new UnsupportedOperationException("only exact native CHEST is supported");
            if(record.isPresent()) throw new IllegalStateException("block entity payload without chest state");
            return null;
        }
        if(!single(state)) throw new UnsupportedOperationException("only dry SINGLE CHEST is supported");
        SavedCubeBlockEntity saved=record.orElseThrow(() -> new IllegalStateException("resident chest is missing its inventory payload: "+pos));
        var chest=live.get(pos);
        if(chest!=null) return chest;
        if(live.size()>=MAX_LIVE) throw new IllegalStateException("native chest residency cache budget exceeded");
        try {
            var tag=ChestBlockEntityCodec.validateAndRead(saved,state);
            chest=new LiveChest(pos,state,this); chest.load(tag); chest.lastSaved=saved;
            chest.setLevel(level); chest.clearRemoved(); live.put(pos.immutable(),chest); chest.onLoad();
            return chest;
        } catch(IOException exception) { throw new IllegalStateException("invalid resident chest payload",exception); }
    }
    /** Explicit fixture placement never overwrites existing blocks, fluids or containers. */
    public boolean place(ServerLevel level,BlockPos pos,BlockState state,CompoundTag nbt) {
        requireAttachment(level); Objects.requireNonNull(state); Objects.requireNonNull(pos);
        if(!DeepCubeDomain.isDeep(level,pos) || !single(state) || !known(pos) || !stored(pos).isAir()
                || space.blockEntity(coordinate(pos)).isPresent() || live.size()>=MAX_LIVE) return false;
        try {
            var candidate=new LiveChest(pos,state,this);
            if(nbt!=null) candidate.load(ChestBlockEntityCodec.validateAndRead(record(pos,nbt),state));
            var saved=ChestBlockEntityCodec.encode(candidate);
            ChestBlockEntityCodec.validateAndRead(saved,state);
            space.applyBlockEntity(coordinate(pos),Block.getId(state),saved);
            candidate.lastSaved=saved; candidate.setLevel(level); candidate.clearRemoved();
            live.put(pos.immutable(),candidate); candidate.onLoad(); changed.add(CubeKey.fromBlock(coordinate(pos))); return true;
        } catch(IOException | IllegalArgumentException exception) { lastError=exception.getClass().getSimpleName()+": "+exception.getMessage(); return false; }
    }
    /** Explicit fixture removal deletes inventory atomically; generic survival drops are outside this gate. */
    public boolean remove(ServerLevel level,BlockPos pos) {
        requireAttachment(level);
        if(!DeepCubeDomain.isDeep(level,pos) || !known(pos) || !single(stored(pos))) return false;
        var chest=get(level,pos);
        space.applyBlockEntity(coordinate(pos),Block.getId(Blocks.AIR.defaultBlockState()),null);
        live.remove(pos); if(chest!=null) invalidate(level,(LiveChest)chest);
        changed.add(CubeKey.fromBlock(coordinate(pos))); return true;
    }
    /** Actual ChestBlock.use/ServerPlayer.openMenu retain native menu IDs, slots and click protocol. */
    public boolean open(ServerPlayer player,BlockPos pos) {
        var level=player.serverLevel(); requireAttachment(level);
        if(!DeepCubeDomain.isDeep(level,pos) || !known(pos) || !known(pos.above()) || !single(stored(pos))) return false;
        if(player.distanceToSqr(Vec3.atCenterOf(pos))>64 || player.isSpectator()) return false;
        var chest=get(level,pos); if(chest==null) return false;
        validateRecheckCapacity(pos);
        stored(pos).use(level,player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        return player.containerMenu instanceof ChestMenu menu && menu.getContainer()==chest;
    }
    /** Stage real native clicks, then publish chest NBT once. Refusal restores chest, player slots and cursor. */
    public boolean click(ChestMenu menu,int slot,int button,ClickType type,ServerPlayer player) {
        requireAttachment(player.serverLevel());
        if(!(menu.getContainer() instanceof LiveChest chest) || chest.engine!=this || menu!=player.containerMenu
                || slot<0 || slot>=menu.slots.size() || button<0 || button>1
                || (type!=ClickType.PICKUP && type!=ClickType.QUICK_MOVE) || !menu.stillValid(player)) {
            refusedClicks++; return false;
        }
        if(clickTransaction!=null) throw new IllegalStateException("nested native chest click");
        ClickTransaction staged;
        try {
            staged=new ClickTransaction(chest,ChestBlockEntityCodec.encode(chest),chest.lastSaved,player,menu.getCarried());
        } catch(IOException | RuntimeException exception) { refusedClicks++; lastError="native chest click snapshot rejected: "+exception.getMessage(); return false; }
        clickTransaction=staged;
        try {
            menu.clicked(slot,button,type,player);
            var saved=ChestBlockEntityCodec.encode(chest);
            space.setBlockEntity(saved); // all native mutations remained staged until this one atomic publish
            if(!saved.equals(chest.lastSaved)) changed.add(CubeKey.fromBlock(coordinate(chest.getBlockPos())));
            chest.lastSaved=saved; return true;
        } catch(IOException | RuntimeException exception) {
            try { chest.load(ChestBlockEntityCodec.validateAndRead(staged.nativeBefore,chest.getBlockState())); }
            catch(IOException | RuntimeException restoreFailure) { exception.addSuppressed(restoreFailure); }
            for(int i=0;i<staged.playerItems.size();i++) player.getInventory().setItem(i,staged.playerItems.get(i).copy());
            menu.setCarried(staged.carried.copy()); chest.lastSaved=staged.savedBefore;
            refusedClicks++; lastError="native chest click refused: "+exception.getClass().getSimpleName()+": "+exception.getMessage(); return false;
        } finally { clickTransaction=null; }
    }
    public void reconcile(ServerLevel level) { reconcile((Level)level); }
    private void reconcile(Level level) {
        requireAttachment(level);
        var iterator=live.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next(); var chest=entry.getValue();
            BlockPos pos=entry.getKey();
            if(chest.isRemoved() || !known(pos) || stored(pos)!=chest.getBlockState()
                    || !Objects.equals(space.blockEntity(coordinate(pos)).orElse(null),chest.lastSaved)) {
                iterator.remove(); invalidate(level,chest);
            }
        }
    }
    /** Call before cube persistence/unload to capture direct mutable native stack or ForgeData changes. */
    public void flush(ServerLevel level) {
        requireAttachment(level); reconcile(level);
        for(var chest:List.copyOf(live.values())) persist(chest);
    }
    public void schedule(Level level,BlockPos pos,Block block,int delay,TickPriority priority) {
        requireAttachment(level); requireDeep(level,pos); Objects.requireNonNull(priority);
        if(block!=Blocks.CHEST) throw new UnsupportedOperationException("only exact CHEST block ticks supported");
        if(!known(pos)) throw new IllegalStateException("cannot schedule unknown chest cube");
        var queue=transactionQueue==null?space.scheduledTicks():transactionQueue;
        long order=refreshOrder(queue,nextOrder); long following=Math.incrementExact(order);
        queue.schedule(new CubeScheduledTicks.Tick(SavedCubeTick.Kind.BLOCK,"minecraft:chest",coordinate(pos),Math.addExact(queue.clock(),delay),priority.getValue(),order));
        nextOrder=following;
    }
    /** Exact native ChestBlock.tick on an immutable due batch; other persistent queues remain untouched. */
    public void step(ServerLevel level,long now) {
        requireAttachment(level); var queue=space.scheduledTicks(); queue.advanceTime(now); reconcile(level);
        if(now==lastStep) return; if(transactionQueue!=null) throw new IllegalStateException("nested chest dispatch"); lastStep=now;
        int count=0;
        for(var tick:queue.due(now,4096)) {
            if(tick.kind()!=SavedCubeTick.Kind.BLOCK || !tick.type().equals("minecraft:chest")) continue;
            if(count>=64) break; var pos=new BlockPos(tick.position().x(),tick.position().y(),tick.position().z());
            if(!known(pos)) { deferred++; continue; }
            long originalOrder=nextOrder;
            try {
                nextOrder=refreshOrder(queue,nextOrder); transactionQueue=queue.copy();
                if(!transactionQueue.consume(tick)) throw new IllegalStateException("frozen chest tick changed");
                var state=stored(pos); boolean matches=single(state);
                if(matches) state.tick(level,pos,level.random);
                queue.replaceWith(transactionQueue);
                count++;
                if(matches) completed++; else stale++;
            } catch(RuntimeException exception) { nextOrder=originalOrder; rolledBack++; lastError=exception.getClass().getSimpleName()+": "+exception.getMessage(); }
            finally { transactionQueue=null; }
        }
    }
    /** Only native event1; root fans out its full signed position to resident client mirrors. */
    public boolean blockEvent(Level level,BlockPos pos,Block block,int id,int value) {
        requireAttachment(level); requireDeep(level,pos);
        if(block!=Blocks.CHEST || id!=1 || value<0 || value>4) throw new UnsupportedOperationException("unsupported deep chest block event");
        var chest=get(level,pos); if(chest==null) return false;
        boolean accepted=chest.triggerEvent(id,value); if(accepted) lidEvents.accept(new LidEvent(pos,id,value)); return accepted;
    }
    public Set<CubeKey> drainChanged() { space.scheduledTicks().assertOwner(); var copy=Set.copyOf(changed); changed.clear(); return copy; }
    public int liveCount() { space.scheduledTicks().assertOwner(); return (int)live.values().stream().filter(c -> !c.isRemoved()).count(); }
    public Stats stats() { return new Stats(liveCount(),space.scheduledTicks().pendingCount(),completed,stale,deferred,rolledBack,nextOrder,refusedClicks); }
    public String lastError() { space.scheduledTicks().assertOwner(); return lastError; }
    private void persist(LiveChest chest) {
        var level=chest.getLevel(); requireAttachment(level); var pos=chest.getBlockPos();
        if(chest.isRemoved() || live.get(pos)!=chest || !known(pos) || stored(pos)!=chest.getBlockState())
            throw new IllegalStateException("stale native chest inventory callback");
        try {
            var saved=ChestBlockEntityCodec.encode(chest);
            if(clickTransaction!=null) {
                if(clickTransaction.chest!=chest) throw new IllegalStateException("native click reached another chest");
                return; // validation is real, but callbacks cannot publish intermediate native slot changes
            }
            space.setBlockEntity(saved);
            if(!saved.equals(chest.lastSaved)) changed.add(CubeKey.fromBlock(coordinate(pos)));
            chest.lastSaved=saved;
        } catch(IOException | RuntimeException exception) {
            if(clickTransaction!=null) throw new IllegalStateException("staged native chest mutation rejected",exception);
            // Native setItem/removeItem mutate before setChanged: restore the last authoritative payload.
            try { chest.load(ChestBlockEntityCodec.validateAndRead(chest.lastSaved,chest.getBlockState())); }
            catch(IOException | RuntimeException restoreFailure) { exception.addSuppressed(restoreFailure); }
            throw new IllegalStateException("native chest mutation rejected; previous inventory restored",exception);
        }
    }
    private void invalidate(Level level,LiveChest chest) {
        chest.onChunkUnloaded(); chest.setRemoved();
        if(level instanceof ServerLevel server) for(var player:List.copyOf(server.players()))
            if(player.containerMenu instanceof ChestMenu menu && menu.getContainer()==chest) player.closeContainer();
        chest.setLevel(null);
    }
    private void validateRecheckCapacity(BlockPos pos) {
        var queue=transactionQueue==null?space.scheduledTicks():transactionQueue; var check=queue.copy();
        long order=refreshOrder(check,nextOrder);
        check.schedule(new CubeScheduledTicks.Tick(SavedCubeTick.Kind.BLOCK,"minecraft:chest",coordinate(pos),Math.addExact(check.clock(),5),0,order));
    }
    private boolean known(BlockPos pos) { return space.block(coordinate(pos)).isPresent(); }
    private BlockState stored(BlockPos pos) {
        int id=space.block(coordinate(pos)).orElseThrow(() -> new IllegalStateException("chest cube is not resident: "+pos));
        var state=Block.BLOCK_STATE_REGISTRY.byId(id); if(state==null) throw new IllegalStateException("unknown native block state ID"); return state;
    }
    private void requireAttachment(Level level) {
        space.scheduledTicks().assertOwner();
        if(lookup(level).orElse(null)!=this || DeepCubeDomain.space(level).orElse(null)!=space) throw new IllegalStateException("stale chest attachment");
    }
    private static void requireDeep(Level level,BlockPos pos) { if(!DeepCubeDomain.isDeep(level,pos)) throw new IllegalArgumentException("not a deep chest position"); }
    private static boolean single(BlockState state) { return state.is(Blocks.CHEST) && state.getValue(ChestBlock.TYPE)==ChestType.SINGLE && !state.getValue(ChestBlock.WATERLOGGED); }
    private static BlockCoordinate coordinate(BlockPos pos) { return new BlockCoordinate(pos.getX(),pos.getY(),pos.getZ()); }
    private static long refreshOrder(CubeScheduledTicks queue,long candidate) { for(var tick:queue.due(Long.MAX_VALUE,4096)) if(tick.order()>=candidate) candidate=Math.incrementExact(tick.order()); return candidate; }
    private static SavedCubeBlockEntity record(BlockPos pos,CompoundTag tag) throws IOException {
        var bytes=new ByteArrayOutputStream();
        NbtIo.write(tag,new DataOutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException { reserve(1); bytes.write(value); }
            @Override public void write(byte[] data,int offset,int length) throws IOException { reserve(length); bytes.write(data,offset,length); }
            private void reserve(int length) throws IOException {
                if(length<0 || length>SavedCubeBlockEntity.MAX_PAYLOAD_BYTES-bytes.size()) throw new IOException("chest placement payload exceeds byte budget");
            }
        }));
        return new SavedCubeBlockEntity(coordinate(pos),"minecraft:chest",bytes.toByteArray());
    }
    private static final class LiveChest extends ChestBlockEntity {
        private final DeepChestEngine engine;
        private SavedCubeBlockEntity lastSaved;
        private boolean omitEmptyForgeData;
        LiveChest(BlockPos pos,BlockState state,DeepChestEngine engine) { super(pos,state); this.engine=engine; }
        @Override public void load(CompoundTag tag) {
            // Native load only overwrites these optional fields when present; rollback is a full replacement.
            setCustomName(null); setLootTable(null,0);
            var persistent=getPersistentData(); for(var key:Set.copyOf(persistent.getAllKeys())) persistent.remove(key);
            super.load(tag); omitEmptyForgeData=!tag.contains("ForgeData");
        }
        @Override protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            if(omitEmptyForgeData && tag.getCompound("ForgeData").isEmpty()) tag.remove("ForgeData");
        }
        @Override public void setChanged() { if(lastSaved!=null && !isRemoved()) engine.persist(this); }
        @Override public void startOpen(Player player) { if(!isRemoved() && !player.isSpectator()) engine.validateRecheckCapacity(getBlockPos()); super.startOpen(player); }
        @Override public boolean stillValid(Player player) {
            if(isRemoved() || getLevel()==null || !engine.known(getBlockPos())) return false;
            return super.stillValid(player);
        }
    }
    private static final class ClickTransaction {
        final LiveChest chest; final SavedCubeBlockEntity nativeBefore,savedBefore;
        final List<ItemStack> playerItems; final ItemStack carried;
        ClickTransaction(LiveChest chest,SavedCubeBlockEntity nativeBefore,SavedCubeBlockEntity savedBefore,ServerPlayer player,ItemStack carried) {
            this.chest=chest; this.nativeBefore=nativeBefore; this.savedBefore=savedBefore; this.carried=carried.copy();
            var items=new ArrayList<ItemStack>(); for(int i=0;i<player.getInventory().getContainerSize();i++) items.add(player.getInventory().getItem(i).copy());
            playerItems=List.copyOf(items);
        }
    }
}
