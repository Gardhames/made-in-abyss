package dev.madeinabyss.forge.probe.client.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShadowCullingFrustum;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.*;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import dev.madeinabyss.forge.probe.client.ClientDeepVisual;
import dev.madeinabyss.forge.probe.client.ClientGeneratedLight;
import dev.madeinabyss.forge.probe.client.DeepCubeRenderer;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.worldgen.GeneratedSkySource;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.concurrent.BoundedOrderedWork;
import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.lod.TerrainLodParallelBuild;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import dev.madeinabyss.kernel.lod.TerrainTileDiskCache;
import dev.madeinabyss.kernel.lod.TerrainLodClipper;
import dev.madeinabyss.kernel.lod.RenderGroupHandoff;
import dev.madeinabyss.kernel.lod.TerrainLodRefinement;
import dev.madeinabyss.kernel.lod.NativeSeamRefinement;
import dev.madeinabyss.kernel.lod.ColdPreviewStages;
import dev.madeinabyss.kernel.lod.TerrainLodVerticalMerge;
import dev.madeinabyss.kernel.lod.TerrainLodBudgetRefinement;
import dev.madeinabyss.kernel.lod.ProgressiveRefinementOverlay;
import dev.madeinabyss.kernel.lod.BoxLightWorkOrder;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.awt.Color;
import java.io.Closeable;
import java.util.*;
import java.util.concurrent.*;

/** Narrow diagnostic first-layer preview through the public DH API. No DH core implementation imports. */
public final class DhPreviewAdapter implements DistantHorizonsCompat.Bridge {
    private static final String DISPLAY_NAME="Made in Abyss development preview";
    private static final long SAMPLE_CAP=120_000_000;
    private static final int BOX_CAP=400_000, CUBE_CAP=512;
    private static final TerrainMaterial[] KNOWN_MATERIALS=TerrainMaterial.values();

    private boolean initialized,eventsBound,active,scopeActive,generationOwned,closed;
    private volatile long epoch;
    private volatile long lifetime;
    private String error="",phase="WAITING_FOR_DH_INIT",session,fingerprint;
    private ClientLevel level;
    private SparseCubeSpace physical;
    private long physicalGeneration,visualRevision,sampledVoxels,renderCallbacks,preRenderCallbacks,terrainCulls,levelLoads,connectionPreparations,ignoredLevelUnloads;
    private int capturedCubes,sourceBoxes,nearCulledBoxes,mirrorCulledBoxes;
    private JsonObject requested=new JsonObject();
    private float nearClip,farClip;
    private IDhApiLevelWrapper wrapper;
    private IDhApiCustomRenderRegister register;
    private final RenderGroupHandoff<Scene> groups=new RenderGroupHandoff<>();
    private long pendingEpoch,stagedEpoch,handoffCount,retainedRefreshCount;
    private long stagedDrawCallbacks,missingDisplayedRefreshFrames;
    private int maximumOwnedGroups;
    private long groupCreated,groupClosed;
    private int maximumOwnedScenes;
    private long sceneCreated,sceneClosed,publishedGeometryRevision=-1;
    private record LightBucket(int sky,int block) {}
    private record RegistrationPart(LightBucket light,List<DhApiRenderableBox> boxes) {}
    private static final int REGISTRATION_BOX_CHUNK=4096;
    private static final long REGISTRATION_BUDGET_NANOS=3_000_000L;
    private long registrationBatches,registrationMaxNanos,registrationCreatedParts,hiddenWarmupDraws;
    private static final class Scene {
        List<TerrainLodColumns.Box> sourceGeometry=List.of();
        Map<TerrainLodColumns.Box,Integer> shadowSky=Map.of();
        final List<IDhApiRenderableBoxGroup> buckets=new ArrayList<>();
        final Map<IDhApiRenderableBoxGroup,LightBucket> physicalLights=new IdentityHashMap<>();
        final Set<IDhApiRenderableBoxGroup> drawn=Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<IDhApiRenderableBoxGroup> registered=Collections.newSetFromMap(new IdentityHashMap<>());
        final ArrayDeque<RegistrationPart> remaining=new ArrayDeque<>();
        boolean registrationComplete,overview,nearOverview,nearDetail,refinedComplete,previewBackdrop,earlyDetailPreserved;
        int requestedDetailBoxes;
        boolean closed;
        int boxCount(){return buckets.stream().mapToInt(IDhApiRenderableBoxGroup::size).sum();}
        boolean markDrawn(IDhApiRenderableBoxGroup bucket){drawn.add(bucket);return registrationComplete&&drawn.size()==buckets.size();}
    }
    private IDhApiCullingFrustum ownCuller,delegate;
    private IDhApiShadowCullingFrustum ownShadowCuller,shadowDelegate;
    private int shadowCullerPriority;
    private long shadowTerrainCulls;
    private java.lang.reflect.Method shaderShadowState;
    private long shaderShadowObjectsSkipped;
    private long shaderShadowPassesSkipped;
    private int cullerPriority;
    private IDhApiConfigValue<EDhApiGeneratorPlan> generation;
    private IDhApiConfigValue<Float> overdraw;
    private boolean overdrawOwned;
    private ExecutorService worker;
    private ThreadPoolExecutor lightWorker;
    private int lightWorkers;
    private Future<?> task;
    private Map<LightBucket,List<DhApiRenderableBox>> pendingBoxes;
    private List<TerrainLodColumns.Box> pendingGeometry=List.of();
    private Map<TerrainLodColumns.Box,Integer> pendingShadowSky=Map.of();
    private boolean overviewRequested,overviewPending,pendingOverview;
    private boolean nearOverviewEligible,pendingNearOverview;
    private boolean earlyDetailEligible,pendingNearDetail;
    private boolean pendingEarlyDetailPreserved;
    private boolean pendingPreviewBackdrop;
    private int pendingRequestedDetailBoxes;
    private record FullPreviewBackdrop(long life,long revision,String identity,List<TerrainLodColumns.Box> raw,
                                       Map<TerrainLodColumns.Box,LightBucket> lights) {
        FullPreviewBackdrop {raw=List.copyOf(raw);lights=Map.copyOf(lights);}
        boolean matches(long lifetime,Mirror mirror,String sourceIdentity) {
            return life==lifetime&&revision==mirror.geometryRevision()&&identity.equals(sourceIdentity);
        }
    }
    private volatile FullPreviewBackdrop fullPreviewBackdrop;
    private volatile FullPreviewBackdrop nearPreviewBackdrop;
    private record StartupAcknowledgement(Mirror mirror,long run) {}
    private record EarlyDetail(long life,long revision,String identity,String planIdentity,List<TerrainLodBudgetRefinement.Tile> raw) {
        EarlyDetail {raw=List.copyOf(raw);}
        boolean matches(long lifetime,Mirror mirror,String sourceIdentity){return life==lifetime&&revision==mirror.geometryRevision()&&identity.equals(sourceIdentity);}
    }
    private volatile EarlyDetail earlyDetail;
    private volatile Map<DetailTile,Long> earlyFreshTiles=Map.of();
    private ColdPreviewStages startupStages;
    private CompletableFuture<StartupAcknowledgement> nearOverviewAck,nearDetailAck;
    private ColdPreviewStages.NearPlan nearOverviewPlan;
    private long nearOverviewSampledVoxels,nearOverviewBuildMillis,firstNearMillis=-1,firstFullOverviewMillis=-1;
    private long earlyDetailSampledVoxels,earlyDetailGeometryMillis,earlyDetailBuildMillis,firstNearDetailMillis=-1;
    private long earlyChangedPlanSampleReserve,retainedEarlyDetailPromotions,earlyDetailRefusals,earlyDetailDowngrades,earlyDetailPlanConflicts;
    private int earlyDetailPreparedJobs,earlyDetailSelectedJobs,earlyDetailRawAcceptedTiles,earlyDetailVisibleBoxes;
    private long estimatedOverviewSamples,overviewSampledVoxels,overviewBuildMillis,overviewFirstDisplayMillis=-1,firstRequestedDisplayMillis=-1,firstPartialDetailDisplayMillis=-1,firstVisibleDetailDisplayMillis=-1;
    private long[] publishedYExtents;
    private UUID sessionToken;
    private TerrainLodColumns.Bounds bounds;
    private TerrainLodColumns.Parameters limits;
    private double nearDistance;
    private boolean refinementEnabled;
    private boolean refreshInFlight, refinementPending, pendingRefined;
    private long beginNanos, firstDisplayMillis, deferredMovementRefreshes;
    private long geometryBuildMillis, lightingBuildMillis, reusedBoxLights;
    private long reusedCumulativeBoxLights,reusedPreviewBoxLights;
    private volatile long baseBuildMillis;
    private long detailGeometryBuildMillis;
    private long exposedSkyCacheHits,exposedSkyEvaluations;
    private int exposedSkyCachedProbes;
    private int verifiedBoxLights;
    private boolean boxLightSpatialOrderEnabled=false;
    private String boxLightSpatialOrderMode="ORIGINAL";
    private int boxLightSpatialOrderedJobs;
    private long boxLightSpatialOrderingNanos;
    private Map<TerrainLodColumns.Box,LightBucket> boxLightCache=Map.of();
    private long boxLightGeometryRevision=-1;
    private record BaseCache(String key,String sourceIdentity,TerrainLodColumns.Result base,
                             Map<TerrainLodColumns.Box,LightBucket> lights,java.nio.file.Path directory,boolean hit) {}
    private volatile BaseCache baseDiskCache;
    private volatile String diskCacheStatus="NOT_STARTED",diskCacheWarning="";
    private volatile long diskCacheReadMillis,diskCacheWriteMillis;
    private volatile String finalDiskCacheStatus="NOT_STARTED",finalDiskCacheWarning="";
    private volatile long finalDiskCacheReadMillis,finalDiskCacheWriteMillis;
    private Map<TerrainLodColumns.Box,LightBucket> shadowLightCache=Map.of();
    private long shadowLightGeometryRevision=-1;
    private String quality="legacy";
    private int overviewStep=16;
    private long detailSampleCap=3_473_408,estimatedBaseSamples,estimatedDetailSamples,estimatedInitialSamples;
    private List<TerrainLodRefinement.Band> detailBands=DhPreviewAdapter.LEGACY_BANDS;
    private long refinementSampledVoxels;
    private int refinementRegions,refinementCacheHits;
    private int refinementAcceptedCells,refinementRejectedCells;
    private int refinementOneMetre,refinementTwoMetre,refinementFourMetre;
    private long retainedCandidateRefusals;
    private long sourceStateCacheHits,sourceStateCacheMisses,sourceStateCacheResidentBytes;
    private int sourceStateCacheResidents;
    private Map<DetailTile,List<TerrainLodColumns.Box>> detailCache=Map.of();
    private record DetailTile(int x,int minY,int z,int endX,int endY,int endZ,int step) {}
    // Completed raw geometry belongs to immutable world data, not a displayed camera scene.
    // Whole-world light scope is intentional: an edit outside a tile can occlude its sky rays.
    private static final class PreparationReuse {
        final long revision;final String identity;
        final BoundedReuseCache<DetailTile,List<TerrainLodColumns.Box>> geometry=
                new BoundedReuseCache<>(96L*1024*1024,32768,boxes->128L+80L*boxes.size());
        final ExposedSkyCache light;
        // Full requested light must never inherit an8-ray preview value. Exact boxes can
        // survive several prefixes even when adjacent intervals merge in the next scene.
        final BoundedReuseCache<TerrainLodColumns.Box,LightBucket> requestedBoxes=
                new BoundedReuseCache<>(96L*1024*1024,786432,ignored->128L);
        final BoundedReuseCache<TerrainLodColumns.Box,LightBucket> previewBoxes=
                new BoundedReuseCache<>(32L*1024*1024,262144,ignored->128L);
        final java.nio.file.Path tileDirectory;final String tileIdentity;
        volatile String tileStatus="DISABLED",tileWarning="";volatile long tileReadMillis,tileWriteMillis;volatile int restoredTiles;
        PreparationReuse(Mirror mirror,String identity,BaseCache disk,boolean persist){
            this(mirror,identity,disk==null?null:disk.key(),disk==null?null:disk.directory(),persist);
        }
        PreparationReuse(Mirror mirror,String identity,String diskKey,java.nio.file.Path directory,boolean persist){
            this.revision=mirror.geometryRevision();this.identity=identity;this.light=new ExposedSkyCache(mirror.source());
            tileDirectory=persist&&directory!=null?directory.resolve("detail-tiles"):null;
            tileIdentity=TerrainLodDiskCache.identity("raw-detail-v2",diskKey==null?"disabled":diskKey,identity,TerrainLodDiskCache.codeIdentity(TerrainTileDiskCache.class,TerrainLodColumns.class));
            if(tileDirectory!=null){long start=System.nanoTime();var loaded=TerrainTileDiskCache.load(tileDirectory,tileIdentity);tileStatus=loaded.status();
                loaded.tiles().forEach((k,v)->geometry.put(new DetailTile(k.x(),k.y(),k.z(),k.endX(),k.endY(),k.endZ(),k.step()),v));
                restoredTiles=geometry.size();tileReadMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
        }
        void persistTiles(){
            if(tileDirectory==null)return;
            try{long start=System.nanoTime();var tiles=new LinkedHashMap<TerrainTileDiskCache.Key,List<TerrainLodColumns.Box>>();long count=0;
                for(var entry:geometry.snapshot().entrySet()){var k=entry.getKey();var v=entry.getValue();tiles.put(new TerrainTileDiskCache.Key(k.x(),k.minY(),k.z(),k.endX(),k.endY(),k.endZ(),k.step()),v);count+=v.size();}
                var oldest=tiles.entrySet().iterator();while(count>1_000_000&&oldest.hasNext()){count-=oldest.next().getValue().size();oldest.remove();}
                TerrainTileDiskCache.save(tileDirectory,tileIdentity,tiles);tileWriteMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);tileStatus="SAVED";
            }catch(java.io.IOException|RuntimeException failure){tileWarning=failure.toString();}
        }
        boolean matches(Mirror mirror,String id){return revision==mirror.geometryRevision()&&identity.equals(id);}
    }
    private volatile PreparationReuse preparationReuse;
    private String refinementProgressKey="";
    private int refinementProgressLimit,refinementTotalJobs;
    private long progressiveHandoffs,completeDetailRefreshes;
    private Map<String,Long> preparationStages=Map.of();
    private long preparedAtNanos,publicationDelayMillis,preparationQueueMillis;
    private int verifiedPreparationTiles;
    private long preparationTotalMillis,verificationSamples,reusableLightSourceHits,reusableLightSourceMisses;
    private record DetailJob(DetailTile key,List<TerrainLodColumns.Box> cached) {}
    private record DetailResult(DetailTile key,List<TerrainLodColumns.Box> boxes,long samples,boolean reused) {}
    private static final List<TerrainLodRefinement.Band> LEGACY_BANDS=List.of(
            new TerrainLodRefinement.Band(32,1),new TerrainLodRefinement.Band(64,2),
            new TerrainLodRefinement.Band(128,4),new TerrainLodRefinement.Band(256,8));
    private static final List<TerrainLodRefinement.Band> BALANCED_BANDS=List.of(
            new TerrainLodRefinement.Band(48,1),new TerrainLodRefinement.Band(96,2),
            new TerrainLodRefinement.Band(192,4),new TerrainLodRefinement.Band(384,8));
    private static final List<TerrainLodRefinement.Band> HIGH_BANDS=List.of(
            new TerrainLodRefinement.Band(96,1),new TerrainLodRefinement.Band(192,2),
            new TerrainLodRefinement.Band(384,4),new TerrainLodRefinement.Band(512,8));
    private volatile Map<Tile,List<TerrainLodColumns.Box>> baseColumns;
    private volatile long baseSampledVoxels;
    private long refreshSampledVoxels,refreshCount,refreshBuildMillis,publishedVisualRevision=-1,publishedPhysicalGeneration=-1;
    private Mirror latestMirror;
    private Mirror patchedMirror;
    private Map<Tile,List<TerrainLodColumns.Box>> patchedColumns=Map.of();
    private record Tile(int x,int z) {}
    private record Mirror(SparseCubeSpace physical,long generation,long revision,Map<CubeKey,byte[]> cubes,
                          GeneratedSkySource source,long geometryRevision,double x,double y,double z,DeepCubeRenderer.NativeCoverage coverage) {}

    public DhPreviewAdapter() {}
    @Override public void initialize() {
        try {
            shaderShadowState=Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState")
                    .getMethod("areShadowsCurrentlyBeingRendered");
        }catch(ClassNotFoundException absent){shaderShadowState=null;}
        catch(ReflectiveOperationException incompatible){throw new IllegalStateException("Unsupported shader shadow API",incompatible);}
        if(DhApi.getApiMajorVersion()!=7 || DhApi.getApiMinorVersion()<2)
            throw new IllegalStateException("Requires public DH API7.2+ within major7; tested mod3.3.3 only");
        DhApi.events.bind(DhApiAfterDhInitEvent.class,new DhApiAfterDhInitEvent(){
            @Override public void afterDistantHorizonsInit(DhApiEventParam<Void> input){afterInit();}
        });
    }
    private void afterInit() {
        if(initialized || closed)return;
        try {
            if(DhApi.Delayed.worldProxy==null || DhApi.Delayed.customRenderObjectFactory==null || DhApi.Delayed.configs==null)
                throw new IllegalStateException("DH delayed public services missing");
            initialized=true;
            // Same early public gates used by Oculus itself when a pack disables DH shadows.
            // Cancel before generic setup binds the regular G-buffer, not merely before drawing.
            DhApi.events.bind(DhApiBeforeRenderEvent.class,new DhApiBeforeRenderEvent(){
                @Override public void beforeRender(com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam<DhApiRenderParam> input){skipUnsupportedShadowPass(input);}
            });
            DhApi.events.bind(DhApiBeforeDeferredRenderEvent.class,new DhApiBeforeDeferredRenderEvent(){
                @Override public void beforeRender(com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam<DhApiRenderParam> input){skipUnsupportedShadowPass(input);}
            });
            DhApi.events.bind(DhApiBeforeGenericObjectRenderEvent.class,new DhApiBeforeGenericObjectRenderEvent(){
                @Override public void beforeRender(com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam<DhApiBeforeGenericObjectRenderEvent.EventParam> input){
                    if(!active || shaderShadowState==null || !ownsBoxGroup(input.value.boxGroupId))return;
                    try {
                        // Oculus1.8's generic shader writes regular G-buffer attachments even in a
                        // shadow pass. The native far-shadow bridge handles opaque occluders separately;
                        // omit our generic objects here to preserve the main camera's depth/color.
                        if(Boolean.TRUE.equals(shaderShadowState.invoke(null))){shaderShadowObjectsSkipped++;input.cancelEvent();}
                    }catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot query shader shadow pass",failure);}
                }
            });
            DhApi.events.bind(DhApiBeforeRenderSetupEvent.class,new DhApiBeforeRenderSetupEvent(){
                @Override public void beforeSetup(DhApiEventParam<DhApiRenderParam> input){renderSetup(input.value);}
            });
            DhApi.events.bind(DhApiBeforeRenderCleanupEvent.class,new DhApiBeforeRenderCleanupEvent(){
                @Override public void beforeCleanup(DhApiEventParam<DhApiRenderParam> input){scopeActive=false;}
            });
            DhApi.events.bind(DhApiLevelLoadEvent.class,new DhApiLevelLoadEvent(){
                @Override public void onLevelLoad(DhApiEventParam<EventParam> input){levelLoads++;}
            });
            DhApi.events.bind(DhApiLevelUnloadEvent.class,new DhApiLevelUnloadEvent(){
                @Override public void onLevelUnload(DhApiEventParam<EventParam> input){
                    if(active && (input.value.levelWrapper==wrapper || input.value.levelWrapper.getWrappedMcObject()==level)) {
                        long unloadedEpoch=epoch;
                        Minecraft.getInstance().execute(()->{if(epoch==unloadedEpoch)end("DH level unload");});
                    }
                    // Integrated DH also unloads server dimensions. They do not own our client connection lease.
                    // Real Forge LoggingOut (non-null player) restores an idle lease before client-level unload.
                    else ignoredLevelUnloads++;
                }
            });
            eventsBound=true;
            acquireGeneration();phase="IDLE";
        }catch(RuntimeException failure){fail(failure);}
    }
    @Override public void prepareConnection() {
        connectionPreparations++;
        if(!initialized || closed)return;
        try{acquireGeneration();}catch(RuntimeException failure){fail(failure);}
    }
    private void acquireGeneration() {
        generation=DhApi.Delayed.configs.worldGenerator().GeneratorPlan();
        if(generationOwned){
            if(generation.getApiValue()!=EDhApiGeneratorPlan.DISABLED)throw new IllegalStateException("DH generation override ownership lost");
            return;
        }
        if(generation.getApiValue()!=null && generation.getApiValue()!=EDhApiGeneratorPlan.DISABLED)
            throw new IllegalStateException("Foreign DH generation API override: refusing replacement");
        if(generation.getValue()==EDhApiGeneratorPlan.DISABLED)return;
        if(!generation.getCanBeOverrodeByApi() || !generation.setValue(EDhApiGeneratorPlan.DISABLED,DISPLAY_NAME))
            throw new IllegalStateException("Could not disable DH distant generation");
        generationOwned=true;
        if(generation.getValue()!=EDhApiGeneratorPlan.DISABLED)throw new IllegalStateException("DH distant generation is still enabled");
    }
    private void restoreGeneration() {
        if(generationOwned && generation!=null && generation.getApiValue()==EDhApiGeneratorPlan.DISABLED) {
            if(!generation.clearValue())error="Could not restore owned DH generation API value";
        }
        generationOwned=false;
    }
    private void acquireNearCoverage(){
        overdraw=DhApi.Delayed.configs.graphics().overdrawPreventionRadius();
        if(overdraw.getApiValue()!=null && Float.compare(overdraw.getApiValue(),0f)!=0)
            throw new IllegalStateException("Foreign DH overdraw API override");
        if(Float.compare(overdraw.getValue(),0f)==0)return;
        if(!overdraw.getCanBeOverrodeByApi() || !overdraw.setValue(0f,DISPLAY_NAME))
            throw new IllegalStateException("Could not retain detailed LODs inside native host render distance");
        overdrawOwned=true;
    }
    private void restoreNearCoverage(){
        if(overdrawOwned && overdraw!=null && overdraw.getApiValue()!=null && Float.compare(overdraw.getApiValue(),0f)==0)
            if(!overdraw.clearValue())error="Could not restore owned DH overdraw value";
        overdrawOwned=false;
    }
    @Override public JsonObject begin(Minecraft client,JsonObject parameters) {
        if(!initialized || closed)throw new IllegalStateException("DH public services are not ready");
        if(active || groups.size()!=0)throw new IllegalStateException("End and clean the existing DH preview first");
        if(client.level==null || client.player==null)throw new IllegalStateException("No native client level/player");
        try {
            var admission=ClientDeepRuntime.status();
            if(!admission.has("fixture") || !"generated".equals(admission.get("fixture").getAsString())
                    || !admission.has("generatedProfile") || !admission.has("completed") || !admission.get("completed").getAsBoolean()
                    || admission.has("error"))throw new IllegalStateException("Active accepted deep_generated_probe required");
            var admitted=admission.getAsJsonObject("generatedProfile");
            if(!admitted.get("generatedAccepted").getAsBoolean())throw new IllegalStateException("Generated profile not server-admitted");
            var admittedSession=UUID.fromString(admission.get("session").getAsString());
            var admittedFingerprint=admitted.get("fingerprint").getAsString();
            if(!ClientGeneratedLight.admittedProfile(client.level,admittedSession,admitted.get("profileId").getAsString(),admittedFingerprint))
                throw new IllegalStateException("Current validated client/server generated profile required");
            var boundsJson=parameters.getAsJsonArray("bounds");
            if(boundsJson==null || boundsJson.size()!=6)throw new IllegalArgumentException("Explicit six inclusive XYZ bounds required");
            bounds=new TerrainLodColumns.Bounds(boundsJson.get(0).getAsInt(),boundsJson.get(1).getAsInt(),boundsJson.get(2).getAsInt(),
                    boundsJson.get(3).getAsInt(),boundsJson.get(4).getAsInt(),boundsJson.get(5).getAsInt());
            quality=parameters.has("quality")?parameters.get("quality").getAsString():parameters.has("step")?"legacy":"high";
            int presetStep,presetBoxes;long presetSamples;
            switch(quality) {
                case "legacy"->{presetStep=16;presetSamples=25_000_000;presetBoxes=131_072;detailBands=LEGACY_BANDS;detailSampleCap=3_473_408;}
                case "balanced"->{presetStep=8;presetSamples=75_000_000;presetBoxes=300_000;detailBands=BALANCED_BANDS;detailSampleCap=12_000_000;}
                case "high"->{presetStep=8;presetSamples=SAMPLE_CAP;presetBoxes=BOX_CAP;detailBands=HIGH_BANDS;detailSampleCap=60_000_000;}
                case "island-high"->{presetStep=16;presetSamples=SAMPLE_CAP;presetBoxes=BOX_CAP;detailBands=HIGH_BANDS;detailSampleCap=60_000_000;}
                default->throw new IllegalArgumentException("Quality must be legacy, balanced, high or island-high");
            }
            int step=parameters.has("step")?parameters.get("step").getAsInt():presetStep;
            overviewStep="island-high".equals(quality)?32:16;
            if(!"legacy".equals(quality) && step!=presetStep)
                throw new IllegalArgumentException("Quality "+quality+" requires base step "+presetStep+"; explicit step="+step);
            limits=new TerrainLodColumns.Parameters(step,parameters.has("maxSamples")?parameters.get("maxSamples").getAsLong():presetSamples,
                    parameters.has("maxBoxes")?parameters.get("maxBoxes").getAsInt():presetBoxes);
            if(limits.maxSampleVoxels()>presetSamples || limits.maxBoxes()>presetBoxes)
                throw new IllegalArgumentException("Quality "+quality+" cap:"+presetSamples+" aggregate samples/"+presetBoxes+" final boxes");
            nearDistance=parameters.get("nearDistance").getAsDouble();
            refinementEnabled=parameters.has("refinement")?parameters.get("refinement").getAsBoolean():!"legacy".equals(quality);
            long nx=((long)bounds.maxXInclusive()+1-bounds.minX()+step-1)/step;
            long nz=((long)bounds.maxZInclusive()+1-bounds.minZ()+step-1)/step;
            estimatedBaseSamples=Math.multiplyExact(Math.multiplyExact(nx,nz),(long)bounds.maxYInclusive()+1-bounds.minY());
            estimatedDetailSamples=refinementEnabled?refinementPlan(bounds,client.player.getX(),client.player.getY(),client.player.getZ(),
                    DeepCubeRenderer.nativeCoverage(),usesNativeSeam(quality),detailBands,detailSampleCap).sampledVoxels():0;
            estimatedInitialSamples=Math.addExact(estimatedBaseSamples,estimatedDetailSamples);
            if(estimatedInitialSamples>limits.maxSampleVoxels())
                throw new IllegalArgumentException("Quality "+quality+" needs "+estimatedInitialSamples+" initial samples (base="+estimatedBaseSamples
                        +", detail="+estimatedDetailSamples+"); explicit maxSamples="+limits.maxSampleVoxels());
            long overviewX=((long)bounds.maxXInclusive()+1-bounds.minX()+overviewStep-1)/overviewStep;
            long overviewZ=((long)bounds.maxZInclusive()+1-bounds.minZ()+overviewStep-1)/overviewStep;
            long possibleOverview=Math.multiplyExact(Math.multiplyExact(overviewX,overviewZ),(long)bounds.maxYInclusive()+1-bounds.minY());
            overviewRequested=(!parameters.has("earlyOverview")||parameters.get("earlyOverview").getAsBoolean())&&step<overviewStep
                    &&Math.addExact(estimatedInitialSamples,possibleOverview)<=limits.maxSampleVoxels();
            estimatedOverviewSamples=overviewRequested?possibleOverview:0;
            estimatedInitialSamples=Math.addExact(estimatedInitialSamples,estimatedOverviewSamples);
            nearOverviewPlan=overviewRequested?ColdPreviewStages.near(bounds,client.player.getX(),client.player.getY(),client.player.getZ(),
                    Math.min(200_000,limits.maxSampleVoxels()-estimatedInitialSamples)).orElse(null):null;
            nearOverviewEligible=nearOverviewPlan!=null;
            earlyDetailEligible=nearOverviewEligible&&refinementEnabled&&(!parameters.has("reusePreparation")||parameters.get("reusePreparation").getAsBoolean())
                    &&(!parameters.has("progressiveRefinement")||parameters.get("progressiveRefinement").getAsBoolean());
            if(nearOverviewEligible)estimatedInitialSamples=Math.addExact(estimatedInitialSamples,nearOverviewPlan.samples());
            if(earlyDetailEligible&&!ColdPreviewStages.earlyFitsBudget(estimatedInitialSamples-estimatedDetailSamples,detailSampleCap,
                    ColdPreviewStages.MAX_EARLY_DETAIL_TILES,limits.maxSampleVoxels()))earlyDetailEligible=false;
            if(!Double.isFinite(nearDistance) || nearDistance<1 || nearDistance>512)throw new IllegalArgumentException("Explicit nearDistance1..512 required");
            if(DhApi.Delayed.configs.graphics().disableFrustumCulling().getValue())throw new IllegalStateException("DH frustum culling must be enabled; settings not changed");
            acquireGeneration();acquireNearCoverage();
            level=client.level;session=admission.get("session").getAsString();sessionToken=admittedSession;fingerprint=admittedFingerprint;
            requested=parameters.deepCopy();error="";active=true;phase="BUILDING_BASE";
            refreshInFlight=false;refinementPending=refinementEnabled;pendingRefined=false;
            refinementProgressKey="";refinementProgressLimit=refinementTotalJobs=0;progressiveHandoffs=completeDetailRefreshes=0;
            overviewPending=overviewRequested;pendingOverview=false;overviewSampledVoxels=overviewBuildMillis=0;overviewFirstDisplayMillis=firstRequestedDisplayMillis=-1;
            firstPartialDetailDisplayMillis=firstVisibleDetailDisplayMillis=-1;pendingPreviewBackdrop=false;pendingRequestedDetailBoxes=0;fullPreviewBackdrop=nearPreviewBackdrop=null;
            earlyDetail=null;earlyFreshTiles=Map.of();pendingNearDetail=false;earlyDetailSampledVoxels=earlyDetailGeometryMillis=earlyDetailBuildMillis=0;firstNearDetailMillis=-1;
            pendingEarlyDetailPreserved=false;earlyChangedPlanSampleReserve=retainedEarlyDetailPromotions=earlyDetailRefusals=earlyDetailDowngrades=earlyDetailPlanConflicts=0;
            earlyDetailPreparedJobs=earlyDetailSelectedJobs=earlyDetailRawAcceptedTiles=earlyDetailVisibleBoxes=0;
            pendingNearOverview=false;nearOverviewSampledVoxels=nearOverviewBuildMillis=0;firstNearMillis=firstFullOverviewMillis=-1;
            startupStages=new ColdPreviewStages(nearOverviewEligible,earlyDetailEligible);nearOverviewAck=nearOverviewEligible?new CompletableFuture<>():null;
            nearDetailAck=earlyDetailEligible?new CompletableFuture<>():null;
            if(!overviewRequested)startupStages.bypass();
            beginNanos=System.nanoTime();firstDisplayMillis=-1;deferredMovementRefreshes=0;
            boxLightCache=Map.of();boxLightGeometryRevision=-1;
            exposedSkyCacheHits=0;exposedSkyEvaluations=0;exposedSkyCachedProbes=0;
            boxLightSpatialOrderEnabled=Boolean.parseBoolean(System.getProperty("mia.dh.boxLightSpatialOrder","false"));
            boxLightSpatialOrderMode="ORIGINAL";boxLightSpatialOrderedJobs=0;boxLightSpatialOrderingNanos=0;
            verifiedBoxLights=0;verifiedPreparationTiles=0;preparationReuse=null;preparationStages=Map.of();preparedAtNanos=0;publicationDelayMillis=preparationQueueMillis=0;
            reusedCumulativeBoxLights=reusedPreviewBoxLights=0;
            baseBuildMillis=0;detailGeometryBuildMillis=0;
            baseDiskCache=null;diskCacheStatus="NOT_STARTED";diskCacheWarning="";diskCacheReadMillis=0;diskCacheWriteMillis=0;
            finalDiskCacheStatus="NOT_STARTED";finalDiskCacheWarning="";finalDiskCacheReadMillis=0;finalDiskCacheWriteMillis=0;
            shadowLightCache=Map.of();shadowLightGeometryRevision=-1;
            sampledVoxels=0;sourceBoxes=0;nearCulledBoxes=0;mirrorCulledBoxes=0;renderCallbacks=0;preRenderCallbacks=0;terrainCulls=0;
            handoffCount=0;retainedRefreshCount=0;maximumOwnedGroups=0;maximumOwnedScenes=0;stagedDrawCallbacks=0;missingDisplayedRefreshFrames=0;publishedGeometryRevision=-1;
            baseSampledVoxels=0;refreshSampledVoxels=0;refreshCount=0;baseColumns=null;patchedMirror=null;patchedColumns=Map.of();detailCache=Map.of();publishedVisualRevision=-1;publishedPhysicalGeneration=-1;
            long life=++lifetime;
            worker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{
                var t=new Thread(r,"mia-dh-preview-builder");t.setDaemon(true);t.setPriority(AbyssLodPipeline.BACKGROUND_PRIORITY);return t;
            });
            lightWorkers=Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-2));
            var lightThreadNumber=new java.util.concurrent.atomic.AtomicInteger();
            lightWorker=new ThreadPoolExecutor(lightWorkers,lightWorkers,0L,TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(4),r->{
                var t=new Thread(r,"mia-dh-preview-light-"+lightThreadNumber.incrementAndGet());t.setDaemon(true);t.setPriority(AbyssLodPipeline.BACKGROUND_PRIORITY);return t;
            });
            final var baseBounds=bounds;final var baseLimits=limits;
            final var baseSource=ClientGeneratedLight.source();
            final String baseFingerprint=fingerprint;
            final var baseComputeWorker=lightWorker;final int baseComputeWorkers=lightWorkers;
            final var cacheDirectory=System.getProperty("mia.dh.previewCacheDirectory")==null
                    ?client.gameDirectory.toPath().toAbsolutePath().normalize().getParent().getParent().resolve("cache/dh-preview")
                    :java.nio.file.Path.of(System.getProperty("mia.dh.previewCacheDirectory")).toAbsolutePath().normalize();
            if(baseSource==null)throw new IllegalStateException("Verified generated geometry source required");
            if("island-high".equals(quality)&&!baseSource.hasIslandSurface())throw new IllegalArgumentException("Island quality needs the versioned island generator");
            if(parameters.has("oceanHorizonRadius")) {
                if(!baseSource.hasIslandSurface())throw new IllegalArgumentException("Ocean horizon needs verified island geometry");
                dev.madeinabyss.kernel.lod.OceanHorizon.plan(bounds,parameters.get("oceanHorizonRadius").getAsInt(),baseSource.oceanLevelY());
            }
            final Mirror overviewMirror=overviewRequested?captureCurrent(client):null;
            final long overviewRun=overviewRequested?++epoch:0;
            final var startupNearPlan=nearOverviewPlan;final var startupAck=nearOverviewAck;final var startupDetailAck=nearDetailAck;
            final boolean prepareEarlyDetail=earlyDetailEligible;
            if(overviewRequested){latestMirror=overviewMirror;physical=overviewMirror.physical();physicalGeneration=overviewMirror.generation();
                visualRevision=overviewMirror.revision();capturedCubes=overviewMirror.cubes().size();refreshInFlight=true;groups.beginTarget(overviewRun);}
            worker.submit(()->{
                try {
                    long baseStarted=System.nanoTime();
                    var sourceIdentity=baseSource.cacheGeometryIdentity();
                    var rendererCodeIdentity=TerrainLodDiskCache.codeIdentity(DhPreviewAdapter.class,GeneratedSkySource.class,GeneratedSkySource.SkyExposure.class,dev.madeinabyss.kernel.cube.ExactXYZStateCache.class,dev.madeinabyss.kernel.light.PagedOpacityCache.class,
                            dev.madeinabyss.forge.worldgen.GeneratedIslandAirCertificate.class,dev.madeinabyss.forge.worldgen.GeneratedDeepAirCertificate.class,dev.madeinabyss.kernel.light.DeepCavityAirBounds.class,
                            dev.madeinabyss.forge.worldgen.GeneratedDeepAirCellCertificate.class,dev.madeinabyss.kernel.light.DeepCavityAirCellBounds.class,
                            TerrainLodDiskCache.class,TerrainLodColumns.class,TerrainLodParallelBuild.class,TerrainLodClipper.class,TerrainLodRefinement.class,NativeSeamRefinement.class,ColdPreviewStages.class,dev.madeinabyss.kernel.lod.GeometryPatchChanges.class,TerrainLodVerticalMerge.class,TerrainLodBudgetRefinement.class,ProgressiveRefinementOverlay.class,BoxLightWorkOrder.class,BoundedOrderedWork.class,BoundedReuseCache.class,
                            dev.madeinabyss.kernel.light.SkyVisibility.class,dev.madeinabyss.kernel.light.QuantizedDiffuseSky.class,dev.madeinabyss.kernel.light.LodBoxSkyLight.class,dev.madeinabyss.kernel.terrain.CompiledTerrainSampler.class,dev.madeinabyss.forge.worldgen.ParameterizedTerrainChunkGenerator.class,
                            dev.madeinabyss.forge.worldgen.HabitatBlockStates.class);
                    var codeIdentity=TerrainLodDiskCache.identity("dh-opacity-proof-v18",rendererCodeIdentity,
                            dev.madeinabyss.forge.worldgen.GeneratedCaveOpacityProjection.proofCodeIdentity());
                    checkLife(life);
                    var cacheKey=TerrainLodDiskCache.identity("dh-base-light-v3-tile-fallback",baseFingerprint,sourceIdentity,codeIdentity,baseBounds.toString(),baseLimits.toString());
                    long readStarted=System.nanoTime();var cached=TerrainLodDiskCache.load(cacheDirectory,cacheKey,baseBounds,baseLimits);
                    checkLife(life);
                    diskCacheReadMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-readStarted);diskCacheStatus=cached.status();
                    if(cached.status().startsWith("INVALID"))diskCacheWarning=cached.status();
                    Mirror startupMirror=overviewMirror;long fullRun=overviewRun;
                    if(overviewRequested) {
                        if(cached.entry().isEmpty()) {
                            if(startupNearPlan!=null) {
                                buildOverview(client,overviewMirror,overviewRun,life,baseComputeWorkers,baseComputeWorker,startupNearPlan.bounds(),true);
                                var acknowledged=awaitStartup(startupAck,life);fullRun=acknowledged.run();startupMirror=acknowledged.mirror();
                            }
                            if(!prepareEarlyDetail)buildOverview(client,startupMirror,fullRun,life,baseComputeWorkers,baseComputeWorker,baseBounds,false);
                        } else client.execute(()->{if(active&&lifetime==life){if(startupStages.guarded())startupStages.bypass();overviewPending=false;refreshInFlight=false;queueRefresh(client);}});
                    }
                    var base=cached.entry().isPresent()?cached.entry().get().base():TerrainLodParallelBuild.build((x,y,z)->{
                        checkLife(life);
                        return new TerrainSample.Known(material(Block.stateById(baseSource.stateIdForDerivedGeometry(x,y,z))));
                    },baseBounds,baseLimits,baseComputeWorkers,baseComputeWorker);
                    var loadedLights=new HashMap<TerrainLodColumns.Box,LightBucket>();
                    cached.entry().ifPresent(entry->entry.lights().forEach((box,light)->loadedLights.put(box,new LightBucket(light.sky(),light.block()))));
                    var byTile=new LinkedHashMap<Tile,List<TerrainLodColumns.Box>>();
                    for(var box:base.boxes())byTile.computeIfAbsent(new Tile((int)box.minX(),(int)box.minZ()),ignored->new ArrayList<>()).add(box);
                    var immutable=new LinkedHashMap<Tile,List<TerrainLodColumns.Box>>();byTile.forEach((key,value)->immutable.put(key,List.copyOf(value)));
                    synchronized(this){checkLife(life);baseDiskCache=new BaseCache(cacheKey,sourceIdentity,base,Collections.unmodifiableMap(loadedLights),cacheDirectory,cached.entry().isPresent());baseBuildMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-baseStarted);baseColumns=Collections.unmodifiableMap(immutable);baseSampledVoxels=base.sampledVoxels();}
                    if(prepareEarlyDetail&&cached.entry().isEmpty()) {
                        buildEarlyDetail(client,startupMirror,fullRun,life,baseComputeWorkers,baseComputeWorker,base,cacheKey,cacheDirectory);
                        var acknowledged=awaitStartup(startupDetailAck,life);
                        buildOverview(client,acknowledged.mirror(),acknowledged.run(),life,baseComputeWorkers,baseComputeWorker,baseBounds,false);
                    }
                }catch(CancellationException cancelled){
                    client.execute(()->resumeCancelledStartup(client,life));
                }
                catch(Exception failure){client.execute(()->{if(active && lifetime==life)fail(failure);});}
            });
            if(!overviewRequested)queueRefresh(client);
            return status();
        }catch(Exception failure){fail(failure);throw new IllegalStateException("DH generated preview refused",failure);}
    }
    private void checkLife(long life) {
        if(Thread.currentThread().isInterrupted() || !active || lifetime!=life)throw new CancellationException("Preview lifetime ended");
    }
    private static List<TerrainLodClipper.BoundsLong> coverageExclusions(DeepCubeRenderer.NativeCoverage coverage) {
        var exclusions=new ArrayList<TerrainLodClipper.BoundsLong>();
        if(coverage!=null&&coverage.complete()) {
            double safeNear=Math.max(0,coverage.radius()/Math.sqrt(2)-2);
            long x0=(long)Math.ceil(coverage.anchorX()-safeNear),x1=(long)Math.floor(coverage.anchorX()+safeNear);
            long z0=(long)Math.ceil(coverage.anchorZ()-safeNear),z1=(long)Math.floor(coverage.anchorZ()+safeNear);
            if(x0<x1&&z0<z1&&coverage.minY()<coverage.maxY())exclusions.add(new TerrainLodClipper.BoundsLong(x0,coverage.minY(),z0,x1,coverage.maxY(),z1));
        }
        return exclusions;
    }
    /** Cold-start overview uses authoritative geometry/direct SKY with temporary8-ray diffuse.
     * Its 16m approximation is replaced by the requested geometry and full32-ray lighting. */
    private StartupAcknowledgement awaitStartup(CompletableFuture<StartupAcknowledgement> acknowledgement,long life) throws Exception {
        while(true) {
            checkLife(life);
            try{var result=acknowledgement.get(100,TimeUnit.MILLISECONDS);if(result.run()<0)throw new CancellationException("Startup acknowledgement cancelled");return result;}
            catch(TimeoutException pending){/* Only this worker waits for the owner upload acknowledgement. */}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("Near preview interrupted");}
        }
    }
    private void cancelStartup() {
        if(startupStages!=null)startupStages.cancel();
        var cancelled=new StartupAcknowledgement(null,-1);
        if(nearOverviewAck!=null)nearOverviewAck.complete(cancelled);
        if(nearDetailAck!=null)nearDetailAck.complete(cancelled);
    }
    private void clearEarlyCertificate() {
        earlyDetail=null;earlyFreshTiles=Map.of();nearPreviewBackdrop=null;
    }
    private void resumeCancelledStartup(Minecraft client,long life) {
        if(!active||lifetime!=life)return;
        // A newer normal target already owns cancellation once startup was released.
        if(baseColumns!=null&&(startupStages==null||!startupStages.guarded()))return;
        try {
            requireSession(client);cancelStartup();overviewPending=false;refreshInFlight=false;
            pendingBoxes=null;pendingGeometry=List.of();pendingShadowSky=Map.of();pendingOverview=false;
            pendingNearOverview=false;pendingNearDetail=false;pendingEarlyDetailPreserved=false;pendingPreviewBackdrop=false;pendingRequestedDetailBoxes=0;
            clearEarlyCertificate();
            if(baseColumns==null) {
                // Cancellation before the unlit base exists cannot queue a patch against
                // a nonexistent base. Re-admit the same request against the current source.
                var retry=requested.deepCopy();end("startup source changed before base");begin(client,retry);return;
            }
            queueRefresh(client);
        }catch(RuntimeException failure){fail(failure);}
    }
    private StartupAcknowledgement nextStartupTarget(Minecraft client) {
        requireSession(client);var mirror=captureCurrent(client);
        if(latestMirror!=null&&latestMirror.geometryRevision()!=mirror.geometryRevision()) {
            cancelStartup();clearEarlyCertificate();overviewPending=false;refreshInFlight=false;queueRefresh(client);return new StartupAcknowledgement(null,-1);
        }
        latestMirror=mirror;physical=mirror.physical();physicalGeneration=mirror.generation();visualRevision=mirror.revision();capturedCubes=mirror.cubes().size();
        long run=++epoch;retireGroup(groups.beginTarget(run));return new StartupAcknowledgement(mirror,run);
    }
    private static String detailPlanIdentity(Mirror mirror,String identity,String quality) {
        return identity+"/"+mirror.geometryRevision()+"/"+quality+"/"+coverageShape(mirror.coverage())+"/"
                +anchor(mirror.x())+"/"+anchor(mirror.y())+"/"+anchor(mirror.z());
    }
    private static double detailDistance(TerrainLodClipper.BoundsLong b,Mirror mirror) {
        double dx=Math.max(0,Math.max(b.minX()-mirror.x(),mirror.x()-b.maxXExclusive()));
        double dy=Math.max(0,Math.max(b.minY()-mirror.y(),mirror.y()-b.maxYExclusive()));
        double dz=Math.max(0,Math.max(b.minZ()-mirror.z(),mirror.z()-b.maxZExclusive()));return dx*dx+dy*dy+dz*dz;
    }
    private List<DetailJob> startupDetailJobs(TerrainLodRefinement.Plan plan,Mirror mirror,PreparationReuse reuse,long life,long run) {
        var jobs=new ArrayList<DetailJob>();
        for(var tile:ColdPreviewStages.detailPrefix(plan,mirror.x(),mirror.y(),mirror.z(),ColdPreviewStages.MAX_EARLY_DETAIL_TILES)) {
            checkLife(life);if(epoch!=run)throw new CancellationException();var b=tile.bounds();
            var key=new DetailTile(b.minX(),b.minY(),b.minZ(),b.maxXInclusive(),b.maxYInclusive(),b.maxZInclusive(),tile.horizontalStep());
            jobs.add(new DetailJob(key,reuse.geometry.get(key)));
        }
        return jobs;
    }
    private void buildEarlyDetail(Minecraft client,Mirror mirror,long run,long life,int workers,ExecutorService computeWorker,
            TerrainLodColumns.Result base,String cacheKey,java.nio.file.Path cacheDirectory) {
        long start=System.nanoTime();checkLife(life);
        if(epoch!=run||ClientGeneratedLight.geometryRevision()!=mirror.geometryRevision())throw new CancellationException("Startup source changed");
        String identity=mirror.source().cacheGeometryIdentity();
        var reuse=new PreparationReuse(mirror,identity,cacheKey,cacheDirectory,!requested.has("sceneCache")||requested.get("sceneCache").getAsBoolean());
        var near=nearPreviewBackdrop;
        if(near==null||!near.matches(life,mirror,identity))throw new CancellationException("Near raw backdrop invalidated");
        near.lights().forEach(reuse.previewBoxes::put);preparationReuse=reuse;
        var plan=refinementPlan(bounds,mirror.x(),mirror.y(),mirror.z(),mirror.coverage(),usesNativeSeam(quality),detailBands,
                Math.min(limits.maxSampleVoxels(),detailSampleCap));
        long required=Math.addExact(Math.addExact(Math.addExact(base.sampledVoxels(),estimatedOverviewSamples),nearOverviewSampledVoxels),plan.sampledVoxels());
        if(required>limits.maxSampleVoxels())throw new TerrainLodColumns.BudgetExceededException("Early terminal certificate exceeds aggregate sample budget");
        var jobs=startupDetailJobs(plan,mirror,reuse,life,run);var fragments=new java.util.concurrent.atomic.AtomicLong(base.boxes().size());
        earlyDetailPreparedJobs=jobs.size();
        var results=BoundedOrderedWork.map(jobs,workers,computeWorker,job->{
            checkLife(life);if(epoch!=run)throw new CancellationException();
            var key=job.key();var boxes=job.cached();long fresh=0;
            if(boxes==null) {
                var rb=new TerrainLodColumns.Bounds(key.x(),key.minY(),key.z(),key.endX(),key.endY(),key.endZ());
                var built=TerrainLodColumns.build((x,y,z)->{
                    checkLife(life);if(epoch!=run)throw new CancellationException();
                    var saved=mirror.cubes().get(CubeKey.fromBlock(new BlockCoordinate(x,y,z)));
                    var mat=saved==null?material(Block.stateById(mirror.source().stateIdForDerivedGeometry(x,y,z))):
                            KNOWN_MATERIALS[saved[(Math.floorMod(y,16)<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16)]];
                    return new TerrainSample.Known(mat);
                },rb,new TerrainLodColumns.Parameters(key.step(),limits.maxSampleVoxels(),limits.maxBoxes()));
                boxes=built.boxes();fresh=built.sampledVoxels();reuse.geometry.put(key,boxes);
            }
            if(fragments.addAndGet(boxes.size())>8L*limits.maxBoxes())throw new TerrainLodColumns.BudgetExceededException("Early terminal geometry fragments exceeded budget");
            return new DetailResult(key,boxes,fresh,job.cached()!=null);
        });
        var tiles=new ArrayList<TerrainLodBudgetRefinement.Tile>();var freshTiles=new LinkedHashMap<DetailTile,Long>();long fresh=0;
        var exclusions=coverageExclusions(mirror.coverage());
        for(var result:results) {
            var k=result.key();fresh=Math.addExact(fresh,result.samples());if(result.samples()>0)freshTiles.put(k,result.samples());
            tiles.add(new TerrainLodBudgetRefinement.Tile(new TerrainLodClipper.BoundsLong(k.x(),k.minY(),k.z(),(long)k.endX()+1,(long)k.endY()+1,(long)k.endZ()+1),result.boxes(),k.step(),exclusions));
        }
        var visibleBase=TerrainLodClipper.subtract(base.boxes(),exclusions,limits.maxBoxes());
        // Both planners partition disjoint slabs. Later candidates add no initial
        // geometry and never revisit these near-first admissions. The full exact
        // base therefore certifies this bounded prefix's grids for the same plan.
        var terminal=TerrainLodBudgetRefinement.select(visibleBase,tiles,mirror.x(),mirror.y(),mirror.z(),limits.maxBoxes());
        var accepted=new ArrayList<>(terminal.rawAcceptedReplacements());
        accepted.sort(Comparator.<TerrainLodBudgetRefinement.Tile>comparingDouble(tile->detailDistance(tile.bounds(),mirror))
                .thenComparingLong(tile->tile.bounds().minX()).thenComparingLong(tile->tile.bounds().minZ())
                .thenComparingLong(tile->tile.bounds().minY()).thenComparingLong(tile->tile.bounds().maxYExclusive()).thenComparingInt(TerrainLodBudgetRefinement.Tile::horizontalStep));
        var pins=List.copyOf(accepted.subList(0,Math.min(ColdPreviewStages.MAX_EARLY_DETAIL_TILES,accepted.size())));
        earlyDetailSelectedJobs=earlyDetailRawAcceptedTiles=pins.size();
        earlyFreshTiles=Map.copyOf(freshTiles);earlyDetailSampledVoxels=fresh;
        earlyDetailGeometryMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        if(pins.isEmpty()) {
            client.execute(()->{if(active&&lifetime==life&&epoch==run){startupStages.skipNearDetail();nearDetailAck.complete(new StartupAcknowledgement(captureCurrent(client),run));}});
            return;
        }
        var selection=ProgressiveRefinementOverlay.rebase(near.raw(),pins,List.of(),exclusions,mirror.x(),mirror.y(),mirror.z(),limits.maxBoxes())
                .orElseThrow(()->new TerrainLodColumns.BudgetExceededException("Certified early detail does not fit near backdrop"));
        earlyDetailVisibleBoxes=selection.detail().size();
        var clipped=selection.boxes();var previewKeys=Set.copyOf(selection.background());
        var previewExposed=new ExposedSkyCache(mirror.source(),true);
        var lights=BoundedOrderedWork.map(clipped,workers,computeWorker,box->{
            checkLife(life);if(epoch!=run)throw new CancellationException();
            var light=previewKeys.contains(box)?reuse.previewBoxes.get(box):reuse.requestedBoxes.get(box);
            if(light==null){light=boxLight(mirror.source(),box,previewKeys.contains(box)?previewExposed:reuse.light);
                if(previewKeys.contains(box))reuse.previewBoxes.put(box,light);else reuse.requestedBoxes.put(box,light);}
            return light;
        });
        var rendered=new LinkedHashMap<LightBucket,List<DhApiRenderableBox>>();
        for(int i=0;i<clipped.size();i++)rendered.computeIfAbsent(lights.get(i),ignored->new ArrayList<>()).add(renderBox(clipped.get(i),mirror.source()));
        appendOceanHorizon(rendered,mirror,clipped.size());
        var immutable=new LinkedHashMap<LightBucket,List<DhApiRenderableBox>>();rendered.forEach((key,value)->immutable.put(key,List.copyOf(value)));
        var shadowProxy=TerrainLodClipper.subtract(near.raw(),exclusions,limits.maxBoxes());var sky=new HashMap<TerrainLodColumns.Box,Integer>();
        var shadowLights=BoundedOrderedWork.map(shadowProxy,workers,computeWorker,box->{checkLife(life);if(epoch!=run)throw new CancellationException();
            var light=reuse.previewBoxes.get(box);if(light==null){light=boxLight(mirror.source(),box,previewExposed);reuse.previewBoxes.put(box,light);}return light;});
        for(int i=0;i<shadowProxy.size();i++)sky.put(shadowProxy.get(i),shadowLights.get(i).sky());
        earlyDetail=new EarlyDetail(life,mirror.geometryRevision(),identity,detailPlanIdentity(mirror,identity,quality),pins);
        earlyDetailBuildMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);final long[] extents=yExtents(clipped);
        client.execute(()->{
            if(!active||lifetime!=life||epoch!=run)return;
            if(ClientGeneratedLight.geometryRevision()!=mirror.geometryRevision()){cancelStartup();clearEarlyCertificate();overviewPending=false;refreshInFlight=false;queueRefresh(client);return;}
            pendingBoxes=Collections.unmodifiableMap(immutable);pendingEpoch=run;publishedYExtents=extents;
            pendingGeometry=shadowProxy;pendingShadowSky=Collections.unmodifiableMap(sky);
            pendingOverview=false;pendingNearOverview=false;pendingNearDetail=true;pendingRefined=false;pendingPreviewBackdrop=true;
            pendingEarlyDetailPreserved=true;
            pendingRequestedDetailBoxes=selection.detail().size();sourceBoxes=clipped.size();phase="WAITING_FOR_NEAR_DETAIL_REGISTER";
        });
    }
    private void buildOverview(Minecraft client,Mirror mirror,long run,long life,int workers,ExecutorService computeWorker,
                               TerrainLodColumns.Bounds buildBounds,boolean near) {
        long start=System.nanoTime();var overviewLimits=new TerrainLodColumns.Parameters(near?16:overviewStep,limits.maxSampleVoxels(),limits.maxBoxes());
        var geometry=TerrainLodParallelBuild.build((x,y,z)->{
            checkLife(life);var saved=mirror.cubes().get(CubeKey.fromBlock(new BlockCoordinate(x,y,z)));
            var mat=saved==null?material(Block.stateById(mirror.source().stateIdForDerivedGeometry(x,y,z))):
                    KNOWN_MATERIALS[saved[(Math.floorMod(y,16)<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16)]];
            return new TerrainSample.Known(mat);
        },buildBounds,overviewLimits,workers,computeWorker);
        var exclusions=coverageExclusions(mirror.coverage());
        var visibleRaw=TerrainLodClipper.subtract(geometry.boxes(),exclusions,limits.maxBoxes());
        var retained=earlyDetail;var reuse=preparationReuse;String identity=mirror.source().cacheGeometryIdentity();
        var selection=!near&&retained!=null&&retained.matches(life,mirror,identity)
                ?ProgressiveRefinementOverlay.rebase(geometry.boxes(),retained.raw(),List.of(),exclusions,mirror.x(),mirror.y(),mirror.z(),limits.maxBoxes())
                    .orElseThrow(()->new TerrainLodColumns.BudgetExceededException("Full preview cannot retain certified early detail")):null;
        var clipped=selection==null?visibleRaw:selection.boxes();var previewKeys=Set.copyOf(selection==null?clipped:selection.background());
        var exposed=new ExposedSkyCache(mirror.source(),true);
        var lights=BoundedOrderedWork.map(clipped,workers,computeWorker,box->{
            checkLife(life);if(epoch!=run)throw new CancellationException();
            boolean preview=previewKeys.contains(box);var light=reuse==null?null:preview?reuse.previewBoxes.get(box):reuse.requestedBoxes.get(box);
            if(light==null){light=boxLight(mirror.source(),box,preview?exposed:reuse.light);
                if(reuse!=null){if(preview)reuse.previewBoxes.put(box,light);else reuse.requestedBoxes.put(box,light);}}
            return light;
        });
        var rendered=new LinkedHashMap<LightBucket,List<DhApiRenderableBox>>();var sky=new HashMap<TerrainLodColumns.Box,Integer>();
        var backdropLights=new HashMap<TerrainLodColumns.Box,LightBucket>();
        for(int i=0;i<clipped.size();i++){var box=clipped.get(i);var light=lights.get(i);rendered.computeIfAbsent(light,k->new ArrayList<>()).add(renderBox(box,mirror.source()));if(previewKeys.contains(box))backdropLights.put(box,light);}
        var proxyLights=BoundedOrderedWork.map(visibleRaw,workers,computeWorker,box->{
            checkLife(life);if(epoch!=run)throw new CancellationException();
            var light=reuse==null?backdropLights.get(box):reuse.previewBoxes.get(box);
            if(light==null){light=boxLight(mirror.source(),box,exposed);if(reuse!=null)reuse.previewBoxes.put(box,light);}return light;
        });
        for(int i=0;i<visibleRaw.size();i++){sky.put(visibleRaw.get(i),proxyLights.get(i).sky());backdropLights.put(visibleRaw.get(i),proxyLights.get(i));}
        appendOceanHorizon(rendered,mirror,clipped.size());
        var immutable=new LinkedHashMap<LightBucket,List<DhApiRenderableBox>>();rendered.forEach((key,value)->immutable.put(key,List.copyOf(value)));
        if(near){nearOverviewSampledVoxels=geometry.sampledVoxels();nearOverviewBuildMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
        else {overviewSampledVoxels=geometry.sampledVoxels();overviewBuildMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
        final long cacheHits=mirror.source().stateCacheHits(),cacheMisses=mirror.source().stateCacheMisses();
        final int cacheResidents=mirror.source().residentStateCaches();
        checkLife(life);
        var backdrop=new FullPreviewBackdrop(life,mirror.geometryRevision(),identity,geometry.boxes(),backdropLights);
        if(near)nearPreviewBackdrop=backdrop;else fullPreviewBackdrop=backdrop;
        final long[] overviewYExtents=yExtents(clipped);
        client.execute(()->{
            if(!active||lifetime!=life||epoch!=run)return;
            if(ClientGeneratedLight.geometryRevision()!=mirror.geometryRevision()){
                cancelStartup();clearEarlyCertificate();
                overviewPending=false;refreshInFlight=false;queueRefresh(client);return;
            }
            pendingBoxes=Collections.unmodifiableMap(immutable);pendingEpoch=run;publishedYExtents=overviewYExtents;
            pendingGeometry=visibleRaw;pendingShadowSky=Collections.unmodifiableMap(sky);pendingOverview=true;pendingNearOverview=near;pendingNearDetail=false;pendingRefined=false;pendingPreviewBackdrop=selection!=null;pendingRequestedDetailBoxes=selection==null?0:selection.detail().size();
            pendingEarlyDetailPreserved=selection!=null;
            sourceBoxes=clipped.size();phase=near?"WAITING_FOR_NEAR_PREVIEW_REGISTER":"WAITING_FOR_OVERVIEW_REGISTER";
            sourceStateCacheHits=cacheHits;sourceStateCacheMisses=cacheMisses;sourceStateCacheResidents=cacheResidents;
            sourceStateCacheResidentBytes=cacheResidents*(long)dev.madeinabyss.kernel.cube.ExactXYZStateCache.MAX_CAPACITY*16;
        });
    }
    private Mirror captureCurrent(Minecraft client) {
        var nextPhysical=DeepCubeDomain.space(level).orElseThrow(()->new IllegalStateException("No authoritative physical mirror"));
        var visualStatus=ClientDeepVisual.status();
        if(!visualStatus.has("completed") || !visualStatus.get("completed").getAsBoolean() || visualStatus.has("error"))
            throw new IllegalStateException("Current complete visual mirror required");
        var captured=new HashMap<CubeKey,byte[]>();
        capture(ClientDeepVisual.space(level).orElseThrow(),captured);capture(nextPhysical,captured);
        var source=ClientGeneratedLight.source();
        if(source==null)throw new IllegalStateException("Verified full saved geometry source required");
        return new Mirror(nextPhysical,nextPhysical.contentGeneration(),ClientDeepVisual.revision(),Map.copyOf(captured),
                source,ClientGeneratedLight.geometryRevision(),client.player.getX(),client.player.getY(),client.player.getZ(),DeepCubeRenderer.nativeCoverage());
    }
    private void queueRefresh(Minecraft client) {
        requireSession(client);
        // Packet observations are compared with exact procedural data off the render thread.
        // Keep the committed scene until its new source revision is authoritative.
        if(ClientGeneratedLight.geometryObservationsPending()){deferredMovementRefreshes++;return;}
        // A newly published source may not yet have its worker-built identity. Do not
        // cancel a target or initialize procedural digests on the render thread.
        var currentSource=ClientGeneratedLight.source();
        final String targetIdentity=currentSource==null?null:currentSource.cachedGeometryIdentity();
        if(targetIdentity==null){deferredMovementRefreshes++;return;}
        // The startup coordinator owns both the overview and the following full-base build.
        // Movement cannot clear/cancel this queue before the first immutable overview handoff.
        if(overviewPending) {
            if(latestMirror!=null&&latestMirror.geometryRevision()!=ClientGeneratedLight.geometryRevision()) {
                cancelStartup();clearEarlyCertificate();overviewPending=false;
            }else {deferredMovementRefreshes++;return;}
        }
        // The cold worker still owns unlit base construction. A render-frame retry
        // can queue current saved patches once that immutable base has finished.
        if(baseColumns==null){deferredMovementRefreshes++;return;}
        // Movement must not starve publication. Finish and upload the current immutable
        // scene, then follow the newest camera. Actual geometry edits still invalidate it.
        if(refreshInFlight && latestMirror!=null
                && latestMirror.geometryRevision()==ClientGeneratedLight.geometryRevision()) {
            deferredMovementRefreshes++;return;
        }
        refreshInFlight=true;
        if(groups.displayed()!=null)retainedRefreshCount++;
        pendingBoxes=null;
        pendingNearOverview=false;
        pendingNearDetail=false;
        pendingEarlyDetailPreserved=false;
        pendingPreviewBackdrop=false;
        pendingRequestedDetailBoxes=0;
        pendingGeometry=List.of();
        pendingShadowSky=Map.of();
        Mirror mirror=captureCurrent(client);latestMirror=mirror;physical=mirror.physical();physicalGeneration=mirror.generation();visualRevision=mirror.revision();capturedCubes=mirror.cubes().size();
        long run=++epoch,life=lifetime;
        retireGroup(groups.beginTarget(run));
        if(task!=null)task.cancel(true);
        // One running base/patch and one latest pending patch; cancelled queued targets do not accumulate.
        ((ThreadPoolExecutor)worker).getQueue().clear();
        phase=baseColumns==null?"BUILDING_BASE":"REFRESHING";
        final var sourceBounds=bounds;final var sourceLimits=limits;final double clipDistance=nearDistance;
        final var previousMirror=patchedMirror;final var previousPatches=patchedColumns;
        final var previousDetail=detailCache;
        // A complete cold panorama already covers the world. Start local requested detail
        // directly over that backdrop; the terminal candidate retains the original base.
        final boolean requestedRefine=refinementEnabled && groups.displayed()!=null;
        final var currentLights=boxLightGeometryRevision==mirror.geometryRevision()?boxLightCache:Map.<TerrainLodColumns.Box,LightBucket>of();
        final var currentShadowLights=shadowLightGeometryRevision==mirror.geometryRevision()?shadowLightCache:Map.<TerrainLodColumns.Box,LightBucket>of();
        final var sourceBands=detailBands;final long sourceDetailCap=detailSampleCap;
        final boolean nativeSeam=usesNativeSeam(quality);
        final var sourceLightWorker=lightWorker;final int sourceLightWorkers=lightWorkers;
        final boolean spatialLightOrderEnabled=boxLightSpatialOrderEnabled;
        final boolean verifyParallelLight=requested.has("verifyParallelLight")&&requested.get("verifyParallelLight").getAsBoolean();
        final boolean reuseEnabled=!requested.has("reusePreparation")||requested.get("reusePreparation").getAsBoolean();
        final boolean sceneCacheEnabled=!requested.has("sceneCache")||requested.get("sceneCache").getAsBoolean();
        final boolean verifyReuse=requested.has("verifyPreparationReuse")&&requested.get("verifyPreparationReuse").getAsBoolean();
        final boolean progressive=!requested.has("progressiveRefinement")||requested.get("progressiveRefinement").getAsBoolean();
        // Prefixes accelerate first acquisition only. A moved camera changes the plan key,
        // but must not replace an already complete scene with 128 detailed tiles and a
        // coarse panorama. Keep the displayed scene until the complete new plan is ready;
        // raw reusable tiles are clipped again against this target's native coverage.
        final var early=earlyDetail;
        final boolean changedEarlyPlan=early!=null&&early.matches(life,mirror,targetIdentity)
                &&!early.planIdentity().equals(detailPlanIdentity(mirror,targetIdentity,quality));
        final boolean completeDetailDisplayed=groups.displayed()!=null&&(groups.displayed().refinedComplete||changedEarlyPlan);
        if(completeDetailDisplayed)completeDetailRefreshes++;
        final String priorProgressKey=refinementProgressKey;final int priorProgressLimit=refinementProgressLimit;
        final long queuedAt=System.nanoTime();
        task=worker.submit(()->{
            try {
                long started=System.nanoTime();
                var stages=new LinkedHashMap<String,Long>();long stageStart=started;
                final long queuedMillis=TimeUnit.NANOSECONDS.toMillis(started-queuedAt);
                checkLife(life);if(epoch!=run)throw new CancellationException();
                var base=baseColumns;if(base==null)throw new IllegalStateException("Base geology did not finish before patch");
                var disk=baseDiskCache;
                final String sourceIdentity=mirror.source().cacheGeometryIdentity();
                final boolean validEarly=early!=null&&early.matches(life,mirror,sourceIdentity);
                final PreparationReuse reusable;
                var stored=preparationReuse;
                // Disk decoding is worker-owned and never holds the render lifecycle monitor.
                if(reuseEnabled&&(stored==null||!stored.matches(mirror,sourceIdentity)))stored=new PreparationReuse(mirror,sourceIdentity,disk,sceneCacheEnabled);
                synchronized(this){
                    checkLife(life);if(epoch!=run)throw new CancellationException();
                    if(reuseEnabled)preparationReuse=stored;
                    reusable=reuseEnabled?stored:null;
                }
                stages.put("sourceIdentity",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-stageStart));stageStart=System.nanoTime();
                final var finalPlan=refinementEnabled?refinementPlan(sourceBounds,mirror.x(),mirror.y(),mirror.z(),mirror.coverage(),
                        nativeSeam,sourceBands,Math.min(sourceLimits.maxSampleVoxels(),sourceDetailCap)):null;
                final long finalSamples=finalPlan==null?baseSampledVoxels:Math.addExact(baseSampledVoxels,finalPlan.sampledVoxels());
                if(finalSamples>sourceLimits.maxSampleVoxels())throw new IllegalArgumentException("Final cached view sample budget exceeded");
                final String finalKey=sceneCacheEnabled&&refinementEnabled&&disk!=null?TerrainLodDiskCache.viewIdentity(disk.key(),sourceIdentity,quality,
                        "native-seam-v1/"+sourceBands+"/"+sourceDetailCap+"/native="+coverageShape(mirror.coverage()),mirror.x(),mirror.y(),mirror.z(),clipDistance,mirror.cubes()):null;
                long finalReadStarted=System.nanoTime();
                var cachedFinal=finalKey==null?new TerrainLodDiskCache.Read(Optional.empty(),"DISABLED"):
                        TerrainLodDiskCache.loadScene(disk.directory().resolve("final-scene"),finalKey,sourceBounds,sourceLimits,finalSamples);
                finalDiskCacheReadMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-finalReadStarted);finalDiskCacheStatus=cachedFinal.status();
                if(cachedFinal.status().startsWith("INVALID"))finalDiskCacheWarning=cachedFinal.status();
                final boolean finalHit=cachedFinal.entry().isPresent();
                final boolean refine=requestedRefine||finalHit;
                var retainedBackdrop=fullPreviewBackdrop;
                final var backdrop=retainedBackdrop!=null&&retainedBackdrop.matches(life,mirror,sourceIdentity)?retainedBackdrop:null;
                if(reusable!=null&&backdrop!=null)
                    backdrop.lights().forEach(reusable.previewBoxes::put);
                var cachedFinalLights=new HashMap<TerrainLodColumns.Box,LightBucket>();
                cachedFinal.entry().ifPresent(entry->entry.lights().forEach((box,light)->cachedFinalLights.put(box,new LightBucket(light.sky(),light.block()))));
                final var previousLights=finalHit?cachedFinalLights:currentLights.isEmpty()&&disk!=null&&disk.sourceIdentity().equals(sourceIdentity)?disk.lights():currentLights;
                stages.put("planAndSceneCache",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-stageStart));stageStart=System.nanoTime();
                var geometryKeys=new HashSet<>(mirror.source().overrideKeys());geometryKeys.addAll(mirror.cubes().keySet());
                var affected=affectedTiles(geometryKeys,sourceBounds,sourceLimits.horizontalStep());
                Set<CubeKey> changed;
                if(previousMirror==null)changed=Set.copyOf(geometryKeys);
                else {
                    var previousAuthority=new HashSet<>(previousMirror.cubes().keySet());
                    previousAuthority.addAll(previousMirror.source().overrideKeys());
                    // A verified immutable world revision includes all saved overrides. Resident
                    // arrival/departure does not change terrain, including unloaded cube authority.
                    changed=dev.madeinabyss.kernel.lod.GeometryPatchChanges.between(mirror.geometryRevision(),
                            previousMirror.geometryRevision(),geometryKeys,previousAuthority);
                }
                if(previousMirror!=null)affected.addAll(affectedTiles(previousMirror.source().overrideKeys(),sourceBounds,sourceLimits.horizontalStep()));
                // The raw base was sampled from the same complete immutable saved world. A
                // pristine resident arrival cannot add material authority missing from that base.
                affected=dev.madeinabyss.kernel.lod.GeometryPatchChanges.againstBase(
                        disk==null?null:disk.sourceIdentity(),sourceIdentity,affected);
                var dirty=affectedTiles(changed,sourceBounds,sourceLimits.horizontalStep());
                var nextPatches=new LinkedHashMap<Tile,List<TerrainLodColumns.Box>>();
                var all=new ArrayList<TerrainLodColumns.Box>();long samples=0;
                for(var entry:base.entrySet())if(!affected.contains(entry.getKey()))all.addAll(entry.getValue());
                for(var tile:affected) {
                    if(epoch!=run)throw new CancellationException();checkLife(life);
                    if(!dirty.contains(tile) && previousPatches.containsKey(tile)) {
                        var reuse=previousPatches.get(tile);nextPatches.put(tile,reuse);all.addAll(reuse);continue;
                    }
                    var tileBounds=new TerrainLodColumns.Bounds(tile.x(),sourceBounds.minY(),tile.z(),
                            (int)Math.min((long)tile.x()+sourceLimits.horizontalStep()-1,sourceBounds.maxXInclusive()),sourceBounds.maxYInclusive(),
                            (int)Math.min((long)tile.z()+sourceLimits.horizontalStep()-1,sourceBounds.maxZInclusive()));
                    var patch=TerrainLodColumns.build((x,y,z)->{
                        checkLife(life);if(epoch!=run)throw new CancellationException();
                        var saved=mirror.cubes().get(CubeKey.fromBlock(new BlockCoordinate(x,y,z)));
                        var material=saved==null?material(Block.stateById(mirror.source().stateIdForDerivedGeometry(x,y,z)))
                                :KNOWN_MATERIALS[saved[Math.floorMod(y,16)<<8|Math.floorMod(z,16)<<4|Math.floorMod(x,16)]];
                        return new TerrainSample.Known(material);
                    },tileBounds,sourceLimits);
                    samples=Math.addExact(samples,patch.sampledVoxels());all.addAll(patch.boxes());nextPatches.put(tile,patch.boxes());
                    if(samples>sourceLimits.maxSampleVoxels() || all.size()>sourceLimits.maxBoxes())throw new IllegalArgumentException("Refresh source budgets exceeded");
                }
                if(all.size()>sourceLimits.maxBoxes())throw new IllegalArgumentException("Refresh source box budget exceeded");
                // Shadow proxy keeps the bounded base-step approximation even after the main
                // scene receives high refinement. Both use current authoritative patched cubes.
                final var shadowBase=List.copyOf(all);
                stages.put("baseAndSavedPatches",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-stageStart));stageStart=System.nanoTime();
                final var checkedTiles=new java.util.concurrent.atomic.AtomicInteger();
                final var checkedSamples=new java.util.concurrent.atomic.AtomicLong();
                var nextDetail=new LinkedHashMap<DetailTile,List<TerrainLodColumns.Box>>();
                long detailSamples=0;int detailHits=0,detailRegionCount=0,acceptedCells=0,rejectedCells=0,oneMetre=0,twoMetre=0,fourMetre=0;
                var detailTiles=new ArrayList<TerrainLodBudgetRefinement.Tile>();
                String progressKey=detailPlanIdentity(mirror,sourceIdentity,quality);
                int selectedJobs=0,totalJobs=0;boolean partialRefinement=false;
                long detailMillis=0;
                if(refine&&!finalHit) {
                    long detailStarted=System.nanoTime();
                    var plan=finalPlan;
                    if(Math.addExact(Math.addExact(Math.addExact(baseSampledVoxels,overviewSampledVoxels),nearOverviewSampledVoxels),plan.sampledVoxels())>sourceLimits.maxSampleVoxels())
                        throw new IllegalArgumentException("Base plus refinement sample budget exceeded");
                    detailRegionCount=plan.regions().size();
                    var detailJobs=new ArrayList<DetailJob>();
                    for(var region:plan.regions()) {
                        var rb=region.bounds();
                        for(long x=rb.minX();x<=(long)rb.maxXInclusive();x=nextTile(x,rb.maxXInclusive()))
                            for(long z=rb.minZ();z<=(long)rb.maxZInclusive();z=nextTile(z,rb.maxZInclusive()))
                                for(long y=rb.minY();y<=(long)rb.maxYInclusive();y=nextTileY(y,rb.maxYInclusive())) {
                                    checkLife(life);if(epoch!=run)throw new CancellationException();
                                    int endX=(int)(nextTile(x,rb.maxXInclusive())-1),endY=(int)(nextTileY(y,rb.maxYInclusive())-1),endZ=(int)(nextTile(z,rb.maxZInclusive())-1);
                                    var key=new DetailTile((int)x,(int)y,(int)z,endX,endY,endZ,region.horizontalStep());
                                    List<TerrainLodColumns.Box> boxes=reusable==null?previousDetail.get(key):reusable.geometry.get(key);
                                    detailJobs.add(new DetailJob(key,boxes!=null&&(reusable!=null||!detailChanged(key,changed))?boxes:null));
                                }
                    }
                    var fragmentCount=new java.util.concurrent.atomic.AtomicLong(all.size());
                    totalJobs=detailJobs.size();
                    int cachedJobs=(int)detailJobs.stream().filter(job->job.cached()!=null).count();
                    // The early certificate prepared geometry, not all requested32-ray light.
                    // Keep the ordinary128-prefix lighting cadence until complete acquisition.
                    if(early!=null&&early.matches(life,mirror,sourceIdentity)&&!completeDetailDisplayed)cachedJobs=0;
                    var currentKeys=new HashSet<DetailTile>();for(var job:detailJobs)currentKeys.add(job.key());
                    long extraEarly=0;if(validEarly)for(var entry:earlyFreshTiles.entrySet())if(!currentKeys.contains(entry.getKey()))extraEarly=Math.addExact(extraEarly,entry.getValue());
                    if(Math.addExact(Math.addExact(Math.addExact(Math.addExact(baseSampledVoxels,overviewSampledVoxels),nearOverviewSampledVoxels),plan.sampledVoxels()),extraEarly)>sourceLimits.maxSampleVoxels())
                        throw new TerrainLodColumns.BudgetExceededException("Changed plan plus early fresh tiles exceeds aggregate sample budget");
                    earlyChangedPlanSampleReserve=extraEarly;
                    selectedJobs=progressive?dev.madeinabyss.kernel.lod.ProgressiveRefinementBudget.next(totalJobs,cachedJobs,progressKey.equals(priorProgressKey)?priorProgressLimit:0,completeDetailDisplayed):totalJobs;
                    partialRefinement=selectedJobs<totalJobs;
                    // Closest tiles become visible first; every publication still contains the
                    // complete coarse base and replaces only fully prepared tile volumes.
                    detailJobs.sort(Comparator.<DetailJob>comparingDouble(job->{var k=job.key();double dx=Math.max(k.x()-mirror.x(),Math.max(0,mirror.x()-((long)k.endX()+1))),dy=Math.max(k.minY()-mirror.y(),Math.max(0,mirror.y()-((long)k.endY()+1))),dz=Math.max(k.z()-mirror.z(),Math.max(0,mirror.z()-((long)k.endZ()+1)));return dx*dx+dy*dy+dz*dz;})
                            .thenComparingInt(job->job.key().x()).thenComparingInt(job->job.key().z()).thenComparingInt(job->job.key().minY()).thenComparingInt(job->job.key().endY()).thenComparingInt(job->job.key().step()));
                    var selectedDetailJobs=detailJobs.subList(0,selectedJobs);
                    sourceLightWorker.purge();
                    var detailResults=BoundedOrderedWork.map(selectedDetailJobs,sourceLightWorkers,sourceLightWorker,job->{
                        checkLife(life);if(epoch!=run)throw new CancellationException();
                        var key=job.key();var boxes=job.cached();long fresh=0;
                        boolean verifyCached=boxes!=null&&verifyReuse&&checkedTiles.getAndIncrement()<8;
                        if(boxes==null||verifyCached) {
                            var tileBounds=new TerrainLodColumns.Bounds(key.x(),key.minY(),key.z(),key.endX(),key.endY(),key.endZ());
                            var tileLimits=new TerrainLodColumns.Parameters(key.step(),sourceLimits.maxSampleVoxels(),sourceLimits.maxBoxes());
                            var detail=TerrainLodColumns.build((bx,by,bz)->{
                                checkLife(life);if(epoch!=run)throw new CancellationException();
                                var saved=mirror.cubes().get(CubeKey.fromBlock(new BlockCoordinate(bx,by,bz)));
                                var mat=saved==null?material(Block.stateById(mirror.source().stateIdForDerivedGeometry(bx,by,bz))):
                                        KNOWN_MATERIALS[saved[Math.floorMod(by,16)<<8|Math.floorMod(bz,16)<<4|Math.floorMod(bx,16)]];
                                return new TerrainSample.Known(mat);
                            },tileBounds,tileLimits);
                            if(verifyCached){checkedSamples.addAndGet(detail.sampledVoxels());if(!boxes.equals(detail.boxes()))throw new IllegalStateException("Reusable terrain tile differs from authoritative source: "+key);}
                            else {fresh=detail.sampledVoxels();boxes=detail.boxes();}
                            if(reusable!=null)reusable.geometry.put(key,boxes);
                        }
                        if(fragmentCount.addAndGet(boxes.size())>8L*sourceLimits.maxBoxes())
                            throw new IllegalArgumentException("Mixed-resolution global fragment budget exceeded");
                        return new DetailResult(key,boxes,fresh,job.cached()!=null);
                    });
                    for(var detail:detailResults) {
                        checkLife(life);if(epoch!=run)throw new CancellationException();
                        detailSamples=Math.addExact(detailSamples,detail.samples());if(detail.reused())detailHits++;
                        nextDetail.put(detail.key(),detail.boxes());
                        if(detailSamples>sourceDetailCap || Math.addExact(samples,detailSamples)>sourceLimits.maxSampleVoxels())
                            throw new IllegalArgumentException("Mixed-resolution global sample budget exceeded");
                    }
                    detailMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-detailStarted);
                    for(var detail:detailResults){var k=detail.key();detailTiles.add(new TerrainLodBudgetRefinement.Tile(
                        new TerrainLodClipper.BoundsLong(k.x(),k.minY(),k.z(),(long)k.endX()+1,(long)k.endY()+1,(long)k.endZ()+1),detail.boxes(),k.step(),List.of()));}
                }
                stages.put("detailTiles",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-stageStart));stageStart=System.nanoTime();
                if(finalHit)all=new ArrayList<>(cachedFinal.entry().get().base().boxes());
                else {
                    if(refine)all=new ArrayList<>(TerrainLodVerticalMerge.merge(all,sourceLimits.maxBoxes()));
                    all.sort(Comparator.comparingLong(TerrainLodColumns.Box::minX).thenComparingLong(TerrainLodColumns.Box::minZ).thenComparingLong(TerrainLodColumns.Box::minY));
                }
                var exclusions=new ArrayList<TerrainLodClipper.BoundsLong>();
                // Only a fully uploaded native candidate can exclude distant geometry. The
                // inscribed rectangle is anchored to the committed mesh, not the requested slider.
                var coverage=mirror.coverage();
                if(coverage!=null&&coverage.complete()) {
                    double safeNear=Math.max(0,coverage.radius()/Math.sqrt(2)-2);
                    long x0=(long)Math.ceil(coverage.anchorX()-safeNear),x1=(long)Math.floor(coverage.anchorX()+safeNear);
                    long z0=(long)Math.ceil(coverage.anchorZ()-safeNear),z1=(long)Math.floor(coverage.anchorZ()+safeNear);
                    if(x0<x1&&z0<z1&&coverage.minY()<coverage.maxY())
                        exclusions.add(new TerrainLodClipper.BoundsLong(x0,coverage.minY(),z0,x1,coverage.maxY(),z1));
                }
                var clipped=finalHit?cachedFinal.entry().get().base().boxes():TerrainLodClipper.subtract(all,exclusions,sourceLimits.maxBoxes());
                final boolean useBackdrop=refine&&!finalHit&&partialRefinement&&backdrop!=null;
                List<TerrainLodColumns.Box> previewBackground=List.of();
                boolean currentPinsPreserved=!validEarly;
                if(refine&&!finalHit) {
                    // Count the geometry that will actually upload, including native-seam fragments.
                    var visibleDetail=new ArrayList<TerrainLodBudgetRefinement.Tile>(detailTiles.size());
                    for(var tile:detailTiles)visibleDetail.add(new TerrainLodBudgetRefinement.Tile(tile.bounds(),
                        tile.boxes(),tile.horizontalStep(),exclusions));
                    if(useBackdrop) {
                        // Raw preview geometry precedes the native hole. A moved native window
                        // therefore restores its old hole and removes only the current one.
                        var visibleBackdrop=TerrainLodClipper.subtract(backdrop.raw(),exclusions,sourceLimits.maxBoxes());
                        var selection=early!=null&&early.matches(life,mirror,sourceIdentity)
                            ?ProgressiveRefinementOverlay.rebase(backdrop.raw(),early.raw(),detailTiles,exclusions,mirror.x(),mirror.y(),mirror.z(),sourceLimits.maxBoxes())
                                .orElseThrow(()->new TerrainLodColumns.BudgetExceededException("Partial panorama cannot retain certified early detail"))
                            :ProgressiveRefinementOverlay.select(visibleBackdrop,visibleDetail,mirror.x(),mirror.y(),mirror.z(),sourceLimits.maxBoxes());
                        clipped=selection.boxes();previewBackground=selection.background();
                        currentPinsPreserved=!validEarly||ProgressiveRefinementOverlay.preserves(early.raw(),selection.rawAcceptedReplacements(),exclusions,sourceLimits.maxBoxes());
                        acceptedCells=selection.acceptedCells();rejectedCells=selection.rejectedCells();
                        oneMetre=selection.acceptedOneMetre();twoMetre=selection.acceptedTwoMetre();fourMetre=selection.acceptedFourMetre();
                    } else {
                        // Terminal geometry/compaction/budgets are exactly the original path.
                        var selection=TerrainLodBudgetRefinement.select(clipped,visibleDetail,mirror.x(),mirror.y(),mirror.z(),sourceLimits.maxBoxes());
                        clipped=selection.boxes();acceptedCells=selection.acceptedCells();rejectedCells=selection.rejectedCells();
                        currentPinsPreserved=!validEarly||ProgressiveRefinementOverlay.preserves(early.raw(),selection.rawAcceptedReplacements(),exclusions,sourceLimits.maxBoxes());
                        oneMetre=selection.acceptedOneMetre();twoMetre=selection.acceptedTwoMetre();fourMetre=selection.acceptedFourMetre();
                    }
                }
                if(validEarly&&!changedEarlyPlan&&!currentPinsPreserved)
                    throw new TerrainLodColumns.BudgetExceededException("Unchanged plan cannot drop certified early detail");
                final var shadowProxy=TerrainLodClipper.subtract(useBackdrop?backdrop.raw():shadowBase,exclusions,sourceLimits.maxBoxes());
                final var previewBackgroundKeys=Set.copyOf(previewBackground);
                checkLife(life);if(epoch!=run)throw new CancellationException();
                stages.put("selectionAndClipping",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-stageStart));stageStart=System.nanoTime();
                final long geometryMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                var rendered=new LinkedHashMap<LightBucket,List<DhApiRenderableBox>>();
                var nextLights=new HashMap<TerrainLodColumns.Box,LightBucket>();long lightHits=0;
                var exposedCache=reusable==null?new ExposedSkyCache(mirror.source()):reusable.light;
                var previewExposedCache=useBackdrop?new ExposedSkyCache(mirror.source(),true):null;
                var cumulativeBoxHits=new java.util.concurrent.atomic.LongAdder();
                var previewBoxHits=new java.util.concurrent.atomic.LongAdder();
                final long priorProbeHits=exposedCache.hits.sum(),priorProbeEvaluations=exposedCache.evaluations.sum();
                final long priorLightSourceHits=exposedCache.source.stateCacheHits(),priorLightSourceMisses=exposedCache.source.stateCacheMisses();
                // Only fresh terminal scalar jobs use hybrid64 spatial batches. Native objects,
                // grouping and published lights retain exactly the original geometry order.
                final boolean spatialLightOrder=spatialLightOrderEnabled&&refine&&!partialRefinement&&!finalHit;
                final long orderingStarted=spatialLightOrder?System.nanoTime():0;
                final var lightOrder=spatialLightOrder?BoxLightWorkOrder.order(clipped,sourceLimits.maxBoxes()):null;
                final long orderingNanos=lightOrder==null?0:System.nanoTime()-orderingStarted;
                final int orderedLightJobs=lightOrder==null?0:lightOrder.size();
                final String actualLightOrder=lightOrder==null?"ORIGINAL":"HYBRID64";
                sourceLightWorker.purge();
                var solvedLights=BoundedOrderedWork.map(lightOrder==null?clipped:lightOrder.orderedBoxes(),sourceLightWorkers,sourceLightWorker,box->{
                    checkLife(life);if(epoch!=run)throw new CancellationException();
                    if(previewBackgroundKeys.contains(box)) {
                        var light=reusable==null?backdrop.lights().get(box):reusable.previewBoxes.get(box);
                        if(light!=null)previewBoxHits.increment();
                        if(light==null){light=boxLight(mirror.source(),box,previewExposedCache);if(reusable!=null)reusable.previewBoxes.put(box,light);}
                        return light;
                    }
                    var light=reusable==null?null:reusable.requestedBoxes.get(box);
                    if(light!=null)cumulativeBoxHits.increment();
                    if(light==null)light=previousLights.get(box);
                    if(light==null)light=boxLight(mirror.source(),box,exposedCache);
                    if(reusable!=null)reusable.requestedBoxes.put(box,light);
                    return light;
                });
                if(lightOrder!=null)solvedLights=lightOrder.restore(solvedLights);
                stages.put("exposedLight",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-stageStart));stageStart=System.nanoTime();
                int verifiedLights=0;
                if(verifyParallelLight) {
                    // Explicit diagnostic: compare evenly distributed actual loaded-geometry boxes
                    // against the same serial calculation with exposed-probe memoization disabled.
                    int count=Math.min(64,clipped.size());
                    for(int probe=0;probe<count;probe++) {
                        checkLife(life);if(epoch!=run)throw new CancellationException();
                        int index=(int)((long)probe*clipped.size()/count);
                        var box=clipped.get(index);
                        var expected=boxLight(mirror.source(),box,new ExposedSkyCache(mirror.source(),0,previewBackgroundKeys.contains(box)));
                        if(!expected.equals(solvedLights.get(index)))throw new IllegalStateException("Parallel LOD light mismatch at box "+index);
                        verifiedLights++;
                    }
                }
                for(int i=0;i<clipped.size();i++) {
                    checkLife(life);if(epoch!=run)throw new CancellationException();
                    var box=clipped.get(i);var light=solvedLights.get(i);
                    if(!previewBackgroundKeys.contains(box)) {
                        if(previousLights.containsKey(box))lightHits++;
                        nextLights.put(box,light);
                    }
                    rendered.computeIfAbsent(light,ignored->new ArrayList<>()).add(renderBox(box,mirror.source()));
                }
                appendOceanHorizon(rendered,mirror,clipped.size());
                stages.put("renderObjectsAndVerification",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-stageStart));stageStart=System.nanoTime();
                // Physical shadow illumination is derived only on workers. The clipped coarse
                // proxy survives high refinement and receives exact keys/values as one scene.
                sourceLightWorker.purge();
                var shadowLights=BoundedOrderedWork.map(shadowProxy,sourceLightWorkers,sourceLightWorker,box->{
                    checkLife(life);if(epoch!=run)throw new CancellationException();
                    if(useBackdrop) {
                        var light=reusable==null?backdrop.lights().get(box):reusable.previewBoxes.get(box);
                        if(light!=null)previewBoxHits.increment();
                        if(light==null){light=boxLight(mirror.source(),box,previewExposedCache);if(reusable!=null)reusable.previewBoxes.put(box,light);}
                        return light;
                    }
                    var light=nextLights.get(box);if(light==null)light=currentShadowLights.get(box);
                    if(light==null&&reusable!=null){light=reusable.requestedBoxes.get(box);if(light!=null)cumulativeBoxHits.increment();}
                    if(light==null)light=previousLights.get(box);
                    if(light==null&&disk!=null&&disk.sourceIdentity().equals(sourceIdentity))light=disk.lights().get(box);
                    if(light==null)light=boxLight(mirror.source(),box,exposedCache);
                    if(reusable!=null)reusable.requestedBoxes.put(box,light);
                    return light;
                });
                var nextShadowLights=new HashMap<TerrainLodColumns.Box,LightBucket>();
                var nextShadowSky=new HashMap<TerrainLodColumns.Box,Integer>();
                for(int i=0;i<shadowProxy.size();i++){var box=shadowProxy.get(i);var light=shadowLights.get(i);nextShadowLights.put(box,light);nextShadowSky.put(box,light.sky());}
                stages.put("shadowLight",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-stageStart));stageStart=System.nanoTime();
                var immutableRendered=new LinkedHashMap<LightBucket,List<DhApiRenderableBox>>();
                rendered.forEach((key,value)->immutableRendered.put(key,List.copyOf(value)));
                final var finalClipped=clipped;
                final long[] finalYExtents=yExtents(finalClipped);
                final long patchSamples=samples,buildMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);final int sourceCount=clipped.size();
                final long builtDetailSamples=detailSamples;final int hits=detailHits,regions=detailRegionCount;
                final int accepted=acceptedCells,rejected=rejectedCells;
                final int admittedOne=oneMetre,admittedTwo=twoMetre,admittedFour=fourMetre;
                final long cacheHits=mirror.source().stateCacheHits(),cacheMisses=mirror.source().stateCacheMisses();
                final int cacheResidents=mirror.source().residentStateCaches();
                final long cachedLights=lightHits;
                final long builtDetailMillis=detailMillis;
                final int comparedLights=verifiedLights;
                stages.put("freezeResult",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-stageStart));stageStart=System.nanoTime();
                // Finish the atomic cache write before publication can trigger a new refinement
                // and cancel this coordinator Future. External cancellation still stops admission.
                if((!refine||!partialRefinement)&&disk!=null&&!disk.hit()) {
                    checkLife(life);if(epoch!=run)throw new CancellationException();
                    try {
                        if(!disk.sourceIdentity().equals(mirror.source().cacheGeometryIdentity()))diskCacheWarning="WRITE_SKIPPED: source changed during initial build";
                        else {
                            var rawLights=new HashMap<TerrainLodColumns.Box,TerrainLodDiskCache.Light>();
                            // Only complete requested32-ray values enter persistent base light.
                            // The raw original base is stored even if its native-clipped shadow
                            // proxy contributes only a subset of exact light keys.
                            (refine?nextShadowLights:nextLights).forEach((box,light)->rawLights.put(box,new TerrainLodDiskCache.Light(light.sky(),light.block())));
                            checkLife(life);if(epoch!=run)throw new CancellationException();
                            long writeStarted=System.nanoTime();
                            TerrainLodDiskCache.save(disk.directory(),disk.key(),new TerrainLodDiskCache.Entry(disk.base(),rawLights),sourceBounds,sourceLimits);
                            diskCacheWriteMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-writeStarted);
                        }
                    }catch(CancellationException cancelled){throw cancelled;}
                    catch(java.io.IOException|RuntimeException cacheFailure){diskCacheWarning="WRITE_FAILED: "+cacheFailure;}
                }
                if(refine&&!partialRefinement&&!finalHit&&finalKey!=null) {
                    checkLife(life);if(epoch!=run)throw new CancellationException();
                    try {
                        var rawLights=new HashMap<TerrainLodColumns.Box,TerrainLodDiskCache.Light>();
                        nextLights.forEach((box,light)->rawLights.put(box,new TerrainLodDiskCache.Light(light.sky(),light.block())));
                        checkLife(life);if(epoch!=run)throw new CancellationException();
                        long writeStarted=System.nanoTime();
                        TerrainLodDiskCache.saveScene(disk.directory().resolve("final-scene"),finalKey,
                                new TerrainLodDiskCache.Entry(new TerrainLodColumns.Result(clipped,finalSamples),rawLights),
                                sourceBounds,sourceLimits,finalSamples);
                        finalDiskCacheWriteMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-writeStarted);
                    }catch(CancellationException cancelled){throw cancelled;}
                    catch(java.io.IOException|RuntimeException cacheFailure){finalDiskCacheWarning="WRITE_FAILED: "+cacheFailure;}
                }
                checkLife(life);if(epoch!=run)throw new CancellationException();
                stages.put("diskWrite",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-stageStart));
                final var finishedStages=Map.copyOf(stages);final long finishedAt=System.nanoTime();
                final long lightSourceHits=exposedCache.source.stateCacheHits()-priorLightSourceHits,lightSourceMisses=exposedCache.source.stateCacheMisses()-priorLightSourceMisses;
                final boolean partial=partialRefinement;final int completedJobs=selectedJobs,plannedJobs=totalJobs;
                final boolean pinsPreserved=currentPinsPreserved;
                client.execute(()->{
                    if(!active || epoch!=run || lifetime!=life)return;
                    try {
                        requireSession(client);
                        if(ClientGeneratedLight.geometryRevision()!=mirror.geometryRevision()){queueRefresh(client);return;}
                        preparationStages=finishedStages;preparationQueueMillis=queuedMillis;preparedAtNanos=finishedAt;
                        preparationTotalMillis=TimeUnit.NANOSECONDS.toMillis(finishedAt-started);verificationSamples=checkedSamples.get();
                        reusableLightSourceHits=lightSourceHits;reusableLightSourceMisses=lightSourceMisses;
                        verifiedPreparationTiles=verifyReuse?Math.min(8,checkedTiles.get()):0;
                        pendingBoxes=Collections.unmodifiableMap(immutableRendered);pendingEpoch=run;publishedYExtents=finalYExtents;sampledVoxels=baseSampledVoxels;
                        pendingGeometry=shadowProxy;
                        pendingShadowSky=Collections.unmodifiableMap(nextShadowSky);
                        pendingOverview=false;pendingRefined=refine&&!partial;pendingPreviewBackdrop=useBackdrop;pendingRequestedDetailBoxes=nextLights.size();geometryBuildMillis=geometryMillis;lightingBuildMillis=buildMillis-geometryMillis;reusedBoxLights=cachedLights;
                        pendingNearDetail=false;pendingEarlyDetailPreserved=validEarly&&pinsPreserved;
                        if(validEarly&&!pinsPreserved){if(changedEarlyPlan)earlyDetailPlanConflicts++;else earlyDetailDowngrades++;}
                        refinementProgressKey=progressKey;refinementProgressLimit=completedJobs;refinementTotalJobs=plannedJobs;
                        detailGeometryBuildMillis=builtDetailMillis;
                        reusedCumulativeBoxLights=cumulativeBoxHits.sum();reusedPreviewBoxLights=previewBoxHits.sum();
                        exposedSkyCacheHits=exposedCache.hits.sum()-priorProbeHits;exposedSkyEvaluations=exposedCache.evaluations.sum()-priorProbeEvaluations;exposedSkyCachedProbes=exposedCache.values.size();
                        verifiedBoxLights=comparedLights;
                        boxLightSpatialOrderMode=actualLightOrder;boxLightSpatialOrderedJobs=orderedLightJobs;boxLightSpatialOrderingNanos=orderingNanos;
                        boxLightCache=Collections.unmodifiableMap(nextLights);boxLightGeometryRevision=mirror.geometryRevision();
                        shadowLightCache=useBackdrop?Map.of():Collections.unmodifiableMap(nextShadowLights);shadowLightGeometryRevision=useBackdrop?-1:mirror.geometryRevision();
                        patchedMirror=mirror;patchedColumns=Map.copyOf(nextPatches);
                        detailCache=Map.copyOf(nextDetail);refinementSampledVoxels=builtDetailSamples;refinementCacheHits=hits;refinementRegions=regions;
                        refinementAcceptedCells=accepted;refinementRejectedCells=rejected;
                        refinementOneMetre=admittedOne;refinementTwoMetre=admittedTwo;refinementFourMetre=admittedFour;
                        sourceStateCacheHits=cacheHits;sourceStateCacheMisses=cacheMisses;sourceStateCacheResidents=cacheResidents;
                        sourceStateCacheResidentBytes=cacheResidents*(long)dev.madeinabyss.kernel.cube.ExactXYZStateCache.MAX_CAPACITY*16;
                        refreshSampledVoxels=patchSamples;refreshBuildMillis=buildMillis;sourceBoxes=sourceCount;phase="WAITING_FOR_RENDER_REGISTER";
                    }catch(RuntimeException failure){fail(failure);}
                });
                // Persist only on this coordinator worker, after enqueueing scene publication.
                if(reusable!=null&&builtDetailSamples>0)reusable.persistTiles();
                if(exposedCache.source!=mirror.source())exposedCache.source.persistLightCache();
                mirror.source().persistLightCache();
            }catch(CancellationException cancelled){/* Latest target/lifetime owns publication. */}
            catch(Exception failure){client.execute(()->{if(active && epoch==run && lifetime==life)fail(failure);});}
        });
    }
    private static Set<Tile> affectedTiles(Set<CubeKey> keys,TerrainLodColumns.Bounds bounds,int step) {
        var tiles=new TreeSet<Tile>(Comparator.comparingInt(Tile::x).thenComparingInt(Tile::z));
        for(var key:keys) {
            long minX=Math.max((long)key.x()*16,bounds.minX()),endX=Math.min((long)key.x()*16+16,(long)bounds.maxXInclusive()+1);
            long minZ=Math.max((long)key.z()*16,bounds.minZ()),endZ=Math.min((long)key.z()*16+16,(long)bounds.maxZInclusive()+1);
            if(minX>=endX || minZ>=endZ)continue;
            long tileX=bounds.minX()+Math.floorDiv(minX-bounds.minX(),step)*step;
            long tileZ=bounds.minZ()+Math.floorDiv(minZ-bounds.minZ(),step)*step;
            for(long x=tileX;x<endX;x+=step)for(long z=tileZ;z<endZ;z+=step)tiles.add(new Tile((int)x,(int)z));
        }
        return tiles;
    }
    private static long nextTile(long coordinate,int inclusiveEnd) {
        return Math.min((Math.floorDiv(coordinate,16)+1)*16,(long)inclusiveEnd+1);
    }
    private static long nextTileY(long coordinate,int inclusiveEnd) {
        return Math.min((Math.floorDiv(coordinate,64)+1)*64,(long)inclusiveEnd+1);
    }
    private static boolean detailChanged(DetailTile tile,Set<CubeKey> changed) {
        int cx=Math.floorDiv(tile.x(),16),cz=Math.floorDiv(tile.z(),16);
        for(long cy=Math.floorDiv(tile.minY(),16);cy<=Math.floorDiv(tile.endY(),16);cy++)
            if(changed.contains(new CubeKey(cx,(int)cy,cz)))return true;
        return false;
    }
    private boolean mirrorChanged() {
        var current=DeepCubeDomain.space(level).orElse(null);
        var player=Minecraft.getInstance().player;
        boolean detailMoved=refinementEnabled && latestMirror!=null && player!=null &&
                (anchor(player.getX())!=anchor(latestMirror.x()) || anchor(player.getY())!=anchor(latestMirror.y()) || anchor(player.getZ())!=anchor(latestMirror.z()));
        return current!=physical || current==null || current.contentGeneration()!=physicalGeneration || ClientDeepVisual.revision()!=visualRevision
                || latestMirror==null || ClientGeneratedLight.geometryRevision()!=latestMirror.geometryRevision() || detailMoved
                || !coverageShape(DeepCubeRenderer.nativeCoverage()).equals(coverageShape(latestMirror.coverage()));
    }
    private static String coverageShape(DeepCubeRenderer.NativeCoverage c){
        return c==null||!c.complete()?"none":c.anchorX()+","+c.anchorY()+","+c.anchorZ()+","+c.radius()+","+c.minY()+","+c.maxY();
    }
    private static TerrainLodRefinement.Plan refinementPlan(TerrainLodColumns.Bounds world,double x,double y,double z,
            DeepCubeRenderer.NativeCoverage coverage,boolean nativeSeam,List<TerrainLodRefinement.Band> bands,long budget) {
        if(nativeSeam&&coverage!=null&&coverage.complete())
            return NativeSeamRefinement.plan(world,new NativeSeamRefinement.NativeVolume(coverage.anchorX(),coverage.anchorZ(),
                    coverage.radius(),coverage.minY(),Math.addExact(coverage.maxY(),1)),budget,64);
        return TerrainLodRefinement.plan(world,(int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z),bands,budget,64);
    }
    private static long anchor(double position) {return Math.floorDiv((long)Math.floor(position),16);}
    private static boolean usesNativeSeam(String quality){return "high".equals(quality)||"island-high".equals(quality);}
    /** Far water is a visible surface of real generated water, never a synthetic seabed.
     * It is rebuilt outside the cached core and excludes authoritative saved cells. */
    private void appendOceanHorizon(Map<LightBucket,List<DhApiRenderableBox>> rendered,Mirror mirror,int coreBoxes) {
        if(!requested.has("oceanHorizonRadius"))return;
        if(!mirror.source().hasIslandSurface())throw new IllegalStateException("Ocean geometry identity lost");
        int sea=mirror.source().oceanLevelY();
        var horizon=dev.madeinabyss.kernel.lod.OceanHorizon.plan(bounds,requested.get("oceanHorizonRadius").getAsInt(),sea);
        var cuts=new ArrayList<>(coverageExclusions(mirror.coverage()));
        for(var key:mirror.source().overrideKeys())if((long)key.y()*16+15>=sea)
            cuts.add(new TerrainLodClipper.BoundsLong((long)key.x()*16,sea,(long)key.z()*16,(long)key.x()*16+16,(long)sea+1,(long)key.z()*16+16));
        var surface=TerrainLodClipper.subtract(horizon,cuts,Math.max(1,limits.maxBoxes()-coreBoxes));
        if(coreBoxes+surface.size()>limits.maxBoxes())throw new TerrainLodColumns.BudgetExceededException("Ocean surface exceeds scene box budget");
        // Open ocean has unobstructed sky; any saved roof is handled by native
        // geometry locally. This distant plane does not participate in shadow proxies.
        var bucket=rendered.computeIfAbsent(new LightBucket(15,0),ignored->new ArrayList<>());
        for(var box:surface)bucket.add(renderBox(box,mirror.source()));
    }
    private static void capture(SparseCubeSpace space,Map<CubeKey,byte[]> captured) {
        for(CubeKey key:space.residentKeys()) {
            int[] states=space.snapshot(key).orElseThrow().states();var bytes=new byte[states.length];
            for(int i=0;i<states.length;i++)bytes[i]=(byte)material(Block.stateById(states[i])).ordinal();
            captured.put(key,bytes);
            if(captured.size()>CUBE_CAP)throw new IllegalArgumentException("Immutable captured cube cap exceeded");
        }
    }
    private static TerrainMaterial material(BlockState state) {
        return LodBlockStateMaterials.material(state);
    }
    /** Coarse boxes share the maximum exposed direct/diffuse sky sample. */
    private static LightBucket boxLight(GeneratedSkySource source,TerrainLodColumns.Box box,ExposedSkyCache exposedCache) {
        long x=box.minX()+(box.maxXExclusive()-box.minX()-1)/2;
        long y=box.minY()+(box.maxYExclusive()-box.minY()-1)/2;
        long z=box.minZ()+(box.maxZExclusive()-box.minZ()-1)/2;
        int localMinimum=source.minKnownYForBox(Math.toIntExact(box.minX()-1),Math.toIntExact(box.minZ()-1),
                Math.toIntExact(box.maxXExclusive()),Math.toIntExact(box.maxZExclusive()));
        int sky=dev.madeinabyss.kernel.light.LodBoxSkyLight.maximum(box,localMinimum,
                source::opacityFast,source::directSkyFast,
                (px,py,pz)->exposedCache.get(px,py,pz));
        int block=source.emission(source.stateIdFast(Math.toIntExact(x),Math.toIntExact(y),Math.toIntExact(z)));
        if(sky<0 || sky>15 || block<0 || block>15)throw new IllegalStateException("Non-scalar LOD light bucket");
        return new LightBucket(sky,block);
    }
    /** Exact bounded probes owned by one immutable geometry scope; preview/final remain separate. */
    private static final class ExposedSkyCache {
        private static final int CAP=262144;
        private record Probe(int x,int y,int z) {
            @Override public int hashCode(){return dev.madeinabyss.kernel.light.SkyProbeSingleFlight.hashCoordinates(x,y,z);}
        }
        final BoundedReuseCache<Probe,Integer> values;
        final java.util.concurrent.atomic.LongAdder hits=new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder evaluations=new java.util.concurrent.atomic.LongAdder();
        final GeneratedSkySource source;
        final int cap;
        final boolean preview;
        ExposedSkyCache(GeneratedSkySource source){this(source,CAP,false);}
        ExposedSkyCache(GeneratedSkySource source,int cap){this(source,cap,false);}
        ExposedSkyCache(GeneratedSkySource source,boolean preview){this(source,CAP,preview);}
        private ExposedSkyCache(GeneratedSkySource source,int cap,boolean preview){this.source=source;this.cap=cap;this.preview=preview;this.values=new BoundedReuseCache<>(Math.max(1,cap),Math.max(1,cap),ignored->1L);}
        int get(long x,long y,long z) {
            var key=new Probe(Math.toIntExact(x),Math.toIntExact(y),Math.toIntExact(z));
            var known=values.get(key);if(known!=null){hits.increment();return known;}
            evaluations.increment();int value=exposedSky(source,key.x(),key.y(),key.z(),preview);
            // Miss computation stays outside the lock. Admission has a strict memory cap;
            // raced exact calculations may repeat but cannot change their result or coverage.
            if(cap>0)values.put(key,value);
            return value;
        }
    }
    private static int exposedSky(GeneratedSkySource source,long x,long y,long z,boolean preview) {
        int bx=Math.toIntExact(x),by=Math.toIntExact(y),bz=Math.toIntExact(z);
        return source.opacityFast(bx,by,bz)>=15?0:preview?source.ambientSkyPreview(bx,by,bz,32):source.ambientSky(bx,by,bz,32);
    }
    private static DhApiRenderableBox renderBox(TerrainLodColumns.Box box,GeneratedSkySource source) {
        Color color=new Color(dev.madeinabyss.forge.worldgen.LivingAbyssLodColors155.lod(source,box),true);
        EDhApiBlockMaterial material=switch(box.material()){case ROCK->EDhApiBlockMaterial.STONE;case SOIL->EDhApiBlockMaterial.DIRT;
            case GRASS->EDhApiBlockMaterial.GRASS;case WATER->EDhApiBlockMaterial.WATER;case AIR->EDhApiBlockMaterial.AIR;};
        return new DhApiRenderableBox(new DhApiVec3d(box.minX(),box.minY(),box.minZ()),
                new DhApiVec3d(box.maxXExclusive(),box.maxYExclusive(),box.maxZExclusive()),color,material);
    }
    private void requireSession(Minecraft client) {
        if(client.level!=level || client.player==null || !ClientDeepRuntime.matchesGeneratedProfile(client.level,sessionToken,fingerprint))
            throw new SessionEndedException();
        if(ClientDeepRuntime.observationFailed() || ClientDeepVisual.observationFailed())throw new IllegalStateException("Current mirror observation refused");
    }
    private void installCuller() {
        if(ownCuller!=null)return;
        delegate=DhApi.overrides.get(IDhApiCullingFrustum.class);
        if(delegate==null)throw new IllegalStateException("No public DH terrain culling delegate");
        if(delegate.getPriority()==Integer.MAX_VALUE)throw new IllegalStateException("No culling override priority available");
        int p=Math.max(0,delegate.getPriority()+1);
        while(DhApi.overrides.get(IDhApiCullingFrustum.class,p)!=null){if(p==Integer.MAX_VALUE)throw new IllegalStateException("No free culling priority");p++;}
        cullerPriority=p;
        ownCuller=new IDhApiCullingFrustum(){
            @Override public int getPriority(){return cullerPriority;}
            @Override public void update(int minY,int maxY,DhApiMat4f matrix){delegate.update(minY,maxY,matrix);}
            @Override public boolean intersects(int x,int z,int width,int detail){if(scopeActive){terrainCulls++;return false;}return delegate.intersects(x,z,width,detail);}
        };
        DhApi.overrides.bind(IDhApiCullingFrustum.class,ownCuller);
        shadowDelegate=DhApi.overrides.get(IDhApiShadowCullingFrustum.class);
        if(shadowDelegate==null)throw new IllegalStateException("No public DH shadow culling delegate");
        if(shadowDelegate.getPriority()==Integer.MAX_VALUE)throw new IllegalStateException("No shadow culling priority available");
        // Oculus registers its shadow frustum at the public default priority10, possibly later.
        int sp=Math.max(11,shadowDelegate.getPriority()+1);
        while(DhApi.overrides.get(IDhApiShadowCullingFrustum.class,sp)!=null){
            if(sp==Integer.MAX_VALUE)throw new IllegalStateException("No free shadow culling priority");sp++;
        }
        shadowCullerPriority=sp;
        ownShadowCuller=new IDhApiShadowCullingFrustum(){
            @Override public int getPriority(){return shadowCullerPriority;}
            @Override public void update(int minY,int maxY,DhApiMat4f matrix){
                var current=DhApi.overrides.get(IDhApiShadowCullingFrustum.class,10);
                if(current!=null)shadowDelegate=current;
                shadowDelegate.update(minY,maxY,matrix);
            }
            @Override public boolean intersects(int x,int z,int width,int detail){
                if(scopeActive){shadowTerrainCulls++;return false;}
                return shadowDelegate.intersects(x,z,width,detail);
            }
        };
        DhApi.overrides.bind(IDhApiShadowCullingFrustum.class,ownShadowCuller);
    }
    private void renderSetup(DhApiRenderParam input) {
        scopeActive=false;
        if(!active || input==null || input.clientLevelWrapper==null || input.clientLevelWrapper.getWrappedMcObject()!=level)return;
        try {
            requireSession(Minecraft.getInstance());
            dev.madeinabyss.forge.probe.client.GeneratedShaderLightPresentation.prepareFrame();
            if(handoffCount>0 && groups.displayed()==null)missingDisplayedRefreshFrames++;
            if(mirrorChanged() || refinementPending&&!refreshInFlight)queueRefresh(Minecraft.getInstance());
            if(DhApi.Delayed.configs.graphics().disableFrustumCulling().getValue())throw new IllegalStateException("DH frustum culling was disabled");
            if(DhApi.Delayed.configs.graphics().disableShadowFrustumCulling().getValue())throw new IllegalStateException("DH shadow frustum culling is disabled");
            if(generation.getValue()!=EDhApiGeneratorPlan.DISABLED)throw new IllegalStateException("DH generation became enabled during preview");
            nearClip=input.nearClipPlane;farClip=input.farClipPlane;
            if(groups.ready()&&!ClientGeneratedLight.geometryObservationsPending()) {
                // Every part completed a real GPU upload/draw while clipped out of view.
                // Restore all origins together on this owner thread before the atomic handoff.
                for(var group:groups.staged().buckets)group.setOriginBlockPos(new DhApiVec3d(0,0,0));
                retireGroup(groups.promote());handoffCount++;phase="REGISTERED";
                if(preparedAtNanos!=0)publicationDelayMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-preparedAtNanos);
                dev.madeinabyss.forge.probe.client.GeneratedFarShadowRenderer.acceptGeometry(groups.displayed().sourceGeometry,groups.displayed().shadowSky);
                publishedVisualRevision=latestMirror.revision();publishedPhysicalGeneration=latestMirror.generation();refreshCount++;
                publishedGeometryRevision=latestMirror.geometryRevision();
                refreshInFlight=false;
                if(groups.displayed().earlyDetailPreserved)retainedEarlyDetailPromotions++;
                if(groups.displayed().nearOverview) {
                    startupStages.nearDisplayed();overviewPending=true;refreshInFlight=true;refinementPending=false;phase="NEAR_PREVIEW_REGISTERED";
                    if(firstNearMillis<0)firstNearMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-beginNanos);
                    // The owner allocates the full-preview target only after near upload/draw.
                    // Its worker can now proceed without overwriting a pending/staged scene.
                    nearOverviewAck.complete(nextStartupTarget(Minecraft.getInstance()));
                } else if(groups.displayed().nearDetail) {
                    startupStages.nearDetailDisplayed();overviewPending=true;refreshInFlight=true;refinementPending=false;phase="NEAR_DETAIL_REGISTERED";
                    if(firstNearDetailMillis<0)firstNearDetailMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-beginNanos);
                    if(firstPartialDetailDisplayMillis<0)firstPartialDetailDisplayMillis=firstNearDetailMillis;
                    nearDetailAck.complete(nextStartupTarget(Minecraft.getInstance()));
                } else if(groups.displayed().overview){startupStages.fullDisplayed();overviewPending=false;refinementPending=true;phase="OVERVIEW_REGISTERED";
                    if(firstFullOverviewMillis<0)firstFullOverviewMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-beginNanos);
                    if(overviewFirstDisplayMillis<0)overviewFirstDisplayMillis=firstFullOverviewMillis;}
                else {refinementPending=refinementEnabled&&!groups.displayed().refinedComplete;if(refinementPending)progressiveHandoffs++;
                    if(refinementPending&&firstPartialDetailDisplayMillis<0)firstPartialDetailDisplayMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-beginNanos);
                    if(groups.displayed().requestedDetailBoxes>0&&firstVisibleDetailDisplayMillis<0)firstVisibleDetailDisplayMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-beginNanos);
                    if(!refinementPending&&firstRequestedDisplayMillis<0)firstRequestedDisplayMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-beginNanos);
                    if(groups.displayed().refinedComplete)clearEarlyCertificate();}
                if(groups.displayed().requestedDetailBoxes>0&&firstVisibleDetailDisplayMillis<0)firstVisibleDetailDisplayMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-beginNanos);
                if(firstDisplayMillis<0)firstDisplayMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-beginNanos);
            }
            if(pendingBoxes!=null && groups.staged()==null) {
                wrapper=input.clientLevelWrapper;register=wrapper.getRenderRegister();if(register==null)return;
                long candidateEpoch=pendingEpoch;stagedEpoch=candidateEpoch;
                stagedDrawCallbacks=0;
                var candidate=new Scene();sceneCreated++;
                candidate.sourceGeometry=pendingGeometry;
                candidate.shadowSky=pendingShadowSky;
                candidate.overview=pendingOverview;
                candidate.nearOverview=pendingNearOverview;
                candidate.nearDetail=pendingNearDetail;
                candidate.earlyDetailPreserved=pendingEarlyDetailPreserved;
                candidate.refinedComplete=pendingRefined;
                candidate.previewBackdrop=pendingPreviewBackdrop;
                candidate.requestedDetailBoxes=pendingRequestedDetailBoxes;
                for(var bucket:pendingBoxes.entrySet()) {
                    var values=bucket.getValue();
                    for(int start=0;start<values.size();start+=REGISTRATION_BOX_CHUNK)
                        candidate.remaining.add(new RegistrationPart(bucket.getKey(),values.subList(start,Math.min(values.size(),start+REGISTRATION_BOX_CHUNK))));
                }
                groups.stage(candidateEpoch,candidate);
                pendingBoxes=null;pendingGeometry=List.of();pendingShadowSky=Map.of();installCuller();phase="UPLOADING_REPLACEMENT";
                maximumOwnedScenes=Math.max(maximumOwnedScenes,groups.size());
            }
            pumpRegistration();
            if(ownCuller!=null) {
                if(DhApi.overrides.get(IDhApiCullingFrustum.class)!=ownCuller)throw new IllegalStateException("Foreign override superseded preview culler");
                if(DhApi.overrides.get(IDhApiShadowCullingFrustum.class)!=ownShadowCuller)throw new IllegalStateException("Foreign override superseded preview shadow culler");
                scopeActive=input.clientLevelWrapper==wrapper;
            }
        }catch(RuntimeException failure){fail(failure);}
    }
    private void pumpRegistration() {
        var candidate=groups.staged();if(candidate==null||candidate.closed||candidate.registrationComplete)return;
        final long candidateEpoch=stagedEpoch;long start=System.nanoTime();
        try {
            while(!candidate.remaining.isEmpty()) {
                var part=candidate.remaining.removeFirst();var light=part.light();
                var group=DhApi.Delayed.customRenderObjectFactory.createAbsolutePositionedGroup(
                        "madeinabyss:generated_preview_"+candidateEpoch+"_part_"+candidate.buckets.size(),part.boxes());
                if(!(group instanceof Closeable))throw new IllegalStateException("Exact DH box group lacks standard Closeable");
                candidate.buckets.add(group);candidate.physicalLights.put(group,light);groupCreated++;registrationCreatedParts++;
                group.setSsaoEnabled(false);group.setSkyLight(dev.madeinabyss.forge.probe.client.GeneratedShaderLightPresentation.presentSky(light.sky()));group.setBlockLight(light.block());
                group.setPreRenderFunc(param->{
                    preRenderCallbacks++;int encoded=dev.madeinabyss.forge.probe.client.GeneratedShaderLightPresentation.presentSky(light.sky());
                    if(group.getSkyLight()!=encoded)group.setSkyLight(encoded);
                    if(groups.staged()==candidate) {
                        // Official DH3.3.3 and Oculus1.8 consume origin only as a draw uniform;
                        // asynchronous box upload ignores it and there is no CPU group culling.
                        // Warm every staged VBO beyond the far clip without touching GL state,
                        // depth/G-buffer contents or the currently displayed scene.
                        double far=Float.isFinite(param.farClipPlane)?Math.max(0,param.farClipPlane):0;
                        double cameraY=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
                        double dy=cameraY+Math.max(1_048_576,4*far)-(double)bounds.minY();
                        group.setOriginBlockPos(new DhApiVec3d(0,dy,0));
                    } else group.setOriginBlockPos(new DhApiVec3d(0,0,0));
                });
                group.setPostRenderFunc(param->{
                    renderCallbacks++;
                    if(epoch==candidateEpoch&&groups.staged()==candidate){stagedDrawCallbacks++;hiddenWarmupDraws++;if(candidate.markDrawn(group))groups.markDrawn(candidateEpoch,candidate);}
                });
                register.add(group);candidate.registered.add(group);
                if(System.nanoTime()-start>=REGISTRATION_BUDGET_NANOS)break;
            }
            if(candidate.remaining.isEmpty()) {
                candidate.registrationComplete=true;
                if(candidate.drawn.size()==candidate.buckets.size())groups.markDrawn(candidateEpoch,candidate);
            }
            maximumOwnedGroups=Math.max(maximumOwnedGroups,ownedGroupCount());
        } finally {registrationBatches++;registrationMaxNanos=Math.max(registrationMaxNanos,System.nanoTime()-start);}
    }
    @Override public void tick(Minecraft client) {
        if(active)try{requireSession(client);if(mirrorChanged() || refinementPending&&!refreshInFlight)queueRefresh(client);}catch(RuntimeException failure){fail(failure);}
    }
    private static final class SessionEndedException extends IllegalStateException {}
    private void fail(Exception failure){
        if(failure instanceof SessionEndedException){end("generated session ended");return;}
        if(failure instanceof TerrainLodColumns.BudgetExceededException && groups.displayed()!=null) {
            if(earlyDetail!=null)earlyDetailRefusals++;
            error=failure.toString();retainedCandidateRefusals++;refreshInFlight=false;refinementPending=false;
            retireGroup(groups.beginTarget(epoch));pendingBoxes=null;pendingGeometry=List.of();pendingShadowSky=Map.of();
            if(groups.displayed().overview) {
                cancelStartup();overviewPending=false;
                phase=groups.displayed().nearOverview?"NEAR_PREVIEW_REFUSED":"OVERVIEW_REFUSED";
            }else phase="REGISTERED";
            return;
        }
        error=failure.toString();end("error");phase="REFUSED";
    }
    private void skipUnsupportedShadowPass(com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam<DhApiRenderParam> input) {
        if(!active || shaderShadowState==null || input.value.clientLevelWrapper==null
                || input.value.clientLevelWrapper.getWrappedMcObject()!=level)return;
        try {
            if(Boolean.TRUE.equals(shaderShadowState.invoke(null))){shaderShadowPassesSkipped++;input.cancelEvent();}
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot query shader shadow pass",failure);}
    }
    private boolean ownsBoxGroup(long id) {
        var displayed=groups.displayed();var staged=groups.staged();
        if(displayed!=null)for(var group:displayed.buckets)if(group.getId()==id)return true;
        if(staged!=null)for(var group:staged.buckets)if(group.getId()==id)return true;
        return false;
    }
    private int ownedGroupCount() {
        var displayed=groups.displayed();var staged=groups.staged();
        return (displayed==null?0:displayed.buckets.size())+(staged==null?0:staged.buckets.size());
    }
    private void retireGroup(Scene scene) {
        if(scene==null || scene.closed)return;
        scene.closed=true;sceneClosed++;
        scene.remaining.clear();
        RuntimeException first=null;
        for(var group:scene.buckets) {
            try {
                group.setActive(false);
                boolean removed=!scene.registered.contains(group) || register!=null && register.remove(group.getId())==group;
                if(!removed)throw new IllegalStateException("Public DH register did not remove exact owned group");
            }catch(Exception failure){if(first==null)first=new IllegalStateException("Owned DH group close failed",failure);}
            finally {
                try{((Closeable)group).close();groupClosed++;}
                catch(Exception failure){if(first==null)first=new IllegalStateException("Owned DH group close failed",failure);}
            }
        }
        if(first!=null)throw first;
    }
    @Override public synchronized void end(String reason) {
        cancelStartup();
        dev.madeinabyss.forge.probe.client.GeneratedFarShadowRenderer.clear();
        active=false;scopeActive=false;++epoch;++lifetime;
        preparationReuse=null;fullPreviewBackdrop=nearPreviewBackdrop=null;earlyDetail=null;earlyFreshTiles=Map.of();
        refreshInFlight=false;refinementPending=false;boxLightCache=Map.of();boxLightGeometryRevision=-1;
        overviewPending=false;pendingOverview=false;pendingNearOverview=false;pendingNearDetail=false;pendingEarlyDetailPreserved=false;pendingPreviewBackdrop=false;pendingRequestedDetailBoxes=0;
        if(task!=null)task.cancel(true);task=null;
        if(worker!=null)worker.shutdownNow();worker=null;
        if(lightWorker!=null)lightWorker.shutdownNow();lightWorker=null;
        baseDiskCache=null;pendingGeometry=List.of();
        pendingShadowSky=Map.of();shadowLightCache=Map.of();shadowLightGeometryRevision=-1;
        if(ownCuller!=null){DhApi.overrides.unbind(IDhApiCullingFrustum.class,ownCuller);ownCuller=null;delegate=null;}
        if(ownShadowCuller!=null){DhApi.overrides.unbind(IDhApiShadowCullingFrustum.class,ownShadowCuller);ownShadowCuller=null;shadowDelegate=null;}
        boolean cleanupFailed=false;
        for(var owned:groups.clear())try{retireGroup(owned);}catch(RuntimeException failure){error="Owned DH group cleanup: "+failure;cleanupFailed=true;}
        register=null;wrapper=null;pendingBoxes=null;publishedYExtents=null;level=null;physical=null;baseColumns=null;latestMirror=null;patchedMirror=null;patchedColumns=Map.of();detailCache=Map.of();sessionToken=null;
        restoreNearCoverage();restoreGeneration();phase=cleanupFailed?"REFUSED":"ENDED: "+reason;
    }
    @Override public void close(){if(closed)return;end("adapter shutdown");closed=true;}
    @Override public synchronized void shutdownBackground(){
        cancelStartup();
        clearEarlyCertificate();fullPreviewBackdrop=null;preparationReuse=null;
        overviewPending=false;pendingOverview=false;pendingNearOverview=false;pendingNearDetail=false;pendingEarlyDetailPreserved=false;pendingPreviewBackdrop=false;pendingRequestedDetailBoxes=0;
        pendingBoxes=null;pendingGeometry=List.of();pendingShadowSky=Map.of();refreshInFlight=false;refinementPending=false;
        active=false;closed=true;++epoch;++lifetime;
        if(task!=null)task.cancel(true);
        if(worker!=null)worker.shutdownNow();
        if(lightWorker!=null)lightWorker.shutdownNow();
        dev.madeinabyss.forge.probe.client.GeneratedFarShadowRenderer.shutdownBackground();
    }
    @Override public JsonObject status() {
        var out=new JsonObject();out.addProperty("initialized",initialized);out.addProperty("eventsBound",eventsBound);out.addProperty("active",active);
        out.addProperty("registrationBoxChunk",REGISTRATION_BOX_CHUNK);out.addProperty("registrationSoftBudgetMillis",REGISTRATION_BUDGET_NANOS/1_000_000.0);
        out.addProperty("registrationBatches",registrationBatches);out.addProperty("registrationMaxMillis",registrationMaxNanos/1_000_000.0);
        out.addProperty("registrationCreatedParts",registrationCreatedParts);out.addProperty("registrationRemainingParts",groups.staged()==null?0:groups.staged().remaining.size());
        out.addProperty("hiddenWarmupDraws",hiddenWarmupDraws);
        out.addProperty("stagedWarmupScope","Public origin uniform shifts upload draws beyond the far clip; all origins restored before atomic promotion; runtime pixel review required");
        out.addProperty("overviewEligible",overviewRequested);out.addProperty("overviewPending",overviewPending);
        out.addProperty("nearOverviewEligible",nearOverviewEligible);
        out.addProperty("nearOverviewDisplayed",groups.displayed()!=null&&groups.displayed().nearOverview);
        out.addProperty("nearOverviewSampledVoxels",nearOverviewSampledVoxels);out.addProperty("nearOverviewBuildMillis",nearOverviewBuildMillis);
        out.addProperty("estimatedNearOverviewSamples",nearOverviewPlan==null?0:nearOverviewPlan.samples());
        out.addProperty("firstNearMillis",firstNearMillis);out.addProperty("firstFullOverviewMillis",firstFullOverviewMillis);
        out.addProperty("earlyDetailEligible",earlyDetailEligible);
        out.addProperty("earlyDetailDisplayed",groups.displayed()!=null&&groups.displayed().nearDetail);
        out.addProperty("firstEarlyDetailDisplayMillis",firstNearDetailMillis);
        out.addProperty("earlyDetailBuildMillis",earlyDetailBuildMillis);out.addProperty("earlyDetailGeometryMillis",earlyDetailGeometryMillis);
        out.addProperty("earlyDetailPreparedJobs",earlyDetailPreparedJobs);out.addProperty("earlyDetailSelectedJobs",earlyDetailSelectedJobs);
        out.addProperty("earlyDetailRawAcceptedTiles",earlyDetailRawAcceptedTiles);out.addProperty("earlyDetailVisibleBoxes",earlyDetailVisibleBoxes);
        out.addProperty("earlyDetailFreshSamples",earlyDetailSampledVoxels);out.addProperty("earlyDetailChangedPlanSampleReserve",earlyChangedPlanSampleReserve);
        out.addProperty("retainedEarlyDetailPromotions",retainedEarlyDetailPromotions);out.addProperty("earlyDetailRefusals",earlyDetailRefusals);
        out.addProperty("earlyDetailDowngrades",earlyDetailDowngrades);out.addProperty("earlyDetailPlanConflicts",earlyDetailPlanConflicts);
        out.addProperty("displayedEarlyDetailPreserved",groups.displayed()!=null&&groups.displayed().earlyDetailPreserved);
        out.addProperty("earlyDetailScope","Full16m base and nearest<=128 disjoint first-plan jobs certify the same-plan terminal admission; no future geometry is sampled for this certificate. Actual accepted grids get32-ray light before fullpreview. Worst128 fresh tiles plus maximum changed-plan detail are reserved upfront; tight budgets skip this optional stage. Changed plans retain the drawn fine scene until a complete current-target scene; final cache uses original current-plan selection only.");
        out.addProperty("startupStage",startupStages==null?"NONE":startupStages.stage().name());
        out.addProperty("overviewDisplayed",groups.displayed()!=null&&groups.displayed().overview&&!groups.displayed().nearOverview);
        out.addProperty("displayedQualityStage",groups.displayed()==null?"NONE":groups.displayed().nearOverview?"NEAR_PREVIEW":groups.displayed().nearDetail?"NEAR_DETAIL":groups.displayed().overview?"FULL_PREVIEW":refinementEnabled&&!groups.displayed().refinedComplete?"PARTIAL_DETAIL":"REQUESTED");
        out.addProperty("nearOverviewScope","Temporary radius160m XZ/radius192m Y clamped to requested bounds,16m geometry/8-ray diffuse; outside this partial extent is not completed; final requested detail unchanged");
        out.addProperty("overviewStep",overviewStep);out.addProperty("overviewSampledVoxels",overviewSampledVoxels);
        out.addProperty("estimatedOverviewSamples",estimatedOverviewSamples);out.addProperty("overviewBuildMillis",overviewBuildMillis);
        out.addProperty("overviewFirstDisplayMillis",overviewFirstDisplayMillis);
        out.addProperty("firstRequestedDisplayMillis",firstRequestedDisplayMillis);
        out.addProperty("firstPartialDetailDisplayMillis",firstPartialDetailDisplayMillis);
        out.addProperty("firstVisibleDetailDisplayMillis",firstVisibleDetailDisplayMillis);
        out.addProperty("displayedRequestedDetailBoxes",groups.displayed()==null?0:groups.displayed().requestedDetailBoxes);
        out.addProperty("firstDisplayScope","First accepted scene may be bounded near preview; firstFullOverviewMillis covers the temporary panorama; firstPartialDetailDisplayMillis records partial tile acquisition including AIR; firstVisibleDetailDisplayMillis requires a promoted scene with32-ray boxes; firstRequestedDisplayMillis is the terminal full requested geometry/light only");
        out.addProperty("overviewScope","Temporary16m cold-start geometry and8-ray render diffuse; exact DDA/opacity/direct SKY; independent preview cache; requested8m/high and32-ray diffuse replace it automatically");
        out.addProperty("overviewDiffuseRays",8);out.addProperty("requestedDiffuseRays",32);
        out.addProperty("displayedDiffuseRays",groups.displayed()==null?0:groups.displayed().overview||groups.displayed().previewBackdrop?8:32);
        out.addProperty("displayedBackgroundDiffuseRays",groups.displayed()==null?0:groups.displayed().overview||groups.displayed().previewBackdrop?8:32);
        out.addProperty("displayedDetailDiffuseRays",groups.displayed()==null||groups.displayed().overview?0:32);
        out.addProperty("progressiveBackdropDisplayed",groups.displayed()!=null&&groups.displayed().previewBackdrop);
        out.addProperty("progressiveBackdropScope","Partial acquisition retains raw full-preview geometry/8-ray background, subtracts only admitted detail volumes including AIR, and solves localdetail32 rays. Terminal uses unchanged original requested base/fullplan/32 rays; only terminal writes finalscene cache.");
        out.addProperty("diffuseIrradianceLatticeMeters",32);
        out.addProperty("phase",phase);out.addProperty("completed",error.isEmpty()&&"REGISTERED".equals(phase)&&groups.displayed()!=null&&!groups.displayed().overview&&(!refinementEnabled||groups.displayed().refinedComplete)&&!refinementPending&&!refreshInFlight&&!ClientGeneratedLight.geometryObservationsPending());
        out.addProperty("progressiveCompletedTiles",refinementProgressLimit);out.addProperty("progressiveTotalTiles",refinementTotalJobs);out.addProperty("progressiveHandoffs",progressiveHandoffs);
        out.addProperty("completeDetailRefreshes",completeDetailRefreshes);
        out.addProperty("apiVersion",DhApi.getApiMajorVersion()+"."+DhApi.getApiMinorVersion()+"."+DhApi.getApiPatchVersion());out.addProperty("modVersion",DhApi.getModVersion());
        out.addProperty("testedModVersion","3.3.3 only; current run must be verified");out.addProperty("generationOverrideOwned",generationOwned);
        out.addProperty("generationDisabled",generation!=null && generation.getValue()==EDhApiGeneratorPlan.DISABLED);
        out.addProperty("overdrawOverrideOwned",overdrawOwned);if(overdraw!=null)out.addProperty("overdrawPrevention",overdraw.getValue());
        out.addProperty("cullerPriority",cullerPriority);out.addProperty("foreignOverrideCheck",ownCuller==null || DhApi.overrides.get(IDhApiCullingFrustum.class)==ownCuller);
        out.addProperty("shadowCullerOwned",ownShadowCuller!=null);out.addProperty("shadowCullerPriority",shadowCullerPriority);
        out.addProperty("shadowTerrainCullCount",shadowTerrainCulls);
        out.addProperty("shadowOverrideCheck",ownShadowCuller==null || DhApi.overrides.get(IDhApiShadowCullingFrustum.class)==ownShadowCuller);
        out.addProperty("shaderShadowObjectsSkipped",shaderShadowObjectsSkipped);
        out.addProperty("shaderShadowPassesSkipped",shaderShadowPassesSkipped);
        out.addProperty("customLodShadowScope","Oculus1.8 generic objects omitted from shadow pass to protect main G-buffer; GeneratedFarShadowRenderer separately submits coarse opaque depth/albedo/normal/SKY through verified native shadow attachments for IterationT3.2.0.");
        var group=groups.displayed();var staged=groups.staged();
        out.addProperty("objects",ownedGroupCount());out.addProperty("boxCount",group==null?(staged==null?0:staged.boxCount()):group.boxCount());out.addProperty("sourceBoxCount",sourceBoxes);
        out.addProperty("groupCreated",groupCreated);out.addProperty("groupClosed",groupClosed);out.addProperty("currentRegistered",ownedGroupCount());
        out.addProperty("sceneCreated",sceneCreated);out.addProperty("sceneClosed",sceneClosed);out.addProperty("ownedScenes",groups.size());
        if(group!=null && !group.buckets.isEmpty())out.addProperty("groupId",group.buckets.get(0).getId());
        out.addProperty("nearCulledBoxes",nearCulledBoxes);out.addProperty("mirrorCulledBoxes",mirrorCulledBoxes);out.addProperty("sampledVoxels",sampledVoxels);out.addProperty("capturedCubes",capturedCubes);
        out.addProperty("renderCallbackCount",renderCallbacks);out.addProperty("preRenderCallbackCount",preRenderCallbacks);out.addProperty("terrainCullCount",terrainCulls);
        out.addProperty("nearClipPlane",nearClip);out.addProperty("farClipPlane",farClip);out.addProperty("epoch",epoch);
        out.addProperty("levelLoadEvents",levelLoads);out.add("explicitParameters",requested.deepCopy());
        out.addProperty("connectionPreparations",connectionPreparations);out.addProperty("ignoredLevelUnloadEvents",ignoredLevelUnloads);
        out.addProperty("baseSampledVoxels",baseSampledVoxels);out.addProperty("refreshSampledVoxels",refreshSampledVoxels);out.addProperty("refreshCount",refreshCount);
        out.addProperty("refreshBuildMillis",refreshBuildMillis);out.addProperty("cachedPatchColumns",patchedColumns.size());
        out.addProperty("firstDisplayMillis",firstDisplayMillis);out.addProperty("refinementPending",refinementPending);
        out.addProperty("refreshInFlight",refreshInFlight);out.addProperty("deferredMovementRefreshes",deferredMovementRefreshes);
        out.addProperty("geometryBuildMillis",geometryBuildMillis);out.addProperty("lightingBuildMillis",lightingBuildMillis);
        var stagesJson=new JsonObject();preparationStages.forEach(stagesJson::addProperty);out.add("preparationStagesMillis",stagesJson);
        out.addProperty("preparationQueueMillis",preparationQueueMillis);out.addProperty("publicationDelayMillis",publicationDelayMillis);
        out.addProperty("verifiedPreparationTiles",verifiedPreparationTiles);
        out.addProperty("preparationTotalMillis",preparationTotalMillis);out.addProperty("verificationSampledVoxels",verificationSamples);
        out.addProperty("reusableLightSourceHits",reusableLightSourceHits);out.addProperty("reusableLightSourceMisses",reusableLightSourceMisses);
        var reuse=preparationReuse;
        out.addProperty("reusableGeometryTiles",reuse==null?0:reuse.geometry.size());
        out.addProperty("detailDiskStatus",reuse==null?"NONE":reuse.tileStatus);out.addProperty("detailDiskWarning",reuse==null?"":reuse.tileWarning);
        out.addProperty("detailDiskRestoredTiles",reuse==null?0:reuse.restoredTiles);out.addProperty("detailDiskReadMillis",reuse==null?0:reuse.tileReadMillis);out.addProperty("detailDiskWriteMillis",reuse==null?0:reuse.tileWriteMillis);
        out.addProperty("reusableGeometryEstimatedBytes",reuse==null?0:reuse.geometry.weight());
        out.addProperty("reusableGeometryEvictions",reuse==null?0:reuse.geometry.evictions());
        out.addProperty("reusableLightProbes",reuse==null?0:reuse.light.values.size());
        out.addProperty("reusableLightEvictions",reuse==null?0:reuse.light.values.evictions());
        out.addProperty("reusableRequestedBoxLights",reuse==null?0:reuse.requestedBoxes.size());
        out.addProperty("reusableRequestedBoxLightBytes",reuse==null?0:reuse.requestedBoxes.weight());
        out.addProperty("reusableRequestedBoxLightEvictions",reuse==null?0:reuse.requestedBoxes.evictions());
        out.addProperty("reusablePreviewBoxLights",reuse==null?0:reuse.previewBoxes.size());
        out.addProperty("reusedCumulativeBoxLights",reusedCumulativeBoxLights);out.addProperty("reusedPreviewBoxLights",reusedPreviewBoxLights);
        out.addProperty("preparationReuseScope","Exact tile/box/grid keys; immutable complete source identity plus geometry revision;96MiB geometry/32768tiles,96MiB requested32-ray boxes/786432entries,32MiB separate preview8-ray boxes/262144entries,262144 exposed probes; nonlocal edits invalidate all caches; misses preserve exact generation.");
        out.addProperty("baseBuildMillis",baseBuildMillis);out.addProperty("detailGeometryBuildMillis",detailGeometryBuildMillis);
        out.addProperty("diskCacheStatus",diskCacheStatus);out.addProperty("diskCacheWarning",diskCacheWarning);
        out.addProperty("diskCacheReadMillis",diskCacheReadMillis);out.addProperty("diskCacheWriteMillis",diskCacheWriteMillis);
        out.addProperty("finalDiskCacheStatus",finalDiskCacheStatus);out.addProperty("finalDiskCacheWarning",finalDiskCacheWarning);
        out.addProperty("finalDiskCacheReadMillis",finalDiskCacheReadMillis);out.addProperty("finalDiskCacheWriteMillis",finalDiskCacheWriteMillis);
        out.addProperty("reusedBoxLights",reusedBoxLights);out.addProperty("cachedBoxLights",boxLightCache.size());
        out.addProperty("lightWorkers",lightWorkers);out.addProperty("exposedSkyCacheHits",exposedSkyCacheHits);
        out.addProperty("exposedSkyEvaluations",exposedSkyEvaluations);out.addProperty("exposedSkyCachedProbes",exposedSkyCachedProbes);
        out.addProperty("verifiedBoxLights",verifiedBoxLights);
        out.addProperty("boxLightSpatialOrderEnabled",boxLightSpatialOrderEnabled);
        out.addProperty("boxLightSpatialOrderMode",boxLightSpatialOrderMode);
        out.addProperty("boxLightSpatialOrderedJobs",boxLightSpatialOrderedJobs);
        out.addProperty("boxLightSpatialOrderingMillis",boxLightSpatialOrderingNanos/1_000_000.0);
        out.addProperty("boxLightSpatialOrderScope","Fresh complete requested light only; exact wide Morton across signed64m tiles, X/Z/Y within each tile; complete scalar results restored to original box indices before verification and publication.");
        out.addProperty("publishedVisualRevision",publishedVisualRevision);out.addProperty("publishedPhysicalGeneration",publishedPhysicalGeneration);out.addProperty("cancelEpoch",epoch);
        out.addProperty("publishedGeometryRevision",publishedGeometryRevision);
        out.addProperty("workerRunning",task!=null && !task.isDone());out.addProperty("scopeActive",scopeActive);
        out.addProperty("working",active && !"REGISTERED".equals(phase));
        out.addProperty("displayedGroups",group==null?0:group.buckets.size());out.addProperty("uploadingGroups",staged==null?0:staged.buckets.size());
        out.addProperty("displayedShadowProxyBoxes",group==null?0:group.sourceGeometry.size());
        out.addProperty("shadowProxyScope","Current authoritative base-step geometry with identical near exclusions; coarse shadow approximation, main high detail unchanged.");
        out.addProperty("displayedScenes",group==null?0:1);out.addProperty("uploadingScenes",staged==null?0:1);
        var opacitySource=latestMirror==null?null:latestMirror.source();
        out.addProperty("opacityPagesEnabled",opacitySource!=null&&opacitySource.opacityPagesEnabled());
        out.addProperty("opacityCacheHits",opacitySource==null?0:opacitySource.opacityCacheHits());
        out.addProperty("opacityCacheMisses",opacitySource==null?0:opacitySource.opacityCacheMisses());
        out.addProperty("opacityCacheEvictions",opacitySource==null?0:opacitySource.opacityCacheEvictions());
        out.addProperty("opacityCacheResidentPayloadBytes",opacitySource==null?0:opacitySource.opacityCacheResidentBytes());
        var bucketStatus=new JsonArray();
        if(group!=null)for(var bucket:group.buckets){
            var item=new JsonObject();item.addProperty("sky",bucket.getSkyLight());item.addProperty("block",bucket.getBlockLight());
            item.addProperty("physicalSky",group.physicalLights.get(bucket).sky());
            item.addProperty("boxes",bucket.size());bucketStatus.add(item);
        }
        out.add("displayedLightBuckets",bucketStatus);
        out.addProperty("maximumOwnedGroups",maximumOwnedGroups);out.addProperty("handoffCount",handoffCount);out.addProperty("retainedRefreshCount",retainedRefreshCount);
        out.addProperty("maximumOwnedScenes",maximumOwnedScenes);
        out.addProperty("stagedDrawCallbacks",stagedDrawCallbacks);out.addProperty("missingDisplayedRefreshFrames",missingDisplayedRefreshFrames);
        if(staged!=null && !staged.buckets.isEmpty())out.addProperty("uploadingGroupId",staged.buckets.get(0).getId());out.addProperty("uploadingEpoch",stagedEpoch);
        out.addProperty("refinementEnabled",refinementEnabled);out.addProperty("refinementSampledVoxels",refinementSampledVoxels);
        out.addProperty("quality",quality);out.addProperty("effectiveBaseStep",limits==null?0:limits.horizontalStep());
        out.addProperty("aggregateSampleCap",limits==null?0:limits.maxSampleVoxels());out.addProperty("finalBoxCap",limits==null?0:limits.maxBoxes());
        out.addProperty("temporaryFragmentCap",limits==null?0:8L*limits.maxBoxes());
        out.addProperty("estimatedBaseSamples",estimatedBaseSamples);out.addProperty("estimatedDetailSamples",estimatedDetailSamples);
        out.addProperty("estimatedInitialSamples",estimatedInitialSamples);
        var bandsStatus=new JsonArray();
        boolean seamActive="high".equals(quality)&&latestMirror!=null&&latestMirror.coverage()!=null&&latestMirror.coverage().complete();
        if(seamActive){int[] margins={32,64,128},steps={1,2,4};for(int i=0;i<3;i++){var b=new JsonObject();b.addProperty("nativeMargin",margins[i]);b.addProperty("step",steps[i]);bandsStatus.add(b);}}
        else for(var band:detailBands){var b=new JsonObject();b.addProperty("radius",band.radius());b.addProperty("step",band.horizontalStep());bandsStatus.add(b);}
        out.addProperty("refinementStrategy",seamActive?"native-volume-3d-seam-v1":"camera-centred-fallback");
        out.add("refinementBands",bandsStatus);out.addProperty("refinementSampleCap",detailSampleCap);
        out.addProperty("refinementRegions",refinementRegions);out.addProperty("refinementCacheHits",refinementCacheHits);out.addProperty("refinementCachedTiles",detailCache.size());
        out.addProperty("refinementAcceptedCells",refinementAcceptedCells);out.addProperty("refinementRejectedCells",refinementRejectedCells);
        out.addProperty("refinementOneMetreTiles",refinementOneMetre);out.addProperty("refinementTwoMetreTiles",refinementTwoMetre);out.addProperty("refinementFourMetreTiles",refinementFourMetre);
        out.addProperty("refinementAdmissionCountsScope","Last newly selected scene only; zero on complete scene disk-cache hit");
        out.addProperty("retainedCandidateRefusals",retainedCandidateRefusals);
        out.addProperty("sourceStateCacheHits",sourceStateCacheHits);out.addProperty("sourceStateCacheMisses",sourceStateCacheMisses);
        out.addProperty("sourceStateCacheResidents",sourceStateCacheResidents);out.addProperty("sourceStateCacheResidentBytes",sourceStateCacheResidentBytes);
        out.addProperty("groupLifecycleScope","Displayed scene retained through worker and GPU upload; every replacement light bucket must postRender before next setup promotes and closes predecessor. At most two owned scenes, each up to256light buckets; close queues GPU deletion, not driver completion acknowledgement.");
        if(session!=null)out.addProperty("generatedSession",session);if(fingerprint!=null)out.addProperty("generatedFingerprint",fingerprint);
        if(publishedYExtents!=null){var extents=new JsonArray();extents.add(publishedYExtents[0]);
            extents.add(publishedYExtents[1]);out.add("globalYExtentsExclusive",extents);}
        out.addProperty("visualScope","DIAGNOSTIC approximate native colors; per-box maximum direct SKY at exposed top/side sample neighbors and scalar source BLOCK emissions, grouped by light pair. Coarse box face illumination and indirect SKY/BLOCK are approximate; native propagated light and shaders require separate verification.");
        out.addProperty("preservationScope","Verified immutable full saved geometry source and current mirrors override generator; unsupported states refuse entire scene. Only current native mirrors clip LOD geometry; historical saved cubes remain visible in LOD.");
        out.addProperty("nearSeamScope","Exact AABB subtraction preserves far fragments; quality preset controls XYZ 1/2/4/8m refinement and cached base. Fixed16XZ/64Y cache retains geometry; contiguous equal-material Y intervals merge before final budget. Public boxes use approximate colors; seamless native transition and shaders remain unverified.");
        if(error!=null)out.addProperty("error",error);return out;
    }
    /** Worker-owned once per prepared scene; diagnostic polling must not scan all boxes on the render thread. */
    private static long[] yExtents(List<TerrainLodColumns.Box> boxes) {
        if(boxes.isEmpty())return null;
        long min=Long.MAX_VALUE,max=Long.MIN_VALUE;
        for(var box:boxes){min=Math.min(min,box.minY());max=Math.max(max,box.maxYExclusive());}
        return new long[]{min,max};
    }
}

