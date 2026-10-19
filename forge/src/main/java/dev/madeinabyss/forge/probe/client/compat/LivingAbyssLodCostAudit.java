package dev.madeinabyss.forge.probe.client.compat;

import com.google.gson.*;
import dev.madeinabyss.forge.worldgen.*;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.*;
import net.minecraft.world.level.block.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Actual camera acquisition/page geometry, counted BEFORE original32 evaluation.
 * Uniform diagnostic light avoids spending minutes solving scalars merely to count
 * their immutable owners. Raw surfaces and owner count are independent of light values;
 * merged faces below are explicitly a diagnostic lower bound, never a native frame claim. */
public final class LivingAbyssLodCostAudit {
    private LivingAbyssLodCostAudit(){}
    public static JsonObject measure(GeneratedPreviewProfile v14,GeneratedPreviewProfile v15,JsonObject oldManifest,JsonObject manifest,Path root)throws Exception {
        Files.createDirectories(root);var result=new JsonObject();
        result.addProperty("scope","full shaft camera plan; unchanged v14 reference index vs v15 B; complete CPU acquisition/geometry, memory-only cold source, uniform diagnostic light and simulated ACK; no original32/GPU/frame-time claim");
        result.add("camera",new Gson().toJsonTree(new AbyssLodPipeline.Camera(0,-300,0,0,0,-1)));
        var a=one(v14,oldManifest,root.resolve("v14"),false);var b=one(v15,manifest,root.resolve("v15"),true);
        result.add("v14",a);result.add("v15",b);
        result.addProperty("rawSurfaceRatio",b.get("rawFaces").getAsDouble()/a.get("rawFaces").getAsDouble());
        result.addProperty("lightOwnerRatio",b.get("lightOwners").getAsDouble()/a.get("lightOwners").getAsDouble());
        result.addProperty("geometryPreparationRatio",b.get("elapsedMillis").getAsDouble()/a.get("elapsedMillis").getAsDouble());
        result.addProperty("retainedIndexRatio",b.getAsJsonObject("pageStatus").get("retainedIndexBytes").getAsDouble()/a.getAsJsonObject("pageStatus").get("retainedIndexBytes").getAsDouble());
        result.addProperty("peakUsedHeapBytes",Math.max(a.get("peakUsedHeapBytes").getAsLong(),b.get("peakUsedHeapBytes").getAsLong()));
        result.addProperty("gatePass",a.get("completed").getAsBoolean()&&b.get("completed").getAsBoolean()&&result.get("rawSurfaceRatio").getAsDouble()<=1.5&&result.get("lightOwnerRatio").getAsDouble()<=1.5
            &&result.get("geometryPreparationRatio").getAsDouble()<=1.65&&result.get("retainedIndexRatio").getAsDouble()<=1.65&&result.get("peakUsedHeapBytes").getAsLong()<6_000_000_000L);
        Files.writeString(root.resolve("lod-comparison.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));return result;
    }
    private static JsonObject one(GeneratedPreviewProfile profile,JsonObject manifest,Path dir,boolean deduplicate)throws Exception {
        System.gc();
        if(Files.exists(dir))throw new IllegalArgumentException("LOD cost audit refuses to reuse prior results: "+dir);Files.createDirectories(dir);
        var source=new GeneratedSkySource(profile,Map.of());var palette=new HashMap<Integer,TerrainMaterial>();
        for(var state:Block.BLOCK_STATE_REGISTRY)if(LodBlockStateMaterials.findMaterial(state)!=null)
            palette.put(Block.getId(state),LodBlockStateMaterials.material(state));
        var b=manifest.getAsJsonObject("nativeWorldView").getAsJsonArray("bounds");
        var bounds=new TerrainLodColumns.Bounds(b.get(0).getAsInt(),b.get(1).getAsInt(),b.get(2).getAsInt(),b.get(3).getAsInt(),b.get(4).getAsInt(),b.get(5).getAsInt());
        var bands=List.of(new TerrainLodRefinement.Band(384,1),new TerrainLodRefinement.Band(768,2),new TerrainLodRefinement.Band(1280,4),new TerrainLodRefinement.Band(1920,8));
        var lighting=new AbyssLodPipeline.BoxLighting(){public AbyssLodPipeline.Light light(TerrainLodColumns.Box box){return new AbyssLodPipeline.Light(0,0);}
            public int rgba(TerrainLodColumns.Box box){return LivingAbyssColors.lod(source,box);}};
        var input=new AbyssLodPipeline.Input(new AbyssLodPipeline.Target("v15-part3a-geometry-audit",1,profile.fingerprint(),0,0,0,1),
            new AbyssLodPipeline.Camera(0,-300,0,0,0,-1),new TerrainLodColumns.Bounds(-160,-460,-160,159,-141,159),bounds,bands,List.of(),
            (x,y,z)->{int id=source.stateIdForDerivedGeometry(Math.toIntExact(x),Math.toIntExact(y),Math.toIntExact(z));var mat=palette.get(id);
                if(mat==null)throw new IllegalArgumentException("Unmapped final v15 state "+Block.stateById(id));return new TerrainSample.Known(mat);},lighting,AbyssLodPipeline.Limits.defaults());
        var result=new JsonObject();long started=System.nanoTime(),until=started+TimeUnit.MINUTES.toNanos(15),next=started,peakHeap=0;
        var memory=java.lang.management.ManagementFactory.getMemoryMXBean();
        String previousIndex=System.getProperty("mia.dh.deduplicateIncidence");System.setProperty("mia.dh.deduplicateIncidence",Boolean.toString(deduplicate));
        String previousCache=System.getProperty("mia.dh.previewCacheDirectory");System.setProperty("mia.dh.previewCacheDirectory","");
        AbyssLodPipeline.Proposal last=null;
        // This audit counts ownership/index work, not persistence. No raw binary archive.
        try(var pipeline=new AbyssLodPipeline(true,true,null)){
            pipeline.request(input);
            while(System.nanoTime()<until){var proposal=pipeline.poll();if(proposal.isPresent()){last=proposal.get();pipeline.acknowledge(last,true);}
                peakHeap=Math.max(peakHeap,memory.getHeapMemoryUsage().getUsed());
                var status=pipeline.status();if(System.nanoTime()>=next){Files.writeString(dir.resolve("progress.json"),new Gson().toJson(status));next=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);}
                if(Boolean.TRUE.equals(status.get("completed"))||!"".equals(status.get("error")))break;Thread.sleep(5);}
            var status=pipeline.status();result.add("status",new Gson().toJsonTree(status));result.addProperty("completed",Boolean.TRUE.equals(status.get("completed")));
            result.addProperty("elapsedMillis",(System.nanoTime()-started)/1e6);result.addProperty("rawFaces",((Number)status.getOrDefault("surfaceRawFaces",0)).longValue());
            result.addProperty("peakUsedHeapBytes",peakHeap);result.addProperty("maxHeapBytes",Runtime.getRuntime().maxMemory());
            // Streaming uses IncrementalSurfacePages; its exact complete incidence count
            // is obtained from the actual page index, not extrapolated from a patch.
            long owners=0,rawFaces=0;
            if(last!=null&&last.surfacePages()!=null){var boxes=new HashSet<TerrainLodColumns.Box>();
                for(var page:last.surfacePages().pages())for(var owner:page.prepared().owners())boxes.add(owner.box());owners=boxes.size();
                result.addProperty("uniformLightMergedFaces",last.surfacePages().faces());
            }
            var contextField=AbyssLodPipeline.class.getDeclaredField("context");contextField.setAccessible(true);var context=contextField.get(pipeline);
            var pagesField=context.getClass().getDeclaredField("incrementalPages");pagesField.setAccessible(true);var pages=pagesField.get(context);
            var pageStatus=(Map<?,?>)pages.getClass().getMethod("status").invoke(pages);
            result.add("pageStatus",new Gson().toJsonTree(pageStatus));
            if(pageStatus.get("rawFaces") instanceof Number n)rawFaces=n.longValue();
            if(rawFaces==0&&last!=null&&last.surfacePages()!=null)rawFaces=last.surfacePages().faces();
            result.addProperty("rawFaces",rawFaces);result.addProperty("lightOwners",owners);
        } finally {if(previousIndex==null)System.clearProperty("mia.dh.deduplicateIncidence");else System.setProperty("mia.dh.deduplicateIncidence",previousIndex);
            if(previousCache==null)System.clearProperty("mia.dh.previewCacheDirectory");else System.setProperty("mia.dh.previewCacheDirectory",previousCache);}
        result.addProperty("deduplicateIndex",deduplicate);
        Files.writeString(dir.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));return result;
    }
}
