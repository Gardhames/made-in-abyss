package dev.madeinabyss.forge.probe.client.compat;

import com.google.gson.*;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.worldgen.*;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

/** Opt-in real CPU pipeline benchmark. ACK is simulated, never a native WORLD draw claim. */
@PrefixGameTestTemplate(false)
public final class LodSpeedGameTests {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    @Mod.EventBusSubscriber(modid=MadeInAbyssProbe.MOD_ID,bus=Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void register(RegisterGameTestsEvent event){
            if(!System.getProperty("mia.bench.lodManifest","").isBlank()){
                event.register(LodSpeedGameTests.class);event.register(LodSpeedOracleGameTests.class);
            }
        }
    }
    // Blocking diagnostic in its own batch: game ticks must not time out a wall-clock benchmark.
    @GameTest(templateNamespace=MadeInAbyssProbe.MOD_ID,template="empty",timeoutTicks=20,batch="miaLodSpeed")
    public static void measureActualLodPipeline(GameTestHelper helper)throws Exception {
        var manifestPath=Path.of(System.getProperty("mia.bench.lodManifest"));
        var manifest=JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
        var root=Path.of(System.getProperty("mia.bench.lodOutput"));Files.createDirectories(root);
        var requested=Set.of(System.getProperty("mia.bench.lodCameras","shaft,ledge,island").split(","));
        var names=List.of("shaft","ledge","island","fixture");
        for(var name:requested)if(!names.contains(name)&&!name.equals("controls")&&!name.equals("costs"))
            throw new IllegalArgumentException("Unknown LOD benchmark camera: "+name+"; use comma-separated names");
        if(requested.stream().noneMatch(names::contains)&&!requested.contains("controls"))
            throw new IllegalArgumentException("At least one LOD benchmark camera or controls is required");
        var cameras=List.of(new AbyssLodPipeline.Camera(0,-300,0,0,0,-1),
                new AbyssLodPipeline.Camera(58,-530,-446,0,0,-1),new AbyssLodPipeline.Camera(530,100,0,-1,0,0),
                new AbyssLodPipeline.Camera(58,-530,-446,0,0,-1));
        var results=new JsonArray();boolean all=true,quality=true,restart=true;
        if(requested.contains("controls")){
            var controls=new JsonObject();controls.add("aggregate8192",completionControl(8192,false));
            controls.add("aggregate1000000",completionControl(1_000_000,true));
            Files.writeString(root.resolve("controls.json"),JSON.toJson(controls));
        }
        for(int i=0;i<names.size();i++)if(requested.contains(names.get(i))){
            var out=measure(helper,manifest,root,names.get(i),cameras.get(i));results.add(out);
            all&=out.get("completed").getAsBoolean();
            if(out.get("cacheMode").getAsString().equals("warm"))restart&=out.getAsJsonObject("status").get("actualLightJobs").getAsLong()==0;
            if(out.has("referenceComparison"))quality&=out.getAsJsonObject("referenceComparison").get("accepted").getAsBoolean();
            Files.writeString(root.resolve("summary.json"),JSON.toJson(results));
        }
        helper.assertTrue(quality,"LOD fixture quality comparison failed; captured results are in "+root.resolve("summary.json"));
        helper.assertTrue(restart,"Warm archive recomputed exact light; see "+root.resolve("summary.json"));
        helper.assertTrue(all,"LOD benchmark did not complete; see "+root.resolve("summary.json"));helper.succeed();
    }
    /** Small independently enumerable workload: eight 16x16x16 source cells with one rock layer. */
    private static JsonObject completionControl(long budget,boolean repeat)throws Exception {
        var near=new TerrainLodColumns.Bounds(0,0,0,15,15,15);var world=new TerrainLodColumns.Bounds(0,0,0,127,15,15);
        var input=new AbyssLodPipeline.Input(new AbyssLodPipeline.Target("completion-control",1,"non-disk-source",0,0,0,1),
                new AbyssLodPipeline.Camera(0,8,8,1,0,0),near,world,List.of(new TerrainLodRefinement.Band(256,1)),List.of(),
                (x,y,z)->new TerrainSample.Known(y==8?TerrainMaterial.ROCK:TerrainMaterial.AIR),box->new AbyssLodPipeline.Light(7,0),
                new AbyssLodPipeline.Limits(budget,20_000,80_000,8L*1024*1024,128,1));
        var out=new JsonObject();out.addProperty("budget",budget);out.addProperty("independentExpectedFineSourceCells",8);
        try(var pipeline=new AbyssLodPipeline(true,!Boolean.getBoolean("mia.bench.lodReference"))){
            pipeline.request(input);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);AbyssLodPipeline.Proposal last=null;
            while(System.nanoTime()<until){var p=pipeline.poll();if(p.isPresent()){last=p.get();pipeline.acknowledge(last,true);}
                var s=pipeline.status();if(Boolean.TRUE.equals(s.get("completed"))||!s.get("error").equals(""))break;
                if(((Number)s.get("fineCursor")).intValue()==((Number)s.get("plannedFineJobs")).intValue()
                        &&!Boolean.TRUE.equals(s.get("workerPending"))&&!Boolean.TRUE.equals(s.get("proposalPending")))break;
                Thread.sleep(2);
            }
            out.add("status",JSON.toJsonTree(pipeline.status()));if(last!=null)out.add("oracle",oracle(last));
            if(repeat&&Boolean.TRUE.equals(pipeline.status().get("completed"))){pipeline.request(input);until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(System.nanoTime()<until){var p=pipeline.poll();if(p.isPresent())pipeline.acknowledge(p.get(),true);if(Boolean.TRUE.equals(pipeline.status().get("completed")))break;Thread.sleep(2);}
                out.add("identicalRequestStatus",JSON.toJsonTree(pipeline.status()));}
        }
        return out;
    }
    private static JsonObject measure(GameTestHelper helper,JsonObject manifest,Path root,String name,AbyssLodPipeline.Camera camera)throws Exception {
        var dir=root.resolve(name);Files.createDirectories(dir);
        String mode=System.getProperty("mia.bench.lodCacheMode","cold"),cacheRoot=System.getProperty("mia.bench.lodCacheRoot","");
        if(!mode.equals("cold")&&!mode.equals("warm"))throw new IllegalArgumentException("LOD cache mode must be cold or warm");
        var cache=cacheRoot.isBlank()?dir.resolve("cache"):Path.of(cacheRoot).resolve(name);
        if(mode.equals("cold")&&Files.exists(cache))throw new IllegalStateException("Cold benchmark refuses an existing cache: "+cache);
        if(mode.equals("warm")&&!Files.isDirectory(cache))throw new IllegalStateException("Warm benchmark requires a persisted cache: "+cache);
        Files.createDirectories(cache);String prior=System.getProperty("mia.dh.previewCacheDirectory");
        System.setProperty("mia.dh.previewCacheDirectory",cache.toString());
        var out=new JsonObject();out.addProperty("camera",name);out.add("pose",JSON.toJsonTree(camera));
        out.addProperty("referenceMode",Boolean.getBoolean("mia.bench.lodReference"));
        out.addProperty("cacheMode",mode);out.addProperty("cacheDirectory",cache.toAbsolutePath().toString());
        out.addProperty("scope","real CPU pipeline, exact original32 lighting, simulated immediate ACK; no DH GPU/client/native density or saved overrides");
        out.addProperty("manifestSha256",sha(Files.readAllBytes(Path.of(System.getProperty("mia.bench.lodManifest")))));
        out.addProperty("pipelineCode",TerrainLodDiskCache.codeIdentity(AbyssLodPipeline.class,IncrementalSurfacePages.class));
        out.addProperty("physicalLightCode",AbyssLodPhysicalLighting.codeIdentity());
        out.addProperty("availableProcessors",Runtime.getRuntime().availableProcessors());out.addProperty("maximumHeapBytes",Runtime.getRuntime().maxMemory());
        out.addProperty("javaVersion",System.getProperty("java.version"));out.addProperty("pollMillis",2);
        var biomes=new FixedBiomeSource(helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        long profileStart=System.nanoTime();var profile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),biomes);
        var source=new GeneratedSkySource(profile,Map.of());String identity=TerrainLodDiskCache.identity("abyss-authoritative-source-v1",profile.fingerprint(),source.cacheGeometryIdentity());
        out.addProperty("physicalResourceIdentity",source.physicalSemanticsIdentity());
        out.add("loadedMods",JSON.toJsonTree(net.minecraftforge.fml.ModList.get().getMods().stream().map(m->m.getModId()+":"+m.getVersion()).sorted().toList()));
        out.addProperty("profileBuildMillis",(System.nanoTime()-profileStart)/1e6);out.addProperty("fingerprint",profile.fingerprint());
        var view=manifest.getAsJsonObject("nativeWorldView");var b=view.getAsJsonArray("bounds");
        long maximumSamples=view.has("maxSamples")?view.get("maxSamples").getAsLong():120_000_000;
        int maximumBoxes=view.has("maxBoxes")?view.get("maxBoxes").getAsInt():400_000;
        if(maximumSamples>120_000_000||maximumBoxes>400_000)throw new IllegalArgumentException("Aggregate scene cap exceeded");
        var limits=new AbyssLodPipeline.Limits(maximumSamples,maximumBoxes,Math.multiplyExact(maximumBoxes,4),96L*1024*1024,262144,8);
        var bounds=name.equals("fixture")?new TerrainLodColumns.Bounds(-16,-576,-512,111,-449,-385):
                new TerrainLodColumns.Bounds(b.get(0).getAsInt(),b.get(1).getAsInt(),b.get(2).getAsInt(),b.get(3).getAsInt(),b.get(4).getAsInt(),b.get(5).getAsInt());
        var bands=name.equals("fixture")?List.of(new TerrainLodRefinement.Band(32,1),new TerrainLodRefinement.Band(64,2),new TerrainLodRefinement.Band(192,4)):
                List.of(new TerrainLodRefinement.Band(384,1),new TerrainLodRefinement.Band(768,2),new TerrainLodRefinement.Band(1280,4),new TerrainLodRefinement.Band(1920,8));
        var near=new TerrainLodColumns.Bounds(Math.max(bounds.minX(),(int)camera.x()-160),Math.max(bounds.minY(),(int)camera.y()-160),Math.max(bounds.minZ(),(int)camera.z()-160),
                Math.min(bounds.maxXInclusive(),(int)camera.x()+159),Math.min(bounds.maxYInclusive(),(int)camera.y()+159),Math.min(bounds.maxZInclusive(),(int)camera.z()+159));
        // Identical material palette to the public DH adapter, resolved once on the server owner.
        var palette=new HashMap<Integer,TerrainMaterial>();for(var state:Block.BLOCK_STATE_REGISTRY){var material=material(state);if(material!=null)palette.put(Block.getId(state),material);}
        var input=new AbyssLodPipeline.Input(new AbyssLodPipeline.Target("headless-v14",1,identity,0,0,0,1),camera,near,bounds,bands,List.of(),
                (x,y,z)->{int id=source.stateIdForDerivedGeometry(Math.toIntExact(x),Math.toIntExact(y),Math.toIntExact(z));var material=palette.get(id);
                    if(material==null)throw new IllegalArgumentException("Unsupported benchmark state "+Block.stateById(id));return new TerrainSample.Known(material);},
                new AbyssLodPhysicalLighting(source,profile.fingerprint()),limits);
        out.add("bounds",JSON.toJsonTree(bounds));out.add("bands",JSON.toJsonTree(bands));out.add("limits",JSON.toJsonTree(input.limits()));
        double firstFine=-1,firstFineLedger=-1,firstAny=-1;int publications=0;AbyssLodPipeline.Proposal last=null;
        JsonObject initialOracle=null;
        var samples=new JsonArray();String outcome="TIMEOUT";
        try(var recording=new Recording();var pipeline=new AbyssLodPipeline(true,!Boolean.getBoolean("mia.bench.lodReference"))){
            var transport=new AbyssLodTransport<AbyssLodPipeline.Target,AbyssLodPipeline.Proposal>();
            recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(20));recording.start();
            var cpu=new CpuWindow();
            long started=System.nanoTime(),deadline=started+TimeUnit.SECONDS.toNanos(Long.getLong("mia.bench.lodSeconds",1800L));
            long nextSample=started,nextStatus=started,peakHeap=0;pipeline.request(input);
            while(System.nanoTime()<deadline){
                var proposal=pipeline.poll();if(proposal.isPresent()){
                    var p=proposal.get();if(firstAny<0)firstAny=(System.nanoTime()-started)/1e6;
                    if(firstFineLedger<0&&!p.fine().isEmpty())firstFineLedger=(System.nanoTime()-started)/1e6;
                    if(firstFine<0&&p.surfacePages().faces()>0&&p.fine().stream().anyMatch(g->!g.raw().boxes().isEmpty()))firstFine=(System.nanoTime()-started)/1e6;
                    if(initialOracle==null&&p.stage()==AbyssLodPipeline.Stage.NEAR_FINE)initialOracle=oracle(p);
                    transport.request(p.target(),p.sequence());transport.stage(p.target(),p.sequence(),p);transport.uploaded(p.sequence(),p);transport.promote();
                    if(transport.drawn(p.target(),p.sequence(),p,AbyssLodTransport.DrawPass.HIDDEN_UPLOAD,p.surfacePages().faces())
                            ||transport.drawn(p.target(),p.sequence(),p,AbyssLodTransport.DrawPass.SHADOW,p.surfacePages().faces()))throw new IllegalStateException("Non-world CPU transport admitted");
                    if(!transport.drawn(p.target(),p.sequence(),p,AbyssLodTransport.DrawPass.WORLD,p.surfacePages().faces())
                            ||!pipeline.acknowledge(p,true))throw new IllegalStateException("Current CPU proposal ACK refused");last=p;publications++;
                }
                long now=System.nanoTime();if(now>=nextSample){var s=JSON.toJsonTree(pipeline.status()).getAsJsonObject();s.addProperty("elapsedMillis",(now-started)/1e6);
                    s.addProperty("firstFinePublicationMillis",firstFine);s.addProperty("publications",publications);
                    s.add("sourceDiagnostics",sourceDiagnostics(source));
                    s.add("cpu",cpu.snapshot());
                    s.addProperty("heapUsedBytes",java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());samples.add(s);nextSample=now+TimeUnit.SECONDS.toNanos(5);
                    Files.writeString(dir.resolve("progress.json"),JSON.toJson(s));
                    if(Files.exists(root.resolve("stop"))||Files.exists(dir.resolve("stop"))){outcome="STOPPED";break;}}
                if(now>=nextStatus){var status=pipeline.status();nextStatus=now+TimeUnit.MILLISECONDS.toNanos(100);
                    peakHeap=Math.max(peakHeap,java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                    if(!status.get("error").equals("")){outcome="ERROR";break;}
                    if(Boolean.TRUE.equals(status.get("completed"))){outcome="COMPLETED";break;}}
                Thread.sleep(2);
            }
            out.addProperty("elapsedMillis",(System.nanoTime()-started)/1e6);out.addProperty("outcome",outcome);out.addProperty("completed",outcome.equals("COMPLETED"));
            out.addProperty("firstFinePublicationMillis",firstFine);out.addProperty("firstPublicationMillis",firstAny);out.addProperty("publications",publications);
            out.addProperty("firstFineLedgerPublicationMillis",firstFineLedger);out.addProperty("statusPollMillis",100);
            out.addProperty("firstFineDefinition","positive fine grid and positive surface proposal; not a native draw or fully refined quality milestone");
            out.addProperty("peakHeapUsedBytes",peakHeap);out.addProperty("heapSamplingMillis",100);
            out.add("status",JSON.toJsonTree(pipeline.status()));
            out.add("sourceDiagnostics",sourceDiagnostics(source));
            out.add("cpu",cpu.snapshot());
            out.add("plannedWork",plannedWork(pipeline));
            long jobs=((Number)pipeline.status().getOrDefault("preparedFineJobs",0)).longValue();out.addProperty("fineJobsPerSecond",jobs/(out.get("elapsedMillis").getAsDouble()/1000));
            recording.stop();recording.dump(dir.resolve("pipeline.jfr"));out.add("phases",phases(dir.resolve("pipeline.jfr")));
            if(last!=null)out.add("oracle",oracle(last));
            if(initialOracle!=null)out.add("initialFineOracle",initialOracle);
            // Outside the measured wall interval and stopped JFR. Keep the pipeline and
            // last immutable proposal alive while measuring the actual retained heap.
            var heap=new JsonObject();var gcBeans=java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
            long gcBefore=gcBeans.stream().mapToLong(bean->Math.max(0,bean.getCollectionCount())).sum();long gcStarted=System.nanoTime();
            System.gc();
            heap.addProperty("requestedGcMillis",(System.nanoTime()-gcStarted)/1e6);
            heap.addProperty("collectionsDuringRequest",gcBeans.stream().mapToLong(bean->Math.max(0,bean.getCollectionCount())).sum()-gcBefore);
            heap.addProperty("usedBytes",java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            heap.addProperty("scope","whole GameTest JVM with live pipeline/proposal; requested GC after timing/JFR; not an isolated LOD allocation count");out.add("retainedHeap",heap);
            String reference=System.getProperty("mia.bench.lodReferenceFixture","");
            String referenceRun=System.getProperty("mia.bench.lodReferenceRun","");
            if(!referenceRun.isBlank())out.add("referenceComparison",compareFixture(out,Path.of(referenceRun).resolve(name).resolve("result.json"),false));
            else if(name.equals("fixture")&&!reference.isBlank())out.add("referenceComparison",compareFixture(out,Path.of(reference)));
            Files.writeString(dir.resolve("samples.json"),JSON.toJson(samples));Files.writeString(dir.resolve("result.json"),JSON.toJson(out));
            System.out.println("MIA_LOD_BENCH "+name+" "+outcome+" ms="+out.get("elapsedMillis")+" fine/s="+out.get("fineJobsPerSecond"));
            if(System.getProperty("mia.bench.lodCameras","").contains("costs")&&last!=null){
                var measuredOwners=last.surfacePages().pages().stream().flatMap(page->page.prepared().owners().stream()).map(SurfaceQuadPlan.Owner::box).toList();
                var regions=actualJobs(pipeline);pipeline.close();
                var costs=LodSpeedCostProbe.measure(()->{
                    var freshProfile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),biomes);
                    var freshSource=new GeneratedSkySource(freshProfile,Map.of());
                    return new AbyssLodPipeline.Input(input.target(),camera,near,bounds,bands,List.of(),
                        (x,y,z)->{var material=palette.get(freshSource.stateIdForDerivedGeometry(Math.toIntExact(x),Math.toIntExact(y),Math.toIntExact(z)));
                            if(material==null)throw new IllegalArgumentException("Unsupported cost-probe state");return new TerrainSample.Known(material);},
                        new AbyssLodPhysicalLighting(freshSource),input.limits());
                },measuredOwners,regions);
                Files.writeString(dir.resolve("costs.json"),JSON.toJson(costs));
            }
        }finally{if(prior==null)System.clearProperty("mia.dh.previewCacheDirectory");else System.setProperty("mia.dh.previewCacheDirectory",prior);}
        return out;
    }
    /** Low-frequency lifetime CPU deltas; terminated thread samples remain in the total. */
    private static final class CpuWindow {
        private final java.lang.management.ThreadMXBean threads=java.lang.management.ManagementFactory.getThreadMXBean();
        private final Map<Long,Long> initial=new HashMap<>(),observed=new HashMap<>();
        private final Map<Long,String> names=new HashMap<>();
        private final long processStart=processCpu();
        CpuWindow(){read();initial.putAll(observed);}
        private static long processCpu(){var os=java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return os instanceof com.sun.management.OperatingSystemMXBean bean?bean.getProcessCpuTime():-1;}
        private void read(){if(!threads.isThreadCpuTimeSupported()||!threads.isThreadCpuTimeEnabled())return;
            for(long id:threads.getAllThreadIds()){var info=threads.getThreadInfo(id);
                if(info==null||!info.getThreadName().startsWith("mia-abyss-lod"))continue;
                long nanos=threads.getThreadCpuTime(id);if(nanos<0)continue;
                observed.merge(id,nanos,Math::max);names.put(id,info.getThreadName());}}
        JsonObject snapshot(){read();var result=new JsonObject();var byThread=new TreeMap<String,Long>();long total=0;
            for(var entry:observed.entrySet()){long delta=entry.getValue()-initial.getOrDefault(entry.getKey(),0L);total+=delta;
                byThread.merge(names.get(entry.getKey()),delta,Long::sum);}
            result.addProperty("lodCpuNanos",total);result.add("lodThreadCpuNanos",JSON.toJsonTree(byThread));
            long process=processCpu();result.addProperty("processCpuNanos",process<0||processStart<0?-1:process-processStart);
            result.addProperty("supported",threads.isThreadCpuTimeSupported()&&threads.isThreadCpuTimeEnabled());
            result.addProperty("scope","ThreadMXBean cumulative LOD deltas; process includes GC/JIT/server; 5s sampling may omit final CPU of a terminated thread; Windows CPU timer is quantized");
            return result;}
    }
    /** Public counters only: no physical-light code change, reflection or owner/voxel scan. */
    private static JsonObject sourceDiagnostics(GeneratedSkySource source){
        var out=new JsonObject();
        out.addProperty("scope","Approximate live public source counters; independent reads, no worker barrier. Thread-local memo counters may lag and terminated weakly referenced caches may disappear. Private physical exposure and quantized-probe occupancy/evictions are not exposed.");
        out.addProperty("opacityPagesEnabled",source.opacityPagesEnabled());
        out.addProperty("opacityPageCapacity",source.opacityPageCapacity());
        out.addProperty("maximumOpacityPageCapacity",dev.madeinabyss.kernel.light.PagedOpacityCache.MAX_CAPACITY);
        out.addProperty("opacityCacheHits",source.opacityCacheHits());
        out.addProperty("opacityCacheMisses",source.opacityCacheMisses());
        out.addProperty("opacityCacheEvictions",source.opacityCacheEvictions());
        out.addProperty("opacityCacheResidentBytes",source.opacityCacheResidentBytes());
        out.addProperty("sharedOpacityEnabled",source.sharedOpacityEnabled());
        out.addProperty("sharedOpacityHits",source.sharedOpacityHits());
        out.addProperty("sharedOpacityMisses",source.sharedOpacityMisses());
        out.addProperty("sharedOpacityEvictions",source.sharedOpacityEvictions());
        out.addProperty("sharedOpacityResidentBytes",source.sharedOpacityResidentBytes());
        out.addProperty("cachedSkyColumns",source.cachedSkyColumns());
        out.addProperty("maximumSkyColumns",GeneratedSkySource.MAX_SKY_COLUMNS);
        out.addProperty("skyColumnBuilds",source.skyColumnBuilds());
        out.addProperty("skyColumnEvictions",source.skyColumnEvictions());
        out.addProperty("cachedVisibilityProbes",source.cachedVisibilityProbes());
        out.addProperty("singleFlightEnabled",source.singleFlightEnabled());
        out.addProperty("probeFlightEvaluations",source.probeFlightEvaluations());
        out.addProperty("probeFlightJoins",source.probeFlightJoins());
        out.addProperty("stateCacheHits",source.stateCacheHits());
        out.addProperty("stateCacheMisses",source.stateCacheMisses());
        out.addProperty("residentStateCaches",source.residentStateCaches());
        out.addProperty("denseOpacityEnabled",source.denseOpacityEnabled());
        out.addProperty("denseOpacityHits",source.denseOpacityHits());
        out.addProperty("denseOpacityResidentBytes",source.denseOpacityResidentBytes());
        out.addProperty("airCertificateEnabled",source.airCertificateEnabled());
        out.addProperty("airCertificateHits",source.airCertificateHits());
        out.addProperty("islandAirCertificateEnabled",source.islandAirCertificateEnabled());
        out.addProperty("islandAirCertificateHits",source.islandAirCertificateHits());
        out.addProperty("deepAirCertificateEnabled",source.deepAirCertificateEnabled());
        out.addProperty("deepAirCertificateHits",source.deepAirCertificateHits());
        out.addProperty("deepAirCellCertificateEnabled",source.deepAirCellCertificateEnabled());
        out.addProperty("deepAirCellCertificateHits",source.deepAirCellCertificateHits());
        out.addProperty("deepAirCellCertificateResidentBytes",source.deepAirCellCertificateResidentBytes());
        out.addProperty("deepAirCellCertificateAdmittedCells",source.deepAirCellCertificateAdmittedCells());
        out.addProperty("derivedGeometryAirCertificateEnabled",source.derivedGeometryAirCertificateEnabled());
        out.addProperty("derivedGeometryAirCertificateHits",source.derivedGeometryAirCertificateHits());
        out.addProperty("caveOpacityProjectionMode",source.caveOpacityProjectionMode());
        out.addProperty("caveOpacityProjectionCoreSamples",source.caveOpacityProjectionCoreSamples());
        out.addProperty("caveOpacityProjectionHits",source.caveOpacityProjectionHits());
        out.addProperty("caveOpacityProjectionContinued",source.caveOpacityProjectionContinued());
        out.addProperty("caveOpacityProjectionRefusals",source.caveOpacityProjectionRefusals());
        out.addProperty("lightCacheStatus",source.lightCacheStatus());
        out.addProperty("lightCacheWarning",source.lightCacheWarning());
        return out;
    }
    /** Compare captured deterministic artifacts only; timing and pipeline bytecode may legitimately differ. */
    static JsonObject compareFixture(JsonObject actual,Path referencePath) {return compareFixture(actual,referencePath,true);}
    /** Restart may publish more initial pages immediately; the terminal image must match exactly. */
    static JsonObject compareFixture(JsonObject actual,Path referencePath,boolean requireInitial) {
        var result=new JsonObject();result.addProperty("referencePath",referencePath.toAbsolutePath().toString());
        result.addProperty("accepted",false);result.addProperty("status","INCOMPARABLE");
        try {
            byte[] bytes=Files.readAllBytes(referencePath);result.addProperty("referenceFileSha256",sha(bytes));
            var reference=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            var fields=new JsonObject();boolean sameInput=true;
            for(String key:List.of("camera","pose","manifestSha256","fingerprint","physicalLightCode","bounds","bands","limits")){
                boolean equal=reference.has(key)&&actual.has(key)&&reference.get(key).equals(actual.get(key));
                fields.addProperty(key,equal);sameInput&=equal;
            }
            result.add("inputMatches",fields);result.addProperty("sameInput",sameInput);
            var initial=compareOracle(reference,"initialFineOracle",actual);var terminal=compareOracle(reference,"oracle",actual);
            result.add("initialFine",initial);result.add("terminal",terminal);
            boolean initialComparable=sameInput&&initial.get("available").getAsBoolean();
            boolean terminalComparable=sameInput&&terminal.get("available").getAsBoolean()
                    &&isTrue(reference,"completed")&&isTrue(actual,"completed")
                    &&isTrue(reference.getAsJsonObject("oracle"),"terminal")&&isTrue(actual.getAsJsonObject("oracle"),"terminal");
            boolean initialEqual=initialComparable&&initial.get("equal").getAsBoolean();
            boolean terminalEqual=terminalComparable&&terminal.get("equal").getAsBoolean();
            result.addProperty("initialFineComparable",initialComparable);result.addProperty("terminalComparable",terminalComparable);
            result.addProperty("initialFineEqual",initialEqual);result.addProperty("terminalEqual",terminalEqual);
            boolean mismatch=requireInitial&&initialComparable&&!initialEqual||terminalComparable&&!terminalEqual;
            boolean accepted=terminalEqual&&(!requireInitial||initialEqual);
            result.addProperty("initialRequired",requireInitial);result.addProperty("actualMismatch",mismatch);result.addProperty("accepted",accepted);
            result.addProperty("status",mismatch?"MISMATCH":accepted?"PASS":"INCOMPARABLE");
        }catch(Exception failure){result.addProperty("error",failure.toString());}
        return result;
    }
    private static JsonObject compareOracle(JsonObject reference,String key,JsonObject actual) {
        var result=new JsonObject();boolean available=reference.has(key)&&actual.has(key)
                &&reference.get(key).isJsonObject()&&actual.get(key).isJsonObject(),equal=available;
        for(String digest:List.of("pagesSha256","ownersSha256","orderedRenderSha256","fineGridsSha256")){
            boolean present=available&&reference.getAsJsonObject(key).has(digest)&&actual.getAsJsonObject(key).has(digest);
            boolean matches=present&&reference.getAsJsonObject(key).get(digest).equals(actual.getAsJsonObject(key).get(digest));
            result.addProperty(digest,matches);equal&=matches;available&=present;
        }
        result.addProperty("available",available);result.addProperty("equal",equal);return result;
    }
    private static boolean isTrue(JsonObject value,String key){return value!=null&&value.has(key)&&value.get(key).isJsonPrimitive()&&value.get(key).getAsBoolean();}
    /** Read-only diagnostic of the actual immutable plan, not a second planner implementation. */
    private static JsonObject plannedWork(AbyssLodPipeline pipeline)throws Exception {
        var jobs=actualJobs(pipeline);
        var finest=new HashMap<TerrainLodColumns.Bounds,TerrainLodRefinement.Region>();var byStep=new TreeMap<Integer,Long>();
        for(var job:jobs){byStep.merge(job.horizontalStep(),job.sampledVoxels(),Long::sum);finest.merge(job.bounds(),job,(a,b)->a.horizontalStep()<=b.horizontalStep()?a:b);}
        var out=new JsonObject();out.add("samplesByStep",JSON.toJsonTree(byStep));out.addProperty("minimumSamplesEvenWithPerfectSameTileCoarserReuse",finest.values().stream().mapToLong(TerrainLodRefinement.Region::sampledVoxels).sum());
        out.addProperty("scope","Plan source grids; does not subtract previously cached/native data, initial index or FAR. Cold benchmark supplies no native density.");return out;
    }
    private static List<TerrainLodRefinement.Region> actualJobs(AbyssLodPipeline pipeline)throws Exception {
        var field=AbyssLodPipeline.class.getDeclaredField("context");field.setAccessible(true);var context=field.get(pipeline);
        var jobsField=context.getClass().getDeclaredField("jobs");jobsField.setAccessible(true);
        @SuppressWarnings("unchecked") var jobs=(List<TerrainLodRefinement.Region>)jobsField.get(context);
        return jobs;
    }
    /** Canonical face/owner/light/provenance digest independent of list/owner-id order. */
    static JsonObject oracle(AbyssLodPipeline.Proposal proposal)throws Exception {
        var out=new JsonObject();var pages=new TreeMap<String,String>();var orderedRender=new LodSpeedCanonicalDigest.Stream();
        var spillRoot=Path.of(System.getProperty("mia.bench.lodOutput",System.getProperty("java.io.tmpdir")));
        try(var owners=new LodSpeedCanonicalDigest.SortedUnique(spillRoot,50_000)) {
        if(proposal.surfacePages()!=null)for(var page:proposal.surfacePages().pages()){
            var p=page.prepared();var records=new ArrayList<String>();
            orderedRender.add("PAGE|"+page.key()+"|"+p.chunkBounds());
            for(var owner:p.owners())owners.add(owner.toString());
            for(int i=0;i<p.faces().size();i++){var f=p.faces().get(i);var provenance=new TreeSet<String>();for(int id:p.contributors().get(i))provenance.add(p.owners().get(id).toString());
                String physical=f.axis()+"|"+f.positive()+"|"+f.plane()+"|"+f.u0()+"|"+f.v0()+"|"+f.u1()+"|"+f.v1()+"|"+provenance;
                records.add(physical);orderedRender.add(physical+"|renderOwner="+p.owners().get(f.owner()));}
            Collections.sort(records);pages.put(page.key().toString(),digest(records));
        }
        out.addProperty("algorithm","SHA256 canonical sorted page rectangles with whole BOX contributors, material, ARGB and exact SKY/BLOCK; separate owner digest");
        out.addProperty("terminal",proposal.requestedComplete());out.addProperty("pagesSha256",digest(pages.entrySet().stream().map(Object::toString).toList()));
        var ownerDigest=owners.finish();
        out.addProperty("ownersSha256",ownerDigest.sha256());out.addProperty("ownerCount",ownerDigest.count());out.add("pages",JSON.toJsonTree(pages));
        out.addProperty("orderedRenderSha256",orderedRender.finish());
        out.addProperty("orderedRenderDefinition","page/face order and chunk bounds preserved, physical render owner and exact light included; transient owner IDs ignored");
        var grids=new TreeSet<String>();for(var g:proposal.fine())grids.add(g.bounds()+"|"+g.step()+"|"+digest(g.raw().boxes().stream().map(Object::toString).sorted().toList()));
        out.addProperty("fineGridsSha256",digest(grids));return out;
        }
    }
    private static String digest(Collection<String> records)throws Exception {var md=MessageDigest.getInstance("SHA-256");for(var line:records){md.update(line.getBytes(StandardCharsets.UTF_8));md.update((byte)'\n');}return HexFormat.of().formatHex(md.digest());}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static JsonObject phases(Path path)throws Exception {
        var counts=new TreeMap<String,Long>();try(var file=new RecordingFile(path)){while(file.hasMoreEvents()){var e=file.readEvent();if(!e.getEventType().getName().equals("jdk.ExecutionSample"))continue;
            String thread=e.getThread("sampledThread").getJavaName();if(!thread.startsWith("mia-abyss-lod"))continue;
            var stack=e.getStackTrace();String phase="other";if(stack!=null)for(var frame:stack.getFrames()){
                String c=frame.getMethod().getType().getName(),m=frame.getMethod().getName();
                if(c.endsWith("AbyssLodPhysicalLighting")){phase="exactLight";break;}
                if(c.endsWith("IncrementalSurfacePages")&&m.equals("preparePartial")){phase="publication";break;}
                if(c.endsWith("IncrementalSurfacePages")&&(m.equals("update")||m.equals("extract"))){phase="pageGeometry";break;}
                if(c.endsWith("SurfaceQuadPlan")&&m.equals("prepare")){phase="pagePreparation";break;}
                if(c.endsWith("AbyssLodPipeline")&&(m.equals("publishedLight")||m.equals("publishedCoverage"))){phase="publicationSummary";break;}
                if(c.endsWith("AbyssLodPipeline")&&(m.equals("angularProgress")||m.equals("angularCounts"))){phase="angularProgress";break;}
                if(c.endsWith("AbyssLodPipeline")&&(m.equals("buildRaw")||m.equals("acquireFar"))){phase="sampling";break;}
                if(c.endsWith("AbyssLodScalarArchive")){phase="scalarArchive";break;}
            }counts.merge(thread+"/"+phase,1L,Long::sum);}}
        var result=JSON.toJsonTree(counts).getAsJsonObject();result.addProperty("interpretation","JFR execution sample counts, CPU attribution only; not wall-time percentages");return result;
    }
    static TerrainMaterial material(BlockState state){
        return LodBlockStateMaterials.findMaterial(state)==null?null:LodBlockStateMaterials.material(state);
    }
}
