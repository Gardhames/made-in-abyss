package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.ManagedItemCodec;
import dev.madeinabyss.forge.cube.ManagedItemSnapshot;
import dev.madeinabyss.forge.cube.ManagedItemStore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ManagedItemStoreGameTests {
    private static final UUID ID = UUID.fromString("c31e3f14-a783-4654-948b-b56c42948ace");

    // Break caught: integer/cube-local Pos or a replacement UUID loses actual fractional native identity.
    @GameTest(template="empty")
    public static void fractionalNativeItemColdStoreRoundTrip(GameTestHelper h) throws Exception {
        var source = item(h); var saved = ManagedItemCodec.encode(source);
        var store = new ManagedItemStore(directory()); store.save(saved);
        var disk = new ManagedItemStore(store.file(ID).getParent()).load(ID).orElseThrow();
        var restored = ManagedItemCodec.decode(h.getLevel(), ID, disk.data());
        h.assertTrue(restored.getUUID().equals(ID) && restored.getX()==-16.25 && restored.getY()==-19999.125 && restored.getZ()==-0.25,
                "cold native identity and fractional negative full XYZ survive");
        h.assertTrue(restored.getItem().getCount()==17 && restored.getItem().getTag().getString("miaManagedItem").equals(ID.toString()), "full marked diamond stack survives");
        h.assertTrue(restored.getPersistentData().getString("testEvidence").equals("full-native"), "ForgeData survives");
        h.assertTrue(Arrays.equals(saved.data(), disk.data()), "disk retains complete native payload bytes");
        byte[] leaked = saved.data(); leaked[0]^=1;
        h.assertTrue(!Arrays.equals(leaked,saved.data()), "snapshot cannot be mutated through returned array");
        h.succeed();
    }

    // Break caught: cube-seam movement writes a second authority or leaves the old coordinate version live.
    @GameTest(template="empty")
    public static void seamMovementReplacesOneUuidFrame(GameTestHelper h) throws Exception {
        Path root=directory(); var store=new ManagedItemStore(root); var item=item(h);
        store.save(ManagedItemCodec.encode(item)); Path original=store.file(ID);
        item.setPos(-15.75,-20000.25,-16.125); store.save(ManagedItemCodec.encode(item));
        var restored=ManagedItemCodec.decode(h.getLevel(),ID,new ManagedItemStore(root).load(ID).orElseThrow().data());
        h.assertTrue(store.file(ID).equals(original) && restored.getX()==-15.75 && restored.getY()==-20000.25 && restored.getZ()==-16.125,"seam movement replaces same UUID authority");
        try(var paths=Files.walk(root)) { h.assertTrue(paths.filter(Files::isRegularFile).count()==1,"only one persistent entity frame exists"); }
        h.assertTrue(!Files.exists(root.resolve("entities")),"custom persistence does not invent vanilla region authority");
        h.succeed();
    }

    // Break caught: unsupported payload save overwrites the old native record or invalid restore mutates the live object.
    @GameTest(template="empty")
    public static void unsupportedPayloadPreservesDiskAndLiveNativeItem(GameTestHelper h) throws Exception {
        var store=new ManagedItemStore(directory()); var item=item(h); store.save(ManagedItemCodec.encode(item));
        byte[] original=Files.readAllBytes(store.file(ID)); CompoundTag good=nativeTag(item);
        for(int kind=0;kind<15;kind++) {
            CompoundTag bad=good.copy();
            switch(kind) {
                case 0 -> bad.putString("id","minecraft:pig");
                case 1 -> bad.putUUID("UUID",UUID.randomUUID());
                case 2 -> bad.put("Pos",doubles(-16.25,Double.NaN,-0.25));
                case 3 -> bad.put("Pos",doubles(-16.25,-64,-0.25));
                case 4 -> bad.put("Motion",doubles(3.01,0,0));
                case 5 -> bad.put("Passengers",new ListTag());
                case 6 -> bad.getCompound("Item").putByte("Count",(byte)0);
                case 7 -> bad.getCompound("Item").getCompound("tag").putString("miaManagedItem","wrong");
                case 8 -> bad.putShort("Age",(short)0);
                case 9 -> bad.putBoolean("Invulnerable",false);
                case 10 -> bad.putShort("PickupDelay",(short)0);
                case 11 -> bad.putInt("Lifespan",1);
                case 12 -> bad.getCompound("Item").putString("id","minecraft:emerald");
                case 13 -> bad.put("Rotation",doubles(0,0));
                case 14 -> bad.putBoolean("NoGravity",true);
            }
            byte[] data=bytes(bad);
            reject(h,()->store.save(new ManagedItemSnapshot(ID,data)),"unsupported save");
            reject(h,()->ManagedItemCodec.restore(item,data),"unsupported restore");
            h.assertTrue(Arrays.equals(original,Files.readAllBytes(store.file(ID))),"unsupported save preserves old bytes");
            h.assertTrue(nativeTag(item).equals(good),"failed restore preserves full native state");
        }
        h.succeed();
    }

    // Break caught: native capability deserialization drops an unclaimed compound while a restore appears successful.
    @GameTest(template="empty")
    public static void unknownNativeCapsRejectWithoutMutatingLiveItem(GameTestHelper h) throws Exception {
        var item=item(h); CompoundTag previous=nativeTag(item);
        var store=new ManagedItemStore(directory());store.save(ManagedItemCodec.encode(item));byte[] oldFrame=Files.readAllBytes(store.file(ID));
        for(boolean stackCaps:new boolean[]{false,true}) {
            CompoundTag unsupported=previous.copy(),caps=new CompoundTag(),unclaimed=new CompoundTag();
            unclaimed.putString("evidence","must-not-be-dropped");caps.put("madeinabyss:unsupported_probe_capability",unclaimed);
            (stackCaps?unsupported.getCompound("Item"):unsupported).put("ForgeCaps",caps);
            byte[] data=bytes(unsupported);
            reject(h,()->store.save(new ManagedItemSnapshot(ID,data)),"unclaimed native capability save");
            reject(h,()->ManagedItemCodec.decode(h.getLevel(),ID,data),"unclaimed native capability decode");
            reject(h,()->ManagedItemCodec.restore(item,data),"unclaimed native capability restore");
            h.assertTrue(Arrays.equals(oldFrame,Files.readAllBytes(store.file(ID))),"unsupported caps preserve existing authoritative file bytes");
            h.assertTrue(nativeTag(item).equals(previous),"unsupported native caps leave exact live item untouched");
        }
        h.succeed();
    }

    // Break caught: native NBT limits are checked only after publishing a dangerous compound or silently trimming tags.
    @GameTest(template="empty")
    public static void boundedNativeTreesRejectBeforeRestore(GameTestHelper h) throws Exception {
        var item=item(h); CompoundTag original=nativeTag(item);
        CompoundTag deep=original.copy(); CompoundTag cursor=deep.getCompound("ForgeData");
        for(int i=0;i<34;i++) { var child=new CompoundTag(); cursor.put("nested",child); cursor=child; }
        CompoundTag wide=original.copy(); var list=new ListTag(); for(int i=0;i<4097;i++) list.add(IntTag.valueOf(i)); wide.getCompound("ForgeData").put("many",list);
        CompoundTag oversized=original.copy(); oversized.getCompound("ForgeData").putByteArray("many",new byte[65536]);
        for(byte[] bad:new byte[][]{bytes(deep),bytes(wide),bytes(oversized)}) {
            reject(h,()->ManagedItemCodec.restore(item,bad),"bounded native tree");
            h.assertTrue(nativeTag(item).equals(original),"bounded failure leaves full live native state intact");
        }
        h.succeed();
    }

    // Break caught: corrupted/oversized/declared allocation-bomb frames turn into absence or overwrite old evidence.
    @GameTest(template="empty")
    public static void corruptFramesRejectWithoutRegeneration(GameTestHelper h) throws Exception {
        var store=new ManagedItemStore(directory()); store.save(ManagedItemCodec.encode(item(h))); byte[] valid=Files.readAllBytes(store.file(ID));
        byte[] crc=valid.clone(); crc[crc.length-1]^=1;
        byte[] version=valid.clone(); ByteBuffer.wrap(version).putInt(4,999); checksum(version);
        byte[] size=valid.clone(); ByteBuffer.wrap(size).putInt(25,Integer.MAX_VALUE); checksum(size);
        byte[] allocationBomb=frame(ID,1,new byte[]{10,0,0,7,0,1,120,127,-1,-1,-1,0});
        for(byte[] bad:new byte[][]{crc,version,size,allocationBomb,new byte[ManagedItemStore.MAX_FILE_BYTES+1],Arrays.copyOf(valid,valid.length-1),new byte[0]}) {
            Files.write(store.file(ID),bad);
            reject(h,()->store.load(ID),"corrupt read");
            reject(h,()->store.save(ManagedItemCodec.encode(item(h))),"corrupt overwrite");
            h.assertTrue(Arrays.equals(bad,Files.readAllBytes(store.file(ID))),"bad evidence never silently regenerated");
        }
        h.succeed();
    }

    // Break caught: deleting a frame makes consumed item appear missing and eligible for new fixture generation.
    @GameTest(template="empty")
    public static void erasedIdentityLeavesPersistentTombstone(GameTestHelper h) throws Exception {
        var store=new ManagedItemStore(directory()); store.save(ManagedItemCodec.encode(item(h))); store.erase(ID);
        byte[] first=Files.readAllBytes(store.file(ID));
        h.assertTrue(first.length>0 && new ManagedItemStore(store.file(ID).getParent()).load(ID).isEmpty(),"durable tombstone means absent entity while preserving consumed identity");
        store.erase(ID); h.assertTrue(Arrays.equals(first,Files.readAllBytes(store.file(ID))),"repeat erase is stable");
        h.succeed();
    }

    private static ItemEntity item(GameTestHelper h) {
        var stack=new ItemStack(Items.DIAMOND,17); stack.getOrCreateTag().putString("miaManagedItem",ID.toString());
        stack.getOrCreateTag().putString("evidence","fractional-native");
        var item=new ItemEntity(h.getLevel(),-16.25,-19999.125,-0.25,stack); item.setUUID(ID);
        item.setInvulnerable(true); item.setUnlimitedLifetime(); item.setNeverPickUp(); item.lifespan=6000;
        item.setDeltaMovement(0.125,-0.25,0.375); item.getPersistentData().putString("testEvidence","full-native");
        return item;
    }
    private static CompoundTag nativeTag(ItemEntity item) { var tag=new CompoundTag(); item.save(tag); return tag; }
    private static byte[] bytes(CompoundTag tag) throws IOException { var out=new ByteArrayOutputStream(); NbtIo.write(tag,new DataOutputStream(out)); return out.toByteArray(); }
    private static ListTag doubles(double... values) { var list=new ListTag(); for(double value:values) list.add(DoubleTag.valueOf(value)); return list; }
    private static Path directory() { return Path.of("diagnostics","managed-item-stores",UUID.randomUUID().toString()); }
    private interface Action { void run() throws Exception; }
    private static void reject(GameTestHelper h,Action action,String reason) throws Exception { try { action.run(); h.fail("must reject "+reason); } catch(IOException expected) { } }
    private static byte[] frame(UUID id,int state,byte[] data) throws IOException { var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes); out.writeInt(0x4d494550); out.writeInt(1); out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); out.writeByte(state); out.writeInt(data.length); out.write(data); out.writeInt(0); byte[] result=bytes.toByteArray(); checksum(result); return result; }
    private static void checksum(byte[] bytes) { var crc=new CRC32(); crc.update(bytes,0,bytes.length-4); ByteBuffer.wrap(bytes).putInt(bytes.length-4,(int)crc.getValue()); }
}
