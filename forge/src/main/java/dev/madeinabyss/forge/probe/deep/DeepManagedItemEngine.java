package dev.madeinabyss.forge.probe.deep;

import com.google.gson.JsonObject;
import dev.madeinabyss.forge.cube.ManagedItemPersistence;
import dev.madeinabyss.forge.cube.ManagedItemSnapshot;
import dev.madeinabyss.forge.cube.ManagedItemCodec;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.cube.CubeKey;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.lang.ref.WeakReference;
import com.google.gson.JsonArray;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;

/** One native object with a single acknowledged UUID store and independent 3D residency. */
public final class DeepManagedItemEngine {
    private static final Map<ItemEntity,DeepManagedItemEngine> OWNERS=new java.util.IdentityHashMap<>();
    public record Observer(ServerPlayer player, UUID session, Set<CubeKey> window) { }
    private final WeakReference<ServerLevel> level;
    private final SparseCubeSpace space;
    private final ManagedItemPersistence store;
    private final UUID uuid;
    private final java.util.function.BiConsumer<ServerPlayer,DeepItemPackets.Admission> admissionTransport;
    private ManagedItemSnapshot saved;
    private ItemEntity item;
    private ServerEntity tracker;
    private final Map<UUID,Observer> paired=new HashMap<>();
    private boolean paused;
    private long nativeCalls,unknownPauses,saves,loads,unloads,packetsSent,pairings,unpairings,refusedTicks;
    private String error="";
    private boolean pickupActive,pickupRemoved;
    private Packet<?> pickupPacket;
    public DeepManagedItemEngine(ServerLevel level,SparseCubeSpace space,ManagedItemPersistence store,UUID uuid) throws IOException {
        this(level,space,store,uuid,DeepRuntimeNetwork::itemAdmission);
    }
    public DeepManagedItemEngine(ServerLevel level,SparseCubeSpace space,ManagedItemPersistence store,UUID uuid,java.util.function.BiConsumer<ServerPlayer,DeepItemPackets.Admission> admissionTransport) throws IOException {
        this.level=new WeakReference<>(java.util.Objects.requireNonNull(level));this.space=java.util.Objects.requireNonNull(space);
        this.store=java.util.Objects.requireNonNull(store);this.uuid=java.util.Objects.requireNonNull(uuid);
        this.admissionTransport=java.util.Objects.requireNonNull(admissionTransport);
        saved=store.load(uuid).orElse(null);reconcile();
    }
    private ServerLevel level(){var value=level.get();if(value==null)throw new IllegalStateException("managed item level released");return value;}
    public ItemEntity item(){return item;}
    public boolean canAcceptDrop(){return item==null && saved==null;}
    /** Write-ahead source link: an unpublished drop is recovered only if its full-XYZ source became AIR. */
    public void stageDrop(ItemEntity candidate,BlockPos source) throws IOException {
        if(!canAcceptDrop() || candidate.level()!=level() || !candidate.getItem().is(Items.COBBLESTONE) || candidate.getItem().getCount()!=1)
            throw new IOException("managed drop slot busy or unsupported native loot");
        candidate.setUUID(uuid);candidate.setUnlimitedLifetime();candidate.setInvulnerable(true);candidate.lifespan=6000;
        var data=candidate.getPersistentData();data.putString(ManagedItemCodec.DROP_MARKER,uuid.toString());
        data.putIntArray(ManagedItemCodec.DROP_SOURCE,new int[]{source.getX(),source.getY(),source.getZ()});
        data.putBoolean(ManagedItemCodec.DROP_PENDING,true);
        var payload=ManagedItemCodec.encode(candidate);
        if(!known(candidate))throw new IOException("native loot requires a known motion halo");
        // Native popResource does not run a spawn collision veto. The source is still STONE in
        // the sparse collision view until commit; this native item fits entirely in that future AIR cell.
        var box=candidate.getBoundingBox();
        if(box.minX<source.getX() || box.maxX>source.getX()+1D || box.minY<source.getY() || box.maxY>source.getY()+1D
                || box.minZ<source.getZ() || box.maxZ>source.getZ()+1D)
            throw new IOException("native loot leaves the one-cell mining transaction");
        store.save(payload);saved=payload;saves++;
    }
    public void abortDrop() throws IOException {
        if(saved!=null && pending(ManagedItemCodec.readAndValidate(uuid,saved.data()))){store.erase(uuid);saved=null;}
    }
    private static boolean pending(net.minecraft.nbt.CompoundTag tag){return tag.getCompound("ForgeData").getBoolean(ManagedItemCodec.DROP_PENDING);}
    public void seed(Vec3 position,Vec3 motion,String token) throws IOException {
        if(item!=null || saved!=null)throw new IOException("managed UUID already has an authoritative item");
        if(token==null || token.isBlank() || token.length()>64)throw new IOException("invalid fixture token");
        var stack=new ItemStack(Items.DIAMOND,17);stack.getOrCreateTag().putString("miaManagedItem",uuid.toString());
        stack.getOrCreateTag().putString("miaItemToken",token);stack.getOrCreateTag().putInt("miaItemY",-19999);
        var candidate=new ItemEntity(level(),position.x,position.y,position.z,stack);candidate.setUUID(uuid);
        candidate.setDeltaMovement(motion);candidate.setUnlimitedLifetime();candidate.setNeverPickUp();candidate.setInvulnerable(true);candidate.lifespan=6000;
        var payload=ManagedItemCodec.encode(candidate);
        if(!known(candidate) || !level().noCollision(candidate))throw new IOException("seed requires known empty native collision space");
        store.save(payload);saved=payload;saves++;publish(candidate);
    }
    public void flush() throws IOException {
        if(item==null)return;
        var candidate=ManagedItemCodec.encode(item);
        if(saved!=null && Arrays.equals(saved.data(),candidate.data()))return;
        store.save(candidate);saved=candidate;saves++;
    }
    /** Save acknowledgement precedes discard; an IO failure keeps the previous live object. */
    public void reconcile() throws IOException {
        if(item!=null && !space.isLoaded(key(item.position()))){flush();withdraw();unloads++;}
        if(item==null && saved!=null){
            var nativeTag=ManagedItemCodec.readAndValidate(uuid,saved.data());var p=nativeTag.getList("Pos",net.minecraft.nbt.Tag.TAG_DOUBLE);
            if(pending(nativeTag)) {
                int[] source=nativeTag.getCompound("ForgeData").getIntArray(ManagedItemCodec.DROP_SOURCE);
                var state=space.block(new BlockCoordinate(source[0],source[1],source[2]));
                if(state.isEmpty())return;
                if(Block.stateById(state.getAsInt()).is(Blocks.STONE)){store.erase(uuid);saved=null;return;}
                if(!Block.stateById(state.getAsInt()).isAir())throw new IOException("pending drop source changed outside mining profile");
                var recovered=ManagedItemCodec.decode(level(),uuid,saved.data());
                recovered.getPersistentData().putBoolean(ManagedItemCodec.DROP_PENDING,false);
                var payload=ManagedItemCodec.encode(recovered);store.save(payload);saved=payload;saves++;
            }
            if(space.isLoaded(key(new Vec3(p.getDouble(0),p.getDouble(1),p.getDouble(2))))){publish(ManagedItemCodec.decode(level(),uuid,saved.data()));loads++;}
        }
    }
    public void erase() throws IOException {store.erase(uuid);saved=null;withdraw();}
    public void step() throws IOException {
        reconcile();if(item==null || paused)return;
        if(!known(item)){unknownPauses++;return;}
        var before=ManagedItemCodec.encode(item);int beforeTicks=item.tickCount;
        try {item.setOldPosAndRot();item.tickCount++;item.tick();ManagedItemCodec.encode(item);nativeCalls++;error="";}
        catch(RuntimeException | IOException refused){
            ManagedItemCodec.restore(item,before.data());item.tickCount=beforeTicks;refusedTicks++;error=refused.toString();return;
        }
        // The native move can leave one observer's halo while another keeps union residency.
        // Withdraw before any native motion/metadata packet reaches the now-unavailable mirror.
        for(var observer:List.copyOf(paired.values()))if(!visibleIn(observer.window()))unpair(observer);
        if(ManagedItemCodec.survivalDrop(item)) {
            for(var observer:List.copyOf(paired.values())) {
                touch(observer.player());if(item==null)return;
            }
        }
        tracker.sendChanges();
    }
    public void pause(boolean value){paused=value;}
    private boolean known(ItemEntity candidate){return DeepCubeDomain.isDeep(level(),candidate.getY()) && DeepCubeDomain.allowsMove(level(),swept(candidate));}
    private static AABB swept(ItemEntity candidate){var motion=candidate.getDeltaMovement();return candidate.getBoundingBox().expandTowards(motion).expandTowards(candidate.isNoGravity()?motion:motion.add(0,-.04,0));}
    public boolean visibleIn(Set<CubeKey> window){
        if(item==null || !DeepCubeDomain.isDeep(level(),item.getY()))return false;
        var bounds=swept(item).inflate(8);int x0=(int)Math.floor(bounds.minX/16),x1=(int)Math.floor(bounds.maxX/16);
        int y0=(int)Math.floor(bounds.minY/16),y1=(int)Math.floor(bounds.maxY/16),z0=(int)Math.floor(bounds.minZ/16),z1=(int)Math.floor(bounds.maxZ/16);
        if((long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1)>64)return false;
        for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)if(!window.contains(new CubeKey(x,y,z)))return false;
        return true;
    }
    public void observers(List<Observer> observers){
        if(observers.size()>4)throw new IllegalArgumentException("managed native observer cap4");
        var wanted=new HashMap<UUID,Observer>();
        if(item!=null)for(var observer:observers)if(observer.player().level()==level() && visibleIn(observer.window()))wanted.put(observer.player().getUUID(),observer);
        for(var old:List.copyOf(paired.values())){var next=wanted.get(old.player().getUUID());if(next==null || next.player()!=old.player() || !next.session().equals(old.session()))unpair(old);}
        if(item==null)return;
        for(var observer:wanted.values()){
            if(!paired.containsKey(observer.player().getUUID())){admission(observer,true);tracker.addPairing(observer.player());pairings++;}
            paired.put(observer.player().getUUID(),observer);
        }
    }
    private void admission(Observer observer,boolean active){admissionTransport.accept(observer.player(),new DeepItemPackets.Admission(observer.session(),level().dimension().location().toString(),uuid,item.getId(),item.position(),active));}
    private void unpair(Observer observer){tracker.removePairing(observer.player());admission(observer,false);paired.remove(observer.player().getUUID());unpairings++;}
    private void publish(ItemEntity candidate){
        if(item!=null)throw new IllegalStateException("one live native UUID only");
        DeepManagedItemIdentity.admit(candidate);item=candidate;OWNERS.put(candidate,this);
        // Never addFreshEntity: vanilla X/Z manager is not the persistence or ticking authority.
        tracker=new ServerEntity(level(),item,1,true,this::broadcast);
    }
    private void broadcast(Packet<?> packet){for(var observer:paired.values()){observer.player().connection.send(packet);packetsSent++;}}
    private void withdraw(){
        if(item==null)return;
        for(var observer:List.copyOf(paired.values()))unpair(observer);
        OWNERS.remove(item);DeepManagedItemIdentity.release(item);item.discard();item=null;tracker=null;
    }
    public static boolean stageRemoval(Entity entity,Entity.RemovalReason reason) {
        if(!(entity instanceof ItemEntity nativeItem) || reason!=Entity.RemovalReason.DISCARDED)return false;
        var owner=OWNERS.get(nativeItem);if(owner==null || !owner.pickupActive)return false;
        owner.pickupRemoved=true;return true;
    }
    public static boolean stageTake(Entity entity,Packet<?> packet) {
        if(!(entity instanceof ItemEntity nativeItem) || !(packet instanceof ClientboundTakeItemEntityPacket))return false;
        var owner=OWNERS.get(nativeItem);if(owner==null || !owner.pickupActive)return false;
        owner.pickupPacket=packet;return true;
    }
    /** Native Inventory.add/Forge pickup hooks and native pickup delay; only discovery/removal publication are bridged. */
    private void touch(ServerPlayer player) throws IOException {
        if(player.level()!=level() || !player.isAlive() || player.gameMode.getGameModeForPlayer()!=GameType.SURVIVAL
                || player.isPassenger() || player.containerMenu!=player.inventoryMenu
                || !player.getBoundingBox().inflate(1,.5,1).intersects(item.getBoundingBox())
                || !DeepCubeDomain.allowsMove(level(),player.getBoundingBox().inflate(1,.5,1)))return;
        var before=ManagedItemCodec.encode(item);var slots=new java.util.ArrayList<ItemStack>();
        for(var slot:player.inventoryMenu.slots)slots.add(slot.getItem().copy());
        var carried=player.inventoryMenu.getCarried().copy();int selected=player.getInventory().selected;
        pickupActive=true;pickupRemoved=false;pickupPacket=null;boolean committed=false;
        try {
            item.playerTouch(player);
            if(pickupRemoved) {
                store.erase(uuid);saved=null;committed=true;
                if(pickupPacket!=null)broadcast(pickupPacket);
                withdraw();player.inventoryMenu.broadcastChanges();
            } else ManagedItemCodec.encode(item);
        } catch(IOException | RuntimeException failure) {
            if(!committed){
                for(int i=0;i<slots.size();i++)player.inventoryMenu.slots.get(i).set(slots.get(i).copy());
                player.inventoryMenu.setCarried(carried);player.getInventory().selected=selected;
                ManagedItemCodec.restore(item,before.data());player.inventoryMenu.broadcastFullState();
            }
            if(failure instanceof IOException io)throw io;throw new IOException("native pickup refused",failure);
        } finally {pickupActive=false;pickupRemoved=false;pickupPacket=null;}
    }
    public void close() throws IOException {flush();withdraw();}
    public JsonObject status(){
        var out=new JsonObject();out.addProperty("uuid",uuid.toString());out.addProperty("live",item!=null);out.addProperty("present",item!=null);
        out.addProperty("persisted",saved!=null);out.addProperty("paused",paused);out.addProperty("file",store.file(uuid).toAbsolutePath().toString());
        out.addProperty("nativeCalls",nativeCalls);out.addProperty("unknownPauses",unknownPauses);out.addProperty("refusedTicks",refusedTicks);out.addProperty("error",error);
        out.addProperty("saves",saves);out.addProperty("loads",loads);out.addProperty("unloads",unloads);out.addProperty("observerCount",paired.size());
        out.addProperty("packetsSent",packetsSent);out.addProperty("pairings",pairings);out.addProperty("unpairings",unpairings);
        out.addProperty("nativeColumnRegistered",level().getEntity(uuid)!=null);
        if(item!=null){out.addProperty("id",item.getId());out.addProperty("nativeClass",item.getClass().getName());out.add("position",vector(item.position()));out.add("motion",vector(item.getDeltaMovement()));
            out.addProperty("onGround",item.onGround());out.addProperty("tickCount",item.tickCount);out.addProperty("count",item.getItem().getCount());out.addProperty("item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
            var tag=item.getItem().getTag();String nbt=tag==null?"":tag.toString();out.addProperty("nbt",nbt.substring(0,Math.min(256,nbt.length())));out.addProperty("nbtTruncated",nbt.length()>256);
            out.addProperty("token",tag==null?"":tag.getString("miaItemToken"));}
        return out;
    }
    private static JsonArray vector(Vec3 p){var out=new JsonArray();out.add(p.x);out.add(p.y);out.add(p.z);return out;}
    private static CubeKey key(Vec3 p){return CubeKey.fromBlock(new BlockCoordinate((int)Math.floor(p.x),(int)Math.floor(p.y),(int)Math.floor(p.z)));}
}
