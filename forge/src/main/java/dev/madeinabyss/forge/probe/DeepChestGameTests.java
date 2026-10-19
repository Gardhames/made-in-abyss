package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepChestEngine;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.tick.SavedCubeTick;
import net.minecraft.world.ticks.TickPriority;
import java.util.List;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real native BE/menu operations at signed deep coordinates, never SimpleContainer substitutes. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepChestGameTests {
    private static final BlockPos POS=new BlockPos(-1,-19999,-1);
    @GameTest(template="empty")
    public static void nativeInventoryNbtSurvivesUnloadAndRestoresNewInstance(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space);
        try {
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"single native chest placed atomically");
            var chest=engine.get(level,POS);
            h.assertTrue(chest!=null && level.getBlockEntity(POS)==chest,"native Level returns same live chest");
            var stack=new ItemStack(Items.DIAMOND,17); stack.setHoverName(Component.literal("deep cargo"));
            stack.getOrCreateTag().putInt("signedY",-19999); chest.setItem(0,stack);
            chest.setCustomName(Component.literal("Abyss chest")); chest.getPersistentData().putString("provenance","ORIGINAL"); chest.setChanged();
            var snapshot=space.unload(new CubeKey(-1,-1250,-1)).orElseThrow(); engine.reconcile(level);
            h.assertTrue(chest.isRemoved() && engine.liveCount()==0,"unload invalidates actual native BE");
            space.load(snapshot); var restored=engine.get(level,POS);
            h.assertTrue(restored!=chest && restored.getItem(0).getCount()==17 && restored.getItem(0).is(Items.DIAMOND),"new BE reloads native inventory");
            h.assertTrue(restored.getItem(0).getTag().getInt("signedY")==-19999 && restored.getItem(0).getHoverName().getString().equals("deep cargo"),"full nested item NBT preserved");
            h.assertTrue(restored.getCustomName().getString().equals("Abyss chest") && restored.getPersistentData().getString("provenance").equals("ORIGINAL"),"name and ForgeData preserved");
            h.assertTrue(snapshot.blockEntities().size()==1,"one inventory record survives cube snapshot");
        } finally { clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void nativeChestMenuClickAndLidUseSameAuthoritativeInventory(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space); var player=h.makeMockPlayer();
        try {
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"native chest seed");
            var chest=engine.get(level,POS); chest.setItem(0,new ItemStack(Items.EMERALD,9));
            player.setPos(-.5,-19999,-.5); var menu=ChestMenu.threeRows(1,player.getInventory(),chest); player.containerMenu=menu;
            h.assertTrue(menu.stillValid(player) && ChestBlockEntity.getOpenCount(level,POS)==1,"native startOpen and stillValid use deep identity");
            ChestBlockEntity.lidAnimateTick(level,POS,chest.getBlockState(),chest);
            h.assertTrue(chest.getOpenNess(1)>0,"native open event animates lid");
            menu.clicked(0,0,ClickType.PICKUP,player);
            h.assertTrue(chest.getItem(0).isEmpty() && menu.getCarried().is(Items.EMERALD) && menu.getCarried().getCount()==9,"native menu removes authoritative item once");
            menu.clicked(1,0,ClickType.PICKUP,player);
            h.assertTrue(menu.getCarried().isEmpty() && chest.getItem(1).getCount()==9,"native menu deposits authoritative item once");
            menu.removed(player); player.containerMenu=player.inventoryMenu;
            h.assertTrue(ChestBlockEntity.getOpenCount(level,POS)==0,"native stopOpen decrements actual counter");
            for(int i=0;i<12;i++) ChestBlockEntity.lidAnimateTick(level,POS,chest.getBlockState(),chest);
            h.assertTrue(chest.getOpenNess(1)==0,"native close event shuts actual lid");
            engine.flush(level); var snapshot=space.unload(new CubeKey(-1,-1250,-1)).orElseThrow(); engine.reconcile(level); space.load(snapshot);
            var restored=engine.get(level,POS);
            h.assertTrue(restored.getItem(0).isEmpty() && restored.getItem(1).getCount()==9,"native slot callback persists move without duplication");
        } finally { clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void unsupportedAndUnknownPlacementHaveNoPhantomInventory(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space);
        try {
            long generation=space.contentGeneration();
            h.assertTrue(!engine.place(level,POS,Blocks.HOPPER.defaultBlockState(),null),"unsupported BE rejected");
            h.assertTrue(!engine.place(level,POS,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE,ChestType.LEFT),null),"double chest refused");
            h.assertTrue(space.contentGeneration()==generation && space.blockEntity(coordinate(POS)).isEmpty() && engine.liveCount()==0,"rejection leaves states and records unchanged");
            var unknown=new BlockPos(1000,-19999,1000);
            h.assertTrue(!engine.place(level,unknown,Blocks.CHEST.defaultBlockState(),null),"unknown placement refused");
            boolean failed=false; try { engine.get(level,unknown); } catch(IllegalStateException expected) { failed=true; }
            h.assertTrue(failed,"unknown BE query remains strict");
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"valid place after refusals");
            var chest=engine.get(level,POS); chest.setItem(4,new ItemStack(Items.GOLD_INGOT,2));
            h.assertTrue(engine.remove(level,POS) && chest.isRemoved() && level.getBlockEntity(POS)==null,"remove invalidates BE and makes known absence");
            h.assertTrue(space.blockEntity(coordinate(POS)).isEmpty() && level.getBlockState(POS).isAir(),"removal atomically deletes inventory and chest state");
        } finally { clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void deepAttachmentLeavesOrdinaryChestNative(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); attach(h,space); var p=h.absolutePos(new BlockPos(1,2,1)); var before=level.getBlockState(p);
        try {
            level.setBlock(p,Blocks.CHEST.defaultBlockState(),2); var chest=(ChestBlockEntity)level.getBlockEntity(p);
            chest.setItem(3,new ItemStack(Items.IRON_INGOT,5));
            h.assertTrue(chest.getItem(3).getCount()==5 && space.blockEntity(coordinate(POS)).isEmpty(),"ordinary native inventory does not enter deep storage");
        } finally { level.setBlock(p,before,2); clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void nativeOpenerRecheckIsPersistentAndPausedWhileUnloaded(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space); var player=h.makeMockPlayer();
        try {
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"native chest seed");
            var chest=engine.get(level,POS); chest.startOpen(player);
            var saved=space.unload(new CubeKey(-1,-1250,-1)).orElseThrow(); engine.reconcile(level);
            h.assertTrue(saved.ticks().size()==1 && saved.ticks().get(0).kind()==SavedCubeTick.Kind.BLOCK && saved.ticks().get(0).type().equals("minecraft:chest") && saved.ticks().get(0).delay()==5,"native schedule5 survives snapshot with exact type/delay");
            engine.step(level,100); space.load(saved); var restored=engine.get(level,POS);
            engine.step(level,104); h.assertTrue(space.scheduledTicks().pendingCount()==1,"loaded clock restores remaining delay instead of expiring unloaded tick");
            engine.step(level,105); h.assertTrue(space.scheduledTicks().pendingCount()==0 && ChestBlockEntity.getOpenCount(level,POS)==0,"actual native recheck consumes orphan once without phantom viewers");
            h.assertTrue(restored.getOpenNess(1)==0,"reload does not retain stale lid state");
        } finally { clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void latePersistentBlockOrderPrecedesNewNativeSchedule(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space);
        try {
            var remote=new BlockCoordinate(32,-19999,32);
            space.load(new CubeSnapshot(new CubeKey(2,-1250,2),new int[CubeSnapshot.BLOCK_COUNT],List.of(new SavedCubeTick(SavedCubeTick.Kind.BLOCK,"minecraft:chest",remote,5,0,9000))));
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"native chest seed after late saved queue");
            level.scheduleTick(POS,Blocks.CHEST,5,TickPriority.NORMAL);
            var due=space.scheduledTicks().due(5,20);
            h.assertTrue(due.size()==2 && due.get(0).position().equals(remote) && due.get(1).order()>9000,"native chest callback follows late saved queue order");
            engine.step(level,5);
            h.assertTrue(space.scheduledTicks().pendingCount()==0,"stale AIR tick and exact native CHEST tick both safely consumed");
        } finally { clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void directMutableInventoryAndForgeDataRemainResidentUntilFlush(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space);
        try {
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"native chest seed");
            var chest=engine.get(level,POS); chest.setItem(0,new ItemStack(Items.DIAMOND,10)); chest.setItem(1,new ItemStack(Items.EMERALD,4));
            chest.getItem(0).shrink(2); chest.removeItemNoUpdate(1); chest.getPersistentData().putString("rawMutation","preserved");
            h.assertTrue(engine.get(level,POS)==chest && engine.get(level,POS).getItem(0).getCount()==8,"resident native instance owns direct unsignalled edits");
            engine.flush(level); var snapshot=space.unload(new CubeKey(-1,-1250,-1)).orElseThrow(); engine.reconcile(level); space.load(snapshot);
            var restored=engine.get(level,POS);
            h.assertTrue(restored.getItem(0).getCount()==8 && restored.getItem(1).isEmpty() && restored.getPersistentData().getString("rawMutation").equals("preserved"),"flush captures direct stack/removeNoUpdate/ForgeData");
            restored.clearContent(); engine.flush(level);
            var empty=space.unload(new CubeKey(-1,-1250,-1)).orElseThrow(); engine.reconcile(level); space.load(empty);
            h.assertTrue(engine.get(level,POS).isEmpty(),"native clearContent without setChanged survives next flush");
        } finally { clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void earliestInvalidChestTicksDoNotStarveLaterNativeWork(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space);
        try {
            for(int i=0;i<64;i++) {
                var p=new BlockPos(-16+(i&15),-19999,-16+(i>>4));
                space.setBlock(coordinate(p),Block.getId(Blocks.CHEST.defaultBlockState()));
                engine.schedule(level,p,Blocks.CHEST,0,TickPriority.NORMAL);
            }
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"65th valid native chest seed");
            engine.schedule(level,POS,Blocks.CHEST,0,TickPriority.NORMAL); engine.step(level,0);
            h.assertTrue(engine.stats().completed()==1 && engine.stats().rolledBack()==64 && space.scheduledTicks().pendingCount()==64,"bounded scan retains failed orphan ticks yet executes later healthy native chest");
        } finally { clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void overflowedPersistenceRestoresNativeInventory(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space);
        try {
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"native chest seed");
            var chest=engine.get(level,POS); chest.setItem(0,new ItemStack(Items.DIAMOND,3)); var before=space.blockEntity(coordinate(POS)).orElseThrow();
            try { var field=SparseCubeSpace.class.getDeclaredField("contentGeneration"); field.setAccessible(true); field.setLong(space,Long.MAX_VALUE); }
            catch(ReflectiveOperationException exception) { throw new IllegalStateException("unable to set mathematical boundary fixture",exception); }
            boolean refused=false; try { chest.setItem(0,new ItemStack(Items.DIAMOND,9)); } catch(RuntimeException expected) { refused=true; }
            h.assertTrue(refused && space.blockEntity(coordinate(POS)).orElseThrow().equals(before),"overflow refuses authoritative metadata commit");
            h.assertTrue(chest.getItem(0).getCount()==3,"overflow also restores actual native inventory before control returns");
        } finally { clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void invalidMutationRestoresAbsentNativeNameAndForgeData(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space);
        try {
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"native chest seed");
            var chest=engine.get(level,POS); chest.setItem(0,new ItemStack(Items.DIAMOND,3)); var before=space.blockEntity(coordinate(POS)).orElseThrow();
            chest.setCustomName(Component.literal("rejected name")); chest.getPersistentData().putString("rejectedField","rejected value");
            var big=new ItemStack(Items.GOLD_INGOT); big.getOrCreateTag().putByteArray("large",new byte[65536]);
            boolean refused=false; try { chest.setItem(1,big); } catch(RuntimeException expected) { refused=true; }
            h.assertTrue(refused && space.blockEntity(coordinate(POS)).orElseThrow().equals(before) && chest.getItem(1).isEmpty(),"invalid payload keeps original native inventory and saved record");
            h.assertTrue(chest.getCustomName()==null && !chest.getPersistentData().contains("rejectedField"),"native restore clears fields absent in original metadata");
        } finally { clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void transactionalNativeClickMovesAndQuickMovesExactStacks(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space); ServerPlayer player=null;
        try {
            player=registeredPlayer(h);
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"native chest seed");
            var chest=engine.get(level,POS); player.setPos(-.5,-19999,-.5); var menu=ChestMenu.threeRows(3,player.getInventory(),chest); player.containerMenu=menu;
            menu.setCarried(new ItemStack(Items.EMERALD,9));
            h.assertTrue(engine.click(menu,1,0,ClickType.PICKUP,player),"accepted native PICKUP transaction");
            h.assertTrue(menu.getCarried().isEmpty() && chest.getItem(1).getCount()==9,"native cursor deposited once");
            h.assertTrue(engine.click(menu,1,0,ClickType.QUICK_MOVE,player),"accepted native QUICK_MOVE transaction");
            h.assertTrue(chest.getItem(1).isEmpty() && player.getInventory().countItem(Items.EMERALD)==9,"native shift transfer preserves item count");
            h.assertTrue(engine.stats().refusedClicks()==0,"valid clicks do not increment refusal");
            menu.removed(player); player.containerMenu=player.inventoryMenu;
        } finally { removePlayer(h,player); clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void oversizedCarriedClickRestoresChestPlayerAndCursor(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space); ServerPlayer player=null;
        try {
            player=registeredPlayer(h);
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"native chest seed");
            var chest=engine.get(level,POS); chest.setItem(0,new ItemStack(Items.DIAMOND,17)); player.setPos(-.5,-19999,-.5);
            var menu=ChestMenu.threeRows(4,player.getInventory(),chest); player.containerMenu=menu;
            player.getInventory().setItem(0,new ItemStack(Items.IRON_INGOT,5));
            var carried=new ItemStack(Items.GOLD_INGOT); carried.getOrCreateTag().putByteArray("large",new byte[65536]); menu.setCarried(carried);
            var baseline=carried.copy(); var before=space.blockEntity(coordinate(POS)).orElseThrow(); long generation=space.contentGeneration();
            h.assertTrue(!engine.click(menu,1,0,ClickType.PICKUP,player),"oversized native click refused without escaping exception");
            h.assertTrue(space.contentGeneration()==generation && space.blockEntity(coordinate(POS)).orElseThrow().equals(before),"rejected native click never publishes intermediate chest");
            h.assertTrue(chest.getItem(0).getCount()==17 && chest.getItem(1).isEmpty() && player.getInventory().getItem(0).getCount()==5 && ItemStack.matches(menu.getCarried(),baseline),
                    "native click restore counts chest0="+chest.getItem(0).getCount()+" chest1="+chest.getItem(1).getCount()+" player0="+player.getInventory().getItem(0).getCount()+" cursor="+menu.getCarried().getCount()+" match="+ItemStack.matches(menu.getCarried(),baseline)+" error="+engine.lastError());
            h.assertTrue(engine.stats().refusedClicks()==1,"refusal exposed once");
            menu.setCarried(ItemStack.EMPTY); menu.removed(player); player.containerMenu=player.inventoryMenu;
        } finally { removePlayer(h,player); clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void registeredNativePlayerMenuClosesOnResidencyUnload(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space); ServerPlayer player=null;
        try {
            player=registeredPlayer(h);
            h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"native chest seed");
            var chest=engine.get(level,POS); player.setPos(-.5,-19999,-.5); var menu=ChestMenu.threeRows(5,player.getInventory(),chest); player.containerMenu=menu;
            h.assertTrue(level.players().contains(player) && menu.stillValid(player),"real registered native player owns same valid chest menu");
            space.unload(new CubeKey(-1,-1250,-1)); engine.reconcile(level);
            h.assertTrue(chest.isRemoved() && !menu.stillValid(player) && player.containerMenu==player.inventoryMenu,"residency unload closes registered native menu and revokes stale identity");
        } finally { removePlayer(h,player); clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void nativeResidentCacheBudgetRefusesWithoutPhantomPlacement(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space);
        try {
            for(int x=-1;x<=0;x++) for(int y=-1251;y<=-1250;y++) for(int z=-1;z<=0;z++) for(int i=0;i<64;i++) {
                var p=new BlockPos(x*16+(i&15),y*16+1,z*16+(i>>4));
                h.assertTrue(engine.place(level,p,Blocks.CHEST.defaultBlockState(),null),"bounded live native chest fixture accepts first512");
            }
            var rejected=new BlockPos(0,-19983,0); long generation=space.contentGeneration();
            h.assertTrue(engine.liveCount()==512 && !engine.place(level,rejected,Blocks.CHEST.defaultBlockState(),null),"513th native live instance refused before placement");
            h.assertTrue(space.contentGeneration()==generation && level.getBlockState(rejected).isAir() && space.blockEntity(coordinate(rejected)).isEmpty(),"native cache cap retains exact state and metadata");
        } finally { clean(h); } h.succeed();
    }
    @GameTest(template="empty")
    public static void actualNativeClickPacketRejectsOversizeWithoutLosingCursor(GameTestHelper h) {
        var space=window(); var level=h.getLevel(); var engine=attach(h,space); ServerPlayer player=null;
        try {
            player=registeredPlayer(h); h.assertTrue(engine.place(level,POS,Blocks.CHEST.defaultBlockState(),null),"native chest seed");
            var chest=engine.get(level,POS); chest.setItem(0,new ItemStack(Items.DIAMOND,17)); player.setPos(-.5,-19999,-.5);
            var menu=ChestMenu.threeRows(6,player.getInventory(),chest); player.containerMenu=menu;
            var carried=new ItemStack(Items.GOLD_INGOT); carried.getOrCreateTag().putByteArray("large",new byte[65536]); menu.setCarried(carried);
            var baseline=carried.copy(); var before=space.blockEntity(coordinate(POS)).orElseThrow(); long generation=space.contentGeneration();
            boolean escaped=false;
            try { player.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId,menu.getStateId(),1,0,ClickType.PICKUP,ItemStack.EMPTY,new Int2ObjectOpenHashMap<>())); }
            catch(RuntimeException exception) { escaped=true; }
            h.assertTrue(!escaped,"actual native packet handler returns without unwrapped chest exception");
            h.assertTrue(space.contentGeneration()==generation && space.blockEntity(coordinate(POS)).orElseThrow().equals(before)
                    && chest.getItem(0).getCount()==17 && chest.getItem(1).isEmpty() && ItemStack.matches(menu.getCarried(),baseline),"actual packet adapter restores authoritative inventory and cursor");
            h.assertTrue(engine.stats().refusedClicks()==1,"actual native packet enters bounded click adapter once");
        } finally { removePlayer(h,player); clean(h); } h.succeed();
    }
    // Native player/listener, like ProbeHomeGameTests; login needs a Channel, Level registration does not.
    private static ServerPlayer registeredPlayer(GameTestHelper h) {
        var level=h.getLevel(); var player=new ServerPlayer(level.getServer(),level,new GameProfile(UUID.randomUUID(),"DeepChestTest"));
        new ServerGamePacketListenerImpl(level.getServer(),new Connection(PacketFlow.SERVERBOUND),player);
        var ordinary=h.absolutePos(new BlockPos(1,2,1)); player.setPos(ordinary.getX(),ordinary.getY(),ordinary.getZ());
        level.addNewPlayer(player); return player;
    }
    private static void removePlayer(GameTestHelper h,ServerPlayer player) {
        if(player==null) return;
        player.containerMenu.setCarried(ItemStack.EMPTY); player.containerMenu=player.inventoryMenu;
        var ordinary=h.absolutePos(new BlockPos(1,2,1)); player.setPos(ordinary.getX(),ordinary.getY(),ordinary.getZ());
        h.getLevel().removePlayerImmediately(player,Entity.RemovalReason.DISCARDED);
    }
    private static DeepChestEngine attach(GameTestHelper h,SparseCubeSpace s) { DeepCubeDomain.attach(h.getLevel(),s,false); return DeepChestEngine.attach(h.getLevel(),s); }
    private static void clean(GameTestHelper h) { DeepChestEngine.detach(h.getLevel()); DeepCubeDomain.detach(h.getLevel()); }
    private static BlockCoordinate coordinate(BlockPos p) { return new BlockCoordinate(p.getX(),p.getY(),p.getZ()); }
    private static SparseCubeSpace window() { var s=new SparseCubeSpace(); for(int x=-1;x<=0;x++) for(int y=-1251;y<=-1249;y++) for(int z=-1;z<=0;z++) s.loadEmpty(new CubeKey(x,y,z)); return s; }
}

