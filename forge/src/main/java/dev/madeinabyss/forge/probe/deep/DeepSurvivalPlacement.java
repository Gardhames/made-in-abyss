package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.cube.DeepCreativeEdits;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.CubeStore;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.util.BlockSnapshot;

/** One native stone placement. Full-XYZ overlay, Forge snapshots, atomic cube save before publication. */
public final class DeepSurvivalPlacement {
    private static final Map<Level,DeepSurvivalPlacement> ENGINES = Collections.synchronizedMap(new WeakHashMap<>());
    private final SparseCubeSpace space;
    private final CubeStore store;
    private Transaction transaction;
    private DeepManagedItemEngine drops;
    private String lastError = "";
    private DeepSurvivalPlacement(SparseCubeSpace space,CubeStore store) { this.space=space; this.store=store; }
    public static DeepSurvivalPlacement attach(Level level,SparseCubeSpace space,CubeStore store) {
        if(DeepCubeDomain.space(level).orElseThrow()!=space || ENGINES.containsKey(level))
            throw new IllegalStateException("placement requires exclusive matching cube attachment");
        var engine=new DeepSurvivalPlacement(space,java.util.Objects.requireNonNull(store));
        ENGINES.put(level,engine); return engine;
    }
    public static void detach(Level level) { ENGINES.remove(level); }
    public static Optional<DeepSurvivalPlacement> lookup(Level level) { return Optional.ofNullable(ENGINES.get(level)); }
    public static boolean active(Level level) { var engine=ENGINES.get(level);return engine!=null && engine.transaction!=null; }
    public static Optional<BlockState> overlay(Level level,BlockPos pos) {
        var engine=ENGINES.get(level);
        return engine==null || engine.transaction==null || !pos.equals(engine.transaction.target)
                ?Optional.empty():Optional.of(engine.transaction.next);
    }
    public String lastError() { return lastError; }
    public void bindDrops(DeepManagedItemEngine engine){drops=java.util.Objects.requireNonNull(engine);}
    public boolean captureDrop(net.minecraft.world.entity.Entity candidate) {
        if(transaction==null || !transaction.breaking)throw new UnsupportedOperationException("entity spawn during placement unavailable");
        if(transaction.drop!=null || !(candidate instanceof net.minecraft.world.entity.item.ItemEntity nativeItem)
                || !nativeItem.getItem().is(Items.COBBLESTONE) || nativeItem.getItem().getCount()!=1)
            throw new UnsupportedOperationException("unsupported native mining loot");
        transaction.drop=nativeItem;return true;
    }
    public boolean readyForMining(ServerPlayer player,BlockPos target) {
        var level=player.level();
        if(player.gameMode.getGameModeForPlayer()!=GameType.SURVIVAL || !player.isAlive()
                || !DeepCubeDomain.isDeep(level,target) || !player.getMainHandItem().is(Items.DIAMOND_PICKAXE)
                || player.getMainHandItem().isEnchanted() || player.getMainHandItem().getDamageValue()>=player.getMainHandItem().getMaxDamage()-1
                || !player.canReach(target,1.5) || !DeepCubeDomain.allowsMove(level,player.getBoundingBox())) return false;
        var state=space.block(coordinate(target));
        if(state.isEmpty() || !Block.stateById(state.getAsInt()).is(Blocks.STONE)) return false;
        try {for(Direction direction:Direction.values()) if(!supported(level.getBlockState(target.relative(direction))))return false;}
        catch(RuntimeException unavailable){return false;}
        return true;
    }
    /** Actual native destroyBlock is used only after the native server mining clock admits it. */
    public boolean mine(ServerPlayer player,BlockPos target) {
        if(transaction!=null)throw new IllegalStateException("nested survival mining");
        var level=player.level();lastError="";
        if(!readyForMining(player,target)){refuse("unavailable_mining_target");return false;}
        boolean loot=level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS);
        if(loot && (drops==null || !drops.canAcceptDrop())){refuse("managed_drop_slot_unavailable");return false;}
        var original=player.getMainHandItem().copy();var slots=new ArrayList<ItemStack>();
        for(var slot:player.inventoryMenu.slots)slots.add(slot.getItem().copy());
        var carried=player.inventoryMenu.getCarried().copy();int selected=player.getInventory().selected;
        boolean capture=level.captureBlockSnapshots,restoring=level.restoringBlockSnapshots;
        var snapshots=new ArrayList<>(level.capturedBlockSnapshots);
        try {
            transaction=new Transaction(target,level.getBlockState(target));transaction.breaking=true;
            if(!player.gameMode.destroyBlock(target) || !transaction.next.isAir()){refuse("native_break_refused");return false;}
            var expected=original.copy();expected.setDamageValue(original.getDamageValue()+1);
            for(int i=0;i<slots.size();i++) {
                var slot=player.inventoryMenu.slots.get(i);
                var wanted=slot.container==player.getInventory() && slot.getContainerSlot()==selected?expected:slots.get(i);
                if(!same(wanted,slot.getItem()))throw new UnsupportedOperationException("unsupported native mining inventory effect");
            }
            if(!same(carried,player.inventoryMenu.getCarried()) || selected!=player.getInventory().selected)
                throw new UnsupportedOperationException("unsupported mining cursor/selection effect");
            if(loot) {
                if(transaction.drop==null)throw new UnsupportedOperationException("expected native stone loot missing");
                drops.stageDrop(transaction.drop,target);transaction.dropStaged=true;
            }
            var key=CubeKey.fromBlock(coordinate(target));var old=space.snapshot(key).orElseThrow();var states=old.states();
            states[(Math.floorMod(target.getY(),16)<<8)|(Math.floorMod(target.getZ(),16)<<4)|Math.floorMod(target.getX(),16)]=Block.getId(transaction.next);
            store.write(new CubeSnapshot(key,states,old.ticks(),old.blockEntities()));
            space.setBlock(coordinate(target),Block.getId(transaction.next));transaction.committed=true;
            if(loot) {
                // AIR is durable now. If final item publication fails, the source-linked frame can recover it.
                try {drops.reconcile();drops.pause(false);}catch(IOException failure){lastError="drop_publication_pending: "+failure;}
            }
            player.inventoryMenu.broadcastChanges();return true;
        }catch(IOException | RuntimeException failure){refuse(failure.getClass().getSimpleName()+": "+failure.getMessage());return false;}
        finally {
            if(transaction!=null && !transaction.committed) {
                for(int i=0;i<slots.size();i++)player.inventoryMenu.slots.get(i).set(slots.get(i).copy());
                player.inventoryMenu.setCarried(carried);player.getInventory().selected=selected;player.inventoryMenu.broadcastFullState();
                if(transaction.dropStaged)try{drops.abortDrop();}catch(IOException failure){lastError+="; pending_drop_rollback: "+failure;}
            }
            level.captureBlockSnapshots=capture;level.restoringBlockSnapshots=restoring;
            level.capturedBlockSnapshots.clear();level.capturedBlockSnapshots.addAll(snapshots);transaction=null;
        }
    }

    public InteractionResult place(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit) {
        if(transaction!=null) throw new IllegalStateException("nested survival placement");
        lastError="";
        if(player.level()!=level || player.gameMode.getGameModeForPlayer()!=GameType.SURVIVAL || !player.isAlive()
                || !stack.is(Items.STONE) || stack.isEmpty() || stack!=player.getItemInHand(hand)
                || level.captureBlockSnapshots || level.restoringBlockSnapshots || !level.capturedBlockSnapshots.isEmpty())
            return refuse("unsupported_placement_context");
        if(stack.hasTag() && (stack.getTag().contains("BlockEntityTag") || stack.getTag().contains("BlockStateTag")))
            return refuse("unsupported_block_item_tag");
        var current=space.block(coordinate(hit.getBlockPos()));
        if(current.isEmpty()) return refuse("unknown_clicked_cube");
        var request=new DeepCreativeEdits.Request(true,hit.getBlockPos(),hit.getDirection(),hit.getLocation(),current.getAsInt());
        var plan=DeepCreativeEdits.validate(space,request,player.getEyePosition(),player.getBoundingBox(),Blocks.STONE.defaultBlockState(),player.getBlockReach());
        if(!plan.accepted()) return refuse(plan.reason());
        if(!level.getWorldBorder().isWithinBounds(plan.changed())) return refuse("outside_world_border");
        if(!DeepCubeDomain.allowsMove(level,player.getBoundingBox())) return refuse("unknown_player_halo");
        var target=plan.changed();
        try {
            // Only full inert neighbors are supported. No fluid, BE, shape-dependent or remote callback writes.
            for(Direction direction:Direction.values()) if(!supported(level.getBlockState(target.relative(direction))))
                return refuse("unsupported_neighbor");
        } catch(RuntimeException unavailable) { return refuse("unknown_neighbor"); }
        if(!level.getEntities(player,new net.minecraft.world.phys.AABB(target),e->!e.isSpectator()).isEmpty())
            return refuse("entity_collision");
        var original=stack.copy();
        var slots=new ArrayList<ItemStack>();
        for(var slot:player.inventoryMenu.slots) slots.add(slot.getItem().copy());
        var carried=player.inventoryMenu.getCarried().copy();
        int selected=player.getInventory().selected;
        boolean capture=level.captureBlockSnapshots,restoring=level.restoringBlockSnapshots;
        var snapshots=new ArrayList<>(level.capturedBlockSnapshots);
        try {
            transaction=new Transaction(target,level.getBlockState(target));
            // Re-enters actual ServerPlayerGameMode; the active scope prevents recursive interception.
            var result=player.gameMode.useItemOn(player,level,stack,hand,hit);
            if(!result.consumesAction() || !transaction.next.is(Blocks.STONE)) return refuse("native_placement_refused");
            var expected=original.copy(); expected.shrink(1);
            if(!same(expected,player.getItemInHand(hand))) throw new UnsupportedOperationException("native stack cost/tag changed outside stone profile");
            for(int i=0;i<slots.size();i++) {
                var slot=player.inventoryMenu.slots.get(i);
                // The selected inventory slot, or offhand slot, is the sole permitted mutable native slot.
                int allowed=hand==InteractionHand.MAIN_HAND?selected:40;
                var wanted=slot.container==player.getInventory() && slot.getContainerSlot()==allowed?expected:slots.get(i);
                if(!same(wanted,slot.getItem())) throw new UnsupportedOperationException("unsupported callback inventory effect");
            }
            if(!same(carried,player.inventoryMenu.getCarried()) || selected!=player.getInventory().selected)
                throw new UnsupportedOperationException("unsupported callback cursor/selection effect");
            var key=CubeKey.fromBlock(coordinate(target));
            var old=space.snapshot(key).orElseThrow(); var states=old.states();
            states[(Math.floorMod(target.getY(),16)<<8)|(Math.floorMod(target.getZ(),16)<<4)|Math.floorMod(target.getX(),16)]=Block.getId(transaction.next);
            var saved=new CubeSnapshot(key,states,old.ticks(),old.blockEntities());
            store.write(saved); // Failed acknowledgement leaves live geometry/inventory at the old values.
            space.setBlock(coordinate(target),Block.getId(transaction.next));
            transaction.committed=true;
            player.inventoryMenu.broadcastChanges();
            return result;
        } catch(IOException | RuntimeException failure) { return refuse(failure.getClass().getSimpleName()+": "+failure.getMessage()); }
        finally {
            if(transaction!=null && !transaction.committed) {
                for(int i=0;i<slots.size();i++) player.inventoryMenu.slots.get(i).set(slots.get(i).copy());
                player.inventoryMenu.setCarried(carried); player.getInventory().selected=selected;
                player.inventoryMenu.broadcastFullState();
            }
            level.captureBlockSnapshots=capture;level.restoringBlockSnapshots=restoring;
            level.capturedBlockSnapshots.clear();level.capturedBlockSnapshots.addAll(snapshots);
            transaction=null;
        }
    }

    /** Native Level write dispatch. A callback cannot escape this one-cell staged transaction. */
    public boolean write(Level level,BlockPos pos,BlockState next,int flags,int depth) {
        if(transaction==null) return false;
        if(!pos.equals(transaction.target) || (!next.isAir() && !next.is(Blocks.STONE)) || depth<0 || (flags & ~127)!=0)
            throw new UnsupportedOperationException("unsupported survival placement write");
        var previous=transaction.next;
        if(previous==next) return false;
        if(level.captureBlockSnapshots && !level.isClientSide)
            level.capturedBlockSnapshots.add(BlockSnapshot.create(level.dimension(),level,pos,flags));
        transaction.next=next;
        if(!level.captureBlockSnapshots) {
            previous.onRemove(level,pos,next,false); next.onPlace(level,pos,previous,false);
            notifyNeighbors(level,pos,previous,next,flags,depth);
        }
        return true;
    }
    public void notifyNeighbors(Level level,BlockPos pos,BlockState previous,BlockState next,int flags,int depth) {
        if(transaction==null || !pos.equals(transaction.target)) throw new UnsupportedOperationException("unowned survival notification");
        for(Direction direction:Direction.values()) {
            var other=pos.relative(direction);var state=level.getBlockState(other);
            if(!supported(state)) throw new UnsupportedOperationException("unsupported survival neighbor callback");
            if((flags&1)!=0) state.neighborChanged(level,other,previous.getBlock(),pos,false);
            if((flags&16)==0 && depth>0 && state.updateShape(direction.getOpposite(),next,level,other,pos)!=state)
                throw new UnsupportedOperationException("survival neighbor shape mutation is unavailable");
        }
        next.onBlockStateChange(level,pos,previous);
    }
    private InteractionResult refuse(String error) { lastError=error;return InteractionResult.FAIL; }
    private static boolean supported(BlockState state) { return state.isAir() || DeepCreativeEdits.inert(state) || state.is(Blocks.BEDROCK); }
    private static boolean same(ItemStack a,ItemStack b) { return ItemStack.isSameItemSameTags(a,b) && a.getCount()==b.getCount(); }
    private static BlockCoordinate coordinate(BlockPos pos) {return new BlockCoordinate(pos.getX(),pos.getY(),pos.getZ());}
    private static final class Transaction {
        final BlockPos target;BlockState next;boolean committed,breaking,dropStaged;
        net.minecraft.world.entity.item.ItemEntity drop;
        Transaction(BlockPos target,BlockState previous) {this.target=target.immutable();this.next=previous;}
    }
}
