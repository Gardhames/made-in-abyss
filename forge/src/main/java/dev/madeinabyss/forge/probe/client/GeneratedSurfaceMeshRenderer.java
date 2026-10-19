package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import dev.madeinabyss.forge.probe.client.compat.AbyssLodPipeline;
import dev.madeinabyss.kernel.lod.PackedSurfaceVertex;
import dev.madeinabyss.kernel.lod.SurfaceQuadPlan;
import dev.madeinabyss.kernel.lod.SurfacePageLeases;
import dev.madeinabyss.kernel.lod.SurfaceUploadCursor;
import dev.madeinabyss.kernel.concurrent.FrameWorkBudget;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Version-fenced Oculus1.8 terrain QUAD transport. No source sampling, IO or physical lighting. */
public final class GeneratedSurfaceMeshRenderer {
    public static final long MAX_GPU_BYTES=AbyssLodPipeline.MAX_SURFACE_GPU_BYTES;
    private static final long UPLOAD_NANOS=GeneratedFrameCosts.OWNER_BUDGET_NANOS;
    // 2026-10-15 client A/B (T9): surface upload is <0.01 ms/frame, interior checks gave no tail benefit and cost
    // +23% encode throughput, so whole-chunk encoding is the default; true re-enables the resumable cursor.
    private static final boolean RESUMABLE_UPLOAD=Boolean.parseBoolean(System.getProperty("mia.render.resumableSurfaceUpload","false"));
    private static final int MAX_PAGES=8192;
    public record DrawAck(AbyssLodPipeline.Target target,long sequence,int opaqueFaces,int waterFaces,long worldDrawMillis){}
    /** Back-to-front water order: the same stable descending-distance order the former per-frame
     * comparator sort produced, restricted to meshes that actually have water faces (the rest are skipped
     * by the draw loop anyway); distances are computed once per mesh and the order is reused while
     * neither the camera nor the mesh list changes. */
    private static List<Mesh> waterSource,waterSorted;private static double waterX=Double.NaN,waterY,waterZ;private static int waterSize=-1;
    private static List<Mesh> waterOrder(List<Mesh> meshes,double x,double y,double z){
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.SURFACE_SORT);
        try{return waterOrderMeasured(meshes,x,y,z);}finally{GeneratedFrameCosts.end(timing);}
    }
    private static List<Mesh> waterOrderMeasured(List<Mesh> meshes,double x,double y,double z){
        if(meshes==waterSource&&meshes.size()==waterSize&&x==waterX&&y==waterY&&z==waterZ)return waterSorted;
        int n=0;for(var mesh:meshes)if(mesh.water!=0)n++;
        var drawn=new Mesh[n];var distance=new double[n];var order=new Integer[n];int i=0;
        for(var mesh:meshes)if(mesh.water!=0){drawn[i]=mesh;distance[i]=mesh.distance(x,y,z);order[i]=i;i++;}
        java.util.Arrays.sort(order,(a,b)->Double.compare(distance[b],distance[a]));
        var sorted=new ArrayList<Mesh>(n);for(int k:order)sorted.add(drawn[k]);
        waterSource=meshes;waterSize=meshes.size();waterSorted=sorted;waterX=x;waterY=y;waterZ=z;return sorted;
    }
    private record Mesh(int vao,int vbo,int ebo,int opaque,int water,long x,long y,long z,double cx,double cy,double cz,AABB bounds){
        void close(){GL30.glDeleteVertexArrays(vao);GL15.glDeleteBuffers(vbo);GL15.glDeleteBuffers(ebo);}
        double distance(double x,double y,double z){double a=cx-x,b=cy-y,c=cz-z;return a*a+b*b+c*c;}
    }
    private static final class GpuPage {
        final SurfaceQuadPlan.Prepared plan;final ArrayList<Mesh> meshes=new ArrayList<>();
        int chunk,opaque,water;long bytes;boolean closed;Upload pending;
        GpuPage(SurfaceQuadPlan.Prepared plan){this.plan=plan;}
        boolean ready(){return chunk==plan.chunkBounds().size();}
        void close(){if(closed)return;closed=true;if(pending!=null){pending.close();pending=null;}for(var mesh:meshes){mesh.close();closes++;}meshes.clear();liveGpuBytes-=bytes;}
    }
    /** At most one active chunk per scene. Partial CPU bytes belong to this page lease. */
    private static final class Upload implements AutoCloseable {
        final int chunk,faces;final long ax,ay,az,bytes;final ByteBuffer vertices,indices;
        final SurfaceUploadCursor cursor;final java.util.function.IntConsumer vertex,index;
        boolean encoded;
        Upload(SurfaceQuadPlan.Prepared plan,int chunk){
            this.chunk=chunk;var bounds=plan.chunkBounds().get(chunk);ax=bounds.minX();ay=bounds.minY();az=bounds.minZ();
            int start=chunk*SurfaceQuadPlan.CHUNK_FACES,end=Math.min(plan.faces().size(),start+SurfaceQuadPlan.CHUNK_FACES);faces=end-start;
            bytes=(long)faces*SurfaceQuadPlan.GPU_BYTES_PER_FACE;
            vertices=MemoryUtil.memAlloc(faces*64).order(ByteOrder.nativeOrder());
            try{indices=MemoryUtil.memAlloc(faces*12).order(ByteOrder.nativeOrder());}
            catch(RuntimeException|Error failure){MemoryUtil.memFree(vertices);throw failure;}
            cursor=new SurfaceUploadCursor(start,end,i->plan.owners().get(plan.faces().get(i).owner()).box().material()==TerrainMaterial.WATER,32);
            vertex=i->{var face=plan.faces().get(i);putFace(vertices,face,plan.owners().get(face.owner()),ax,ay,az);};
            index=i->indices.putShort((short)i);
        }
        public void close(){MemoryUtil.memFree(vertices);MemoryUtil.memFree(indices);}
    }
    private static SurfacePageLeases<SurfaceQuadPlan.Prepared,GpuPage> pageRegistry;
    private static long liveGpuBytes,uploadedGpuBytes;
    private static final class Scene {
        final AbyssLodPipeline.Target target;final long sequence;final int faces,expectedOpaque,expectedWater;final Consumer<DrawAck> ack;
        final Object model;final long presentation,material;final ArrayList<Mesh> meshes=new ArrayList<>();
        final ArrayList<SurfacePageLeases<SurfaceQuadPlan.Prepared,GpuPage>.Lease> pages=new ArrayList<>();
        int pageCursor,opaque,water,reusedPages,newPages;long bytes,firstDraw=-1;boolean ready,closed,opaqueDrawn,waterDrawn,acknowledged;
        Scene(AbyssLodPipeline.Target target,long sequence,List<SurfaceQuadPlan.Prepared> prepared,int faces,int expectedOpaque,int expectedWater,Consumer<DrawAck> ack){
            this.target=target;this.sequence=sequence;this.faces=faces;this.expectedOpaque=expectedOpaque;this.expectedWater=expectedWater;this.ack=ack;
            model=Minecraft.getInstance().getBlockRenderer().getBlockModel(Blocks.STONE.defaultBlockState());
            presentation=GeneratedShaderLightPresentation.revision();material=GeneratedShaderMaterialContext.revision();
            var scope=new SurfacePageLeases.Scope(target.session(),target.lifetime(),target.sourceIdentity(),target.sourceRevision(),target.resourceRevision(),presentation,material,model);
            try{for(var plan:prepared){long created=pageRegistry.creations();var lease=pageRegistry.acquire(scope,plan,()->new GpuPage(plan),GpuPage::close);pages.add(lease);
                if(pageRegistry.creations()==created)reusedPages++;else newPages++;}advance();}
            catch(RuntimeException failure){close();throw failure;}
        }
        boolean ready(){return ready;}
        void advance(){while(pageCursor<pages.size()){var lease=pages.get(pageCursor);if(!lease.value().ready())break;lease.markReady();pageCursor++;}
            if(pageCursor==pages.size()&&!ready){int actualFaces=0;for(var lease:pages){var page=lease.value();meshes.addAll(page.meshes);bytes+=page.bytes;
                    opaque=Math.addExact(opaque,page.opaque);water=Math.addExact(water,page.water);actualFaces=Math.addExact(actualFaces,page.plan.faces().size());}
                if(actualFaces!=faces||(expectedOpaque>=0&&opaque!=expectedOpaque)||(expectedWater>=0&&water!=expectedWater)||bytes>maxSceneGpuBytes)
                    throw new IllegalArgumentException("Ready surface page aggregate differs from worker proposal");ready=true;}
        }
        int pendingChunks(){int count=0;for(var lease:pages){var page=lease.value();count=Math.addExact(count,page.plan.chunkBounds().size()-page.chunk);}return count;}
        boolean resources(){return model==Minecraft.getInstance().getBlockRenderer().getBlockModel(Blocks.STONE.defaultBlockState())
            &&presentation==GeneratedShaderLightPresentation.revision()&&material==GeneratedShaderMaterialContext.revision();}
        void close(){if(closed)return;closed=true;for(var page:pages)page.close();pages.clear();meshes.clear();}
    }
    private static Predicate<AbyssLodPipeline.Target> valid=unused->false;
    private static Scene displayed,candidate;
    private static long originNanos,draws,uploads,closes,worldAcks,shadowSkipped,uploadBatchMaxNanos,firstWorldDraw=-1;
    private static long maxSceneGpuBytes=MAX_GPU_BYTES;
    private static int framebuffer,depthAttachment;private static long opaqueSubmitted,waterSubmitted;
    private static String error="",backend="UNRESOLVED";
    private static Method shadowState,getManager,getPipeline,getDhCompat,getInstance,getSolid,getWater,getSolidFB,getWaterFB;
    private static Method getFramebufferId,bindFramebuffer,bind,unbind,fill,setModel,copyTranslucents;
    private static Method capturedView,capturedProjection;private static Object capturedState;
    private static Method getShadow,getShadowFB,currentPack;private static Field shadowFrustum;
    private static boolean resolved,configured;
    private static String shadowError="",shadowBackend="UNRESOLVED",shadowFrustumStatus="UNTESTED";
    private static long shadowDraws,shadowSubmitted,shadowFrustumFallbacks,shadowSequence=-1,shadowWallMaxNanos;
    private static int shadowFramebuffer,shadowDepth,shadowColor0,shadowColor1,shadowChunksTested,shadowChunksDrawn,shadowChunksCulled,shadowLastFaces;
    private GeneratedSurfaceMeshRenderer(){}

    public static void configure(Predicate<AbyssLodPipeline.Target> currentTarget,long timingOriginNanos){
        configure(currentTarget,timingOriginNanos,MAX_GPU_BYTES);
    }
    public static void configure(Predicate<AbyssLodPipeline.Target> currentTarget,long timingOriginNanos,long gpuBudgetBytes){
        if(gpuBudgetBytes!=MAX_GPU_BYTES&&gpuBudgetBytes!=AbyssLodPipeline.MAX_SURFACE_GPU_BYTES_155)throw new IllegalArgumentException("Unsupported bounded surface GPU admission");
        RenderSystem.assertOnRenderThread();valid=Objects.requireNonNull(currentTarget);originNanos=timingOriginNanos;configured=true;
        maxSceneGpuBytes=gpuBudgetBytes;
        if(pageRegistry==null)pageRegistry=new SurfacePageLeases<>(MAX_PAGES*2);
    }
    /** Mode ownership, including the interval before a first scene has actually drawn. */
    public static boolean enabled(){return configured;}
    public static void offer(AbyssLodPipeline.Target target,long sequence,SurfaceQuadPlan.Prepared plan,Consumer<DrawAck> ack){
        RenderSystem.assertOnRenderThread();Objects.requireNonNull(plan);
        offerPrepared(target,sequence,List.of(plan),plan.faces().size(),-1,-1,(long)plan.faces().size()*SurfaceQuadPlan.GPU_BYTES_PER_FACE,ack);
    }
    /** Worker-built aggregate validation is not repeated over every face on the GL owner. */
    public static void offerPages(AbyssLodPipeline.Target target,long sequence,SurfaceQuadPlan.PageSet pages,Consumer<DrawAck> ack){
        RenderSystem.assertOnRenderThread();Objects.requireNonNull(pages);
        if(pages.pages().size()>MAX_PAGES)throw new IllegalArgumentException("Surface page count exceeds8192");
        var prepared=new ArrayList<SurfaceQuadPlan.Prepared>(pages.pages().size());for(var page:pages.pages())prepared.add(page.prepared());
        offerPrepared(target,sequence,prepared,pages.faces(),pages.opaqueFaces(),pages.waterFaces(),pages.gpuBytes(),ack);
    }
    private static void offerPrepared(AbyssLodPipeline.Target target,long sequence,List<SurfaceQuadPlan.Prepared> prepared,
                                      int faces,int opaque,int water,long gpuBytes,Consumer<DrawAck> ack){
        Objects.requireNonNull(target);Objects.requireNonNull(ack);
        if(!valid.test(target))return;
        if(faces<=0)throw new IllegalArgumentException("Empty surface cannot certify a positive WORLD draw");
        if(gpuBytes>maxSceneGpuBytes)throw new IllegalArgumentException("Surface GPU budget exceeds bounded exact scene admission");
        GeneratedShaderLightPresentation.prepareFrame();GeneratedShaderMaterialContext.prepareFrame();
        if(candidate!=null&&candidate.target.equals(target)&&candidate.sequence==sequence)return;
        closeCandidate();
        if(displayed!=null&&!sameAuthority(displayed.target,target)){displayed.close();displayed=null;}
        if(pageRegistry==null)throw new IllegalStateException("Surface page renderer was not configured");
        candidate=new Scene(target,sequence,prepared,faces,opaque,water,ack);error="";
    }
    private static boolean sameAuthority(AbyssLodPipeline.Target a,AbyssLodPipeline.Target b){
        return a.session().equals(b.session())&&a.lifetime()==b.lifetime()&&a.sourceIdentity().equals(b.sourceIdentity())
            &&a.sourceRevision()==b.sourceRevision()&&a.resourceRevision()==b.resourceRevision();
    }
    private static void closeCandidate(){if(candidate!=null)candidate.close();candidate=null;}
    public static void clear(){RenderSystem.assertOnRenderThread();closeCandidate();if(displayed!=null)displayed.close();displayed=null;
        valid=unused->false;configured=false;maxSceneGpuBytes=MAX_GPU_BYTES;error="";firstWorldDraw=-1;opaqueSubmitted=waterSubmitted=0;
        shadowError="";shadowSequence=-1;shadowLastFaces=shadowChunksTested=shadowChunksDrawn=shadowChunksCulled=0;
        shadowFramebuffer=shadowDepth=shadowColor0=shadowColor1=0;}

    /** Called only by the owner's public DH BeforeRenderPass event; never registers duplicate handlers. */
    public static void draw(DhApiRenderParam param){
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.SURFACE_DRAW);
        int counts=GeneratedDrawCounters.ENABLED&&(param.renderPass==EDhApiRenderPass.OPAQUE||param.renderPass==EDhApiRenderPass.TRANSPARENT)
                ?GeneratedDrawCounters.enter(param.renderPass==EDhApiRenderPass.OPAQUE?GeneratedDrawCounters.SURFACE_OPAQUE:GeneratedDrawCounters.SURFACE_WATER):-1;
        try{drawMeasured(param);}finally{GeneratedDrawCounters.leave(counts);GeneratedFrameCosts.end(timing);}
    }
    private static void drawMeasured(DhApiRenderParam param){
        RenderSystem.assertOnRenderThread();if(displayed==null&&candidate==null)return;
        if(param.renderPass!=EDhApiRenderPass.OPAQUE&&param.renderPass!=EDhApiRenderPass.TRANSPARENT)return;
        Object program=null;boolean bound=false;Scene drawing=null;int submitted=0;
        try{
            resolve();if(Boolean.TRUE.equals(shadowState.invoke(null))){shadowSkipped++;return;}
            var client=Minecraft.getInstance();if(!ClientDeepRuntime.generatedAccepted(client.level))return;
            GeneratedShaderLightPresentation.prepareFrame();GeneratedShaderMaterialContext.prepareFrame();
            if(candidate!=null&&(!valid.test(candidate.target)||!candidate.resources()))closeCandidate();
            if(displayed!=null&&!displayed.resources()){displayed.close();displayed=null;}
            if(param.renderPass==EDhApiRenderPass.OPAQUE&&candidate!=null)pumpUploads();
            drawing=candidate!=null&&candidate.ready()?candidate:displayed;if(drawing==null)return;
            boolean water=param.renderPass==EDhApiRenderPass.TRANSPARENT;
            if(water?drawing.water==0:drawing.opaque==0)return;
            if(drawing==candidate&&!valid.test(drawing.target))return;
            Object pipeline=((Optional<?>)getPipeline.invoke(getManager.invoke(null))).orElseThrow();
            Object compat=getInstance.invoke(getDhCompat.invoke(pipeline));
            program=(water?getWater:getSolid).invoke(compat);Object fb=(water?getWaterFB:getSolidFB).invoke(compat);
            if(program==null||fb==null)throw new IllegalStateException("Actual Oculus terrain backend unavailable for "+param.renderPass);
            backend=program.getClass().getName();
            try(var state=new GlState()){try{
                // A late event listener still obtains the current complete opaque depth, never a stale texture.
                if(water){int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
                    copyTranslucents.invoke(compat,viewport[2],viewport[3]);}
                bindFramebuffer.invoke(fb);framebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                int expected=(Integer)getFramebufferId.invoke(fb);
                if(framebuffer!=expected||framebuffer==client.getMainRenderTarget().frameBufferId)throw new IllegalStateException("Refusing unrelated main surface FBO");
                depthAttachment=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                if(depthAttachment==0)throw new IllegalStateException("Surface target has no depth attachment");
                RenderSystem.enableDepthTest();RenderSystem.colorMask(true,true,true,true);
                // Match Oculus terrain transforms: captured gameplay view, DH near/far perspective.
                var projection=(Matrix4f)capturedProjection.invoke(capturedState);
                var dhProjection=new Matrix4f().setPerspective(projection.perspectiveFov(),projection.m11()/projection.m00(),param.nearClipPlane,param.farClipPlane);
                bind.invoke(program);bound=true;
                // Vertices and anchors already include global Y; no level-minY offset is applied again.
                fill.invoke(program,dhProjection,capturedView.invoke(capturedState),0,param.partialTicks);
                if(water)RenderSystem.disableCull();else RenderSystem.enableCull();
                var camera=client.gameRenderer.getMainCamera().getPosition();
                List<Mesh> meshes=drawing.meshes;
                if(water)meshes=waterOrder(meshes,camera.x,camera.y,camera.z);
                for(var mesh:meshes){int faces=water?mesh.water:mesh.opaque;if(faces==0)continue;
                    setModel.invoke(program,new DhApiVec3f((float)(mesh.x-camera.x),(float)(mesh.y-camera.y),(float)(mesh.z-camera.z)));
                    GL30.glBindVertexArray(mesh.vao);GL11.glDrawElements(GL11.GL_TRIANGLES,faces*6,GL11.GL_UNSIGNED_SHORT,water?(long)mesh.opaque*12:0L);submitted+=faces;
                    GeneratedDrawCounters.add(GeneratedDrawCounters.DRAWS,1);
                }
                int glError=GL11.glGetError();if(glError!=GL11.GL_NO_ERROR)throw new IllegalStateException("Surface draw GL error "+glError);
                unbind.invoke(program);bound=false;
            }finally{if(bound)try{unbind.invoke(program);}catch(ReflectiveOperationException failure){error=failure.toString();}}}
            if(submitted>0){draws++;long elapsed=elapsed();if(firstWorldDraw<0)firstWorldDraw=elapsed;
                if(drawing.firstDraw<0)drawing.firstDraw=elapsed;
                if(water){waterSubmitted+=submitted;drawing.waterDrawn=true;}else{opaqueSubmitted+=submitted;drawing.opaqueDrawn=true;}
                if(drawing==candidate&&(drawing.opaque==0||drawing.opaqueDrawn)&&(drawing.water==0||drawing.waterDrawn)&&valid.test(drawing.target)){
                    // All buffers were ready before either pass; old complete mesh is retained until real submission proof.
                    Scene old=displayed;displayed=drawing;candidate=null;drawing.acknowledged=true;worldAcks++;
                    if(old!=null)old.close();drawing.ack.accept(new DrawAck(drawing.target,drawing.sequence,drawing.opaque,drawing.water,drawing.firstDraw));
                }
            }
        }catch(ReflectiveOperationException|RuntimeException failure){error=failure.toString();}
    }
    /** Shadow/RSM submission reuses the acknowledged main mesh; never uploads, relights or WORLD-ACKs. */
    public static void drawShadow(RenderLevelStageEvent event){
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.SURFACE_SHADOW);
        int counts=GeneratedDrawCounters.ENABLED&&GeneratedFarShadowRenderer.isShadowPass()?GeneratedDrawCounters.enter(GeneratedDrawCounters.SURFACE_SHADOW):-1;
        try{drawShadowMeasured(event);}finally{GeneratedDrawCounters.leave(counts);GeneratedFrameCosts.end(timing);}
    }
    private static void drawShadowMeasured(RenderLevelStageEvent event){
        RenderSystem.assertOnRenderThread();
        if(!configured||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS||displayed==null||!displayed.acknowledged)return;
        long started=System.nanoTime();Object program=null;boolean bound=false;
        try{
            resolve();if(!Boolean.TRUE.equals(shadowState.invoke(null)))return;
            var client=Minecraft.getInstance();if(!ClientDeepRuntime.generatedAccepted(client.level))return;
            if(!"iterationT 3.2.0.zip".equals(String.valueOf(currentPack.invoke(null)))){
                shadowError="Surface shadow backend supports only IterationT3.2.0";return;}
            GeneratedShaderLightPresentation.prepareFrame();GeneratedShaderMaterialContext.prepareFrame();
            Scene scene=displayed;if(!scene.resources()||scene.opaque==0)return;
            Object pipeline=((Optional<?>)getPipeline.invoke(getManager.invoke(null))).orElseThrow();
            Object compat=getInstance.invoke(getDhCompat.invoke(pipeline));program=getShadow.invoke(compat);Object fb=getShadowFB.invoke(compat);
            if(program==null||fb==null)throw new IllegalStateException("Actual Oculus surface shadow/RSM backend unavailable");
            shadowBackend=program.getClass().getName();
            shadowChunksTested=shadowChunksDrawn=shadowChunksCulled=shadowLastFaces=0;
            Frustum frustum=null;
            try{frustum=(Frustum)shadowFrustum.get(null);shadowFrustumStatus=frustum==null?"UNAVAILABLE_DRAW_ALL":frustum.getClass().getName();}
            catch(ReflectiveOperationException|RuntimeException unavailable){shadowFrustumStatus="FAILED_DRAW_ALL: "+unavailable;}
            if(frustum==null)shadowFrustumFallbacks++;
            // Preflight all culling before drawing. A frustum failure falls back to the complete bounded mesh.
            boolean[] visible=new boolean[scene.meshes.size()];java.util.Arrays.fill(visible,true);
            if(frustum!=null)try{for(int i=0;i<visible.length;i++){var mesh=scene.meshes.get(i);if(mesh.opaque>0){long cull=GeneratedDrawCounters.frustumBegin();try{visible[i]=frustum.isVisible(mesh.bounds);}finally{GeneratedDrawCounters.frustumEnd(cull);}}}}
            catch(RuntimeException failed){java.util.Arrays.fill(visible,true);shadowFrustumFallbacks++;shadowFrustumStatus="FAILED_DRAW_ALL: "+failed;}
            try(var state=new GlState()){try{
                bindFramebuffer.invoke(fb);shadowFramebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                if(shadowFramebuffer!=(Integer)getFramebufferId.invoke(fb)||shadowFramebuffer==client.getMainRenderTarget().frameBufferId)
                    throw new IllegalStateException("Refusing surface shadow without actual Oculus FBO");
                shadowDepth=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                shadowColor0=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                shadowColor1=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                if(shadowDepth==0||shadowColor0==0||shadowColor1==0)throw new IllegalStateException("Surface shadow target lacks depth or RSM attachments");
                RenderSystem.enableDepthTest();RenderSystem.enableCull();RenderSystem.depthMask(true);RenderSystem.colorMask(true,true,true,true);
                bind.invoke(program);bound=true;
                fill.invoke(program,event.getProjectionMatrix(),event.getPoseStack().last().pose(),0,client.getFrameTime());
                var camera=event.getCamera().getPosition();
                for(int i=0;i<scene.meshes.size();i++){var mesh=scene.meshes.get(i);if(mesh.opaque==0)continue;shadowChunksTested++;
                    if(!visible[i]){shadowChunksCulled++;continue;}
                    setModel.invoke(program,new DhApiVec3f((float)(mesh.x-camera.x),(float)(mesh.y-camera.y),(float)(mesh.z-camera.z)));
                    GL30.glBindVertexArray(mesh.vao);
                    // Opaque indices are the first contiguous prefix; WATER is never submitted here.
                    GL11.glDrawElements(GL11.GL_TRIANGLES,mesh.opaque*6,GL11.GL_UNSIGNED_SHORT,0L);
                    GeneratedDrawCounters.add(GeneratedDrawCounters.DRAWS,1);
                    shadowLastFaces+=mesh.opaque;shadowChunksDrawn++;
                }
                int glError=GL11.glGetError();if(glError!=GL11.GL_NO_ERROR)throw new IllegalStateException("Surface shadow GL error "+glError);
                unbind.invoke(program);bound=false;
            }finally{if(bound)unbind.invoke(program);}}
            if(shadowLastFaces>0){shadowDraws++;shadowSubmitted+=shadowLastFaces;shadowSequence=scene.sequence;}shadowError="";
        }catch(ReflectiveOperationException|RuntimeException failure){shadowError=failure.toString();}
        finally{shadowWallMaxNanos=Math.max(shadowWallMaxNanos,System.nanoTime()-started);}
    }
    private static long elapsed(){return originNanos==0?0:Math.max(0,(System.nanoTime()-originNanos)/1_000_000);}
    private static void pumpUploads(){
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.SURFACE_UPLOAD);
        try{pumpUploadsMeasured();}finally{GeneratedFrameCosts.end(timing);}
    }
    private static void pumpUploadsMeasured(){long started=System.nanoTime();var budget=new FrameWorkBudget(System::nanoTime,UPLOAD_NANOS);try{
        while(candidate!=null&&!candidate.ready()){var lease=candidate.pages.get(candidate.pageCursor);var page=lease.value();
            if(!budget.available()||!upload(page,page.chunk,budget))break;
            page.chunk++;uploads++;if(page.ready())lease.markReady();candidate.advance();}
    }catch(RuntimeException failure){closeCandidate();throw failure;}
    finally{uploadBatchMaxNanos=Math.max(uploadBatchMaxNanos,System.nanoTime()-started);}}

    private static boolean upload(GpuPage scene,int chunk,FrameWorkBudget budget){
        var bounds=scene.plan.chunkBounds().get(chunk);long ax=bounds.minX(),ay=bounds.minY(),az=bounds.minZ();
        int start=chunk*SurfaceQuadPlan.CHUNK_FACES,end=Math.min(scene.plan.faces().size(),start+SurfaceQuadPlan.CHUNK_FACES),faces=end-start;
        long bytes=(long)faces*SurfaceQuadPlan.GPU_BYTES_PER_FACE;
        if(scene.bytes+bytes>maxSceneGpuBytes||liveGpuBytes+bytes>maxSceneGpuBytes*2)throw new IllegalArgumentException("Surface live GPU budget exceeded");
        if(scene.pending==null)scene.pending=new Upload(scene.plan,chunk);
        var payload=scene.pending;
        if(payload.chunk!=chunk)throw new IllegalStateException("Surface CPU upload cursor changed chunks");
        int encodeTiming=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.SURFACE_ENCODE);
        try{
            if(!payload.encoded){
                if(!payload.cursor.advance(RESUMABLE_UPLOAD?budget::available:()->true,payload.vertex,payload.index))return false;
                payload.vertices.flip();payload.indices.flip();payload.encoded=true;
            }
        }finally{GeneratedFrameCosts.end(encodeTiming);}
        // A completed CPU payload waits privately if its slice expired; never start a late GL upload.
        if(RESUMABLE_UPLOAD&&!budget.available())return false;
        var vertices=payload.vertices;var indices=payload.indices;
        int vao=0,vbo=0,ebo=0,opaque=payload.cursor.opaque(),water=payload.cursor.water();boolean committed=false;
        int vboTiming=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.SURFACE_VBO);
        try {
        int oldVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),oldArray=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try{
            vao=GL30.glGenVertexArrays();vbo=GL15.glGenBuffers();ebo=GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,vertices,GL15.GL_STATIC_DRAW);
            GL30.glVertexAttribIPointer(0,4,GL11.GL_UNSIGNED_SHORT,16,0L);GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1,4,GL11.GL_UNSIGNED_BYTE,true,16,8L);GL20.glEnableVertexAttribArray(1);
            GL30.glVertexAttribIPointer(2,2,GL11.GL_UNSIGNED_BYTE,16,12L);GL20.glEnableVertexAttribArray(2);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ebo);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,indices,GL15.GL_STATIC_DRAW);
            scene.meshes.add(new Mesh(vao,vbo,ebo,opaque,water,ax,ay,az,(bounds.minX()+bounds.maxX())/2.0,(bounds.minY()+bounds.maxY())/2.0,(bounds.minZ()+bounds.maxZ())/2.0,
                new AABB(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.maxZ()).inflate(1.0/16)));
            scene.bytes+=bytes;liveGpuBytes+=bytes;uploadedGpuBytes+=bytes;scene.opaque+=opaque;scene.water+=water;committed=true;
        }finally{GL30.glBindVertexArray(oldVao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,oldArray);
            if(!committed){if(vao!=0)GL30.glDeleteVertexArrays(vao);if(vbo!=0)GL15.glDeleteBuffers(vbo);if(ebo!=0)GL15.glDeleteBuffers(ebo);}
        }
        return true;
        }finally{scene.pending=null;payload.close();GeneratedFrameCosts.end(vboTiming);}
    }
    private static void putFace(ByteBuffer b,SurfaceQuadPlan.Face f,SurfaceQuadPlan.Owner owner,long ax,long ay,long az){
        int material=Byte.toUnsignedInt(switch(owner.box().material()){
            case ROCK->EDhApiBlockMaterial.STONE.index;case SOIL->EDhApiBlockMaterial.DIRT.index;case GRASS->EDhApiBlockMaterial.GRASS.index;
            case WATER->EDhApiBlockMaterial.WATER.index;default->EDhApiBlockMaterial.AIR.index;});
        int normal=PackedSurfaceVertex.normalIndex(f.axis(),f.positive()),sky=GeneratedShaderLightPresentation.presentSky(owner.sky());
        // Vertex order matches existing tested shadow winding, without per-face coordinate arrays.
        for(int i=0;i<4;i++){
            long u,v;
            if(f.axis()==0){u=(f.positive()?(i>=2):(i<2))?f.u1():f.u0();v=(i==1||i==2)?f.v1():f.v0();}
            else if(f.axis()==1){u=(i==1||i==2)?f.u1():f.u0();v=(f.positive()?(i<2):(i>=2))?f.v1():f.v0();}
            else{u=(f.positive()?(i==1||i==2):(i==0||i==3))?f.u1():f.u0();v=i>=2?f.v1():f.v0();}
            long x=f.axis()==0?f.plane():u,y=f.axis()==1?f.plane():v,z=f.axis()==0?u:f.axis()==1?v:f.plane();
            PackedSurfaceVertex.put(b,ax,ay,az,x,y,z,owner.rgba(),sky,owner.block(),material,normal);
        }
    }
    private static void resolve()throws ReflectiveOperationException{
        if(resolved)return;
        var iris=Class.forName("net.irisshaders.iris.Iris");getManager=iris.getMethod("getPipelineManager");getPipeline=getManager.getReturnType().getMethod("getPipeline");currentPack=iris.getMethod("getCurrentPackName");
        getDhCompat=Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline").getMethod("getDHCompat");getInstance=getDhCompat.getReturnType().getMethod("getInstance");
        var compat=Class.forName("net.irisshaders.iris.compat.dh.DHCompatInternal");getSolid=compat.getMethod("getSolidShader");getWater=compat.getMethod("getTranslucentShader");
        getSolidFB=compat.getMethod("getSolidFB");getWaterFB=compat.getMethod("getTranslucentFB");copyTranslucents=compat.getMethod("copyTranslucents",int.class,int.class);
        getShadow=compat.getMethod("getShadowShader");getShadowFB=compat.getMethod("getShadowFB");
        getFramebufferId=getSolidFB.getReturnType().getMethod("getId");bindFramebuffer=getSolidFB.getReturnType().getMethod("bindAsDrawBuffer");
        var program=getSolid.getReturnType();bind=program.getMethod("bind");unbind=program.getMethod("unbind");
        fill=program.getMethod("fillUniformData",org.joml.Matrix4fc.class,org.joml.Matrix4fc.class,int.class,float.class);setModel=program.getMethod("setModelPos",DhApiVec3f.class);
        shadowState=Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState").getMethod("areShadowsCurrentlyBeingRendered");
        shadowFrustum=Class.forName("net.irisshaders.iris.shadows.ShadowRenderer").getField("FRUSTUM");
        var captured=Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");capturedState=captured.getField("INSTANCE").get(null);
        capturedView=captured.getMethod("getGbufferModelView");capturedProjection=captured.getMethod("getGbufferProjection");resolved=true;
    }
    /** Saves target-specific blending too: Iris may change each G-buffer attachment independently. */
    private static final class GlState implements AutoCloseable {
        final int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),vao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        final int array=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final boolean depth=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),depthTest=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),blend=GL11.glIsEnabled(GL11.GL_BLEND);
        final int depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC),src=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dst=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        final int srcA=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstA=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),eq=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),eqA=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        static final int[] TEXTURE_TARGETS={GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_3D,GL30.GL_TEXTURE_2D_ARRAY,GL13.GL_TEXTURE_CUBE_MAP,GL31.GL_TEXTURE_BUFFER,GL11.GL_TEXTURE_1D,GL32.GL_TEXTURE_2D_MULTISAMPLE};
        static final int[] TEXTURE_BINDINGS={GL11.GL_TEXTURE_BINDING_2D,GL12.GL_TEXTURE_BINDING_3D,GL30.GL_TEXTURE_BINDING_2D_ARRAY,GL13.GL_TEXTURE_BINDING_CUBE_MAP,GL31.GL_TEXTURE_BINDING_BUFFER,GL11.GL_TEXTURE_BINDING_1D,GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE};
        final int[][] textures,images;final int[] samplers,shaderTextures=new int[12];final int[][] targetBlend;final boolean[] targetEnabled;final byte[][] targetMask;
        final net.minecraft.client.renderer.ShaderInstance shader=RenderSystem.getShader();
        GlState(){int units=GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);textures=new int[units][TEXTURE_TARGETS.length];samplers=new int[units];
            for(int i=0;i<12;i++)shaderTextures[i]=RenderSystem.getShaderTexture(i);
            for(int i=0;i<units;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);for(int j=0;j<TEXTURE_BINDINGS.length;j++)textures[i][j]=GL11.glGetInteger(TEXTURE_BINDINGS[j]);samplers[i]=GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);}GL13.glActiveTexture(active);
            int imageCount=GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS);images=new int[imageCount][6];int[] imageKeys={GL42.GL_IMAGE_BINDING_NAME,GL42.GL_IMAGE_BINDING_LEVEL,GL42.GL_IMAGE_BINDING_LAYERED,GL42.GL_IMAGE_BINDING_LAYER,GL42.GL_IMAGE_BINDING_ACCESS,GL42.GL_IMAGE_BINDING_FORMAT};
            for(int i=0;i<imageCount;i++)for(int j=0;j<6;j++)images[i][j]=GL30.glGetIntegeri(imageKeys[j],i);
            int n=GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);targetBlend=new int[n][6];targetEnabled=new boolean[n];targetMask=new byte[n][4];
            try(var stack=MemoryStack.stackPush()){var mask=stack.malloc(4);for(int i=0;i<n;i++){targetEnabled[i]=GL30.glIsEnabledi(GL11.GL_BLEND,i);
                int[] keys={GL14.GL_BLEND_SRC_RGB,GL14.GL_BLEND_DST_RGB,GL14.GL_BLEND_SRC_ALPHA,GL14.GL_BLEND_DST_ALPHA,GL20.GL_BLEND_EQUATION_RGB,GL20.GL_BLEND_EQUATION_ALPHA};
                for(int j=0;j<6;j++)targetBlend[i][j]=GL30.glGetIntegeri(keys[j],i);GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK,i,mask);
                for(int j=0;j<4;j++)targetMask[i][j]=mask.get(j);}}
        }
        @Override public void close(){RenderSystem.depthMask(depth);RenderSystem.depthFunc(depthFunc);if(depthTest)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
            if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();
            GlStateManager._blendFuncSeparate(src,dst,srcA,dstA);GL20.glBlendEquationSeparate(eq,eqA);
            RenderSystem.colorMask(targetMask[0][0]!=0,targetMask[0][1]!=0,targetMask[0][2]!=0,targetMask[0][3]!=0);
            for(int i=0;i<targetBlend.length;i++){if(targetEnabled[i])GL30.glEnablei(GL11.GL_BLEND,i);else GL30.glDisablei(GL11.GL_BLEND,i);var b=targetBlend[i];
                GL40.glBlendFuncSeparatei(i,b[0],b[1],b[2],b[3]);GL40.glBlendEquationSeparatei(i,b[4],b[5]);var m=targetMask[i];GL30.glColorMaski(i,m[0]!=0,m[1]!=0,m[2]!=0,m[3]!=0);}
            for(int i=0;i<textures.length;i++){if(i<12)GlStateManager._activeTexture(GL13.GL_TEXTURE0+i);else GL13.glActiveTexture(GL13.GL_TEXTURE0+i);
                for(int j=0;j<TEXTURE_TARGETS.length;j++)GL11.glBindTexture(TEXTURE_TARGETS[j],textures[i][j]);if(i<12)GlStateManager._bindTexture(textures[i][0]);GL33.glBindSampler(i,samplers[i]);}
            for(int i=0;i<images.length;i++){var image=images[i];GL42.glBindImageTexture(i,image[0],image[1],image[2]!=0,image[3],image[4],image[5]);}
            GL13.glActiveTexture(active);GlStateManager._activeTexture(active);RenderSystem.setShader(()->shader);for(int i=0;i<12;i++)RenderSystem.setShaderTexture(i,shaderTextures[i]);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,array);GlStateManager._glUseProgram(program);
        }
    }
    public static JsonObject status(){var out=new JsonObject();out.addProperty("backend",backend);out.addProperty("error",error);
        out.addProperty("enabled",configured);
        out.addProperty("ownedScenes",(displayed==null?0:1)+(candidate==null?0:1));out.addProperty("maxOwnedScenes",2);
        out.addProperty("uploading",candidate!=null&&!candidate.ready());out.addProperty("pendingChunks",candidate==null?0:candidate.pendingChunks());
        out.addProperty("candidateReady",candidate!=null&&candidate.ready());out.addProperty("candidateSequence",candidate==null?-1:candidate.sequence);
        out.addProperty("displayedSequence",displayed==null?-1:displayed.sequence);out.addProperty("displayedFaces",displayed==null?0:displayed.faces);
        out.addProperty("displayedPages",displayed==null?0:displayed.pages.size());out.addProperty("candidatePages",candidate==null?0:candidate.pages.size());
        out.addProperty("candidateReusedPages",candidate==null?0:candidate.reusedPages);out.addProperty("candidateNewPages",candidate==null?0:candidate.newPages);
        out.addProperty("displayedReusedPages",displayed==null?0:displayed.reusedPages);out.addProperty("displayedNewPages",displayed==null?0:displayed.newPages);
        out.addProperty("liveGpuPages",pageRegistry==null?0:pageRegistry.size());out.addProperty("gpuPageReuseHits",pageRegistry==null?0:pageRegistry.reuses());
        out.addProperty("gpuPageCreations",pageRegistry==null?0:pageRegistry.creations());out.addProperty("gpuPageDisposals",pageRegistry==null?0:pageRegistry.disposals());
        out.addProperty("liveGpuBytes",liveGpuBytes);out.addProperty("uploadedGpuBytes",uploadedGpuBytes);
        out.addProperty("maxSceneGpuBytes",maxSceneGpuBytes);out.addProperty("maxLiveGpuBytes",maxSceneGpuBytes*2);
        out.addProperty("displayedGpuBytes",displayed==null?0:displayed.bytes);out.addProperty("candidateGpuBytes",candidate==null?0:candidate.bytes);
        out.addProperty("opaqueSubmittedFaces",opaqueSubmitted);out.addProperty("waterSubmittedFaces",waterSubmitted);out.addProperty("worldAcks",worldAcks);
        out.addProperty("firstWorldDrawMillis",firstWorldDraw);out.addProperty("draws",draws);out.addProperty("uploads",uploads);out.addProperty("closes",closes);
        out.addProperty("shadowPassesSkipped",shadowSkipped);out.addProperty("drawFramebuffer",framebuffer);out.addProperty("depthAttachment",depthAttachment);
        out.addProperty("uploadBatchMaxMillis",uploadBatchMaxNanos/1_000_000.0);out.addProperty("uploadScope","4096-face GL units; time-sliced CPU encoding; soft owner budget");
        out.addProperty("ownerBudgetMillis",UPLOAD_NANOS/1e6);out.addProperty("resumableSurfaceUpload",RESUMABLE_UPLOAD);
        out.addProperty("pendingCpuBytes",candidate==null||candidate.ready()||candidate.pages.get(candidate.pageCursor).value().pending==null?0:candidate.pages.get(candidate.pageCursor).value().pending.bytes);
        out.addProperty("waterSortScope","Back-to-front immutable chunks; per-face order preserved within a chunk");
        out.addProperty("shadowBackend",shadowBackend);out.addProperty("shadowError",shadowError);out.addProperty("shadowSceneSequence",shadowSequence);
        out.addProperty("shadowUsesDisplayedScene",displayed!=null&&shadowSequence==displayed.sequence);out.addProperty("shadowDraws",shadowDraws);
        out.addProperty("shadowSubmittedFaces",shadowSubmitted);out.addProperty("shadowLastSubmittedFaces",shadowLastFaces);out.addProperty("shadowWaterSubmittedFaces",0);
        out.addProperty("shadowChunksTested",shadowChunksTested);out.addProperty("shadowChunksDrawn",shadowChunksDrawn);out.addProperty("shadowChunksCulled",shadowChunksCulled);
        out.addProperty("shadowFramebuffer",shadowFramebuffer);out.addProperty("shadowDepthAttachment",shadowDepth);out.addProperty("shadowColorAttachment0",shadowColor0);out.addProperty("shadowColorAttachment1",shadowColor1);
        out.addProperty("shadowFrustum",shadowFrustumStatus);out.addProperty("shadowFrustumFallbacks",shadowFrustumFallbacks);out.addProperty("shadowWallMaxMillis",shadowWallMaxNanos/1_000_000.0);
        out.addProperty("shadowGeometryScope","Acknowledged main-scene opaque VBO/EBO reuse; global chunk AABBs; no additional geometry/light/upload");return out;}
}
