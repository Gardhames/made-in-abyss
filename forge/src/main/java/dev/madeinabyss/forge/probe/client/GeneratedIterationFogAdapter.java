package dev.madeinabyss.forge.probe.client;

import com.mojang.logging.LogUtils;
import dev.madeinabyss.kernel.light.IterationFogSourceTransform;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;

/** Opt-in ORIGINAL fog presentation, scoped to the unmodified verified shader pack. */
public final class GeneratedIterationFogAdapter {
    public static final String PROPERTY="madeinabyss.iterationTFogAdapter";
    private static final String PACK="iterationT 3.2.0.zip";
    private static final String HASH="c0735d5f1de0edca2b1c155dae1c650ddb8d2d04a3ebdd3d2ff74dd7a19db116";
    private static final boolean ENABLED=Boolean.getBoolean(PROPERTY);
    // Each ShaderPack object is one reload. Values never retain their weak keys.
    private static final Map<Object,Guard> RELOADS=new WeakHashMap<>();
    private static String lastReason=ENABLED?"awaiting shader source":"disabled";
    private static long transformed,rejected,hashChecks;
    private GeneratedIterationFogAdapter() {}
    public record Decision(String source,boolean cacheable) {}
    private record Guard(boolean accepted,String reason) {}
    public static synchronized Decision transform(Object program,String stage,String source) {
        if(!ENABLED||(!stage.equals("composite5")&&!stage.equals("composite2")))return new Decision(source,true);
        try {
            Class<?> iris=Class.forName("net.irisshaders.iris.Iris");
            if(!PACK.equals(String.valueOf(iris.getMethod("getCurrentPackName").invoke(null)))) {
                lastReason="awaiting exact active pack";
                // Constructor directive discovery can precede active-pack assignment.
                return new Decision(source,false);
            }
            Object parent=program.getClass().getMethod("getParent").invoke(program);
            Object pack=parent.getClass().getMethod("getPack").invoke(parent);
            var current=(java.util.Optional<?>)iris.getMethod("getCurrentPack").invoke(null);
            if(current.isEmpty()||current.get()!=pack) {
                lastReason="awaiting active pack identity";
                return new Decision(source,false);
            }
            Guard guard=RELOADS.get(pack);
            if(guard==null){guard=verify();RELOADS.put(pack,guard);}
            if(!guard.accepted()){lastReason=guard.reason();return new Decision(source,true);}
            var result=IterationFogSourceTransform.transform(stage,source);lastReason=result.reason();
            if(result.changed()) {
                transformed++;
                LogUtils.getLogger().info("MIA optional IterationT fog adapter: {} adapted; rain air extinction x1..3, physical SKY unchanged",stage);
            }else {
                rejected++;
                LogUtils.getLogger().warn("MIA optional IterationT fog adapter: {} unchanged: {}",stage,result.reason());
            }
            return new Decision(result.source(),true);
        }catch(ReflectiveOperationException|RuntimeException failure) {
            lastReason="optional source hook unavailable: "+failure;rejected++;
            LogUtils.getLogger().warn("MIA optional IterationT fog adapter bypassed: {}",lastReason);
            return new Decision(source,true);
        }
    }
    private static Guard verify() {
        try {
            String version=ModList.get().getModContainerById("oculus").map(mod->mod.getModInfo().getVersion().toString()).orElse("absent");
            if(!version.equals("1.8.0"))return reject("unsupported Oculus "+version);
            var path=Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks").resolve(PACK);
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(path)) {
                byte[] buffer=new byte[65536];int read;
                while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);
            }
            hashChecks++;
            if(!HASH.equals(HexFormat.of().formatHex(digest.digest())))return reject("original shader ZIP fingerprint mismatch");
            return new Guard(true,"verified original IterationT3.2.0 / Oculus1.8.0");
        }catch(Exception failure){return reject("shader ZIP verification unavailable: "+failure);}
    }
    private static Guard reject(String reason) {
        rejected++;LogUtils.getLogger().warn("MIA optional IterationT fog adapter bypassed: {}",reason);
        return new Guard(false,reason);
    }
    public static synchronized Map<String,Object> status() {
        var out=new LinkedHashMap<String,Object>();out.put("enabled",ENABLED);out.put("reason",lastReason);
        out.put("transformedStages",transformed);out.put("rejectedStagesOrReloads",rejected);out.put("originalZipHashChecks",hashChecks);
        out.put("scope","ORIGINAL optional air-fog extinction x1..3 by wetness; stock INDOOR_FOG and VFOG_IGNORE_WORLDTIME required; physical SKY/exposure/ZIP/JAR unchanged");return out;
    }
}
