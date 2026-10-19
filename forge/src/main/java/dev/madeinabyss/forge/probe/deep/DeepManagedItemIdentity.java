package dev.madeinabyss.forge.probe.deep;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.util.List;
import java.util.Objects;
import java.lang.ref.WeakReference;

/** Explicit ENT-01 identity ownership; values never retain their weak Level key. */
public final class DeepManagedItemIdentity {
    public static final int CLIENT_CAP=16;
    private static final Map<Level,WeakReference<ItemEntity>> SERVER=new WeakHashMap<>();
    private static final Map<Level,Map<UUID,Admission>> CLIENT=new WeakHashMap<>();
    private static final Map<Level,Counters> COUNTERS=new WeakHashMap<>();
    public record Admission(UUID uuid,int id,UUID session) {}
    private static final class Counters {long tickSkips,authorityTickSkips,renderCalls,rendered,shadowSkips,lightSkips;UUID lastUuid;double lastY;}
    private DeepManagedItemIdentity() {}
    public static synchronized void admit(ItemEntity item) {
        Objects.requireNonNull(item);if(item.level().isClientSide || item.isRemoved())throw new IllegalArgumentException("live server item required");
        var ref=SERVER.get(item.level());var previous=ref==null?null:ref.get();
        if(previous!=null && previous!=item && !previous.isRemoved())throw new IllegalArgumentException("one managed native item per level");
        SERVER.put(item.level(),new WeakReference<>(item));
    }
    public static synchronized void release(ItemEntity item) {
        if(item==null)return;var ref=SERVER.get(item.level());if(ref!=null && ref.get()==item)SERVER.remove(item.level());
    }
    public static synchronized boolean managed(Entity entity) {
        if(!(entity instanceof ItemEntity item) || item.isRemoved())return false;
        if(item.level().isClientSide){var entries=CLIENT.get(item.level());var admission=entries==null?null:entries.get(item.getUUID());return admission!=null && admission.id()==item.getId();}
        var ref=SERVER.get(item.level());return ref!=null && ref.get()==item;
    }
    public static void clientAdmit(Level level, UUID uuid, int id) { clientAdmit(level,uuid,id,new UUID(0,0)); }
    public static synchronized void clientAdmit(Level level, UUID uuid, int id, UUID session) {
        Objects.requireNonNull(level);Objects.requireNonNull(uuid);Objects.requireNonNull(session);
        if(id<0)throw new IllegalArgumentException("negative native item id");
        var entries=CLIENT.get(level);var candidate=new Admission(uuid,id,session);
        if(entries!=null){var previous=entries.get(uuid);if(candidate.equals(previous))return;
            if(previous!=null)throw new IllegalArgumentException("conflicting UUID admission");
            for(var entry:entries.values())if(entry.id()==id)throw new IllegalArgumentException("conflicting native entity id");
            if(entries.size()>=CLIENT_CAP)throw new IllegalArgumentException("managed client item cap");
        } else {entries=new HashMap<>();CLIENT.put(level,entries);}
        entries.put(uuid,candidate);
    }
    public static synchronized void clientRelease(Level level, UUID uuid) {var entries=CLIENT.get(level);if(entries!=null){entries.remove(uuid);if(entries.isEmpty())CLIENT.remove(level);}}
    public static synchronized boolean clientRelease(Level level, UUID uuid, int id, UUID session) {
        if(!clientAdmitted(level,uuid,id,session))return false;clientRelease(level,uuid);return true;
    }
    public static synchronized boolean clientAdmitted(Level level, UUID uuid, int id, UUID session) {
        var entries=CLIENT.get(level);return entries!=null && new Admission(uuid,id,session).equals(entries.get(uuid));
    }
    public static synchronized int clientCount(Level level) {var entries=CLIENT.get(level);return entries==null?0:entries.size();}
    public static synchronized List<Admission> clientAdmissions(Level level) {var entries=CLIENT.get(level);return entries==null?List.of():List.copyOf(entries.values());}
    public static synchronized void clearClient(Level level) {CLIENT.remove(level);COUNTERS.remove(level);}
    public static synchronized void recordTickSkip(Entity entity){if(managed(entity))counters(entity.level()).tickSkips++;}
    public static synchronized void recordAuthorityTickSkip(Entity entity){if(managed(entity))counters(entity.level()).authorityTickSkips++;}
    public static synchronized void recordRenderCall(ItemEntity item){if(managed(item))counters(item.level()).renderCalls++;}
    public static synchronized void recordRendered(ItemEntity item){if(managed(item)){var c=counters(item.level());c.rendered++;c.lastUuid=item.getUUID();c.lastY=item.getY();}}
    public static synchronized void recordShadowSkip(Entity entity){if(managed(entity))counters(entity.level()).shadowSkips++;}
    public static synchronized void recordLightSkip(Entity entity){if(managed(entity))counters(entity.level()).lightSkips++;}
    public static synchronized com.google.gson.JsonObject countersStatus(Level level){var out=new com.google.gson.JsonObject();var c=COUNTERS.get(level);out.addProperty("tickSkips",c==null?0:c.tickSkips);out.addProperty("authorityTickSkips",c==null?0:c.authorityTickSkips);out.addProperty("renderCalls",c==null?0:c.renderCalls);out.addProperty("rendered",c==null?0:c.rendered);out.addProperty("shadowSkips",c==null?0:c.shadowSkips);out.addProperty("lightSkips",c==null?0:c.lightSkips);if(c!=null && c.lastUuid!=null){out.addProperty("lastRenderedUuid",c.lastUuid.toString());out.addProperty("lastRenderedY",c.lastY);}return out;}
    private static Counters counters(Level level){return COUNTERS.computeIfAbsent(level,key->new Counters());}
}
