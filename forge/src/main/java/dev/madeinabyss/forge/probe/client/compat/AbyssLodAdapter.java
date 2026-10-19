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

import dev.madeinabyss.kernel.lod.AbyssLodFineLedger;
import dev.madeinabyss.kernel.lod.RawGridProofCache;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import dev.madeinabyss.forge.probe.client.GeneratedShaderLightPresentation;
import dev.madeinabyss.forge.probe.client.GeneratedShaderMaterialContext;
import dev.madeinabyss.forge.probe.client.GeneratedFarShadowRenderer;
import dev.madeinabyss.forge.probe.client.GeneratedSurfaceMeshRenderer;
import dev.madeinabyss.kernel.lod.SurfaceQuadPlan;

/** Public-DH transport for the independent exact source pipeline. All GPU ownership stays on client owner. */
public final class AbyssLodAdapter implements DistantHorizonsCompat.Bridge {
    private static final String DISPLAY_NAME="Made in Abyss surface pipeline";
    private static final int CHUNK=4096,CUBE_CAP=512;
    private static final long UPLOAD_NANOS=3_000_000;
    private final boolean surfaceMesh=Boolean.getBoolean("mia.dh.surfaceMesh");
    private final AbyssLodTransport<AbyssLodPipeline.Target,Scene> transport=new AbyssLodTransport<>();
    private AbyssLodGroupLeases<TerrainLodColumns.Box,IDhApiRenderableBoxGroup> groupLeases;
    private final Map<IDhApiRenderableBoxGroup,IDhApiCustomRenderRegister> groupRegistrations=new IdentityHashMap<>();
    private AbyssLodPipeline pipeline;
    private ThreadPoolExecutor conversion;
    private RawGridProofCache rawProofs;
    private Future<Scene> preparing;
    private Scene prepared;
    private boolean initialized,eventsBound,active,scopeActive,generationOwned,overdrawOwned,closed;
    private String error="",phase="WAITING_FOR_DH_INIT",session="",fingerprint="";
    private UUID sessionToken;
    private ClientLevel level;
    private long epoch,lifetime,requestRevision;
    private volatile long resourceGeneration;
    private boolean resourceListenerBound;
    private long beginNanos,startOriginWallMillis,handoffCount,missingDisplayedRefreshFrames;
    private long connectionPreparations,levelLoads,ignoredLevelUnloads,terrainCulls,shadowTerrainCulls;
    private long shaderShadowObjectsSkipped,shaderShadowPassesSkipped,renderCallbacks,preRenderCallbacks;
    private long registrationBatches,registrationMaxNanos,hiddenWarmupDraws,groupCreated,groupClosed;
    private long newGroupBoxes,uploadedBoxes,reusedGroups,reusedBoxes,nativeCacheUpdates;
    private final java.util.concurrent.atomic.LongAdder nativeDensityHits=new java.util.concurrent.atomic.LongAdder(),nativeDensityMisses=new java.util.concurrent.atomic.LongAdder();
    private int maximumOwnedScenes,maximumOwnedGroups;
    private double firstFineWorldDrawMillis=-1,firstFullBackgroundWorldDrawMillis=-1,currentRequestedWorldDrawMillis=-1;
    private IDhApiLevelWrapper wrapper;
    private IDhApiCustomRenderRegister register;
    private IDhApiCullingFrustum ownCuller,delegate;
    private IDhApiShadowCullingFrustum ownShadowCuller,shadowDelegate;
    private int cullerPriority,shadowCullerPriority;
    private IDhApiConfigValue<EDhApiGeneratorPlan> generation;
    private IDhApiConfigValue<Float> overdraw;
    private java.lang.reflect.Method shaderShadowState;
    private TerrainLodColumns.Bounds bounds;
    private JsonObject requested=new JsonObject();
    private AbyssLodPipeline.Input latest;
    private long capturedPhysical=-1,capturedVisual=-1,capturedGeometry=-1;
    private GeneratedSkySource capturedSource;
    private String capturedAuthority="",lastRetargetReason="INITIAL";
    private AbyssLodPipeline.Camera capturedView;
    private final ArrayDeque<JsonObject> retargetTrace=new ArrayDeque<>();
    private Map<CubeKey,int[]> captured=Map.of();
    private dev.madeinabyss.kernel.concurrent.FrozenCubeTable<int[]> capturedTable=new dev.madeinabyss.kernel.concurrent.FrozenCubeTable<>(Map.of());
    private List<TerrainLodRefinement.Band> bands=List.of(new TerrainLodRefinement.Band(384,1),
            new TerrainLodRefinement.Band(768,2),new TerrainLodRefinement.Band(1280,4),new TerrainLodRefinement.Band(1920,8));
    private AbyssLodPipeline.Limits limits=AbyssLodPipeline.Limits.defaults();
    private record Part(AbyssLodPipeline.Light light,List<DhApiRenderableBox> boxes,int fineBoxes,int backgroundBoxes,
                        AbyssLodGroupLeases.Payload<TerrainLodColumns.Box> payload) {}
    private static final class Scene {
        final AbyssLodPipeline.Proposal proposal;
        final ArrayDeque<Part> remaining=new ArrayDeque<>();
        final List<IDhApiRenderableBoxGroup> buckets=new ArrayList<>();
        final List<AbyssLodGroupLeases<TerrainLodColumns.Box,IDhApiRenderableBoxGroup>.Lease> leases=new ArrayList<>();
        final Map<IDhApiRenderableBoxGroup,AbyssLodGroupLeases<TerrainLodColumns.Box,IDhApiRenderableBoxGroup>.Lease> leaseByGroup=new IdentityHashMap<>();
        final Set<AbyssLodGroupLeases.Payload<TerrainLodColumns.Box>> payloads;
        final Set<IDhApiRenderableBoxGroup> drawn=Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<IDhApiRenderableBoxGroup> registered=Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<IDhApiRenderableBoxGroup,Integer> fine=new IdentityHashMap<>();
        final Map<IDhApiRenderableBoxGroup,Integer> background=new IdentityHashMap<>();
        final Map<TerrainLodColumns.Box,Integer> shadowSky;
        final SurfaceQuadPlan.Prepared surface;
        final SurfaceQuadPlan.PageSet surfacePages;
        final JsonArray fineProof;
        final int fineBoxes,finestStep;
        final long proofBuilds,proofHits,proofEvictions;
        final int proofEntries;
        final Map<Cell,List<AbyssLodFineLedger.Grid>> fineIndex=new HashMap<>();
        private record Cell(long x,long y,long z){}
        boolean registrationComplete,closed,worldAcknowledged;
        Scene(AbyssLodPipeline.Proposal proposal,RawGridProofCache proofs,Set<AbyssLodGroupLeases.Payload<TerrainLodColumns.Box>> reusable,boolean meshMode) {
            this.proposal=proposal;for(var g:proposal.fine()){if(Thread.currentThread().isInterrupted())throw new CancellationException();var v=g.volume();for(long x=Math.floorDiv(v.minX(),16);x<=Math.floorDiv(v.maxXExclusive()-1,16);x++)for(long y=Math.floorDiv(v.minY(),64);y<=Math.floorDiv(v.maxYExclusive()-1,64);y++)for(long z=Math.floorDiv(v.minZ(),16);z<=Math.floorDiv(v.maxZExclusive()-1,16);z++){if(fineIndex.size()>262144)throw new IllegalArgumentException("Fine proof index cap exceeded");fineIndex.computeIfAbsent(new Cell(x,y,z),k->new ArrayList<>()).add(g);}}
            surface=meshMode?proposal.surfacePlan():null;surfacePages=meshMode?proposal.surfacePages():null;
            if(meshMode&&surface==null&&surfacePages==null)throw new IllegalArgumentException("Surface-mode proposal must own prepared faces or pages");
            Map<TerrainLodColumns.Box,Integer> shadow=meshMode?Map.of():new HashMap<>();int count=0,finest=16;
            for(var b:proposal.boxes()){if(Thread.currentThread().isInterrupted())throw new CancellationException();if(!meshMode)shadow.put(b.box(),b.light().sky());if(isFine(b.box()))count++;}
            var parts=meshMode?List.<AbyssLodGroupLeases.Payload<TerrainLodColumns.Box>>of():AbyssLodGroupPartition.parts(proposal.boxes());payloads=Set.copyOf(parts);
            for(var payload:parts){boolean reuse=reusable.contains(payload);var list=reuse?null:new ArrayList<DhApiRenderableBox>();int n=0,background=0;
                for(var b:payload.boxes()){if(Thread.currentThread().isInterrupted())throw new CancellationException();if(list!=null)list.add(renderBox(b));if(isFine(b))n++;if(!inFineGrid(b,false))background++;}
                remaining.add(new Part(new AbyssLodPipeline.Light(payload.sky(),payload.block()),list==null?null:List.copyOf(list),n,background,payload));}
            fineBoxes=count;fineProof=new JsonArray();for(var grid:proposal.fine()){finest=Math.min(finest,grid.step());var proof=new JsonObject();var b=grid.bounds();var a=new JsonArray();
                for(int v:new int[]{b.minX(),b.minY(),b.minZ(),b.maxXInclusive(),b.maxYInclusive(),b.maxZInclusive()})a.add(v);
                proof.add("bounds",a);proof.addProperty("step",grid.step());proof.addProperty("sourceIdentity",grid.sourceIdentity());
                proof.addProperty("hash",proofs.proof(grid));proof.addProperty("hashType","SHA256_EXACT_RAW_GRID_PROVENANCE");fineProof.add(proof);}
            finestStep=finest;shadowSky=Map.copyOf(shadow);proofBuilds=proofs.digestBuilds();proofHits=proofs.hits();proofEvictions=proofs.evictions();proofEntries=proofs.residentEntries();
        }
        private boolean isFine(TerrainLodColumns.Box b){return inFineGrid(b,true);}
        private boolean inFineGrid(TerrainLodColumns.Box b,boolean finestOnly){for(var g:fineIndex.getOrDefault(new Cell(Math.floorDiv(b.minX(),16),Math.floorDiv(b.minY(),64),Math.floorDiv(b.minZ(),16)),List.of())){var v=g.volume();if((!finestOnly||g.step()<=2)&&b.minX()>=v.minX()&&b.maxXExclusive()<=v.maxXExclusive()&&b.minY()>=v.minY()&&b.maxYExclusive()<=v.maxYExclusive()&&b.minZ()>=v.minZ()&&b.maxZExclusive()<=v.maxZExclusive())return true;}return false;}
    }
    @Override public JsonObject begin(Minecraft client,JsonObject parameters) {
        if(!initialized||closed||active)throw new IllegalStateException("DH bridge not available for begin");
        if(client.level==null||client.player==null||!ClientDeepRuntime.generatedAccepted(client.level))throw new IllegalStateException("Persistent generated admission required");
        var admission=ClientDeepRuntime.status();var profile=admission.getAsJsonObject("generatedProfile");
        sessionToken=UUID.fromString(admission.get("session").getAsString());session=sessionToken.toString();fingerprint=profile.get("fingerprint").getAsString();
        if(!ClientGeneratedLight.admittedProfile(client.level,sessionToken,profile.get("profileId").getAsString(),fingerprint))throw new IllegalStateException("Current profile authority missing");
        var a=parameters.getAsJsonArray("bounds");if(a==null||a.size()!=6)throw new IllegalArgumentException("Explicit inclusive XYZ bounds required");
        bounds=new TerrainLodColumns.Bounds(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt(),a.get(3).getAsInt(),a.get(4).getAsInt(),a.get(5).getAsInt());
        long samples=parameters.has("maxSamples")?parameters.get("maxSamples").getAsLong():120_000_000;int boxes=parameters.has("maxBoxes")?parameters.get("maxBoxes").getAsInt():400_000;
        if(samples>120_000_000||boxes>400_000)throw new IllegalArgumentException("Aggregate scene cap exceeded");
        limits=new AbyssLodPipeline.Limits(samples,boxes,Math.multiplyExact(boxes,4),96L*1024*1024,262144,8);
        requested=parameters.deepCopy();level=client.level;active=true;++lifetime;error="";phase="SOURCE_PENDING";
        dev.madeinabyss.forge.probe.client.compat.lightgpu.ClientGpuLight.prepare(client);
        beginNanos=System.nanoTime();startOriginWallMillis=System.currentTimeMillis();firstFineWorldDrawMillis=firstFullBackgroundWorldDrawMillis=-1;
        pipeline=new AbyssLodPipeline(surfaceMesh,scalarCacheDirectory(client));rawProofs=new RawGridProofCache(32768);groupLeases=new AbyssLodGroupLeases<>(1024);conversion=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"mia-abyss-lod-transport");t.setDaemon(true);t.setPriority(AbyssLodPipeline.BACKGROUND_PRIORITY+1);return t;});
        if(surfaceMesh)GeneratedSurfaceMeshRenderer.configure(this::valid,beginNanos,AbyssLodPhysicalLighting.maxSurfaceGpuBytes(ClientGeneratedLight.source()));
        acquireGeneration();acquireNearCoverage();installCuller();
        if(!resourceListenerBound&&client.getResourceManager() instanceof ReloadableResourceManager resources){resources.registerReloadListener((ResourceManagerReloadListener)manager->{ClientGeneratedLight.verifyPhysicalResources();resourceGeneration++;});resourceListenerBound=true;}
        tick(client);return status();
    }
    private long resources(){return resourceGeneration*1_000_003+GeneratedShaderLightPresentation.revision()*31+GeneratedShaderMaterialContext.revision();}
    private static java.nio.file.Path scalarCacheDirectory(Minecraft client){
        String explicit=System.getProperty("mia.dh.previewCacheDirectory","");
        if(!explicit.isBlank())return java.nio.file.Path.of(explicit);
        var server=client.getSingleplayerServer();
        return server==null?null:server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve("mia-lod-cache");
    }
    private void requireSession(Minecraft client){if(client.level!=level||client.player==null||!ClientDeepRuntime.matchesGeneratedProfile(level,sessionToken,fingerprint))throw new IllegalStateException("Generated session ended");
        if(ClientGeneratedLight.physicalResourcesRefused())pipeline.close();
        if(ClientDeepRuntime.observationFailed()||ClientDeepVisual.observationFailed()||ClientGeneratedLight.physicalResourcesRefused())throw new IllegalStateException("Current source observation refused: "+ClientGeneratedLight.physicalResourceError());}
    private boolean valid(AbyssLodPipeline.Target target){return active&&latest!=null&&target.equals(latest.target())&&level==Minecraft.getInstance().level&&ClientGeneratedLight.geometryRevision()==target.sourceRevision()
            &&resources()==target.resourceRevision()&&!ClientGeneratedLight.geometryObservationsPending()&&!ClientDeepRuntime.observationFailed()&&!ClientDeepVisual.observationFailed()&&ClientGeneratedLight.source()==capturedSource
            &&nativeRevision()==target.nativeRevision();}
    private static long nativeRevision(){var c=DeepCubeRenderer.nativeCoverage();return c==null||!c.complete()?-1:c.revision();}
    private AbyssLodPipeline.Input captureInput(Minecraft client){
        var source=ClientGeneratedLight.source();if(source==null||source.cachedGeometryIdentity()==null||ClientGeneratedLight.geometryObservationsPending())return null;
        var visual=ClientDeepVisual.status();if(!visual.has("completed")||!visual.get("completed").getAsBoolean()||visual.has("error"))return null;
        var physical=DeepCubeDomain.space(level).orElseThrow();long gr=ClientGeneratedLight.geometryRevision(),vr=ClientDeepVisual.revision(),pr=physical.contentGeneration();
        if(source!=capturedSource||gr!=capturedGeometry||pr!=capturedPhysical||vr!=capturedVisual){var map=new HashMap<CubeKey,int[]>();captureSpace(ClientDeepVisual.space(level).orElseThrow(),map);captureSpace(physical,map);
            captured=Map.copyOf(map);capturedTable=new dev.madeinabyss.kernel.concurrent.FrozenCubeTable<>(captured);capturedSource=source;capturedGeometry=gr;capturedPhysical=pr;capturedVisual=vr;
            capturedAuthority=TerrainLodDiskCache.identity("abyss-authoritative-source-v1",fingerprint,source.cachedGeometryIdentity());}
        var mainCamera=client.gameRenderer.getMainCamera();var look=mainCamera.getLookVector();var position=mainCamera.getPosition();
        // Before the first frame there may be no current camera entity. Never inherit its previous level origin.
        boolean frameCamera=mainCamera.getEntity()==client.player;
        double x=frameCamera?position.x:client.player.getX(),y=frameCamera?position.y:client.player.getY(),z=frameCamera?position.z:client.player.getZ();
        var camera=new AbyssLodPipeline.Camera(x,y,z,look.x(),look.y(),look.z(),client.options.fov().get(),(double)client.getWindow().getWidth()/Math.max(1,client.getWindow().getHeight()));capturedView=camera;var coverage=DeepCubeRenderer.nativeCoverage();var cuts=coverageExclusions(coverage);
        long res=resources(),nr=nativeRevision();var density=ClientGeneratedLight.nativeDensity(capturedAuthority,gr,nr,id->material(Block.stateById(id)));
        boolean sameRender=latest!=null&&latest.target().sourceIdentity().equals(capturedAuthority)&&latest.target().sourceRevision()==gr
                &&latest.target().nativeRevision()==nr&&latest.target().resourceRevision()==res&&sameView(latest.camera(),camera)
                &&latest.nativeCuts().equals(cuts);
        if(sameRender){
            if(latest.nativeDensity()==density)return latest;
            // Completed density enriches the exact source cache. No real coverage, source,
            // resource or quantized view changed, so it cannot retire an in-flight WORLD scene.
            return new AbyssLodPipeline.Input(latest.target(),latest.camera(),latest.nearBounds(),latest.worldBounds(),latest.bands(),
                    latest.nativeCuts(),latest.terrain(),latest.lighting(),latest.limits(),density);
        }
        lastRetargetReason=latest==null?"INITIAL":!latest.target().sourceIdentity().equals(capturedAuthority)||latest.target().sourceRevision()!=gr?"SOURCE":latest.target().nativeRevision()!=nr?"NATIVE":latest.target().resourceRevision()!=res?"RESOURCE":latest.nativeDensity()!=density?"DENSITY":"VIEW";
        var trace=new JsonObject();trace.addProperty("request",requestRevision+1);trace.addProperty("reason",lastRetargetReason);trace.add("camera",cameraJson(camera));
        if(latest!=null)trace.add("previous",cameraJson(latest.camera()));var player=new JsonArray();player.add(client.player.getX());player.add(client.player.getY());player.add(client.player.getZ());trace.add("player",player);
        trace.addProperty("yaw",client.player.getYRot());trace.addProperty("pitch",client.player.getXRot());if(retargetTrace.size()==8)retargetTrace.removeFirst();retargetTrace.addLast(trace);
        var target=new AbyssLodPipeline.Target(session,lifetime,capturedAuthority,gr,nr,res,++requestRevision);final var cubes=capturedTable;
        var densityAuthority=new AbyssNativeDensity.Authority(capturedAuthority,gr,ClientGeneratedLight.geometryEpoch(),nr);
        dev.madeinabyss.kernel.terrain.trail.TrailNetwork.SampleSource terrain=(px,py,pz)->{int bx=Math.toIntExact(px),by=Math.toIntExact(py),bz=Math.toIntExact(pz);
            // Saved/physical mirrors win, including a complete saved AIR cube.
            var states=cubes.get(bx>>4,by>>4,bz>>4);int id;
            if(states!=null)id=states[(Math.floorMod(by,16)<<8)|(Math.floorMod(bz,16)<<4)|Math.floorMod(bx,16)];
            else{if(density!=null){var nativeMaterial=density.materialOrNull(densityAuthority,bx,by,bz);if(nativeMaterial!=null){nativeDensityHits.increment();return dev.madeinabyss.kernel.lod.LodTerrainSamples.known(nativeMaterial);}nativeDensityMisses.increment();}
                return dev.madeinabyss.kernel.lod.LodTerrainSamples.known(source.materialForDerivedGeometry(bx,by,bz));}
            return dev.madeinabyss.kernel.lod.LodTerrainSamples.known(LodBlockStateMaterials.materialById(id));};
        var near=new TerrainLodColumns.Bounds(Math.max(bounds.minX(),floor(x)-160),Math.max(bounds.minY(),floor(y)-160),Math.max(bounds.minZ(),floor(z)-160),
                Math.min(bounds.maxXInclusive(),floor(x)+159),Math.min(bounds.maxYInclusive(),floor(y)+159),Math.min(bounds.maxZInclusive(),floor(z)+159));
        // Exact32 rays, independent of geometry tile approximation; cache scope stays one immutable source.
        AbyssLodPipeline.BoxLighting light=new AbyssLodPhysicalLighting(source,fingerprint);
        return new AbyssLodPipeline.Input(target,camera,near,bounds,bands,cuts,terrain,light,limits,density);
    }
    private static int floor(double p){double f=Math.floor(p);if(f<Integer.MIN_VALUE+640||f>Integer.MAX_VALUE-640)throw new IllegalArgumentException("Camera outside supported signed envelope");return(int)f;}
    private static boolean sameView(AbyssLodPipeline.Camera a,AbyssLodPipeline.Camera b){return Math.floor(a.x()/16)==Math.floor(b.x()/16)&&Math.floor(a.y()/16)==Math.floor(b.y()/16)&&Math.floor(a.z()/16)==Math.floor(b.z()/16)
            &&a.forwardX()*b.forwardX()+a.forwardY()*b.forwardY()+a.forwardZ()*b.forwardZ()>.996&&a.fov()==b.fov()&&Math.abs(a.aspect()-b.aspect())<1e-9;}
    private static JsonArray cameraJson(AbyssLodPipeline.Camera camera){var a=new JsonArray();for(double v:new double[]{camera.x(),camera.y(),camera.z(),camera.forwardX(),camera.forwardY(),camera.forwardZ()})a.add(v);return a;}
    private static void captureSpace(SparseCubeSpace space,Map<CubeKey,int[]> map){for(var key:space.residentKeys()){map.put(key,space.snapshot(key).orElseThrow().states().clone());if(map.size()>CUBE_CAP)throw new IllegalArgumentException("Immutable mirror capture cap exceeded");}}
    private static List<TerrainLodClipper.BoundsLong> coverageExclusions(DeepCubeRenderer.NativeCoverage c){if(c==null||!c.complete())return List.of();double r=Math.max(0,c.radius()/Math.sqrt(2)-2);
        long x0=(long)Math.ceil(c.anchorX()-r),x1=(long)Math.floor(c.anchorX()+r),z0=(long)Math.ceil(c.anchorZ()-r),z1=(long)Math.floor(c.anchorZ()+r);
        return x0<x1&&z0<z1&&c.minY()<c.maxY()?List.of(new TerrainLodClipper.BoundsLong(x0,c.minY(),z0,x1,c.maxY(),z1)):List.of();}
    @Override public void tick(Minecraft client){
        int timing=dev.madeinabyss.forge.probe.client.GeneratedFrameCosts.begin(dev.madeinabyss.forge.probe.client.GeneratedFrameCosts.Stage.DH_TICK);
        try{tickMeasured(client);}finally{dev.madeinabyss.forge.probe.client.GeneratedFrameCosts.end(timing);}
    }
    private void tickMeasured(Minecraft client){if(!active)return;try{requireSession(client);GeneratedShaderLightPresentation.prepareFrame();var input=captureInput(client);
        if(input!=null&&input!=latest){
            if(latest!=null&&input.target().equals(latest.target())){pipeline.updateNativeCache(input);latest=input;nativeCacheUpdates++;}
            else{latest=input;currentRequestedWorldDrawMillis=-1;pipeline.request(input);retireGroup(transport.request(input.target(),++epoch));prepared=null;
                if(preparing!=null)preparing.cancel(true);conversion.getQueue().clear();preparing=null;phase="PREPARING_SURFACES";}
        }
        if(preparing!=null&&preparing.isDone()){var done=preparing;preparing=null;try{var scene=done.get();if(valid(scene.proposal.target()))prepared=scene;}catch(ExecutionException failed){candidateFailed(String.valueOf(failed.getCause()));}catch(CancellationException canceled){} }
        if(preparing==null&&prepared==null&&transport.staged()==null){var proposal=pipeline.poll();if(proposal.isPresent()&&valid(proposal.get().target())&&(transport.displayed()==null||transport.displayed().proposal!=proposal.get())){var p=proposal.get();var proofs=rawProofs;var displayed=transport.displayed();
            var reusable=displayed!=null&&groupScope(displayed.proposal.target()).equals(groupScope(p.target()))?displayed.payloads:Set.<AbyssLodGroupLeases.Payload<TerrainLodColumns.Box>>of();
            preparing=conversion.submit(()->new Scene(p,proofs,reusable,surfaceMesh));}}
        pipeline.servicePendingExact();
        var state=pipeline.status();if(!String.valueOf(state.getOrDefault("error","")).isEmpty())candidateFailed(String.valueOf(state.get("error")));
        if(surfaceMesh){var meshStatus=GeneratedSurfaceMeshRenderer.status();if(meshStatus.has("error")&&!meshStatus.get("error").getAsString().isEmpty())candidateFailed(meshStatus.get("error").getAsString());}
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();candidateFailed(interrupted.toString());}catch(RuntimeException failed){if(client.level!=level||!ClientDeepRuntime.matchesGeneratedProfile(level,sessionToken,fingerprint))end("generated session ended");else candidateFailed(failed.toString());}}
    private void candidateFailed(String reason){error=reason;phase=transport.displayed()==null?"REFUSED":"RETAINED_CANDIDATE_REFUSED";retireGroup(transport.request(latest==null?new AbyssLodPipeline.Target(session,lifetime,"refused",0,0,0,++requestRevision):latest.target(),++epoch));}
    private void renderSetup(DhApiRenderParam input){
        int timing=dev.madeinabyss.forge.probe.client.GeneratedFrameCosts.begin(dev.madeinabyss.forge.probe.client.GeneratedFrameCosts.Stage.DH_SETUP);
        try{renderSetupMeasured(input);}finally{dev.madeinabyss.forge.probe.client.GeneratedFrameCosts.end(timing);}
    }
    private void renderSetupMeasured(DhApiRenderParam input){scopeActive=false;if(!active||input==null||input.clientLevelWrapper==null||input.clientLevelWrapper.getWrappedMcObject()!=level)return;
        try{requireSession(Minecraft.getInstance());if(isShadowPass())return;GeneratedShaderLightPresentation.prepareFrame();
            if(generation.getValue()!=EDhApiGeneratorPlan.DISABLED||DhApi.Delayed.configs.graphics().disableFrustumCulling().getValue()||DhApi.Delayed.configs.graphics().disableShadowFrustumCulling().getValue())throw new IllegalStateException("DH public rendering gates changed");
            wrapper=input.clientLevelWrapper;register=wrapper.getRenderRegister();if(register==null&&!surfaceMesh)return;
            var staged=transport.staged();if(staged!=null&&!valid(staged.proposal.target()))retireGroup(transport.request(latest.target(),++epoch));
            if(surfaceMesh){
                if(prepared!=null&&transport.staged()==null){var next=prepared;prepared=null;if(valid(next.proposal.target())){
                    retireGroup(transport.request(next.proposal.target(),next.proposal.sequence()));epoch=next.proposal.sequence();
                    transport.stage(next.proposal.target(),next.proposal.sequence(),next);maximumOwnedScenes=Math.max(maximumOwnedScenes,transport.size());
                    if(next.surfacePages!=null)GeneratedSurfaceMeshRenderer.offerPages(next.proposal.target(),next.proposal.sequence(),next.surfacePages,this::surfaceDrawn);
                    else GeneratedSurfaceMeshRenderer.offer(next.proposal.target(),next.proposal.sequence(),next.surface,this::surfaceDrawn);
                    phase="UPLOADING_SURFACE_MESH";
                }}
                checkOwnedCuller();return;
            }
            if(transport.ready()){var next=transport.staged();if(valid(next.proposal.target())){for(var group:next.buckets)group.setOriginBlockPos(new DhApiVec3d(0,0,0));retireGroup(transport.promote());handoffCount++;phase="REGISTERED";GeneratedFarShadowRenderer.acceptGeometry(next.proposal.shadowBoxes(),next.shadowSky);}}
            if(prepared!=null&&transport.staged()==null){var next=prepared;prepared=null;if(valid(next.proposal.target())){retireGroup(transport.request(next.proposal.target(),next.proposal.sequence()));epoch=next.proposal.sequence();transport.stage(next.proposal.target(),next.proposal.sequence(),next);maximumOwnedScenes=Math.max(maximumOwnedScenes,transport.size());phase="UPLOADING_REPLACEMENT";}}
            pumpRegistration();checkOwnedCuller();
            if(handoffCount>0&&transport.displayed()==null)missingDisplayedRefreshFrames++;
        }catch(RuntimeException failed){candidateFailed(failed.toString());}}
    private void checkOwnedCuller(){if(ownCuller!=null){if(DhApi.overrides.get(IDhApiCullingFrustum.class)!=ownCuller||DhApi.overrides.get(IDhApiShadowCullingFrustum.class)!=ownShadowCuller)throw new IllegalStateException("Foreign DH override superseded owned culler");scopeActive=true;}}
    /** A completed upload is not a draw. Only the actual non-shadow main passes promote a mesh. */
    private void surfaceDrawn(GeneratedSurfaceMeshRenderer.DrawAck ack){
        var scene=transport.staged();if(scene==null||!valid(ack.target())||!scene.proposal.target().equals(ack.target())||scene.proposal.sequence()!=ack.sequence())return;
        int faces=Math.addExact(ack.opaqueFaces(),ack.waterFaces());if(faces<=0)return;
        transport.uploaded(scene.proposal.sequence(),scene);retireGroup(transport.promote());handoffCount++;phase="REGISTERED_SURFACE_MESH";
        renderCallbacks++;
        if(transport.drawn(scene.proposal.target(),scene.proposal.sequence(),scene,AbyssLodTransport.DrawPass.WORLD,faces)&&pipeline.acknowledge(scene.proposal,true)){
            scene.worldAcknowledged=true;double time=ack.worldDrawMillis();
            acknowledgeNativeCuts(scene);
            if(scene.fineBoxes>0&&firstFineWorldDrawMillis<0)firstFineWorldDrawMillis=time;
            if(firstFullBackgroundWorldDrawMillis<0&&scene.proposal.fullBackgroundReady())firstFullBackgroundWorldDrawMillis=time;
            if(scene.proposal.requestedComplete()&&currentRequestedWorldDrawMillis<0)currentRequestedWorldDrawMillis=time;
        }
    }
    private boolean isShadowPass(){try{return shaderShadowState!=null&&Boolean.TRUE.equals(shaderShadowState.invoke(null));}catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot query shader pass",failure);}}
    private static AbyssLodGroupLeases.Scope groupScope(AbyssLodPipeline.Target target){return new AbyssLodGroupLeases.Scope(target.session(),target.lifetime(),target.sourceIdentity(),target.sourceRevision(),target.resourceRevision());}
    private void pumpRegistration(){var scene=transport.staged();if(scene==null||scene.closed||scene.registrationComplete)return;long start=System.nanoTime();try{
        while(!scene.remaining.isEmpty()){var part=scene.remaining.removeFirst();var light=part.light();
            var lease=groupLeases.acquire(groupScope(scene.proposal.target()),part.payload(),()->{
                // Reusable payloads skipped conversion on the worker. Their displayed lease stays
                // alive until promotion; this bounded fallback is only for a lost owner invariant.
                var boxes=part.boxes();if(boxes==null){var rebuilt=new ArrayList<DhApiRenderableBox>();for(var b:part.payload().boxes())rebuilt.add(renderBox(b));boxes=List.copyOf(rebuilt);}
                return DhApi.Delayed.customRenderObjectFactory.createAbsolutePositionedGroup("madeinabyss:surface_"+lifetime+"_"+scene.proposal.sequence()+"_"+scene.buckets.size(),boxes);
            },this::disposePhysicalGroup);
            scene.leases.add(lease);var group=lease.value();scene.buckets.add(group);scene.leaseByGroup.put(group,lease);scene.fine.put(group,part.fineBoxes());scene.background.put(group,part.backgroundBoxes());
            if(!groupRegistrations.containsKey(group)){
                if(!(group instanceof Closeable))throw new IllegalStateException("Public DH group must be Closeable");groupCreated++;newGroupBoxes+=part.payload().boxes().size();
                group.setSsaoEnabled(false);group.setSkyLight(GeneratedShaderLightPresentation.presentSky(light.sky()));group.setBlockLight(light.block());
                group.setPreRenderFunc(param->{preRenderCallbacks++;group.setSkyLight(GeneratedShaderLightPresentation.presentSky(light.sky()));
                    var displayed=transport.displayed();var staged=transport.staged();
                    if((displayed==null||!displayed.leaseByGroup.containsKey(group))&&staged!=null&&staged.leaseByGroup.containsKey(group)){
                        double far=Float.isFinite(param.farClipPlane)?Math.max(0,param.farClipPlane):0;double y=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
                        group.setOriginBlockPos(new DhApiVec3d(0,y+Math.max(1_048_576,4*far)-bounds.minY(),0));
                    }else group.setOriginBlockPos(new DhApiVec3d(0,0,0));});
                group.setPostRenderFunc(param->groupDrawn(group));
                register.add(group);groupRegistrations.put(group,register);
            }else{reusedGroups++;reusedBoxes+=part.payload().boxes().size();}
            scene.registered.add(group);if(lease.warm())scene.drawn.add(group);
            if(System.nanoTime()-start>=UPLOAD_NANOS)break;}
        if(scene.remaining.isEmpty()){scene.registrationComplete=true;if(scene.drawn.size()==scene.buckets.size())transport.uploaded(scene.proposal.sequence(),scene);}
        maximumOwnedGroups=Math.max(maximumOwnedGroups,ownedGroupCount());
    }finally{registrationBatches++;registrationMaxNanos=Math.max(registrationMaxNanos,System.nanoTime()-start);}}
    private void groupDrawn(IDhApiRenderableBoxGroup group){renderCallbacks++;if(isShadowPass())return;
        var displayed=transport.displayed();var staged=transport.staged();boolean inDisplayed=displayed!=null&&displayed.leaseByGroup.containsKey(group);
        if(staged!=null&&staged.leaseByGroup.containsKey(group)&&valid(staged.proposal.target())){
            if(!inDisplayed)hiddenWarmupDraws++;var lease=staged.leaseByGroup.get(group);if(!lease.warm())uploadedBoxes+=group.size();lease.markWarm();staged.drawn.add(group);
            if(staged.registrationComplete&&staged.drawn.size()==staged.buckets.size())transport.uploaded(staged.proposal.sequence(),staged);
        }
        if(!inDisplayed||!valid(displayed.proposal.target()))return;
        double time=elapsed();var stage=displayed.proposal.stage();
        if(firstFullBackgroundWorldDrawMillis<0&&displayed.proposal.fullBackgroundReady()
            &&displayed.background.getOrDefault(group,0)>0)firstFullBackgroundWorldDrawMillis=time;
        if(displayed.worldAcknowledged)return;
        int fine=displayed.fine.getOrDefault(group,0),positive=displayed.proposal.stage()==AbyssLodPipeline.Stage.NEAR_FINE?fine:group.size();
        if(transport.drawn(displayed.proposal.target(),displayed.proposal.sequence(),displayed,AbyssLodTransport.DrawPass.WORLD,positive)&&pipeline.acknowledge(displayed.proposal,true)){
            displayed.worldAcknowledged=true;if(fine>0&&firstFineWorldDrawMillis<0)firstFineWorldDrawMillis=time;
            acknowledgeNativeCuts(displayed);
            if(displayed.proposal.requestedComplete()&&currentRequestedWorldDrawMillis<0)currentRequestedWorldDrawMillis=time;
        }
    }
    private double elapsed(){return beginNanos==0?-1:(System.nanoTime()-beginNanos)/1_000_000.0;}
    private void acknowledgeNativeCuts(Scene scene){
        var coverage=DeepCubeRenderer.nativeCoverage();
        if(coverage!=null&&scene.proposal.target().nativeRevision()==coverage.revision())
            ClientGeneratedLight.lodCutWorldAck(DeepCubeRenderer.nativeTargetGeneration(),coverage.revision(),DeepCubeRenderer.cutsCoverNative(latest.nativeCuts()));
    }
    private boolean ownsBoxGroup(long id){for(var scene:new Scene[]{transport.displayed(),transport.staged()})if(scene!=null)for(var group:scene.buckets)if(group.getId()==id)return true;return false;}
    private int ownedGroupCount(){return groupLeases==null?0:groupLeases.size();}
    private void retireGroup(Scene scene){if(scene==null||scene.closed)return;scene.closed=true;scene.remaining.clear();RuntimeException first=null;
        for(var lease:scene.leases)try{lease.close();}catch(RuntimeException failed){first=failed;}scene.leases.clear();scene.leaseByGroup.clear();scene.buckets.clear();scene.drawn.clear();scene.registered.clear();if(first!=null)throw first;}
    private void disposePhysicalGroup(IDhApiRenderableBoxGroup group){RuntimeException first=null;
        try{group.setActive(false);var ownedRegister=groupRegistrations.remove(group);if(ownedRegister!=null&&ownedRegister.remove(group.getId())!=group)throw new IllegalStateException("Owned DH registration changed");}
        catch(Exception failed){first=new IllegalStateException("Owned group removal failed",failed);}finally{try{((Closeable)group).close();groupClosed++;}catch(Exception failed){first=new IllegalStateException("Owned group close failed",failed);}}if(first!=null)throw first;}
    @Override public void end(String reason){active=false;scopeActive=false;++lifetime;++epoch;if(pipeline!=null)pipeline.close();pipeline=null;if(preparing!=null)preparing.cancel(true);preparing=null;prepared=null;
        if(conversion!=null)conversion.shutdownNow();conversion=null;rawProofs=null;for(var scene:transport.clear())try{retireGroup(scene);}catch(RuntimeException failed){error=failed.toString();}groupLeases=null;
        if(ownCuller!=null){DhApi.overrides.unbind(IDhApiCullingFrustum.class,ownCuller);ownCuller=null;delegate=null;}if(ownShadowCuller!=null){DhApi.overrides.unbind(IDhApiShadowCullingFrustum.class,ownShadowCuller);ownShadowCuller=null;shadowDelegate=null;}
        if(surfaceMesh)GeneratedSurfaceMeshRenderer.clear();GeneratedFarShadowRenderer.clear();restoreNearCoverage();restoreGeneration();latest=null;captured=Map.of();capturedTable=new dev.madeinabyss.kernel.concurrent.FrozenCubeTable<>(Map.of());capturedSource=null;wrapper=null;register=null;level=null;phase="ENDED: "+reason;}
    @Override public void close(){if(closed)return;end("adapter close");closed=true;}
    @Override public void shutdownBackground(){closed=true;if(pipeline!=null)pipeline.close();if(conversion!=null)conversion.shutdownNow();dev.madeinabyss.forge.probe.client.compat.lightgpu.ClientGpuLight.shutdown();}
    private void fail(Exception failure){error=failure.toString();phase="REFUSED";}
    @Override public JsonObject status(){return status(new JsonObject());}
    @Override public JsonObject status(JsonObject parameters){var out=new JsonObject();var p=pipeline==null?Map.<String,Object>of():pipeline.status();var scene=transport.displayed();boolean current=scene!=null&&valid(scene.proposal.target());boolean complete=current&&scene.worldAcknowledged&&scene.proposal.requestedComplete();
        boolean exact=complete&&scene.proposal.exactLightComplete();
        var cpu=new JsonObject();for(var entry:p.entrySet()){var value=entry.getValue();if(value instanceof Number n)cpu.addProperty(entry.getKey(),n);else if(value instanceof Boolean b)cpu.addProperty(entry.getKey(),b);else if(value instanceof String s)cpu.addProperty(entry.getKey(),s);
            else if(value instanceof Map<?,?> counters){var nested=new JsonObject();for(var counter:counters.entrySet()){if(!(counter.getKey() instanceof String key))continue;
                if(counter.getValue() instanceof Number n)nested.addProperty(key,n);else if(counter.getValue() instanceof Boolean b)nested.addProperty(key,b);else if(counter.getValue() instanceof String s)nested.addProperty(key,s);}
                cpu.add(entry.getKey(),nested);}}
        out.add("cpu",cpu);
        out.add("gpuLight",new com.google.gson.Gson().toJsonTree(dev.madeinabyss.forge.probe.client.compat.lightgpu.ClientGpuLight.status()));
        out.addProperty("pipeline","ABYSS_LOD_REWRITE");out.addProperty("initialized",initialized);out.addProperty("active",active);out.addProperty("error",error);out.addProperty("phase",phase);out.addProperty("completed",complete);out.addProperty("requestedComplete",complete);
        out.addProperty("geometryComplete",complete);out.addProperty("exactLightComplete",exact);out.addProperty("completionScope",complete&&!exact?"REQUESTED_GEOMETRY_PROVISIONAL_LIGHT":"EXACT_GEOMETRY_AND_LIGHT");
        out.addProperty("geometryBackend",surfaceMesh?"EXPOSED_SURFACE_QUADS":"PUBLIC_DH_BOXES");if(surfaceMesh)out.add("surfaceMesh",GeneratedSurfaceMeshRenderer.status());
        out.addProperty("session",session);out.addProperty("startOriginWallMillis",startOriginWallMillis);out.addProperty("sourceAuthorityKey",latest==null?"":latest.target().sourceIdentity());out.addProperty("sourceRevision",latest==null?-1:latest.target().sourceRevision());
        out.addProperty("targetRevision",latest==null?-1:latest.target().requestRevision());out.addProperty("displayedTargetRevision",scene==null?-1:scene.proposal.target().requestRevision());out.addProperty("displayedTargetValid",current);
        out.addProperty("nativeCoverageRevision",latest==null?-1:latest.target().nativeRevision());out.addProperty("resourceRevision",latest==null?-1:latest.target().resourceRevision());out.addProperty("workerPending",Boolean.TRUE.equals(p.get("workerPending"))||preparing!=null);out.addProperty("proposalPending",Boolean.TRUE.equals(p.get("proposalPending"))||prepared!=null);
        out.addProperty("registrationPending",transport.staged()!=null);out.addProperty("uploadingScenes",transport.staged()==null?0:1);out.addProperty("ownedScenes",transport.size());out.addProperty("maximumOwnedScenes",maximumOwnedScenes);out.addProperty("maxOwnedScenes",maximumOwnedScenes);out.addProperty("ownedGroups",ownedGroupCount());out.addProperty("maximumOwnedGroups",maximumOwnedGroups);
        out.addProperty("firstFineWorldDrawMillis",firstFineWorldDrawMillis);double full=number(p.get("fullBackgroundStartedMillis"),-1);if(full>=0&&pipeline!=null)full+=(pipeline.timingOriginNanos()-beginNanos)/1_000_000.0;out.addProperty("fullBackgroundStartedMillis",full);out.addProperty("firstFullBackgroundWorldDrawMillis",firstFullBackgroundWorldDrawMillis);
        long displayedSequence=scene==null?-1:scene.proposal.sequence();boolean includeFine=!parameters.has("fineAfterSequence")||parameters.get("fineAfterSequence").getAsLong()!=displayedSequence;
        out.addProperty("displayedSequence",displayedSequence);out.addProperty("fineProofIncluded",includeFine);out.addProperty("displayedFineGridCount",scene==null?0:scene.fineProof.size());
        out.addProperty("rawProofBuilds",scene==null?0:scene.proofBuilds);out.addProperty("rawProofHits",scene==null?0:scene.proofHits);out.addProperty("rawProofEvictions",scene==null?0:scene.proofEvictions);out.addProperty("rawProofEntries",scene==null?0:scene.proofEntries);
        out.addProperty("firstFineWorldDrawEvidence","MAIN_PASS_POSITIVE_FINE_SUBMISSION_NOT_PIXEL_VISIBILITY");if(includeFine)out.add("displayedFineGrids",scene==null?new JsonArray():scene.fineProof.deepCopy());out.addProperty("displayedFineBoxes",scene==null?0:scene.fineBoxes);out.addProperty("actualFinestStep",scene==null?0:scene.finestStep);
        if(capturedView!=null)out.add("capturedCamera",cameraJson(capturedView));out.addProperty("lastRetargetReason",lastRetargetReason);var trace=new JsonArray();retargetTrace.forEach(row->trace.add(row.deepCopy()));out.add("retargetTrace",trace);
        out.addProperty("displayedQualityStage",complete?(exact?"REQUESTED":"REQUESTED_PROVISIONAL_LIGHT"):scene==null?"NONE":scene.proposal.stage().name());out.addProperty("refreshInFlight",active&&!exact);out.addProperty("refinementPending",active&&!exact);out.addProperty("handoffCount",handoffCount);out.addProperty("missingDisplayedRefreshFrames",missingDisplayedRefreshFrames);
        out.addProperty("sourceBoxes",scene==null?0:scene.proposal.boxes().size());out.addProperty("sampledVoxels",scene==null?0:scene.proposal.chargedSamples());out.addProperty("farStep",scene==null?0:scene.proposal.farStep());out.addProperty("capturedCubes",captured.size());out.addProperty("boundedScope",true);out.addProperty("fullWorldCoverage",false);out.add("requestedBounds",requested.has("bounds")?requested.get("bounds").deepCopy():new JsonArray());
        out.addProperty("oceanHorizonRequested",requested.has("oceanHorizonRadius"));out.addProperty("oceanHorizonIncluded",false);
        out.addProperty("firstVisibleDetailDisplayMillis",firstFineWorldDrawMillis);out.addProperty("firstRequestedDisplayMillis",complete?currentRequestedWorldDrawMillis:-1);out.addProperty("registrationBatches",registrationBatches);out.addProperty("registrationMaxNanos",registrationMaxNanos);out.addProperty("hiddenWarmupDraws",hiddenWarmupDraws);out.addProperty("groupCreated",groupCreated);out.addProperty("groupClosed",groupClosed);
        out.addProperty("newGroupBoxes",newGroupBoxes);out.addProperty("uploadedBoxes",uploadedBoxes);out.addProperty("reusedGroups",reusedGroups);out.addProperty("reusedBoxes",reusedBoxes);
        out.addProperty("nativeDensityGeometryHits",nativeDensityHits.sum());out.addProperty("nativeDensityGeometryMisses",nativeDensityMisses.sum());out.addProperty("nativeDensityPublicationRevision",latest==null||latest.nativeDensity()==null?-1:latest.nativeDensity().publicationRevision());
        out.addProperty("nativeCacheOnlyUpdates",nativeCacheUpdates);
        return out;}
    private static double number(Object value,double fallback){return value instanceof Number n?n.doubleValue():fallback;}    private static final TerrainMaterial[] KNOWN_MATERIALS=TerrainMaterial.values();

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
            DhApi.events.bind(DhApiBeforeRenderPassEvent.class,new DhApiBeforeRenderPassEvent(){
                @Override public void beforeRender(DhApiEventParam<DhApiRenderParam> input){
                    int timing=dev.madeinabyss.forge.probe.client.GeneratedFrameCosts.begin(dev.madeinabyss.forge.probe.client.GeneratedFrameCosts.Stage.DH_BEFORE_RENDER);
                    try{if(surfaceMesh&&active&&scopeActive&&input.value.clientLevelWrapper!=null&&input.value.clientLevelWrapper.getWrappedMcObject()==level&&!isShadowPass())GeneratedSurfaceMeshRenderer.draw(input.value);}
                    finally{dev.madeinabyss.forge.probe.client.GeneratedFrameCosts.end(timing);}
                }
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
    private void skipUnsupportedShadowPass(com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam<DhApiRenderParam> input) {
        if(!active || shaderShadowState==null || input.value.clientLevelWrapper==null
                || input.value.clientLevelWrapper.getWrappedMcObject()!=level)return;
        try {
            if(Boolean.TRUE.equals(shaderShadowState.invoke(null))){shaderShadowPassesSkipped++;input.cancelEvent();}
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot query shader shadow pass",failure);}
    }
    private static DhApiRenderableBox renderBox(TerrainLodColumns.Box box) {
        Color color=switch(box.material()) {case ROCK->new Color(126,123,119);case SOIL->new Color(116,83,55);case GRASS->new Color(104,138,65);
            case WATER->new Color(53,94,163,180);case AIR->throw new IllegalArgumentException("Air must not emit boxes");};
        EDhApiBlockMaterial material=switch(box.material()){case ROCK->EDhApiBlockMaterial.STONE;case SOIL->EDhApiBlockMaterial.DIRT;
            case GRASS->EDhApiBlockMaterial.GRASS;case WATER->EDhApiBlockMaterial.WATER;case AIR->EDhApiBlockMaterial.AIR;};
        return new DhApiRenderableBox(new DhApiVec3d(box.minX(),box.minY(),box.minZ()),
                new DhApiVec3d(box.maxXExclusive(),box.maxYExclusive(),box.maxZExclusive()),color,material);
    }
    private static TerrainMaterial material(BlockState state) {
        return LodBlockStateMaterials.material(state);
    }

}



