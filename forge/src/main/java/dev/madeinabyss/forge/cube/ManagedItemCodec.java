package dev.madeinabyss.forge.cube;

import java.io.*;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** Strict native NBT for the retained diamond fixture and one actual survival stone drop. */
public final class ManagedItemCodec {
    public static final int MAX_PAYLOAD_BYTES = 65536;
    public static final String DROP_MARKER="miaSurvivalDrop", DROP_SOURCE="miaDropSource", DROP_PENDING="miaDropPending";
    private static final Set<String> ROOT_KEYS=Set.of("id","UUID","Pos","Motion","Rotation","FallDistance","Fire","Air","OnGround",
            "Invulnerable","PortalCooldown","CanUpdate","Health","Age","PickupDelay","Lifespan","Item","Owner","Thrower",
            "ForgeData","ForgeCaps","NoGravity","Silent","Glowing","TicksFrozen","HasVisualFire","Tags");
    private static final Set<String> ITEM_KEYS=Set.of("id","Count","tag","ForgeCaps");
    private ManagedItemCodec() { }
    public static ManagedItemSnapshot encode(ItemEntity item) throws IOException {
        Objects.requireNonNull(item,"item");
        try {
            if(item.isRemoved() || item.isPassenger() || item.isVehicle()) throw new IOException("unsupported managed item lifecycle");
            // Native save copies these compounds recursively; bound them before that copy too.
            tree(item.getPersistentData(),0,new int[]{0});
            if(item.getItem().getTag()!=null) tree(item.getItem().getTag(),0,new int[]{0});
            var tag=new CompoundTag(); if(!item.save(tag)) throw new IOException("native managed item refused save");
            validate(tag,item.getUUID());
            var bytes=new ByteArrayOutputStream();
            NbtIo.write(tag,new DataOutputStream(new OutputStream() {
                private void reserve(int size) throws IOException { if(size<0 || size>MAX_PAYLOAD_BYTES-bytes.size()) throw new IOException("managed item NBT exceeds byte budget"); }
                @Override public void write(int value) throws IOException { reserve(1); bytes.write(value); }
                @Override public void write(byte[] data,int offset,int length) throws IOException { reserve(length); bytes.write(data,offset,length); }
            }));
            byte[] data=bytes.toByteArray(); readAndValidate(item.getUUID(),data);
            return new ManagedItemSnapshot(item.getUUID(),data);
        } catch(RuntimeException invalid) { throw new IOException("invalid native managed item save",invalid); }
    }
    public static ItemEntity decode(Level level, UUID expected, byte[] data) throws IOException {
        CompoundTag tag=readAndValidate(expected,data);
        try {
            var item=new ItemEntity(Objects.requireNonNull(level),tag.getList("Pos",Tag.TAG_DOUBLE).getDouble(0),
                    tag.getList("Pos",Tag.TAG_DOUBLE).getDouble(1),tag.getList("Pos",Tag.TAG_DOUBLE).getDouble(2),new ItemStack(Items.DIAMOND));
            item.load(tag);
            if(item.isRemoved() || !item.getUUID().equals(expected)
                    || (!survivalDrop(item) && (!item.getItem().is(Items.DIAMOND)
                    || !item.getItem().getOrCreateTag().getString("miaManagedItem").equals(expected.toString()))))
                throw new IOException("native managed item restore identity mismatch");
            requireNativeRoundTrip(item,tag);
            return item;
        } catch(RuntimeException invalid) { throw new IOException("native managed item failed restore",invalid); }
    }
    public static void restore(ItemEntity item, byte[] data) throws IOException {
        Objects.requireNonNull(item,"item");
        if(item.isRemoved() || item.isPassenger() || item.isVehicle()) throw new IOException("unsupported live managed item lifecycle");
        CompoundTag desired=readAndValidate(item.getUUID(),data);
        // All unsupported/corrupt input fails before touching the live object, including UUID mismatch.
        decode(item.level(),item.getUUID(),data);
        CompoundTag previous=new CompoundTag();
        if(!item.save(previous)) throw new IOException("live managed item cannot provide rollback state");
        try { item.load(desired); requireNativeRoundTrip(item,desired); }
        catch(RuntimeException | IOException invalid) {
            try { item.load(previous); requireNativeRoundTrip(item,previous); }
            catch(RuntimeException | IOException failedRollback) { invalid.addSuppressed(failedRollback); }
            throw new IOException("native managed item restore failed",invalid);
        }
    }
    public static CompoundTag readAndValidate(UUID expected, byte[] data) throws IOException {
        Objects.requireNonNull(expected,"expected UUID");
        if(data==null || data.length<1 || data.length>MAX_PAYLOAD_BYTES) throw new IOException("invalid managed item NBT size");
        try(var input=new DataInputStream(new ByteArrayInputStream(data))) {
            CompoundTag tag=NbtIo.read(input,new NbtAccounter(1024L*1024));
            if(tag==null || input.available()!=0) throw new IOException("invalid/trailing managed item NBT");
            validate(tag,expected); return tag;
        } catch(RuntimeException invalid) { throw new IOException("invalid/over-budget managed item NBT",invalid); }
    }
    private static void validate(CompoundTag tag,UUID expected) throws IOException {
        tree(tag,0,new int[]{0}); allowed(tag,ROOT_KEYS);
        require(tag,"id",Tag.TAG_STRING); if(!tag.getString("id").equals("minecraft:item")) throw new IOException("unsupported managed entity type");
        uuid(tag,"UUID"); if(!tag.getUUID("UUID").equals(expected)) throw new IOException("managed item UUID mismatch");
        ListTag pos=vector(tag,"Pos",Tag.TAG_DOUBLE,3); ListTag motion=vector(tag,"Motion",Tag.TAG_DOUBLE,3);
        ListTag rotation=vector(tag,"Rotation",Tag.TAG_FLOAT,2);
        double x=pos.getDouble(0), y=pos.getDouble(1), z=pos.getDouble(2);
        if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x)>30000512D || Math.abs(z)>30000512D || y < -24000 || y >= -64)
            throw new IOException("managed item full XYZ outside supported deep/native bounds");
        for(int i=0;i<3;i++) if(!Double.isFinite(motion.getDouble(i)) || Math.abs(motion.getDouble(i))>3) throw new IOException("unsupported managed item Motion");
        for(int i=0;i<2;i++) if(!Float.isFinite(rotation.getFloat(i))) throw new IOException("invalid managed item Rotation");
        require(tag,"FallDistance",Tag.TAG_FLOAT); if(!Float.isFinite(tag.getFloat("FallDistance")) || tag.getFloat("FallDistance")<0) throw new IOException("invalid managed item FallDistance");
        require(tag,"Fire",Tag.TAG_SHORT); require(tag,"Air",Tag.TAG_SHORT); require(tag,"PortalCooldown",Tag.TAG_INT);
        if(tag.getInt("PortalCooldown")<0) throw new IOException("invalid managed item portal cooldown");
        bool(tag,"OnGround",true); bool(tag,"Invulnerable",true); bool(tag,"CanUpdate",true);
        if(!tag.getBoolean("Invulnerable") || !tag.getBoolean("CanUpdate")) throw new IOException("unsupported managed item update/invulnerability profile");
        require(tag,"Health",Tag.TAG_SHORT); require(tag,"Age",Tag.TAG_SHORT); require(tag,"PickupDelay",Tag.TAG_SHORT); require(tag,"Lifespan",Tag.TAG_INT);
        compound(tag,"ForgeData");
        var forge=tag.getCompound("ForgeData"); boolean drop=forge.contains(DROP_MARKER);
        if(drop) {
            require(forge,DROP_MARKER,Tag.TAG_STRING);require(forge,DROP_SOURCE,Tag.TAG_INT_ARRAY);bool(forge,DROP_PENDING,true);
            int[] source=forge.getIntArray(DROP_SOURCE);
            if(!forge.getString(DROP_MARKER).equals(expected.toString()) || source.length!=3 || source[1]<-24000 || source[1]>=-64
                    || Math.abs((long)source[0])>30000000 || Math.abs((long)source[2])>30000000)
                throw new IOException("invalid survival drop source/identity");
        }
        int delay=tag.getShort("PickupDelay");
        if(tag.getShort("Health")<1 || tag.getShort("Health")>5 || tag.getShort("Age")!=-32768
                || (drop?(delay<0 || delay>10):delay!=32767) || tag.getInt("Lifespan")!=6000)
            throw new IOException("unsupported managed item lifetime/pickup/health profile");
        require(tag,"Item",Tag.TAG_COMPOUND); var stack=tag.getCompound("Item"); allowed(stack,ITEM_KEYS);
        require(stack,"id",Tag.TAG_STRING); require(stack,"Count",Tag.TAG_BYTE); compound(stack,"tag");
        if(drop) {
            if(!stack.getString("id").equals("minecraft:cobblestone") || stack.getByte("Count")!=1)
                throw new IOException("only one actual native cobblestone drop is supported");
        } else {
            require(stack,"tag",Tag.TAG_COMPOUND);
            if(!stack.getString("id").equals("minecraft:diamond") || stack.getByte("Count")<1 || stack.getByte("Count")>64)
                throw new IOException("only a bounded native diamond stack is supported");
            require(stack.getCompound("tag"),"miaManagedItem",Tag.TAG_STRING);
            if(!stack.getCompound("tag").getString("miaManagedItem").equals(expected.toString())) throw new IOException("managed item stack marker mismatch");
        }
        emptyCaps(stack); compound(tag,"ForgeData"); emptyCaps(tag);
        for(String field:new String[]{"Owner","Thrower"}) if(tag.contains(field)) uuid(tag,field);
        for(String field:new String[]{"NoGravity","Silent","Glowing","HasVisualFire"}) bool(tag,field,false);
        if(tag.getBoolean("NoGravity")) throw new IOException("managed item native gravity must remain enabled");
        if(tag.contains("TicksFrozen")) { require(tag,"TicksFrozen",Tag.TAG_INT); if(tag.getInt("TicksFrozen")<0) throw new IOException("invalid frozen ticks"); }
        if(tag.contains("Tags")) {
            require(tag,"Tags",Tag.TAG_LIST); var tags=(ListTag)tag.get("Tags");
            if(tags.size()>1024 || (tags.size()>0 && tags.getElementType()!=Tag.TAG_STRING)) throw new IOException("invalid native entity tags");
            var names=new java.util.HashSet<String>(); for(Tag entry:tags) if(!names.add(entry.getAsString())) throw new IOException("duplicate native entity tag");
        }
    }
    public static boolean survivalDrop(ItemEntity item) {
        return item.getPersistentData().contains(DROP_MARKER,Tag.TAG_STRING);
    }
    /** Native loaders can discard unclaimed capabilities or retain absent optional state. Neither is success. */
    private static void requireNativeRoundTrip(ItemEntity item,CompoundTag expected) throws IOException {
        var actual=new CompoundTag();
        if(!item.save(actual) || !canonical(actual).equals(canonical(expected)))
            throw new IOException("native managed item NBT did not round-trip without state loss");
    }
    private static CompoundTag canonical(CompoundTag source) {
        var tag=source.copy();
        // Native save omits semantic defaults; exact nonempty compounds and all full tags remain significant.
        for(String name:new String[]{"NoGravity","Silent","Glowing","HasVisualFire"})
            if(tag.contains(name,Tag.TAG_BYTE) && tag.getByte(name)==0) tag.remove(name);
        if(tag.contains("TicksFrozen",Tag.TAG_INT) && tag.getInt("TicksFrozen")==0) tag.remove("TicksFrozen");
        for(String name:new String[]{"ForgeData","ForgeCaps"})
            if(tag.contains(name,Tag.TAG_COMPOUND) && tag.getCompound(name).isEmpty()) tag.remove(name);
        if(tag.contains("Tags",Tag.TAG_LIST) && ((ListTag)tag.get("Tags")).isEmpty()) tag.remove("Tags");
        var stack=tag.getCompound("Item");
        if(stack.contains("ForgeCaps",Tag.TAG_COMPOUND) && stack.getCompound("ForgeCaps").isEmpty()) stack.remove("ForgeCaps");
        return tag;
    }
    private static void require(CompoundTag tag,String key,int type) throws IOException { if(!tag.contains(key,type)) throw new IOException("invalid managed item field: "+key); }
    private static void allowed(CompoundTag tag,Set<String> keys) throws IOException { for(String key:tag.getAllKeys()) if(!keys.contains(key)) throw new IOException("unsupported managed item field: "+key); }
    private static void uuid(CompoundTag tag,String key) throws IOException { require(tag,key,Tag.TAG_INT_ARRAY); if(!tag.hasUUID(key)) throw new IOException("invalid native UUID field: "+key); }
    private static void compound(CompoundTag tag,String key) throws IOException { if(tag.contains(key)) require(tag,key,Tag.TAG_COMPOUND); }
    /** Lazy ItemStack caps can round-trip unclaimed bytes. Preservation does not make their callbacks supported. */
    private static void emptyCaps(CompoundTag tag) throws IOException {
        compound(tag,"ForgeCaps");
        if(tag.contains("ForgeCaps") && !tag.getCompound("ForgeCaps").isEmpty())
            throw new IOException("nonempty native capabilities are unsupported by the managed vanilla item profile");
    }
    private static void bool(CompoundTag tag,String key,boolean required) throws IOException { if(required || tag.contains(key)) { require(tag,key,Tag.TAG_BYTE); if(tag.getByte(key)!=0 && tag.getByte(key)!=1) throw new IOException("invalid native boolean: "+key); } }
    private static ListTag vector(CompoundTag tag,String key,int type,int size) throws IOException { require(tag,key,Tag.TAG_LIST); var values=(ListTag)tag.get(key); if(values.size()!=size || values.getElementType()!=type) throw new IOException("invalid managed item vector: "+key); return values; }
    private static void tree(Tag tag,int depth,int[] nodes) throws IOException {
        if(depth>32 || ++nodes[0]>4096) throw new IOException("managed item NBT tree budget exceeded");
        if(tag instanceof CompoundTag compound) for(String key:compound.getAllKeys()) tree(compound.get(key),depth+1,nodes);
        else if(tag instanceof ListTag list) for(Tag child:list) tree(child,depth+1,nodes);
    }
}
