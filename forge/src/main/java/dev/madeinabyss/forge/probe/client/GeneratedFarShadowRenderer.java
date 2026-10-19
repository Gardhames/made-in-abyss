package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.lod.SpatialBoundsIndex;
import dev.madeinabyss.kernel.lod.OpaqueBoxFaces;
import dev.madeinabyss.kernel.lod.ShadowBoundaryPlan;
import dev.madeinabyss.kernel.lod.PackedShadowVertex;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import org.lwjgl.system.MemoryStack;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.*;

/** Bounded shadow-only occluders and matching RSM data from accepted DH geometry.
 * Uses Oculus's public DH shadow program and packed vertex format.
 * Opaque GRASS is a coarse canopy approximation; liquids are excluded. */
public final class GeneratedFarShadowRenderer {
    public static final int MAX_BOXES = ShadowBoundaryPlan.MAX_INPUT_BOXES;
    private static final int MAX_INPUT_BOXES = 400000;
    private static List<TerrainLodColumns.Box> geometry = List.of();
    private record Occluder(TerrainLodColumns.Box box,AABB bounds) {}
    private static List<Occluder> occluders = List.of();
    private static SpatialBoundsIndex index=SpatialBoundsIndex.create(List.of(),128);
    private static List<AABB> nodeBounds=List.of();
    private static long indexBuildMillis,visitedNodes,testedBoxes,cullingCacheHits;
    private static List<TerrainLodColumns.Box> selectionSnapshot=List.of();
    private static boolean selectionKeyValid;
    private static long selectionGeometryRevision=-1;
    private static double selectionX,selectionY,selectionZ;
    private static float selectionYaw,selectionPitch;
    private static Class<?> selectionFrustumType;
    private static final Matrix4f selectionPose=new Matrix4f(),selectionProjection=new Matrix4f();
    private static final Matrix4f selectionView=new Matrix4f(),selectionViewProjection=new Matrix4f();
    private static int waterBoxes;
    private static Map<TerrainLodColumns.Box,Integer> physicalSky=Map.of();
    private static int missingLightBoxes, vertexBytes, colorAttachment0, colorAttachment1;
    private static long meshPresentationRevision=-1;
    private static List<TerrainLodColumns.Box> cachedSelection = List.of();
    private static volatile long geometryRevision;
    private static long meshRevision = -1, frames, draws, uploads, closes;
    private static long selectionMillis, uploadMillis;
    private static long faceExtractionMillis,drawWallTotalNanos,drawWallMaxNanos,glWallTotalNanos,glWallMaxNanos;
    private static long guardWallTotalNanos,shaderDrawWallTotalNanos,cleanupWallTotalNanos;
    private static long guardWallMaxNanos,shaderDrawWallMaxNanos,cleanupWallMaxNanos;
    private static int guardTimingSamples,shaderDrawTimingSamples,cleanupTimingSamples;
    private static int timingSamples,glTimingSamples,emittedFaces,hiddenFaces,extraSplitFaces;
    private static int selectedBoxes, excludedWater, frustumRejected, meshBytes, drawFramebuffer, depthAttachment;
    private static boolean capped, enabled = true;
    private static double minY, maxY;
    private static int anchorX, anchorY, anchorZ;
    private static Object resourceModel;
    private static VertexBuffer mesh;
    private record PreparedJob(long revision,ShadowBoundaryPlan.Prepared plan,long millis,String failure) {}
    private static volatile PreparedJob prepared;
    private static ThreadPoolExecutor preparationWorker;
    private static Future<?> preparationTask;
    private record PackedMesh(int vao,int vbo,int ebo,int indices,long x,long y,long z) {
        void close(){GL30.glDeleteVertexArrays(vao);GL15.glDeleteBuffers(vbo);GL15.glDeleteBuffers(ebo);}
    }
    private static List<PackedMesh> displayedMeshes=List.of();
    private static List<AABB> displayedChunkBounds=List.of();
    private static int shadowChunksTested,shadowChunksDrawn,shadowChunksCulled;
    private static long shadowFrustumFallbackFrames;
    private static String shadowFrustumStatus="UNTESTED";
    private static final ArrayList<PackedMesh> candidateMeshes=new ArrayList<>();
    private static PreparedJob candidateJob;
    private static int candidateChunk,candidateBytes,candidateAnchorX,candidateAnchorY,candidateAnchorZ;
    private static long candidatePresentation=-1,candidateMaterial=-1,displayedMaterial=-1;
    private static Object candidateResourceModel;
    private static long uploadBatches,uploadBatchMaxNanos,preparedRevision=-1;
    private static final long UPLOAD_TIME_BUDGET_NANOS=3_000_000L;
    private static Method shadowState, getManager, getPipeline, getDhCompat, getCompatInstance, getShadowFramebuffer, getFramebufferId, bindShadowFramebuffer;
    private static Method getShadowProgram,dhBind,dhUnbind,dhFill,dhSetModel;
    private static String shadowBackend="UNAVAILABLE";
    private static Field shadowFrustum;
    private static Object capturedState;
    private static Method capturedView,capturedProjection;
    private static boolean resolved;
    private static String error = "", shaderName = "";
    private GeneratedFarShadowRenderer() {}

    /** Render-thread publication only after the main scene's accepted handoff. */
    public static void acceptGeometry(List<TerrainLodColumns.Box> boxes) {
        acceptGeometry(boxes,Map.of());
    }

    /** SKY must be worker-computed for these exact clipped proxy boxes, never queried on the render thread. */
    public static void acceptGeometry(List<TerrainLodColumns.Box> boxes,Map<TerrainLodColumns.Box,Integer> rawSky) {
        RenderSystem.assertOnRenderThread();
        if (boxes.size() > MAX_INPUT_BOXES) throw new IllegalArgumentException("Far shadow input exceeds400000 boxes");
        geometry = List.copyOf(boxes);
        // Caller publishes an immutable map owned by the accepted scene. Copy/validation and
        // whole-boundary extraction happen on the worker, never during the render handoff.
        physicalSky=rawSky;geometryRevision++;error="";capped=false;prepared=null;
        failedJob=null;failedResourceModel=null;
        closeCandidate();
        if(preparationTask!=null)preparationTask.cancel(true);
        if(preparationWorker==null||preparationWorker.isShutdown())preparationWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"mia-far-shadow-boundary");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY);return t;});
        preparationWorker.purge();
        final long revision=geometryRevision;final var captured=geometry;final var capturedSky=rawSky;
        preparationTask=preparationWorker.submit(()->{
            long start=System.nanoTime();
            try {var plan=ShadowBoundaryPlan.prepare(captured,capturedSky);
                if(!Thread.currentThread().isInterrupted()&&revision==geometryRevision)prepared=new PreparedJob(revision,plan,(System.nanoTime()-start)/1_000_000,"");
            }catch(CancellationException cancelled){/* Superseded immutable scene. */}
            catch(RuntimeException failure){if(!Thread.currentThread().isInterrupted()&&revision==geometryRevision)prepared=new PreparedJob(revision,null,(System.nanoTime()-start)/1_000_000,failure.toString());}
        });
    }

    public static void enabled(boolean value) { enabled = value; }

    /** Process shutdown only; all VBO destruction belongs to normal render-thread clear(). */
    public static void shutdownBackground(){
        geometryRevision++;
        if(preparationTask!=null)preparationTask.cancel(true);
        if(preparationWorker!=null)preparationWorker.shutdownNow();
    }

    public static void clear() {
        RenderSystem.assertOnRenderThread();
        closeMesh(); geometry = List.of(); occluders=List.of();physicalSky=Map.of();waterBoxes=missingLightBoxes=0;cachedSelection = List.of(); geometryRevision++;
        prepared=null;closeCandidate();if(preparationTask!=null)preparationTask.cancel(true);preparationTask=null;
        failedJob=null;failedResourceModel=null;
        if(preparationWorker!=null)preparationWorker.shutdownNow();preparationWorker=null;
        index=SpatialBoundsIndex.create(List.of(),128);nodeBounds=List.of();selectionSnapshot=List.of();selectionKeyValid=false;
        selectedBoxes = excludedWater = frustumRejected = meshBytes = 0; capped = false; resourceModel = null;
        emittedFaces=hiddenFaces=extraSplitFaces=0;
    }

    private static void closeMesh() { for(var value:displayedMeshes){value.close();closes++;}displayedMeshes=List.of();displayedChunkBounds=List.of();mesh=null; }
    private static void closeCandidate(){for(var value:candidateMeshes){value.close();closes++;}candidateMeshes.clear();candidateJob=null;candidateChunk=candidateBytes=0;}

    private static boolean shadowPass() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> state = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState");
                shadowState = state.getMethod("areShadowsCurrentlyBeingRendered");
                shadowFrustum = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer").getField("FRUSTUM");
                var compatType=Class.forName("net.irisshaders.iris.compat.dh.DHCompatInternal");
                getShadowProgram=compatType.getMethod("getShadowShader");
                var programType=getShadowProgram.getReturnType();
                dhBind=programType.getMethod("bind");dhUnbind=programType.getMethod("unbind");
                dhFill=programType.getMethod("fillUniformData",org.joml.Matrix4fc.class,org.joml.Matrix4fc.class,int.class,float.class);
                dhSetModel=programType.getMethod("setModelPos",DhApiVec3f.class);
                Class<?> captured=Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
                capturedState=captured.getField("INSTANCE").get(null);
                capturedView=captured.getMethod("getGbufferModelView");capturedProjection=captured.getMethod("getGbufferProjection");
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                getManager = iris.getMethod("getPipelineManager");
                getPipeline = getManager.getReturnType().getMethod("getPipeline");
                Class<?> pipeline = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
                getDhCompat = pipeline.getMethod("getDHCompat");
                getCompatInstance = getDhCompat.getReturnType().getMethod("getInstance");
                Class<?> internal=Class.forName("net.irisshaders.iris.compat.dh.DHCompatInternal");
                getShadowFramebuffer=internal.getMethod("getShadowFB");
                getFramebufferId=getShadowFramebuffer.getReturnType().getMethod("getId");
                bindShadowFramebuffer=getShadowFramebuffer.getReturnType().getMethod("bindAsDrawBuffer");
            } catch (ClassNotFoundException absent) { return false; }
            catch (ReflectiveOperationException failure) { error = failure.toString(); return false; }
        }
        try { return shadowState != null && Boolean.TRUE.equals(shadowState.invoke(null)); }
        catch (ReflectiveOperationException failure) { error = failure.toString(); return false; }
    }
    public static boolean isShadowPass(){return shadowPass();}

    public static void draw(RenderLevelStageEvent event) {
        if (!enabled || geometry.isEmpty() || !shadowPass()) return;
        // The native dispatcher invokes far shadows before its main-pass preparation.
        // Refresh backend identities here as well, including the first shadow after reload.
        GeneratedShaderMaterialContext.prepareFrame();GeneratedShaderLightPresentation.prepareFrame();
        if(timingSamples==256){timingSamples=glTimingSamples=0;drawWallTotalNanos=drawWallMaxNanos=glWallTotalNanos=glWallMaxNanos=0;}
        if(timingSamples==0){guardTimingSamples=shaderDrawTimingSamples=cleanupTimingSamples=0;
            guardWallTotalNanos=shaderDrawWallTotalNanos=cleanupWallTotalNanos=0;
            guardWallMaxNanos=shaderDrawWallMaxNanos=cleanupWallMaxNanos=0;}
        long start=System.nanoTime();
        try {drawShadow(event);}
        finally {long elapsed=System.nanoTime()-start;timingSamples++;drawWallTotalNanos+=elapsed;drawWallMaxNanos=Math.max(drawWallMaxNanos,elapsed);}
    }

    private static void drawShadow(RenderLevelStageEvent event) {
        var client=Minecraft.getInstance();if(!ClientDeepRuntime.generatedAccepted(client.level))return;
        try {if(!"iterationT 3.2.0.zip".equals(Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPackName").invoke(null)))return;}
        catch(ReflectiveOperationException failure){error=failure.toString();return;}
        frames++;var camera=event.getCamera().getPosition();var model=client.getBlockRenderer().getBlockModel(Blocks.STONE.defaultBlockState());
        pumpUploads(camera.x,camera.y,camera.z,model);
        if(displayedMeshes.isEmpty()||resourceModel!=model||displayedMaterial!=GeneratedShaderMaterialContext.revision()
                ||meshPresentationRevision!=GeneratedShaderLightPresentation.revision())return;
        long glStart=System.nanoTime();
        int oldDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),oldRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldArray=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),oldActive=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        boolean oldDepth=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),oldCull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean oldBlend=GL11.glIsEnabled(GL11.GL_BLEND);
        int oldSrcRgb=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),oldDstRgb=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int oldSrcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),oldDstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int oldEquationRgb=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),oldEquationAlpha=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        var oldShader=RenderSystem.getShader();int units=Math.min(32,GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS));
        int[] oldShaderTextures=new int[12];for(int i=0;i<12;i++)oldShaderTextures[i]=RenderSystem.getShaderTexture(i);
        int[] textures=new int[units],samplers=new int[units];
        // Vanilla tracks only12 texture units. Iris may bind higher units directly.
        for(int i=0;i<units;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);textures[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);samplers[i]=GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);}
        GL13.glActiveTexture(oldActive);
        Object program=null;boolean bound=false;
        try(var stack=MemoryStack.stackPush()) {
            var color=stack.malloc(4);GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,color);
            try {
                var pipeline=(Optional<?>)getPipeline.invoke(getManager.invoke(null));
                Object compat=getCompatInstance.invoke(getDhCompat.invoke(pipeline.orElseThrow()));
                program=getShadowProgram.invoke(compat);if(program==null)throw new IllegalStateException("Actual Oculus DH shadow program unavailable");
                shadowBackend=program.getClass().getName();shaderName="dh_shadow (official Oculus program)";
                Object shadowFb=getShadowFramebuffer.invoke(compat);if(shadowFb==null)throw new IllegalStateException("Oculus DH shadow target unavailable");
                int expectedFb=(Integer)getFramebufferId.invoke(shadowFb);
                // IrisLodRenderProgram.bind selects only the program/blend state. The
                // official DH framebuffer must be selected independently before draw.
                bindShadowFramebuffer.invoke(shadowFb);
                drawFramebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                depthAttachment=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,expectedFb);
                int expectedDepth=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);
                if(drawFramebuffer!=expectedFb||drawFramebuffer==client.getMainRenderTarget().frameBufferId||depthAttachment!=expectedDepth||depthAttachment==0)
                    throw new IllegalStateException("Refusing DH shadow draw without verified Oculus depth target");
                colorAttachment0=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                colorAttachment1=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                if(colorAttachment0==0||colorAttachment1==0)throw new IllegalStateException("Shadow target lacks RSM attachments");
                boolean[] visible=new boolean[displayedMeshes.size()];java.util.Arrays.fill(visible,true);
                shadowChunksTested=shadowChunksDrawn=shadowChunksCulled=0;
                try {
                    Frustum frustum=shadowFrustum==null?null:(Frustum)shadowFrustum.get(null);
                    shadowFrustumStatus=frustum==null?"UNAVAILABLE_DRAW_ALL":frustum.getClass().getName();
                    if(frustum==null)shadowFrustumFallbackFrames++;
                    else for(int i=0;i<visible.length;i++){shadowChunksTested++;visible[i]=frustum.isVisible(displayedChunkBounds.get(i));}
                }catch(ReflectiveOperationException|RuntimeException failure){java.util.Arrays.fill(visible,true);shadowFrustumFallbackFrames++;shadowFrustumStatus="FAILED_DRAW_ALL: "+failure;}
                RenderSystem.enableCull();RenderSystem.depthMask(true);RenderSystem.colorMask(true,true,true,true);
                bound=true;dhBind.invoke(program);
                // Official transformer adds modelOffset to unsigned local positions. Camera
                // subtraction belongs only there; the shadow view remains untranslated.
                dhFill.invoke(program,event.getProjectionMatrix(),event.getPoseStack().last().pose(),0,client.getFrameTime());
                long elapsed=System.nanoTime()-glStart;guardTimingSamples++;guardWallTotalNanos+=elapsed;guardWallMaxNanos=Math.max(guardWallMaxNanos,elapsed);
                long drawStart=System.nanoTime();
                try {
                    for(int i=0;i<visible.length;i++) {
                        if(!visible[i]){shadowChunksCulled++;continue;}
                        var value=displayedMeshes.get(i);
                        dhSetModel.invoke(program,new DhApiVec3f((float)(value.x-camera.x),(float)(value.y-camera.y),(float)(value.z-camera.z)));
                        GL30.glBindVertexArray(value.vao);GL11.glDrawElements(GL11.GL_TRIANGLES,value.indices,GL11.GL_UNSIGNED_SHORT,0L);shadowChunksDrawn++;
                    }
                    draws++;
                }finally{long nanos=System.nanoTime()-drawStart;shaderDrawTimingSamples++;shaderDrawWallTotalNanos+=nanos;shaderDrawWallMaxNanos=Math.max(shaderDrawWallMaxNanos,nanos);}
            }catch(ReflectiveOperationException|RuntimeException failure){error=failure.toString();}
            finally {
                long cleanup=System.nanoTime();
                if(bound)try{dhUnbind.invoke(program);}catch(ReflectiveOperationException failure){error=failure.toString();}
                RenderSystem.colorMask(color.get(0)!=0,color.get(1)!=0,color.get(2)!=0,color.get(3)!=0);
                RenderSystem.depthMask(oldDepth);if(oldCull)RenderSystem.enableCull();else RenderSystem.disableCull();
                if(oldBlend)RenderSystem.enableBlend();else RenderSystem.disableBlend();
                GlStateManager._blendFuncSeparate(oldSrcRgb,oldDstRgb,oldSrcAlpha,oldDstAlpha);GL20.glBlendEquationSeparate(oldEquationRgb,oldEquationAlpha);
                for(int i=0;i<units;i++) {
                    if(i<12)GlStateManager._activeTexture(GL13.GL_TEXTURE0+i);else GL13.glActiveTexture(GL13.GL_TEXTURE0+i);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]);
                    if(i<12)GlStateManager._bindTexture(textures[i]);GL33.glBindSampler(i,samplers[i]);
                }
                GL13.glActiveTexture(oldActive);GlStateManager._activeTexture(oldActive);RenderSystem.setShader(()->oldShader);
                for(int i=0;i<12;i++)RenderSystem.setShaderTexture(i,oldShaderTextures[i]);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);
                GL30.glBindVertexArray(oldVao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,oldArray);GlStateManager._glUseProgram(oldProgram);
                long nanos=System.nanoTime()-cleanup;cleanupTimingSamples++;cleanupWallTotalNanos+=nanos;cleanupWallMaxNanos=Math.max(cleanupWallMaxNanos,nanos);
            }
        }finally{long nanos=System.nanoTime()-glStart;glTimingSamples++;glWallTotalNanos+=nanos;glWallMaxNanos=Math.max(glWallMaxNanos,nanos);}
    }
    private static PreparedJob failedJob;
    private static Object failedResourceModel;
    private static long failedPresentation=-1,failedMaterial=-1;
    private static void pumpUploads(double cameraX,double cameraY,double cameraZ,Object model) {
        var job=prepared;if(job==null||job.revision()!=geometryRevision)return;
        preparedRevision=job.revision();faceExtractionMillis=job.millis();
        if(!job.failure().isEmpty()){error=job.failure()+"; previous complete shadow mesh retained";capped=true;return;}
        long presentation=GeneratedShaderLightPresentation.revision(),material=GeneratedShaderMaterialContext.revision();
        if(failedJob==job&&failedPresentation==presentation&&failedMaterial==material&&failedResourceModel==model)return;
        if(meshRevision==job.revision()&&meshPresentationRevision==presentation&&displayedMaterial==material&&resourceModel==model)return;
        if(candidateJob!=job||candidatePresentation!=presentation||candidateMaterial!=material||candidateResourceModel!=model) {
            closeCandidate();candidateJob=job;candidatePresentation=presentation;candidateMaterial=material;candidateResourceModel=model;
            candidateAnchorX=Math.floorDiv((int)Math.floor(cameraX),16)*16;
            candidateAnchorY=Math.floorDiv((int)Math.floor(cameraY),16)*16;
            candidateAnchorZ=Math.floorDiv((int)Math.floor(cameraZ),16)*16;
        }
        long start=System.nanoTime();
        try {
            // A complete chunk is the smallest upload unit: <=4096 faces and <=1MiB.
            // Further chunks wait for a later callback once this soft wall budget expires.
            while(candidateChunk<job.plan().chunkCount()) {
                uploadChunk(job.plan(),candidateChunk++);
                if(System.nanoTime()-start>=UPLOAD_TIME_BUDGET_NANOS)break;
            }
            if(candidateChunk==job.plan().chunkCount()) {
                closeMesh();displayedMeshes=List.copyOf(candidateMeshes);candidateMeshes.clear();
                var plan=job.plan();meshBytes=candidateBytes;vertexBytes=displayedMeshes.isEmpty()?0:vertexBytes;
                // Oculus FRUSTUM is already prepared at the unshifted world camera. Its
                // isVisible(AABB) subtracts that camera internally: bounds remain global.
                // A small conservative pad covers flat faces and float vertex rounding.
                displayedChunkBounds=plan.chunkBounds().stream().map(b->new AABB(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()).inflate(1.0/16)).toList();
                anchorX=candidateAnchorX;anchorY=candidateAnchorY;anchorZ=candidateAnchorZ;
                meshRevision=job.revision();meshPresentationRevision=presentation;displayedMaterial=material;resourceModel=model;
                selectedBoxes=plan.boxes().size();waterBoxes=geometry.size()-selectedBoxes;excludedWater=waterBoxes;frustumRejected=0;
                emittedFaces=plan.surface().faces().size();hiddenFaces=plan.surface().hiddenFaces();extraSplitFaces=plan.surface().extraSplitFaces();
                minY=plan.boxes().stream().mapToLong(TerrainLodColumns.Box::minY).min().orElse(0);
                maxY=plan.boxes().stream().mapToLong(TerrainLodColumns.Box::maxYExclusive).max().orElse(0);
                candidateJob=null;candidateChunk=candidateBytes=0;capped=false;error="";
                failedJob=null;failedResourceModel=null;
            }
        } catch(RuntimeException failure) {
            failedJob=job;failedPresentation=presentation;failedMaterial=material;failedResourceModel=model;error=failure+"; previous complete shadow mesh retained";capped=true;closeCandidate();
        } finally {
            long elapsed=System.nanoTime()-start;uploadBatches++;uploadBatchMaxNanos=Math.max(uploadBatchMaxNanos,elapsed);uploadMillis=elapsed/1_000_000;
        }
    }
    private static void uploadChunk(ShadowBoundaryPlan.Prepared plan,int chunk) {
        int start=plan.chunkStart(chunk),end=plan.chunkEnd(chunk),faces=end-start;
        var bounds=plan.chunkBounds().get(chunk);long ax=bounds.minX(),ay=bounds.minY(),az=bounds.minZ();
        long bytes=(long)faces*(4*PackedShadowVertex.STRIDE+6*2);
        if(candidateBytes+bytes>ShadowBoundaryPlan.MAX_GPU_BYTES)throw new IllegalArgumentException("Packed shadow VBO/index budget exceeded128MiB");
        ByteBuffer vertices=MemoryUtil.memAlloc(faces*4*PackedShadowVertex.STRIDE).order(ByteOrder.nativeOrder());
        ByteBuffer indices=MemoryUtil.memAlloc(faces*6*2).order(ByteOrder.nativeOrder());
        int vao=0,vbo=0,ebo=0;boolean committed=false;
        int oldVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),oldArray=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            for(int i=start;i<end;i++) {
                var f=plan.surface().faces().get(i);var box=plan.boxes().get(f.owner());
                int rgb=switch(box.material()){case ROCK->0x7e7b77;case SOIL->0x745337;default->0x688a41;};
                int material=switch(box.material()){case ROCK->Byte.toUnsignedInt(EDhApiBlockMaterial.STONE.index);case SOIL->Byte.toUnsignedInt(EDhApiBlockMaterial.DIRT.index);default->Byte.toUnsignedInt(EDhApiBlockMaterial.GRASS.index);};
                putPackedFace(vertices,f,ax,ay,az,rgb,GeneratedShaderLightPresentation.presentSky(plan.sky(f.owner())),material);
                int v=(i-start)*4;indices.putShort((short)v).putShort((short)(v+1)).putShort((short)(v+2))
                    .putShort((short)(v+2)).putShort((short)(v+3)).putShort((short)v);
            }
            vertices.flip();indices.flip();vao=GL30.glGenVertexArrays();vbo=GL15.glGenBuffers();ebo=GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,vertices,GL15.GL_STATIC_DRAW);
            GL30.glVertexAttribIPointer(0,4,GL11.GL_UNSIGNED_SHORT,PackedShadowVertex.STRIDE,0L);GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1,4,GL11.GL_UNSIGNED_BYTE,true,PackedShadowVertex.STRIDE,8L);GL20.glEnableVertexAttribArray(1);
            GL30.glVertexAttribIPointer(2,2,GL11.GL_UNSIGNED_BYTE,PackedShadowVertex.STRIDE,12L);GL20.glEnableVertexAttribArray(2);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ebo);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,indices,GL15.GL_STATIC_DRAW);
            candidateMeshes.add(new PackedMesh(vao,vbo,ebo,faces*6,ax,ay,az));candidateBytes+=Math.toIntExact(bytes);vertexBytes=PackedShadowVertex.STRIDE;uploads++;committed=true;
        }finally {
            GL30.glBindVertexArray(oldVao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,oldArray);
            if(!committed){if(vao!=0)GL30.glDeleteVertexArrays(vao);if(vbo!=0)GL15.glDeleteBuffers(vbo);if(ebo!=0)GL15.glDeleteBuffers(ebo);}
            MemoryUtil.memFree(vertices);MemoryUtil.memFree(indices);
        }
    }
    private static void putPackedFace(ByteBuffer b,OpaqueBoxFaces.Face f,long ax,long ay,long az,int rgb,int sky,int material) {
        int n=PackedShadowVertex.normalIndex(f.axis(),f.positive());
        if(f.axis()==0) {
            long x=f.plane(),z0=f.u0(),z1=f.u1(),y0=f.v0(),y1=f.v1();
            if(f.positive()){pv(b,ax,ay,az,x,y0,z0,rgb,sky,material,n);pv(b,ax,ay,az,x,y1,z0,rgb,sky,material,n);pv(b,ax,ay,az,x,y1,z1,rgb,sky,material,n);pv(b,ax,ay,az,x,y0,z1,rgb,sky,material,n);}
            else{pv(b,ax,ay,az,x,y0,z1,rgb,sky,material,n);pv(b,ax,ay,az,x,y1,z1,rgb,sky,material,n);pv(b,ax,ay,az,x,y1,z0,rgb,sky,material,n);pv(b,ax,ay,az,x,y0,z0,rgb,sky,material,n);}
        }else if(f.axis()==1) {
            long y=f.plane(),x0=f.u0(),x1=f.u1(),z0=f.v0(),z1=f.v1();
            if(f.positive()){pv(b,ax,ay,az,x0,y,z1,rgb,sky,material,n);pv(b,ax,ay,az,x1,y,z1,rgb,sky,material,n);pv(b,ax,ay,az,x1,y,z0,rgb,sky,material,n);pv(b,ax,ay,az,x0,y,z0,rgb,sky,material,n);}
            else{pv(b,ax,ay,az,x0,y,z0,rgb,sky,material,n);pv(b,ax,ay,az,x1,y,z0,rgb,sky,material,n);pv(b,ax,ay,az,x1,y,z1,rgb,sky,material,n);pv(b,ax,ay,az,x0,y,z1,rgb,sky,material,n);}
        }else {
            long z=f.plane(),x0=f.u0(),x1=f.u1(),y0=f.v0(),y1=f.v1();
            if(f.positive()){pv(b,ax,ay,az,x1,y0,z,rgb,sky,material,n);pv(b,ax,ay,az,x1,y1,z,rgb,sky,material,n);pv(b,ax,ay,az,x0,y1,z,rgb,sky,material,n);pv(b,ax,ay,az,x0,y0,z,rgb,sky,material,n);}
            else{pv(b,ax,ay,az,x0,y0,z,rgb,sky,material,n);pv(b,ax,ay,az,x0,y1,z,rgb,sky,material,n);pv(b,ax,ay,az,x1,y1,z,rgb,sky,material,n);pv(b,ax,ay,az,x1,y0,z,rgb,sky,material,n);}
        }
    }
    private static void pv(ByteBuffer b,long ax,long ay,long az,long x,long y,long z,int rgb,int sky,int material,int normal) {
        PackedShadowVertex.put(b,ax,ay,az,x,y,z,rgb,sky,0,material,normal);
    }    private static void surfaceFace(BufferBuilder b,float u,float v,int tint,int light,OpaqueBoxFaces.Face f,int ax,int ay,int az) {
        if(f.axis()==0) {
            float x=(float)(f.plane()-ax),z0=(float)(f.u0()-az),z1=(float)(f.u1()-az),y0=(float)(f.v0()-ay),y1=(float)(f.v1()-ay);
            if(f.positive())face(b,u,v,tint,light,1,0,0,x,y0,z0,x,y1,z0,x,y1,z1,x,y0,z1);
            else face(b,u,v,tint,light,-1,0,0,x,y0,z1,x,y1,z1,x,y1,z0,x,y0,z0);
        } else if(f.axis()==1) {
            float y=(float)(f.plane()-ay),x0=(float)(f.u0()-ax),x1=(float)(f.u1()-ax),z0=(float)(f.v0()-az),z1=(float)(f.v1()-az);
            if(f.positive())face(b,u,v,tint,light,0,1,0,x0,y,z1,x1,y,z1,x1,y,z0,x0,y,z0);
            else face(b,u,v,tint,light,0,-1,0,x0,y,z0,x1,y,z0,x1,y,z1,x0,y,z1);
        } else {
            float z=(float)(f.plane()-az),x0=(float)(f.u0()-ax),x1=(float)(f.u1()-ax),y0=(float)(f.v0()-ay),y1=(float)(f.v1()-ay);
            if(f.positive())face(b,u,v,tint,light,0,0,1,x1,y0,z,x1,y1,z,x0,y1,z,x0,y0,z);
            else face(b,u,v,tint,light,0,0,-1,x0,y0,z,x0,y1,z,x1,y1,z,x1,y0,z);
        }
    }

    private static void face(BufferBuilder b,float u,float v,int tint,int light,float nx,float ny,float nz,float... xyz) {
        for(int i=0;i<12;i+=3)b.vertex(xyz[i],xyz[i+1],xyz[i+2])
                .color((tint>>>16)&255,(tint>>>8)&255,tint&255,255).uv(u,v).uv2(light).normal(nx,ny,nz).endVertex();
    }

    public static JsonObject status() {
        var out=new JsonObject();out.addProperty("enabled",enabled);out.addProperty("geometryRevision",geometryRevision);
        out.addProperty("inputBoxes",geometry.size());out.addProperty("selectedBoxes",selectedBoxes);out.addProperty("maxBoxes",MAX_BOXES);
        out.addProperty("faces",emittedFaces);out.addProperty("boxFacesBeforeExtraction",selectedBoxes*6);
        out.addProperty("hiddenFaces",hiddenFaces);out.addProperty("extraSplitFaces",extraSplitFaces);out.addProperty("faceExtractionMillis",faceExtractionMillis);
        out.addProperty("meshBytes",meshBytes);out.addProperty("capped",capped);
        out.addProperty("vertexBytes",vertexBytes);out.addProperty("maximumVboBytes",ShadowBoundaryPlan.MAX_GPU_BYTES);
        out.addProperty("shadowBackend",shadowBackend);out.addProperty("shadowDrawMode","GL_TRIANGLES / ushort indices / official16-byte DH vertices");
        out.addProperty("shadowDepthFixScope","Actual Oculus PROGRAM_DH_SHADOW invokes pack DH_SHADOW_FIX plane-depth correction; isolated DH_SHADOW_CULLING must be false for own native coverage");
        out.addProperty("rsmAlbedoScope","Flat RGB matches main DH material colors; official shader sampler semantics");
        out.addProperty("maximumOwnedVboBytes",2*ShadowBoundaryPlan.MAX_GPU_BYTES);
        out.addProperty("maxFaces",ShadowBoundaryPlan.MAX_FACES);out.addProperty("uploadChunkFaces",ShadowBoundaryPlan.CHUNK_FACES);
        out.addProperty("uploadChunkMaximumBytes",(long)ShadowBoundaryPlan.CHUNK_FACES*4*ShadowBoundaryPlan.MAX_VERTEX_BYTES);
        out.addProperty("uploadSoftBudgetMillis",UPLOAD_TIME_BUDGET_NANOS/1_000_000.0);
        out.addProperty("uploadBatchMaxMillis",uploadBatchMaxNanos/1_000_000.0);out.addProperty("uploadBatches",uploadBatches);
        out.addProperty("preparedGeometryRevision",preparedRevision);out.addProperty("displayedGeometryRevision",meshRevision);
        out.addProperty("displayedChunks",displayedMeshes.size());out.addProperty("pendingChunks",candidateMeshes.size());
        out.addProperty("shadowChunksTested",shadowChunksTested);out.addProperty("shadowChunksDrawn",shadowChunksDrawn);out.addProperty("shadowChunksCulled",shadowChunksCulled);
        out.addProperty("shadowFrustumFallbackFrames",shadowFrustumFallbackFrames);out.addProperty("shadowFrustumStatus",shadowFrustumStatus);
        out.addProperty("candidateBytes",candidateBytes);out.addProperty("boundaryWorkerRunning",preparationTask!=null&&!preparationTask.isDone());
        out.addProperty("cullingScope","Static4096-face global AABBs against actual Oculus shadow frustum; draw-all fallback; no main-camera index, sort or extraction");
        out.addProperty("missingLightBoxes",missingLightBoxes);out.addProperty("meshPresentationRevision",meshPresentationRevision);
        out.addProperty("excludedWater",excludedWater);out.addProperty("frustumRejected",frustumRejected);
        out.addProperty("shadowCallbacks",frames);out.addProperty("draws",draws);out.addProperty("uploads",uploads);out.addProperty("closes",closes);
        out.addProperty("selectionMillis",selectionMillis);out.addProperty("uploadMillis",uploadMillis);
        out.addProperty("indexBuildMillis",indexBuildMillis);out.addProperty("indexNodes",index.nodeCount());
        out.addProperty("visitedNodes",visitedNodes);out.addProperty("testedBoxes",testedBoxes);out.addProperty("cullingCacheHits",cullingCacheHits);
        out.addProperty("wallTimingWindowSamples",timingSamples);out.addProperty("glWallTimingWindowSamples",glTimingSamples);
        out.addProperty("drawWallMeanMillis",timingSamples==0?0:drawWallTotalNanos/(timingSamples*1_000_000.0));
        out.addProperty("drawWallMaxMillis",drawWallMaxNanos/1_000_000.0);
        out.addProperty("glStateDrawWallMeanMillis",glTimingSamples==0?0:glWallTotalNanos/(glTimingSamples*1_000_000.0));
        out.addProperty("glStateDrawWallMaxMillis",glWallMaxNanos/1_000_000.0);
        out.addProperty("guardSetupWallMeanMillis",guardTimingSamples==0?0:guardWallTotalNanos/(guardTimingSamples*1_000_000.0));
        out.addProperty("guardSetupWallMaxMillis",guardWallMaxNanos/1_000_000.0);
        out.addProperty("shaderDrawWallMeanMillis",shaderDrawTimingSamples==0?0:shaderDrawWallTotalNanos/(shaderDrawTimingSamples*1_000_000.0));
        out.addProperty("shaderDrawWallMaxMillis",shaderDrawWallMaxNanos/1_000_000.0);
        out.addProperty("cleanupWallMeanMillis",cleanupTimingSamples==0?0:cleanupWallTotalNanos/(cleanupTimingSamples*1_000_000.0));
        out.addProperty("cleanupWallMaxMillis",cleanupWallMaxNanos/1_000_000.0);
        out.addProperty("wallTimingScope","CPU wall time including driver waits; GL section excludes culling/rebuild; 256-callback windows; not GPU timer");
        if(Double.isFinite(minY)&&Double.isFinite(maxY)){out.addProperty("selectedMinY",minY);out.addProperty("selectedMaxY",maxY);}
        out.addProperty("drawFramebuffer",drawFramebuffer);out.addProperty("depthAttachment",depthAttachment);out.addProperty("shader",shaderName);
        out.addProperty("albedoAttachment",colorAttachment0);out.addProperty("normalSkyAttachment",colorAttachment1);
        out.addProperty("scope","Official Oculus DH shadow depth/albedo/normal/SKY tuples for coarse ROCK/SOIL/GRASS; packed16-byte vertices with main-DH flat RGB; GRASS canopy approximate; liquids excluded");
        out.addProperty("error",error);return out;
    }
}


