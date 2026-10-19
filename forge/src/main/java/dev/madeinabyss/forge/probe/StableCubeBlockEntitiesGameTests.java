package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.SavedCubeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StableCubeBlockEntitiesGameTests {
    private static final CubeKey KEY = new CubeKey(-2,-1251,-2);
    private static final BlockCoordinate POS = new BlockCoordinate(-17,-20001,-17);
    private static final String GENERATOR = "madeinabyss:chest_store_test";

    // Break caught: v3 drops native inventory/custom name or reads encoder and decoder's shared wrong layout.
    @GameTest(template="empty")
    public static void independentV3AndNativeChestRoundTrip(GameTestHelper helper) throws Exception {
        var store=store(); byte[] nbt=nativeChest(); byte[] wire=fixture(3,true,List.of(record(nbt)));
        Files.createDirectories(store.path(KEY).getParent()); Files.write(store.path(KEY),wire);
        var decoded=store.read(KEY).orElseThrow();
        helper.assertTrue(Arrays.equals(nbt,decoded.blockEntities().get(0).data()),"independent v3 payload is exact");
        store.write(decoded); var restored=store.read(KEY).orElseThrow();
        var chest=new ChestBlockEntity(new BlockPos(POS.x(),POS.y(),POS.z()),Blocks.CHEST.defaultBlockState());
        chest.load(NbtIo.read(new DataInputStream(new ByteArrayInputStream(restored.blockEntities().get(0).data()))));
        helper.assertTrue(chest.getItem(4).is(Items.DIAMOND) && chest.getItem(4).getCount()==23,"native slot/count survive disk decode");
        helper.assertTrue(chest.getName().getString().equals("\u0413\u043b\u0443\u0431\u043e\u043a\u0438\u0439 \u0441\u043a\u043b\u0430\u0434") && chest.getItem(4).getTag().getCompound("Nested").getString("owner").equals("abyss"),"custom name and nested item NBT survive");
        helper.assertTrue(ByteBuffer.wrap(Files.readAllBytes(store.path(KEY))).getInt(4)==3,"explicit write emits v3"); helper.succeed();
    }

    // Break caught: opening older frame rewrites it or invents BEs for old terrain.
    @GameTest(template="empty")
    public static void legacyV1V2ReadsRemainInert(GameTestHelper helper) throws Exception {
        var store=store();Files.createDirectories(store.path(KEY).getParent());
        for(int version:new int[]{1,2}){byte[] before=fixture(version,false,List.of());Files.write(store.path(KEY),before);
            helper.assertTrue(store.read(KEY).orElseThrow().blockEntities().isEmpty() && Arrays.equals(before,Files.readAllBytes(store.path(KEY))),"legacy read preserves bytes and empty BE list");}
        helper.succeed();
    }

    // Break caught: supported record boundary is rejected or negative local positions alias inventories.
    @GameTest(template="empty")
    public static void sixtyFourDistinctChestsSurvive(GameTestHelper helper) throws Exception {
        var store=store();var records=new ArrayList<SavedCubeBlockEntity>();int[] states=states(false);
        for(int i=0;i<64;i++){
            var position=new BlockCoordinate(-32+(i&15),-20016,-32+(i>>4));
            var chest=new ChestBlockEntity(new BlockPos(position.x(),position.y(),position.z()),Blocks.CHEST.defaultBlockState());
            chest.setItem(0,new ItemStack(Items.DIAMOND,i+1));
            records.add(new SavedCubeBlockEntity(position,"minecraft:chest",bytes(chest.saveWithFullMetadata())));states[i]=Block.getId(Blocks.CHEST.defaultBlockState());
        }
        store.write(new CubeSnapshot(KEY,states,List.of(),records));var restored=store.read(KEY).orElseThrow();
        helper.assertTrue(restored.blockEntities().equals(records),"64 signed distinct position/payload records survive without alias");
        var last=tag(restored.blockEntities().get(63).data());helper.assertTrue(last.getList("Items",10).getCompound(0).getByte("Count")==64,"boundary chest keeps its distinct stack");helper.succeed();
    }

    // Break caught: invalid full metadata, missing chest data, unsupported state/type or corruption silently publish.
    @GameTest(template="empty")
    public static void invalidV3NeverPublishesOrOverwrites(GameTestHelper helper) throws Exception {
        var store=store();Files.createDirectories(store.path(KEY).getParent());byte[] good=nativeChest();
        var wrongId=tag(good);wrongId.putString("id","minecraft:furnace");
        var wrongPos=tag(good);wrongPos.putInt("y",-20000);
        var malformedItems=tag(good);malformedItems.putString("Items","not a list");
        var duplicateSlots=tag(good);var items=duplicateSlots.getList("Items",10);items.add(items.getCompound(0).copy());
        var unknownItem=tag(good);unknownItem.getList("Items",10).getCompound(0).putString("id","minecraft:missing_item");
        var invalidSlot=tag(good);invalidSlot.getList("Items",10).getCompound(0).putByte("Slot",(byte)27);
        var missingX=tag(good);missingX.remove("x");
        var loot=tag(good);loot.putString("LootTable","minecraft:chests/simple_dungeon");
        var badName=tag(good);badName.putString("CustomName","{bad JSON");
        byte[] outside=fixture(3,true,List.of(record(good)));int recordOffset=fixture(3,true,List.of()).length-4;ByteBuffer.wrap(outside).putInt(recordOffset+2+"minecraft:chest".length(),-16);checksum(outside);
        byte[] trailing=Arrays.copyOf(good,good.length+1);
        byte[] corrupt=fixture(3,true,List.of(record(good)));corrupt[corrupt.length-1]^=1;
        for(byte[] bad:new byte[][]{fixture(3,true,List.of()),fixture(3,false,List.of(record(good))),fixture(3,true,List.of(record(good),record(good))),
            fixture(3,true,List.of(new SavedCubeBlockEntity(POS,"minecraft:furnace",good))),fixture(3,true,List.of(record(bytes(wrongId)))),
            fixture(3,true,List.of(record(bytes(wrongPos)))),fixture(3,true,List.of(record(bytes(malformedItems)))),fixture(3,true,List.of(record(bytes(duplicateSlots)))),
            fixture(3,true,List.of(record(bytes(unknownItem)))),fixture(3,true,List.of(record(bytes(invalidSlot)))),fixture(3,true,List.of(record(bytes(missingX)))),
            fixture(3,true,List.of(record(bytes(loot)))),fixture(3,true,List.of(record(bytes(badName)))),fixture(3,true,List.of(record(trailing))),outside,corrupt}){
            Files.write(store.path(KEY),bad);rejectRead(helper,store);
            try{store.write(new CubeSnapshot(KEY,states(true),List.of(),List.of(record(good))));helper.fail("bad visited file must refuse replacement");}catch(IOException expected){}
            helper.assertTrue(Arrays.equals(bad,Files.readAllBytes(store.path(KEY))),"invalid visited file remains exact");}
        helper.succeed();
    }

    // Break caught: failed write replaces last valid state before validating native payload/state compatibility.
    @GameTest(template="empty")
    public static void failedChestSaveKeepsPreviousFrame(GameTestHelper helper) throws Exception {
        var store=store();byte[] good=nativeChest();store.write(new CubeSnapshot(KEY,states(true),List.of(),List.of(record(good))));byte[] before=Files.readAllBytes(store.path(KEY));
        int[] doubled=states(true);doubled[4095]=Block.getId(Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.CHEST_TYPE,ChestType.LEFT));
        int[] wet=states(true);wet[4095]=Block.getId(Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true));
        for(var invalid:List.of(new CubeSnapshot(KEY,states(true)),new CubeSnapshot(KEY,states(false),List.of(),List.of(record(good))),new CubeSnapshot(KEY,doubled,List.of(),List.of(record(good))),
            new CubeSnapshot(KEY,wet,List.of(),List.of(record(good))),new CubeSnapshot(KEY,states(true),List.of(),List.of(record(new byte[]{10,0,0,0}))))){
            try{store.write(invalid);helper.fail("invalid save must reject");}catch(IOException expected){}
            helper.assertTrue(Arrays.equals(before,Files.readAllBytes(store.path(KEY))),"failed save preserves last valid complete frame");}
        helper.succeed();
    }

    // Break caught: unchecked wire lengths/counts allocate unbounded records or NBT arrays.
    @GameTest(template="empty")
    public static void wireCapsAndNbtAllocationLimitReject(GameTestHelper helper) throws Exception {
        var store=store();Files.createDirectories(store.path(KEY).getParent());byte[] base=fixture(3,true,List.of(record(nativeChest())));
        int countOffset=fixture(3,true,List.of()).length-8;
        byte[] tooMany=base.clone();ByteBuffer.wrap(tooMany).putInt(countOffset,65);checksum(tooMany);
        byte[] tooLong=base.clone();ByteBuffer.wrap(tooLong).putInt(countOffset+4+2+"minecraft:chest".length()+12,65537);checksum(tooLong);
        // Tiny input claims a giant byte array; NbtAccounter must reject before allocation.
        var huge=new ByteArrayOutputStream();var out=new DataOutputStream(huge);out.writeByte(10);out.writeUTF("");out.writeByte(7);out.writeUTF("bomb");out.writeInt(Integer.MAX_VALUE);
        var deep=tag(nativeChest());var cursor=deep;for(int i=0;i<40;i++){var next=new CompoundTag();cursor.put("child",next);cursor=next;}
        for(byte[] bad:new byte[][]{tooMany,tooLong,fixture(3,true,List.of(record(huge.toByteArray()))),fixture(3,true,List.of(record(bytes(deep))))}){Files.write(store.path(KEY),bad);rejectRead(helper,store);}
        helper.succeed();
    }

    // Break caught: native getCompound silently turns malformed persistent/capability data into empty data.
    @GameTest(template="empty")
    public static void malformedForgePayloadNeverNormalizes(GameTestHelper helper) throws Exception {
        var store=store();Files.createDirectories(store.path(KEY).getParent());
        var rootData=tag(nativeChest());rootData.putString("ForgeData","wrong type");
        var rootCaps=tag(nativeChest());rootCaps.putInt("ForgeCaps",1);
        var itemCaps=tag(nativeChest());itemCaps.getList("Items",10).getCompound(0).putString("ForgeCaps","wrong type");
        for(var tag:List.of(rootData,rootCaps,itemCaps)){Files.write(store.path(KEY),fixture(3,true,List.of(record(bytes(tag)))));rejectRead(helper,store);}
        helper.succeed();
    }

    // Break caught: accepted names exceed the client metadata budget or bypass NBT depth through JSON text.
    @GameTest(template="empty")
    public static void customNameJsonAndUtf8BudgetRejectBeforeNativeLoad(GameTestHelper helper) throws Exception {
        var store=store();Files.createDirectories(store.path(KEY).getParent());
        var boundaryName=tag(nativeChest());boundaryName.putString("CustomName","{\"text\":\""+"a".repeat(3061)+"\"}");
        Files.write(store.path(KEY),fixture(3,true,List.of(record(bytes(boundaryName)))));
        helper.assertTrue(tag(store.read(KEY).orElseThrow().blockEntities().get(0).data()).getString("CustomName").equals(boundaryName.getString("CustomName")),"3072-byte native JSON name is supported and preserved");
        var longName=tag(nativeChest());longName.putString("CustomName","{\"text\":\""+"\u044f".repeat(2000)+"\"}");
        var nonBmpName=tag(nativeChest());nonBmpName.putString("CustomName","{\"text\":\""+"😀".repeat(700)+"\"}");
        String nested="{\"text\":\"leaf\"}";for(int i=0;i<40;i++)nested="{\"text\":\"\",\"extra\":["+nested+"]}";
        var deepName=tag(nativeChest());deepName.putString("CustomName",nested);
        for(var tag:List.of(longName,deepName,nonBmpName)){Files.write(store.path(KEY),fixture(3,true,List.of(record(bytes(tag)))));rejectRead(helper,store);}
        helper.succeed();
    }

    private static void rejectRead(GameTestHelper helper,StableCubeStore store)throws Exception{try{store.read(KEY);helper.fail("invalid frame must reject");}catch(IOException expected){}}
    private static SavedCubeBlockEntity record(byte[] data){return new SavedCubeBlockEntity(POS,"minecraft:chest",data);}
    private static StableCubeStore store(){return new StableCubeStore(Path.of("diagnostics","stable-chest-stores",UUID.randomUUID().toString()),GENERATOR,1);}
    private static int[] states(boolean chest){int[] states=new int[4096];Arrays.fill(states,Block.getId(Blocks.AIR.defaultBlockState()));if(chest)states[4095]=Block.getId(Blocks.CHEST.defaultBlockState());return states;}
    private static byte[] nativeChest()throws Exception{var chest=new ChestBlockEntity(new BlockPos(POS.x(),POS.y(),POS.z()),Blocks.CHEST.defaultBlockState());chest.setCustomName(Component.literal("\u0413\u043b\u0443\u0431\u043e\u043a\u0438\u0439 \u0441\u043a\u043b\u0430\u0434"));var stack=new ItemStack(Items.DIAMOND,23);var nested=new CompoundTag();nested.putString("owner","abyss");stack.getOrCreateTag().put("Nested",nested);chest.setItem(4,stack);return bytes(chest.saveWithFullMetadata());}
    private static CompoundTag tag(byte[] bytes)throws Exception{return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));}
    private static byte[] bytes(CompoundTag tag)throws Exception{var bytes=new ByteArrayOutputStream();NbtIo.write(tag,new DataOutputStream(bytes));return bytes.toByteArray();}
    private static byte[] fixture(int version,boolean chest,List<SavedCubeBlockEntity> records)throws Exception{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(0x4d495350);out.writeInt(version);out.writeInt(KEY.x());out.writeInt(KEY.y());out.writeInt(KEY.z());utf(out,GENERATOR);out.writeInt(1);
        out.writeInt(chest?2:1);utf(out,"minecraft:air");out.writeByte(0);
        if(chest){utf(out,"minecraft:chest");out.writeByte(3);utf(out,"facing");utf(out,"north");utf(out,"type");utf(out,"single");utf(out,"waterlogged");utf(out,"false");}
        out.writeInt(4096);for(int i=0;i<4096;i++)out.writeShort(chest&&i==4095?1:0);
        if(version>=2)out.writeInt(0);
        if(version>=3){out.writeInt(records.size());for(var record:records){utf(out,record.type());out.writeInt(record.position().x());out.writeInt(record.position().y());out.writeInt(record.position().z());out.writeInt(record.data().length);out.write(record.data());}}
        out.writeInt(0);byte[] result=bytes.toByteArray();checksum(result);return result;
    }
    private static void utf(DataOutputStream out,String text)throws IOException{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);out.writeShort(bytes.length);out.write(bytes);}
    private static void checksum(byte[] bytes){var crc=new CRC32();crc.update(bytes,0,bytes.length-4);ByteBuffer.wrap(bytes).putInt(bytes.length-4,(int)crc.getValue());}
}
