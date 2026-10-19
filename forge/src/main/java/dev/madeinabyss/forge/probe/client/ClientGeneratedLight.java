package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.forge.probe.deep.GeneratedSceneNetwork;
import dev.madeinabyss.forge.probe.deep.GeneratedLightAccess;
import dev.madeinabyss.forge.probe.deep.GeneratedNativeWindow;
import dev.madeinabyss.forge.worldgen.GeneratedPreviewProfile;
import dev.madeinabyss.forge.worldgen.GeneratedSkySource;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.GeneratedObservationQueue;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.cube.NativeFirstViewPlan;
import dev.madeinabyss.kernel.light.VoxelSkyLight;
import dev.madeinabyss.kernel.light.DirectSkyColumnSeed;
import dev.madeinabyss.kernel.light.NativeLightWindowCache;
import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Client-owner geometry capture and one immutable, bounded background SKY/BLOCK solve. */
public final class ClientGeneratedLight {
    /** Native near-window work is explicit NORMAL: threads otherwise inherit the render thread's priority 10 (HIGHEST). */
    private static final int NATIVE_PRIORITY=Thread.NORM_PRIORITY;
    private static final int PHASE_WORKERS=4;
    private static final java.util.concurrent.ThreadPoolExecutor PHASE_POOL=new java.util.concurrent.ThreadPoolExecutor(PHASE_WORKERS,PHASE_WORKERS,0L,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(4),task->{var thread=new Thread(task,"mia-generated-light-phase");thread.setDaemon(true);thread.setPriority(NATIVE_PRIORITY);return thread;});
    private static final int MAX_CELLS=dev.madeinabyss.kernel.cube.NativeRenderDistance.MAX_CELLS;
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"mia-generated-light");thread.setDaemon(true);thread.setPriority(NATIVE_PRIORITY);return thread;});
    private static final int OBSERVATION_LIMIT=GeneratedSceneNetwork.MAX_CUBES,COMPARISON_BATCH=64;
    private static final ExecutorService COMPARISON_WORKER=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"mia-generated-observation-compare");thread.setDaemon(true);thread.setPriority(NATIVE_PRIORITY);return thread;});
    private static final GeneratedObservationQueue observations=new GeneratedObservationQueue(GeneratedSceneNetwork.MAX_CUBES,OBSERVATION_LIMIT,COMPARISON_BATCH);
    private static volatile long comparisonEpoch;
    private static ComparisonJob comparisonJob;
    private static long comparisonBatches,comparisonMillis,maxComparisonMillis,staleComparisons,comparisonFailures;
    private static boolean comparisonAdmissionRefused;
    private static String physicalResourceError="";
    private static long observationAdmissionFailures;
    private static WeakReference<Level> level=new WeakReference<>(null);
    private static UUID session;
    private static GeneratedPreviewProfile profile;
    private static GeneratedSkySource source;
    private static final GeneratedSceneNetwork.Receiver inventoryReceiver=new GeneratedSceneNetwork.Receiver();
    private static Map<CubeKey,int[]> known=Map.of(),physical=Map.of(),visual=Map.of();
    private static boolean inventoryComplete;
    private static volatile long epoch;
    private static long revision,geometryRevision,geometryChanges,solves,staleResults,noOpWindows,pristineCubes;
    private static long inventoryBytes,captureMillis,maxCaptureMillis,publishedPristineComparisons,generatorPristineComparisons,tilePristineComparisons;
    private static volatile String nativeWindowCacheStatus="NOT_STARTED",nativeWindowCacheWarning="";
    private static volatile long nativeWindowCacheHits,nativeWindowCacheMisses,nativeWindowCacheReadMillis,nativeWindowCacheWriteMillis;
    private static int initialSavedCubes;
    private static Job job;
    private static Published published;
    private static dev.madeinabyss.forge.probe.client.compat.AbyssNativeDensity.Snapshot exportedNativeDensity;
    private static Anchor desired,failedAnchor;
    private static long failedEpoch=-1;
    private static String error="";
    private static boolean nativeFirstViewEnabled,firstViewAttempted,firstViewResolved;
    private static Anchor firstViewTarget;
    private static int desiredRequestedRadius,firstViewRequestedRadius;
    private static long firstViewPublishedRevision=-1,nearRefusals,sessionOriginNanos,sessionOriginWallMillis;
    private static String nearError="";
    private static NearStats initialNearStats;
    private static final dev.madeinabyss.kernel.cube.NativeTargetGeneration targets=new dev.madeinabyss.kernel.cube.NativeTargetGeneration();
    private static final dev.madeinabyss.kernel.cube.NativeLatencyTrace latency=new dev.madeinabyss.kernel.cube.NativeLatencyTrace();
    private static final dev.madeinabyss.forge.probe.deep.GeneratedSceneDeltaAdmission globalDeltas=new dev.madeinabyss.forge.probe.deep.GeneratedSceneDeltaAdmission();
    private static final com.google.gson.Gson TRACE_JSON=new com.google.gson.Gson();
    private static volatile boolean runtimeCacheBypass;
    private static long sourceCreationTarget=-1,cancelledJobs,confirmedWorkerExits,maxCancelExitNanos;
    private static final java.util.concurrent.ThreadPoolExecutor CACHE_WRITER=new java.util.concurrent.ThreadPoolExecutor(1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"mia-native-derived-cache-writer");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-2);return t;},new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private static final java.util.concurrent.atomic.AtomicLong cacheWrites=new java.util.concurrent.atomic.AtomicLong(),cacheWriteFailures=new java.util.concurrent.atomic.AtomicLong();
    private record Anchor(int x,int y,int z,int radius) { }
    private record PhaseTimings(long densityMillis,long directSkyMillis,long ambientSeedMillis,long skyPropagationMillis,long blockPropagationMillis,long ambientSeedQueries,int directSkyColumns,long reusedDensityCells,long sampledDensityCells,long denseOpacityQueries,long fallbackOpacityQueries) { }
    private record Published(long epoch,long targetGeneration,Anchor anchor,VoxelSkyLight.Result sky,VoxelSkyLight.Result block,long cells,long millis,PhaseTimings phases,Map<CubeKey,int[]> cubes,VoxelSkyLight.Bounds bounds,GeneratedSkySource.SkyExposure[] exposure,GeneratedNativeWindow window,
                             dev.madeinabyss.forge.probe.client.compat.AbyssNativeDensity.Data density) {
        private Published(long epoch,long targetGeneration,Anchor anchor,VoxelSkyLight.Result sky,VoxelSkyLight.Result block,long cells,long millis,PhaseTimings phases,Map<CubeKey,int[]> cubes,VoxelSkyLight.Bounds bounds,GeneratedSkySource.SkyExposure[] exposure,GeneratedNativeWindow window){
            this(epoch,targetGeneration,anchor,sky,block,cells,millis,phases,cubes,bounds,exposure,window,dev.madeinabyss.forge.probe.client.compat.AbyssNativeDensity.Data.fromOwned(bounds,cubes));
        }
    }
    private static final class WorkExit {
        final java.util.concurrent.atomic.AtomicInteger state=new java.util.concurrent.atomic.AtomicInteger();volatile long cancelAt;volatile boolean cancelRequested;
        boolean start(){return state.compareAndSet(0,1);}boolean exited(){return state.get()==2;}
    }
    private record Job(long epoch,long targetGeneration,Anchor anchor,NativeFirstViewPlan.Plan nearPlan,Future<Published> future,WorkExit exit) { }
    private record NearStats(long epoch,long revision,long cells,long millis,PhaseTimings phases,VoxelSkyLight.Bounds solveBounds,VoxelSkyLight.Bounds drawBounds) { }
    private record ComparedObservations(GeneratedObservationQueue.Compared result,long publishedComparisons,long generatedComparisons,long tileComparisons,long millis) { }
    private record ComparisonJob(long epoch,Future<ComparedObservations> future) { }
    private record DensitySlab(Map<CubeKey,int[]> cubes,long reused,long sampled) {}
    private record AmbientSlab(long queries,long denseReads,long fallbackReads) {}
    static {
        GeneratedLightAccess.install((current,pos)->current==Minecraft.getInstance().level&&ClientDeepRuntime.generatedAccepted(current),
                (current,pos)->packedLight(pos.getX(),pos.getY(),pos.getZ()));
        GeneratedLightAccess.geometry((current,pos)->directSky(pos.getX(),pos.getY(),pos.getZ()),
                (current,pos)->rainHeight(pos.getX(),pos.getZ()));
    }
    private ClientGeneratedLight() { }
    public static boolean physicalResourcesRefused(){return !physicalResourceError.isEmpty();}
    public static String physicalResourceError(){return physicalResourceError;}
    /** Reload runs on the client owner. Never revive a profile with stale classification memoization. */
    public static void verifyPhysicalResources(){
        if(source==null||physicalResourcesRefused())return;
        try {source.verifyPhysicalSemantics();}
        catch(RuntimeException changed){
            var revoked=source;physicalResourceError=changed.toString();invalidGeometry(physicalResourceError);
            WORKER.execute(()->{try{revoked.quarantineLightCaches();}
                catch(java.io.IOException failed){com.mojang.logging.LogUtils.getLogger().error("Physical reload light-cache quarantine refused",failed);}});
        }
    }

    /** Reuse only the profile already validated for this complete, current inventory. */
    public static boolean admittedProfile(Level current,UUID token,String profileId,String fingerprint) {
        return current!=null && current==level.get() && token!=null && token.equals(session)
                && inventoryComplete && source!=null && profile!=null && error.isEmpty()
                && profile.id().equals(profileId) && profile.fingerprint().equals(fingerprint);
    }

    /** Spatial visual protocol admission can precede the complete saved-light inventory. */
    public static Integer visualMinimumCubeY(Level current,UUID token,CubeKey center){
        return current!=null && current==level.get() && token!=null && token.equals(session) && profile!=null
                ?profile.visualMinimumCubeY(center):null;
    }

    public static void begin(UUID token,String profileId,ClientLevel current) {
        clear();
        if(current==null)throw new IllegalStateException("Generated light requires client level");
        try {
            var biomes=current.registryAccess().registryOrThrow(Registries.BIOME);
            var candidate=GeneratedPreviewProfile.load(profileId,new FixedBiomeSource(biomes.getHolderOrThrow(Biomes.PLAINS)));
            session=token;profile=candidate;level=new WeakReference<>(current);globalDeltas.reset(token);captureNativeSession();
            GeneratedSceneNetwork.receiver(packet->ClientWindowPreparation.after(()->receive(packet)));
        }catch(java.io.IOException invalid){throw new IllegalStateException("Local generated profile validation failed",invalid);}
    }

    public static void begin(UUID token,GeneratedPreviewProfile candidate,ClientLevel current) {
        clear();
        if(current==null||token==null||candidate==null)throw new IllegalArgumentException("World light requires validated manifest and current level");
        session=token;profile=candidate;level=new WeakReference<>(current);globalDeltas.reset(token);captureNativeSession();
        GeneratedSceneNetwork.receiver(packet->ClientWindowPreparation.after(()->receive(packet)));
    }

    /** Complete inventory is decoded privately; no partial frame ever reaches the source. */
    public static void receive(GeneratedSceneNetwork.Message message) {
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.INVENTORY_APPLY);
        try{receiveMeasured(message);}finally{GeneratedFrameCosts.end(timing);}
    }
    private static void receiveMeasured(GeneratedSceneNetwork.Message message) {
        if(session==null||!session.equals(message.session())||profile==null||physicalResourcesRefused())return;
        try {
            if(message instanceof GeneratedSceneNetwork.Delta delta){
                var current=level.get();if(current==null){clear();return;}
                var prepared=globalDeltas.stage(delta,current.registryAccess().registryOrThrow(Registries.BIOME));
                if(prepared==null)return;
                if(!profile.generator().supportsCube(prepared.key()))throw new IllegalArgumentException("Global cube delta outside known generated domain");
                observations.observe(prepared.statesMap());globalDeltas.commit(prepared);comparisonAdmissionRefused=false;
                latency.phase(targets.generation(),"GLOBAL_CUBE_DELTA_APPLIED",prepared.key(),prepared.revision(),System.nanoTime());return;
            }
            if(message instanceof GeneratedSceneNetwork.Begin begin) {
                if(inventoryComplete)throw new IllegalArgumentException("Repeated initial generated scene");
                if(!profile.fingerprint().equals(begin.profileFingerprint()))throw new IllegalArgumentException("Generated inventory profile mismatch");
            }
            var complete=inventoryReceiver.accept(message);
            if(complete.isEmpty())return;
            var current=level.get();if(current==null){clear();return;}
            var biomes=current.registryAccess().registryOrThrow(Registries.BIOME);
            var candidate=new HashMap<CubeKey,int[]>();
            for(var frame:complete.get().frames()) {
                var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(frame.bytes()));
                try {
                    var decoded=CubeSectionPacketCodec.decode(buffer,biomes);int[] states=new int[4096];
                    for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)states[y<<8|z<<4|x]=Block.getId(decoded.section().getBlockState(x,y,z));
                    if(candidate.put(decoded.key(),states)!=null)throw new IllegalArgumentException("Duplicate decoded saved cube");
                }finally {buffer.release();}
            }
            // Physical/visual packets may precede the initial saved inventory. Their newest
            // observations override the earlier disk capture, with physical data winning.
            // Every pre-inventory observation is already queued, even if its window has left.
            // Install saved geometry without allowing older disk data to overwrite that queue.
            // Procedural classification happens exclusively on the comparison worker.
            observations.baseline(candidate);known=observations.retained();
            inventoryComplete=true;
            initialSavedCubes=complete.get().begin().cubeCount();inventoryBytes=complete.get().begin().encodedBytes();
            refreshSource();
        }catch(RuntimeException invalid){inventoryReceiver.clear();invalidGeometry(invalid.toString());}
    }

    public static void physical(Level current,SparseCubeSpace space){capture(current,space,true);}
    public static void visual(Level current,SparseCubeSpace space){capture(current,space,false);}
    private static void capture(Level current,SparseCubeSpace space,boolean isPhysical) {
        if(session==null||current==null||current!=level.get()||space==null||physicalResourcesRefused())return;
        long started=System.nanoTime();
        try {
            long generation=space.contentGeneration();var window=new HashMap<CubeKey,int[]>();
            for(var key:space.residentKeys())window.put(key,space.snapshot(key).orElseThrow().states());
            if(space.contentGeneration()!=generation)throw new IllegalStateException("Geometry changed during generated light capture");
            var incoming=new HashMap<>(window);
            if(!isPhysical)incoming.keySet().removeAll(physical.keySet());
            // Snapshot + bounded per-key coalescing only. An unverified previous window
            // cannot certify procedural geometry or cause an off-window edit to be dropped.
            for(var key:incoming.keySet())latency.phase(targets.generation(),isPhysical?"PHYSICAL_APPLY":"VISUAL_APPLY",key,generation,System.nanoTime());
            observations.observe(incoming);
            if(isPhysical)physical=Map.copyOf(window);else visual=Map.copyOf(window);
            comparisonAdmissionRefused=false;
            if(inventoryComplete&&source==null&&!geometryObservationsPending())refreshSource();
            noOpWindows++;
        }catch(RuntimeException invalid){observationAdmissionFailures++;invalidGeometry(invalid.toString());}
        finally {captureMillis=(System.nanoTime()-started)/1_000_000;maxCaptureMillis=Math.max(maxCaptureMillis,captureMillis);}
    }
    /** Pending includes in-flight keys; callers retain their previous scene until exact admission. */
    public static boolean geometryObservationsPending(){return session!=null&&observations.pendingCount()>0;}

    private static void pollObservations() {
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.OBSERVATION_APPLY);
        try{pollObservationsMeasured();}finally{GeneratedFrameCosts.end(timing);}
    }
    private static void pollObservationsMeasured() {
        if(comparisonJob!=null&&comparisonJob.future().isDone()) {
            var finished=comparisonJob;comparisonJob=null;
            try {
                var result=finished.future().get();
                if(finished.epoch()!=comparisonEpoch){staleComparisons++;}
                else {
                    var committed=observations.commit(result.result());
                    if(!committed.accepted())staleComparisons++;
                    else {
                        comparisonBatches++;comparisonMillis=result.millis();maxComparisonMillis=Math.max(maxComparisonMillis,comparisonMillis);
                        publishedPristineComparisons+=result.publishedComparisons();generatorPristineComparisons+=result.generatedComparisons();
                        tilePristineComparisons+=result.tileComparisons();
                        pristineCubes+=committed.pristineComparisons();known=observations.retained();
                        latency.phase(targets.generation(),"OBSERVATION_BATCH_ACCEPTED",null,committed.committed(),System.nanoTime());
                        if(committed.geometryChanged()){geometryChanges++;refreshSource();}
                    }
                }
            }catch(Exception failure){
                if(finished.epoch()==comparisonEpoch){comparisonFailures++;invalidGeometry("Generated observation comparison failed: "+failure);}
                else staleComparisons++;
            }
        }
        if(comparisonJob!=null||!inventoryComplete||comparisonAdmissionRefused||observations.pendingCount()==0)return;
        var batch=observations.takeBatch();var capturedProfile=profile;long capturedEpoch=comparisonEpoch;
        var capturedKnown=known;var capturedSource=source;var density=published!=null&&published.epoch()==epoch?published.cubes():Map.<CubeKey,int[]>of();
        latency.phase(targets.generation(),"OBSERVATION_COMPARE_QUEUED",null,batch.size(),System.nanoTime());
        comparisonJob=new ComparisonJob(capturedEpoch,COMPARISON_WORKER.submit(()-> {
            long started=System.nanoTime();long[] counts=new long[3];
            Runnable checkpoint=()->{if(capturedEpoch!=comparisonEpoch||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Generated observation session changed");};
            var compared=GeneratedObservationQueue.compare(batch,key->{
                checkpoint.run();
                if(!capturedKnown.containsKey(key)){if(density.containsKey(key))counts[0]++;else if(capturedSource!=null)counts[2]++;}
                return dev.madeinabyss.forge.worldgen.GeneratedObservationExpectations.expected(key,capturedKnown,density,capturedSource,
                        ()->{counts[1]++;return capturedProfile.generator().generateCube(key).states();},checkpoint);
            });
            return new ComparedObservations(compared,counts[0],counts[1],counts[2],(System.nanoTime()-started)/1_000_000);
        }));
    }
    private static void refreshSource() {
        if(physicalResourcesRefused())return;
        cancelJob(false);
        epoch++;published=null;exportedNativeDensity=null;revision++;geometryRevision++;failedAnchor=null;failedEpoch=-1;source=null;
        resetFirstView();
        // Native block state opacity/emission is compiled here on the client owner thread.
        source=freshSource();error="";
    }
    private static boolean cachesBypassed(){return runtimeCacheBypass||Boolean.getBoolean("mia.generated.nativeWindowCacheBypass");}
    private static GeneratedSkySource freshSource(){
        var made=new GeneratedSkySource(profile,known);if(cachesBypassed())made.disableDerivedDiskRestore();
        sourceCreationTarget=targets.generation();return made;
    }
    private static void cancelJob(boolean targetRetired){
        var active=job;if(active==null||active.exit().exited()||active.exit().cancelRequested)return;
        active.exit().cancelAt=System.nanoTime();active.exit().cancelRequested=true;cancelledJobs++;
        if(targetRetired)latency.cancel(active.targetGeneration(),active.exit().cancelAt);
        else latency.phase(active.targetGeneration(),"GEOMETRY_CANCEL_REQUESTED",null,active.epoch(),active.exit().cancelAt);
        if(active.exit().state.compareAndSet(0,2)){confirmedWorkerExits++;latency.workerExited(active.targetGeneration(),System.nanoTime());}
        active.future().cancel(true);
    }
    private static void requestTarget(double x,double y,double z,boolean teleport,long nano,long wall,String reason){
        int chunks=Minecraft.getInstance().options.renderDistance().get(),radius=Math.min(chunks,dev.madeinabyss.kernel.cube.NativeRenderDistance.MAX_CHUNKS)*16;
        var player=Minecraft.getInstance().player;long before=targets.generation();
        var target=teleport&&player!=null?targets.requestAppliedTeleport(x,y,z,player.getEyeHeight(),radius,chunks*16):targets.request(x,y,z,radius,chunks*16,teleport);
        desiredRequestedRadius=target.requestedRadius();desired=new Anchor(target.anchorX(),target.anchorY(),target.anchorZ(),target.radius());
        if(target.generation()==before)return;
        latency.cancel(before,nano);cancelJob(true);resetFirstView();failedAnchor=null;failedEpoch=-1;
        if(cachesBypassed()&&profile!=null&&inventoryComplete&&!comparisonAdmissionRefused)source=freshSource();
        latency.begin(target.generation(),nano,wall,player==null?x:player.getX(),player==null?y:player.getY(),player==null?z:player.getZ(),radius,cachesBypassed()?"BYPASS":"NORMAL",reason);
        if(source!=null)latency.cacheScope(target.generation(),sourceCreationTarget==target.generation(),source.derivedDiskRestoreDisabled(),true);
    }
    /** Called only after the vanilla client has actually applied its position packet. */
    public static void teleportApplied(double x,double y,double z,int teleportId){
        if(session==null||Minecraft.getInstance().level!=level.get())return;
        requestTarget(x,y,z,true,System.nanoTime(),System.currentTimeMillis(),"TELEPORT");
        latency.phase(targets.generation(),"TELEPORT_APPLIED",null,teleportId,System.nanoTime());
    }
    static long targetGeneration(){return targets.generation();}
    static void nativeCommitted(long target,long coverage,boolean full){latency.commit(target,coverage,full,System.nanoTime());}
    static void nativeDrawn(long target,long coverage,CubeKey cube,int faces,boolean full){latency.draw(target,coverage,cube,faces,true,full,System.nanoTime());}
    static void retainedNativeDrawn(CubeKey cube,int faces){latency.retainedDraw(targets.generation(),cube,faces,true,System.nanoTime());}
    public static void lodCutWorldAck(long target,long coverage,boolean completeCoverage){latency.cutAck(target,coverage,completeCoverage,System.nanoTime());}
    static void traceCube(long target,String stage,CubeKey cube,long revision){latency.phase(target,stage,cube,revision,System.nanoTime());}
    public static JsonObject latencyStatus(){return latencyStatus(false);}
    public static JsonObject latencyStatus(boolean includeEvents){
        var out=new JsonObject();out.addProperty("session",session==null?"":session.toString());out.addProperty("currentTargetGeneration",targets.generation());
        out.addProperty("requestedRadius",desired==null?0:desired.radius());out.add("targets",TRACE_JSON.toJsonTree(latency.targets()));
        if(includeEvents)out.add("events",TRACE_JSON.toJsonTree(latency.events()));
        out.addProperty("cancelledJobs",cancelledJobs);out.addProperty("confirmedWorkerExits",confirmedWorkerExits);out.addProperty("maxCancelExitMillis",maxCancelExitNanos/1_000_000.0);
        out.addProperty("phaseActive",PHASE_POOL.getActiveCount());out.addProperty("phaseQueued",PHASE_POOL.getQueue().size());
        out.addProperty("cacheWriterActive",CACHE_WRITER.getActiveCount());out.addProperty("cacheWriterQueued",CACHE_WRITER.getQueue().size());out.addProperty("cacheWritesCompleted",cacheWrites.get());out.addProperty("cacheWriteFailures",cacheWriteFailures.get());return out;
    }
    public static JsonObject latencyControl(JsonObject parameters){
        if(parameters.has("bypassCache")){
            boolean next=parameters.get("bypassCache").getAsBoolean();
            if(next!=runtimeCacheBypass){runtimeCacheBypass=next;cancelJob(false);if(profile!=null&&inventoryComplete)source=freshSource();
                var player=Minecraft.getInstance().player;if(player!=null&&session!=null)requestTarget(player.getX(),player.getY(),player.getZ(),true,System.nanoTime(),System.currentTimeMillis(),"CACHE_CONTROL");}
        }
        boolean reset=parameters.has("resetTrace")&&parameters.get("resetTrace").getAsBoolean();if(reset)latency.clear();
        if(parameters.has("resetMeshMetrics")&&parameters.get("resetMeshMetrics").getAsBoolean())DeepCubeRenderer.resetNativeMeshMetrics();
        var result=new JsonObject();result.addProperty("bypassCache",cachesBypassed());result.addProperty("resetTrace",reset);result.addProperty("cacheMode",cachesBypassed()?"BYPASS":"NORMAL");
        result.addProperty("clientObservedWallMillis",System.currentTimeMillis());
        var player=Minecraft.getInstance().player;if(player!=null){var position=new com.google.gson.JsonArray();position.add(player.getX());position.add(player.getY());position.add(player.getZ());result.add("clientPosition",position);}
        result.addProperty("sourceCachesFresh",source!=null&&sourceCreationTarget==targets.generation());result.addProperty("diskRestoreBypassed",source!=null&&source.derivedDiskRestoreDisabled());result.addProperty("inSolveMemoization",true);
        if(parameters.has("includeTrace")&&parameters.get("includeTrace").getAsBoolean())result.add("trace",latencyStatus(true));return result;
    }
    public static void invalidGeometry(String reason) {
        if(session==null)return;
        comparisonAdmissionRefused=true;
        comparisonEpoch++;
        observations.invalidateBatch();
        epoch++;revision++;geometryRevision++;published=null;exportedNativeDensity=null;source=null;error=reason;failedAnchor=null;failedEpoch=-1;
        resetFirstView();
    }

    /** Owner-thread poll and latest camera request; never blocks a render frame on the worker. */
    public static void update(ClientLevel current,double x,double y,double z) {
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.LIGHT_UPDATE);
        try{updateMeasured(current,x,y,z);}finally{GeneratedFrameCosts.end(timing);}
    }
    private static void updateMeasured(ClientLevel current,double x,double y,double z) {
        if(session==null)return;
        if(current==null||current!=level.get()){clear();return;}
        if(physicalResourcesRefused())return;
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))return;
        try{requestTarget(x,y,z,false,System.nanoTime(),System.currentTimeMillis(),"ANCHOR");}catch(RuntimeException refused){error=refused.toString();return;}
        pollObservations();
        if(inventoryComplete&&source==null&&!comparisonAdmissionRefused)refreshSource();
        if(job!=null&&job.exit().exited()&&job.future().isDone()&&!geometryObservationsPending()) {
            var finished=job;job=null;
            try {
                var result=finished.future().get();
                if(result!=null&&result.epoch()==epoch&&targets.current(result.targetGeneration())&&source!=null&&!geometryObservationsPending()
                        &&(finished.nearPlan()==null||!firstViewResolved)){
                    published=result;revision++;solves++;error="";
                    latency.phase(result.targetGeneration(),"LIGHT_PUBLISHED",null,revision,System.nanoTime());
                    if(result.window().stage()==NativeFirstViewPlan.Stage.NEAR){
                        firstViewPublishedRevision=revision;nearError="";
                        if(initialNearStats==null)initialNearStats=new NearStats(epoch,revision,result.cells(),result.millis(),result.phases(),result.window().solveBounds(),result.window().drawBounds());
                    }
                }
                else staleResults++;
            }catch(Exception failure){if(finished.epoch()==epoch&&targets.current(finished.targetGeneration())&&!finished.exit().cancelRequested){
                if(finished.nearPlan()!=null)resolveFirstView("Near solve refused: "+failure);
                else {error=failure.toString();failedEpoch=epoch;failedAnchor=finished.anchor();}
            }}
        }
        if(geometryObservationsPending())return;
        if(source==null||!inventoryComplete)return;
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))return;
        int chunks=Minecraft.getInstance().options.renderDistance().get();
        try{source.nativePlan(chunks,x,y,z);}catch(RuntimeException refused){error=refused.toString();return;}
        if(published!=null&&targets.current(published.targetGeneration())&&published.window().stage()==NativeFirstViewPlan.Stage.NEAR&&!firstViewResolved)return;
        if(job!=null||PHASE_POOL.getActiveCount()!=0||!PHASE_POOL.getQueue().isEmpty()
                ||published!=null&&targets.current(published.targetGeneration())&&published.epoch()==epoch&&published.window().stage()==NativeFirstViewPlan.Stage.FULL&&published.anchor().equals(desired)
                ||failedEpoch==epoch&&desired.equals(failedAnchor))return;
        try {
            var captured=source;long capturedEpoch=epoch;var center=desired;
            NativeFirstViewPlan.Plan nearPlan=null;
            if(nativeFirstViewEnabled&&!firstViewAttempted) {
                firstViewAttempted=true;firstViewTarget=desired;firstViewRequestedRadius=desiredRequestedRadius;
                var player=Minecraft.getInstance().player;double px=player==null?x:player.getX(),py=player==null?y:player.getY(),pz=player==null?z:player.getZ();
                var preliminary=NativeFirstViewPlan.near(chunks,px,py,pz,captured.minY(),captured.knownMaxY(),capturedEpoch,MAX_CELLS);
                if(preliminary.isPresent()) {
                    var b=preliminary.get().solveBounds();
                    int lower=captured.minKnownYForBox(b.minX(),b.minZ(),Math.addExact(b.minX(),b.sizeX()-1),Math.addExact(b.minZ(),b.sizeZ()-1));
                    nearPlan=NativeFirstViewPlan.near(chunks,px,py,pz,lower,captured.knownMaxY(),capturedEpoch,MAX_CELLS).orElse(null);
                }
                if(nearPlan==null)resolveFirstView("No complete known signed first-view halo for this target");
            }
            var bounds=nearPlan==null?bounds(captured,center):nearPlan.solveBounds();
            if(nearPlan!=null)center=new Anchor(nearPlan.anchorX(),nearPlan.anchorY(),nearPlan.anchorZ(),nearPlan.effectiveRadius());
            var previous=published;
            long cells=(long)bounds.sizeX()*bounds.sizeZ()*bounds.sizeY();
            long headroom=Runtime.getRuntime().maxMemory()-Runtime.getRuntime().totalMemory()+Runtime.getRuntime().freeMemory();
            if(headroom<cells*26+128L*1024*1024)throw new IllegalStateException("Native light memory admission refused: need "+(cells*26+128L*1024*1024)+" bytes, available "+headroom);
            String fingerprint=profile.fingerprint();var capturedProfile=profile;
            var configured=System.getProperty("mia.dh.previewCacheDirectory");
            var cacheDirectory=(configured==null?Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().getParent().getParent().resolve("cache/dh-preview"):
                    java.nio.file.Path.of(configured).toAbsolutePath().normalize()).resolve("sky-probes");
            var solveCenter=center;var solveNear=nearPlan;
            long target=targets.generation();var exit=new WorkExit();
            latency.cacheScope(target,sourceCreationTarget==target,captured.derivedDiskRestoreDisabled(),true);
            latency.workQueued(target,System.nanoTime());
            latency.phase(target,solveNear==null?"FULL_SOLVE_QUEUED":"NEAR_SOLVE_QUEUED",null,capturedEpoch,System.nanoTime());
            var future=WORKER.submit(()->{
                if(!exit.start())throw new java.util.concurrent.CancellationException("Native request cancelled before worker start");
                latency.phase(target,"WORKER_BEGIN",null,capturedEpoch,System.nanoTime());
                try{return solve(captured,capturedEpoch,target,solveCenter,bounds,previous,cacheDirectory,fingerprint,capturedProfile,solveNear);}
                finally{long done=System.nanoTime();if(exit.cancelRequested)maxCancelExitNanos=Math.max(maxCancelExitNanos,done-exit.cancelAt);confirmedWorkerExits++;latency.workerExited(target,done);exit.state.set(2);}
            });
            job=new Job(capturedEpoch,target,desired,solveNear,future,exit);
        }catch(RuntimeException refused){
            if(firstViewAttempted&&!firstViewResolved)resolveFirstView("Near preparation refused: "+refused);
            else {error=refused.toString();failedEpoch=epoch;failedAnchor=desired;}
        }
    }
    private static void captureNativeSession(){
        nativeFirstViewEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.nativeFirstView","true"));
        sessionOriginNanos=System.nanoTime();sessionOriginWallMillis=System.currentTimeMillis();
    }
    private static void resetFirstView(){firstViewAttempted=firstViewResolved=false;firstViewTarget=null;firstViewRequestedRadius=0;firstViewPublishedRevision=-1;}
    private static void resolveFirstView(String reason){
        if(!firstViewResolved){firstViewResolved=true;if(reason!=null&&!reason.isEmpty()){nearRefusals++;nearError=reason;}}
    }
    /** Owner ACK follows atomic solid+fluid commit; a light result alone never releases FULL. */
    static void nativeSceneCommitted(NativeSnapshot snapshot){
        if(snapshot!=null&&snapshot.stage()==NativeFirstViewPlan.Stage.NEAR&&snapshot.buildAllowed())resolveFirstView("");
    }
    static void nativeSceneRefused(NativeSnapshot snapshot,String reason){
        if(snapshot!=null&&snapshot.stage()==NativeFirstViewPlan.Stage.NEAR&&snapshot.epoch()==epoch
                &&snapshot.revision()==firstViewPublishedRevision)resolveFirstView(reason);
    }
    static UUID nativeSession(){return session;}
    static long nativeSessionOriginNanos(){return sessionOriginNanos;}
    static long nativeSessionOriginWallMillis(){return sessionOriginWallMillis;}
    private static int anchor(double coordinate) {
        double block=Math.floor(coordinate);
        if(block<Integer.MIN_VALUE||block>Integer.MAX_VALUE)throw new IllegalArgumentException("Generated camera outside signed XYZ");
        return Math.multiplyExact(Math.floorDiv((int)block,16),16);
    }
    private static VoxelSkyLight.Bounds bounds(GeneratedSkySource captured,Anchor center) {
        // A legal camera at an unaligned local floor has an anchor in the preceding cube.
        // Keep that anchor, but do not reclassify its rounded Y as the camera position.
        int cameraY=Math.max(center.y(),captured.knownLowerY(center.x(),center.z()));
        return captured.nativePlan(center.radius()/16,center.x(),cameraY,center.z()).lightBounds();
    }

    private static Published solve(GeneratedSkySource captured,long capturedEpoch,long capturedTarget,Anchor center,VoxelSkyLight.Bounds bounds,Published previous,java.nio.file.Path cacheDirectory,String fingerprint,GeneratedPreviewProfile capturedProfile,NativeFirstViewPlan.Plan nearPlan) {
        Runnable checkpoint=()->checkRequest(capturedEpoch,capturedTarget);
        checkpoint.run();
        try(var requestScope=captured.requestCheckpoint(checkpoint)){captured.restoreLightCache(cacheDirectory,fingerprint);}
        checkpoint.run();
        long start=System.nanoTime();int cells=Math.multiplyExact(Math.multiplyExact(bounds.sizeX(),bounds.sizeZ()),bounds.sizeY());
        String windowKey=null;var windowDirectory=cacheDirectory.resolve("native-windows");
        boolean cacheEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.nativeWindowCache","true"))
                && !cachesBypassed();
        if(cacheEnabled) {
            long readStarted=System.nanoTime();
            try {
                // Complete actual project resources + full numeric palette + exact saved geometry.
                windowKey=dev.madeinabyss.kernel.lod.TerrainLodDiskCache.identity("native-window-v2-relocation",fingerprint,
                        dev.madeinabyss.kernel.lod.TerrainLodDiskCache.codeIdentity(dev.madeinabyss.forge.worldgen.GeneratedNativeLightScheduler.class,dev.madeinabyss.kernel.light.NativeAmbientWork.class,dev.madeinabyss.forge.worldgen.GeneratedNativeLightSolver.class,NativeFirstViewPlan.class,VoxelSkyLight.class),
                        dev.madeinabyss.forge.probe.deep.GeneratedSceneInventory.proceduralIdentity(capturedProfile),
                        captured.cacheGeometryIdentity(),center.toString(),bounds.toString());
                if(nearPlan!=null)windowKey=dev.madeinabyss.kernel.lod.TerrainLodDiskCache.identity(windowKey,"certified-near-v1",
                        nearPlan.drawBounds().toString(),nearPlan.solveBounds().toString(),
                        Integer.toString(nearPlan.effectiveRadius()),Integer.toString(nearPlan.requestedRadius()));
                var cachedExposure=new GeneratedSkySource.SkyExposure[bounds.sizeX()*bounds.sizeZ()];boolean completeExposure=true;
                for(int z=0;z<bounds.sizeZ()&&completeExposure;z++)for(int x=0;x<bounds.sizeX();x++) {
                    var column=captured.cachedColumnExposure(bounds.minX()+x,bounds.minZ()+z);
                    if(column.isEmpty()){completeExposure=false;break;}cachedExposure[x+bounds.sizeX()*z]=column.get();
                }
                if(completeExposure) {
                    var read=NativeLightWindowCache.read(windowDirectory,windowKey,bounds,MAX_CELLS,Block.BLOCK_STATE_REGISTRY.size());nativeWindowCacheStatus=read.status();
                    if(read.entry().isPresent()) {
                        checkEpoch(capturedEpoch);var entry=read.entry().get();var cachedCubes=entry.cubes();
                        nativeWindowCacheHits++;nativeWindowCacheWarning="";nativeWindowCacheReadMillis=(System.nanoTime()-readStarted)/1_000_000;
                        latency.cacheHit(capturedTarget);checkpoint.run();return new Published(capturedEpoch,capturedTarget,center,entry.sky(),entry.block(),cells,(System.nanoTime()-start)/1_000_000,
                                new PhaseTimings(0,0,0,0,0,0,bounds.sizeX()*bounds.sizeZ(),(long)cachedCubes.size()*4096,0,0,0),cachedCubes,bounds,cachedExposure,
                                nativeWindow(capturedEpoch,center,entry.sky(),entry.block(),cachedCubes,nearPlan));
                    }
                } else nativeWindowCacheStatus="EXPOSURE_MISS";
                nativeWindowCacheMisses++;
            }catch(java.util.concurrent.CancellationException stale){throw stale;}
            catch(RuntimeException unavailable){windowKey=null;nativeWindowCacheStatus="UNAVAILABLE";nativeWindowCacheWarning=unavailable.toString();nativeWindowCacheMisses++;}
            finally{nativeWindowCacheReadMillis=(System.nanoTime()-readStarted)/1_000_000;}
        } else nativeWindowCacheStatus="BYPASSED";
        Map<CubeKey,int[]> previousCubes=previous!=null&&previous.epoch()==capturedEpoch
                &&(!cachesBypassed()||previous.targetGeneration()==capturedTarget)?previous.cubes():Map.of();
        var solved=dev.madeinabyss.forge.worldgen.GeneratedNativeLightScheduler.solve(captured,bounds,previousCubes,PHASE_POOL,PHASE_WORKERS,checkpoint,
                (stage,key)->latency.phase(capturedTarget,stage,key,capturedEpoch,System.nanoTime()));
        checkpoint.run();var t=solved.timings();
        var completedWindow=nativeWindow(capturedEpoch,center,solved.sky(),solved.block(),solved.cubes(),nearPlan);
        checkpoint.run();
        var result=new Published(capturedEpoch,capturedTarget,center,solved.sky(),solved.block(),cells,(System.nanoTime()-start)/1_000_000,
                new PhaseTimings(t.densityMillis(),t.directSkyMillis(),t.ambientSeedMillis(),t.skyPropagationMillis(),t.blockPropagationMillis(),t.ambientSeedQueries(),t.directSkyColumns(),t.reusedDensityCells(),t.sampledDensityCells(),t.denseOpacityQueries(),t.fallbackOpacityQueries()),
                solved.cubes(),bounds,solved.exposure(),completedWindow);
        String capturedKey=windowKey;
        if(!cachesBypassed())CACHE_WRITER.execute(()->{
            long writeStart=System.nanoTime();
            try{dev.madeinabyss.forge.worldgen.GeneratedNativeCachePersistence.write(captured,()->{
                if(capturedKey!=null)NativeLightWindowCache.write(windowDirectory,capturedKey,new NativeLightWindowCache.Entry(bounds,result.sky(),result.block(),result.cubes()));
                });
                cacheWrites.incrementAndGet();nativeWindowCacheWarning="";
            }catch(java.io.IOException|RuntimeException unavailable){cacheWriteFailures.incrementAndGet();nativeWindowCacheWarning=unavailable.toString();}
            finally{nativeWindowCacheWriteMillis=(System.nanoTime()-writeStart)/1_000_000;}
        });
        checkpoint.run();return result;
    }
    private static GeneratedNativeWindow nativeWindow(long capturedEpoch,Anchor center,VoxelSkyLight.Result sky,VoxelSkyLight.Result block,Map<CubeKey,int[]> cubes,NativeFirstViewPlan.Plan nearPlan){
        return nearPlan==null?GeneratedNativeWindow.full(capturedEpoch,center.x(),center.y(),center.z(),center.radius(),sky,block,cubes):
                GeneratedNativeWindow.near(nearPlan,capturedEpoch,sky,block,cubes).orElseThrow(()->new IllegalStateException("Near state/light certificate refused"));
    }
    private static void checkRequest(long capturedEpoch,long capturedTarget){
        if(!targets.current(capturedTarget))throw new java.util.concurrent.CancellationException("Native camera target changed");
        checkEpoch(capturedEpoch);
    }
    private static void checkEpoch(long capturedEpoch) {
        if(capturedEpoch!=epoch||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Generated light epoch changed");
    }
    public static OptionalInt packedLight(int x,int y,int z) {
        if(geometryObservationsPending()&&observations.pending(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16))))return OptionalInt.empty();
        var result=published;if(result==null||result.epoch()!=epoch)return OptionalInt.empty();
        return result.window().packed(x,y,z);
    }
    /** Captured immutable solve retained by a staged native build even when a newer solve publishes. */
    static final class NativeSnapshot {
        private final Published captured;private final long capturedRevision;
        private NativeSnapshot(Published p,long r){captured=p;capturedRevision=r;}
        long epoch(){return captured.epoch();}long revision(){return capturedRevision;}long targetGeneration(){return captured.targetGeneration();}
        int radius(){return captured.window().radius();}int anchorX(){return captured.window().anchorX();}int anchorY(){return captured.window().anchorY();}int anchorZ(){return captured.window().anchorZ();}
        Map<CubeKey,int[]> cubes(){return captured.window().cubes();}
        dev.madeinabyss.forge.probe.client.compat.AbyssNativeDensity.Data density(){return captured.density();}
        int minimumY(){return captured.window().minimumY();}
        int maximumY(){return captured.window().maximumY();}
        OptionalInt packed(int x,int y,int z){return captured.window().packed(x,y,z);}
        boolean readyFor(int targetRadius){return captured.window().readyFor(targetRadius);}
        boolean allowsBlock(int bx,int by,int bz,double x,double y,double z,int down,int up){return captured.window().allowsBlock(bx,by,bz,x,y,z,down,up);}
        int neighborState(long x,long y,long z){return captured.window().neighborState(x,y,z);}
        NativeFirstViewPlan.Stage stage(){return captured.window().stage();}
        GeneratedNativeWindow window(){return captured.window();}
        VoxelSkyLight.Bounds drawBounds(){return captured.window().drawBounds();}
        VoxelSkyLight.Bounds solveBounds(){return captured.window().solveBounds();}
        boolean buildAllowed(){return epoch()==epoch&&targets.current(captured.targetGeneration())&&source!=null&&(stage()!=NativeFirstViewPlan.Stage.NEAR
                ||!firstViewResolved&&published==captured&&capturedRevision==firstViewPublishedRevision);}
    }
    static NativeSnapshot nativeSnapshot(){return geometryObservationsPending()||published==null||published.epoch()!=epoch||!targets.current(published.targetGeneration())?null:new NativeSnapshot(published,revision);}
    /** Owner-only cheap export. Geometry comes from a completed worker; no light values or IO cross this boundary. */
    public static dev.madeinabyss.forge.probe.client.compat.AbyssNativeDensity.Snapshot nativeDensity(String expectedSourceIdentity,long expectedSourceRevision,long committedNativeRevision,
                                                                                                  java.util.function.IntFunction<dev.madeinabyss.kernel.terrain.TerrainMaterial> mapper){
        if(geometryObservationsPending()||published==null||published.epoch()!=epoch||source==null||profile==null||!inventoryComplete||expectedSourceRevision!=geometryRevision||source.cachedGeometryIdentity()==null){exportedNativeDensity=null;return null;}
        var authority=new dev.madeinabyss.forge.probe.client.compat.AbyssNativeDensity.Authority(expectedSourceIdentity,expectedSourceRevision,epoch,committedNativeRevision);
        if(exportedNativeDensity!=null&&exportedNativeDensity.authority().equals(authority)&&exportedNativeDensity.publicationRevision()==revision)return exportedNativeDensity;
        String actualIdentity=dev.madeinabyss.kernel.lod.TerrainLodDiskCache.identity("abyss-authoritative-source-v1",profile.fingerprint(),source.cachedGeometryIdentity());
        if(!actualIdentity.equals(expectedSourceIdentity))return null;
        var snapshot=new NativeSnapshot(published,revision);
        return exportedNativeDensity=new dev.madeinabyss.forge.probe.client.compat.AbyssNativeDensity.Snapshot(snapshot.density(),authority,revision,mapper);
    }
    private static GeneratedSkySource.SkyExposure exposure(int x,int z) {
        var p=published;
        if(p!=null&&p.epoch()==epoch) {long xx=(long)x-p.bounds().minX(),zz=(long)z-p.bounds().minZ();if(xx>=0&&xx<p.bounds().sizeX()&&zz>=0&&zz<p.bounds().sizeZ())return p.exposure()[(int)(xx+p.bounds().sizeX()*zz)];}
        return source==null?null:source.cachedColumnExposure(x,z).orElse(null);
    }
    public static OptionalInt directSky(int x,int y,int z){var c=exposure(x,z);return c==null||source==null||y<c.minimumY()?OptionalInt.empty():OptionalInt.of(c.directSky(y));}
    public static OptionalInt rainHeight(int x,int z){var c=exposure(x,z);return c==null?OptionalInt.empty():OptionalInt.of(c.rainHeight());}
    /** Immutable worker-produced native geometry; arrays remain exclusively owned and read-only. */
    static Map<CubeKey,int[]> nativeCubes(){return published==null||published.epoch()!=epoch?Map.of():published.cubes();}
    public static int nativeRadius(){return published==null||published.epoch()!=epoch?0:published.anchor().radius();}
    public static boolean nativeReady(double x,double y,double z,int radius){return !geometryObservationsPending()&&published!=null&&published.window().nativeReady(x,y,z,radius,epoch);}
    public static long revision(){return revision;}
    public static long geometryRevision(){return geometryRevision;}
    public static long geometryEpoch(){return epoch;}
    public static int nativeMinimumY(){return published==null?Integer.MAX_VALUE:published.window().minimumY();}
    public static int nativeMaximumY(){return published==null?Integer.MIN_VALUE:published.window().maximumY();}
    /** Explicit development-only probe; never called on render/tick hot paths. */
    public static com.google.gson.JsonObject samplingDiagnostics() {
        var out=new com.google.gson.JsonObject();
        var hashes=new com.google.gson.JsonArray();
        for(int x=-20;x<20;x++)hashes.add(new dev.madeinabyss.kernel.terrain.natural.SupportedCaveTerrainSampler.Foot(x,-31*x,-512).hashCode());
        out.add("footHashes",hashes);
        if(profile==null||profile.generator().caveExpansion()==null)return out;
        var caves=profile.generator().caveExpansion().caves();
        try {
            var field=caves.getClass().getDeclaredField("clearanceNetworks");field.setAccessible(true);
            var map=(java.util.Map<?,?>)field.get(caves);var counts=new java.util.HashMap<Integer,Integer>();
            for(var key:map.keySet())counts.merge(key.hashCode(),1,Integer::sum);
            out.addProperty("clearanceKeys",map.size());out.addProperty("uniqueHashes",counts.size());
            out.addProperty("maximumHashMultiplicity",counts.values().stream().mapToInt(Integer::intValue).max().orElse(0));
            out.addProperty("mapType",map.getClass().getName());
        }catch(ReflectiveOperationException failure){out.addProperty("error",failure.toString());}
        return out;
    }
    public static GeneratedSkySource source(){return source;}
    public static JsonObject status() {
        var out=new JsonObject();out.addProperty("active",session!=null);out.addProperty("inventoryComplete",inventoryComplete);
        out.addProperty("session",session==null?"":session.toString());out.addProperty("epoch",epoch);
        out.addProperty("nativeFirstViewEnabled",nativeFirstViewEnabled);out.addProperty("targetGeneration",targets.generation());
        out.addProperty("nativeStage",published==null?"NONE":published.window().stage().name());
        out.addProperty("nativePendingStage",job==null?"NONE":job.nearPlan()==null?"FULL":"NEAR");
        out.addProperty("nativeRequestedRadius",desiredRequestedRadius);out.addProperty("nativeTargetRadius",desired==null?0:desired.radius());
        out.addProperty("nearAwaitingCommit",firstViewAttempted&&!firstViewResolved&&published!=null&&published.window().stage()==NativeFirstViewPlan.Stage.NEAR);
        out.addProperty("nearRefusals",nearRefusals);out.addProperty("nearError",nearError);
        if(published!=null){out.add("solveBounds",boundsJson(published.window().solveBounds()));out.add("drawBounds",boundsJson(published.window().drawBounds()));}
        if(initialNearStats!=null){
            var near=new JsonObject();near.addProperty("epoch",initialNearStats.epoch());near.addProperty("revision",initialNearStats.revision());
            near.addProperty("cells",initialNearStats.cells());near.addProperty("solveMillis",initialNearStats.millis());
            near.addProperty("densityMillis",initialNearStats.phases().densityMillis());near.addProperty("directSkyMillis",initialNearStats.phases().directSkyMillis());
            near.addProperty("ambientSeedMillis",initialNearStats.phases().ambientSeedMillis());near.addProperty("skyPropagationMillis",initialNearStats.phases().skyPropagationMillis());
            near.addProperty("blockPropagationMillis",initialNearStats.phases().blockPropagationMillis());near.addProperty("densitySampleCalls",initialNearStats.phases().sampledDensityCells());
            near.addProperty("densityReusedCells",initialNearStats.phases().reusedDensityCells());near.add("solveBounds",boundsJson(initialNearStats.solveBounds()));near.add("drawBounds",boundsJson(initialNearStats.drawBounds()));
            out.add("initialNearPhaseStats",near);
        }
        out.addProperty("sourceAvailable",source!=null);out.addProperty("cachedSkyColumns",source==null?0:source.cachedSkyColumns());out.addProperty("skyColumnBuilds",source==null?0:source.skyColumnBuilds());out.addProperty("skyColumnEvictions",source==null?0:source.skyColumnEvictions());out.addProperty("available",published!=null&&published.epoch()==epoch);
        int residentStateCaches=source==null?0:source.residentStateCaches();
        out.addProperty("residentStateCaches",residentStateCaches);
        out.addProperty("residentStateCacheBytes",(long)residentStateCaches*65536*4*Integer.BYTES);
        out.addProperty("residentStateCacheHits",source==null?0:source.stateCacheHits());
        out.addProperty("residentStateCacheMisses",source==null?0:source.stateCacheMisses());
        out.addProperty("opacityPagesEnabled",source!=null&&source.opacityPagesEnabled());
        out.addProperty("opacityPageCapacity",source==null?0:source.opacityPageCapacity());
        out.addProperty("proceduralCacheStatus",source==null?"NONE":source.proceduralCacheStatus());
        out.addProperty("proceduralCacheWarning",source==null?"":source.proceduralCacheWarning());
        out.addProperty("proceduralCacheHits",source==null?0:source.proceduralCacheHits());
        out.addProperty("proceduralGeneratedCubes",source==null?0:source.proceduralGeneratedCubes());
        out.addProperty("proceduralReadMillis",source==null?0:source.proceduralReadMillis());
        out.addProperty("proceduralWriteMillis",source==null?0:source.proceduralWriteMillis());
        out.addProperty("opacityCacheHits",source==null?0:source.opacityCacheHits());
        out.addProperty("opacityCacheMisses",source==null?0:source.opacityCacheMisses());
        out.addProperty("opacityCacheEvictions",source==null?0:source.opacityCacheEvictions());
        out.addProperty("opacityCacheResidentBytes",source==null?0:source.opacityCacheResidentBytes());
        out.addProperty("sharedOpacityEnabled",source!=null&&source.sharedOpacityEnabled());
        out.addProperty("sharedOpacityHits",source==null?0:source.sharedOpacityHits());
        out.addProperty("sharedOpacityMisses",source==null?0:source.sharedOpacityMisses());
        out.addProperty("sharedOpacityEvictions",source==null?0:source.sharedOpacityEvictions());
        out.addProperty("sharedOpacityResidentBytes",source==null?0:source.sharedOpacityResidentBytes());
        out.addProperty("denseOpacityEnabled",source!=null&&source.denseOpacityEnabled());
        out.addProperty("denseOpacityHits",source==null?0:source.denseOpacityHits());
        out.addProperty("denseOpacityResidentBytes",source==null?0:source.denseOpacityResidentBytes());
        out.addProperty("islandAirCertificateEnabled",source!=null&&source.islandAirCertificateEnabled());
        out.addProperty("islandAirCertificateHits",source==null?0:source.islandAirCertificateHits());
        out.addProperty("deepAirCertificateEnabled",source!=null&&source.deepAirCertificateEnabled());
        out.addProperty("deepAirCertificateHits",source==null?0:source.deepAirCertificateHits());
        out.addProperty("deepAirCellCertificateEnabled",source!=null&&source.deepAirCellCertificateEnabled());
        out.addProperty("deepAirCellCertificateHits",source==null?0:source.deepAirCellCertificateHits());
        out.addProperty("deepAirCellCertificateResidentBytes",source==null?0:source.deepAirCellCertificateResidentBytes());
        out.addProperty("deepAirCellCertificateConstructionNanos",source==null?0:source.deepAirCellCertificateConstructionNanos());
        out.addProperty("deepAirCellCertificateAdmittedCells",source==null?0:source.deepAirCellCertificateAdmittedCells());
        out.addProperty("derivedGeometryAirCertificateEnabled",source!=null&&source.derivedGeometryAirCertificateEnabled());
        out.addProperty("derivedGeometryAirCertificateHits",source==null?0:source.derivedGeometryAirCertificateHits());
        out.addProperty("caveOpacityProjectionEnabled",source==null?false:source.caveOpacityProjectionEnabled());
        out.addProperty("caveOpacityProjectionMode",source==null?"NO_SOURCE":source.caveOpacityProjectionMode());
        out.addProperty("caveOpacityProjectionCoreSamples",source==null?0:source.caveOpacityProjectionCoreSamples());
        out.addProperty("caveOpacityProjectionHits",source==null?0:source.caveOpacityProjectionHits());
        out.addProperty("caveOpacityProjectionRockHits",source==null?0:source.caveOpacityProjectionRockHits());
        out.addProperty("caveOpacityProjectionSoilHits",source==null?0:source.caveOpacityProjectionSoilHits());
        out.addProperty("caveOpacityProjectionGrassHits",source==null?0:source.caveOpacityProjectionGrassHits());
        out.addProperty("caveOpacityProjectionContinued",source==null?0:source.caveOpacityProjectionContinued());
        out.addProperty("caveOpacityProjectionRefusals",source==null?0:source.caveOpacityProjectionRefusals());
        out.addProperty("projection155Enabled",source!=null&&source.projection155Enabled());out.addProperty("projection155Hits",source==null?0:source.projection155Hits());
        out.addProperty("exact155ViewEnabled",source!=null&&source.exact155ViewEnabled());out.addProperty("stateProjection155Hits",source==null?0:source.stateProjection155Hits());
        out.addProperty("recentVisibility155Entries",source==null?0:source.recentVisibility155Entries());out.addProperty("recentVisibility155Evictions",source==null?0:source.recentVisibility155Evictions());
        out.addProperty("airCertificateEnabled",source!=null&&source.airCertificateEnabled());out.addProperty("airCertificateHits",source==null?0:source.airCertificateHits());
        out.addProperty("singleFlightEnabled",source!=null&&source.singleFlightEnabled());out.addProperty("probeFlightEvaluations",source==null?0:source.probeFlightEvaluations());out.addProperty("probeFlightJoins",source==null?0:source.probeFlightJoins());
        out.addProperty("nativeWindowCacheEnabled",Boolean.parseBoolean(System.getProperty("mia.generated.nativeWindowCache","true")));
        out.addProperty("nativeWindowCacheBypass",cachesBypassed());
        out.addProperty("nativeWindowCacheStatus",nativeWindowCacheStatus);out.addProperty("nativeWindowCacheWarning",nativeWindowCacheWarning);
        out.addProperty("nativeWindowCacheHits",nativeWindowCacheHits);out.addProperty("nativeWindowCacheMisses",nativeWindowCacheMisses);
        out.addProperty("nativeWindowCacheReadMillis",nativeWindowCacheReadMillis);out.addProperty("nativeWindowCacheWriteMillis",nativeWindowCacheWriteMillis);
        out.addProperty("lightProbeCacheStatus",source==null?"INACTIVE":source.lightCacheStatus());
        out.addProperty("lightProbeCacheWarning",source==null?"":source.lightCacheWarning());
        out.addProperty("lightProbeCacheReadMillis",source==null?0:source.lightCacheReadMillis());
        out.addProperty("lightProbeCacheWriteMillis",source==null?0:source.lightCacheWriteMillis());
        out.addProperty("cachedVisibilityProbes",source==null?0:source.cachedVisibilityProbes());
        out.addProperty("skyColumnBudget",GeneratedSkySource.MAX_SKY_COLUMNS);out.addProperty("phaseWorkers",PHASE_WORKERS);out.addProperty("phaseQueued",PHASE_POOL.getQueue().size());out.addProperty("phaseActive",PHASE_POOL.getActiveCount());out.addProperty("worker",session!=null&&job!=null&&!job.future().isDone());out.addProperty("revision",revision);out.addProperty("geometryRevision",geometryRevision);out.addProperty("geometryEpoch",epoch);
        out.addProperty("captureMillis",captureMillis);out.addProperty("maxCaptureMillis",maxCaptureMillis);out.addProperty("publishedPristineComparisons",publishedPristineComparisons);out.addProperty("generatorPristineComparisons",generatorPristineComparisons);out.addProperty("tilePristineComparisons",tilePristineComparisons);out.addProperty("knownCubes",known.size());out.addProperty("physicalCubes",physical.size());out.addProperty("visualCubes",visual.size());
        out.addProperty("geometryObservationsPending",geometryObservationsPending());out.addProperty("pendingObservationCubes",observations.pendingCount());out.addProperty("verifiedObservationCubes",observations.verifiedCount());
        out.addProperty("observationLimit",OBSERVATION_LIMIT);out.addProperty("comparisonBatchLimit",COMPARISON_BATCH);out.addProperty("comparisonWorker",comparisonJob!=null&&!comparisonJob.future().isDone());
        out.addProperty("comparisonBatches",comparisonBatches);out.addProperty("comparisonMillis",comparisonMillis);out.addProperty("maxComparisonMillis",maxComparisonMillis);out.addProperty("staleComparisons",staleComparisons);out.addProperty("comparisonFailures",comparisonFailures);
        out.addProperty("observationAdmissionFailures",observationAdmissionFailures);out.addProperty("comparisonAdmissionRefused",comparisonAdmissionRefused);
        out.addProperty("initialSavedCubes",initialSavedCubes);out.addProperty("initialEncodedBytes",inventoryBytes);
        out.addProperty("retentionPolicy","saved-and-modified-plus-current-windows");
        out.addProperty("geometryChanges",geometryChanges);out.addProperty("noOpWindows",noOpWindows);out.addProperty("pristineCubes",pristineCubes);out.addProperty("solves",solves);out.addProperty("staleResults",staleResults);
        out.addProperty("nativeRadius",nativeRadius());out.addProperty("maxCells",MAX_CELLS);out.addProperty("nativeGeometryCubes",nativeCubes().size());
        out.addProperty("cells",published==null?0:published.cells());out.addProperty("solveMillis",published==null?0:published.millis());
        if(published!=null) {
            var phases=published.phases();out.addProperty("densityMillis",phases.densityMillis());out.addProperty("directSkyMillis",phases.directSkyMillis());
            out.addProperty("ambientSeedMillis",phases.ambientSeedMillis());out.addProperty("skyPropagationMillis",phases.skyPropagationMillis());
            out.addProperty("blockPropagationMillis",phases.blockPropagationMillis());out.addProperty("ambientSeedQueries",phases.ambientSeedQueries());
            out.addProperty("directSkyColumnCount",phases.directSkyColumns());out.addProperty("densitySampleCalls",phases.sampledDensityCells());out.addProperty("densityReusedCells",phases.reusedDensityCells());out.addProperty("denseOpacityQueries",phases.denseOpacityQueries());out.addProperty("fallbackOpacityQueries",phases.fallbackOpacityQueries());
        }
        out.addProperty("globalDeltaRevision",globalDeltas.revision());out.addProperty("error",error);if(profile!=null)out.addProperty("profileFingerprint",profile.fingerprint());return out;
    }
    public static void clear() {
        cancelJob(true);targets.retire();latency.clear();
        epoch++;revision++;geometryRevision++;session=null;profile=null;source=null;published=null;exportedNativeDensity=null;desired=null;failedAnchor=null;failedEpoch=-1;
        level=new WeakReference<>(null);known=physical=visual=Map.of();inventoryComplete=false;inventoryReceiver.clear();globalDeltas.reset(null);
        comparisonEpoch++;observations.clear();comparisonBatches=comparisonMillis=maxComparisonMillis=staleComparisons=comparisonFailures=0;
        comparisonAdmissionRefused=false;physicalResourceError="";observationAdmissionFailures=0;
        captureMillis=maxCaptureMillis=publishedPristineComparisons=generatorPristineComparisons=tilePristineComparisons=0;
        if(comparisonJob!=null&&comparisonJob.future().isDone())comparisonJob=null;
        inventoryBytes=0;initialSavedCubes=0;geometryChanges=solves=staleResults=noOpWindows=pristineCubes=0;error="";
        nativeWindowCacheStatus="NOT_STARTED";nativeWindowCacheWarning="";nativeWindowCacheHits=nativeWindowCacheMisses=nativeWindowCacheReadMillis=nativeWindowCacheWriteMillis=0;
        resetFirstView();nativeFirstViewEnabled=false;desiredRequestedRadius=0;initialNearStats=null;nearRefusals=0;nearError="";sessionOriginNanos=sessionOriginWallMillis=0;
        if(job!=null&&job.future().isDone())job=null;
        // A previous job keeps its single worker slot until it notices the epoch. New sessions
        // cannot queue another solve until update observes that job's actual completion.
    }
    static JsonObject boundsJson(VoxelSkyLight.Bounds b){
        var out=new JsonObject();out.addProperty("minX",b.minX());out.addProperty("minY",b.minY());out.addProperty("minZ",b.minZ());
        out.addProperty("sizeX",b.sizeX());out.addProperty("sizeY",b.sizeY());out.addProperty("sizeZ",b.sizeZ());return out;
    }
}
