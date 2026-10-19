package dev.madeinabyss.forge.probe.deep;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Diagnostic replies are separate from authoritative NBT and vanilla private inventory packets. */
public final class DeepChestDiagnostics {
    private DeepChestDiagnostics() { }
    private static final Set<String> ESSENTIAL=Set.of("active","serverPid","dedicated","dimension","levelMinY","levelHeight","nativeSections","x","y","z","onGround","flying","flightSpeed","fixture","windowRevision","owners","residentCubes","loadedCubes","savedCubes","unloadedCubes","loaded","block","fluidType","fluidLevel","blockLightImplemented","skyLightImplemented","blockLightKnown","blockLight","blockLightError","lightComputedCubes","lightRecomputations","file","chestAction","chestAccepted","chestError","fluidAction","fluidAccepted","fluidError","error","positionPackets","rejectedMoves","movementSamples","minY","maxVerticalStep");
    public static JsonArray items(ChestBlockEntity chest) {
        var items=new JsonArray();int remaining=512;
        for(int index=0;index<27;index++) {
            var stack=chest.getItem(index);if(stack.isEmpty())continue;
            var item=new JsonObject();item.addProperty("slot",index);item.addProperty("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());item.addProperty("count",stack.getCount());
            if(stack.hasTag()) {
                String full=stack.getTag().toString();String excerpt=prefix(full,Math.min(256,remaining));remaining-=excerpt.length();item.addProperty("nbt",excerpt);
                if(excerpt.length()!=full.length()){item.addProperty("nbtTruncated",true);item.addProperty("nbtLength",full.length());item.addProperty("nbtSha256",sha(full));}
            }
            items.add(item);
        }
        return items;
    }
    public static JsonObject state(JsonObject input) {
        if(fits(input))return input;
        var output=new JsonObject();var omitted=new JsonArray();var truncated=new JsonArray();
        output.addProperty("reportTruncated",true);output.addProperty("reportTruncationReason","diagnostic State exceeds native 8192 UTF-16 / 24576 UTF-8 byte envelope");
        for(var entry:input.entrySet()) {
            if(ESSENTIAL.contains(entry.getKey()) && entry.getValue().isJsonPrimitive())copyPrimitive(output,entry.getKey(),entry.getValue(),textLimit(entry.getKey()),truncated,entry.getKey());
            else if(!entry.getKey().equals("chest"))label(omitted,entry.getKey());
        }
        if(input.has("chest") && input.get("chest").isJsonObject())output.add("chest",compactChest(input.getAsJsonObject("chest"),omitted,truncated));
        output.add("omittedFields",omitted);if(!truncated.isEmpty())output.add("truncatedFields",truncated);
        if(!fits(output)) {
            if(output.has("chest")) {
                var chest=output.getAsJsonObject("chest");
                if(chest.has("items")){chest.add("items",minimalItems(chest.getAsJsonArray("items")));chest.addProperty("itemNbtDetailsOmitted",true);label(omitted,"chest.items.nbtDetails");}
                capStrings(chest,128,truncated,"chest.");
            }
            capStrings(output,128,truncated,"");
        }
        if(!fits(output)) {
            // Keep accepted/error/control fields and all known slot counts even in an unusually verbose report.
            var minimal=new JsonObject();
            for(String key:new String[]{"active","serverPid","dedicated","dimension","x","y","z","block","file","chestAction","chestAccepted","chestError","fluidAction","fluidAccepted","fluidError","error"})
                if(output.has(key))copyPrimitive(minimal,key,output.get(key),key.equals("file")?256:64,truncated,key);
            if(output.has("chest")) {
                var previous=output.getAsJsonObject("chest");var chest=new JsonObject();
                for(String key:new String[]{"enabled","present","menuOpen","openers","payloadBytes","live","name"})
                    if(previous.has(key) && previous.get(key).isJsonPrimitive())copyPrimitive(chest,key,previous.get(key),64,truncated,"chest."+key);
                if(previous.has("items"))chest.add("items",minimalItems(previous.getAsJsonArray("items")));
                if(previous.has("pending")){
                    var before=previous.getAsJsonArray("pending");chest.add("pending",pending(before,4,truncated));
                    int omittedPending=Math.max(0,before.size()-4)+(previous.has("pendingOmitted")?previous.get("pendingOmitted").getAsInt():0);
                    if(omittedPending>0)chest.addProperty("pendingOmitted",omittedPending);
                }
                chest.addProperty("itemNbtDetailsOmitted",true);minimal.add("chest",chest);
            }
            label(omitted,"additionalDiagnosticDetails");minimal.addProperty("reportTruncated",true);minimal.addProperty("reportTruncationReason","diagnostic State required compact fallback");
            minimal.add("omittedFields",omitted);if(!truncated.isEmpty())minimal.add("truncatedFields",truncated);output=minimal;
        }
        if(!fits(output))throw new IllegalStateException("bounded diagnostic fallback exceeds native State envelope");
        return output;
    }

    private static JsonObject compactChest(JsonObject source,JsonArray omitted,JsonArray truncated) {
        var result=new JsonObject();
        for(var entry:source.entrySet()) {
            String key=entry.getKey();var value=entry.getValue();
            if(value.isJsonPrimitive())copyPrimitive(result,key,value,key.equals("name")?512:256,truncated,"chest."+key);
            else if(key.equals("items") && value.isJsonArray())result.add(key,value.deepCopy());
            else if(key.equals("pending") && value.isJsonArray()){result.add(key,pending(value.getAsJsonArray(),8,truncated));if(value.getAsJsonArray().size()>8)result.addProperty("pendingOmitted",value.getAsJsonArray().size()-8);}
            else label(omitted,"chest."+key);
        }
        return result;
    }
    private static JsonArray minimalItems(JsonArray input) {
        var result=new JsonArray();
        for(int index=0;index<Math.min(27,input.size());index++) {
            if(!input.get(index).isJsonObject())continue;var source=input.get(index).getAsJsonObject();var item=new JsonObject();
            for(String key:new String[]{"slot","item","count"})if(source.has(key) && source.get(key).isJsonPrimitive())item.add(key,source.get(key).deepCopy());
            result.add(item);
        }
        return result;
    }
    private static JsonArray pending(JsonArray input,int limit,JsonArray truncated) {
        var result=new JsonArray();
        for(int index=0;index<Math.min(limit,input.size());index++) {
            if(!input.get(index).isJsonObject())continue;var source=input.get(index).getAsJsonObject();var tuple=new JsonObject();
            for(String key:new String[]{"type","kind","delay","priority","order"})if(source.has(key) && source.get(key).isJsonPrimitive())copyPrimitive(tuple,key,source.get(key),64,truncated,"chest.pending."+key);
            result.add(tuple);
        }
        return result;
    }
    private static void copyPrimitive(JsonObject target,String key,JsonElement value,int limit,JsonArray truncated,String label) {
        if(value.getAsJsonPrimitive().isString()) {
            String full=value.getAsString(),excerpt=prefix(full,limit);target.addProperty(key,excerpt);if(full.length()!=excerpt.length())label(truncated,label);
        } else target.add(key,value.deepCopy());
    }
    private static int textLimit(String key){return key.equals("file")?512:key.toLowerCase(java.util.Locale.ROOT).contains("error")?256:128;}
    private static void capStrings(JsonObject object,int limit,JsonArray truncated,String path) {
        for(var entry:object.entrySet())if(entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
            String full=entry.getValue().getAsString();if(full.length()>limit){entry.setValue(new com.google.gson.JsonPrimitive(prefix(full,limit)));label(truncated,path+entry.getKey());}
        }
    }
    private static void label(JsonArray labels,String value) {String bounded=prefix(value,80);for(var previous:labels)if(previous.getAsString().equals(bounded))return;if(labels.size()<16)labels.add(bounded);}
    private static boolean fits(JsonObject object){String json=object.toString();return json.length()<=8192 && json.getBytes(StandardCharsets.UTF_8).length<=24576;}
    private static String prefix(String value,int limit){int end=Math.min(limit,value.length());if(end>0 && end<value.length() && Character.isHighSurrogate(value.charAt(end-1)) && Character.isLowSurrogate(value.charAt(end)))end--;return value.substring(0,end);}
    private static String sha(String full){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(full.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("required SHA-256 unavailable",impossible);}}
}
