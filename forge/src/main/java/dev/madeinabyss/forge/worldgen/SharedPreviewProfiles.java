package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;
import net.minecraft.world.level.biome.FixedBiomeSource;
import java.security.MessageDigest;
import java.util.*;

/** One generated profile per recipe per JVM. The integrated server builds it while the world loads and the
 * client only samples it, so the minute-long construction is never repeated on the render thread. Kept out of
 * {@link GeneratedPreviewProfile}: that class is part of the world's algorithm SHA. */
public final class SharedPreviewProfiles {
    private record Shared(GeneratedPreviewProfile profile,Object biomeIdentity){}
    private static final Map<String,Shared> SHARED=new LinkedHashMap<>();
    private SharedPreviewProfiles(){}
    private static Object biomeIdentity(FixedBiomeSource biomes){var holders=biomes.possibleBiomes();return holders.isEmpty()?biomes:holders.iterator().next().value();}
    private static String key(JsonObject manifest,String id){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((id+"|"+manifest+"|"+System.getProperty("mia.generated.optimizedSampler","true")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    /** Server side: reuse only a profile bound to the same biome registry objects. */
    public static GeneratedPreviewProfile forServer(JsonObject manifest,String id,FixedBiomeSource biomes){return get(manifest,id,biomes,true);}
    /** Client side: only samples terrain, so a profile built for any registry is valid. */
    public static GeneratedPreviewProfile forSampling(JsonObject manifest,String id,FixedBiomeSource biomes){return get(manifest,id,biomes,false);}
    private static GeneratedPreviewProfile get(JsonObject manifest,String id,FixedBiomeSource biomes,boolean bound){
        String key=key(manifest,id);Object identity=biomeIdentity(biomes);
        synchronized(SHARED){
            var hit=SHARED.get(key);
            if(hit!=null&&(!bound||hit.biomeIdentity()==identity))return hit.profile();
            long start=System.nanoTime();
            var built=new GeneratedPreviewProfile(manifest,id,biomes);
            System.out.println("MIA_PROFILE_BUILD ms="+(System.nanoTime()-start)/1_000_000+" thread="+Thread.currentThread().getName()+" bound="+bound+" fingerprint="+built.fingerprint());
            SHARED.remove(key);SHARED.put(key,new Shared(built,identity));
            while(SHARED.size()>2)SHARED.remove(SHARED.keySet().iterator().next());
            return built;
        }
    }
}
