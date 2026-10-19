package dev.madeinabyss.forge.probe.client.compat.lightgpu;

import dev.madeinabyss.forge.worldgen.*;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import dev.madeinabyss.kernel.volume.*;
import dev.madeinabyss.kernel.light.gpu.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/** Client-only transport. No GL queries/readback or source sampling on the render tick. */
public final class ClientGpuLight {
    private static volatile GpuLightWorker worker;
    private static final List<GpuLightWorker> retiring=new ArrayList<>();
    private static final AtomicReference<GpuSnapshotMetadata> metadata=new AtomicReference<>();
    private static final Map<GeneratedSkySource,GpuAuthorityGuard> sourceGuards=Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong fallbackOwners=new AtomicLong(),refusedOwners=new AtomicLong();
    private static final java.util.concurrent.atomic.LongAdder authorityCalls=new java.util.concurrent.atomic.LongAdder(),authorityNanos=new java.util.concurrent.atomic.LongAdder(),generationHashes=new java.util.concurrent.atomic.LongAdder(),generationHashNanos=new java.util.concurrent.atomic.LongAdder(),physicalHashes=new java.util.concurrent.atomic.LongAdder(),physicalHashNanos=new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder authorityCaptures=new java.util.concurrent.atomic.LongAdder(),authorityCaptureNanos=new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder ownerPackNanos=new java.util.concurrent.atomic.LongAdder(),ownerPackOwners=new java.util.concurrent.atomic.LongAdder(),resultEmissionNanos=new java.util.concurrent.atomic.LongAdder(),futureWaitNanos=new java.util.concurrent.atomic.LongAdder();
    private static volatile String error="",lastFallback="";
    private static boolean attempted;
    private static long renderFrames,renderNanos,maxRenderNanos,lastRenderNanos;
    private static double contextCreateMillis;
    private static final String RESOURCE_ROOT="/assets/madeinabyss/shaders/compute/exact/";
    private ClientGpuLight(){}
    public static boolean enabled(){return Boolean.getBoolean("mia.light.gpu");}
    public static boolean ready(){var current=worker;return enabled()&&current!=null&&metadata.get()!=null&&current.ready();}
    public static boolean cpuFallback(){return Boolean.parseBoolean(System.getProperty("mia.light.gpu.cpuFallback","true"));}
    /** Main-thread prewarm at setup/menu when an explicit checked snapshot is provided. */
    public static void prepare(Minecraft client){
        if(!enabled()||attempted)return;attempted=true;error="";metadata.set(null);
        String path=System.getProperty("mia.light.gpu.snapshot","");
        if(path.isBlank()){error="Missing -Dmia.light.gpu.snapshot=<checked B1 cache>; CPU fallback only";return;}
        long start=System.nanoTime();
        try{
            startWorker(client.getWindow().getWindow(),Path.of(path));
        }catch(RuntimeException|LinkageError failed){error=failed.toString();}
        finally{contextCreateMillis=(System.nanoTime()-start)/1e6;}
    }
    /** Same transport entry point for the hidden Forge DATA integration harness. */
    public static void prepareContext(long sharedContext,Path snapshot){if(worker!=null||attempted)throw new IllegalStateException("GPU worker already prepared");attempted=true;startWorker(sharedContext,snapshot);}
    private static void startWorker(long sharedContext,Path snapshot){
        double budget=Double.parseDouble(System.getProperty("mia.light.gpu.budgetMillis","1.5"));
        worker=GpuLightWorker.open(sharedContext,snapshot,ClientGpuLight::shader,budget,()->{
            try{metadata.set(GpuSnapshotMetadata.load(snapshot));}catch(IOException failed){throw new IllegalStateException("GPU snapshot refused",failed);}
        });
    }
    private static String shader(String name)throws IOException{
        try(var in=ClientGpuLight.class.getResourceAsStream(RESOURCE_ROOT+name)){
            if(in==null)throw new IOException("Missing exact compute resource "+name);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    /** Worker-safe cached/string-only checks. Real edits invalidate the pristine G1 cache. */
    public static CompletableFuture<int[]> solve(GeneratedSkySource source,String fingerprint,int[] owners,Runnable checkpoint){
        var current=worker;if(!enabled()||current==null)return CompletableFuture.failedFuture(new IllegalStateException("GPU light not prepared: "+error));
        var checked=authorityCheckpoint(source,fingerprint,metadata::get,checkpoint);
        return current.solve(owners,()->{if(current!=worker)throw new java.util.concurrent.CancellationException("GPU worker retired");checked.run();});
    }
    /** Also exercised by the Forge DATA harness, with the same actual source/class loader. */
    public static Runnable authorityCheckpoint(GeneratedSkySource source,String fingerprint,Supplier<GpuSnapshotMetadata> metadata,Runnable checkpoint){
        var guard=sourceGuards.computeIfAbsent(source,s->{long start=System.nanoTime();authorityCaptures.increment();try{
            String generation=GenerationIdentity.VALUE,physical=physicalIdentity(s),identity=GpuSourceAuthority.full(s,fingerprint,generation,physical);
            return new GpuAuthorityGuard(fingerprint,()->generation,()->physical,()->identity,()->Code.PRODUCER,()->Code.PACKED);
        }finally{authorityCaptureNanos.add(System.nanoTime()-start);}});
        if(!guard.fingerprint().equals(fingerprint))throw new IllegalStateException("Same immutable source used with a foreign world fingerprint");
        return ()->{long start=System.nanoTime();authorityCalls.increment();try{var authority=metadata.get();if(authority==null)throw new IllegalStateException("GPU snapshot retired");guard.require(authority,()->{checkpoint.run();source.requirePhysicalAuthority();});}finally{authorityNanos.add(System.nanoTime()-start);}};
    }
    private static String generationIdentity(){long start=System.nanoTime();generationHashes.increment();try{return LivingAbyssGeneration.algorithmSha256();}finally{generationHashNanos.add(System.nanoTime()-start);}}
    private static String physicalIdentity(GeneratedSkySource source){long start=System.nanoTime();physicalHashes.increment();try{return source.physicalSemanticsIdentity();}finally{physicalHashNanos.add(System.nanoTime()-start);}}
    private static final class GenerationIdentity {static final String VALUE=generationIdentity();}
    public static void noteOwnerPack(long nanos,int owners){ownerPackNanos.add(nanos);ownerPackOwners.add(owners);}
    public static void noteFutureWait(long nanos){futureWaitNanos.add(nanos);}
    public static void noteResultEmission(long nanos){resultEmissionNanos.add(nanos);}
    public static void fallback(int owners,Throwable failure){
        lastFallback=failure.toString();
        if(!cpuFallback()){refusedOwners.addAndGet(owners);throw new IllegalStateException("GPU exact refused and mia.light.gpu.cpuFallback=false: "+failure,failure);}
        fallbackOwners.addAndGet(owners);
    }
    /** Exactly one call at RenderTick START. Includes only notification and nonblocking retired-context cleanup. */
    public static void frame(){long start=System.nanoTime();var current=worker;if(current!=null)current.frame();
        for(var iterator=retiring.iterator();iterator.hasNext();)if(iterator.next().pollClosed())iterator.remove();
        lastRenderNanos=System.nanoTime()-start;renderNanos+=lastRenderNanos;maxRenderNanos=Math.max(maxRenderNanos,lastRenderNanos);renderFrames++;
    }
    public static Map<String,Object> status(){var out=new LinkedHashMap<String,Object>();var current=worker;if(current!=null)out.putAll(current.status());else{out.put("state",enabled()?"UNAVAILABLE":"DISABLED");out.put("dispatches",0);out.put("queue",0);out.put("queuedOwners",0);out.put("ownersPerSecond",0);out.put("vramBytes",0);}
        out.put("enabled",enabled());out.put("cpuFallback",cpuFallback());out.put("fallbackOwners",fallbackOwners.get());out.put("refusedOwners",refusedOwners.get());out.put("lastFallback",lastFallback);out.put("bootstrapError",error);
        out.put("renderThreadMillisPerFrame",lastRenderNanos/1e6);out.put("renderThreadMeanMillisPerFrame",renderFrames==0?0:renderNanos/(double)renderFrames/1e6);out.put("renderThreadMaxMillisPerFrame",maxRenderNanos/1e6);out.put("contextCreateMillis",contextCreateMillis);out.put("readyAuthority",metadata.get()!=null);
        out.put("authorityCheckCalls",authorityCalls.sum());out.put("authorityCheckMillis",authorityNanos.sum()/1e6);out.put("generationIdentityHashes",generationHashes.sum());out.put("generationIdentityHashMillis",generationHashNanos.sum()/1e6);out.put("physicalIdentityHashes",physicalHashes.sum());out.put("physicalIdentityHashMillis",physicalHashNanos.sum()/1e6);out.put("authorityCaptures",authorityCaptures.sum());out.put("authorityCaptureMillis",authorityCaptureNanos.sum()/1e6);out.put("cpuOwnerPackMillis",ownerPackNanos.sum()/1e6);out.put("cpuOwnerPackOwners",ownerPackOwners.sum());out.put("cpuResultEmissionMillis",resultEmissionNanos.sum()/1e6);out.put("futureWaitMillis",futureWaitNanos.sum()/1e6);return Map.copyOf(out);
    }
    /** Never join a GL worker on the render thread. Its context is destroyed by a later frame. */
    public static void end(){var old=worker;worker=null;metadata.set(null);sourceGuards.clear();attempted=false;if(old!=null){old.close();retiring.add(old);}}
    public static void shutdown(){var current=worker;if(current!=null)current.close();}
    public static String producerCodeIdentity(){return Code.PRODUCER;}
    public static String packedCodeIdentity(){return Code.PACKED;}
    /** Same cache identity with GPU off/on; a shader/math update must never reuse a stale exact seal. */
    public static String physicalCodeIdentity(){return Code.PHYSICAL;}
    private static final class Code {
        static final String PRODUCER=TerrainLodDiskCache.codeIdentity(ReachableOpacityVolume.class,VoxelBrick.class,VoxelVolumeArchive.class,GeneratedSkySource.class,GeneratedVoxelVolume.class,Generated155AirProof.class,B1AirBounds.class);
        static final String PACKED=TerrainLodDiskCache.codeIdentity(PackedLightVolume.class,PackedHeightHalo.class,ClosedLightScene.class);
        static final String PHYSICAL=physical();
        private static String physical(){try{
            var digest=MessageDigest.getInstance("SHA-256");digest.update(TerrainLodDiskCache.codeIdentity(GpuOwnerSkyKernel.class).getBytes(StandardCharsets.UTF_8));
            for(String name:new String[]{"common.glsl","direct.comp","prepare.comp","rays.comp","reduce.comp","prefix.comp","resolve.comp"}){digest.update(name.getBytes(StandardCharsets.UTF_8));digest.update(shader(name).getBytes(StandardCharsets.UTF_8));}
            return HexFormat.of().formatHex(digest.digest());
        }catch(IOException|NoSuchAlgorithmException failed){throw new IllegalStateException("Cannot identify canonical GPU exact code",failed);}}
    }
}
