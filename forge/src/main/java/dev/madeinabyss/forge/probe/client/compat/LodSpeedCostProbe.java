package dev.madeinabyss.forge.probe.client.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.lod.TerrainLodRefinement;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/** Opt-in diagnostic only. Evaluates unchanged source/physical light without a client or pipeline scheduling. */
public final class LodSpeedCostProbe {
    private static final int MAX_OWNERS=4096,REGIONS_PER_STEP=64;
    private static final long MAX_SOURCE_SAMPLES=1_048_576;
    private static final Comparator<TerrainLodColumns.Box> BOX_ORDER=Comparator
            .comparingLong(TerrainLodColumns.Box::minX).thenComparingLong(TerrainLodColumns.Box::minY)
            .thenComparingLong(TerrainLodColumns.Box::minZ).thenComparingLong(TerrainLodColumns.Box::maxXExclusive)
            .thenComparingLong(TerrainLodColumns.Box::maxYExclusive).thenComparingLong(TerrainLodColumns.Box::maxZExclusive)
            .thenComparing(box->box.material().name());
    private static final Comparator<TerrainLodRefinement.Region> REGION_ORDER=Comparator
            .comparingInt(TerrainLodRefinement.Region::horizontalStep)
            .thenComparingInt(region->region.bounds().minX()).thenComparingInt(region->region.bounds().minY())
            .thenComparingInt(region->region.bounds().minZ()).thenComparingInt(region->region.bounds().maxXInclusive())
            .thenComparingInt(region->region.bounds().maxYInclusive()).thenComparingInt(region->region.bounds().maxZInclusive());
    private LodSpeedCostProbe() {}

    /** The supplier must build a fresh source and lighting instance, with the same immutable world identity, per call. */
    public static JsonObject measure(Supplier<AbyssLodPipeline.Input> freshInputs,List<TerrainLodColumns.Box> owners,
                                     List<TerrainLodRefinement.Region> regions) {
        Objects.requireNonNull(freshInputs);Objects.requireNonNull(owners);Objects.requireNonNull(regions);
        var selectedOwners=selectOwners(owners);var selectedRegions=selectRegions(regions);
        if(selectedOwners.isEmpty()||selectedRegions.isEmpty())throw new IllegalArgumentException("Cost probe needs positive owners and source regions within its sample budget");
        long sourceCalls=selectedRegions.stream().mapToLong(TerrainLodRefinement.Region::sampledVoxels).sum();
        var out=new JsonObject();out.addProperty("scope","Fixed real owners and whole source regions; original scalar/BOX equality; no publication, archive, GPU or client cost");
        out.addProperty("selection","Sorted unique owners spread uniformly over canonical order, with WATER retained if present; up to64 uniformly spread whole regions per step, round-robin steps under1048576 samples");
        out.addProperty("timing","Cold means fresh supplied source, not cold JVM/JIT; warm retains the same source and worker threads. Hashing, selection and source construction are outside task timers. CPU is summed task-thread CPU, not wall time.");
        out.addProperty("cpuTimingResolutionWarning","ThreadMXBean uses nanosecond units but CPU accounting can be coarse;15.625ms quanta were observed on this Windows/JDK. Short-pass CPU micros/call and averageBusyWorkers can be inaccurate, including values above the worker count. Raw data is not clamped; use wall timing for these short passes or repeat the warm workload for at least0.5-1s before interpreting CPU-derived rates. This fixture does not predict full-world cost.");
        out.addProperty("inputOwners",owners.size());out.addProperty("selectedOwners",selectedOwners.size());
        out.addProperty("selectedWaterOwners",selectedOwners.stream().filter(box->box.material()==TerrainMaterial.WATER).count());
        out.addProperty("inputRegions",regions.size());out.addProperty("selectedRegions",selectedRegions.size());out.addProperty("sourceSampleCalls",sourceCalls);
        var steps=new TreeMap<Integer,Long>();for(var region:selectedRegions)steps.merge(region.horizontalStep(),region.sampledVoxels(),Long::sum);
        var stepJson=new JsonObject();steps.forEach((step,count)->stepJson.addProperty(Integer.toString(step),count));out.add("sourceSamplesByStep",stepJson);
        out.addProperty("availableProcessors",Runtime.getRuntime().availableProcessors());
        var cpu=ManagementFactory.getThreadMXBean();boolean cpuEnabled=enableCpu(cpu);out.addProperty("threadCpuTimeEnabled",cpuEnabled);
        var sourceRuns=new JsonArray();var lightRuns=new JsonArray();String sourceOracle=null,lightOracle=null,sourceIdentity=null;
        // Detect an accidentally shared mutable source between variants, which would invalidate cold timings.
        var sources=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object,Boolean>());
        var lights=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object,Boolean>());
        for(int threads:new int[]{1,4,8}) {
            long setup=System.nanoTime();var sourceInput=Objects.requireNonNull(freshInputs.get());double sourceSetup=(System.nanoTime()-setup)/1e6;
            sourceIdentity=checkInput(sourceInput,sourceIdentity,sources,lights);
            var sourceVariant=new JsonObject();sourceVariant.addProperty("workers",threads);sourceVariant.addProperty("setupMillis",sourceSetup);
            try(var pool=new Pool(threads)) {
                for(String temperature:new String[]{"cold","warm"}) {
                    var timed=run(selectedRegions,pool.executor,threads,cpu,cpuEnabled,region->TerrainLodColumns.build(sourceInput.terrain(),region.bounds(),
                            new TerrainLodColumns.Parameters(region.horizontalStep(),region.sampledVoxels(),sourceInput.limits().maxBoxes())));
                    String digest=sourceDigest(selectedRegions,timed.values());sourceOracle=requireEqual("source/"+threads+"/"+temperature,sourceOracle,digest);
                    long actualCalls=0,boxes=0;for(var value:timed.values()){actualCalls=Math.addExact(actualCalls,value.sampledVoxels());boxes=Math.addExact(boxes,value.boxes().size());}
                    if(actualCalls!=sourceCalls)throw new IllegalStateException("Source probe sampled-cell accounting differs from the actual source plan");
                    var result=metrics(timed,sourceCalls,"sample");result.addProperty("regions",selectedRegions.size());result.addProperty("outputBoxes",boxes);result.addProperty("digest",digest);result.addProperty("exactEqualsSequentialCold",true);
                    sourceVariant.add(temperature,result);
                }
            }
            sourceRuns.add(sourceVariant);
            setup=System.nanoTime();var lightInput=Objects.requireNonNull(freshInputs.get());double lightSetup=(System.nanoTime()-setup)/1e6;
            sourceIdentity=checkInput(lightInput,sourceIdentity,sources,lights);
            var lightVariant=new JsonObject();lightVariant.addProperty("workers",threads);lightVariant.addProperty("setupMillis",lightSetup);lightVariant.addProperty("lightingClass",lightInput.lighting().getClass().getName());
            try(var pool=new Pool(threads)) {
                for(String temperature:new String[]{"cold","warm"}) {
                    var timed=run(selectedOwners,pool.executor,threads,cpu,cpuEnabled,lightInput.lighting()::light);
                    String digest=lightDigest(selectedOwners,timed.values());lightOracle=requireEqual("light/"+threads+"/"+temperature,lightOracle,digest);
                    var result=metrics(timed,selectedOwners.size(),"wholePhysicalOwner");result.addProperty("digest",digest);result.addProperty("exactEqualsSequentialCold",true);lightVariant.add(temperature,result);
                }
            }
            lightRuns.add(lightVariant);
        }
        out.addProperty("sourceIdentity",sourceIdentity);out.add("source",sourceRuns);out.add("original32Light",lightRuns);out.addProperty("allExactEqual",true);
        return out;
    }

    private static String checkInput(AbyssLodPipeline.Input input,String expected,java.util.Set<Object> sources,java.util.Set<Object> lights) {
        String identity=input.target().sourceIdentity();
        if(expected!=null&&!expected.equals(identity))throw new IllegalArgumentException("Cost probe supplier changed immutable source identity");
        if(!sources.add(input.terrain())||!lights.add(input.lighting()))throw new IllegalArgumentException("Cost probe supplier reused its source/lighting object; cold measurement refused");
        return identity;
    }
    private static List<TerrainLodColumns.Box> selectOwners(List<TerrainLodColumns.Box> input) {
        var unique=new ArrayList<>(new HashSet<>(input));unique.sort(BOX_ORDER);int size=Math.min(MAX_OWNERS,unique.size());
        var result=new ArrayList<TerrainLodColumns.Box>(size);
        for(int i=0;i<size;i++)result.add(unique.get((int)((long)i*unique.size()/size)));
        if(size>0&&result.stream().noneMatch(box->box.material()==TerrainMaterial.WATER))
            unique.stream().filter(box->box.material()==TerrainMaterial.WATER).findFirst().ifPresent(water->result.set(result.size()-1,water));
        result.sort(BOX_ORDER);return List.copyOf(result);
    }
    private static List<TerrainLodRefinement.Region> selectRegions(List<TerrainLodRefinement.Region> input) {
        var groups=new TreeMap<Integer,List<TerrainLodRefinement.Region>>();
        for(var region:new HashSet<>(input))if(region.sampledVoxels()<=MAX_SOURCE_SAMPLES)groups.computeIfAbsent(region.horizontalStep(),ignored->new ArrayList<>()).add(region);
        groups.values().forEach(group->group.sort(REGION_ORDER));var result=new ArrayList<TerrainLodRefinement.Region>();long remaining=MAX_SOURCE_SAMPLES;
        for(int round=0;round<REGIONS_PER_STEP;round++)for(var group:groups.values()) {
            int count=Math.min(REGIONS_PER_STEP,group.size());if(round>=count)continue;
            var region=group.get((int)((long)round*group.size()/count));long cost=region.sampledVoxels();if(cost<=remaining){result.add(region);remaining-=cost;}
        }
        result.sort(REGION_ORDER);return List.copyOf(result);
    }
    private record Timed<T>(List<T> values,double wallMillis,long cpuNanos) {}
    private static <T,R> Timed<R> run(List<T> input,ExecutorService executor,int threads,ThreadMXBean cpu,boolean cpuEnabled,Function<T,R> operation) {
        Object[] output=new Object[input.size()];var cursor=new AtomicInteger();var jobs=new ArrayList<Future<Long>>();long start=System.nanoTime();
        try {
            for(int worker=0;worker<threads;worker++)jobs.add(executor.submit((Callable<Long>)()->{
                long before=cpuEnabled?cpu.getCurrentThreadCpuTime():-1;
                while(true){int from=cursor.getAndAdd(16);if(from>=input.size())break;int end=Math.min(input.size(),from+16);
                    for(int i=from;i<end;i++){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Cost probe canceled");output[i]=Objects.requireNonNull(operation.apply(input.get(i)));}}
                long after=cpuEnabled?cpu.getCurrentThreadCpuTime():-1;return before>=0&&after>=before?after-before:-1L;
            }));
            long totalCpu=0,deadline=start+TimeUnit.MINUTES.toNanos(3);
            for(var job:jobs){long wait=deadline-System.nanoTime();if(wait<=0)throw new TimeoutException("Cost probe exceeded3min for one timed pass");long spent=job.get(wait,TimeUnit.NANOSECONDS);totalCpu=spent<0||totalCpu<0?-1:Math.addExact(totalCpu,spent);}
            double elapsed=(System.nanoTime()-start)/1e6;var values=new ArrayList<R>(output.length);
            for(Object value:output){@SuppressWarnings("unchecked") R cast=(R)value;values.add(cast);}return new Timed<>(List.copyOf(values),elapsed,totalCpu);
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Cost probe interrupted",interrupted);
        } catch(ExecutionException failed){var cause=failed.getCause();if(cause instanceof RuntimeException runtime)throw runtime;if(cause instanceof Error error)throw error;throw new IllegalStateException("Cost probe worker failed",cause);
        } catch(TimeoutException timeout){throw new IllegalStateException("Cost probe timed out",timeout);
        } finally {for(var job:jobs)if(!job.isDone())job.cancel(true);}
    }
    private static JsonObject metrics(Timed<?> timed,long calls,String unit) {
        var result=new JsonObject();result.addProperty("calls",calls);result.addProperty("unit",unit);result.addProperty("wallMillis",timed.wallMillis());
        result.addProperty("callsPerWallSecond",calls*1000.0/timed.wallMillis());result.addProperty("wallMicrosPerCall",timed.wallMillis()*1000/calls);
        if(timed.cpuNanos()>=0){result.addProperty("workerCpuMillis",timed.cpuNanos()/1e6);result.addProperty("cpuMicrosPerCall",timed.cpuNanos()/1000.0/calls);result.addProperty("averageBusyWorkers",timed.cpuNanos()/1e6/timed.wallMillis());}
        return result;
    }
    private static boolean enableCpu(ThreadMXBean cpu) {
        if(!cpu.isCurrentThreadCpuTimeSupported())return false;
        try{if(!cpu.isThreadCpuTimeEnabled())cpu.setThreadCpuTimeEnabled(true);return cpu.isThreadCpuTimeEnabled();}
        catch(UnsupportedOperationException|SecurityException unavailable){return false;}
    }
    private static String requireEqual(String name,String expected,String actual) {
        if(expected!=null&&!expected.equals(actual))throw new IllegalStateException("Cost probe changed exact output at "+name+": "+expected+" != "+actual);return actual;
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static void number(MessageDigest digest,long value){for(int shift=56;shift>=0;shift-=8)digest.update((byte)(value>>>shift));}
    private static void text(MessageDigest digest,String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);number(digest,bytes.length);digest.update(bytes);}
    private static void box(MessageDigest digest,TerrainLodColumns.Box box){number(digest,box.minX());number(digest,box.minY());number(digest,box.minZ());number(digest,box.maxXExclusive());number(digest,box.maxYExclusive());number(digest,box.maxZExclusive());text(digest,box.material().name());}
    private static String lightDigest(List<TerrainLodColumns.Box> owners,List<AbyssLodPipeline.Light> values) {
        var digest=digest();text(digest,"LOD-COST-EXACT-PHYSICAL-LIGHT-v1");number(digest,owners.size());
        for(int i=0;i<owners.size();i++){box(digest,owners.get(i));number(digest,values.get(i).sky());number(digest,values.get(i).block());}return HexFormat.of().formatHex(digest.digest());
    }
    private static String sourceDigest(List<TerrainLodRefinement.Region> regions,List<TerrainLodColumns.Result> values) {
        var digest=digest();text(digest,"LOD-COST-EXACT-ORDERED-SOURCE-v1");number(digest,regions.size());
        for(int i=0;i<regions.size();i++){var region=regions.get(i);var bounds=region.bounds();number(digest,bounds.minX());number(digest,bounds.minY());number(digest,bounds.minZ());number(digest,bounds.maxXInclusive());number(digest,bounds.maxYInclusive());number(digest,bounds.maxZInclusive());number(digest,region.horizontalStep());
            var result=values.get(i);number(digest,result.sampledVoxels());number(digest,result.boxes().size());for(var box:result.boxes())box(digest,box);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static final class Pool implements AutoCloseable {
        final ExecutorService executor;
        Pool(int threads){executor=Executors.newFixedThreadPool(threads,task->{var thread=new Thread(task,"mia-lod-cost-probe-"+threads);thread.setDaemon(true);return thread;});}
        @Override public void close(){executor.shutdownNow();try{if(!executor.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Cost probe workers did not stop");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    }
}
