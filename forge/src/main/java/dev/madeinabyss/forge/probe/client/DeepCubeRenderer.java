package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.worldgen.IslandSurfaceBlockStates;
import dev.madeinabyss.forge.cube.CubeBlockLightAccess;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.GeneratedNativeWindow;
import dev.madeinabyss.forge.probe.deep.GeneratedNativeMeshPrefilter;
import dev.madeinabyss.kernel.cube.NativeFirstViewPlan;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Bounded native-model diagnostic; opt-in scalar BLOCK light does not establish SKY, AO or shaders. */
@Mod.EventBusSubscriber(modid = MadeInAbyssProbe.MOD_ID, value = Dist.CLIENT)
public final class DeepCubeRenderer {
    private static final int RADIUS = 24;
    private static final int RANGE_DOWN = 88, RANGE_UP = 48; // v15.4: DH draws beyond; halves the native light window
    private static final int MAX_FACES = 1_048_576;
    // Foliage must never consume the water pass's entire tessellation budget.
    private static final int MAX_FLUID_FACES = dev.madeinabyss.kernel.cube.NativeFluidBudget.MAX_FACES;
    private static final int AIR_STATE_ID = Block.getId(Blocks.AIR.defaultBlockState());
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final RenderType SOLID = RenderType.solid();
    private static final List<RenderType> MODEL_LAYERS = List.of(SOLID, RenderType.cutoutMipped(), RenderType.cutout());
    private static final GeneratedBatchedDraw DRAW_BATCH=new GeneratedBatchedDraw();
    private static final double MESH_REUSE_MARGIN = 2;
    private static final MultiBufferSource.BufferSource BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(262144));

    private record Face(BakedQuad quad, float red, float green, float blue, int light, RenderType layer) {}
    private record ModelBlock(int x, int y, int z, net.minecraft.world.level.block.state.BlockState state, List<Face> faces) {}
    private record MeshCube(AABB bounds, List<ModelBlock> blocks) {}
    private record GpuCube(AABB bounds,RenderType layer,VertexBuffer buffer,int blocks,int faces,int minY,int maxY,int minBlockLight,int maxBlockLight,long bytes) {}
    private static List<GpuCube> gpu=List.of();
    private static long gpuUploads,gpuCloses,gpuDraws,gpuBytes,meshPresentation=-1,meshMaterial=-1;
    private static int requestedRadius=24,meshRadius=24,meshDown=24,meshUp=24;
    private static String meshError="";
    private static long meshRebuildMillis,meshGeometryEpoch=-1;
    private static double maxBuiltHorizontalDistance;
    private static int cachedFacesBeyond24;
    public record NativeCoverage(double anchorX,double anchorY,double anchorZ,int radius,int minY,int maxY,long revision,boolean complete) {}
    private static final long FRAME_BUDGET_NANOS=GeneratedFrameCosts.OWNER_BUDGET_NANOS,MAX_TOTAL_GPU_BYTES=256L*1024*1024;
    private static NativeTask pendingNative;
    private static final java.util.concurrent.ThreadPoolExecutor MESH_WORKER=new java.util.concurrent.ThreadPoolExecutor(1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"mia-native-mesh-prefilter");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY);return t;});
    private static long prefilterFallbacks,lastPrefilterNanos,lastPrefilterCandidates;
    private static long maxBuildStepNanos,maxUploadStepNanos,lastBuildStepNanos,lastUploadStepNanos,stepOverruns,reusedModelCubes,reusedGpuBuffers,maxNativeStageNanos;
    private static java.util.Set<CubeKey> builtNativeKeys=java.util.Set.of();
    private static NativeCoverage coverage;
    private static GeneratedNativeWindow meshWindow;
    private static java.util.UUID timingSession;
    private record DrawStamp(double millis,long epoch,long coverageRevision) { }
    private static DrawStamp nearSolidDraw,nearFluidDraw,fullSolidDraw,fullFluidDraw;
    private static long meshTargetGeneration=-1;
    private static JsonObject currentNativeWorldDraw;
    public static NativeCoverage nativeCoverage(){return coverage;}
    public static long nativeTargetGeneration(){return meshTargetGeneration;}
    static boolean nearDraw(){return meshWindow!=null&&meshWindow.stage()==NativeFirstViewPlan.Stage.NEAR;}
    public static boolean cutsCoverNative(java.util.List<dev.madeinabyss.kernel.lod.TerrainLodClipper.BoundsLong> cuts){
        if(meshWindow==null||meshWindow.stage()!=NativeFirstViewPlan.Stage.FULL||coverage==null||!coverage.complete())return false;
        if(!dev.madeinabyss.kernel.cube.NativeCutCoverage.covers(coverage.anchorX(),coverage.anchorZ(),coverage.radius(),coverage.minY(),coverage.maxY(),cuts))return false;
        for(var cube:mesh)for(var block:cube.blocks()){
            boolean covered=false;for(var c:cuts)if(block.x()>=c.minX()&&block.x()+1<=c.maxXExclusive()&&block.y()>=c.minY()&&block.y()+1<=c.maxYExclusive()&&block.z()>=c.minZ()&&block.z()+1<=c.maxZExclusive()){covered=true;break;}
            if(!covered)return false;
        }
        return DeepFluidRenderer.cutsCoverWater(cuts);
    }

    private static List<MeshCube> mesh = List.of();
    private static WeakReference<ClientLevel> meshLevel = new WeakReference<>(null);
    private static WeakReference<SparseCubeSpace> meshPhysical = new WeakReference<>(null);
    private static WeakReference<SparseCubeSpace> meshVisual = new WeakReference<>(null);
    private static BakedModel meshModel;
    private static long meshRevision = Long.MIN_VALUE, meshRebuilds;
    private static long meshPhysicalGeneration = Long.MIN_VALUE, meshVisualGeneration = Long.MIN_VALUE;
    private static long meshLightRevision = Long.MIN_VALUE;
    private static int meshFaces;
    private static int meshOutsideDrawVolume, meshCutoutFaces;
    private static double meshCameraX, meshCameraY, meshCameraZ;
    private static boolean meshCapped;
    private static boolean blockLightMode;
    private static int unknownLightFaces;
    private static String lightError = "";
    private static int minSubmittedBlockLight, maxSubmittedBlockLight;

    private static long frames, renderedFrames;
    private static int drawnBlocks, drawnFaces, visitedCubes, visualCubes;
    private static int rangeDown = RADIUS, rangeUp = RADIUS;
    private static boolean usingVisual;
    private static double cameraX, cameraY, cameraZ;
    private static double submittedMinY = Double.NaN, submittedMaxY = Double.NaN;
    private static WeakReference<ClientLevel> lastLevel = new WeakReference<>(null);

    private DeepCubeRenderer() {}
    static int nativeFrameState(){return (meshWindow==null?0:meshWindow.stage()==NativeFirstViewPlan.Stage.NEAR?1:2)
            |(pendingNative==null?0:4)|(pendingNative!=null&&pendingNative.phase.equals("UPLOAD")?8:0);}
    static void resetNativeMeshMetrics(){maxBuildStepNanos=maxUploadStepNanos=lastBuildStepNanos=lastUploadStepNanos=stepOverruns=maxNativeStageNanos=0;prefilterFallbacks=lastPrefilterNanos=lastPrefilterCandidates=0;}
    @SubscribeEvent
    public static void onDisconnect(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        Minecraft.getInstance().execute(DeepCubeRenderer::clearMesh);
    }
    public static void blockLightMode(boolean enabled) {
        var level = Minecraft.getInstance().level;
        if (enabled && (level == null || DeepCubeDomain.lookup(level).isEmpty()))
            throw new IllegalStateException("Block-light render requires an attached diagnostic domain");
        if (enabled && !DeepCubeDomain.blockLightEnabled(level))
            throw new UnsupportedOperationException("Block-light queries are not enabled for this fixture");
        if (blockLightMode != enabled) { blockLightMode = enabled; clearMesh(); }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.NATIVE_EVENT);
        try{onRenderLevelMeasured(event);}finally{GeneratedFrameCosts.end(timing);}
    }
    private static void onRenderLevelMeasured(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            if(Boolean.getBoolean("mia.dh.surfaceMesh"))GeneratedSurfaceMeshRenderer.drawShadow(event);
            else GeneratedFarShadowRenderer.draw(event);
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            var client = Minecraft.getInstance();
            if (client.level != null && meshLevel.get() == client.level && !DeepFluidRenderer.inputsChanged(client.level))
                DeepFluidRenderer.draw(event, client.level, meshRadius, rangeDown, rangeUp, usingVisual);
            else DeepFluidRenderer.resetFrame();
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        DeepFluidRenderer.resetFrame();
        frames++;
        drawnBlocks = drawnFaces = visitedCubes = visualCubes = 0;
        minSubmittedBlockLight = 15; maxSubmittedBlockLight = 0;
        submittedMinY = submittedMaxY = Double.NaN;
        usingVisual = false;
        var client = Minecraft.getInstance();
        if (!Boolean.getBoolean("mia.cube.playerProbe") || client.level == null) {
            blockLightMode = false;
            clearMesh();
            return;
        }
        var physical = DeepCubeDomain.space(client.level);
        if (physical.isEmpty()) {
            blockLightMode = false;
            clearMesh();
            return;
        }
        var camera = event.getCamera().getPosition();
        lastLevel = new WeakReference<>(client.level);
        cameraX = camera.x; cameraY = camera.y; cameraZ = camera.z;
        boolean generated=ClientDeepRuntime.generatedAccepted(client.level);
        GeneratedShaderMaterialContext.prepareFrame();
        GeneratedShaderLightPresentation.prepareFrame();
        if(generated)ClientGeneratedLight.update(client.level,cameraX,cameraY,cameraZ);
        requestedRadius=generated?Math.min(client.options.renderDistance().get(),dev.madeinabyss.kernel.cube.NativeRenderDistance.MAX_CHUNKS)*16:RADIUS;
        // Accepted generated previews own their surface too; ordinary fixtures keep native routing.
        if (!Double.isFinite(camera.x) || !Double.isFinite(camera.y) || !Double.isFinite(camera.z)
                || (camera.y >= client.level.getMinBuildHeight() && !ClientDeepRuntime.generatedAccepted(client.level))) return;
        var visual = ClientDeepVisual.space(client.level);
        if (blockLightMode && !DeepCubeDomain.blockLightEnabled(client.level)) {
            blockLightMode = false;
            clearMesh();
        }
        usingVisual = visual.isPresent();
        visualCubes = visual.map(SparseCubeSpace::loadedCubeCount).orElse(0);
        rangeDown = generated||usingVisual ? RANGE_DOWN : RADIUS;
        rangeUp = generated||usingVisual ? RANGE_UP : RADIUS;
        long revision = usingVisual ? ClientDeepVisual.revision() : -1;
        // Models change on resource reload. Old baked quads must not retain stale atlas UVs.
        var model = client.getBlockRenderer().getBlockModel(Blocks.STONE.defaultBlockState());
        CubeBlockLightAccess renderLight = null;
        long lightRevision = -1;
        if (generated) lightRevision=ClientGeneratedLight.revision();
        else if (blockLightMode) {
            try {
                renderLight = visual.isPresent() ? ClientDeepVisual.light(client.level,physical.get())
                        : DeepCubeDomain.lookup(client.level).orElseThrow().blockLight();
                lightRevision = renderLight.revision(client.level);
            } catch (RuntimeException unavailable) {
                // A newer invalid visual observation cannot leave the last lit mesh on screen.
                clearMesh(); unknownLightFaces = 0; lightError = unavailable.toString();
                return;
            }
        }
        if(generated&&meshLevel.get()!=null&&!dev.madeinabyss.kernel.cube.NativeRenderDistance.retain(
                ClientGeneratedLight.source()!=null,meshGeometryEpoch,ClientGeneratedLight.geometryEpoch(),
                meshLevel.get()==client.level&&meshModel==model&&!DeepFluidRenderer.resourcesChanged()
                        &&meshPresentation==GeneratedShaderLightPresentation.revision()&&meshMaterial==GeneratedShaderMaterialContext.revision())) {
            // Retention is only valid for the same authoritative geometry and atlas. A failed
            // observation or reload restores DH coverage instead of displaying stale native data.
            clearMesh();
        }
        var availableSnapshot=generated?ClientGeneratedLight.nativeSnapshot():null;
        boolean cameraCovered=generated&&ClientGeneratedLight.nativeReady(cameraX,cameraY,cameraZ,requestedRadius);
        boolean ready=!generated||availableSnapshot!=null&&availableSnapshot.buildAllowed()&&availableSnapshot.readyFor(requestedRadius);
        if(generated) {
            if(pendingNative!=null&&!pendingNative.valid(client,model))cancelNative();
            boolean needs=meshLevel.get()!=client.level||meshModel!=model||meshLightRevision!=lightRevision
                    ||availableSnapshot!=null&&meshWindow!=availableSnapshot.window()||meshPresentation!=GeneratedShaderLightPresentation.revision()
                    ||meshMaterial!=GeneratedShaderMaterialContext.revision()
                    ||(cameraCovered&&(Math.abs(cameraY-meshCameraY)>=MESH_REUSE_MARGIN||Math.hypot(cameraX-meshCameraX,cameraZ-meshCameraZ)>=8));
            if(!GeneratedFarShadowRenderer.isShadowPass()) {
                int ownerTiming=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.NATIVE_OWNER);
                try {
                long stageStart=System.nanoTime();var frameBudget=new dev.madeinabyss.kernel.concurrent.FrameWorkBudget(System::nanoTime,FRAME_BUDGET_NANOS);long initNanos=0;
                if(pendingNative==null&&ready&&needs) {
                    var snapshot=availableSnapshot;
                    if(snapshot!=null){boolean near=snapshot.stage()==NativeFirstViewPlan.Stage.NEAR;
                        try {pendingNative=new NativeTask(client,model,snapshot,snapshot.radius(),near?32:rangeDown,near?31:rangeUp,
                                near?snapshot.drawBounds().minX()+24:cameraCovered?cameraX:snapshot.anchorX(),
                                near?snapshot.drawBounds().minY()+24:cameraCovered?cameraY:snapshot.anchorY(),
                                near?snapshot.drawBounds().minZ()+24:cameraCovered?cameraZ:snapshot.anchorZ());}
                        catch(RuntimeException refused){meshError=refused.toString();ClientGeneratedLight.nativeSceneRefused(snapshot,meshError);}
                        initNanos=System.nanoTime()-stageStart;}
                }
                if(pendingNative!=null&&!ClientGeneratedLight.geometryObservationsPending())advanceNative(client,frameBudget,initNanos);
                maxNativeStageNanos=Math.max(maxNativeStageNanos,System.nanoTime()-stageStart);
                }finally{GeneratedFrameCosts.end(ownerTiming);}
            }
        } else {
            if(meshLevel.get()!=client.level||meshPhysical.get()!=physical.get()||meshVisual.get()!=visual.orElse(null)||meshRevision!=revision||meshModel!=model)
                rebuild(client,physical.get(),visual.orElse(null),revision,model,renderLight);
            else if(meshPhysicalGeneration!=physical.get().contentGeneration()||meshVisualGeneration!=visual.map(SparseCubeSpace::contentGeneration).orElse(-1L)
                    ||meshLightRevision!=lightRevision||DeepFluidRenderer.inputsChanged(client.level)
                    ||Math.abs(cameraY-meshCameraY)>=MESH_REUSE_MARGIN||Math.hypot(cameraX-meshCameraX,cameraZ-meshCameraZ)>=MESH_REUSE_MARGIN)
                rebuild(client,physical.get(),visual.orElse(null),revision,model,renderLight);
            meshLightRevision=lightRevision;
        }

        double minY = Math.max(DeepCubeDomain.TECHNICAL_BOTTOM, camera.y - rangeDown);
        double maxY = Math.min(DeepCubeDomain.managedTop(client.level), camera.y + rangeUp);
        if(generated) {if(coverage!=null)drawGpu(event,coverage.minY(),coverage.maxY());return;}
        var poses = event.getPoseStack();
        int blocks = 0, faces = 0;
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        poses.pushPose();
        try {
            if (usingVisual) {
                // This bounded diagnostic model pass sees beyond vanilla render-distance fog.
                RenderSystem.setShaderFogStart(Math.max(fogStart, RANGE_DOWN));
                RenderSystem.setShaderFogEnd(Math.max(fogEnd, RANGE_DOWN + RANGE_UP));
            }
            for (RenderType layer : MODEL_LAYERS) {
              var consumer = BUFFERS.getBuffer(layer);
              for (MeshCube cube : mesh) {
                if (layer == SOLID) visitedCubes++;
                if (cube.bounds().maxY < minY || cube.bounds().minY > maxY
                        || cube.bounds().maxX < camera.x - meshRadius || cube.bounds().minX > camera.x + meshRadius
                        || cube.bounds().maxZ < camera.z - meshRadius || cube.bounds().minZ > camera.z + meshRadius
                        || !event.getFrustum().isVisible(cube.bounds())) continue;
                for (ModelBlock block : cube.blocks()) {
                    if ((double) block.y() + 1 < minY || block.y() > maxY) continue;
                    double dx = block.x() + .5 - camera.x, dz = block.z() + .5 - camera.z;
                    if (dx * dx + dz * dz > meshRadius * meshRadius) continue;
                    poses.pushPose();
                    boolean materialContext = generated && GeneratedShaderMaterialContext.begin(
                            consumer, block.state(), block.x(), block.y(), block.z());
                    try {
                        // Native event pose carries view rotation; model vertices keep global XYZ minus camera.
                        poses.translate(block.x() - camera.x, block.y() - camera.y, block.z() - camera.z);
                        boolean submitted=false;
                        for (Face face : block.faces()) {
                            if(face.layer()!=layer)continue;
                            consumer.putBulkData(poses.last(), face.quad(), face.red(), face.green(), face.blue(),
                                    generated ? GeneratedShaderLightPresentation.presentPacked(face.light()) : face.light(), OverlayTexture.NO_OVERLAY);
                            int blockLight = (face.light() >>> 4) & 15;
                            minSubmittedBlockLight = Math.min(minSubmittedBlockLight, blockLight);
                            maxSubmittedBlockLight = Math.max(maxSubmittedBlockLight, blockLight);
                            faces++;
                            submitted=true;
                        }
                        if (submitted) {
                            // Count each native model once, even when it spans more than one layer.
                            if (block.faces().get(0).layer() == layer) blocks++;
                            lowest = Math.min(lowest, block.y()); highest = Math.max(highest, block.y());
                        }
                    } finally {
                        if (materialContext) GeneratedShaderMaterialContext.end(consumer);
                        poses.popPose();
                    }
                }
              }
              BUFFERS.endBatch(layer);
            }
        } finally {
            try {
                BUFFERS.endBatch();
                drawnBlocks = blocks; drawnFaces = faces; renderedFrames++;
                if (blocks > 0) { submittedMinY = lowest; submittedMaxY = highest; }
            } finally {
                try {
                    if (usingVisual) {
                        RenderSystem.setShaderFogStart(fogStart);
                        RenderSystem.setShaderFogEnd(fogEnd);
                    }
                } finally { poses.popPose(); }
            }
        }
    }

    /** Rebuilds bounded resident models on publication/model changes or exhaustion of the movement margin. */
    private static void rebuild(Minecraft client, SparseCubeSpace physical, SparseCubeSpace visual,
                                long revision, BakedModel model, CubeBlockLightAccess renderLight) {
        long buildStarted=System.nanoTime();
        dev.madeinabyss.kernel.light.CubeBlockLight.Result visualLight = null;
        if (blockLightMode && visual != null && !ClientDeepRuntime.generatedAccepted(client.level)) {
            try { visualLight = renderLight.visualSnapshot(client.level); }
            catch (RuntimeException unavailable) {
                clearMesh(); unknownLightFaces = 0; lightError = unavailable.toString();
                return;
            }
        }
        var keys = new HashSet<CubeKey>();
        if (visual != null) keys.addAll(ClientDeepVisual.keys(client.level));
        // Include the whole nearby physical window. This also preserves old corridor rendering without visual data.
        int cx = Math.floorDiv(boundedFloor(cameraX), 16), cy = Math.floorDiv(boundedFloor(cameraY), 16);
        int cz = Math.floorDiv(boundedFloor(cameraZ), 16);
        for (int y = cy - 2; y <= cy + 2; y++) for (int z = cz - 2; z <= cz + 2; z++)
            for (int x = cx - 2; x <= cx + 2; x++) {
                var key = new CubeKey(x, y, z);
                if (physical.isLoaded(key)) keys.add(key);
            }
        boolean generated=ClientDeepRuntime.generatedAccepted(client.level);
        int buildRadius=generated?requestedRadius:RADIUS;
        double reuseMargin=generated?8:MESH_REUSE_MARGIN;
        var snapshots = new HashMap<CubeKey, int[]>();
        if(generated)snapshots.putAll(ClientGeneratedLight.nativeCubes());
        if(!generated)for (CubeKey key : keys) {
            // Near authoritative physics wins for every cell and neighbor in the same cube.
            var snapshot = physical.snapshot(key);
            if (snapshot.isEmpty() && visual != null) snapshot = visual.snapshot(key);
            snapshot.ifPresent(value -> snapshots.put(key, value.states()));
        }
        var ordered = new ArrayList<>(snapshots.keySet());
        // A pathological scene hits the cap near the camera, never because the lowest Y happened to scan first.
        ordered.sort(Comparator.comparingDouble(DeepCubeRenderer::cubeDistance));
        var cubes = new ArrayList<MeshCube>();
        var random = RandomSource.create();
        unknownLightFaces = 0; lightError = "";
        meshOutsideDrawVolume=0;meshCutoutFaces=0;
        int totalFaces = 0,beyond24=0;double maxDistance=0;
        boolean capped = false;
        build:
        for (CubeKey key : ordered) {
            int[] states = snapshots.get(key);
            var blocks = new ArrayList<ModelBlock>();
            for (int index = 0; index < states.length; index++) {
                if (states[index] == AIR_STATE_ID) continue;
                long x = (long) key.x() * 16 + (index & 15);
                long y = (long) key.y() * 16 + (index >>> 8);
                long z = (long) key.z() * 16 + ((index >>> 4) & 15);
                if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE
                        || y < DeepCubeDomain.TECHNICAL_BOTTOM || y >= DeepCubeDomain.managedTop(client.level)) continue;
                var state = Block.BLOCK_STATE_REGISTRY.byId(states[index]);
                if (!IslandSurfaceBlockStates.admitsNativeModel(state,ClientDeepRuntime.generatedAccepted(client.level))) continue;
                // Cache only the declared draw volume plus the movement reuse margin. Neighbor
                // availability still comes from every retained authoritative snapshot, not this mesh.
                double dx=x+.5-cameraX,dz=z+.5-cameraZ;
                if(dx*dx+dz*dz>(buildRadius+reuseMargin)*(buildRadius+reuseMargin)
                        || y+1<cameraY-rangeDown-MESH_REUSE_MARGIN || y>cameraY+rangeUp+MESH_REUSE_MARGIN) {
                    meshOutsideDrawVolume++;continue;
                }
                int exposed=0;
                for (Direction direction : DIRECTIONS) {
                    if (!knownExposed(client.level, snapshots, state, direction, x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ())) continue;
                    exposed|=1<<direction.ordinal();
                }
                // Every emitted quad below requires an exposed direction, including uncullable
                // model quads. Buried rock needs no model lookup or per-block mesh allocation.
                if(exposed==0)continue;
                var pos = new BlockPos((int) x, (int) y, (int) z);
                var baked = client.getBlockRenderer().getBlockModel(state);
                var faces = new ArrayList<Face>();
                long seed = state.getSeed(pos);
                random.setSeed(seed);
                for(RenderType layer:baked.getRenderTypes(state,random,ModelData.EMPTY)) {
                    if(!MODEL_LAYERS.contains(layer))continue;
                    for(Direction direction:DIRECTIONS) {
                        if((exposed&(1<<direction.ordinal()))==0)continue;
                        random.setSeed(seed);
                        for(BakedQuad quad:baked.getQuads(state,direction,random,ModelData.EMPTY,layer))
                            addFace(client.level,pos,state,quad,faces,visualLight,layer);
                    }
                    // Crossed flowers and cutout foliage need a known non-occluding neighbor too.
                    random.setSeed(seed);
                    for(BakedQuad quad:baked.getQuads(state,null,random,ModelData.EMPTY,layer))
                        if((exposed&(1<<quad.getDirection().ordinal()))!=0)addFace(client.level,pos,state,quad,faces,visualLight,layer);
                }
                if (faces.isEmpty()) continue;
                if (totalFaces + faces.size() > (generated?MAX_FACES:65536)) {
                    capped = true;
                    if (!blocks.isEmpty()) cubes.add(cubeMesh(key, blocks));
                    break build;
                }
                totalFaces += faces.size();
                if(dx*dx+dz*dz>24*24)beyond24+=faces.size();maxDistance=Math.max(maxDistance,Math.hypot(dx,dz));
                for(Face face:faces)if(face.layer()!=SOLID)meshCutoutFaces++;
                blocks.add(new ModelBlock((int) x, (int) y, (int) z, state, List.copyOf(faces)));
            }
            if (!blocks.isEmpty()) cubes.add(cubeMesh(key, blocks));
        }
        if(generated&&(capped||unknownLightFaces>0)) {meshError="Native candidate refused: cap="+capped+", unknownLightFaces="+unknownLightFaces;return;}
        if(generated) {
            try {uploadGpu(cubes);meshError="";}catch(RuntimeException failed){meshError=failed.toString();return;}
        }
        cachedFacesBeyond24=beyond24;maxBuiltHorizontalDistance=maxDistance;
        mesh = List.copyOf(cubes); meshFaces = totalFaces; meshCapped = capped;meshRadius=buildRadius;meshDown=rangeDown;meshUp=rangeUp;
        meshPresentation=GeneratedShaderLightPresentation.revision();meshMaterial=GeneratedShaderMaterialContext.revision();
        if(generated) {
            meshGeometryEpoch=ClientGeneratedLight.geometryEpoch();
            coverage=new NativeCoverage(cameraX,cameraY,cameraZ,buildRadius,
                    Math.max(ClientGeneratedLight.nativeMinimumY(),(int)Math.ceil(cameraY-rangeDown)),
                    Math.min(ClientGeneratedLight.nativeMaximumY(),(int)Math.floor(cameraY+rangeUp)),meshRebuilds+1,true);
        }
        DeepFluidRenderer.rebuild(client, snapshots, ordered, visualLight, blockLightMode, MAX_FLUID_FACES);
        meshLevel = new WeakReference<>(client.level); meshPhysical = new WeakReference<>(physical);
        meshVisual = new WeakReference<>(visual); meshRevision = revision; meshModel = model; meshRebuilds++;
        meshPhysicalGeneration = physical.contentGeneration();
        meshVisualGeneration = visual == null ? -1 : visual.contentGeneration();
        meshCameraX=cameraX;meshCameraY=cameraY;meshCameraZ=cameraZ;meshRebuildMillis=(System.nanoTime()-buildStarted)/1_000_000;
    }

    private static void addFace(ClientLevel level, BlockPos position,
                                net.minecraft.world.level.block.state.BlockState state,
                                BakedQuad quad, List<Face> faces,
                                dev.madeinabyss.kernel.light.CubeBlockLight.Result visualLight,RenderType layer) {
        addFace(level,position,state,quad,faces,visualLight,layer,null);
    }
    private static void addFace(ClientLevel level,BlockPos position,net.minecraft.world.level.block.state.BlockState state,
                                BakedQuad quad,List<Face> faces,dev.madeinabyss.kernel.light.CubeBlockLight.Result visualLight,
                                RenderType layer,ClientGeneratedLight.NativeSnapshot snapshot) {
        int light = LightTexture.FULL_BRIGHT;
        if(ClientDeepRuntime.generatedAccepted(level)) {
            var target=position.relative(quad.getDirection());
            var packed=snapshot==null?ClientGeneratedLight.packedLight(target.getX(),target.getY(),target.getZ()):snapshot.packed(target.getX(),target.getY(),target.getZ());
            if(packed.isEmpty()){unknownLightFaces++;return;}
            light=packed.getAsInt();
            int emission=state.getLightEmission(level,position);
            light=LightTexture.pack(Math.max(emission,(light>>>4)&15),(light>>>20)&15);
        } else if (blockLightMode) {
            try {
                int neighbor;
                if (visualLight != null) {
                    var target=position.relative(quad.getDirection());
                    var value=visualLight.value(new dev.madeinabyss.kernel.BlockCoordinate(target.getX(),target.getY(),target.getZ()));
                    if(value.isEmpty()) {unknownLightFaces++;return;}
                    neighbor=value.getAsInt();
                } else neighbor=level.getBrightness(LightLayer.BLOCK,position.relative(quad.getDirection()));
                int block = Math.max(state.getLightEmission(level, position),
                        neighbor);
                light = LightTexture.pack(block, 0); // Explicit BLOCK-only diagnostic. SKY is not simulated.
            } catch (CubeBlockLightAccess.UnavailableLightException unavailable) {
                unknownLightFaces++;
                return; // Optional visual data cannot invent confirmed darkness/fullbright.
            } catch (RuntimeException failed) {
                unknownLightFaces++;
                if (lightError.isEmpty()) lightError = failed.toString();
                return;
            }
        }
        float shade=level.getShade(quad.getDirection(),quad.isShade());
        int tint=quad.isTinted()?Minecraft.getInstance().getBlockColors().getColor(state,previewTintView(level),position,quad.getTintIndex()):0xffffff;
        var source=ClientGeneratedLight.source();var recipe=source==null?null:source.livingRecipe();
        if(quad.isTinted()&&recipe!=null&&recipe.biomeColors()!=null&&dev.madeinabyss.forge.worldgen.LivingAbyssColors.inShaft(position.getX(),position.getY(),position.getZ())
            &&(state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock||state.is(net.minecraft.world.level.block.Blocks.VINE)))tint=recipe.biomeColors().foliageRgb();
        faces.add(new Face(quad,shade*((tint>>>16)&255)/255f,shade*((tint>>>8)&255)/255f,shade*(tint&255)/255f,light,layer));
    }
    /** Native block-color callback with an explicit fixed PLAINS diagnostic color biome. */
    private static net.minecraft.world.level.BlockAndTintGetter previewTintView(ClientLevel level) {
        var biome=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value();
        return new net.minecraft.world.level.BlockAndTintGetter() {
            public float getShade(Direction direction,boolean shade){return level.getShade(direction,shade);}
            public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine(){return level.getLightEngine();}
            public int getBlockTint(BlockPos pos,net.minecraft.world.level.ColorResolver resolver){
                var source=ClientGeneratedLight.source();var r=source==null?null:source.livingRecipe();
                var actual=r!=null&&dev.madeinabyss.forge.worldgen.LivingAbyssColors.inShaft(pos.getX(),pos.getY(),pos.getZ())?dev.madeinabyss.forge.worldgen.LivingAbyssColors.biome(biome,r.biomeColors()):biome;
                return resolver.getColor(actual,pos.getX(),pos.getZ());}
            public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos){return level.getBlockState(pos);}
            public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos){return level.getFluidState(pos);}
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos){return level.getBlockEntity(pos);}
            public int getMinBuildHeight(){return level.getMinBuildHeight();}
            public int getHeight(){return level.getHeight();}
        };
    }

    private static MeshCube cubeMesh(CubeKey key, List<ModelBlock> blocks) {
        double x = (long) key.x() * 16, y = (long) key.y() * 16, z = (long) key.z() * 16;
        return new MeshCube(new AABB(x, y, z, x + 16, y + 16, z + 16), List.copyOf(blocks));
    }

    private static double cubeDistance(CubeKey key) {
        double dx = (long) key.x() * 16 + 8 - cameraX;
        double dy = (long) key.y() * 16 + 8 - cameraY;
        double dz = (long) key.z() * 16 + 8 - cameraZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean knownExposed(ClientLevel level, Map<CubeKey, int[]> snapshots,
            net.minecraft.world.level.block.state.BlockState current, Direction direction, long x, long y, long z) {
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) return false;
        int ix = (int) x, iy = (int) y, iz = (int) z;
        if (iy >= level.getMinBuildHeight() && !ClientDeepRuntime.generatedAccepted(level)) {
            // Above the seam, visual generated AIR is not authoritative native terrain.
            var nativeChunk = level.getChunkSource().getChunk(Math.floorDiv(ix, 16), Math.floorDiv(iz, 16), ChunkStatus.FULL, false);
            if(nativeChunk==null)return false;
            var neighbor=nativeChunk.getBlockState(new BlockPos(ix,iy,iz));
            return !current.skipRendering(neighbor,direction) && !neighbor.canOcclude();
        }
        int[] states = snapshots.get(new CubeKey(Math.floorDiv(ix, 16), Math.floorDiv(iy, 16), Math.floorDiv(iz, 16)));
        if (states == null) return false;
        int id = states[Math.floorMod(iy, 16) << 8 | Math.floorMod(iz, 16) << 4 | Math.floorMod(ix, 16)];
        if (id == AIR_STATE_ID) return true;
        var state = Block.BLOCK_STATE_REGISTRY.byId(id);
        return state != null && !current.skipRendering(state,direction) && !state.canOcclude();
    }

    /** Staged generated geometry reads the same immutable window as its face light. */
    private static boolean knownExposed(ClientGeneratedLight.NativeSnapshot light,
            net.minecraft.world.level.block.state.BlockState current,Direction direction,long x,long y,long z) {
        int id=light.neighborState(x,y,z);
        if(id==AIR_STATE_ID)return true;
        var state=Block.BLOCK_STATE_REGISTRY.byId(id);
        if(state==null)throw new IllegalStateException("Unknown staged native neighbor palette state");
        return !current.skipRendering(state,direction)&&!state.canOcclude();
    }

    private static void clearMesh() {
        cancelNative();builtNativeKeys=java.util.Set.of();
        DeepFluidRenderer.clear();
        closeGpu(gpu);gpu=List.of();coverage=null;meshWindow=null;gpuBytes=0;meshGeometryEpoch=-1;
        mesh = List.of(); meshFaces = 0; meshCapped = false; meshModel = null; meshRevision = Long.MIN_VALUE;
        meshOutsideDrawVolume=meshCutoutFaces=0;
        meshPhysicalGeneration = meshVisualGeneration = Long.MIN_VALUE;
        meshLightRevision = Long.MIN_VALUE;
        meshLevel = new WeakReference<>(null); meshPhysical = new WeakReference<>(null); meshVisual = new WeakReference<>(null);
    }

    /** Submission counters only; positive counts require inspection of an actual native framebuffer. */
    public static JsonObject status() {
        var report = new JsonObject();
        syncNativeTiming();
        report.addProperty("nativeDrawStage",meshWindow==null?"NONE":meshWindow.stage().name());
        report.addProperty("nativeDrawEpoch",meshGeometryEpoch);report.addProperty("nativeDrawRevision",coverage==null?-1:coverage.revision());
        report.addProperty("nativeDrawLightRevision",meshLightRevision);
        report.addProperty("nativeDrawTargetGeneration",meshTargetGeneration);
        report.add("nearLatency",ClientGeneratedLight.latencyStatus());
        if(currentNativeWorldDraw!=null)report.add("currentNativeWorldDraw",currentNativeWorldDraw.deepCopy());
        if(meshWindow!=null){report.add("nativeDrawBounds",ClientGeneratedLight.boundsJson(meshWindow.drawBounds()));report.add("nativeSolveBounds",ClientGeneratedLight.boundsJson(meshWindow.solveBounds()));}
        report.addProperty("nativeTimingSession",timingSession==null?"":timingSession.toString());
        report.addProperty("nativeTimingOriginWallMillis",ClientGeneratedLight.nativeSessionOriginWallMillis());
        report.addProperty("firstNearSolidWorldDrawMillis",drawMillis(nearSolidDraw));report.addProperty("firstNearFluidWorldDrawMillis",drawMillis(nearFluidDraw));
        var nearFirst=earlier(nearSolidDraw,nearFluidDraw);var fullFirst=earlier(fullSolidDraw,fullFluidDraw);
        report.addProperty("firstNearWorldDrawMillis",drawMillis(nearFirst));report.addProperty("firstFullWorldDrawMillis",drawMillis(fullFirst));
        report.addProperty("firstNearWorldDrawEpoch",nearFirst==null?-1:nearFirst.epoch());report.addProperty("firstFullWorldDrawEpoch",fullFirst==null?-1:fullFirst.epoch());
        report.addProperty("firstNearWorldDrawCoverageRevision",nearFirst==null?-1:nearFirst.coverageRevision());report.addProperty("firstFullWorldDrawCoverageRevision",fullFirst==null?-1:fullFirst.coverageRevision());
        var level = Minecraft.getInstance().level;
        boolean current = Boolean.getBoolean("mia.cube.playerProbe") && level != null && lastLevel.get() == level
                && DeepCubeDomain.space(level).isPresent() && (cameraY < level.getMinBuildHeight() || ClientDeepRuntime.generatedAccepted(level));
        report.addProperty("enabled", Boolean.getBoolean("mia.cube.playerProbe"));
        report.addProperty("frames", frames); report.addProperty("renderedFrames", renderedFrames);
        report.addProperty("drawnBlocks", current ? drawnBlocks : 0); report.addProperty("drawnFaces", current ? drawnFaces : 0);
        report.addProperty("visitedCubes", current ? visitedCubes : 0); report.addProperty("visualCubes", current ? visualCubes : 0);
        report.addProperty("rangeDown", rangeDown); report.addProperty("rangeUp", rangeUp);
        report.addProperty("visualWindow", current && usingVisual); report.addProperty("visualRevision", meshRevision);
        report.addProperty("meshBuildPending",pendingNative!=null);report.addProperty("meshBuildPhase",pendingNative==null?"IDLE":pendingNative.phase);
        report.addProperty("meshPrefilterActive",MESH_WORKER.getActiveCount());report.addProperty("meshPrefilterQueued",MESH_WORKER.getQueue().size());
        report.addProperty("meshPrefilterMillis",lastPrefilterNanos/1_000_000.0);report.addProperty("meshPrefilterCandidates",lastPrefilterCandidates);report.addProperty("meshPrefilterFallbacks",prefilterFallbacks);
        report.addProperty("meshFrameBudgetMillis",FRAME_BUDGET_NANOS/1_000_000.0);
        report.addProperty("meshBuildStepMillis",lastBuildStepNanos/1_000_000.0);report.addProperty("maxMeshBuildStepMillis",maxBuildStepNanos/1_000_000.0);
        report.addProperty("meshUploadStepMillis",lastUploadStepNanos/1_000_000.0);report.addProperty("maxMeshUploadStepMillis",maxUploadStepNanos/1_000_000.0);
        report.addProperty("maxNativeStageMillis",maxNativeStageNanos/1_000_000.0);report.addProperty("meshStepOverruns",stepOverruns);report.addProperty("pendingGpuBytes",pendingNative==null?0:pendingNative.uniqueBytes);
        report.addProperty("totalNativeGpuBytes",gpuBytes+(pendingNative==null?0:pendingNative.uniqueBytes));
        report.add("nativeMeshStaging",NativeMeshBuffers.status());
        report.addProperty("maxTotalNativeGpuBytes",MAX_TOTAL_GPU_BYTES);report.addProperty("reusedModelCubes",reusedModelCubes);report.addProperty("reusedGpuBuffers",reusedGpuBuffers);
        report.addProperty("requestedNativeChunks",level==null?0:Minecraft.getInstance().options.renderDistance().get());
        report.addProperty("requestedNativeRadius",level==null?0:Minecraft.getInstance().options.renderDistance().get()*16);
        report.addProperty("targetNativeRadius",requestedRadius);report.addProperty("effectiveNativeRadius",meshRadius);
        report.addProperty("maxNativeChunks",dev.madeinabyss.kernel.cube.NativeRenderDistance.MAX_CHUNKS);
        report.addProperty("nativeDistanceCapped",level!=null&&Minecraft.getInstance().options.renderDistance().get()>8);
        report.addProperty("nativeMeshError",meshError);report.addProperty("meshRebuildMillis",meshRebuildMillis);report.addProperty("maxBuiltHorizontalDistance",maxBuiltHorizontalDistance);report.addProperty("cachedFacesBeyond24",cachedFacesBeyond24);report.addProperty("gpuUploads",gpuUploads);report.addProperty("gpuCloses",gpuCloses);report.addProperty("gpuDraws",gpuDraws);report.addProperty("gpuMeshBytes",gpuBytes);report.addProperty("gpuBuffers",gpu.size());
        report.addProperty("nativeCoverageRevision",coverage==null?-1:coverage.revision());
        report.addProperty("maxFaces", MAX_FACES); report.addProperty("cap", current && meshCapped);
        report.addProperty("maxFluidFaces", MAX_FLUID_FACES);
        report.addProperty("cachedFaces", current ? meshFaces : 0); report.addProperty("meshRebuilds", meshRebuilds);
        report.addProperty("cachedCutoutFaces",current?meshCutoutFaces:0);
        report.addProperty("omittedOutsideDrawVolumeBlocks",current?meshOutsideDrawVolume:0);
        report.addProperty("meshReuseMargin",ClientDeepRuntime.generatedAccepted(level)?8:MESH_REUSE_MARGIN);
        report.addProperty("modelLayers","NATIVE_SOLID_CUTOUT_MIPPED_CUTOUT");
        report.addProperty("lightRevision", meshLightRevision);
        if (current && drawnBlocks > 0 && Double.isFinite(submittedMinY)) {
            report.addProperty("submittedMinY", submittedMinY); report.addProperty("submittedMaxY", submittedMaxY);
        }
        report.addProperty("cameraX", cameraX); report.addProperty("cameraY", cameraY); report.addProperty("cameraZ", cameraZ);
        var camera = new JsonObject();
        camera.addProperty("x", cameraX); camera.addProperty("y", cameraY); camera.addProperty("z", cameraZ);
        report.add("camera", camera); report.addProperty("fullBrightDiagnostic", !blockLightMode && !ClientDeepRuntime.generatedAccepted(level));
        report.addProperty("blockLightMode", blockLightMode);
        report.addProperty("skyLightImplemented", ClientDeepRuntime.generatedAccepted(level));
        report.add("generatedLight",ClientGeneratedLight.status());
        report.add("shaderMaterialContext", new com.google.gson.Gson().toJsonTree(GeneratedShaderMaterialContext.status()));
        report.add("shaderLightPresentation", new com.google.gson.Gson().toJsonTree(GeneratedShaderLightPresentation.status()));
        report.add("farShadow",Boolean.getBoolean("mia.dh.surfaceMesh")?GeneratedSurfaceMeshRenderer.status():GeneratedFarShadowRenderer.status());
        report.addProperty("geologicalTint", "FIXED_PLAINS_NATIVE_BLOCK_COLOR_DIAGNOSTIC");
        report.addProperty("unknownLightFaces", current ? unknownLightFaces : 0);
        report.addProperty("lightError", current ? lightError : "");
        DeepFluidRenderer.status(report, current);
        if (current && drawnFaces > 0) {
            report.addProperty("minSubmittedBlockLight", minSubmittedBlockLight);
            report.addProperty("maxSubmittedBlockLight", maxSubmittedBlockLight);
        }
        // Compatibility with old diagnostic scripts; the actual budget now counts submitted model quads.
        report.addProperty("maxBlocksPerFrame", MAX_FACES);
        return report;
    }

    private static final class NativeTask {
        final ClientLevel level;final BakedModel model;final ClientGeneratedLight.NativeSnapshot light;
        final long presentation,material,resources,started=System.nanoTime();final int radius,down,up;
        final double x,y,z;final Map<CubeKey,int[]> snapshots;final List<CubeKey> ordered;
        final List<MeshCube> cubes=new ArrayList<>();final List<GpuCube> uploaded=new ArrayList<>();
        final java.util.Set<CubeKey> completeKeys=new HashSet<>(),reusedKeys=new HashSet<>();
        final Map<CubeKey,MeshCube> oldModels=new HashMap<>();final Map<CubeKey,List<GpuCube>> oldGpu=new HashMap<>();
        final java.util.Set<VertexBuffer> borrowed=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final RandomSource random=RandomSource.create();final DeepFluidRenderer.GeneratedCandidate water;final boolean reuseAllowed;
        String phase="MODELS";int keyIndex,stateIndex,faces,cutouts,outside,beyond24;
        volatile boolean cancelled;volatile long prefilterNanos;int candidateIndex;
        java.util.concurrent.Future<int[]> paletteJob;java.util.concurrent.Future<List<GeneratedNativeMeshPrefilter.Candidates>> prefilterJob;
        GeneratedNativeMeshPrefilter.RuleBuilder paletteRules;List<GeneratedNativeMeshPrefilter.Candidates> prepared;
        List<ModelBlock> currentBlocks=new ArrayList<>();double maxDistance;
        int uploadCube,uploadLayer,uploadBlock,bufferFaces,bufferBlocks,bufferMinY,bufferMaxY,bufferMinLight,bufferMaxLight;
        BufferBuilder builder;long targetBytes,uniqueBytes;
        NativeTask(Minecraft client,BakedModel model,ClientGeneratedLight.NativeSnapshot light,int radius,int down,int up,double x,double y,double z) {
            level=client.level;this.model=model;this.light=light;this.radius=radius;this.down=down;this.up=up;this.x=x;this.y=y;this.z=z;
            presentation=GeneratedShaderLightPresentation.revision();material=GeneratedShaderMaterialContext.revision();resources=DeepFluidRenderer.resourceRevision();
            snapshots=light.cubes();ordered=new ArrayList<>();for(var key:snapshots.keySet())if(light.window().intersectsDraw(key))ordered.add(key);
            ordered.sort(Comparator.comparingDouble(k->{double dx=k.x()*16.0+8-x,dy=k.y()*16.0+8-y,dz=k.z()*16.0+8-z;return dx*dx+dy*dy+dz*dz;}));
            water=new DeepFluidRenderer.GeneratedCandidate(client,snapshots,light);
            // Same immutable window proves every reused state, face packed value and water uv2.
            // Cross-stage/snapshot reuse awaits an explicit overlap comparison.
            reuseAllowed=light.stage()==NativeFirstViewPlan.Stage.FULL&&meshWindow==light.window()
                    &&meshGeometryEpoch==light.epoch()&&meshModel==model&&meshPresentation==presentation&&meshMaterial==material;
            if(reuseAllowed) {
                for(var cube:mesh)oldModels.put(key(cube.bounds()),cube);
                for(var entry:gpu){oldGpu.computeIfAbsent(key(entry.bounds()),k->new ArrayList<>()).add(entry);borrowed.add(entry.buffer());}
            }
            if(light.stage()==NativeFirstViewPlan.Stage.FULL&&!reuseAllowed&&Boolean.parseBoolean(System.getProperty("mia.generated.nativeMeshPrefilter","true"))) {
                try {
                    int paletteSize=Block.BLOCK_STATE_REGISTRY.size();phase="PALETTE_WAIT";
                    ClientGeneratedLight.traceCube(light.targetGeneration(),"MESH_PALETTE_QUEUED",null,light.revision());
                    paletteJob=MESH_WORKER.submit(()->{long start=System.nanoTime();try{return GeneratedNativeMeshPrefilter.discover(snapshots,paletteSize,this::checkpoint);}finally{prefilterNanos+=System.nanoTime()-start;}});
                }catch(java.util.concurrent.RejectedExecutionException busy){phase="MODELS";prefilterFallbacks++;}
            }
        }
        private void checkpoint(){if(cancelled||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Native mesh candidate retired");}
        private static <T> T completed(java.util.concurrent.Future<T> future){
            try{return future.get();}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Native mesh owner interrupted");}
            catch(java.util.concurrent.ExecutionException failed){if(failed.getCause() instanceof RuntimeException r)throw r;if(failed.getCause() instanceof Error e)throw e;throw new IllegalStateException(failed.getCause());}
        }
        /** False means an unfinished private worker; never spin or join it on the owner. */
        boolean prepareStep(){
            if(phase.equals("PALETTE_WAIT")){
                if(!paletteJob.isDone())return false;var ids=completed(paletteJob);paletteJob=null;
                if(ids.length>128){prefilterFallbacks++;phase="MODELS";return true;}
                paletteRules=GeneratedNativeMeshPrefilter.rules(ids,Block.BLOCK_STATE_REGISTRY.size());phase="PALETTE";
                ClientGeneratedLight.traceCube(light.targetGeneration(),"MESH_PALETTE_READY",null,light.revision());return true;
            }
            if(phase.equals("PALETTE")){
                if(paletteRules.step()){
                    var rules=paletteRules.finish();paletteRules=null;var keys=List.copyOf(ordered);
                    ClientGeneratedLight.traceCube(light.targetGeneration(),"MESH_PREFILTER_QUEUED",null,light.revision());
                    try{prefilterJob=MESH_WORKER.submit(()->{
                        long start=System.nanoTime();ClientGeneratedLight.traceCube(light.targetGeneration(),"MESH_PREFILTER_BEGIN",null,light.revision());
                        try{return GeneratedNativeMeshPrefilter.scan(light.window(),keys,rules,radius,down,up,x,y,z,2*MAX_FACES,this::checkpoint);}
                        finally{prefilterNanos+=System.nanoTime()-start;}
                    });phase="PREFILTER_WAIT";}
                    catch(java.util.concurrent.RejectedExecutionException busy){prefilterFallbacks++;phase="MODELS";}
                }return true;
            }
            if(!prefilterJob.isDone())return false;prepared=completed(prefilterJob);prefilterJob=null;
            lastPrefilterNanos=prefilterNanos;lastPrefilterCandidates=0;
            if(prepared==null)prefilterFallbacks++;else for(var cube:prepared)lastPrefilterCandidates+=cube.entries().length;
            ClientGeneratedLight.traceCube(light.targetGeneration(),"MESH_PREFILTER_READY",null,light.revision());phase="MODELS";return true;
        }
        boolean valid(Minecraft client,BakedModel currentModel){return client.level==level&&light.buildAllowed()&&ClientGeneratedLight.source()!=null&&ClientGeneratedLight.geometryEpoch()==light.epoch()
                &&currentModel==model&&GeneratedShaderLightPresentation.revision()==presentation&&GeneratedShaderMaterialContext.revision()==material&&DeepFluidRenderer.resourceRevision()==resources;}
        static CubeKey key(AABB b){return new CubeKey((int)Math.floor(b.minX/16),(int)Math.floor(b.minY/16),(int)Math.floor(b.minZ/16));}
        boolean interior(CubeKey key,double ax,double ay,double az,int r,int d,int u) {
            double x0=key.x()*16.0-2,x1=x0+20,z0=key.z()*16.0-2,z1=z0+20;
            double dx=Math.max(Math.abs(x0-ax),Math.abs(x1-ax)),dz=Math.max(Math.abs(z0-az),Math.abs(z1-az));
            return dx*dx+dz*dz<=(r+8.0)*(r+8.0)&&key.y()*16.0-2>=ay-d-2&&key.y()*16.0+18<=ay+u+2;
        }
        void count(ModelBlock block) {
            faces+=block.faces().size();if(faces>MAX_FACES)throw new IllegalStateException("Staged native face budget exceeded");
            double dx=block.x()+.5-x,dz=block.z()+.5-z;maxDistance=Math.max(maxDistance,Math.hypot(dx,dz));
            if(dx*dx+dz*dz>24*24)beyond24+=block.faces().size();for(var face:block.faces())if(face.layer()!=SOLID)cutouts++;
        }
        private void finishModelCube(CubeKey key){
            if(!currentBlocks.isEmpty())cubes.add(cubeMesh(key,currentBlocks));
            if(light.window().wholeCubeInDraw(key)&&(light.stage()==NativeFirstViewPlan.Stage.NEAR||interior(key,x,y,z,radius,down,up)))completeKeys.add(key);
            ClientGeneratedLight.traceCube(light.targetGeneration(),"MODEL_CUBE_READY",key,light.revision());
            currentBlocks=new ArrayList<>();stateIndex=candidateIndex=0;keyIndex++;
        }
        void modelStep(Minecraft client) {
            if(keyIndex>=ordered.size()){phase="UPLOAD";return;}
            var key=ordered.get(keyIndex);
            if(stateIndex==0&&reuseAllowed&&builtNativeKeys.contains(key)&&light.window().wholeCubeInDraw(key)&&interior(key,x,y,z,radius,down,up)
                    &&interior(key,meshCameraX,meshCameraY,meshCameraZ,meshRadius,meshDown,meshUp)) {
                var old=oldModels.get(key);if(old!=null){cubes.add(old);for(var block:old.blocks())count(block);}
                water.reuseCube(key);reusedKeys.add(key);completeKeys.add(key);keyIndex++;reusedModelCubes++;return;
            }
            if(prepared==null&&stateIndex>=4096){finishModelCube(key);return;}
            if(prepared==null&&stateIndex==0&&light.stage()==NativeFirstViewPlan.Stage.FULL) {
                double x0=key.x()*16.0+.5,x1=x0+15,z0=key.z()*16.0+.5,z1=z0+15;
                double dx=x<x0?x0-x:x>x1?x-x1:0,dz=z<z0?z0-z:z>z1?z-z1:0;
                if(dx*dx+dz*dz>(radius+8.0)*(radius+8.0)||key.y()*16.0+16<y-down-2||key.y()*16.0>y+up+2){keyIndex++;return;}
            }
            int[] states=snapshots.get(key);int index,exposed=-1;
            if(prepared!=null){
                var candidate=prepared.get(keyIndex);if(stateIndex==0)outside+=candidate.outside();
                if(candidate.skipped()){keyIndex++;return;}
                if(candidateIndex>=candidate.entries().length){finishModelCube(key);return;}
                int entry=candidate.entries()[candidateIndex++];index=entry>>>8;stateIndex=index+1;exposed=entry&63;
            }else{
                while(stateIndex<4096&&states[stateIndex]==AIR_STATE_ID)stateIndex++;
                if(stateIndex>=4096)return;index=stateIndex++;
            }
            int id=states[index];
            int bx=key.x()*16+(index&15),by=key.y()*16+(index>>>8),bz=key.z()*16+((index>>>4)&15);
            double dx=bx+.5-x,dz=bz+.5-z;
            if(prepared==null&&!light.allowsBlock(bx,by,bz,x,y,z,down,up)){outside++;return;}
            var state=Block.BLOCK_STATE_REGISTRY.byId(id);if(state==null)throw new IllegalStateException("Unknown staged native state");
            if(!state.getFluidState().isEmpty())water.observe(key,index,state);
            if(prepared==null){
                if(!IslandSurfaceBlockStates.admitsNativeModel(state,true))return;
                exposed=0;for(var direction:DIRECTIONS)if(knownExposed(light,state,direction,(long)bx+direction.getStepX(),(long)by+direction.getStepY(),(long)bz+direction.getStepZ()))exposed|=1<<direction.ordinal();
            }
            if(exposed==0)return;
            var pos=new BlockPos(bx,by,bz);var baked=client.getBlockRenderer().getBlockModel(state);var result=new ArrayList<Face>();long seed=state.getSeed(pos);
            int previousUnknown=unknownLightFaces;
            try {
                random.setSeed(seed);
                for(var layer:baked.getRenderTypes(state,random,ModelData.EMPTY)) {
                    if(!MODEL_LAYERS.contains(layer))continue;
                    for(var direction:DIRECTIONS)if((exposed&(1<<direction.ordinal()))!=0){random.setSeed(seed);for(var quad:baked.getQuads(state,direction,random,ModelData.EMPTY,layer))addFace(level,pos,state,quad,result,null,layer,light);}
                    random.setSeed(seed);for(var quad:baked.getQuads(state,null,random,ModelData.EMPTY,layer))if((exposed&(1<<quad.getDirection().ordinal()))!=0)addFace(level,pos,state,quad,result,null,layer,light);
                }
                if(unknownLightFaces!=previousUnknown)throw new IllegalStateException("Staged native light is unknown");
            }finally {unknownLightFaces=previousUnknown;}
            if(!result.isEmpty()){var block=new ModelBlock(bx,by,bz,state,List.copyOf(result));currentBlocks.add(block);count(block);}
        }
        void uploadStep() {
            if(uploadCube>=cubes.size()){phase="READY";return;}
            var cube=cubes.get(uploadCube);var key=key(cube.bounds());
            if(builder==null&&uploadLayer==0&&reusedKeys.contains(key)) {
                for(var old:oldGpu.getOrDefault(key,List.of())){uploaded.add(old);targetBytes+=old.bytes();reusedGpuBuffers++;}
                uploadCube++;return;
            }
            var layer=MODEL_LAYERS.get(uploadLayer);
            if(builder==null){builder=NativeMeshBuffers.begin(layer.mode(),layer.format());uploadBlock=0;bufferFaces=bufferBlocks=bufferMaxLight=0;bufferMinLight=15;bufferMinY=Integer.MAX_VALUE;bufferMaxY=Integer.MIN_VALUE;}
            if(uploadBlock<cube.blocks().size()) {
                var block=cube.blocks().get(uploadBlock++);var poses=new PoseStack();poses.translate(block.x()-cube.bounds().minX,block.y()-cube.bounds().minY,block.z()-cube.bounds().minZ);
                boolean context=GeneratedShaderMaterialContext.begin(builder,block.state(),block.x(),block.y(),block.z()),submitted=false;
                try {for(var face:block.faces())if(face.layer()==layer){builder.putBulkData(poses.last(),face.quad(),face.red(),face.green(),face.blue(),GeneratedShaderLightPresentation.presentPacked(face.light()),OverlayTexture.NO_OVERLAY);bufferFaces++;submitted=true;bufferMinLight=Math.min(bufferMinLight,(face.light()>>>4)&15);bufferMaxLight=Math.max(bufferMaxLight,(face.light()>>>4)&15);}}
                finally {if(context)GeneratedShaderMaterialContext.end(builder);}
                if(submitted){bufferBlocks++;bufferMinY=Math.min(bufferMinY,block.y());bufferMaxY=Math.max(bufferMaxY,block.y());}return;
            }
            var built=NativeMeshBuffers.end();builder=null;
            if(bufferFaces==0){built.release();NativeMeshBuffers.complete();}
            else {
                long bytes=built.vertexBuffer().remaining();if(gpuBytes+uniqueBytes+bytes>MAX_TOTAL_GPU_BYTES){built.release();NativeMeshBuffers.complete();throw new IllegalStateException("Displayed+pending native GPU budget exceeded256MiB");}
                int vboTiming=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.NATIVE_VBO);
                try {
                var vbo=new VertexBuffer(VertexBuffer.Usage.STATIC);
                try {vbo.bind();try{vbo.upload(built);}finally{NativeMeshBuffers.complete();}}catch(RuntimeException failed){vbo.close();throw failed;}finally {VertexBuffer.unbind();}
                uploaded.add(new GpuCube(cube.bounds(),layer,vbo,bufferBlocks,bufferFaces,bufferMinY,bufferMaxY,bufferMinLight,bufferMaxLight,bytes));gpuUploads++;targetBytes+=bytes;uniqueBytes+=bytes;
                }finally{GeneratedFrameCosts.end(vboTiming);}
            }
            if(++uploadLayer>=MODEL_LAYERS.size()){ClientGeneratedLight.traceCube(light.targetGeneration(),"GPU_CUBE_READY",key,light.revision());uploadLayer=0;uploadCube++;}
        }
        void cancel(){cancelled=true;if(paletteJob!=null)paletteJob.cancel(true);if(prefilterJob!=null)prefilterJob.cancel(true);MESH_WORKER.purge();try{NativeMeshBuffers.cancel();}finally{builder=null;for(var entry:uploaded)if(!borrowed.contains(entry.buffer())){entry.buffer().close();gpuCloses++;}}}
    }
    private static void cancelNative(){if(pendingNative!=null){ClientGeneratedLight.nativeSceneRefused(pendingNative.light,"Near native candidate canceled");try{pendingNative.cancel();}finally{pendingNative=null;}}}
    private static void advanceNative(Minecraft client,dev.madeinabyss.kernel.concurrent.FrameWorkBudget budget,long initNanos) {
        var task=pendingNative;long buildNanos=initNanos,uploadNanos=0;
        try {
            while(budget.available()&&!task.phase.equals("READY")) {
                long start=System.nanoTime();boolean models=!task.phase.equals("UPLOAD"),progressed=true;
                int timing=GeneratedFrameCosts.begin(models?GeneratedFrameCosts.Stage.NATIVE_MODELS:GeneratedFrameCosts.Stage.NATIVE_UPLOAD);
                try{if(models&&!task.phase.equals("MODELS"))progressed=task.prepareStep();else if(models)task.modelStep(client);else task.uploadStep();}
                finally{GeneratedFrameCosts.end(timing);}
                long elapsed=System.nanoTime()-start;if(models)buildNanos+=elapsed;else uploadNanos+=elapsed;
                if(!progressed)break;
            }
            if(task.phase.equals("READY")) {
                int commitTiming=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.NATIVE_COMMIT);
                try {
                if(!task.valid(client,client.getBlockRenderer().getBlockModel(Blocks.STONE.defaultBlockState())))throw new IllegalStateException("Staged native snapshot changed before commit");
                task.water.publish();var old=gpu;gpu=List.copyOf(task.uploaded);gpuBytes=task.targetBytes;
                mesh=List.copyOf(task.cubes);builtNativeKeys=java.util.Collections.unmodifiableSet(task.completeKeys);meshFaces=task.faces;meshCapped=false;
                meshOutsideDrawVolume=task.outside;meshCutoutFaces=task.cutouts;cachedFacesBeyond24=task.beyond24;maxBuiltHorizontalDistance=task.maxDistance;
                meshLevel=new WeakReference<>(task.level);meshModel=task.model;meshGeometryEpoch=task.light.epoch();meshLightRevision=task.light.revision();meshRadius=task.radius;meshDown=task.down;meshUp=task.up;
                meshPresentation=task.presentation;meshMaterial=task.material;meshCameraX=task.x;meshCameraY=task.y;meshCameraZ=task.z;
                meshWindow=task.light.window();
                meshTargetGeneration=task.light.targetGeneration();
                meshRebuilds++;meshRebuildMillis=(System.nanoTime()-task.started)/1_000_000;meshError="";
                coverage=new NativeCoverage(task.x,task.y,task.z,task.radius,Math.max(task.light.minimumY(),(int)Math.ceil(task.y-task.down)),Math.min(task.light.maximumY(),(int)Math.floor(task.y+task.up)),meshRebuilds,true);
                ClientGeneratedLight.nativeCommitted(meshTargetGeneration,coverage.revision(),task.light.stage()==NativeFirstViewPlan.Stage.FULL);
                var retained=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<VertexBuffer,Boolean>());for(var entry:gpu)retained.add(entry.buffer());
                for(var entry:old)if(!retained.contains(entry.buffer())){entry.buffer().close();gpuCloses++;}
                pendingNative=null;
                ClientGeneratedLight.nativeSceneCommitted(task.light);
                }finally{GeneratedFrameCosts.end(commitTiming);}
            }
        }catch(RuntimeException refused){meshError=refused.toString();ClientGeneratedLight.nativeSceneRefused(task.light,meshError);cancelNative();}
        finally {lastBuildStepNanos=buildNanos;lastUploadStepNanos=uploadNanos;maxBuildStepNanos=Math.max(maxBuildStepNanos,buildNanos);maxUploadStepNanos=Math.max(maxUploadStepNanos,uploadNanos);if(budget.elapsed()>FRAME_BUDGET_NANOS)stepOverruns++;}
    }

    private static void closeGpu(List<GpuCube> entries){for(var entry:entries){entry.buffer().close();gpuCloses++;}}
    private static void uploadGpu(List<MeshCube> cubes) {
        var next=new ArrayList<GpuCube>();long bytes=0;
        try {
            for(var cube:cubes)for(var layer:MODEL_LAYERS) {
                var buffer=NativeMeshBuffers.begin(layer.mode(),layer.format());
                try {
                var poses=new PoseStack();int faces=0,blocks=0,minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE,minBlock=15,maxBlock=0;
                for(var block:cube.blocks()) {
                    boolean context=GeneratedShaderMaterialContext.begin(buffer,block.state(),block.x(),block.y(),block.z());
                    poses.pushPose();poses.translate(block.x()-cube.bounds().minX,block.y()-cube.bounds().minY,block.z()-cube.bounds().minZ);
                    boolean submitted=false;
                    try {for(var face:block.faces())if(face.layer()==layer){buffer.putBulkData(poses.last(),face.quad(),face.red(),face.green(),face.blue(),GeneratedShaderLightPresentation.presentPacked(face.light()),OverlayTexture.NO_OVERLAY);faces++;submitted=true;minBlock=Math.min(minBlock,(face.light()>>>4)&15);maxBlock=Math.max(maxBlock,(face.light()>>>4)&15);}}
                    finally {if(context)GeneratedShaderMaterialContext.end(buffer);poses.popPose();}
                    if(submitted){blocks++;minY=Math.min(minY,block.y());maxY=Math.max(maxY,block.y());}
                }
                var built=NativeMeshBuffers.end();if(faces==0){built.release();NativeMeshBuffers.complete();continue;}
                long builtBytes=built.vertexBuffer().remaining();
                if(bytes+builtBytes>256L*1024*1024){built.release();NativeMeshBuffers.complete();throw new IllegalStateException("Native GPU allocation exceeds256MiB");}
                var vbo=new VertexBuffer(VertexBuffer.Usage.STATIC);
                try {vbo.bind();try{vbo.upload(built);}finally{NativeMeshBuffers.complete();}}catch(RuntimeException failed){vbo.close();throw failed;}finally {VertexBuffer.unbind();}
                next.add(new GpuCube(cube.bounds(),layer,vbo,blocks,faces,minY,maxY,minBlock,maxBlock,builtBytes));gpuUploads++;
                bytes+=builtBytes;
                if(bytes>256L*1024*1024)throw new IllegalStateException("Native GPU allocation exceeds256MiB");
                } finally {NativeMeshBuffers.cancel();}
            }
        }catch(RuntimeException failure){closeGpu(next);throw failure;}
        var old=gpu;gpu=List.copyOf(next);gpuBytes=bytes;closeGpu(old);
    }
    private static void drawGpu(RenderLevelStageEvent event,double minY,double maxY) {
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.NATIVE_DRAW);
        int counts=GeneratedDrawCounters.ENABLED?GeneratedDrawCounters.enter(GeneratedFarShadowRenderer.isShadowPass()?GeneratedDrawCounters.NATIVE_SHADOW:GeneratedDrawCounters.NATIVE_WORLD):-1;
        try{drawGpuMeasured(event,minY,maxY);}finally{GeneratedDrawCounters.leave(counts);GeneratedFrameCosts.end(timing);}
    }
    private static void drawGpuMeasured(RenderLevelStageEvent event,double minY,double maxY) {
        var camera=event.getCamera().getPosition();float fogStart=RenderSystem.getShaderFogStart(),fogEnd=RenderSystem.getShaderFogEnd();
        int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
        try {
            RenderSystem.setShaderFogStart(Math.max(fogStart,rangeDown));RenderSystem.setShaderFogEnd(Math.max(fogEnd,rangeDown+rangeUp));
            for(var layer:MODEL_LAYERS) {
                layer.setupRenderState();
                GeneratedDrawCounters.add(GeneratedDrawCounters.LAYERS,1);boolean nonempty=false;
                DRAW_BATCH.begin(RenderSystem.getShader(),event.getProjectionMatrix());
                try {for(var entry:gpu) {
                    if(entry.layer()!=layer||entry.bounds().maxY<minY||entry.bounds().minY>maxY)continue;
                    GeneratedDrawCounters.add(GeneratedDrawCounters.CANDIDATES,1);long cull=GeneratedDrawCounters.frustumBegin();boolean visible;
                    try{visible=event.getFrustum().isVisible(entry.bounds());}finally{GeneratedDrawCounters.frustumEnd(cull);}
                    if(!visible)continue;nonempty=true;
                    var matrix=GeneratedBatchedDraw.ENABLED?DRAW_BATCH.matrix(event.getPoseStack().last().pose(),(float)(entry.bounds().minX-camera.x),(float)(entry.bounds().minY-camera.y),(float)(entry.bounds().minZ-camera.z))
                            :new Matrix4f(event.getPoseStack().last().pose()).translate((float)(entry.bounds().minX-camera.x),(float)(entry.bounds().minY-camera.y),(float)(entry.bounds().minZ-camera.z));
                    entry.buffer().bind();DRAW_BATCH.draw(entry.buffer(),matrix);VertexBuffer.unbind();gpuDraws++;
                    if(entry.faces()>0&&event.getStage()==RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS)nativeWorldDraw(false,entry.faces(),NativeTask.key(entry.bounds()));
                    drawnFaces+=entry.faces();drawnBlocks+=entry.blocks();visitedCubes++;minSubmittedBlockLight=Math.min(minSubmittedBlockLight,entry.minBlockLight());maxSubmittedBlockLight=Math.max(maxSubmittedBlockLight,entry.maxBlockLight());low=Math.min(low,entry.minY());high=Math.max(high,entry.maxY());
                }} finally {try{DRAW_BATCH.close();}finally{if(!nonempty)GeneratedDrawCounters.add(GeneratedDrawCounters.EMPTY_LAYERS,1);VertexBuffer.unbind();layer.clearRenderState();}}
            }
            renderedFrames++;if(drawnBlocks>0){submittedMinY=low;submittedMaxY=high;}
        }finally {RenderSystem.setShaderFogStart(fogStart);RenderSystem.setShaderFogEnd(fogEnd);}
    }

    /** Successful nonempty world submission only; GPU READY and shadow draws do not count. */
    static void nativeWorldDraw(boolean fluid,int faces){
        nativeWorldDraw(fluid,faces,null);
    }
    static void nativeWorldDraw(boolean fluid,int faces,CubeKey cube){
        syncNativeTiming();
        if(faces<=0||timingSession==null||meshWindow==null)return;
        boolean near=meshWindow.stage()==NativeFirstViewPlan.Stage.NEAR;
        var client=Minecraft.getInstance();
        if(GeneratedFarShadowRenderer.isShadowPass()||coverage==null||!coverage.complete()||meshLevel.get()!=client.level||ClientGeneratedLight.source()==null
                ||ClientGeneratedLight.geometryObservationsPending()||meshGeometryEpoch!=ClientGeneratedLight.geometryEpoch()
                ||DeepFluidRenderer.resourcesChanged()||meshPresentation!=GeneratedShaderLightPresentation.revision()
                ||meshMaterial!=GeneratedShaderMaterialContext.revision())return;
        if(cube!=null){
            long target=ClientGeneratedLight.targetGeneration();
            if(meshTargetGeneration==target)ClientGeneratedLight.nativeDrawn(target,coverage.revision(),cube,faces,!near);
            else ClientGeneratedLight.retainedNativeDrawn(cube,faces);
            if(currentNativeWorldDraw==null||currentNativeWorldDraw.get("coverageRevision").getAsLong()!=coverage.revision()
                    ||currentNativeWorldDraw.get("targetGeneration").getAsLong()!=target){
            var current=new JsonObject();current.addProperty("epoch",meshGeometryEpoch);current.addProperty("coverageRevision",coverage.revision());
            current.addProperty("lightRevision",meshLightRevision);current.addProperty("targetGeneration",target);current.addProperty("faces",faces);
            current.addProperty("meshTargetGeneration",meshTargetGeneration);current.addProperty("retained",meshTargetGeneration!=target);
            current.addProperty("stage",meshWindow.stage().name());current.addProperty("millis",(System.nanoTime()-ClientGeneratedLight.nativeSessionOriginNanos())/1_000_000.0);
            var key=new com.google.gson.JsonArray();key.add(cube.x());key.add(cube.y());key.add(cube.z());current.add("cube",key);currentNativeWorldDraw=current;
            }
        }
        if(near?(fluid?nearFluidDraw!=null:nearSolidDraw!=null):(fluid?fullFluidDraw!=null:fullSolidDraw!=null))return;
        var stamp=new DrawStamp((System.nanoTime()-ClientGeneratedLight.nativeSessionOriginNanos())/1_000_000.0,
                meshGeometryEpoch,coverage.revision());
        if(near){if(fluid)nearFluidDraw=stamp;else nearSolidDraw=stamp;}
        else {if(fluid)fullFluidDraw=stamp;else fullSolidDraw=stamp;}
    }
    private static void syncNativeTiming(){
        var current=ClientGeneratedLight.nativeSession();
        if(!java.util.Objects.equals(timingSession,current)){timingSession=current;nearSolidDraw=nearFluidDraw=fullSolidDraw=fullFluidDraw=null;currentNativeWorldDraw=null;}
    }
    private static double drawMillis(DrawStamp stamp){return stamp==null?-1:stamp.millis();}
    private static DrawStamp earlier(DrawStamp a,DrawStamp b){return a==null?b:b==null?a:a.millis()<=b.millis()?a:b;}

    private static int boundedFloor(double coordinate) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) Math.floor(coordinate)));
    }
}
