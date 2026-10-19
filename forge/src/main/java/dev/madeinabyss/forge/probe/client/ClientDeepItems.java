package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import java.util.HashSet;

/** Client-thread admission precedes native spawn packets; status observes native entity lookup only. */
public final class ClientDeepItems {
    private ClientDeepItems() {}
    public static void admit(ClientLevel level,UUID uuid,int id,UUID session){
        var occupied=level.getEntity(id);
        if(occupied!=null && (!(occupied instanceof ItemEntity) || !occupied.getUUID().equals(uuid)
                || !DeepManagedItemIdentity.managed(occupied)))throw new IllegalArgumentException("native entity id already occupied");
        DeepManagedItemIdentity.clientAdmit(level,uuid,id,session);
    }
    /** Release admission only: the ordered native removal packet owns entity removal. */
    public static boolean release(ClientLevel level,UUID uuid,int id,UUID session){return DeepManagedItemIdentity.clientRelease(level,uuid,id,session);}
    /** Local disconnect/withdraw cleanup removes only exact admitted native identities. */
    public static void clear(ClientLevel level){
        if(level==null)return;
        for(var admission:DeepManagedItemIdentity.clientAdmissions(level)){
            var entity=level.getEntity(admission.id());
            if(entity instanceof ItemEntity && entity.getUUID().equals(admission.uuid()))level.removeEntity(admission.id(),Entity.RemovalReason.DISCARDED);
        }
        DeepManagedItemIdentity.clearClient(level);
    }
    public static JsonObject status(){return status(Minecraft.getInstance());}
    public static JsonObject status(Minecraft client){
        var out=new JsonObject();var entries=new JsonArray();out.add("items",entries);
        if(client.level==null){out.addProperty("admitted",0);out.addProperty("nativeCount",0);return out;}
        var level=client.level;int total=0,orphans=0;var orphanItems=new JsonArray();var admissions=DeepManagedItemIdentity.clientAdmissions(level);var admittedUuids=new HashSet<UUID>();for(var admission:admissions)admittedUuids.add(admission.uuid());
        // Admission withdrawal must not hide a native object which a later removal packet has not removed yet.
        for(var nativeEntity:level.entitiesForRendering())if(nativeEntity instanceof ItemEntity nativeItem){
            boolean marker=nativeItem.getItem().hasTag() && nativeItem.getItem().getTag().contains("miaManagedItem");
            if(marker || admittedUuids.contains(nativeItem.getUUID()))total++;
            if(marker && !DeepManagedItemIdentity.managed(nativeItem)){orphans++;var orphan=new JsonObject();orphan.addProperty("id",nativeItem.getId());orphan.addProperty("uuid",nativeItem.getUUID().toString());orphan.add("position",vector(nativeItem.position()));if(orphanItems.size()<16)orphanItems.add(orphan);}
        }
        for(var admission:admissions){
            var row=new JsonObject();row.addProperty("uuid",admission.uuid().toString());row.addProperty("id",admission.id());row.addProperty("session",admission.session().toString());
            var entity=level.getEntity(admission.id());int matches=0;
            for(var candidate:level.entitiesForRendering())if(candidate.getUUID().equals(admission.uuid()))matches++;
            row.addProperty("uuidCount",matches);
            boolean present=entity instanceof ItemEntity && entity.getUUID().equals(admission.uuid());row.addProperty("present",present);
            if(entity!=null){row.addProperty("nativeClass",entity.getClass().getName());row.addProperty("nativeUuid",entity.getUUID().toString());}
            if(present){var item=(ItemEntity)entity;row.add("position",vector(item.position()));row.add("motion",vector(item.getDeltaMovement()));row.addProperty("tickCount",item.tickCount);row.addProperty("age",item.getAge());row.addProperty("onGround",item.onGround());row.addProperty("removed",item.isRemoved());
                row.addProperty("item",BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());row.addProperty("count",item.getItem().getCount());
                if(item.getItem().hasTag()){String nbt=item.getItem().getTag().toString();row.addProperty("nbt",nbt.substring(0,Math.min(256,nbt.length())));row.addProperty("nbtTruncated",nbt.length()>256);row.addProperty("nbtLength",nbt.length());}
                row.addProperty("rendererClass",client.getEntityRenderDispatcher().getRenderer(item).getClass().getName());
            }
            entries.add(row);
        }
        out.addProperty("admitted",DeepManagedItemIdentity.clientCount(level));out.addProperty("nativeCount",total);out.addProperty("orphanCount",orphans);out.add("orphans",orphanItems);out.addProperty("orphanDetailsTruncated",orphans>orphanItems.size());out.add("render",DeepManagedItemIdentity.countersStatus(level));out.addProperty("nativeItemModel",true);out.addProperty("serverOwnsMotion",true);out.addProperty("deepShadowImplemented",false);out.addProperty("fullBrightSubstitution",false);return out;
    }
    private static JsonArray vector(Vec3 position){var out=new JsonArray();out.add(position.x);out.add(position.y);out.add(position.z);return out;}
}
