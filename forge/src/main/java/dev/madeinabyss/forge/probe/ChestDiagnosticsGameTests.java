package dev.madeinabyss.forge.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.ChestBlockEntityCodec;
import dev.madeinabyss.forge.probe.deep.DeepChestDiagnostics;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChestDiagnosticsGameTests {
    // Break caught: accepted full native NBT expands diagnostic JSON beyond the real State packet string cap.
    @GameTest(template="empty")
    public static void acceptedLargeNbtDiagnosticsAreBoundedWithoutChangingNativeInventory(GameTestHelper h) throws Exception {
        for(int scenario=0;scenario<4;scenario++) {
            var chest=chest();
            if(scenario==0){var stack=new ItemStack(Items.DIAMOND,17);stack.getOrCreateTag().putString("data","a".repeat(9000));chest.setItem(0,stack);}
            if(scenario==1){var stack=new ItemStack(Items.DIAMOND,17);stack.getOrCreateTag().putByteArray("data",new byte[60000]);chest.setItem(0,stack);}
            if(scenario==2)for(int index=0;index<27;index++){var stack=new ItemStack(Items.DIAMOND,index+1);stack.getOrCreateTag().putString("data","x".repeat(600));chest.setItem(index,stack);}
            if(scenario==3){var stack=new ItemStack(Items.DIAMOND,17);stack.getOrCreateTag().putString("data","😀\"\\".repeat(1000));chest.setItem(0,stack);}
            var before=ChestBlockEntityCodec.encode(chest);var identities=new ItemStack[27];for(int index=0;index<27;index++)identities[index]=chest.getItem(index);
            var report=base();var items=DeepChestDiagnostics.items(chest);report.getAsJsonObject("chest").add("items",items);
            var bounded=DeepChestDiagnostics.state(report);roundTrip(h,bounded);
            int aggregate=0;
            for(var element:items){var item=element.getAsJsonObject();String full=chest.getItem(item.get("slot").getAsInt()).getTag().toString();String excerpt=item.get("nbt").getAsString();aggregate+=excerpt.length();
                h.assertTrue(excerpt.length()<=256 && full.startsWith(excerpt),"diagnostic excerpt is a bounded truthful SNBT prefix");
                h.assertTrue(item.get("nbtTruncated").getAsBoolean() && item.get("nbtLength").getAsInt()==full.length() && item.get("nbtSha256").getAsString().equals(sha(full)),"truncated diagnostic explicitly records full SNBT length/hash");}
            h.assertTrue(aggregate<=512,"aggregate SNBT budget remains512 even for27 slots");
            h.assertTrue(Arrays.equals(before.data(),ChestBlockEntityCodec.encode(chest).data()),"diagnostics preserve exact authoritative full native NBT bytes");
            for(int index=0;index<27;index++)h.assertTrue(chest.getItem(index)==identities[index],"diagnostics preserve every native ItemStack identity");
        }
        h.succeed();
    }

    // Break caught: truncation breaks the small full SNBT tokens used for native acceptance or adds false truncation labels.
    @GameTest(template="empty")
    public static void shortTaggedInventoryKeepsCompleteHarnessNbt(GameTestHelper h) throws Exception {
        var chest=chest();var stack=new ItemStack(Items.DIAMOND,17);String token=UUID.randomUUID().toString();stack.getOrCreateTag().putString("miaChestToken",token);stack.getOrCreateTag().putInt("miaChestY",-19999);chest.setItem(0,stack);
        var before=ChestBlockEntityCodec.encode(chest);var report=base();report.getAsJsonObject("chest").add("items",DeepChestDiagnostics.items(chest));var result=DeepChestDiagnostics.state(report);roundTrip(h,result);
        var item=result.getAsJsonObject("chest").getAsJsonArray("items").get(0).getAsJsonObject();
        h.assertTrue(item.get("nbt").getAsString().equals(stack.getTag().toString()) && item.get("nbt").getAsString().contains(token),"short native SNBT remains complete for harness token matching");
        h.assertTrue(!item.has("nbtTruncated") && !item.has("nbtLength") && !item.has("nbtSha256"),"complete SNBT isn't marked as truncated");
        h.assertTrue(!result.has("reportTruncated") && chest.getItem(0)==stack && Arrays.equals(before.data(),ChestBlockEntityCodec.encode(chest).data()),"ordinary diagnostic and native state stay unchanged");h.succeed();
    }

    // Break caught: arbitrary status bulk or escaped text still overflows State after item excerpts were bounded.
    @GameTest(template="empty")
    public static void oversizedWholeReportUsesExplicitValidCompactFallback(GameTestHelper h) throws Exception {
        var input=base();input.addProperty("visual","v".repeat(12000));input.addProperty("lightDependencies","l".repeat(12000));input.addProperty("positionEvents","p".repeat(12000));
        input.addProperty("error","😀\"\\".repeat(4000));input.getAsJsonObject("chest").addProperty("name","n".repeat(12000));
        var pending=new JsonArray();for(int index=0;index<100;index++){var tick=new JsonObject();tick.addProperty("type","minecraft:chest");tick.addProperty("delay",index);pending.add(tick);}input.getAsJsonObject("chest").add("pending",pending);
        var before=input.deepCopy();var result=DeepChestDiagnostics.state(input);roundTrip(h,result);
        h.assertTrue(result.get("reportTruncated").getAsBoolean() && result.has("reportTruncationReason"),"oversized report explicitly explains diagnostic omission");
        h.assertTrue(result.get("active").getAsBoolean() && result.get("chestAccepted").getAsBoolean() && result.get("serverPid").getAsLong()==1234 && result.get("block").getAsString().equals("minecraft:chest") && result.get("file").getAsString().equals("fixture.miaccube"),"essential server/control/block/file scalars survive");
        h.assertTrue(result.get("error").getAsString().length()>0 && result.getAsJsonObject("chest").get("present").getAsBoolean() && result.getAsJsonObject("chest").getAsJsonArray("pending").size()>0,"bounded errors and native chest pending/details remain inspectable");
        String omitted=result.getAsJsonArray("omittedFields").toString();h.assertTrue(omitted.contains("visual") && omitted.contains("lightDependencies") && omitted.contains("positionEvents"),"bulky omitted fields are labeled");
        h.assertTrue(input.equals(before),"fallback never mutates source status/native state");h.succeed();
    }

    private static ChestBlockEntity chest(){return new ChestBlockEntity(new BlockPos(-1,-19999,-1),Blocks.CHEST.defaultBlockState());}
    private static JsonObject base(){var result=new JsonObject();result.addProperty("active",true);result.addProperty("serverPid",1234);result.addProperty("chestAccepted",true);result.addProperty("chestAction","use");result.addProperty("block","minecraft:chest");result.addProperty("file","fixture.miaccube");var chest=new JsonObject();chest.addProperty("present",true);chest.addProperty("menuOpen",true);result.add("chest",chest);return result;}
    private static String sha(String full)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(full.getBytes(StandardCharsets.UTF_8)));}
    private static void roundTrip(GameTestHelper h,JsonObject object){String json=object.toString();var buffer=new FriendlyByteBuf(Unpooled.buffer());boolean encoded=false;try{try{buffer.writeUtf(json,8192);encoded=true;}catch(EncoderException overflow){}h.assertTrue(encoded,"accepted native diagnostic must encode through actual State8192 UTF boundary");String restored=buffer.readUtf(8192);h.assertTrue(JsonParser.parseString(restored).equals(object) && !buffer.isReadable(),"bounded valid JSON round trips whole native string frame");}finally{buffer.release();}}
}
