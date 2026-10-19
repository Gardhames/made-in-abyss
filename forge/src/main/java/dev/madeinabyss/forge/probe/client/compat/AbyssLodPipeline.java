package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.kernel.concurrent.BoundedOrderedWork;
import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** CPU-only bounded preparation. Public proposals never own DH scenes, native buffers or mutable cubes. */
public final class AbyssLodPipeline implements AutoCloseable {
    public enum Stage { NEAR_FINE, FAR, REFINING, PARTIAL_PAGES, COMPLETE }
    public record Target(String session,long lifetime,String sourceIdentity,long sourceRevision,
                         long nativeRevision,long resourceRevision,long requestRevision) {
        public Target {Objects.requireNonNull(session);Objects.requireNonNull(sourceIdentity);
            if(session.isEmpty()||sourceIdentity.isEmpty())throw new IllegalArgumentException("Complete source/session identity required");}
    }
    public record Camera(double x,double y,double z,double forwardX,double forwardY,double forwardZ,double fov,double aspect) {
        public Camera(double x,double y,double z,double forwardX,double forwardY,double forwardZ){this(x,y,z,forwardX,forwardY,forwardZ,90,16.0/9);}
        public Camera(double x,double y,double z){this(x,y,z,0,0,1);}
        public Camera {if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Finite camera required");
            double length=Math.hypot(Math.hypot(forwardX,forwardY),forwardZ);
            if(!Double.isFinite(length)||length==0)throw new IllegalArgumentException("Finite nonzero view direction required");
            forwardX/=length;forwardY/=length;forwardZ/=length;
            if(!Double.isFinite(fov)||!Double.isFinite(aspect)||fov<=0||fov>=179||aspect<=0)throw new IllegalArgumentException("Valid projection extent required");}
    }
    public record Limits(long maxFreshSamples,int maxBoxes,int maxFragments,long maxLedgerBytes,int maxJobs,int initialJobs) {
        public Limits {if(maxFreshSamples<1||maxBoxes<1||maxFragments<maxBoxes||maxLedgerBytes<1||maxJobs<1||maxJobs>262144||initialJobs<1||initialJobs>8||initialJobs>maxJobs)
            throw new IllegalArgumentException("Invalid bounded LOD limits");}
        public static Limits defaults(){return new Limits(120_000_000,400_000,1_600_000,96L*1024*1024,262144,8);}
    }
    public record Light(int sky,int block) {
        public Light {if(sky<0||sky>15||block<0||block>15)throw new IllegalArgumentException("Exact scalar SKY/BLOCK required");}
    }
    @FunctionalInterface public interface BoxLighting {Light light(TerrainLodColumns.Box box);
        default boolean batchLightEnabled(){return false;}
        default List<Light> lightBatch(List<TerrainLodColumns.Box> owners,Runnable checkpoint){var out=new ArrayList<Light>(owners.size());for(var box:owners){checkpoint.run();out.add(light(box));}checkpoint.run();return List.copyOf(out);}
        default int rgba(TerrainLodColumns.Box box){return surfaceColor(box);}
        default boolean provisionalLightEnabled(){return false;}
        default Light provisionalLight(TerrainLodColumns.Box box){return null;}
        default Map<String,Object> presentationLightStatus(){return Map.of();}
        default long maxSurfaceGpuBytes(){return MAX_SURFACE_GPU_BYTES;}}
    public record LitBox(TerrainLodColumns.Box box,Light light) {
        public LitBox {Objects.requireNonNull(box);Objects.requireNonNull(light);}
    }
    public record Input(Target target,Camera camera,TerrainLodColumns.Bounds nearBounds,TerrainLodColumns.Bounds worldBounds,
                        List<TerrainLodRefinement.Band> bands,List<TerrainLodClipper.BoundsLong> nativeCuts,
                        TrailNetwork.SampleSource terrain,BoxLighting lighting,Limits limits,AbyssNativeDensity.Snapshot nativeDensity) {
        public Input(Target target,Camera camera,TerrainLodColumns.Bounds nearBounds,TerrainLodColumns.Bounds worldBounds,
                List<TerrainLodRefinement.Band> bands,List<TerrainLodClipper.BoundsLong> nativeCuts,
                TrailNetwork.SampleSource terrain,BoxLighting lighting,Limits limits) {
            this(target,camera,nearBounds,worldBounds,bands,nativeCuts,terrain,lighting,limits,null);
        }
        public Input {Objects.requireNonNull(target);Objects.requireNonNull(camera);Objects.requireNonNull(nearBounds);Objects.requireNonNull(worldBounds);
            bands=List.copyOf(bands);nativeCuts=List.copyOf(nativeCuts);Objects.requireNonNull(terrain);Objects.requireNonNull(lighting);Objects.requireNonNull(limits);
            if(bands.isEmpty())throw new IllegalArgumentException("Requested distance bands required");}
    }
    public record Proposal(Target target,long sequence,Stage stage,List<LitBox> boxes,List<AbyssLodFineLedger.Grid> fine,
                           List<TerrainLodColumns.Box> rawBackdrop,List<TerrainLodColumns.Box> shadowBoxes,
                           int farStep,long chargedSamples,boolean requestedComplete,SurfaceQuadPlan.Prepared surfacePlan,SurfaceQuadPlan.PageSet surfacePages,boolean exactLightComplete) {
        public Proposal(Target target,long sequence,Stage stage,List<LitBox> boxes,List<AbyssLodFineLedger.Grid> fine,
                List<TerrainLodColumns.Box> rawBackdrop,List<TerrainLodColumns.Box> shadowBoxes,int farStep,long chargedSamples,boolean requestedComplete,SurfaceQuadPlan.Prepared surfacePlan,SurfaceQuadPlan.PageSet surfacePages){
            this(target,sequence,stage,boxes,fine,rawBackdrop,shadowBoxes,farStep,chargedSamples,requestedComplete,surfacePlan,surfacePages,requestedComplete);}
        public Proposal(Target target,long sequence,Stage stage,List<LitBox> boxes,List<AbyssLodFineLedger.Grid> fine,
                List<TerrainLodColumns.Box> rawBackdrop,List<TerrainLodColumns.Box> shadowBoxes,int farStep,long chargedSamples,boolean requestedComplete,SurfaceQuadPlan.Prepared surfacePlan){
            this(target,sequence,stage,boxes,fine,rawBackdrop,shadowBoxes,farStep,chargedSamples,requestedComplete,surfacePlan,null);}
        public Proposal(Target target,long sequence,Stage stage,List<LitBox> boxes,List<AbyssLodFineLedger.Grid> fine,
                List<TerrainLodColumns.Box> rawBackdrop,List<TerrainLodColumns.Box> shadowBoxes,int farStep,long chargedSamples,boolean requestedComplete){
            this(target,sequence,stage,boxes,fine,rawBackdrop,shadowBoxes,farStep,chargedSamples,requestedComplete,null,null);}
        public Proposal {Objects.requireNonNull(target);Objects.requireNonNull(stage);boxes=List.copyOf(boxes);fine=List.copyOf(fine);
            rawBackdrop=List.copyOf(rawBackdrop);shadowBoxes=List.copyOf(shadowBoxes);
            if(sequence<1||chargedSamples<0)throw new IllegalArgumentException("Malformed proposal counters");}
        public boolean fullBackgroundReady(){return farStep>0&&!rawBackdrop.isEmpty()&&(stage==Stage.FAR||stage==Stage.COMPLETE);}
    }
    private record Prepared(Proposal proposal,AbyssLodFineLedger.Candidate ledger,SurfaceQuadPages.Partial pagePublication,Input scalarView) {
        private Prepared(Proposal proposal,AbyssLodFineLedger.Candidate ledger){this(proposal,ledger,null,null);}
        private Prepared(Proposal proposal,AbyssLodFineLedger.Candidate ledger,SurfaceQuadPages.Partial pagePublication){this(proposal,ledger,pagePublication,null);}
    }
    private record RawKey(TerrainLodColumns.Bounds bounds,int step) { }
    private record WindowKey(Camera camera,TerrainLodColumns.Bounds near,TerrainLodColumns.Bounds world,
            List<TerrainLodRefinement.Band> bands,int maxJobs,int initialJobs) { }
    private record FarPrepared(TerrainLodColumns.Bounds bounds,TerrainLodColumns.Result raw,int step,
                               AbyssLodSurfacePriority.SurfaceIndex surfaces,List<TerrainLodColumns.Box> ordered) {
        private FarPrepared {ordered=List.copyOf(ordered);}
    }
    private record PageLightResult(TerrainLodColumns.Box box,Light light) { }
    private record PageLightJob(List<TerrainLodColumns.Box> owners,Future<List<LitBox>> future,
                                ArrayBlockingQueue<PageLightResult> ready,boolean local,String scalarViewKey) {
        private PageLightJob {owners=List.copyOf(owners);}
    }
    private static final String POLICY="source-xyz-incremental-local-pressure-pages-v9";
    private static final int FIRST_FINE_BATCH=128,MAX_FINE_BATCH=512,FAR_LIGHT_BATCH=1024;
    private static final int STREAM_FINE_BATCH=2048;
    private static final int WARM_STREAM_FINE_BATCH=4096;
    private static final int STREAM_LIGHT_BATCH=8192;
    private static final int MAX_DEFERRED_SCALAR_OWNERS=400_000;
    /** Exact v14 pages need a larger finite admission cap than the old partial panorama. */
    public static final long MAX_SURFACE_GPU_BYTES=512L*1024*1024;
    /** True3D15.5 plus its lithology owners; legacy profiles retain the original admission. */
    public static final long MAX_SURFACE_GPU_BYTES_155=640L*1024*1024;
    private static final int SURFACE_MAX_FACES=1_000_000;
    private static final long SURFACE_MAX_GPU_BYTES=96L*1024*1024;
    private static final int SURFACE_MAX_CPU_FACES=2_000_000;
    private static final long SURFACE_MAX_RETAINED_FACE_BYTES=192L*1024*1024;
    private final boolean surfaceMesh,streaming;
    private final Path cacheDirectoryOverride;
    private final int workerCount,pageWorkerCount,residentScale;
    public AbyssLodPipeline(){this(false);}
    public AbyssLodPipeline(boolean surfaceMesh){this(surfaceMesh,surfaceMesh);}
    public AbyssLodPipeline(boolean surfaceMesh,Path cacheDirectory){this(surfaceMesh,surfaceMesh,cacheDirectory);}
    /** The legacy scheduler remains available to the opt-in, reduced-window quality oracle. */
    /** DH detail is background work: it must never outrank the native near window (cube loader/vanilla workers run at 4,
     * light at 5) or the render thread. Java 2 maps to THREAD_PRIORITY_LOWEST on Windows. */
    static final int BACKGROUND_PRIORITY=Integer.getInteger("mia.lod.threadPriority",2);
    AbyssLodPipeline(boolean surfaceMesh,boolean streaming){
        this(surfaceMesh,streaming,null);
    }
    AbyssLodPipeline(boolean surfaceMesh,boolean streaming,Path cacheDirectory){
        cacheDirectoryOverride=cacheDirectory;
        this.surfaceMesh=surfaceMesh;this.streaming=surfaceMesh&&streaming;
        residentScale=this.streaming?Integer.getInteger("mia.bench.lodResidentScale",6):1;
        if(residentScale<1||residentScale>6)throw new IllegalArgumentException("Resident sizing scale must be1..6");
        workerCount=this.streaming?Math.min(11,Math.max(4,Runtime.getRuntime().availableProcessors()-1)):4;
        pageWorkerCount=this.streaming?Math.min(3,workerCount):1;
        workers=new ThreadPoolExecutor(workerCount,workerCount,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(workerCount*2),
            task->{var thread=new Thread(task,"mia-abyss-lod-worker");thread.setDaemon(true);thread.setPriority(BACKGROUND_PRIORITY);return thread;});
        // All parallel maps are orchestrated by the owner, never by a worker. Sharing
        // this bounded pool keeps page capacity useful for source/light between maps.
        pageWorkers=workers;
    }
    private final ThreadPoolExecutor coordinator=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),
            task->{var thread=new Thread(task,"mia-abyss-lod-coordinator");thread.setDaemon(true);thread.setPriority(BACKGROUND_PRIORITY);return thread;});
    /** Dispatch/wait only: generator, exact light and page work stay on the bounded compute pool. */
    private final ThreadPoolExecutor fineOrchestrator=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),
            task->{var thread=new Thread(task,"mia-abyss-lod-source-dispatch");thread.setDaemon(true);thread.setPriority(BACKGROUND_PRIORITY);return thread;});
    private final ThreadPoolExecutor workers;
    private final ExecutorService pageWorkers;
    /** Worker-only ownership registry also fences close/reopen across different Pipeline instances. */
    private record ScalarRegistration(Context owner,AbyssLodScalarArchive archive,AbyssLodPipeline pipeline) { }
    private final Object rawPersistenceLock=new Object();
    private static final Object SCALAR_REGISTRY_LOCK=new Object();
    private static final Map<Path,ScalarRegistration> SCALAR_REGISTRY=new HashMap<>();
    private static final AtomicBoolean SCALAR_RETIRE_REQUESTED=new AtomicBoolean(),SCALAR_RETIRE_RUNNING=new AtomicBoolean();
    private static final ThreadPoolExecutor SCALAR_RETIRE_EXECUTOR=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),
        task->{var thread=new Thread(task,"mia-abyss-lod-scalar-retire");thread.setDaemon(true);thread.setPriority(BACKGROUND_PRIORITY);return thread;});
    private volatile long generation;
    private volatile Input latest;
    private Target target;
    private Future<Prepared> work;
    private Future<?> backgroundExactFeed;
    private Prepared pending;
    private AbyssLodFineLedger ledger;
    private long ledgerCap,sequence,acknowledged;
    private boolean closed,completed,exactLightComplete;
    private String error="";
    private volatile long startedNanos;
    private volatile long targetStartedNanos;
    private volatile double fullBackgroundStartedMillis=-1;
    private volatile boolean fineWorldAcknowledged;
    private Context context;

    /** One source lease. Only the coordinator mutates its acquisition cursors and owned grids. */
    private static final class Context {
        final AbyssLodStageDiagnostics diagnostics=new AbyssLodStageDiagnostics();
        final AbyssLodGridResidency.Cache gridResidency=new AbyssLodGridResidency.Cache();
        final Input source;
        final boolean streaming;
        final int workerCount;
        final long residentLedgerBytes;
        final SurfaceQuadPages.Limits surfaceLimits;
        final BoundedReuseCache<RawKey,TerrainLodColumns.Result> raw;
        final BoundedReuseCache<TerrainLodColumns.Box,Light> light;
        final AtomicLong charged=new AtomicLong();
        final AtomicLong peakCharged=new AtomicLong();
        final AtomicLong scratchCharged=new AtomicLong(),peakScratchCharged=new AtomicLong();
        volatile long fineBatchScratchBytes;
        volatile long maximumFineScratchBytes;
        final LongAdder actualSamples=new LongAdder(),rawHits=new LongAdder(),lightHits=new LongAdder(),lightMisses=new LongAdder();
        final LongAdder lightCompleted=new LongAdder();
        final LongAdder lightTaskCpuNanos=new LongAdder(),lightTaskWallNanos=new LongAdder(),lightTaskOwners=new LongAdder();
        final LongAdder exactLookupNanos=new LongAdder(),exactComputeNanos=new LongAdder(),exactCommitNanos=new LongAdder();
        final LongAdder exactSelectNanos=new LongAdder(),exactAdoptNanos=new LongAdder();
        final LongAdder exactWorkerStarts=new LongAdder(),exactWorkerEnds=new LongAdder(),exactReadyProduced=new LongAdder(),exactReadyAdopted=new LongAdder();
        // Opt-in diagnostic history; production cache ownership/bounds are unchanged.
        final boolean metrics=Boolean.getBoolean("mia.bench.lodMetrics");
        final dev.madeinabyss.kernel.concurrent.BoundedDiagnosticHistory<TerrainLodColumns.Box> measuredLight=metrics&&Boolean.parseBoolean(System.getProperty("mia.bench.lodRepeatHistory","true"))?
            new dev.madeinabyss.kernel.concurrent.BoundedDiagnosticHistory<>(Integer.getInteger("mia.bench.lodRepeatHistoryCapacity",400_000)):null;
        final LongAdder repeatedLight=new LongAdder();
        volatile long plannedFineSamples;
        final List<PageLightJob> pageLightJobs=new ArrayList<>();
        final Set<TerrainLodColumns.Box> claimedLight=new HashSet<>();
        volatile int surfaceLightInFlight,surfaceLightClaimed,surfaceLocalLightInFlight,surfaceBackgroundLightInFlight;
        volatile long surfaceLightTaskStarts,surfaceLightTaskCompletions,surfaceNoChangeYields;
        boolean surfacePublicationDirty,surfaceCacheRestorePending;
        final LongAdder archiveRestoredTiles=new LongAdder(),archiveRestoredCells=new LongAdder();
        final LinkedHashMap<RawKey,AbyssLodFineLedger.Grid> ownedFine=new LinkedHashMap<>();
        TerrainLodColumns.Bounds indexBounds,farBounds;
        TerrainLodColumns.Result index,far;
        AbyssLodSurfacePriority.SurfaceIndex indexSurfaces,farSurfaces;
        List<TerrainLodRefinement.Region> jobs=List.of();
        List<TerrainLodColumns.Box> orderedFar=List.of();
        WindowKey jobWindow;
        WindowKey planningView;
        Set<RawKey> requestedFineKeys;
        List<TerrainLodRefinement.Region> acquisitionJobs=List.of();
        Set<RawKey> acquisitionFragmentKeys=Set.of();
        boolean preparingRetargetWitness;
        Camera initialView;
        List<AbyssLodFineLedger.Grid> witnessPins=List.of();
        List<TerrainLodRefinement.Region> witnessJobs=List.of();
        Camera witnessView;
        int fineCursor,farCursor,farStep=16,nearPrefixes;
        boolean initialPrepared,diskLoaded;
        volatile boolean startupEmpty;
        Path disk;
        String diskCode,tileKey;
        AbyssRawTileArchive archive;
        volatile AbyssLodScalarArchive scalarArchive;
        volatile boolean scalarRetired;
        AbyssLodCompletionSeal scalarCompletionSeal;
        String scalarViewKey="",scalarDeferralExhaustedKey="";
        boolean scalarSealHit;
        volatile boolean scalarMissDeferral;
        final Set<TerrainLodColumns.Box> scalarDeferredOwners=new HashSet<>();
        long scalarDeferredAttempts,scalarDeferredOverflows,scalarDeferredRetired;
        int scalarDeferredTerminal=-1,scalarDeferredTransient=-1;
        volatile String scalarSealError="";
        volatile String scalarArchiveError="";
        volatile Map<String,Object> archiveMetrics=Map.of();
        volatile String archiveError="";
        volatile int deferredFineGrids;
        final Set<RawKey> deferredSource=new HashSet<>();
        volatile int deferredSourceJobs;
        List<AbyssLodFineLedger.Grid> renderInputGrids;
        List<AbyssLodFineLedger.Grid> renderProtectedGrids;
        List<TerrainLodClipper.BoundsLong> renderNativeCuts;
        TerrainLodColumns.Result renderFarRaw;
        int renderFarCursor=-1,renderFarStep=-1,renderBoxCap=-1,renderFragmentCap=-1;
        AbyssLodActiveSelection.Admission renderAdmission;
        List<TerrainLodColumns.Box> shadowGeometry=List.of();
        volatile int renderSurfaceRemoved,renderSurfaceInput,renderSurfaceKept;
        volatile boolean renderSurfaceBudgetRefused;
        volatile long renderSurfaceBuilds;
        volatile double renderSurfaceMillis;
        final SurfaceQuadPages pages=new SurfaceQuadPages(SurfaceQuadPages.Limits.defaults(),128);
        final IncrementalSurfacePages incrementalPages;
        final AbyssLodPublishedLightSummary publishedLightSummary;
        final IncrementalLodComposition incrementalComposition;
        AbyssLodFineLedger.Candidate incrementalCandidate=new AbyssLodFineLedger.Candidate(List.of(),0);
        long incrementalResource=-1,angularPageCursor;
        boolean incrementalRetry;
        volatile long incrementalCompositionUpdates,incrementalRootIndexBuilds,incrementalFineCells,incrementalFarRoots,incrementalIndexBytes;
        SurfaceQuadPages.Geometry surfaceGeometry;
        SurfaceQuadPlan.Prepared surfacePrepared;
        SurfaceQuadPlan.PageSet surfacePagePrepared;
        SurfaceQuadPlan.PageSet surfacePublishedPages;
        Target surfacePublishedTarget;
        List<TerrainLodClipper.BoundsLong> surfacePublishedCuts=List.of();
        volatile int surfacePendingPages,surfaceCurrentPages,surfaceChangedPages;
        volatile long surfacePartialPublications,surfaceFullPublications;
        int surfaceOptionalRetain=-1;
        SurfaceOwnerLightPlan<Light> surfaceLightPlan;
        List<LitBox> surfaceLitBoxes;
        volatile long surfaceLightPlanBuilds,surfaceLitBoxListBuilds;
        volatile double surfaceLightPlanMillis;
        boolean surfaceLightPending;
        volatile int surfaceFaceCount,surfaceOwnerCount,surfaceLightRemaining,surfaceLocalLightRemaining;
        volatile boolean surfaceGeometryReady,surfaceProvisionalLight;
        dev.madeinabyss.kernel.lod.SurfaceProvisionalLight pageProvisional;
        long priorityLightCursor;
        volatile dev.madeinabyss.kernel.light.VisibleLightAudit visibleAudit;
        volatile Target visibleAuditTarget,geometryWorldTarget;
        Target probeBootstrapTarget;volatile boolean probeBootstrapPending,probeVisiblePending;
        volatile boolean probesSkippedForGpu;
        volatile long probeQuotaStartNanos,probeQuotaLimitedNanos;
        volatile double probeVisibleReadyMillis=-1,exactFirstWorldMillis=-1;
        volatile Target exactWorldTarget;
        volatile dev.madeinabyss.kernel.light.VisibleLightAudit.Frame preparedAuditFrame;
        volatile double geometryFirstWorldMillis=-1,visibleAuditSetupMillis;
        Target persistedGeometryTarget;
        AbyssLodActiveSelection.Selection acquisitionDisplaySelection;
        Target acquisitionDisplayTarget;
        TerrainLodColumns.Result acquisitionDisplayFar;
        volatile long stagedSourceBytes,coalescedSourceYields;
        volatile int activePageWorkers=3,pageQuantum=256;
        volatile int surfaceRawFaceCount;
        volatile long surfaceAdmissionRetries;
        volatile double surfaceGreedyMillis;
        AbyssNativeDensity.Snapshot importingNative;
        List<TerrainLodColumns.Bounds> nativeJobs=List.of();
        int nativeCursor;
        volatile long lastNativePublication=-1,lastNativeEpoch=-1;
        volatile long nativeRawTiles,nativeRawCells;
        volatile int pendingNativeCubes;
        final Set<RawKey> angularBootstrap=new HashSet<>();
        volatile int angularBootstrapJobs,angularBootstrapRemaining,angularPositiveGrids,angularSectors,undispatchedSurfaceJobs;
        volatile int plannedJobs,preparedJobs,rawBoxes,litFarBoxes,surfaceHints,lastFineBatch;
        volatile String cacheStatus="MEMORY",phase="LOCAL_SURFACE_INDEX";
        volatile Future<FarPrepared> farWork;
        volatile FinePrefetch fineWork;
        volatile long finePrefetchStarts,finePrefetchAdoptions,finePrefetchRetirements;
        volatile int lastFarLightQuantum,farAcquisitions;
        boolean continuePreparation;
        long fineVersion,lastProposedFineVersion=-1,lastPublicationNanos;
        int lastProposedFarCursor=-1;
        Target lastProposedTarget;
        AbyssLodActiveSelection.Selection cachedSelection;
        List<AbyssLodFineLedger.Grid> selectionLedger,selectionPins;
        List<TerrainLodRefinement.Band> selectionBands;
        Camera selectionCamera;
        long selectionVersion=-1;
        volatile long selectionBuilds,selectionReuses;
        Context(Input source,boolean streaming,int workerCount,int residentScale) {
            this.source=source;this.streaming=streaming;this.workerCount=workerCount;var limits=source.limits();
            residentLedgerBytes=Math.multiplyExact(limits.maxLedgerBytes(),residentScale);
            var defaults=SurfaceQuadPages.Limits.defaults();long gpu=streaming?source.lighting().maxSurfaceGpuBytes():defaults.maxGpuBytes();
            if(streaming&&gpu!=MAX_SURFACE_GPU_BYTES&&gpu!=MAX_SURFACE_GPU_BYTES_155)throw new IllegalArgumentException("Unsupported bounded surface GPU admission");
            surfaceLimits=new SurfaceQuadPages.Limits(defaults.maxPages(),defaults.maxIncidenceRefs()*residentScale,defaults.maxIndexBytes()*residentScale,
                defaults.maxRawFaces()*residentScale,defaults.maxRetainedFaceBytes()*residentScale,
                streaming?Math.toIntExact(gpu/SurfaceQuadPlan.GPU_BYTES_PER_FACE):defaults.maxFaces(),gpu);
            incrementalPages=new IncrementalSurfacePages(surfaceLimits,128);
            publishedLightSummary=streaming?new AbyssLodPublishedLightSummary(128,surfaceLimits.maxPages(),surfaceLimits.maxRawFaces()):null;
            incrementalComposition=new IncrementalLodComposition(Math.multiplyExact(limits.maxFragments(),residentScale),Math.multiplyExact(limits.maxFragments(),residentScale),96L*1024*1024*residentScale);
            raw=new BoundedReuseCache<>(limits.maxLedgerBytes(),Math.min(limits.maxJobs(),32768),
                    value->128L+80L*value.boxes().size());
            light=new BoundedReuseCache<>(Math.min(limits.maxLedgerBytes(),128L*limits.maxBoxes()),limits.maxBoxes(),value->128L);
            incrementalPages.setLightLookup(box->{var scalar=light.get(box);return scalar==null?null:incrementalPages.scalarOwner(box,scalar.sky(),scalar.block(),source.lighting()::rgba);},()->BoxCacheRevision.read(light));
        }
    }

    /** Owner-only latest target request; worker data is immutable and publication is revision-checked. */
    public synchronized void request(Input input) {
        Objects.requireNonNull(input);if(closed)throw new IllegalStateException("Pipeline closed");
        if(completed&&input.equals(latest))return;
        long requestedLedgerCap=Math.multiplyExact(input.limits().maxLedgerBytes(),residentScale);
        if(ledger!=null&&ledgerCap!=requestedLedgerCap)throw new IllegalArgumentException("Retained ledger cap changed during pipeline life");
        boolean changedSource=target==null||!sameSource(target,input.target())
            ||target.resourceRevision()!=input.target().resourceRevision()&&input.lighting() instanceof AbyssLodPhysicalLighting;
        if(startedNanos==0)startedNanos=System.nanoTime();
        if(changedSource){if(work!=null)work.cancel(true);if(backgroundExactFeed!=null)backgroundExactFeed.cancel(true);backgroundExactFeed=null;if(context!=null){retireScalars(context);var far=context.farWork;if(far!=null)far.cancel(true);cancelFine(context);}
            work=null;coordinator.getQueue().clear();++generation;if(context!=null)cancelPageLight(context);workers.purge();
            fineWorldAcknowledged=false;fullBackgroundStartedMillis=-1;context=new Context(input,streaming,workerCount,residentScale);}
        // A camera/native/resource retarget changes composition, never the owned source work.
        pending=null;completed=false;exactLightComplete=false;if(context!=null)context.surfaceGeometryReady=false;error="";
        if(ledger==null){ledgerCap=requestedLedgerCap;ledger=new AbyssLodFineLedger(ledgerCap);}else if(changedSource)ledger.clear();
        target=input.target();latest=input;targetStartedNanos=System.nanoTime();if(work==null)schedule();
    }
    /** A cache publication cannot replace an already staged display target. */
    public synchronized void updateNativeCache(Input input){
        Objects.requireNonNull(input);if(closed||latest==null)throw new IllegalStateException("No active source cache lease");
        var old=latest;
        if(!input.target().equals(old.target())||!input.camera().equals(old.camera())||!input.nearBounds().equals(old.nearBounds())
                ||!input.worldBounds().equals(old.worldBounds())||!input.bands().equals(old.bands())||!input.nativeCuts().equals(old.nativeCuts())
                ||!input.limits().equals(old.limits())||input.terrain()!=old.terrain()||input.lighting()!=old.lighting())
            throw new IllegalArgumentException("Native cache update changed the display contract");
        latest=input;if(work==null&&pending==null&&canContinue(context))schedule();
    }
    private void schedule() {
        if(!finishBackgroundExact()||!error.isEmpty())return;
        final long token=generation,number=++sequence;final Context owned=context;
        work=coordinator.submit(()->prepare(owned,token,number));
    }
    private Prepared prepare(Context owned,long token,long number) {
        Prepared result=null;
        try{result=prepareScene(owned,token,number);return result;}
        finally{checkpointArchive(owned,token);boolean complete=result!=null&&result.proposal().requestedComplete()&&result.proposal().exactLightComplete();checkpointScalars(owned,token,complete);
            if(complete&&result.scalarView()!=null&&owned.scalarCompletionSeal!=null){checkScalar(owned,token);
                try{owned.scalarCompletionSeal.save(result.scalarView());}
                catch(IOException failed){owned.scalarSealError="WRITE_REFUSED:"+failed.getMessage();throw new IllegalStateException("Completed exact view seal could not persist",failed);}}}
    }
    private Prepared prepareScene(Context owned,long token,long number) {
        check(token);owned.continuePreparation=false;initDisk(owned,token);Input input=current(token);
        ingestNative(owned,input,token);
        ensureIndex(owned,input,token);
        if(!fineWorldAcknowledged&&!owned.startupEmpty){
            if(!owned.initialPrepared)prepareInitial(owned,input,token);
        }else {
            input=current(token);ensureFar(owned,input,16,token);ensureJobs(owned,input,token);
            int batch=surfaceMesh?(streaming?streamFineBatch(owned):32):owned.far==null?FIRST_FINE_BATCH:Math.min(MAX_FINE_BATCH,FIRST_FINE_BATCH<<Math.min(2,owned.nearPrefixes));
            // Exact local light remains a publication requirement. It is not an acquisition
            // barrier: RAW scratch, retained ledger and face indices bound source residency.
            if(streaming||!surfaceMesh||!owned.surfaceLightPending)prepareFineBatch(owned,input,batch,token);
            // Completed immutable source is adopted without waiting for an active acquisition.
            // Fine can be published with no FAR backdrop while the reserved worker remains busy.
            if(ensureFar(owned,current(token),16,token)){
                ensureJobs(owned,current(token),token);
                owned.farCursor=surfaceMesh?owned.orderedFar.size():Math.min(owned.orderedFar.size(),owned.farCursor+FAR_LIGHT_BATCH);
            }
        }
        // Work survives cheap retargets. Recompose its immutable RAW against the latest holes.
        // If a request races this loop, poll schedules another composition using the same caches.
        for(int attempt=0;attempt<3;attempt++){
            input=current(token);
            if(fineWorldAcknowledged){
                if(owned.far!=null&&!owned.farBounds.equals(input.worldBounds()))ensureFar(owned,input,16,token);
                ensureJobs(owned,input,token);
            }
            if(!fineWorldAcknowledged&&!owned.startupEmpty&&owned.initialView!=null&&!sameInitialView(owned.initialView,input.camera())){
                owned.initialPrepared=false;owned.ownedFine.clear();owned.fineVersion++;ensureIndex(owned,input,token);prepareInitial(owned,input,token);
            }
            if(input.target().equals(owned.lastProposedTarget)&&owned.fineVersion==owned.lastProposedFineVersion
                    &&owned.farCursor==owned.lastProposedFarCursor&&!surfaceMesh)return null;
            if(surfaceMesh){var result=surfaceProposal(owned,input,token,number);if(result!=null)return result;
                if(!input.target().equals(current(token).target()))continue;return null;}
            var selection=selection(owned,input);var candidate=selection.candidate();
            List<TerrainLodColumns.Box> background=owned.far==null?List.of():owned.orderedFar.subList(0,owned.farCursor);
            AbyssLodFineLedger.Composition composition;
            try{var admission=renderAdmission(owned,selection,background,input);
                candidate=admission.candidate();composition=admission.composition();owned.deferredFineGrids=admission.deferredGrids();}
            catch(TerrainLodColumns.BudgetExceededException actualSceneCap){
                if(owned.far==null||owned.farStep==64)throw actualSceneCap;
                // Only an observed box/fragment cap can choose a different far source lattice.
                if(!ensureFar(owned,input,owned.farStep*2,token))return null;
                owned.farCursor=Math.min(owned.orderedFar.size(),FAR_LIGHT_BATCH);continue;
            }
            var geometry=composition.boxes();
            if(geometry.isEmpty()&&!fineWorldAcknowledged&&!owned.startupEmpty){
                owned.initialPrepared=false;prepareInitial(owned,input,token);continue;
            }
            if(geometry.isEmpty()&&fineWorldAcknowledged&&owned.far==null){
                // A new native hole can hide this entire fine prefix. Keep its RAW and let the
                // next positive background release the stage; an empty upload is never an ACK.
                if(!ensureFar(owned,input,16,token))return null;
                owned.farCursor=Math.min(owned.orderedFar.size(),FAR_LIGHT_BATCH);continue;
            }
            if(geometry.isEmpty()&&owned.far==null)return null;
            if(geometry.isEmpty()&&owned.far!=null&&owned.farCursor<owned.orderedFar.size()){
                owned.farCursor=Math.min(owned.orderedFar.size(),owned.farCursor+FAR_LIGHT_BATCH);continue;
            }
            // Finish exact requested fine light first. A turn then evaluates at most1024 new
            // background fragments, even when native clipping split an original coarse box.
            var fineLit=light(owned,composition.fine(),token);
            var misses=new ArrayList<TerrainLodColumns.Box>();
            for(var box:composition.background())if(owned.light.get(box)==null&&misses.size()<FAR_LIGHT_BATCH)misses.add(box);
            owned.lastFarLightQuantum=misses.size();light(owned,misses,token);
            var lit=new ArrayList<LitBox>(geometry.size());boolean missing=false;
            for(var box:composition.background()){
                var value=owned.light.get(box);if(value==null){missing=true;break;}
                owned.lightHits.increment();lit.add(new LitBox(box,value));
            }
            if(missing){owned.continuePreparation=true;return null;}
            lit.addAll(fineLit);check(token);
            if(!input.target().equals(current(token).target()))continue;
            boolean complete=owned.far!=null&&owned.farBounds.equals(input.worldBounds())&&owned.farWork==null
                    &&owned.farCursor==owned.orderedFar.size()&&owned.fineCursor==owned.jobs.size()&&owned.deferredFineGrids==0&&owned.deferredSourceJobs==0&&owned.undispatchedSurfaceJobs==0;
            if(complete&&geometry.isEmpty())throw new IllegalStateException("Current source view has no positive geometry outside native coverage; no WORLD ACK can be claimed");
            Stage stage=!fineWorldAcknowledged&&!owned.startupEmpty?Stage.NEAR_FINE:complete?Stage.COMPLETE:owned.far==null||owned.farCursor==0?Stage.REFINING:Stage.FAR;
            owned.phase=stage.name();trimOwned(owned,candidate);
            owned.litFarBoxes=owned.farCursor;owned.lastProposedTarget=input.target();
            owned.lastProposedFineVersion=owned.fineVersion;owned.lastProposedFarCursor=owned.farCursor;
            if(complete)persist(owned,input,token);
            return new Prepared(new Proposal(input.target(),number,stage,lit,candidate.grids(),owned.far==null?List.of():owned.far.boxes(),
                    owned.shadowGeometry,owned.far==null?0:owned.farStep,owned.charged.get(),complete),candidate);
        }
        throw new Retargeted();
    }
    private static final class Retargeted extends CancellationException { }
    /** Publish exact ready canonical pages while unrelated owners are still being lit. */
    private Prepared surfaceProposal(Context owned,Input input,long token,long number) {
        boolean provisional=streaming&&fineWorldAcknowledged&&input.lighting().provisionalLightEnabled();
        owned.surfaceProvisionalLight=provisional;
        boolean stableSource=provisional&&owned.far!=null&&owned.farWork==null&&owned.fineWork==null&&owned.fineCursor==owned.jobs.size()
            &&owned.deferredSourceJobs==0&&owned.undispatchedSurfaceJobs==0;
        int preparationWorkers=stableSource?Math.min(8,Math.max(1,owned.workerCount-1)):pageWorkerCount;
        int pageQuantum=stableSource?Math.multiplyExact(owned.surfaceLimits.maxPages(),2):256;
        owned.activePageWorkers=preparationWorkers;owned.pageQuantum=pageQuantum;
        var actualSelection=selection(owned,input);var selection=actualSelection;
        boolean coalesced=provisional&&owned.far!=null&&owned.fineCursor<owned.jobs.size()
            &&Boolean.parseBoolean(System.getProperty("mia.lod.coalesceSource155","true"));
        boolean capFallback=false;long stagingCharge=0,stagingUnion=0;
        if(coalesced){
            if(owned.acquisitionDisplaySelection==null||!input.target().equals(owned.acquisitionDisplayTarget)||owned.acquisitionDisplayFar!=owned.far){
                owned.acquisitionDisplaySelection=actualSelection;owned.acquisitionDisplayTarget=input.target();owned.acquisitionDisplayFar=owned.far;
            }
            // Retain the stable panorama and target under the same byte cap. Shared immutable
            // payloads are charged once; distinct equal geometry keeps its independent charge.
            stagingCharge=Math.addExact(actualSelection.candidate().bytes(),owned.acquisitionDisplaySelection.candidate().bytes());
            owned.stagedSourceBytes=owned.gridResidency.retainedBytes(actualSelection.candidate(),owned.acquisitionDisplaySelection.candidate());
            stagingUnion=owned.stagedSourceBytes;
            capFallback=owned.stagedSourceBytes>owned.residentLedgerBytes;
            if(owned.stagedSourceBytes<=owned.residentLedgerBytes)selection=owned.acquisitionDisplaySelection;else coalesced=false;
        }
        if(!coalesced){owned.acquisitionDisplaySelection=null;owned.acquisitionDisplayTarget=null;owned.acquisitionDisplayFar=null;owned.stagedSourceBytes=0;}
        boolean bulkFarChanged=owned.renderFarRaw!=owned.far;
        boolean same=!owned.incrementalRetry&&owned.renderFarRaw==owned.far&&selection.candidate().grids().equals(owned.renderInputGrids)
            &&selection.protectedGrids().equals(owned.renderProtectedGrids)&&input.nativeCuts().equals(owned.renderNativeCuts);
        if(!same){
            owned.diagnostics.composition(input.target(),owned.fineVersion,!selection.candidate().grids().equals(owned.renderInputGrids),
                !selection.protectedGrids().equals(owned.renderProtectedGrids),!input.nativeCuts().equals(owned.renderNativeCuts),
                owned.renderFarRaw!=owned.far,owned.incrementalRetry,capFallback,stagingCharge,stagingUnion,owned.residentLedgerBytes);
            if(!selection.candidate().grids().equals(owned.renderInputGrids)||!selection.protectedGrids().equals(owned.renderProtectedGrids)
                    ||!input.nativeCuts().equals(owned.renderNativeCuts)||owned.renderFarRaw!=owned.far)owned.surfaceOptionalRetain=-1;
            // Record the attempted lease before admission; the committed composition stays
            // transactional. A retry of this lease must preserve the optional retirement.
            owned.renderInputGrids=selection.candidate().grids();owned.renderProtectedGrids=selection.protectedGrids();
            owned.renderNativeCuts=input.nativeCuts();owned.renderFarRaw=owned.far;
            var limited=surfaceSelection(owned,selection,input.camera());long started=System.nanoTime();
            var checkpoint=owned.incrementalComposition.checkpoint();
            try{
                if(streaming&&limited.candidate().bytes()>owned.residentLedgerBytes)
                    throw new TerrainLodColumns.BudgetExceededException("Exact fine ledger exceeds resident byte cap: "+limited.candidate().bytes());
                long compStart=owned.diagnostics.now(),compCpu=owned.diagnostics.cpu();
                var delta=owned.incrementalComposition.update(owned.far==null?List.of():owned.orderedFar,limited.candidate(),input.nativeCuts());
                owned.diagnostics.finish("composition",compStart,compCpu);
                long indexStart=owned.diagnostics.now(),indexCpu=owned.diagnostics.cpu();
                try{owned.incrementalPages.update(delta.removed(),delta.added(),delta.fineFootprints(),!bulkFarChanged,streaming?pageWorkers:null,streaming?preparationWorkers:1);}
                catch(RuntimeException refused){owned.incrementalComposition.restore(checkpoint);throw refused;}
                finally{owned.diagnostics.finish("pageIndex",indexStart,indexCpu);}
                if(!delta.removed().isEmpty()||!delta.added().isEmpty())owned.surfaceCacheRestorePending=true;
                owned.incrementalCandidate=limited.candidate();owned.incrementalCompositionUpdates++;
                owned.incrementalRootIndexBuilds+=delta.rootIndexBuilds();owned.incrementalFineCells+=delta.recomputedFineCells();
                owned.incrementalFarRoots+=delta.recomputedFarRoots();owned.incrementalIndexBytes=delta.retainedIndexBytes();
                owned.renderSurfaceInput=delta.totalBoxes();owned.renderSurfaceBuilds++;
                owned.renderSurfaceMillis=(System.nanoTime()-started)/1_000_000.0;
                owned.incrementalRetry=false;
            }catch(TerrainLodColumns.BudgetExceededException actualCap){owned.incrementalRetry=true;if(deferSurface(owned,selection,limited.candidate()))return null;throw actualCap;}
            owned.renderSurfaceBudgetRefused=false;
        }
        boolean resourceChanged=owned.incrementalResource!=input.target().resourceRevision();
        if(resourceChanged){
            if(owned.incrementalResource!=-1)owned.incrementalPages.invalidatePrepared();
            owned.incrementalResource=input.target().resourceRevision();owned.surfaceCacheRestorePending=true;
        }
        if(coalesced&&same&&!resourceChanged&&owned.surfacePendingPages==0&&input.target().equals(owned.surfacePublishedTarget)){
            owned.continuePreparation=true;owned.coalescedSourceYields++;owned.phase="ACQUIRING_TARGET_SOURCE";
            try{Thread.sleep(2);}catch(InterruptedException canceled){Thread.currentThread().interrupt();check(token);}return null;
        }
        owned.deferredFineGrids=selection.candidate().grids().size()-owned.incrementalCandidate.grids().size();
        updateScalarDeferral(owned,input);
        var pageOrder=surfacePageOrder(input.camera(),128);
        var presentation=input.lighting() instanceof AbyssLodPhysicalLighting&&owned.source.lighting() instanceof AbyssLodPhysicalLighting
            ?owned.source.lighting():input.lighting();
        java.util.function.Function<TerrainLodColumns.Box,SurfaceQuadPlan.Owner> temporary=null;
        owned.pageProvisional=null;
        if(!input.target().equals(owned.probeBootstrapTarget)){owned.probeBootstrapPending=false;owned.probeVisiblePending=false;}
        boolean gpuExactReady=presentation instanceof AbyssLodPhysicalLighting gpu&&gpu.gpuExactReady();
        owned.probesSkippedForGpu=gpuExactReady;
        if(gpuExactReady){
            if(owned.probeVisiblePending)owned.probeQuotaLimitedNanos=System.nanoTime()-owned.probeQuotaStartNanos;
            owned.probeBootstrapPending=false;owned.probeVisiblePending=false;
        }
        if(provisional){
            if(presentation instanceof AbyssLodPhysicalLighting physical
                    &&!gpuExactReady
                    &&(!physical.probesAfterGeometryAck()||input.target().equals(owned.geometryWorldTarget))){
                owned.pageProvisional=physical.provisionalLighting(input.camera(),b->owned.incrementalPages.scalarOwner(b,0,0,input.lighting()::rgba).rgba());temporary=owned.pageProvisional;
                if(physical.probesAfterGeometryAck()&&!input.target().equals(owned.probeBootstrapTarget)){
                    owned.probeBootstrapTarget=input.target();owned.probeBootstrapPending=true;
                    owned.probeVisiblePending=true;owned.probeQuotaStartNanos=System.nanoTime();owned.probeQuotaLimitedNanos=0;owned.probeVisibleReadyMillis=-1;
                }
            }else temporary=box->{check(token);var value=Objects.requireNonNull(presentation.provisionalLight(box));return owned.incrementalPages.scalarOwner(box,value.sky(),value.block(),input.lighting()::rgba);};
        }
        if(owned.probeBootstrapPending&&input.target().equals(owned.probeBootstrapTarget)){
            // A retained WORLD panorama stays visible while bounded background quanta replace
            // its presentation cache. In-frustum pages precede off-screen fair work.
            pageOrder=Comparator.comparingInt((SurfaceQuadPlan.PageKey k)->AbyssLodPhysicalLighting.visiblePage(k,input.camera())?0:1).thenComparing(pageOrder);
            pageQuantum=64;owned.pageQuantum=pageQuantum;owned.surfacePublicationDirty=true;
        }
        if(!input.target().equals(owned.visibleAuditTarget)){owned.visibleAudit=null;owned.preparedAuditFrame=null;}
        // Audit selection is explicitly after the first geometry WORLD ACK. Its time is
        // included in visible-light latency, never hidden in geometry COMPLETE.
        if(input.target().equals(owned.geometryWorldTarget)&&!input.target().equals(owned.visibleAuditTarget)
                &&(owned.pageProvisional!=null||gpuExactReady)&&presentation instanceof AbyssLodPhysicalLighting physical){
            long auditStart=System.nanoTime();var c=input.camera();
            var v=new dev.madeinabyss.kernel.light.ProvisionalSkyPages.View(c.x(),c.y(),c.z(),c.forwardX(),c.forwardY(),c.forwardZ(),c.fov(),c.aspect());
            var samples=owned.incrementalPages.visibleAudit(v,pageOrder,1024,p->physical.visibleAirProbe(c,p));
            owned.visibleAudit=samples.isEmpty()?null:new dev.madeinabyss.kernel.light.VisibleLightAudit(input.target().toString(),samples,2,.95);
            owned.visibleAuditTarget=input.target();owned.visibleAuditSetupMillis=(System.nanoTime()-auditStart)/1e6;
        }
        if(owned.probeBootstrapPending&&owned.visibleAudit!=null){
            var proved=owned.visibleAudit.visiblePages();
            pageOrder=Comparator.comparingInt((SurfaceQuadPlan.PageKey k)->proved.contains(k)?0:1).thenComparing(pageOrder);
        }
        boolean backgroundShown=owned.surfacePartialPublications>0||owned.surfaceFullPublications>0;
        int missingBefore=pageNumber(owned.incrementalPages.status(),"visibleMissing");
        int adopted=adoptPageLight(owned,token);
        dispatchPageLight(owned,pageOrder,backgroundShown,token);
        adopted+=adoptPageLight(owned,token);
        boolean cacheRestored=pageNumber(owned.incrementalPages.status(),"visibleMissing")<missingBefore;
        if(streaming){var debt=owned.incrementalPages.status();
            // Ready work survives the publication throttle. Otherwise the next pass
            // could forget adopted scalars and wait forever on an unrelated owner.
            if(!same||resourceChanged||adopted>0||cacheRestored)owned.surfacePublicationDirty=true;
            owned.surfaceLightRemaining=pageNumber(debt,"visibleMissing");
            owned.surfaceLocalLightRemaining=pageNumber(debt,"localVisibleMissing");
            owned.surfaceLightPending=owned.surfaceLocalLightRemaining>2048;
            if(input.target().equals(owned.lastProposedTarget)&&owned.surfaceLightInFlight>0
                    &&System.nanoTime()-owned.lastPublicationNanos<TimeUnit.MILLISECONDS.toNanos(500)){
                owned.continuePreparation=true;owned.surfaceNoChangeYields++;owned.phase="COALESCING_EXACT_PAGES";
                try{Thread.sleep(2);}catch(InterruptedException canceled){Thread.currentThread().interrupt();check(token);}return null;
            }
        }
        if(owned.scalarMissDeferral&&same&&!resourceChanged&&!cacheRestored&&adopted==0&&owned.surfaceLightInFlight==0
                &&!owned.surfacePublicationDirty&&input.target().equals(owned.lastProposedTarget)){
            owned.continuePreparation=true;owned.surfaceNoChangeYields++;owned.phase="WAITING_WARM_SOURCE_GEOMETRY";return null;
        }
        // A pending WORLD upload cannot hold all light workers. Exact independent tasks
        // keep running, while an idle coordinator avoids sorting/aggregating the scene.
        if(same&&!resourceChanged&&!cacheRestored&&adopted==0&&owned.surfaceLightInFlight>0
                &&(!streaming||!owned.surfacePublicationDirty&&input.target().equals(owned.lastProposedTarget))){
            long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(5);
            do{check(token);adopted+=adoptPageLight(owned,token);if(adopted>0)break;
                try{Thread.sleep(1);}catch(InterruptedException canceled){Thread.currentThread().interrupt();check(token);}
            }while(System.nanoTime()<until);
            if(adopted==0){owned.surfaceNoChangeYields++;owned.continuePreparation=true;owned.phase="ASYNC_PAGE_OWNER_LIGHT";return null;}
            if(streaming)owned.surfacePublicationDirty=true;
            dispatchPageLight(owned,pageOrder,backgroundShown,token);
        }
        check(token);if(!input.target().equals(current(token).target()))return null;
        SurfaceQuadPages.Partial partial;long greedy=System.nanoTime();
        // Exact scalars already belong to the immutable context source. Retargeting an
        // equivalent physical input must reuse that same presentation authority/cache too.
        long pagesStart=owned.diagnostics.now(),pagesCpu=owned.diagnostics.cpu();
        try{partial=streaming?owned.incrementalPages.preparePartial(retainedSurfacePages(owned,input),pageQuantum,pageOrder,pageWorkers,preparationWorkers,owned.surfaceCacheRestorePending,temporary)
                :owned.incrementalPages.preparePartial(retainedSurfacePages(owned,input),16,pageOrder);}
        catch(TerrainLodColumns.BudgetExceededException actualCap){owned.incrementalRetry=true;if(deferSurface(owned,selection,owned.incrementalCandidate))return null;throw actualCap;}
        finally{owned.diagnostics.finish("pagePrepare",pagesStart,pagesCpu);}
        // Hitting the quantum may leave additional ready pages, even when the only
        // remaining worker is blocked. A subsequent pass must drain those pages too.
        if(streaming){owned.surfaceCacheRestorePending=false;owned.surfacePublicationDirty=partial.changedKeys().size()>=pageQuantum;}
        if(input.target().equals(owned.probeBootstrapTarget)){
            if(owned.probeVisiblePending&&!owned.incrementalPages.pendingProvisional(owned.pageProvisional,
                    k->owned.visibleAudit!=null?owned.visibleAudit.visiblePages().contains(k):AbyssLodPhysicalLighting.visiblePage(k,input.camera()))){
                owned.probeVisiblePending=false;owned.probeQuotaLimitedNanos=System.nanoTime()-owned.probeQuotaStartNanos;
                owned.probeVisibleReadyMillis=(System.nanoTime()-targetStartedNanos)/1e6;
            }
            if(partial.pendingPages()==0)owned.probeBootstrapPending=false;
        }
        var pageStatus=owned.incrementalPages.status();owned.surfaceLightRemaining=pageNumber(pageStatus,"visibleMissing");
        owned.surfaceLocalLightRemaining=pageNumber(pageStatus,"localVisibleMissing");
        owned.surfaceOwnerCount=pageNumber(pageStatus,"visibleOwners");owned.surfaceRawFaceCount=pageNumber(pageStatus,"rawFaces");
        owned.renderSurfaceKept=owned.surfaceOwnerCount;owned.renderSurfaceRemoved=Math.max(0,owned.renderSurfaceInput-owned.surfaceOwnerCount);
        var pages=partial.published();owned.surfacePagePrepared=pages;owned.surfaceFaceCount=pages.faces();
        owned.surfacePendingPages=partial.pendingPages();owned.surfaceCurrentPages=partial.currentKeys().size();owned.surfaceChangedPages=partial.changedKeys().size();
        owned.surfaceGreedyMillis=(System.nanoTime()-greedy)/1_000_000.0;
        // Bound local fine/cut work independently of a distant background light backlog.
        // Both debts still prevent terminal completion and never weaken page coverage.
        owned.surfaceLightPending=owned.surfaceLocalLightRemaining>2048;
        owned.continuePreparation=partial.pendingPages()>0||owned.surfaceLightRemaining>0||owned.surfaceLightInFlight>0;
        if(pages.faces()==0){owned.surfaceLightPending=true;owned.continuePreparation=true;owned.phase="WAITING_POSITIVE_PAGE";return null;}
        boolean geometryReady=owned.far!=null&&owned.farBounds.equals(input.worldBounds())&&owned.farWork==null&&owned.fineCursor==owned.jobs.size()
            &&owned.deferredFineGrids==0&&owned.deferredSourceJobs==0&&owned.undispatchedSurfaceJobs==0
            &&(partial.pendingPages()==0||same&&!resourceChanged&&owned.surfaceGeometryReady&&input.target().equals(owned.surfacePublishedTarget));
        boolean exactReady=geometryReady&&owned.surfaceLightRemaining==0&&owned.surfaceLightInFlight==0;
        boolean complete=geometryReady&&(exactReady||provisional);owned.surfaceGeometryReady=geometryReady;
        if(!complete&&partial.changedKeys().isEmpty()&&input.target().equals(owned.lastProposedTarget)&&owned.fineVersion==owned.lastProposedFineVersion){
            owned.continuePreparation=partial.pendingPages()>0||owned.surfaceLightRemaining>0||owned.surfaceLightInFlight>0;owned.phase="PAGE_OWNER_LIGHT";return null;
        }
        var targetKeys=owned.incrementalPages.targetKeys();var publishedKeys=new HashSet<SurfaceQuadPlan.PageKey>();
        for(var page:pages.pages())publishedKeys.add(page.key());
        List<LitBox> lit;AbyssLodFineLedger.Candidate displayedLedger;
        if(streaming){
            // Independent immutable reductions share the page pool only after geometry has
            // finished. No page worker recursively waits on that same executor.
            var grids=owned.incrementalCandidate.grids();var currentKeys=partial.currentKeys();
            var stableTarget=Set.copyOf(targetKeys);var stablePublished=Set.copyOf(publishedKeys);
            var angular=Set.copyOf(owned.angularBootstrap);
            long reductionStart=owned.diagnostics.now(),reductionCpu=owned.diagnostics.cpu();
            Future<List<LitBox>> lighting=pageWorkers.submit(()->owned.publishedLightSummary.build(pages));
            Future<AbyssLodFineLedger.Candidate> coverage=pageWorkers.submit(()->publishedCoverage(grids,currentKeys,stableTarget,stablePublished));
            Future<int[]> progress=pageWorkers.submit(()->angularCounts(angular,grids));
            try{lit=await(lighting);displayedLedger=await(coverage);var counts=await(progress);
                owned.angularBootstrapRemaining=counts[0];owned.angularPositiveGrids=counts[1];}
            finally{owned.diagnostics.finish("pageReduction",reductionStart,reductionCpu);if(!lighting.isDone())lighting.cancel(true);if(!coverage.isDone())coverage.cancel(true);if(!progress.isDone())progress.cancel(true);}
        }else{lit=publishedLight(pages);displayedLedger=publishedCoverage(owned.incrementalCandidate.grids(),partial.currentKeys(),targetKeys,publishedKeys);}
        owned.surfaceLitBoxes=lit;owned.surfaceLitBoxListBuilds++;
        if(complete&&!owned.scalarDeferredOwners.isEmpty()){
            int terminal=0;for(var value:lit)if(owned.scalarDeferredOwners.contains(value.box()))terminal++;
            owned.scalarDeferredTerminal=terminal;owned.scalarDeferredTransient=owned.scalarDeferredOwners.size()-terminal;
        }
        var readyGrids=displayedLedger.grids();
        var stage=!fineWorldAcknowledged&&!owned.startupEmpty?Stage.NEAR_FINE:complete?Stage.COMPLETE:owned.far==null?Stage.REFINING:partial.pendingPages()>0?Stage.PARTIAL_PAGES:Stage.FAR;
        owned.phase=stage.name();trimOwned(owned,coalesced?actualSelection.candidate():owned.incrementalCandidate);owned.litFarBoxes=owned.orderedFar.size();if(!streaming)angularProgress(owned);
        owned.lastProposedTarget=input.target();owned.lastProposedFineVersion=owned.fineVersion;owned.lastProposedFarCursor=owned.farCursor;
        owned.lastPublicationNanos=System.nanoTime();
        if(complete&&!input.target().equals(owned.persistedGeometryTarget)){persist(owned,input,token);owned.persistedGeometryTarget=input.target();}
        if(owned.visibleAudit!=null&&input.target().equals(owned.visibleAuditTarget)){
            for(var sample:owned.visibleAudit.samples()){
                var known=owned.light.get(sample.box());
                if(known!=null)owned.visibleAudit.reference(sample.box(),known.sky());
                else{Integer exact=owned.incrementalPages.exactAuditSky(sample.box());
                    if(exact!=null)owned.visibleAudit.reference(sample.box(),exact);}
            }
            owned.preparedAuditFrame=owned.visibleAudit.presentedFrame(number,pages);
        }
        return new Prepared(new Proposal(input.target(),number,stage,lit,readyGrids,owned.far==null?List.of():owned.far.boxes(),List.of(),
            owned.far==null?0:owned.farStep,owned.charged.get(),complete,pages.pages().get(0).prepared(),pages,exactReady),displayedLedger,partial,input);
    }
    /** A certified completed view permits delaying only unknown intermediate scalars. */
    private void updateScalarDeferral(Context owned,Input input){
        var seal=owned.scalarCompletionSeal;if(!streaming||seal==null)return;
        String key=seal.key(input);
        if(!key.equals(owned.scalarViewKey)){
            owned.scalarMissDeferral=false;owned.claimedLight.removeAll(owned.scalarDeferredOwners);owned.scalarDeferredOwners.clear();
            owned.scalarViewKey=key;owned.scalarSealHit=seal.contains(input);owned.scalarDeferralExhaustedKey="";
            owned.scalarDeferredAttempts=owned.scalarDeferredOverflows=owned.scalarDeferredRetired=0;
            owned.scalarDeferredTerminal=owned.scalarDeferredTransient=-1;
        }
        boolean sourceComplete=owned.far!=null&&owned.farBounds.equals(input.worldBounds())&&owned.farWork==null
            &&owned.fineWork==null&&owned.fineCursor==owned.jobs.size()&&owned.deferredFineGrids==0
            &&owned.deferredSourceJobs==0&&owned.undispatchedSurfaceJobs==0;
        // Initial fine must always make exact progress: acquisition starts only after
        // its WORLD ACK, including when an old seal outlives a corrupted archive page.
        boolean defer=owned.scalarSealHit&&fineWorldAcknowledged&&!sourceComplete&&!key.equals(owned.scalarDeferralExhaustedKey);
        if(!defer&&owned.scalarMissDeferral)owned.claimedLight.removeAll(owned.scalarDeferredOwners);
        owned.scalarMissDeferral=defer;
    }
    private static List<LitBox> publishedLight(SurfaceQuadPlan.PageSet pages){
        var litByBox=new LinkedHashMap<TerrainLodColumns.Box,Light>();
        for(var page:pages.pages())for(var owner:page.prepared().owners())litByBox.put(owner.box(),new Light(owner.sky(),owner.block()));
        var lit=new ArrayList<LitBox>(litByBox.size());for(var entry:litByBox.entrySet())lit.add(new LitBox(entry.getKey(),entry.getValue()));
        return List.copyOf(lit);
    }
    private static AbyssLodFineLedger.Candidate publishedCoverage(List<AbyssLodFineLedger.Grid> grids,Set<SurfaceQuadPlan.PageKey> current,
            Set<SurfaceQuadPlan.PageKey> target,Set<SurfaceQuadPlan.PageKey> published){
        var ready=new ArrayList<AbyssLodFineLedger.Grid>();long bytes=0;
        for(var grid:grids)if(SurfacePageCoverage.covers(grid.bounds(),current,target,published,128)){
            ready.add(grid);bytes=Math.addExact(bytes,128+80L*grid.raw().boxes().size());}
        return new AbyssLodFineLedger.Candidate(ready,bytes);
    }
    private static int[] angularCounts(Set<RawKey> angular,List<AbyssLodFineLedger.Grid> grids){
        var byKey=new HashMap<RawKey,AbyssLodFineLedger.Grid>();for(var grid:grids)byKey.put(new RawKey(grid.bounds(),grid.step()),grid);
        int remaining=0,positive=0;for(var key:angular){AbyssLodFineLedger.Grid best=null;
            for(int step=1;step<=4;step*=2){best=byKey.get(new RawKey(key.bounds(),step));if(best!=null)break;}
            if(best==null)remaining++;else if(!best.raw().boxes().isEmpty())positive++;}
        return new int[]{remaining,positive};
    }
    private static <T> T await(Future<T> future){try{return future.get();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("Page reduction canceled");}
        catch(ExecutionException failed){if(failed.getCause() instanceof RuntimeException runtime)throw runtime;
            if(failed.getCause() instanceof Error error)throw error;throw new IllegalStateException("Page reduction failed",failed.getCause());}}
    private static int pageNumber(Map<String,Object> status,String key){var value=status.get(key);if(!(value instanceof Number number))throw new IllegalStateException("Missing persistent page counter: "+key);return Math.toIntExact(number.longValue());}
    private void dispatchPageLight(Context owned,Comparator<SurfaceQuadPlan.PageKey> nearest,boolean backgroundShown,long token){
        long selectStart=System.nanoTime();try{
        // Reserve one worker for current source work and one additional slot during FAR
        // acquisition. No task recursively submits to the same bounded worker pool.
        boolean acquiring=owned.far==null||farPending(owned)||owned.fineCursor<owned.jobs.size();
        int maximum=owned.streaming?(acquiring?acquisitionLightSlots(owned):Math.max(1,owned.workerCount-1)):owned.far==null||farPending(owned)?2:3;
        // Cold transient owners are not a useful exact-light target. The initial positive
        // witness stays exact; then source/page work owns the pool until geometry is stable.
        boolean awaitingGeometry=owned.streaming&&fineWorldAcknowledged&&owned.source.lighting().provisionalLightEnabled()&&!owned.surfaceGeometryReady;
        if(awaitingGeometry)maximum=0;
        if(owned.probeVisiblePending)maximum=Math.min(maximum,2);
        synchronized(owned.pageLightJobs){
            // A retarget can leave ten old exact-light tasks after source acquisition
            // restarts. Restore the source/page capacity even before new local geometry
            // arrives; otherwise its very acquisition can wait behind held background light.
            if(owned.streaming&&(acquiring||awaitingGeometry||owned.probeVisiblePending))while(owned.pageLightJobs.size()>maximum){
                int remove=owned.pageLightJobs.size()-1;
                for(int i=remove;i>=0;i--)if(!owned.pageLightJobs.get(i).local()){remove=i;break;}
                retirePageLight(owned,owned.pageLightJobs.remove(remove));
            }
            boolean localPending=pageNumber(owned.incrementalPages.status(),"localVisibleMissing")>0;
            // A footprint can promote an already claimed whole owner while a background
            // batch is waiting on an unrelated prefix. Release that dependency too.
            if(localPending){var iterator=owned.pageLightJobs.iterator();while(iterator.hasNext()){var job=iterator.next();if(job.local())continue;
                boolean promoted=false;for(var box:job.owners())if(owned.incrementalPages.isUnlitLocalOwner(box)){promoted=true;break;}
                if(promoted){iterator.remove();retirePageLight(owned,job);}}}
            // A new local page must not queue behind every slow distant page. Retire
            // excess background tasks only; their completed exact scalars remain cached.
            if(localPending){int backgrounds=0;for(var job:owned.pageLightJobs)if(!job.local())backgrounds++;
                for(int i=owned.pageLightJobs.size()-1;i>=0&&backgrounds>1;i--){var job=owned.pageLightJobs.get(i);if(job.local())continue;
                    owned.pageLightJobs.remove(i);retirePageLight(owned,job);backgrounds--;}}
            workers.purge();
            var risks=new HashMap<SurfaceQuadPlan.PageKey,Integer>();
            while(owned.pageLightJobs.size()<maximum){check(token);
                var order=!backgroundShown||(owned.angularPageCursor++&1)==0?nearest:angularPageOrder(owned.angularPageCursor);
                int locals=0,backgrounds=0;for(var job:owned.pageLightJobs){if(job.local())locals++;else backgrounds++;}
                List<TerrainLodColumns.Box> missing=List.of();boolean local=false;
                // Three equal-owner error-priority quanta, then the fair local/angular quantum.
                if((owned.pageProvisional!=null||owned.visibleAudit!=null)&&(owned.priorityLightCursor++&3)!=3){
                    if(owned.visibleAudit!=null)missing=owned.incrementalPages.requestOwners(owned.visibleAudit.priorityOwners(),256,owned.claimedLight);
                    java.util.function.ToIntFunction<SurfaceQuadPlan.PageKey> risk=k->risks.computeIfAbsent(k,p->{
                        var audit=owned.visibleAudit;if(audit!=null&&!audit.visiblePages().contains(p))return 0;
                        int discrepancy=owned.pageProvisional==null?0:owned.pageProvisional.priority(p);return audit==null?discrepancy:audit.pagePriority(p,discrepancy);});
                    var priority=Comparator.<SurfaceQuadPlan.PageKey>comparingInt(risk).reversed().thenComparing(nearest);
                    if(missing.isEmpty())missing=owned.incrementalPages.missingPriorityOwners(priority,STREAM_LIGHT_BATCH,owned.claimedLight,k->risk.applyAsInt(k)>2);
                    if(!missing.isEmpty())local=missing.stream().anyMatch(owned.incrementalPages::isUnlitLocalOwner);
                }
                if(missing.isEmpty()&&localPending&&locals<maximum-1){missing=owned.streaming?owned.incrementalPages.missingLocalOwners(order,STREAM_LIGHT_BATCH,owned.claimedLight):owned.incrementalPages.missingLocalPageOwners(order,FAR_LIGHT_BATCH,owned.claimedLight);local=!missing.isEmpty();}
                if(missing.isEmpty()&&(!localPending||backgrounds<1))
                    missing=owned.streaming?owned.incrementalPages.missingBackgroundOwners(order,STREAM_LIGHT_BATCH,owned.claimedLight):owned.incrementalPages.missingBackgroundPageOwners(order,FAR_LIGHT_BATCH,owned.claimedLight);
                if(owned.streaming&&missing.isEmpty()&&localPending){
                    missing=owned.incrementalPages.missingLocalOwners(order,STREAM_LIGHT_BATCH,owned.claimedLight);local=!missing.isEmpty();}
                if(missing.isEmpty())break;owned.lastFarLightQuantum=missing.size();owned.claimedLight.addAll(missing);
                var batch=List.copyOf(missing);
                // Every task emits at most one exact scalar per immutable owner. The
                // bounded queue lets complete pages advance before an unrelated owner
                // blocks the rest of this large group, without releasing its claims.
                var ready=owned.streaming?new ArrayBlockingQueue<PageLightResult>(batch.size()):null;
                boolean defer=owned.scalarMissDeferral;String scalarViewKey=owned.scalarViewKey;
                Future<List<LitBox>> future;
                try{future=workers.submit(()->{var values=ready==null?new ArrayList<LitBox>(batch.size()):null;
                    owned.exactWorkerStarts.increment();
                    // Opt-in timing once per large task, never once per original BOX.
                    var cpu=owned.metrics?java.lang.management.ManagementFactory.getThreadMXBean():null;
                    long cpuStart=cpu!=null&&cpu.isCurrentThreadCpuTimeSupported()?cpu.getCurrentThreadCpuTime():-1;
                    long wallStart=cpu==null?0:System.nanoTime();int finishedOwners=0;
                    try{var batched=owned.source.lighting().batchLightEnabled()?scalarBatch(owned,batch,token,defer):null;
                        for(int i=0;i<batch.size();i++){var box=batch.get(i);var value=batched==null?scalar(owned,box,token,defer):batched.get(i);check(token);
                        if(ready==null)values.add(new LitBox(box,value));else ready.add(new PageLightResult(box,value));finishedOwners++;}
                        check(token);return ready==null?List.copyOf(values):List.of();
                    }finally{owned.exactReadyProduced.add(finishedOwners);owned.exactWorkerEnds.increment();if(cpu!=null){long cpuEnd=cpu.getCurrentThreadCpuTime();
                        if(cpuStart>=0&&cpuEnd>=cpuStart)owned.lightTaskCpuNanos.add(cpuEnd-cpuStart);
                        owned.lightTaskWallNanos.add(System.nanoTime()-wallStart);owned.lightTaskOwners.add(finishedOwners);}}
                });}
                catch(RuntimeException refused){owned.claimedLight.removeAll(missing);throw refused;}
                owned.pageLightJobs.add(new PageLightJob(batch,future,ready,local,scalarViewKey));owned.surfaceLightTaskStarts++;
            }
            pageLightCounters(owned);
        }
        }finally{owned.exactSelectNanos.add(System.nanoTime()-selectStart);}
    }
    private int adoptPageLight(Context owned,long token){int adopted=0;
        long adoptStart=System.nanoTime();try{
        synchronized(owned.pageLightJobs){var iterator=owned.pageLightJobs.iterator();
            while(iterator.hasNext()){var job=iterator.next();
                // Read completion before draining: a task that finishes during drain
                // remains registered until the next pass, including its final scalar.
                boolean finished=job.future().isDone();
                if(job.ready()!=null){PageLightResult value;while((value=job.ready().poll())!=null){check(token);
                    adopted+=adoptPageLightResult(owned,job,value);}}
                if(!finished)continue;
                iterator.remove();releasePageLightClaims(owned,job);
                try{var values=job.future().get();check(token);
                    for(var value:values)adopted+=supplyPageLight(owned,value);
                    owned.surfaceLightTaskCompletions++;
                }catch(InterruptedException canceled){Thread.currentThread().interrupt();check(token);}
                catch(ExecutionException failed){var cause=failed.getCause();if(cause instanceof RuntimeException runtime)throw runtime;if(cause instanceof Error error)throw error;throw new IllegalStateException("Asynchronous original BOX light failed",cause);}
                finally{pageLightCounters(owned);}
            }
        }
        return adopted;
        }finally{owned.exactReadyAdopted.add(adopted);owned.exactAdoptNanos.add(System.nanoTime()-adoptStart);}
    }
    private static int supplyPageLight(Context owned,LitBox value){
        if(!owned.incrementalPages.containsOwner(value.box()))return 0;
        if(owned.pageProvisional!=null)owned.pageProvisional.noteExact(value.box(),value.light().sky());
        if(owned.visibleAudit!=null)owned.visibleAudit.reference(value.box(),value.light().sky());
        owned.incrementalPages.supplyLight(value.box(),owned.incrementalPages.scalarOwner(value.box(),value.light().sky(),value.light().block(),owned.source.lighting()::rgba));
        return 1;
    }
    private static int adoptPageLightResult(Context owned,PageLightJob job,PageLightResult value){
        if(value.light()!=null)return supplyPageLight(owned,new LitBox(value.box(),value.light()));
        if(!job.scalarViewKey().equals(owned.scalarViewKey)||!owned.scalarSealHit)return 0;
        owned.scalarDeferredAttempts++;
        if(!owned.scalarDeferredOwners.contains(value.box())){
            if(owned.scalarDeferredOwners.size()>=MAX_DEFERRED_SCALAR_OWNERS){
                owned.scalarDeferredOverflows++;owned.scalarDeferralExhaustedKey=owned.scalarViewKey;
                owned.scalarMissDeferral=false;owned.claimedLight.removeAll(owned.scalarDeferredOwners);return 0;
            }
            owned.scalarDeferredOwners.add(value.box());
            if(!owned.incrementalPages.containsOwner(value.box()))owned.scalarDeferredRetired++;
        }
        if(owned.scalarMissDeferral)owned.claimedLight.add(value.box());return 0;
    }
    private static void releasePageLightClaims(Context owned,PageLightJob job){
        for(var box:job.owners())if(!owned.scalarMissDeferral||!owned.scalarDeferredOwners.contains(box))owned.claimedLight.remove(box);
    }
    /** Coordinator-only retirement keeps already emitted exact values in their live owners. */
    private static void retirePageLight(Context owned,PageLightJob job){
        job.future().cancel(true);
        if(job.ready()!=null){PageLightResult value;while((value=job.ready().poll())!=null)
            if(adoptPageLightResult(owned,job,value)>0)owned.surfacePublicationDirty=true;}
        releasePageLightClaims(owned,job);
    }
    private static void cancelPageLight(Context owned){synchronized(owned.pageLightJobs){for(var job:owned.pageLightJobs)job.future().cancel(true);}}
    private static void reserveFarWorker(Context owned){synchronized(owned.pageLightJobs){
        // A new FAR scope can arrive while the old scope had three page tasks. Retire
        // only a pending exact-light task; cached scalars and displayed pages survive.
        while(owned.pageLightJobs.size()>2){var job=owned.pageLightJobs.remove(owned.pageLightJobs.size()-1);
            retirePageLight(owned,job);}
        pageLightCounters(owned);
    }}
    private static void pageLightCounters(Context owned){
        int locals=0;for(var job:owned.pageLightJobs)if(job.local())locals++;
        owned.surfaceLightInFlight=owned.pageLightJobs.size();owned.surfaceLightClaimed=owned.claimedLight.size();
        owned.surfaceLocalLightInFlight=locals;owned.surfaceBackgroundLightInFlight=owned.pageLightJobs.size()-locals;
    }
    private static Comparator<SurfaceQuadPlan.PageKey> angularPageOrder(long cursor){int wanted=(int)Math.floorMod(cursor/2,8);
        return Comparator.comparingInt((SurfaceQuadPlan.PageKey key)->Math.floorMod(surfaceSector(key)-wanted,8))
            .thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::z);}
    private static int surfaceSector(SurfaceQuadPlan.PageKey key){double angle=Math.atan2(key.z()+.5,key.x()+.5);return (int)Math.floor((angle+Math.PI)*4/Math.PI)&7;}
    private Prepared legacySurfaceProposal(Context owned,Input input,long token,long number) {
        var selection=selection(owned,input);var background=owned.far==null?List.<TerrainLodColumns.Box>of():owned.orderedFar;
        boolean same=owned.surfaceGeometry!=null&&owned.renderFarRaw==owned.far
            &&selection.candidate().grids().equals(owned.renderInputGrids)&&selection.protectedGrids().equals(owned.renderProtectedGrids)
            &&input.nativeCuts().equals(owned.renderNativeCuts);
        AbyssLodActiveSelection.Admission admission;
        if(same)admission=owned.renderAdmission;
        else {
            if(!selection.candidate().grids().equals(owned.renderInputGrids)||!selection.protectedGrids().equals(owned.renderProtectedGrids)
                    ||!input.nativeCuts().equals(owned.renderNativeCuts)||owned.renderFarRaw!=owned.far)owned.surfaceOptionalRetain=-1;
            owned.renderInputGrids=selection.candidate().grids();owned.renderProtectedGrids=selection.protectedGrids();
            owned.renderNativeCuts=input.nativeCuts();owned.renderFarRaw=owned.far;
            var limited=surfaceSelection(owned,selection,input.camera());long start=System.nanoTime();
            // Compose exact RAW under its existing fragment/ledger caps. Face extraction is
            // deliberately outside the optional-grid retry loop: GPU admission follows light.
            admission=AbyssLodActiveSelection.composeParts(limited,background,input.nativeCuts(),input.limits().maxFragments(),input.limits().maxFragments());
            admission=new AbyssLodActiveSelection.Admission(admission.candidate(),admission.composition(),
                    selection.candidate().grids().size()-admission.candidate().grids().size(),admission.rawComposition());
            SurfaceQuadPages.Geometry extracted;
            try{extracted=owned.pages.geometry(admission.rawComposition().boxes());}
            catch(TerrainLodColumns.BudgetExceededException intermediateCap){if(deferSurface(owned,selection,admission.candidate()))return null;throw intermediateCap;}
            long planStart=System.nanoTime();var source=owned.source.target();
            owned.surfaceLightPlan=new SurfaceOwnerLightPlan<>(new SurfaceOwnerLightPlan.Scope(source.session(),source.lifetime(),source.sourceIdentity(),source.sourceRevision()),
                extracted.owners(),extracted.visibleOwners(),admission.rawComposition().background().size(),input.limits().maxFragments(),owned.surfaceLightPlan,owned.light::get);
            owned.surfaceLightPlanBuilds++;owned.surfaceLightPlanMillis=(System.nanoTime()-planStart)/1_000_000.0;
            owned.surfaceGeometry=extracted;owned.surfacePrepared=null;owned.surfacePagePrepared=null;owned.surfaceLitBoxes=null;
            owned.renderAdmission=admission;owned.renderSurfaceBuilds++;
            owned.renderSurfaceMillis=(System.nanoTime()-start)/1_000_000.0;owned.surfaceRawFaceCount=owned.surfaceGeometry.rawFaces();
            owned.surfaceOwnerCount=owned.surfaceGeometry.visibleOwners().size();owned.renderSurfaceInput=owned.surfaceGeometry.owners().size();
            owned.renderSurfaceKept=owned.surfaceOwnerCount;owned.renderSurfaceRemoved=owned.renderSurfaceInput-owned.surfaceOwnerCount;
            owned.renderSurfaceBudgetRefused=false;
        }
        owned.deferredFineGrids=admission.deferredGrids();var geometry=owned.surfaceGeometry;var scalarPlan=owned.surfaceLightPlan;
        var pageOrder=surfacePageOrder(input.camera(),owned.pages.pageMetres());
        var missing=owned.pages.pendingPageOwners(geometry,scalarPlan::value,pageOrder,FAR_LIGHT_BATCH);
        var pageMissing=scalarPlan.pendingOwners(missing,FAR_LIGHT_BATCH);owned.lastFarLightQuantum=pageMissing.size();lightOwners(owned,scalarPlan,pageMissing,token);
        owned.surfaceLightRemaining=scalarPlan.remaining();
        check(token);if(!input.target().equals(current(token).target()))return null;
        long greedyStart=System.nanoTime();SurfaceQuadPages.Partial partial;
        var retained=retainedSurfacePages(owned,input);
        try{partial=owned.pages.preparePartial(geometry,owner->{var box=geometry.owners().get(owner);var scalar=scalarPlan.value(owner);
                return scalar==null?null:new SurfaceQuadPlan.Owner(box,scalar.sky(),scalar.block(),owned.source.lighting().rgba(box));},retained,16,pageOrder);
        }catch(TerrainLodColumns.BudgetExceededException pageCap){
            if(!owned.pages.pageCountRefused()&&deferSurface(owned,selection,admission.candidate()))return null;throw pageCap;}
        var pages=partial.published();owned.surfacePendingPages=partial.pendingPages();owned.surfaceCurrentPages=partial.currentKeys().size();owned.surfaceChangedPages=partial.changedKeys().size();
        owned.surfaceGreedyMillis=(System.nanoTime()-greedyStart)/1_000_000.0;owned.surfaceFaceCount=pages.faces();owned.surfacePagePrepared=pages;
        // A successful page patch releases one fine-source batch; a light-only yield keeps
        // its immutable geometry stable until at least one page can be promoted.
        owned.surfaceLightPending=partial.pendingPages()>0&&partial.changedKeys().isEmpty();
        owned.continuePreparation=partial.pendingPages()>0;
        if(pages.faces()==0){owned.surfaceLightPending=true;owned.continuePreparation=true;owned.phase="WAITING_POSITIVE_PAGE";return null;}
        if(partial.changedKeys().isEmpty()&&input.target().equals(owned.lastProposedTarget)&&owned.fineVersion==owned.lastProposedFineVersion){
            owned.surfaceLightPending=true;owned.continuePreparation=partial.pendingPages()>0;owned.phase="PAGE_OWNER_LIGHT";return null;}
        var litByBox=new LinkedHashMap<TerrainLodColumns.Box,Light>();
        for(var page:pages.pages())for(var owner:page.prepared().owners())litByBox.put(owner.box(),new Light(owner.sky(),owner.block()));
        var lit=new ArrayList<LitBox>(litByBox.size());for(var entry:litByBox.entrySet())lit.add(new LitBox(entry.getKey(),entry.getValue()));
        owned.surfaceLitBoxes=List.copyOf(lit);owned.surfaceLitBoxListBuilds++;
        var targetKeys=new HashSet<SurfaceQuadPlan.PageKey>();for(var region:geometry.pages())targetKeys.add(region.key());
        var publishedKeys=new HashSet<SurfaceQuadPlan.PageKey>();for(var page:pages.pages())publishedKeys.add(page.key());
        var readyGrids=new ArrayList<AbyssLodFineLedger.Grid>();long readyBytes=0;
        for(var grid:admission.candidate().grids())if(SurfacePageCoverage.covers(grid.bounds(),partial.currentKeys(),targetKeys,publishedKeys,owned.pages.pageMetres())){
            readyGrids.add(grid);readyBytes=Math.addExact(readyBytes,128+80L*grid.raw().boxes().size());}
        var displayedLedger=new AbyssLodFineLedger.Candidate(readyGrids,readyBytes);
        boolean complete=owned.far!=null&&owned.farBounds.equals(input.worldBounds())&&owned.farWork==null&&owned.fineCursor==owned.jobs.size()
            &&owned.deferredFineGrids==0&&owned.deferredSourceJobs==0&&owned.undispatchedSurfaceJobs==0&&partial.pendingPages()==0&&scalarPlan.remaining()==0;
        var stage=!fineWorldAcknowledged&&!owned.startupEmpty?Stage.NEAR_FINE:complete?Stage.COMPLETE:owned.far==null?Stage.REFINING:partial.pendingPages()>0?Stage.PARTIAL_PAGES:Stage.FAR;
        owned.phase=stage.name();trimOwned(owned,admission.candidate());owned.litFarBoxes=background.size();
        angularProgress(owned);
        owned.lastProposedTarget=input.target();owned.lastProposedFineVersion=owned.fineVersion;owned.lastProposedFarCursor=owned.farCursor;
        if(complete)persist(owned,input,token);
        // This own-quad main pass is not handed back to the legacy400k-BOX shadow input.
        return new Prepared(new Proposal(input.target(),number,stage,lit,readyGrids,owned.far==null?List.of():owned.far.boxes(),
                List.of(),owned.far==null?0:owned.farStep,owned.charged.get(),complete,pages.pages().get(0).prepared(),pages),displayedLedger,partial);
    }
    private static Comparator<SurfaceQuadPlan.PageKey> surfacePageOrder(Camera camera,int metres){
        return Comparator.comparingInt((SurfaceQuadPlan.PageKey key)->view(pageBounds(key,metres),camera))
            .thenComparingDouble(key->distance(pageBounds(key,metres),camera)).thenComparingLong(SurfaceQuadPlan.PageKey::x)
            .thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
    }
    private static TerrainLodColumns.Box pageBounds(SurfaceQuadPlan.PageKey key,int metres){
        long x=Math.multiplyExact(key.x(),metres),y=Math.multiplyExact(key.y(),metres),z=Math.multiplyExact(key.z(),metres);
        return new TerrainLodColumns.Box(x,y,z,x+metres,y+metres,z+metres,dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK);
    }
    private static SurfaceQuadPlan.PageSet retainedSurfacePages(Context owned,Input input){
        var pages=owned.surfacePublishedPages;var previous=owned.surfacePublishedTarget;
        if(pages==null||previous==null||!sameSource(previous,input.target())||previous.resourceRevision()!=input.target().resourceRevision())return null;
        if(owned.surfacePublishedCuts.equals(input.nativeCuts()))return pages;
        var affected=new HashSet<SurfaceQuadPlan.PageKey>();var cuts=new ArrayList<>(owned.surfacePublishedCuts);cuts.addAll(input.nativeCuts());int metres=owned.pages.pageMetres();
        for(var cut:cuts)for(long x=Math.floorDiv(cut.minX(),metres);x<=Math.floorDiv(cut.maxXExclusive(),metres);x++)
            for(long y=Math.floorDiv(cut.minY(),metres);y<=Math.floorDiv(cut.maxYExclusive(),metres);y++)
                for(long z=Math.floorDiv(cut.minZ(),metres);z<=Math.floorDiv(cut.maxZExclusive(),metres);z++)affected.add(new SurfaceQuadPlan.PageKey(x,y,z));
        var keep=new ArrayList<SurfaceQuadPlan.Page>();long bytes=0,vertices=0;int faces=0,opaque=0,water=0;
        for(var page:pages.pages())if(!affected.contains(page.key())){keep.add(page);var plan=page.prepared();faces+=plan.faces().size();vertices+=plan.vertexBytes();
            for(var face:plan.faces())if(plan.owners().get(face.owner()).box().material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER)water++;else opaque++;}
        bytes=(long)faces*SurfaceQuadPlan.GPU_BYTES_PER_FACE;
        return new SurfaceQuadPlan.PageSet(keep,vertices,bytes,faces,opaque,water);
    }
    private static AbyssLodActiveSelection.Selection surfaceSelection(Context owned,AbyssLodActiveSelection.Selection original,Camera camera){
        if(owned.surfaceOptionalRetain<0)return original;
        var optional=surfaceOptional(original,original.candidate(),camera);int retain=Math.min(owned.surfaceOptionalRetain,optional.size());
        var grids=new ArrayList<>(original.protectedGrids());grids.addAll(optional.subList(0,retain));long bytes=0;
        for(var grid:grids)bytes=Math.addExact(bytes,128+80L*grid.raw().boxes().size());
        return new AbyssLodActiveSelection.Selection(original.sourceIdentity(),original.x(),original.y(),original.z(),original.bands(),original.witnessPins(),
                new AbyssLodFineLedger.Candidate(grids,bytes),original.protectedGrids(),original.maximumBytes());
    }
    private static List<AbyssLodFineLedger.Grid> surfaceOptional(AbyssLodActiveSelection.Selection selection,AbyssLodFineLedger.Candidate candidate,Camera camera){
        var protectedKeys=new HashSet<RawKey>();for(var grid:selection.protectedGrids())protectedKeys.add(new RawKey(grid.bounds(),grid.step()));
        var optional=new ArrayList<AbyssLodFineLedger.Grid>();for(var grid:candidate.grids())if(!protectedKeys.contains(new RawKey(grid.bounds(),grid.step())))optional.add(grid);
        optional.sort(Comparator.comparingInt(AbyssLodFineLedger.Grid::step).thenComparingDouble(grid->distance(grid.bounds(),camera)));return optional;
    }
    /** One substantial optional retirement per worker turn, never a per-grid extraction loop. */
    private static boolean deferSurface(Context owned,AbyssLodActiveSelection.Selection selection,AbyssLodFineLedger.Candidate attempted){
        // A streaming source must never convert a resident scene cap into a smaller target
        // and then claim completion after its acquisition cursor has passed retired grids.
        if(owned.streaming){owned.renderSurfaceBudgetRefused=true;return false;}
        int optional=surfaceOptional(selection,attempted,new Camera(selection.x(),selection.y(),selection.z())).size();if(optional==0)return false;
        owned.surfaceOptionalRetain=optional/2;owned.surfaceAdmissionRetries++;owned.renderSurfaceBudgetRefused=true;owned.surfaceGeometry=null;owned.surfacePrepared=null;owned.surfacePagePrepared=null;
        owned.surfaceLightPending=true;owned.continuePreparation=true;owned.phase="GREEDY_SURFACE_ADMISSION";return true;
    }
    private static int surfaceColor(TerrainLodColumns.Box box){return switch(box.material()){
        case ROCK->0xff7e7b77;case SOIL->0xff745337;case GRASS->0xff688a41;case WATER->0xb4355ea3;
        case AIR->throw new IllegalArgumentException("AIR cannot emit a surface");};}
    private AbyssLodActiveSelection.Admission renderAdmission(Context owned,AbyssLodActiveSelection.Selection selection,
            List<TerrainLodColumns.Box> background,Input input) {
        if(owned.renderAdmission!=null&&owned.renderFarRaw==owned.far&&owned.renderFarCursor==owned.farCursor&&owned.renderFarStep==owned.farStep
                &&owned.renderBoxCap==input.limits().maxBoxes()&&owned.renderFragmentCap==input.limits().maxFragments()
                &&selection.candidate().grids().equals(owned.renderInputGrids)&&selection.protectedGrids().equals(owned.renderProtectedGrids)
                &&input.nativeCuts().equals(owned.renderNativeCuts))return owned.renderAdmission;
        var admission=AbyssLodActiveSelection.composeRenderedParts(selection,background,input.nativeCuts(),input.limits().maxBoxes(),
                input.limits().maxFragments(),source->{
            long started=System.nanoTime();var geometry=source.boxes();
            var result=AbyssLodRenderSurface.cull(geometry,(int)Math.min(Integer.MAX_VALUE,24L*input.limits().maxFragments()));
            var bg=new ArrayList<TerrainLodColumns.Box>();var fine=new ArrayList<TerrainLodColumns.Box>();int boundary=source.background().size();
            for(int i:result.keptIndices()){if(i<boundary)bg.add(geometry.get(i));else fine.add(geometry.get(i));}
            owned.renderSurfaceInput=geometry.size();owned.renderSurfaceKept=result.kept().size();owned.renderSurfaceRemoved=result.removed();
            owned.renderSurfaceBudgetRefused=result.budgetRefused();owned.renderSurfaceBuilds++;
            owned.renderSurfaceMillis=(System.nanoTime()-started)/1_000_000.0;
            return new AbyssLodFineLedger.Composition(bg,fine);
        });
        owned.renderInputGrids=selection.candidate().grids();owned.renderProtectedGrids=selection.protectedGrids();
        owned.renderNativeCuts=input.nativeCuts();owned.renderFarRaw=owned.far;owned.renderFarCursor=owned.farCursor;owned.renderFarStep=owned.farStep;
        owned.renderBoxCap=input.limits().maxBoxes();owned.renderFragmentCap=input.limits().maxFragments();
        // The shadow transport has the same400k input bound and exact SKY dictionary as
        // submitted render geometry. Fully enclosed owners have no exterior boundary;
        // their removal can expose only closed internal shell faces. RAW occupancy and
        // source-grid proofs remain in rawComposition/backdrop/ledger, without a claim
        // that the shell's interior union equals the original solid occupancy.
        owned.renderAdmission=admission;owned.shadowGeometry=admission.composition().boxes();return admission;
    }
    private static boolean sameSource(Target a,Target b){return a.session().equals(b.session())&&a.lifetime()==b.lifetime()
            &&a.sourceIdentity().equals(b.sourceIdentity())&&a.sourceRevision()==b.sourceRevision();}
    private Input current(long token){check(token);return Objects.requireNonNull(latest);}
    private void ensureIndex(Context owned,Input input,long token) {
        if(owned.indexSurfaces!=null&&(owned.indexBounds.equals(input.nearBounds())||interior(owned.indexBounds,input.camera(),32)))return;
        // A new local acquisition window has its own bounded fresh reserve. Cached exact tiles
        // and the promoted ledger survive flight; native-hole and view-only requests do not reset it.
        // A local-window reset cannot erase samples already reserved by an independent FAR
        // acquisition. Keep its current aggregate lease until the immutable result is adopted.
        if(!owned.streaming&&owned.index!=null&&owned.farWork==null)owned.charged.set(0);
        var bounds=intersect(input.nearBounds(),input.worldBounds());
        owned.phase="LOCAL_SURFACE_INDEX";replaceIndex(owned,bounds,token);
        owned.jobWindow=null;owned.jobs=List.of();owned.fineCursor=0;
        if(!fineWorldAcknowledged)owned.initialPrepared=false;
    }
    private void replaceIndex(Context owned,TerrainLodColumns.Bounds bounds,long token){
        var raw=raw(owned,bounds,16,token);var surfaces=AbyssLodSurfacePriority.index(raw.boxes(),owned.source.limits().maxFragments(),16,bounds);check(token);
        owned.index=raw;owned.indexBounds=bounds;owned.indexSurfaces=surfaces;owned.surfaceHints=surfaces.boundaries().size();
    }
    private void prepareInitial(Context owned,Input input,long token) {
        int attempted=0;
        for(int expansion=0;expansion<3;expansion++){
            input=current(token);var descriptor=new TerrainLodRefinement.Plan(List.of(new TerrainLodRefinement.Region(owned.indexBounds,1)),Optional.empty(),0);
            var camera=input.camera();var first=AbyssLodSurfacePriority.prefix(owned.indexBounds,descriptor,owned.indexSurfaces,input.nativeCuts(),
                    camera.x(),camera.y(),camera.z(),camera.forwardX(),camera.forwardY(),camera.forwardZ(),input.limits().initialJobs());
            // A nearer wall behind the camera must not consume the first prefix while the
            // bounded next index can still contain a forward surface. Proxy visibility is a hint.
            boolean forward=false;for(var b:owned.indexSurfaces.boundaries())if(view(b,camera)==0){forward=true;break;}
            if(!forward&&expansion<2){int radius=expansion==0?320:640;var expanded=around(input.worldBounds(),camera,radius);
                if(!expanded.equals(owned.indexBounds)){replaceIndex(owned,expanded,token);continue;}}
            // The first batch has a strict K<=8. If a coarse hint misses a real thin surface,
            // test additional exact source tiles rather than certifying its proxy as AIR.
            var candidates=new ArrayList<>(first);
            if(!owned.index.boxes().isEmpty()){
                var more=AbyssLodSurfacePriority.prefix(owned.indexBounds,descriptor,owned.indexSurfaces,input.nativeCuts(),camera.x(),camera.y(),camera.z(),
                        camera.forwardX(),camera.forwardY(),camera.forwardZ(),Math.min(128,input.limits().maxJobs()));
                for(var r:more)if(!candidates.contains(r))candidates.add(new TerrainLodRefinement.Region(r.bounds(),1));
            }
            for(int start=0;start<candidates.size()&&attempted<128;start+=input.limits().initialJobs()){
                check(token);var end=Math.min(candidates.size(),start+input.limits().initialJobs());attempted+=end-start;
                var grids=grids(owned,candidates.subList(start,end),token);var test=new AbyssLodFineLedger.Candidate(grids,weight(grids));
                var visible=AbyssLodFineLedger.compose(List.of(),test,current(token).nativeCuts(),input.limits().maxBoxes(),input.limits().maxFragments());
                if(!visible.isEmpty()){
                    for(var grid:grids)owned.ownedFine.put(new RawKey(grid.bounds(),grid.step()),grid);owned.fineVersion++;
                    owned.initialPrepared=true;owned.initialView=camera;owned.nearPrefixes=0;
                    owned.witnessPins=List.copyOf(grids);owned.witnessView=camera;
                    captureAcquisitionPlan(owned,input);
                    ensureJobs(owned,current(token),token);return;
                }
            }
            int radius=expansion==0?320:640;var expanded=around(input.worldBounds(),input.camera(),radius);
            if(expanded.equals(owned.indexBounds))continue;
            replaceIndex(owned,expanded,token);
        }
        // Explicit bounded empty outcome. It releases the optional surface stage, never records
        // a fine WORLD draw, and does not manufacture AIR or wait forever for a zero-size scene.
        owned.startupEmpty=true;owned.initialPrepared=true;owned.phase="STARTUP_NO_POSITIVE_SURFACE";
        captureAcquisitionPlan(owned,input);
        ensureJobs(owned,current(token),token);ensureFar(owned,current(token),16,token);
        owned.farCursor=Math.min(owned.orderedFar.size(),FAR_LIGHT_BATCH);
    }
    /** Local coarse midpoints can witness exact source cells that the full FAR lattice misses. */
    private void captureAcquisitionPlan(Context owned,Input input) {
        var c=input.camera();var bounds=owned.indexBounds;
        var plan=AbyssLodSurfacePriority.describe(bounds,signedFloor(c.x()),signedFloor(c.y()),signedFloor(c.z()),input.bands(),
                input.limits().maxFreshSamples(),input.limits().maxJobs()).geometry();
        var jobs=AbyssLodSurfacePriority.prefix(bounds,plan,owned.indexSurfaces,List.of(),
                c.x(),c.y(),c.z(),c.forwardX(),c.forwardY(),c.forwardZ(),input.limits().maxJobs());
        if(surfaceMesh)jobs=AbyssLodSurfacePriority.distributed(jobs,c.x(),c.y(),c.z(),c.forwardX(),c.forwardY(),c.forwardZ(),input.limits().maxJobs(),1).jobs();
        var keys=new HashSet<RawKey>();
        for(var region:jobs)if(!completeWorldCell(region.bounds(),input.worldBounds())){
            keys.add(new RawKey(region.bounds(),region.horizontalStep()));}
        owned.acquisitionJobs=List.copyOf(jobs);owned.acquisitionFragmentKeys=Set.copyOf(keys);owned.cachedSelection=null;
    }
    private void ensureJobs(Context owned,Input input,long token) {
        var c=input.camera();var window=new WindowKey(c,input.nearBounds(),input.worldBounds(),input.bands(),input.limits().maxJobs(),input.limits().initialJobs());
        if(!owned.preparingRetargetWitness&&owned.planningView!=null&&!window.equals(owned.planningView)){
            // A view changes the exact requested tiers even inside the old64m bucket.
            // Keep acknowledged WORLD pages and reusable RAW; stage the same witness
            // and eligible source grids that a fresh pipeline would choose at this view.
            owned.planningView=window;owned.jobWindow=null;owned.requestedFineKeys=Set.of();owned.cachedSelection=null;
            owned.witnessPins=List.of();owned.witnessJobs=List.of();owned.witnessView=null;
            owned.acquisitionJobs=List.of();owned.acquisitionFragmentKeys=Set.of();
            owned.initialPrepared=false;owned.startupEmpty=false;owned.preparingRetargetWitness=true;
            try{replaceIndex(owned,intersect(input.nearBounds(),input.worldBounds()),token);prepareInitial(owned,input,token);}
            finally{owned.preparingRetargetWitness=false;}
            return;
        }
        if(!owned.preparingRetargetWitness)owned.planningView=window;
        if(window.equals(owned.jobWindow))return;
        // Distance tiers belong to the current camera. A remote witness is only a bounded
        // priority island, never the centre of every requested and retained band.
        var bounds=owned.far==null?owned.indexBounds:owned.farBounds;
        var hints=owned.far==null?owned.indexSurfaces:owned.farSurfaces;
        var descriptor=AbyssLodSurfacePriority.describe(bounds,signedFloor(c.x()),signedFloor(c.y()),signedFloor(c.z()),input.bands(),
                input.limits().maxFreshSamples(),input.limits().maxJobs());
        var plan=descriptor.geometry();
        var jobs=AbyssLodSurfacePriority.prefix(bounds,plan,hints,List.of(),c.x(),c.y(),c.z(),c.forwardX(),c.forwardY(),c.forwardZ(),
                input.limits().maxJobs());
        if(surfaceMesh){var distributed=AbyssLodSurfacePriority.distributed(jobs,c.x(),c.y(),c.z(),c.forwardX(),c.forwardY(),c.forwardZ(),input.limits().maxJobs(),1);
            jobs=distributed.jobs();owned.angularSectors=distributed.angularSectors();owned.undispatchedSurfaceJobs=distributed.undispatchedJobs();}
        boolean replaceWitness=owned.witnessView==null||cameraDistance(owned.witnessView,c)>=64;
        if(replaceWitness)owned.witnessJobs=AbyssLodSurfacePriority.prefix(bounds,plan,hints,input.nativeCuts(),
                c.x(),c.y(),c.z(),c.forwardX(),c.forwardY(),c.forwardZ(),input.limits().initialJobs());
        var ordered=new LinkedHashMap<RawKey,TerrainLodRefinement.Region>();
        if(replaceWitness)for(var r:owned.witnessJobs)ordered.put(new RawKey(r.bounds(),r.horizontalStep()),r);
        for(var r:owned.acquisitionJobs){var key=new RawKey(r.bounds(),r.horizontalStep());
            if(ordered.containsKey(key))continue;
            if(ordered.size()<input.limits().maxJobs())ordered.put(key,r);else if(surfaceMesh)owned.undispatchedSurfaceJobs++;}
        for(var r:jobs){var key=new RawKey(r.bounds(),r.horizontalStep());if(ordered.containsKey(key))continue;
            if(ordered.size()<input.limits().maxJobs())ordered.put(key,r);else if(surfaceMesh)owned.undispatchedSurfaceJobs++;}
        var acquisition=List.copyOf(ordered.values());
        owned.jobs=streaming&&input.lighting().provisionalLightEnabled()&&Boolean.parseBoolean(System.getProperty("mia.lod.coalesceSource155","true"))
            ?AbyssLodSourcePages.order(acquisition,c.x(),c.y(),c.z(),c.forwardX(),c.forwardY(),c.forwardZ()):acquisition;
        owned.jobWindow=window;owned.fineCursor=0;owned.plannedJobs=owned.jobs.size();owned.cachedSelection=null;
        if(owned.requestedFineKeys!=null){var eligible=new HashSet<RawKey>();
            for(var region:owned.jobs)eligible.add(new RawKey(region.bounds(),region.horizontalStep()));
            for(var grid:owned.witnessPins)eligible.add(new RawKey(grid.bounds(),grid.step()));
            owned.requestedFineKeys=Set.copyOf(eligible);owned.cachedSelection=null;}
        if(owned.metrics)owned.plannedFineSamples=owned.jobs.stream().mapToLong(TerrainLodRefinement.Region::sampledVoxels).sum();
        if(surfaceMesh){owned.angularBootstrap.clear();for(var r:owned.jobs)if(r.horizontalStep()==4)owned.angularBootstrap.add(new RawKey(r.bounds(),4));
            owned.angularBootstrapJobs=owned.angularBootstrap.size();angularProgress(owned);}
        owned.deferredSource.clear();owned.deferredSourceJobs=0;
        check(token);
    }
    private static double[] closestWitness(Context owned,Input input) {
        var c=input.camera();double best=Double.POSITIVE_INFINITY;int bestView=2;double[] result={c.x(),c.y(),c.z()};
        var hints=owned.far==null?owned.indexSurfaces:owned.farSurfaces;
        var visible=TerrainLodClipper.subtract(hints.boundaries(),input.nativeCuts(),input.limits().maxFragments());
        for(var b:visible){
            double x=clamp(c.x(),b.minX(),b.maxXExclusive()-1),y=clamp(c.y(),b.minY(),b.maxYExclusive()-1),z=clamp(c.z(),b.minZ(),b.maxZExclusive()-1);
            double dx=x-c.x(),dy=y-c.y(),dz=z-c.z(),d=Math.hypot(Math.hypot(dx,dy),dz);int view=d==0||dx*c.forwardX()+dy*c.forwardY()+dz*c.forwardZ()>=d*.25?0:1;
            if(view<bestView||view==bestView&&d<best){best=d;bestView=view;result=new double[]{x,y,z};}
        }
        return result;
    }
    private static int streamFineBatch(Context owned){return owned.scalarSealHit?WARM_STREAM_FINE_BATCH:STREAM_FINE_BATCH;}
    private void prepareFineBatch(Context owned,Input input,int count,long token) {
        if(owned.streaming){prepareStreamingFineBatch(owned,input,count,token);return;}
        var retained=owned.ownedFine;
        if(owned.requestedFineKeys!=null){retained=new LinkedHashMap<>();for(var grid:candidate(owned,input).grids())retained.put(new RawKey(grid.bounds(),grid.step()),grid);}
        var batch=new ArrayList<TerrainLodRefinement.Region>();
        long scratch=0;int nextCursor=owned.fineCursor;
        while(nextCursor<owned.jobs.size()&&batch.size()<count){var r=owned.jobs.get(nextCursor);
            if(covered(retained,r)){nextCursor++;continue;}
            // A result can contain no more boxes than sampled cells. Keep the whole ordered
            // result batch bounded even when every source cell is a different material.
            long weight=128+80L*Math.min(r.sampledVoxels(),input.limits().maxBoxes());
            if(owned.streaming&&weight>input.limits().maxLedgerBytes())throw new TerrainLodColumns.BudgetExceededException("Single source result exceeds scratch byte cap");
            if(owned.streaming&&scratch+weight>input.limits().maxLedgerBytes())break;
            scratch+=weight;nextCursor++;batch.add(r);}
        owned.fineBatchScratchBytes=scratch;
        // Hold restored values through admission so bounded memory eviction cannot turn a
        // certified cached job into unreserved fresh generation inside a parallel worker.
        var cached=new HashMap<RawKey,TerrainLodColumns.Result>();
        for(var region:batch){var value=cachedRaw(owned,region.bounds(),region.horizontalStep(),token);
            if(value!=null)cached.put(new RawKey(region.bounds(),region.horizontalStep()),value);}
        AbyssLodSurfacePriority.SampleAdmission admission;
        if(owned.streaming)admission=AbyssLodSurfacePriority.admitSamples(batch,Long.MAX_VALUE,region->cached.containsKey(new RawKey(region.bounds(),region.horizontalStep())));
        else for(;;){check(token);long previous=owned.charged.get();
            admission=AbyssLodSurfacePriority.admitSamples(batch,Math.max(0,input.limits().maxFreshSamples()-previous),
                region->cached.containsKey(new RawKey(region.bounds(),region.horizontalStep())));
            // An independent FAR worker reserves against this same counter. Recompute if it
            // wins the race; never reset or bypass its captured full-source reservation.
            if(owned.charged.compareAndSet(previous,previous+admission.freshSamples()))break;}
        var accepted=admission.accepted();
        var prepared=BoundedOrderedWork.map(accepted,foregroundWorkers(owned),workers,region->{
            var key=new RawKey(region.bounds(),region.horizontalStep());var value=cached.get(key);
            if(value==null)value=owned.streaming?raw(owned,region.bounds(),region.horizontalStep(),token):buildRaw(owned,region.bounds(),region.horizontalStep(),token);
            return new AbyssLodFineLedger.Grid(owned.source.target().sourceIdentity(),region.bounds(),region.horizontalStep(),value);});
        // A failed/canceled batch cannot advance past source cells that were never installed.
        // A same-source retry may reuse its completed RAW, but must revisit the whole batch.
        owned.fineCursor=nextCursor;
        for(var grid:prepared){var key=new RawKey(grid.bounds(),grid.step());owned.ownedFine.put(key,grid);owned.deferredSource.remove(key);}
        for(var region:admission.deferred())owned.deferredSource.add(new RawKey(region.bounds(),region.horizontalStep()));
        owned.deferredSourceJobs=owned.deferredSource.size();
        finishFineBatch(owned,input,prepared,token);
    }
    private record FineResult(int index,AbyssLodFineLedger.Grid grid,long reservedBytes){}
    /** A canceled executing builder retains its lease until its actual worker finally exits. */
    private static final class ScratchLease {
        final Context context;long bytes;boolean started,finished,closing,released;
        ScratchLease(Context context,long bytes){this.context=context;this.bytes=bytes;}
        synchronized boolean start(){if(closing)return false;started=true;return true;}
        synchronized void finish(){finished=true;if(closing)release();}
        synchronized void shrink(long actual){if(closing||released)throw new CancellationException("Retired source scratch");
            if(actual>bytes)throw new IllegalStateException("Scratch reservation exceeded");context.scratchCharged.addAndGet(actual-bytes);bytes=actual;}
        synchronized void close(){closing=true;if(!started||finished)release();}
        private void release(){if(!released){released=true;context.scratchCharged.addAndGet(-bytes);}}
    }
    private ScratchLease reserveScratch(Context owned,long bytes,long token){
        long cap=owned.source.limits().maxLedgerBytes();
        for(;;){check(token);long previous=owned.scratchCharged.get();
            if(bytes<=cap-previous&&owned.scratchCharged.compareAndSet(previous,previous+bytes)){
                owned.peakScratchCharged.accumulateAndGet(previous+bytes,Math::max);return new ScratchLease(owned,bytes);}
            try{Thread.sleep(1);}catch(InterruptedException canceled){Thread.currentThread().interrupt();throw new CancellationException("Scratch lease canceled");}
        }
    }
    private record FinePrepared(List<AbyssLodFineLedger.Grid> grids,int nextCursor,long retainedBytes,long peakBytes) {
        private FinePrepared {grids=List.copyOf(grids);}
    }
    /** One immutable plan prefix and its leases, including completed results awaiting adoption. */
    private static final class FinePrefetch {
        final List<TerrainLodRefinement.Region> plan,regions;
        final List<Integer> cursors;
        final int startCursor,endCursor,parallelism;
        final Input input;
        private final List<Future<FineResult>> tasks=new ArrayList<>();
        private final List<ScratchLease> leases=new ArrayList<>();
        private volatile Future<FinePrepared> future;
        private volatile long readyAtNanos;
        private boolean retired,cancelRequested;
        FinePrefetch(List<TerrainLodRefinement.Region> plan,int startCursor,int endCursor,
                List<TerrainLodRefinement.Region> regions,List<Integer> cursors,int parallelism,Input input){
            this.plan=plan;this.startCursor=startCursor;this.endCursor=endCursor;this.regions=List.copyOf(regions);
            this.cursors=List.copyOf(cursors);this.parallelism=parallelism;this.input=input;
        }
        synchronized void attach(Future<FinePrepared> value){future=value;if(cancelRequested)value.cancel(true);}
        synchronized void lease(ScratchLease value){if(retired){value.close();throw new CancellationException("Retired source prefix");}leases.add(value);}
        synchronized void task(Future<FineResult> value){if(retired){value.cancel(true);throw new CancellationException("Retired source prefix");}tasks.add(value);}
        synchronized void checkActive(){if(retired)throw new CancellationException("Retired source prefix");}
        boolean ready(){var value=future;return value!=null&&value.isDone();}
        synchronized void release(boolean cancelDispatch){
            if(cancelDispatch){cancelRequested=true;if(future!=null)future.cancel(true);}
            retired=true;for(var task:tasks)if(!task.isDone())task.cancel(true);tasks.clear();
            for(var lease:leases)lease.close();leases.clear();
        }
    }
    private static void cancelFine(Context owned){var previous=owned.fineWork;if(previous!=null){owned.fineWork=null;previous.release(true);}}
    /** Coordinator adopts ready work and starts one prefix; it never waits on source execution. */
    private void prepareStreamingFineBatch(Context owned,Input input,int count,long token){
        var previous=owned.fineWork;
        if(previous!=null&&(previous.plan!=owned.jobs||previous.startCursor!=owned.fineCursor)){
            owned.fineWork=null;previous.release(true);owned.finePrefetchRetirements++;previous=null;fineOrchestrator.purge();workers.purge();
        }
        if(previous!=null){
            if(!previous.ready())return;
            owned.fineWork=null;
            try{
                var ready=await(previous.future);check(token);
                if(previous.readyAtNanos!=0)owned.diagnostics.record("fineReadyToAdopt",System.nanoTime()-previous.readyAtNanos,0);
                long adoptionStart=owned.diagnostics.now(),adoptionCpu=owned.diagnostics.cpu();
                for(var grid:ready.grids())owned.ownedFine.put(new RawKey(grid.bounds(),grid.step()),grid);
                owned.fineCursor=ready.nextCursor();owned.fineBatchScratchBytes=ready.retainedBytes();
                owned.maximumFineScratchBytes=Math.max(owned.maximumFineScratchBytes,ready.peakBytes());
                owned.finePrefetchAdoptions++;finishFineBatch(owned,input,ready.grids(),token);
                owned.diagnostics.finish("fineAdoption",adoptionStart,adoptionCpu);
            }finally{previous.release(true);}
        }
        // Adoption precedes this turn's surface publication/trim. Fresh finer grids may
        // lie outside their display band, so raw ownership is not a coverage proof for
        // a later coarse job. Only the selected immutable scene survives that trim.
        if(owned.fineCursor>=owned.jobs.size()){owned.lastFineBatch=0;owned.fineBatchScratchBytes=0;return;}
        var retained=new HashMap<RawKey,AbyssLodFineLedger.Grid>();
        for(var grid:candidate(owned,input).grids())retained.put(new RawKey(grid.bounds(),grid.step()),grid);
        var regions=new ArrayList<TerrainLodRefinement.Region>();var cursors=new ArrayList<Integer>();
        int end=owned.fineCursor;
        while(end<owned.jobs.size()&&regions.size()<count){var region=owned.jobs.get(end++);
            if(covered(retained,region))continue;regions.add(region);cursors.add(end);}
        if(regions.isEmpty()){owned.fineCursor=end;owned.lastFineBatch=0;owned.fineBatchScratchBytes=0;return;}
        // Keep one page worker available even when every source callback is held. Two
        // exact-light jobs and the independent FAR builder have separate capacity.
        if(owned.surfaceLightInFlight>=owned.workerCount-1)return;
        int parallelism=Math.max(1,owned.workerCount-1-Math.max(acquisitionLightSlots(owned),owned.surfaceLightInFlight)-(farPending(owned)?1:0));
        var next=new FinePrefetch(owned.jobs,owned.fineCursor,end,regions,cursors,parallelism,input);owned.fineWork=next;
        try{check(token);next.attach(fineOrchestrator.submit(()->acquireFinePrefix(owned,next,token)));owned.finePrefetchStarts++;}
        catch(RuntimeException failed){if(owned.fineWork==next)owned.fineWork=null;next.release(true);throw failed;}
    }
    /** Even a small host reserves FAR, fine source and an independent page worker. */
    private static int acquisitionLightSlots(Context owned){return Math.min(2,owned.workerCount-3);}
    /** Dispatch/wait thread builds a bounded immutable result; only its caller owns cursors. */
    private FinePrepared acquireFinePrefix(Context owned,FinePrefetch batch,long token){
        long acquisitionStart=owned.diagnostics.now(),acquisitionCpu=owned.diagnostics.cpu();
        var regions=batch.regions;var input=batch.input;
        var completion=new ArrayBlockingQueue<Future<FineResult>>(regions.size());var leases=new ArrayList<ScratchLease>();
        var results=new AbyssLodFineLedger.Grid[regions.size()];int submitted=0,finished=0;
        long retained=0,reserved=0,peak=0,cap=input.limits().maxLedgerBytes();boolean filled=false;
        try{
            while(finished<submitted||!filled){
                while(!filled&&submitted<regions.size()&&submitted-finished<batch.parallelism){
                    check(token);batch.checkActive();var region=regions.get(submitted);long weight=128+80L*Math.min(region.sampledVoxels(),input.limits().maxBoxes());
                    if(weight>cap)throw new TerrainLodColumns.BudgetExceededException("Single source result exceeds scratch byte cap");
                    if(weight>cap-retained-reserved)break;
                    final int index=submitted;reserved+=weight;peak=Math.max(peak,retained+reserved);
                    var lease=reserveScratch(owned,weight,token);batch.lease(lease);leases.add(lease);
                    var task=new FutureTask<FineResult>(()->{if(!lease.start())throw new CancellationException("Retired source scratch");
                        try{return new FineResult(index,new AbyssLodFineLedger.Grid(owned.source.target().sourceIdentity(),
                            region.bounds(),region.horizontalStep(),raw(owned,region.bounds(),region.horizontalStep(),token)),weight);}
                        finally{lease.finish();}}){@Override protected void done(){completion.add(this);}};
                    // Queue the cancelable Future itself. CompletionService wrappers hide
                    // cancellation from purge and can accumulate across rapid retargets.
                    workers.execute(task);batch.task(task);
                    submitted++;
                }
                if(submitted==regions.size())filled=true;
                // Completed immutable results stay leased until coordinator adoption. A full
                // scratch quantum commits this contiguous prefix before another can start.
                if(finished==submitted){filled=true;continue;}
                FineResult result;
                try{result=await(completion.take());}catch(InterruptedException canceled){Thread.currentThread().interrupt();throw new CancellationException("Source quantum canceled");}
                check(token);batch.checkActive();long actual=128+80L*result.grid().raw().boxes().size();
                if(actual>result.reservedBytes())throw new IllegalStateException("Source result exceeded its certified scratch reservation");
                leases.get(result.index()).shrink(actual);
                reserved-=result.reservedBytes();retained+=actual;results[result.index()]=result.grid();finished++;
            }
            check(token);batch.checkActive();
            batch.readyAtNanos=owned.diagnostics.now();owned.diagnostics.finish("fineAcquireBatch",acquisitionStart,acquisitionCpu);
            return new FinePrepared(Arrays.asList(results).subList(0,submitted),
                submitted==regions.size()?batch.endCursor:batch.cursors.get(submitted-1),retained,peak);
        }catch(RuntimeException|Error failed){batch.release(false);throw failed;}
    }
    private void finishFineBatch(Context owned,Input input,List<AbyssLodFineLedger.Grid> prepared,long token){
        if(owned.witnessView==null||cameraDistance(owned.witnessView,input.camera())>=64) {
            var pins=new ArrayList<AbyssLodFineLedger.Grid>();
            for(var region:owned.witnessJobs){var grid=owned.ownedFine.get(new RawKey(region.bounds(),1));if(grid!=null)pins.add(grid);}
            if(!pins.isEmpty()&&pins.stream().anyMatch(g->!g.raw().boxes().isEmpty())){owned.witnessPins=List.copyOf(pins);owned.witnessView=input.camera();}
        }
        if(!prepared.isEmpty())owned.fineVersion++;
        owned.preparedJobs+=prepared.size();owned.lastFineBatch=prepared.size();owned.nearPrefixes++;
        // Progress is aggregated once per publication, rather than both before and after it.
        if(surfaceMesh&&!owned.streaming)angularProgress(owned);
        // Refusal cannot silently coarsen a displayed grid; the owner keeps the prior scene.
        candidate(owned,current(token));
    }
    private static boolean covered(Map<RawKey,AbyssLodFineLedger.Grid> grids,TerrainLodRefinement.Region region){
        for(int step=1;step<=region.horizontalStep();step*=2)if(grids.containsKey(new RawKey(region.bounds(),step)))return true;return false;
    }
    private static void angularProgress(Context owned){int remaining=0,positive=0;
        for(var key:owned.angularBootstrap){AbyssLodFineLedger.Grid best=null;for(int step=1;step<=4;step*=2){best=owned.ownedFine.get(new RawKey(key.bounds(),step));if(best!=null)break;}
            if(best==null)remaining++;else if(!best.raw().boxes().isEmpty())positive++;}
        owned.angularBootstrapRemaining=remaining;owned.angularPositiveGrids=positive;
    }
    private List<AbyssLodFineLedger.Grid> grids(Context owned,List<TerrainLodRefinement.Region> jobs,long token) {
        return BoundedOrderedWork.map(jobs,foregroundWorkers(owned),workers,region->{var value=raw(owned,region.bounds(),region.horizontalStep(),token);
            return new AbyssLodFineLedger.Grid(owned.source.target().sourceIdentity(),region.bounds(),region.horizontalStep(),value);});
    }
    private TerrainLodColumns.Result raw(Context owned,TerrainLodColumns.Bounds bounds,int step,long token) {
        var hit=cachedRaw(owned,bounds,step,token);if(hit!=null)return hit;
        long count=new TerrainLodRefinement.Region(bounds,step).sampledVoxels();reserve(owned,count,token);
        try{return buildRaw(owned,bounds,step,token);}finally{release(owned,count);}
    }
    private TerrainLodColumns.Result cachedRaw(Context owned,TerrainLodColumns.Bounds bounds,int step,long token) {
        check(token);var key=new RawKey(bounds,step);var hit=owned.raw.get(key);if(hit!=null){owned.rawHits.increment();return hit;}
        var tile=tileKey(bounds,step);
        if(owned.archive!=null&&tile!=null){var boxes=archiveGet(owned,tile,token);
            if(boxes==null)boxes=stitchArchiveQuarters(owned,bounds,step,token);
            if(boxes!=null){var restored=CompactLodLists.result(new TerrainLodColumns.Result(boxes,new TerrainLodRefinement.Region(bounds,step).sampledVoxels()));
                owned.raw.put(key,restored);owned.rawHits.increment();owned.archiveRestoredTiles.increment();owned.archiveRestoredCells.add(restored.sampledVoxels());return restored;}}
        return null;
    }
    /** Caller has already acquired the exact source reservation; this never resamples a cache hit. */
    private TerrainLodColumns.Result buildRaw(Context owned,TerrainLodColumns.Bounds bounds,int step,long token) {
        long count=new TerrainLodRefinement.Region(bounds,step).sampledVoxels();var key=new RawKey(bounds,step);var tile=tileKey(bounds,step);
        var sampleSource=current(token).terrain();
        TrailNetwork.SampleSource checked=(x,y,z)->{check(token);owned.actualSamples.increment();return sampleSource.sample(x,y,z);};
        long generationStart=owned.diagnostics.now(),generationCpu=owned.diagnostics.cpu();
        TerrainLodColumns.Result result;
        try{result=CompactLodLists.result(TerrainLodColumns.build(checked,bounds,new TerrainLodColumns.Parameters(step,count,owned.source.limits().maxBoxes())));}
        finally{owned.diagnostics.finish("rawGeneration",generationStart,generationCpu);}
        check(token);owned.raw.put(key,result);archivePut(owned,tile,result,token);return result;
    }
    private void reserve(Context owned,long count,long token){long cap=owned.source.limits().maxFreshSamples();
        if(count>cap)throw new TerrainLodColumns.BudgetExceededException("Single exact source lattice exceeds concurrent sample cap");
        for(;;){check(token);long previous=owned.charged.get();
            if(count<=cap-previous){if(owned.charged.compareAndSet(previous,previous+count)){
                owned.peakCharged.accumulateAndGet(previous+count,Math::max);return;}continue;}
            if(!owned.streaming)throw new TerrainLodColumns.BudgetExceededException("Exact current-window fresh source reserve exhausted");
            // Only executing workers hold leases. Cancellation releases in their finally,
            // never when Future.cancel merely marks an in-progress task as done.
            try{Thread.sleep(1);}catch(InterruptedException canceled){Thread.currentThread().interrupt();check(token);}
        }}
    private static void release(Context owned,long count){if(owned.streaming)owned.charged.addAndGet(-count);}
    private AbyssLodFineLedger.Candidate candidate(Context owned,Input input) {
        return selection(owned,input).candidate();
    }
    private AbyssLodActiveSelection.Selection selection(Context owned,Input input) {
        var c=input.camera();
        var pins=owned.witnessView!=null&&cameraDistance(owned.witnessView,c)<64?owned.witnessPins:List.<AbyssLodFineLedger.Grid>of();
        var displayed=ledger.displayed();
        if(streaming&&owned.cachedSelection!=null&&owned.selectionVersion==owned.fineVersion
                &&owned.selectionLedger==displayed&&owned.selectionPins==pins&&c.equals(owned.selectionCamera)
                &&input.bands().equals(owned.selectionBands)
                &&input.target().sourceIdentity().equals(owned.cachedSelection.sourceIdentity())){
            owned.selectionReuses++;return owned.cachedSelection;}
        var selected=AbyssLodActiveSelection.select(ledger,input.target().sourceIdentity(),List.copyOf(owned.ownedFine.values()),
                pins,c.x(),c.y(),c.z(),surfaceMesh?surfaceDisplayBands(input.bands()):input.bands(),0,
                grid->owned.requestedFineKeys==null||owned.requestedFineKeys.contains(new RawKey(grid.bounds(),grid.step())),
                grid->owned.acquisitionFragmentKeys.contains(new RawKey(grid.bounds(),grid.step())));
        owned.selectionBuilds++;
        // WORLD ACK may replace the immutable ledger concurrently. Cache only a stable
        // read; an ACK, source adoption, view/bands/pins change or trim invalidates reuse.
        if(streaming&&displayed==ledger.displayed()){
            owned.cachedSelection=selected;owned.selectionVersion=owned.fineVersion;
            owned.selectionLedger=displayed;owned.selectionPins=pins;owned.selectionCamera=c;owned.selectionBands=input.bands();
        }else owned.cachedSelection=null;
        return selected;
    }
    private static boolean completeWorldCell(TerrainLodColumns.Bounds bounds,TerrainLodColumns.Bounds world){
        long x=Math.floorDiv((long)bounds.minX(),16)*16,y=Math.floorDiv((long)bounds.minY(),64)*64,z=Math.floorDiv((long)bounds.minZ(),16)*16;
        return bounds.minX()==Math.max(x,world.minX())&&bounds.maxXInclusive()==Math.min(x+15,world.maxXInclusive())
            &&bounds.minY()==Math.max(y,world.minY())&&bounds.maxYInclusive()==Math.min(y+63,world.maxYInclusive())
            &&bounds.minZ()==Math.max(z,world.minZ())&&bounds.maxZInclusive()==Math.min(z+15,world.maxZInclusive());
    }
    private static List<TerrainLodRefinement.Band> surfaceDisplayBands(List<TerrainLodRefinement.Band> requested){
        var levels=new ArrayList<TerrainLodRefinement.Band>();int outer=0;
        for(var band:requested){outer=Math.max(outer,band.radius());if(band.horizontalStep()<4)levels.add(band);}
        if(levels.isEmpty()||outer>levels.get(levels.size()-1).radius())levels.add(new TerrainLodRefinement.Band(outer,4));return List.copyOf(levels);
    }
    private static double keepRadius(int step,List<TerrainLodRefinement.Band> bands){double result=0;for(var b:bands)if(b.horizontalStep()<=step)result=Math.max(result,b.radius());return result;}
    private static long weight(List<AbyssLodFineLedger.Grid> grids){long sum=0;for(var g:grids)sum=Math.addExact(sum,128L+80L*g.raw().boxes().size());return sum;}
    private static void trimOwned(Context owned,AbyssLodFineLedger.Candidate candidate){owned.cachedSelection=null;
        owned.ownedFine.clear();for(var g:candidate.grids())owned.ownedFine.put(new RawKey(g.bounds(),g.step()),g);}
    /** At most one reserved source worker. No coordinator waits on an unfinished FAR Future. */
    private boolean ensureFar(Context owned,Input input,int firstStep,long token) {
        check(token);
        if(owned.farWork!=null){
            if(!owned.farWork.isDone())return owned.farSurfaces!=null&&owned.farBounds.equals(input.worldBounds())&&owned.farStep>=firstStep;
            FarPrepared ready;
            try{ready=owned.farWork.get();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("FAR adoption interrupted");}
            catch(ExecutionException failed){if(failed.getCause() instanceof RuntimeException runtime)throw runtime;
                if(failed.getCause() instanceof Error fatal)throw fatal;throw new IllegalStateException("FAR source failed",failed.getCause());}
            check(token);owned.farWork=null;
            if(ready.bounds().equals(input.worldBounds())){
                owned.far=ready.raw();owned.farBounds=ready.bounds();owned.farStep=ready.step();owned.rawBoxes=ready.raw().boxes().size();
                owned.farCursor=0;owned.litFarBoxes=0;owned.farSurfaces=ready.surfaces();owned.surfaceHints=ready.surfaces().boundaries().size();
                owned.orderedFar=ready.ordered();owned.jobWindow=null;saveFar(owned,input,token);
            }
        }
        if(owned.farSurfaces!=null&&owned.farBounds.equals(input.worldBounds())&&owned.farStep>=firstStep)return true;
        if(surfaceMesh)reserveFarWorker(owned);
        var task=new FutureTask<FarPrepared>(()->acquireFar(owned,input,firstStep,token));owned.farWork=task;
        if(token!=generation||Thread.currentThread().isInterrupted()){task.cancel(true);check(token);}
        owned.farAcquisitions++;workers.execute(task);return false;
    }
    private FarPrepared acquireFar(Context owned,Input input,int firstStep,long token) {
        check(token);if(fullBackgroundStartedMillis<0)fullBackgroundStartedMillis=(System.nanoTime()-startedNanos)/1_000_000.0;
        TerrainLodColumns.Result result=null;int step=firstStep;
        for(;step<=64;step*=2){
            check(token);long count=new TerrainLodRefinement.Region(input.worldBounds(),step).sampledVoxels();
            result=owned.raw.get(new RawKey(input.worldBounds(),step));
            if(result!=null)owned.rawHits.increment();else result=loadFar(owned,input,step,count,token);
            if(result!=null)break;
            // Reserve each complete original source lattice BEFORE sampling. This is separate
            // from other workers' actual counters and remains truthful across camera retargets.
            reserve(owned,count,token);
            TrailNetwork.SampleSource checked=(x,y,z)->{check(token);owned.actualSamples.increment();return owned.source.terrain().sample(x,y,z);};
            try{result=CompactLodLists.result(TerrainLodColumns.build(checked,input.worldBounds(),new TerrainLodColumns.Parameters(step,count,input.limits().maxBoxes())));
                check(token);owned.raw.put(new RawKey(input.worldBounds(),step),result);break;}
            catch(TerrainLodColumns.BudgetExceededException actualBoxOverflow){if(owned.streaming||step==64)throw actualBoxOverflow;}
            finally{release(owned,count);}
        }
        var surfaces=AbyssLodSurfacePriority.index(Objects.requireNonNull(result).boxes(),input.limits().maxFragments(),step,input.worldBounds());check(token);
        var ordered=new ArrayList<>(result.boxes());var c=input.camera();ordered.sort(Comparator
                .comparingInt((TerrainLodColumns.Box b)->view(b,c)).thenComparingDouble(b->distance(b,c))
                .thenComparingLong(TerrainLodColumns.Box::minX).thenComparingLong(TerrainLodColumns.Box::minZ).thenComparingLong(TerrainLodColumns.Box::minY));
        return new FarPrepared(input.worldBounds(),result,step,surfaces,ordered);
    }
    private static int foregroundWorkers(Context owned){return farPending(owned)?owned.workerCount-1:owned.workerCount;}
    // farWork is cleared by other threads; read it once so a null cannot appear between the checks.
    private static boolean farPending(Context owned){var work=owned.farWork;return work!=null&&!work.isDone();}
    private static boolean farDone(Context owned){var work=owned.farWork;return work!=null&&work.isDone();}
    private void lightOwners(Context owned,SurfaceOwnerLightPlan<Light> plan,SurfaceOwnerLightPlan.Batch batch,long token){
        if(batch.isEmpty())return;
        if(owned.source.lighting().batchLightEnabled()){var boxes=new ArrayList<TerrainLodColumns.Box>(batch.size());for(var owner:batch)boxes.add(plan.box(owner));var values=scalarBatch(owned,boxes,token,false);check(token);plan.complete(batch,values);return;}
        var values=BoundedOrderedWork.map(batch,foregroundWorkers(owned),workers,owner->scalar(owned,plan.box(owner),token));
        check(token);plan.complete(batch,values);
    }
    private Light scalar(Context owned,TerrainLodColumns.Box box,long token){return scalar(owned,box,token,false);}
    private Light cachedScalar(Context owned,TerrainLodColumns.Box box,long token){checkScalar(owned,token);var value=owned.light.get(box);
        if(value!=null){owned.lightHits.increment();return value;}
        var archive=owned.scalarArchive;
        if(archive!=null)try{value=archive.get(box,()->checkScalar(owned,token));}
            catch(IOException failed){owned.scalarArchiveError="READ_REFUSED:"+failed.getMessage();throw new IllegalStateException("Exact scalar archive read failed",failed);}
        if(value!=null){checkScalar(owned,token);owned.light.put(box,value);owned.lightHits.increment();return value;}
        return null;
    }
    private void scalarMiss(Context owned,TerrainLodColumns.Box box){owned.lightMisses.increment();if(owned.measuredLight!=null&&!owned.measuredLight.add(box))owned.repeatedLight.increment();}
    private Light commitScalar(Context owned,TerrainLodColumns.Box box,Light value,long token){
        checkScalar(owned,token);var archive=owned.scalarArchive;
        if(archive!=null)try{archive.put(box,value,()->checkScalar(owned,token));}
            catch(IOException failed){owned.scalarArchiveError="WRITE_REFUSED:"+failed.getMessage();throw new IllegalStateException("Exact scalar archive write failed",failed);}
        checkScalar(owned,token);owned.light.put(box,value);owned.lightCompleted.increment();return value;
    }
    private Light scalar(Context owned,TerrainLodColumns.Box box,long token,boolean allowDeferred){var value=cachedScalar(owned,box,token);if(value!=null)return value;
        // A batch captures its scheduling mode. If final geometry arrives while it
        // runs, obsolete intermediate owners still must not trigger original32;
        // surviving missing owners are released and selected by a new exact batch.
        if(allowDeferred)return null;
        scalarMiss(owned,box);
        value=Objects.requireNonNull(owned.source.lighting().light(box));checkScalar(owned,token);
        return commitScalar(owned,box,value,token);
    }
    private List<Light> scalarBatch(Context owned,List<TerrainLodColumns.Box> boxes,long token,boolean allowDeferred){
        var values=new ArrayList<Light>(Collections.nCopies(boxes.size(),null));var missing=new ArrayList<TerrainLodColumns.Box>();var indices=new ArrayList<Integer>();
        long lookupStart=System.nanoTime();try{
        for(int i=0;i<boxes.size();i++){var box=boxes.get(i);var cached=cachedScalar(owned,box,token);values.set(i,cached);
            if(cached==null&&!allowDeferred){scalarMiss(owned,box);missing.add(box);indices.add(i);}}
        }finally{owned.exactLookupNanos.add(System.nanoTime()-lookupStart);}
        if(!missing.isEmpty()){
            List<Light> computed;long computeStart=System.nanoTime();try{
                computed=Objects.requireNonNull(owned.source.lighting().lightBatch(List.copyOf(missing),()->checkScalar(owned,token)));checkScalar(owned,token);
            }finally{owned.exactComputeNanos.add(System.nanoTime()-computeStart);}
            if(computed.size()!=missing.size()||computed.stream().anyMatch(Objects::isNull))throw new IllegalStateException("Malformed exact light batch; nothing committed");
            long commitStart=System.nanoTime();try{for(int i=0;i<missing.size();i++)values.set(indices.get(i),commitScalar(owned,missing.get(i),computed.get(i),token));}
            finally{owned.exactCommitNanos.add(System.nanoTime()-commitStart);}
        }
        checkScalar(owned,token);return values;
    }
    private void checkScalar(Context owned,long token){check(token);if(owned.scalarRetired)throw new CancellationException("Exact scalar authority retired");
        if(owned.source.lighting() instanceof AbyssLodPhysicalLighting physical)physical.requireAuthority();}
    private List<LitBox> light(Context owned,List<TerrainLodColumns.Box> geometry,long token) {
        if(owned.source.lighting().batchLightEnabled()){var values=scalarBatch(owned,geometry,token,false);var out=new ArrayList<LitBox>(geometry.size());for(int i=0;i<geometry.size();i++)out.add(new LitBox(geometry.get(i),values.get(i)));return List.copyOf(out);}
        return BoundedOrderedWork.map(geometry,foregroundWorkers(owned),workers,box->new LitBox(box,scalar(owned,box,token)));
    }
    private void initDisk(Context owned,long token) {
        if(owned.diskLoaded)return;owned.diskLoaded=true;String directory=cacheDirectoryOverride==null?System.getProperty("mia.dh.previewCacheDirectory",""):cacheDirectoryOverride.toString();
        if(directory.isBlank()||!owned.source.target().sourceIdentity().matches("[0-9a-f]{64}"))return;
        check(token);owned.disk=Path.of(directory).resolve("abyss-source-window-v1");
        owned.diskCode=TerrainLodDiskCache.codeIdentity(AbyssLodPipeline.class,AbyssLodSourcePages.class,AbyssLodSurfacePriority.class,AbyssLodFineLedger.class,AbyssLodFarField.class,
                SurfaceQuadPlan.class,
                SurfaceQuadPages.class,
                SurfaceOwnerLightPlan.class,
                SurfacePageCoverage.class,
                IncrementalLodComposition.class,IncrementalSurfacePages.class,StrongBoxKey.class,BoxCacheRevision.class,
                AbyssLodRenderSurface.class,
                AbyssLodActiveSelection.class,
                AbyssNativeDensity.class,
                TerrainLodColumns.class,TerrainLodClipper.class,TerrainLodRefinement.class,TerrainTileDiskCache.class,TerrainLodDiskCache.class,
                OpaqueBoxFaces.class,
                dev.madeinabyss.kernel.terrain.TerrainMaterial.class,BoundedOrderedWork.class,BoundedReuseCache.class,
                dev.madeinabyss.forge.worldgen.GeneratedSkySource.class,dev.madeinabyss.forge.worldgen.Generated155DerivedProjection.class,dev.madeinabyss.forge.worldgen.ParameterizedTerrainChunkGenerator.class,
                dev.madeinabyss.forge.worldgen.GeneratedDeepAirCellCertificate.class,dev.madeinabyss.forge.worldgen.GeneratedDeepAirCertificate.class,
                dev.madeinabyss.forge.worldgen.GeneratedIslandAirCertificate.class,dev.madeinabyss.kernel.light.DeepCavityAirCellBounds.class,
                dev.madeinabyss.kernel.light.DeepCavityAirBounds.class,dev.madeinabyss.kernel.terrain.CompiledTerrainSampler.class,
                owned.source.terrain().getClass().getNestHost(),owned.source.lighting().getClass().getNestHost());
        owned.tileKey=TerrainLodDiskCache.identity(POLICY,owned.source.target().sourceIdentity(),owned.diskCode,"raw-tile-pack");
        var rawCode=TerrainLodDiskCache.codeIdentity(AbyssLodPipeline.class,TerrainLodColumns.class,TerrainTileDiskCache.class,AbyssRawTileArchive.class,AbyssNativeDensity.class,
                dev.madeinabyss.kernel.terrain.TerrainMaterial.class,dev.madeinabyss.forge.worldgen.GeneratedSkySource.class,dev.madeinabyss.forge.worldgen.Generated155DerivedProjection.class,
                dev.madeinabyss.kernel.terrain.CompiledTerrainSampler.class,
                dev.madeinabyss.forge.worldgen.WildernessBlockStates.class,dev.madeinabyss.forge.worldgen.CaveInteriorBlockStates.class,
                dev.madeinabyss.forge.worldgen.AlexEcologyBlockStates.class,
                dev.madeinabyss.forge.worldgen.ParameterizedTerrainChunkGenerator.class,owned.source.terrain().getClass().getNestHost());
        owned.archive=new AbyssRawTileArchive(Path.of(directory).resolve("abyss-xyz-raw"),
                TerrainLodDiskCache.identity("xyz-raw-scope-v1",owned.source.target().sourceIdentity(),rawCode,dev.madeinabyss.forge.worldgen.Generated155ExactView.codeIdentity()),owned.source.lighting().provisionalLightEnabled()?8192:32);
        if(owned.source.lighting() instanceof AbyssLodPhysicalLighting physical)
            initScalarArchive(owned,Path.of(directory).resolve("abyss-physical-scalars"),physical,token);
        var tiles=TerrainTileDiskCache.load(owned.disk.resolve("tiles"),owned.tileKey);owned.cacheStatus=tiles.status();
        for(var e:tiles.tiles().entrySet()){check(token);var k=e.getKey();var b=new TerrainLodColumns.Bounds(k.x(),k.y(),k.z(),k.endX(),k.endY(),k.endZ());
            owned.raw.put(new RawKey(b,k.step()),CompactLodLists.result(new TerrainLodColumns.Result(e.getValue(),new TerrainLodRefinement.Region(b,k.step()).sampledVoxels())));}
    }
    private static String farKey(Context owned,TerrainLodColumns.Bounds bounds,int step){return TerrainLodDiskCache.identity(POLICY,
            owned.source.target().sourceIdentity(),owned.diskCode,bounds.toString(),Integer.toString(step),"raw-far");}
    private TerrainLodColumns.Result loadFar(Context owned,Input input,int step,long count,long token) {
        if(owned.disk==null)return null;check(token);var read=TerrainLodDiskCache.load(owned.disk.resolve("far-"+step),farKey(owned,input.worldBounds(),step),
                input.worldBounds(),new TerrainLodColumns.Parameters(step,count,input.limits().maxBoxes()));owned.cacheStatus=read.status();
        if(read.entry().isEmpty())return null;var result=CompactLodLists.result(read.entry().orElseThrow().base());owned.raw.put(new RawKey(input.worldBounds(),step),result);owned.rawHits.increment();return result;
    }
    private void saveFar(Context owned,Input input,long token) {
        synchronized(rawPersistenceLock){
        if(owned.disk==null)return;checkScalar(owned,token);try{TerrainLodDiskCache.save(owned.disk.resolve("far-"+owned.farStep),farKey(owned,input.worldBounds(),owned.farStep),
                new TerrainLodDiskCache.Entry(owned.far,Map.of()),input.worldBounds(),new TerrainLodColumns.Parameters(owned.farStep,
                        owned.far.sampledVoxels(),input.limits().maxBoxes()));}
        catch(IOException failed){owned.cacheStatus="WRITE_REFUSED: "+failed.getMessage();}
        }
    }
    private void persist(Context owned,Input input,long token) {
        if(owned.disk==null)return;check(token);var pack=new LinkedHashMap<TerrainTileDiskCache.Key,List<TerrainLodColumns.Box>>();int boxes=0;
        for(var e:owned.raw.snapshot().entrySet()){check(token);var k=e.getKey();var b=k.bounds();
            if((long)b.maxXInclusive()-b.minX()>=16||(long)b.maxYInclusive()-b.minY()>=64||(long)b.maxZInclusive()-b.minZ()>=16)continue;
            if(pack.size()>=32768||e.getValue().boxes().size()>500000-boxes)break;boxes+=e.getValue().boxes().size();
            pack.put(new TerrainTileDiskCache.Key(b.minX(),b.minY(),b.minZ(),b.maxXInclusive(),b.maxYInclusive(),b.maxZInclusive(),k.step()),e.getValue().boxes());}
        synchronized(rawPersistenceLock){checkScalar(owned,token);
            try{TerrainTileDiskCache.save(owned.disk.resolve("tiles"),owned.tileKey,pack);}catch(IOException failed){owned.cacheStatus="WRITE_REFUSED: "+failed.getMessage();}}
    }
    private static TerrainTileDiskCache.Key tileKey(TerrainLodColumns.Bounds b,int step) {
        if((long)b.maxXInclusive()-b.minX()>=16||(long)b.maxYInclusive()-b.minY()>=64||(long)b.maxZInclusive()-b.minZ()>=16)return null;
        return new TerrainTileDiskCache.Key(b.minX(),b.minY(),b.minZ(),b.maxXInclusive(),b.maxYInclusive(),b.maxZInclusive(),step);
    }
    private void archivePut(Context owned,TerrainTileDiskCache.Key tile,TerrainLodColumns.Result result,long token) {
        if(owned.archive==null||tile==null)return;
        long waiting=owned.diagnostics.now();
        synchronized(rawPersistenceLock){owned.diagnostics.record("rawWriteLockWait",owned.diagnostics.now()-waiting,0);checkScalar(owned,token);
            long started=owned.diagnostics.now(),cpu=owned.diagnostics.cpu();
            try{owned.archive.put(tile,result.boxes());}catch(IOException failed){owned.archiveError="WRITE_REFUSED: "+failed.getMessage();}
            finally{owned.diagnostics.finish("rawWrite",started,cpu);}}
    }
    private List<TerrainLodColumns.Box> archiveGet(Context owned,TerrainTileDiskCache.Key tile,long token){
        // Reads may evict/checkpoint dirty RAW pages too, so use the same reload IO fence.
        long waiting=owned.diagnostics.now();
        synchronized(rawPersistenceLock){owned.diagnostics.record("rawReadLockWait",owned.diagnostics.now()-waiting,0);checkScalar(owned,token);
            long started=owned.diagnostics.now(),cpu=owned.diagnostics.cpu();
            try{var result=owned.archive.get(tile);checkScalar(owned,token);return result;}
            finally{owned.diagnostics.finish("rawRead",started,cpu);}}
    }
    /** Archive complete immutable native source, including AIR, before its moving window disappears. */
    private void ingestNative(Context owned,Input input,long token) {
        var latestDensity=input.nativeDensity();
        if(latestDensity==null)return;
        var authority=latestDensity.authority();
        if(!authority.sourceIdentity().equals(input.target().sourceIdentity())||authority.sourceRevision()!=input.target().sourceRevision()
                ||authority.nativeRevision()!=input.target().nativeRevision())return;
        if(owned.importingNative!=null&&owned.importingNative.authority().epoch()!=authority.epoch()){
            owned.importingNative=null;owned.nativeJobs=List.of();owned.nativeCursor=0;
        }
        if(owned.importingNative==null&&(latestDensity.publicationRevision()!=owned.lastNativePublication||authority.epoch()!=owned.lastNativeEpoch)) {
            owned.importingNative=latestDensity;owned.nativeCursor=0;
            var keys=latestDensity.wholeCubeKeys(authority);var consumed=new HashSet<CubeKey>();var jobs=new ArrayList<TerrainLodColumns.Bounds>();
            for(var cube:keys){if(consumed.contains(cube))continue;
                int baseY=Math.floorDiv(cube.y(),4)*4;boolean whole=true;
                for(int dy=0;dy<4;dy++)if(!keys.contains(new CubeKey(cube.x(),baseY+dy,cube.z()))){whole=false;break;}
                int y=whole?baseY:cube.y(),height=whole?64:16;
                long x=(long)cube.x()*16,by=(long)y*16,z=(long)cube.z()*16;
                jobs.add(new TerrainLodColumns.Bounds(Math.toIntExact(x),Math.toIntExact(by),Math.toIntExact(z),
                        Math.toIntExact(x+15),Math.toIntExact(by+height-1),Math.toIntExact(z+15)));
                if(whole)for(int dy=0;dy<4;dy++)consumed.add(new CubeKey(cube.x(),baseY+dy,cube.z()));else consumed.add(cube);
            }
            jobs.sort(Comparator.comparingDouble((TerrainLodColumns.Bounds b)->distance(b,input.camera()))
                    .thenComparingInt(TerrainLodColumns.Bounds::minX).thenComparingInt(TerrainLodColumns.Bounds::minZ).thenComparingInt(TerrainLodColumns.Bounds::minY));
            owned.nativeJobs=List.copyOf(jobs);
        }
        if(owned.importingNative==null)return;
        var captured=owned.importingNative;int cubes=0;
        while(owned.nativeCursor<owned.nativeJobs.size()&&cubes<128){check(token);var bounds=owned.nativeJobs.get(owned.nativeCursor);
            int count=(int)(((long)bounds.maxYInclusive()-bounds.minY()+1)/16);if(cubes+count>128)break;
            owned.nativeCursor++;cubes+=count;
            for(int step:new int[]{1,2,4,8}){
                var key=new RawKey(bounds,step);if(owned.raw.get(key)!=null)continue;
                var tile=tileKey(bounds,step);
                if(owned.archive!=null){var cached=archiveGet(owned,tile,token);if(cached!=null){owned.raw.put(key,
                        CompactLodLists.result(new TerrainLodColumns.Result(cached,new TerrainLodRefinement.Region(bounds,step).sampledVoxels())));continue;}}
                var raw=captured.rawRegion(captured.authority(),bounds,step,input.limits().maxBoxes());if(raw.isEmpty())continue;
                var result=CompactLodLists.result(raw.orElseThrow());check(token);owned.raw.put(key,result);archivePut(owned,tile,result,token);
                owned.nativeRawTiles++;owned.nativeRawCells+=result.sampledVoxels();
            }
        }
        owned.pendingNativeCubes=0;for(int i=owned.nativeCursor;i<owned.nativeJobs.size();i++)owned.pendingNativeCubes+=
                ((long)owned.nativeJobs.get(i).maxYInclusive()-owned.nativeJobs.get(i).minY()+1)/16;
        if(owned.nativeCursor==owned.nativeJobs.size()){
            owned.lastNativePublication=captured.publicationRevision();owned.lastNativeEpoch=captured.authority().epoch();
            // Completed new exact native data can now satisfy previously unaffordable jobs
            // from the archive. Revisit the sparse order once, without resetting FAR reserve.
            if(owned.deferredSourceJobs>0)owned.jobWindow=null;
            owned.importingNative=null;owned.nativeJobs=List.of();owned.nativeCursor=0;
        }
    }
    /** Four certified quarters reconstruct the original vertical intervals; a missing quarter is UNKNOWN. */
    private List<TerrainLodColumns.Box> stitchArchiveQuarters(Context owned,TerrainLodColumns.Bounds bounds,int step,long token) {
        long height=(long)bounds.maxYInclusive()-bounds.minY()+1;
        if(owned.archive==null||height<=16||height%16!=0||(bounds.minY()&15)!=0)return null;
        var all=new ArrayList<TerrainLodColumns.Box>();
        for(long y=bounds.minY();y<=bounds.maxYInclusive();y+=16){check(token);
            var part=archiveGet(owned,new TerrainTileDiskCache.Key(bounds.minX(),(int)y,bounds.minZ(),bounds.maxXInclusive(),(int)(y+15),bounds.maxZInclusive(),step),token);
            if(part==null)return null;all.addAll(part);
        }
        all.sort(Comparator.comparingLong(TerrainLodColumns.Box::minX).thenComparingLong(TerrainLodColumns.Box::minZ).thenComparingLong(TerrainLodColumns.Box::minY));
        var merged=new ArrayList<TerrainLodColumns.Box>();
        for(var b:all){if(!merged.isEmpty()){var prior=merged.get(merged.size()-1);
                if(prior.minX()==b.minX()&&prior.maxXExclusive()==b.maxXExclusive()&&prior.minZ()==b.minZ()&&prior.maxZExclusive()==b.maxZExclusive()
                        &&prior.maxYExclusive()==b.minY()&&prior.material()==b.material()){
                    merged.set(merged.size()-1,new TerrainLodColumns.Box(prior.minX(),prior.minY(),prior.minZ(),prior.maxXExclusive(),b.maxYExclusive(),prior.maxZExclusive(),prior.material()));continue;}}
            merged.add(b);
        }
        var result=CompactLodLists.result(new TerrainLodColumns.Result(merged,new TerrainLodRefinement.Region(bounds,step).sampledVoxels()));
        archivePut(owned,tileKey(bounds,step),result,token);return result.boxes();
    }
    private void checkpointArchive(Context owned,long token) {
        if(owned.archive==null)return;
        synchronized(rawPersistenceLock){checkScalar(owned,token);
            try{owned.archive.checkpoint();}catch(IOException failed){owned.archiveError="CHECKPOINT_REFUSED: "+failed.getMessage();}}
        owned.archiveMetrics=owned.archive.status();
    }
    private void checkpointScalars(Context owned,long token,boolean force) {
        var archive=owned.scalarArchive;if(archive==null)return;checkScalar(owned,token);
        try{archive.checkpoint(force,()->checkScalar(owned,token));}
        catch(IOException failed){owned.scalarArchiveError="CHECKPOINT_REFUSED:"+failed.getMessage();
            if(force)throw new IllegalStateException("Terminal exact scalar archive checkpoint failed; durable completion refused",failed);}
    }
    /** Called on a worker. A retired archive must commit before another instance opens the same directory. */
    private void initScalarArchive(Context owned,Path directory,AbyssLodPhysicalLighting physical,long token){
        AbyssLodScalarAuthority authority;
        try{authority=physical.archiveAuthority(owned.source.target().sourceIdentity());}
        catch(AbyssLodResourceIdentity.UnavailableException unavailable){owned.scalarArchiveError="DISABLED_UNVERIFIED_RESOURCE:"+unavailable.getMessage();return;}
        checkScalar(owned,token);
        var path=directory.toAbsolutePath().normalize();
        synchronized(SCALAR_REGISTRY_LOCK){
            checkScalar(owned,token);var previous=SCALAR_REGISTRY.get(path);
            if(previous!=null){
                if(previous.owner()==owned){owned.scalarArchive=previous.archive();return;}
                if(!previous.owner().scalarRetired)throw new IllegalStateException("Exact scalar directory already has an active owner: "+path);
                try{closeScalarRegistration(previous);SCALAR_REGISTRY.remove(path);}
                catch(IOException failed){previous.owner().scalarArchiveError="RETIRE_REFUSED:"+failed.getMessage();throw new IllegalStateException("Previous exact scalar archive cannot retire",failed);}
            }
            checkScalar(owned,token);
            var archive=new AbyssLodScalarArchive(path,authority.familyIdentity(),AbyssLodPhysicalLighting.codeIdentity(),
                owned.source.worldBounds(),owned.source.limits().maxBoxes(),authority::pageIdentity);
            SCALAR_REGISTRY.put(path,new ScalarRegistration(owned,archive,this));owned.scalarArchive=archive;
            if(streaming)owned.scalarCompletionSeal=new AbyssLodCompletionSeal(path.resolve("completed-views"),authority.familyIdentity(),authority.globalRevision(),owned.diskCode);
        }
    }
    /** Owner/render thread only marks retirement and coalesces an IO task; it never waits on disk or archive locks. */
    private static void retireScalars(Context owned){owned.scalarRetired=true;SCALAR_RETIRE_REQUESTED.set(true);scheduleScalarRetirement();}
    private static void scheduleScalarRetirement(){
        if(SCALAR_RETIRE_RUNNING.compareAndSet(false,true))SCALAR_RETIRE_EXECUTOR.execute(AbyssLodPipeline::drainScalarRetirement);
    }
    private static void drainScalarRetirement(){
        try{do{SCALAR_RETIRE_REQUESTED.set(false);
            synchronized(SCALAR_REGISTRY_LOCK){var iterator=SCALAR_REGISTRY.entrySet().iterator();
                while(iterator.hasNext()){var registered=iterator.next().getValue();if(!registered.owner().scalarRetired)continue;
                    try{closeScalarRegistration(registered);iterator.remove();}
                    catch(IOException|RuntimeException failed){registered.owner().scalarArchiveError="RETIRE_REFUSED:"+failed.getMessage();}
                }
            }
        }while(SCALAR_RETIRE_REQUESTED.get());}
        finally{SCALAR_RETIRE_RUNNING.set(false);if(SCALAR_RETIRE_REQUESTED.get())scheduleScalarRetirement();}
    }
    private static void closeScalarRegistration(ScalarRegistration registration)throws IOException {
        if(registration.owner().source.lighting() instanceof AbyssLodPhysicalLighting physical&&physical.authorityRetired()){
            synchronized(registration.pipeline().rawPersistenceLock){
                registration.archive().quarantine();var owned=registration.owner();
                if(owned.archive!=null)owned.archive.quarantine();
                if(owned.disk!=null)DerivedCacheQuarantine.moveChild(owned.disk.getParent(),owned.disk.getFileName().toString());
            }
        }
        else registration.archive().close();
    }
    private static double cameraDistance(Camera a,Camera b){return Math.hypot(Math.hypot(a.x()-b.x(),a.y()-b.y()),a.z()-b.z());}
    private static TerrainLodColumns.Bounds intersect(TerrainLodColumns.Bounds a,TerrainLodColumns.Bounds b){return new TerrainLodColumns.Bounds(
            Math.max(a.minX(),b.minX()),Math.max(a.minY(),b.minY()),Math.max(a.minZ(),b.minZ()),Math.min(a.maxXInclusive(),b.maxXInclusive()),Math.min(a.maxYInclusive(),b.maxYInclusive()),Math.min(a.maxZInclusive(),b.maxZInclusive()));}
    private static TerrainLodColumns.Bounds around(TerrainLodColumns.Bounds world,Camera camera,int radius){long x=signedFloor(camera.x()),y=signedFloor(camera.y()),z=signedFloor(camera.z());
        return new TerrainLodColumns.Bounds((int)Math.max(world.minX(),x-radius),(int)Math.max(world.minY(),y-radius),(int)Math.max(world.minZ(),z-radius),
                (int)Math.min(world.maxXInclusive(),x+radius-1),(int)Math.min(world.maxYInclusive(),y+radius-1),(int)Math.min(world.maxZInclusive(),z+radius-1));}
    private static boolean interior(TerrainLodColumns.Bounds b,Camera c,int margin){long mx=Math.min(margin,((long)b.maxXInclusive()-b.minX())/4),
            my=Math.min(margin,((long)b.maxYInclusive()-b.minY())/4),mz=Math.min(margin,((long)b.maxZInclusive()-b.minZ())/4);
        return c.x()>=b.minX()+mx&&c.x()<=b.maxXInclusive()-mx&&c.y()>=b.minY()+my&&c.y()<=b.maxYInclusive()-my
                &&c.z()>=b.minZ()+mz&&c.z()<=b.maxZInclusive()-mz;}
    private static double clamp(double value,long min,long max){return Math.max(min,Math.min(value,max));}
    private static double distance(TerrainLodColumns.Bounds b,Camera c){double dx=Math.max(0,Math.max(b.minX()-c.x(),c.x()-((long)b.maxXInclusive()+1))),
            dy=Math.max(0,Math.max(b.minY()-c.y(),c.y()-((long)b.maxYInclusive()+1))),dz=Math.max(0,Math.max(b.minZ()-c.z(),c.z()-((long)b.maxZInclusive()+1)));return Math.hypot(Math.hypot(dx,dy),dz);}
    private static double distance(TerrainLodColumns.Box b,Camera c){double dx=Math.max(0,Math.max(b.minX()-c.x(),c.x()-b.maxXExclusive())),
            dy=Math.max(0,Math.max(b.minY()-c.y(),c.y()-b.maxYExclusive())),dz=Math.max(0,Math.max(b.minZ()-c.z(),c.z()-b.maxZExclusive()));return Math.hypot(Math.hypot(dx,dy),dz);}
    private static int view(TerrainLodColumns.Box b,Camera c){double x=clamp(c.x(),b.minX(),b.maxXExclusive()-1)-c.x(),y=clamp(c.y(),b.minY(),b.maxYExclusive()-1)-c.y(),z=clamp(c.z(),b.minZ(),b.maxZExclusive()-1)-c.z();
        double d=Math.hypot(Math.hypot(x,y),z);return d==0||x*c.forwardX()+y*c.forwardY()+z*c.forwardZ()>=d*.25?0:1;}
    private static boolean sameInitialView(Camera a,Camera b){return Math.hypot(Math.hypot(a.x()-b.x(),a.y()-b.y()),a.z()-b.z())<32
            &&a.forwardX()*b.forwardX()+a.forwardY()*b.forwardY()+a.forwardZ()*b.forwardZ()>.996;}
    private static int signedFloor(double value){double v=Math.floor(value);if(v<Integer.MIN_VALUE||v>Integer.MAX_VALUE)throw new IllegalArgumentException("Camera outside signed XYZ");return(int)v;}
    private void check(long token){if(token!=generation||Thread.currentThread().isInterrupted())throw new CancellationException("LOD source lease changed");}
    public synchronized Optional<Proposal> poll() {
        if(closed)return Optional.empty();servicePendingExact();if(pending!=null)return Optional.of(pending.proposal());
        if(work==null&&error.isEmpty()&&canContinue(context))schedule();
        if(work==null||!work.isDone())return Optional.empty();
        var ready=work;work=null;
        try{var result=ready.get();
            // Yield is normal when only the independent FAR task can make further progress,
            // or another exact-light quantum is needed. It never becomes an empty WORLD ACK.
            if(result==null){if(canContinue(context))schedule();return Optional.empty();}
            if(result.proposal().target().equals(target)){pending=result;return Optional.of(result.proposal());}schedule();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(ExecutionException failure){if(failure.getCause() instanceof Retargeted)schedule();else {cancelFine(context);error=String.valueOf(failure.getCause());}}
        catch(CancellationException canceled){ }
        return Optional.empty();
    }
    private boolean canContinue(Context owned){if(owned==null)return false;
        if(owned.continuePreparation||owned.surfaceLightInFlight>0||owned.importingNative!=null||farDone(owned)||owned.fineWork!=null
                ||owned.streaming&&fineWorldAcknowledged&&owned.fineCursor<owned.jobs.size())return true;
        var input=latest;if(input==null||!sameSource(owned.source.target(),input.target()))return false;
        var density=input.nativeDensity();if(density==null)return false;var authority=density.authority();
        return authority.sourceIdentity().equals(input.target().sourceIdentity())&&authority.sourceRevision()==input.target().sourceRevision()
            &&authority.nativeRevision()==input.target().nativeRevision()
            &&(density.publicationRevision()!=owned.lastNativePublication||authority.epoch()!=owned.lastNativeEpoch);
    }
    /** A hidden/shadow/old-target callback does not release worker sequencing or commit quality. */
    public synchronized boolean acknowledge(Proposal proposal,boolean promotedOnCurrentTargetWorldDraw) {
        if(!promotedOnCurrentTargetWorldDraw||closed||pending==null||pending.proposal()!=proposal||!proposal.target().equals(target))return false;
        ledger.promote(pending.ledger());
        if(pending.pagePublication()!=null){
            context.surfacePublishedPages=proposal.surfacePages();context.surfacePublishedTarget=proposal.target();context.surfacePublishedCuts=latest.nativeCuts();
            if(proposal.stage()==Stage.PARTIAL_PAGES)context.surfacePartialPublications++;else if(proposal.fullBackgroundReady())context.surfaceFullPublications++;
        }
        pending=null;acknowledged=proposal.sequence();completed=proposal.requestedComplete();exactLightComplete=completed&&proposal.exactLightComplete();
        double worldMillis=(System.nanoTime()-startedNanos)/1e6;
        if(completed&&!proposal.target().equals(context.geometryWorldTarget)){context.geometryWorldTarget=proposal.target();context.geometryFirstWorldMillis=worldMillis;}
        if(exactLightComplete&&!proposal.target().equals(context.exactWorldTarget)){context.exactWorldTarget=proposal.target();context.exactFirstWorldMillis=worldMillis;}
        var audit=context.visibleAudit;var proof=context.preparedAuditFrame;
        if(audit!=null&&proof!=null&&proposal.target().equals(context.visibleAuditTarget)&&proof.sequence()==proposal.sequence())audit.worldDraw(proof,true,worldMillis);
        if(proposal.stage()==Stage.NEAR_FINE&&!proposal.boxes().isEmpty())fineWorldAcknowledged=true;
        if((!completed||canContinue(context))&&work==null)schedule();return true;
    }
    public long timingOriginNanos(){return startedNanos;}
    /** Nonblocking owner-thread service while the renderer holds a staged publication. */
    public synchronized void servicePendingExact(){
        if(closed||!finishBackgroundExact()||!error.isEmpty()||work!=null||pending==null||!streaming)return;
        var owned=context;var input=latest;
        if(owned==null||input==null||!owned.surfaceGeometryReady||!input.target().equals(owned.geometryWorldTarget)
                ||!input.target().equals(pending.proposal().target())||!owned.source.lighting().batchLightEnabled()
                ||owned.surfaceLightRemaining==0&&owned.surfaceLightInFlight==0)return;
        long token=generation;
        backgroundExactFeed=coordinator.submit(()->{
            check(token);if(!input.target().equals(latest.target()))return;
            int adopted=adoptPageLight(owned,token);
            dispatchPageLight(owned,surfacePageOrder(input.camera(),128),true,token);
            adopted+=adoptPageLight(owned,token);if(adopted>0)owned.surfacePublicationDirty=true;
            var debt=owned.incrementalPages.status();owned.surfaceLightRemaining=pageNumber(debt,"visibleMissing");
            owned.surfaceLocalLightRemaining=pageNumber(debt,"localVisibleMissing");
            owned.continuePreparation=owned.surfaceLightRemaining>0||owned.surfaceLightInFlight>0||owned.surfacePublicationDirty;
            checkpointScalars(owned,token,false);
        });
    }
    /** Completed futures only: owner/render thread never waits for coordinator or IO. */
    private boolean finishBackgroundExact(){
        var ready=backgroundExactFeed;if(ready==null)return true;if(!ready.isDone())return false;backgroundExactFeed=null;
        try{ready.get();}catch(CancellationException canceled){}
        catch(InterruptedException canceled){Thread.currentThread().interrupt();}
        catch(ExecutionException failed){error=String.valueOf(failed.getCause());}
        return true;
    }
    public synchronized Map<String,Object> status(){var out=new LinkedHashMap<String,Object>();out.put("active",!closed);out.put("error",error);
        out.put("workerPending",work!=null||backgroundExactFeed!=null);out.put("backgroundExactPending",backgroundExactFeed!=null);out.put("proposalPending",pending!=null);out.put("acknowledgedSequence",acknowledged);out.put("completed",completed);
        out.put("geometryComplete",completed);out.put("exactLightComplete",exactLightComplete);
        out.put("completionScope",completed&&!exactLightComplete?"REQUESTED_GEOMETRY_PROVISIONAL_LIGHT":"EXACT_GEOMETRY_AND_LIGHT");
        if(context!=null){var quality=new LinkedHashMap<String,Object>();
            quality.put("mode","CAMERA_FACE_CLEAR_LOS_SAMPLE_WORLD_ACK");quality.put("sampleLimit",1024);quality.put("tolerance",2);quality.put("requiredFraction",.95);
            quality.put("auditSetupMillis",context.visibleAuditSetupMillis);
            quality.put("geometryWorldMillis",target!=null&&target.equals(context.geometryWorldTarget)?context.geometryFirstWorldMillis:-1);
            quality.put("exactCompleteWorldMillis",target!=null&&target.equals(context.exactWorldTarget)?context.exactFirstWorldMillis:-1);
            quality.put("probesSkippedForGpu",context.probesSkippedForGpu);
            quality.put("probePagesPending",context.probeBootstrapPending);quality.put("visibleProbePagesPending",context.probeVisiblePending);
            quality.put("exactQuotaLimitedMillis",(context.probeVisiblePending?System.nanoTime()-context.probeQuotaStartNanos:context.probeQuotaLimitedNanos)/1e6);
            if(target!=null&&target.equals(context.probeBootstrapTarget)){
                quality.put("probePagesPending",context.probeBootstrapPending);quality.put("visibleProbePagesPending",context.probeVisiblePending);
                quality.put("visibleProbeReadyFromTargetMillis",context.probeVisibleReadyMillis);
                quality.put("exactQuotaLimitedMillis",(context.probeVisiblePending?System.nanoTime()-context.probeQuotaStartNanos:context.probeQuotaLimitedNanos)/1e6);
            }
            if(context.visibleAudit!=null&&target!=null&&target.equals(context.visibleAuditTarget)){
                var s=context.visibleAudit.status();quality.put("samples",s.samples());quality.put("referenceKnown",s.referenceKnown());quality.put("displayed",s.displayed());
                quality.put("nativeVisiblePages",context.visibleAudit.visiblePages().size());
                quality.put("withinTolerance",s.within());quality.put("falseDark",s.falseDark());quality.put("falseBright",s.falseBright());
                quality.put("referenceCoverage",s.coverage());quality.put("correctFraction",s.correctFraction());quality.put("falseDarkFraction",s.falseDarkFraction());quality.put("falseBrightFraction",s.falseBrightFraction());
                quality.put("worldSequence",s.sequence());quality.put("firstCorrectWorldMillis",s.firstCorrectWorldMillis());
                quality.put("firstCorrectFromTargetMillis",s.firstCorrectWorldMillis()<0?-1:s.firstCorrectWorldMillis()-(targetStartedNanos-startedNanos)/1e6);
                quality.put("firstCorrectAfterGeometryMillis",s.firstCorrectWorldMillis()<0?-1:s.firstCorrectWorldMillis()-context.geometryFirstWorldMillis);
            }else{quality.put("samples",0);quality.put("firstCorrectWorldMillis",-1);}
            out.put("visibleLightQuality",Collections.unmodifiableMap(quality));
        }
        out.put("displayedFineGrids",ledger==null?0:ledger.displayed().size());out.put("fullBackgroundStartedMillis",fullBackgroundStartedMillis);
        out.put("timingOriginNanos",startedNanos);out.put("policy",POLICY);out.put("fineWorldAcknowledged",fineWorldAcknowledged);
        if(context!=null){out.put("startupEmpty",context.startupEmpty);out.put("cpuPhase",context.phase);out.put("plannedFineJobs",context.plannedJobs);
            if(context.metrics){out.put("plannedFineSamples",context.plannedFineSamples);out.put("fineCursor",context.fineCursor);
                out.put("lightCacheEntries",context.light.size());out.put("lightCacheEvictions",context.light.evictions());
                out.put("repeatHistoryEnabled",context.measuredLight!=null);out.put("repeatedLightComputations",context.measuredLight==null?-1:context.repeatedLight.sum());
                out.put("repeatHistoryEntries",context.measuredLight==null?0:context.measuredLight.size());
                out.put("repeatHistoryUntracked",context.measuredLight==null?0:context.measuredLight.untracked());}
            out.put("preparedFineJobs",context.preparedJobs);out.put("farRawBoxes",context.rawBoxes);out.put("farPreparedBoxes",context.litFarBoxes);
            out.put("surfaceBoundaryHints",context.surfaceHints);out.put("lastPreparedFineBatch",context.lastFineBatch);out.put("maximumFineBatch",streaming?streamFineBatch(context):surfaceMesh?32:MAX_FINE_BATCH);
            out.put("streamingSourceBudget",streaming);out.put("peakSourceReserve",context.peakCharged.get());
            out.put("residentScale",residentScale);out.put("residentLedgerBytes",context.residentLedgerBytes);out.put("residentPageIndexBytes",context.surfaceLimits.maxIndexBytes());
            out.put("fineBatchScratchBytes",context.fineBatchScratchBytes);out.put("pageWorkers",pageWorkerCount);
            out.put("selectionBuilds",context.selectionBuilds);out.put("selectionReuses",context.selectionReuses);
            if(context.publishedLightSummary!=null)out.put("publishedLightSummary",context.publishedLightSummary.stats());
            out.put("maximumFineScratchBytes",context.maximumFineScratchBytes);
            out.put("liveFineScratchBytes",context.scratchCharged.get());out.put("peakFineScratchLeases",context.peakScratchCharged.get());
            var fine=context.fineWork;out.put("fineSourcePending",fine!=null);out.put("fineSourceReady",fine!=null&&fine.ready());
            out.put("fineSourceParallelism",fine==null?0:fine.parallelism);out.put("finePrefetchStarts",context.finePrefetchStarts);
            out.put("finePrefetchAdoptions",context.finePrefetchAdoptions);out.put("finePrefetchRetirements",context.finePrefetchRetirements);
            out.put("freshSampleReserve",context.charged.get());out.put("actualFreshSamples",context.actualSamples.sum());out.put("rawCacheHits",context.rawHits.sum());
            out.put("lightTaskCpuNanos",context.lightTaskCpuNanos.sum());out.put("lightTaskWallNanos",context.lightTaskWallNanos.sum());out.put("lightTaskOwners",context.lightTaskOwners.sum());
            out.put("lightCacheHits",context.lightHits.sum());out.put("actualLightJobs",context.lightMisses.sum());out.put("diskCache",context.cacheStatus);}
        if(context!=null){out.put("rawArchive",context.archiveMetrics);out.put("rawArchiveError",context.archiveError);
            out.put("rawArchiveRestoredTiles",context.archiveRestoredTiles.sum());out.put("rawArchiveRestoredSampledCells",context.archiveRestoredCells.sum());
            out.put("scalarArchive",context.scalarArchive==null?Map.of():context.scalarArchive.status());out.put("scalarArchiveError",context.scalarArchiveError);
            out.put("scalarCompletionSeal",context.scalarCompletionSeal==null?Map.of():context.scalarCompletionSeal.status());
            out.put("scalarSealHit",context.scalarSealHit);out.put("scalarSealError",context.scalarSealError);out.put("scalarMissDeferral",context.scalarMissDeferral);
            out.put("scalarDeferredOwners",context.scalarDeferredOwners.size());out.put("scalarDeferredAttempts",context.scalarDeferredAttempts);
            out.put("scalarDeferredOverflows",context.scalarDeferredOverflows);out.put("scalarDeferredRetired",context.scalarDeferredRetired);
            out.put("scalarDeferredTerminal",context.scalarDeferredTerminal);out.put("scalarDeferredTransient",context.scalarDeferredTransient);
            out.put("deferredFineGrids",context.deferredFineGrids);out.put("deferredSourceJobs",context.deferredSourceJobs);out.put("witnessPinnedGrids",context.witnessPins.size());
            out.put("renderSurfaceInputBoxes",context.renderSurfaceInput);out.put("renderSurfaceKeptBoxes",context.renderSurfaceKept);
            out.put("renderSurfaceRemovedBoxes",context.renderSurfaceRemoved);out.put("renderSurfaceBudgetRefused",context.renderSurfaceBudgetRefused);
            out.put("renderSurfaceBuilds",context.renderSurfaceBuilds);out.put("renderSurfaceMillis",context.renderSurfaceMillis);}
        if(context!=null){out.put("nativeRawTiles",context.nativeRawTiles);out.put("nativeRawSampledCells",context.nativeRawCells);
            out.put("lastNativePublication",context.lastNativePublication);out.put("lastNativeEpoch",context.lastNativeEpoch);
            out.put("pendingNativeSourceCubes",context.pendingNativeCubes);out.put("maximumNativeCubesPerTurn",128);}
        if(context!=null){out.put("farSourcePending",farPending(context));
            out.put("farSourceAcquisitions",context.farAcquisitions);out.put("foregroundWorkers",foregroundWorkers(context));
            out.put("maximumSourceWorkers",workerCount);out.put("lastFarLightQuantum",context.lastFarLightQuantum);out.put("maximumFarLightQuantum",streaming?STREAM_LIGHT_BATCH:FAR_LIGHT_BATCH);}
        out.put("surfaceMesh",surfaceMesh);if(context!=null){out.put("surfaceFaces",context.surfaceFaceCount);out.put("surfaceVisibleOwners",context.surfaceOwnerCount);
            out.put("surfaceLightInFlight",context.surfaceLightInFlight);out.put("surfaceLightClaimed",context.surfaceLightClaimed);
            out.put("surfaceLocalLightInFlight",context.surfaceLocalLightInFlight);out.put("surfaceBackgroundLightInFlight",context.surfaceBackgroundLightInFlight);
            out.put("surfaceLightTaskStarts",context.surfaceLightTaskStarts);out.put("surfaceLightTaskCompletions",context.surfaceLightTaskCompletions);out.put("surfaceNoChangeYields",context.surfaceNoChangeYields);
            out.put("exactTaskFlow",Map.ofEntries(
                Map.entry("workerStarts",context.exactWorkerStarts.sum()),Map.entry("workerEnds",context.exactWorkerEnds.sum()),
                Map.entry("readyProduced",context.exactReadyProduced.sum()),Map.entry("readyAdopted",context.exactReadyAdopted.sum()),
                Map.entry("lookupMillis",context.exactLookupNanos.sum()/1e6),Map.entry("computeMillis",context.exactComputeNanos.sum()/1e6),
                Map.entry("commitMillis",context.exactCommitNanos.sum()/1e6),Map.entry("selectMillis",context.exactSelectNanos.sum()/1e6),Map.entry("adoptMillis",context.exactAdoptNanos.sum()/1e6)));
            out.put("surfacePages",surfaceMesh?context.incrementalPages.status():context.pages.status());
            out.put("surfaceGlobalGeometryBuilds",surfaceMesh?0:context.renderSurfaceBuilds);
            out.put("incrementalCompositionUpdates",context.incrementalCompositionUpdates);out.put("incrementalRootIndexBuilds",context.incrementalRootIndexBuilds);
            out.put("incrementalFineCells",context.incrementalFineCells);out.put("incrementalFarRoots",context.incrementalFarRoots);
            out.put("incrementalIndexBytes",context.incrementalIndexBytes);out.put("incrementalRetry",context.incrementalRetry);
            out.put("surfacePendingPages",context.surfacePendingPages);out.put("surfaceCurrentPages",context.surfaceCurrentPages);
            out.put("surfaceChangedPages",context.surfaceChangedPages);out.put("surfacePartialPublications",context.surfacePartialPublications);
            out.put("surfaceFullPublications",context.surfaceFullPublications);
            out.put("surfacePageMetres",context.pages.pageMetres());out.put("angularBootstrapPerFiner",1);
            if(latest!=null)out.put("surfaceFourMetreOuterRadius",latest.bands().get(latest.bands().size()-1).radius());
            out.put("angularBootstrapStep",4);out.put("angularBootstrapJobs",context.angularBootstrapJobs);out.put("angularBootstrapRemaining",context.angularBootstrapRemaining);
            out.put("angularPositiveSourceGrids",context.angularPositiveGrids);out.put("angularSectors",context.angularSectors);out.put("undispatchedSurfaceJobs",context.undispatchedSurfaceJobs);
            out.put("surfaceRawFaces",context.surfaceRawFaceCount);out.put("surfaceGreedyMillis",context.surfaceGreedyMillis);out.put("surfaceAdmissionRetries",context.surfaceAdmissionRetries);
            out.put("surfaceMaxCpuFaces",context.surfaceLimits.maxRawFaces());out.put("surfaceMaxRetainedFaceBytes",context.surfaceLimits.maxRetainedFaceBytes());
            out.put("surfaceRawOwners",context.renderSurfaceInput);out.put("surfaceVertexBytes",(long)context.surfaceFaceCount*SurfaceQuadPlan.VERTEX_BYTES_PER_FACE);
            out.put("surfaceGpuBytes",(long)context.surfaceFaceCount*SurfaceQuadPlan.GPU_BYTES_PER_FACE);
            out.put("surfaceLightRemaining",context.surfaceLightRemaining);out.put("surfaceLightPlanBuilds",context.surfaceLightPlanBuilds);
            out.put("surfaceGeometryReady",context.surfaceGeometryReady);out.put("surfaceProvisionalLight",context.surfaceProvisionalLight);
            out.put("presentationLight",context.source.lighting().presentationLightStatus());
            out.put("stagedSourceBytes",context.stagedSourceBytes);out.put("coalescedSourceYields",context.coalescedSourceYields);
            out.put("stageDiagnostics",context.diagnostics.status());
            out.put("activePageWorkers",context.activePageWorkers);out.put("pageQuantum",context.pageQuantum);
            out.put("surfaceLocalLightRemaining",context.surfaceLocalLightRemaining);
            out.put("surfaceLightPlanMillis",context.surfaceLightPlanMillis);out.put("surfaceLitBoxListBuilds",context.surfaceLitBoxListBuilds);
            out.put("surfaceMaxFaces",context.surfaceLimits.maxFaces());out.put("surfaceMaxGpuBytes",context.surfaceLimits.maxGpuBytes());}
        return Collections.unmodifiableMap(out);}
    @Override public synchronized void close(){if(closed)return;closed=true;++generation;if(work!=null)work.cancel(true);if(backgroundExactFeed!=null)backgroundExactFeed.cancel(true);backgroundExactFeed=null;work=null;pending=null;
        if(context!=null){var far=context.farWork;if(far!=null)far.cancel(true);cancelFine(context);}
        if(context!=null){retireScalars(context);cancelPageLight(context);}
        coordinator.shutdownNow();fineOrchestrator.shutdownNow();workers.shutdownNow();if(ledger!=null)ledger.clear();}
}
