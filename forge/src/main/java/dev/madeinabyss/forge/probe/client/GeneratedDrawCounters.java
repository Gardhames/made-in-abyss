package dev.madeinabyss.forge.probe.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Optional render-thread submission counts. Hooks are not installed when disabled. No GL queries. */
@Mod.EventBusSubscriber(modid=MadeInAbyssProbe.MOD_ID,value=Dist.CLIENT)
public final class GeneratedDrawCounters {
    public static final boolean ENABLED=Boolean.getBoolean("mia.render.drawCounters");
    public static final int NATIVE_WORLD=0,NATIVE_SHADOW=1,SURFACE_OPAQUE=2,SURFACE_WATER=3,SURFACE_SHADOW=4;
    public static final int CALLBACKS=0,DRAWS=1,APPLIES=2,FRAMEBUFFER_BINDS=3,UNIFORM_UPLOADS=4,
            FRUSTUM_TESTS=5,FRUSTUM_NANOS=6,CANDIDATES=7,EMPTY_LAYERS=8,LAYERS=9,PROGRAM_BINDS=10;
    private static final String[] PASSES={"NATIVE_WORLD:AFTER_SOLID_BLOCKS","NATIVE_SHADOW:AFTER_SOLID_BLOCKS",
            "SURFACE_WORLD:DH_OPAQUE","SURFACE_WORLD:DH_TRANSPARENT","SURFACE_SHADOW:AFTER_SOLID_BLOCKS"};
    private static final String[] METRICS={"callbacks","draws","applies","framebufferBinds","uniformUploads",
            "frustumTests","frustumNanos","candidates","emptyLayers","layers","programBinds"};
    private static final int CAPACITY=8192;
    private static final long[][] CURRENT=ENABLED?new long[PASSES.length][METRICS.length]:null;
    private static final long[][][] RING=ENABLED?new long[CAPACITY][PASSES.length][METRICS.length]:null;
    private static final long[] WALL=ENABLED?new long[CAPACITY]:null,TARGET=ENABLED?new long[CAPACITY]:null;
    private static int active=-1,stored;private static long sequence,resetSequence;
    private GeneratedDrawCounters(){}
    public static int enter(int pass){if(!ENABLED)return -1;int previous=active;active=pass;add(CALLBACKS,1);return previous;}
    public static void leave(int previous){if(ENABLED)active=previous;}
    public static void add(int metric,long count){if(ENABLED&&active>=0)CURRENT[active][metric]+=count;}
    public static long frustumBegin(){if(!ENABLED)return 0;add(FRUSTUM_TESTS,1);return System.nanoTime();}
    public static void frustumEnd(long start){if(ENABLED)add(FRUSTUM_NANOS,System.nanoTime()-start);}
    public static void finishFrame(long wall,long target){
        if(!ENABLED)return;int slot=(int)(sequence%CAPACITY);
        for(int p=0;p<PASSES.length;p++){System.arraycopy(CURRENT[p],0,RING[slot][p],0,METRICS.length);java.util.Arrays.fill(CURRENT[p],0);}
        WALL[slot]=wall;TARGET[slot]=target;sequence++;stored=Math.min(CAPACITY,stored+1);
    }
    @SubscribeEvent public static void frame(TickEvent.RenderTickEvent event){
        if(!ENABLED||event.phase!=TickEvent.Phase.END)return;
        if(Minecraft.getInstance().level==null){reset();return;}
        finishFrame(System.currentTimeMillis(),ClientGeneratedLight.targetGeneration());
    }
    private static void reset(){stored=0;active=-1;resetSequence=sequence;for(var counts:CURRENT)java.util.Arrays.fill(counts,0);}
    public static JsonObject status(JsonObject parameters){
        var out=new JsonObject();out.addProperty("enabled",ENABLED);out.addProperty("batchedDraw",GeneratedBatchedDraw.ENABLED);
        if(!ENABLED)return out;
        boolean resetting=parameters.has("reset")&&parameters.get("reset").getAsBoolean();if(resetting)reset();
        long after=parameters.has("afterFrame")?parameters.get("afterFrame").getAsLong():0;
        long oldest=sequence-stored+1;out.addProperty("latestFrame",sequence);out.addProperty("oldestFrame",oldest);
        out.addProperty("resetFrame",resetSequence);
        // A fresh reader starts after the requested reset; an established cursor must see lost rows
        // if another consumer/disconnect clears the ring during its capture.
        long cursor=resetting?sequence:after<=0?resetSequence:after;
        out.addProperty("droppedFrames",Math.max(0,oldest-cursor-1));
        out.addProperty("capacity",CAPACITY);var gson=new Gson();out.add("passes",gson.toJsonTree(PASSES));out.add("metrics",gson.toJsonTree(METRICS));
        out.addProperty("scope","GL submission calls inside named passes; includes setup/clear/restore. Not GPU time. uniforms at GlStateManager, IrisRenderSystem and IrisLodRenderProgram boundaries.");
        var rows=new com.google.gson.JsonArray();for(long n=Math.max(oldest,after+1);n<=sequence;n++){
            int slot=(int)((n-1)%CAPACITY);var row=new JsonObject();row.addProperty("frame",n);row.addProperty("wallMillis",WALL[slot]);row.addProperty("targetGeneration",TARGET[slot]);row.add("counts",gson.toJsonTree(RING[slot]));rows.add(row);
        }out.add("frames",rows);return out;
    }
}
