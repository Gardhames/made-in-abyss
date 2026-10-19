package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.NativeLightRevision;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.NativeFluidBudget;
import dev.madeinabyss.kernel.light.CubeBlockLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Native water tessellation of immutable known inputs. FULL_BRIGHT is an explicit fixture diagnostic. */
@Mod.EventBusSubscriber(modid = MadeInAbyssProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DeepFluidRenderer {
    private static final RenderType TRANSLUCENT = RenderType.translucent();
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new BufferBuilder(262144));
    private static final int AIR_STATE_ID=Block.getId(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    private static final int MAX_NATIVE_INPUTS = 36;
    private static volatile long resources;
    private static long seenResources = -1, rebuilds;
    private static List<WaterBlock> mesh = List.of();
    private static Map<CubeKey,List<WaterBlock>> meshByCube=Map.of();
    private static int meshBudget,drawBatches;
    private static long lastDrawNanos,maxDrawNanos;
    private static Map<CubeKey, NativeStamp> nativeInputs = Map.of();
    private static int meshFaces, unknownBlocks, drawnFaces, drawnBlocks;
    private static double submittedMinY = Double.NaN, submittedMaxY = Double.NaN;
    private static String error = "";
    private static boolean capped, blockMode;

    private record Vertex(double x, double y, double z, int r, int g, int b, int a,
                          float u, float v, int light, float nx, float ny, float nz) { }
    private record Quad(List<Vertex> vertices) { }
    private record WaterBlock(CubeKey key, BlockPos position, BlockState materialState, List<Quad> quads) { }
    private record Submission(WaterBlock block, Quad quad, double distance) { }
    private record NativeStamp(LevelChunk chunk, LevelChunkSection section, long revision, boolean ready) { }

    private DeepFluidRenderer() { }

    @SubscribeEvent
    public static void resources(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> resources++);
    }

    static long resourceRevision(){return resources;}
    public static boolean resourcesChanged(){return resources!=seenResources;}
    public static boolean inputsChanged(ClientLevel level) {
        if (resources != seenResources) return true;
        for (var entry : nativeInputs.entrySet())
            if (!entry.getValue().equals(stamp(level, entry.getKey()))) return true;
        return false;
    }

    public static void clear() {
        mesh = List.of(); meshByCube=Map.of(); nativeInputs = Map.of(); meshFaces = unknownBlocks = 0; meshBudget=0;
        capped = false; error = ""; seenResources = -1; resetFrame();
    }

    public static void resetFrame() {
        drawnFaces = drawnBlocks = drawBatches = 0; submittedMinY = submittedMaxY = Double.NaN;
    }

    public static void rebuild(Minecraft client, Map<CubeKey, int[]> snapshots, List<CubeKey> ordered,
                               CubeBlockLight.Result light, boolean calculatedBlock, int faceBudget) {
        mesh = List.of(); meshByCube=Map.of(); meshBudget=faceBudget; meshFaces = unknownBlocks = 0; error = ""; capped = false;
        blockMode = calculatedBlock; seenResources = resources; rebuilds++;
        var view = new View(client.level, snapshots, light, calculatedBlock);
        var blocks = new ArrayList<WaterBlock>();
        try {
            build:
            for (CubeKey key : ordered) {
                int[] states = snapshots.get(key);
                for (int index = 0; index < states.length; index++) {
                    if(states[index]==AIR_STATE_ID)continue;
                    var state = Block.BLOCK_STATE_REGISTRY.byId(states[index]);
                    if (state == null) throw new IllegalStateException("Unknown fluid mesh registry state");
                    var fluid = state.getFluidState();
                    if (fluid.isEmpty()) continue;
                    long x = (long)key.x()*16+(index&15), y=(long)key.y()*16+(index>>>8), z=(long)key.z()*16+((index>>>4)&15);
                    if (x<Integer.MIN_VALUE || x>Integer.MAX_VALUE || z<Integer.MIN_VALUE || z>Integer.MAX_VALUE
                            || y<DeepCubeDomain.TECHNICAL_BOTTOM || y>=(ClientDeepRuntime.generatedAccepted(client.level)?client.level.getMaxBuildHeight():client.level.getMinBuildHeight())) continue;
                    if (fluid.getType()!=Fluids.WATER && fluid.getType()!=Fluids.FLOWING_WATER) {
                        unknownBlocks++; if(error.isEmpty())error="Unsupported fluid type in bounded water renderer"; continue;
                    }
                    var position=new BlockPos((int)x,(int)y,(int)z);
                    try {
                        if (calculatedBlock && light==null && !ClientDeepRuntime.generatedAccepted(client.level)) throw new Unknown("Calculated fluid BLOCK requires immutable visual light batch; physical batch unsupported");
                        // Fail before native callbacks; the strict view also guards any callback beyond this halo.
                        for(int dy=-2;dy<=2;dy++)for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)
                            view.getBlockState(position.offset(dx,dy,dz));
                        var capture=new Capture();
                        client.getBlockRenderer().renderLiquid(position,view,capture,state,fluid);
                        List<Quad> quads=capture.quads();
                        if(meshFaces+quads.size()>faceBudget){capped=true;break build;}
                        if(!quads.isEmpty()){blocks.add(new WaterBlock(key,position,fluid.createLegacyBlock(),quads));meshFaces+=quads.size();}
                    } catch(RuntimeException unavailable) {
                        unknownBlocks++; if(error.isEmpty())error=describe(unavailable);
                    }
                }
            }
            for(var entry:view.dependencies.entrySet()) if(!entry.getValue().equals(stamp(client.level,entry.getKey())))
                throw new Unknown("Native fluid inputs changed during tessellation");
            if(resources!=seenResources)throw new Unknown("Fluid resources changed during tessellation");
            mesh=List.copyOf(blocks);meshByCube=index(mesh);
        } catch(RuntimeException failure) {
            mesh=List.of();meshFaces=0;error=describe(failure);
        } finally { nativeInputs=Map.copyOf(view.dependencies); }
    }

    /** Collected alongside solid CPU models; no second full-volume scan at commit. */
    static final class GeneratedCandidate {
        final Minecraft client;final View view;final long resourceStamp;final List<WaterBlock> blocks=new ArrayList<>();
        final int faceBudget;final Map<CubeKey,List<WaterBlock>> byCube=new HashMap<>();
        int faces;String failure="";
        GeneratedCandidate(Minecraft client,Map<CubeKey,int[]> snapshots,ClientGeneratedLight.NativeSnapshot light) {
            this.client=client;faceBudget=NativeFluidBudget.facesForRadius(light.radius());resourceStamp=resources;
            long headroom=Runtime.getRuntime().maxMemory()-Runtime.getRuntime().totalMemory()+Runtime.getRuntime().freeMemory();
            long required=faceBudget*576L+NativeFluidBudget.maximumBatchBytes()+32L*1024*1024;
            if(headroom<required)throw new Unknown("Fluid memory admission refused: need "+required+", available "+headroom);
            view=new View(client.level,snapshots,null,false);view.generatedSnapshot=light;
        }
        void observe(CubeKey key,int index,BlockState state) {
            var fluid=state.getFluidState();if(fluid.isEmpty())return;
            if(fluid.getType()!=Fluids.WATER&&fluid.getType()!=Fluids.FLOWING_WATER)throw new Unknown("Unsupported generated fluid type");
            var position=new BlockPos(key.x()*16+(index&15),key.y()*16+(index>>>8),key.z()*16+((index>>>4)&15));
            for(int dy=-2;dy<=2;dy++)for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)view.getBlockState(position.offset(dx,dy,dz));
            var capture=new Capture();client.getBlockRenderer().renderLiquid(position,view,capture,state,fluid);
            var quads=capture.quads();int next=NativeFluidBudget.add(faces,quads.size(),faceBudget);
            if(!quads.isEmpty()){var block=new WaterBlock(key,position,fluid.createLegacyBlock(),quads);blocks.add(block);byCube.computeIfAbsent(key,k->new ArrayList<>()).add(block);faces=next;}
        }
        void reuseCube(CubeKey key) {
            var old=meshByCube.get(key);if(old==null)return;
            int incoming=0;for(var block:old)incoming=Math.addExact(incoming,block.quads().size());
            int next=NativeFluidBudget.add(faces,incoming,faceBudget);
            blocks.addAll(old);byCube.put(key,old);faces=next;
        }
        void publish() {
            if(resources!=resourceStamp)throw new Unknown("Fluid resources changed before atomic publication");
            mesh=List.copyOf(blocks);meshByCube=freezeIndex(byCube);meshBudget=faceBudget;meshFaces=faces;unknownBlocks=0;error="";capped=false;blockMode=false;
            seenResources=resources;nativeInputs=Map.of();rebuilds++;
        }
    }

    private static Map<CubeKey,List<WaterBlock>> index(List<WaterBlock> blocks){
        var result=new HashMap<CubeKey,List<WaterBlock>>();
        for(var block:blocks)result.computeIfAbsent(block.key(),k->new ArrayList<>()).add(block);
        return freezeIndex(result);
    }
    private static Map<CubeKey,List<WaterBlock>> freezeIndex(Map<CubeKey,List<WaterBlock>> input){
        var result=new HashMap<CubeKey,List<WaterBlock>>();input.forEach((key,blocks)->result.put(key,List.copyOf(blocks)));return Map.copyOf(result);
    }
    static boolean cutsCoverWater(java.util.List<dev.madeinabyss.kernel.lod.TerrainLodClipper.BoundsLong> cuts){
        for(var block:mesh){var p=block.position();boolean covered=false;
            for(var c:cuts)if(p.getX()>=c.minX()&&p.getX()+1<=c.maxXExclusive()&&p.getY()>=c.minY()&&p.getY()+1<=c.maxYExclusive()&&p.getZ()>=c.minZ()&&p.getZ()+1<=c.maxZExclusive()){covered=true;break;}
            if(!covered)return false;
        }return true;
    }
    private static String describe(Throwable failure) {
        var result=new StringBuilder();
        for(int depth=0;failure!=null&&depth<5;depth++,failure=failure.getCause()) {
            if(depth>0)result.append(" caused by ");result.append(failure);
            var trace=failure.getStackTrace();for(int i=0;i<Math.min(3,trace.length);i++)result.append(" at ").append(trace[i]);
        }
        return result.toString();
    }

    public static void draw(RenderLevelStageEvent event, ClientLevel level, int radius, int rangeDown, int rangeUp, boolean visual) {
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.FLUID_DRAW);
        try{drawMeasured(event,level,radius,rangeDown,rangeUp,visual);}finally{GeneratedFrameCosts.end(timing);}
    }
    private static void drawMeasured(RenderLevelStageEvent event, ClientLevel level, int radius, int rangeDown, int rangeUp, boolean visual) {
        resetFrame(); var camera=event.getCamera().getPosition();
        if(!Boolean.getBoolean("mia.cube.playerProbe") || level==null || DeepCubeDomain.space(level).isEmpty()
                || !Double.isFinite(camera.x) || !Double.isFinite(camera.y) || !Double.isFinite(camera.z) || (camera.y>=level.getMinBuildHeight() && !ClientDeepRuntime.generatedAccepted(level)))return;
        double minY=Math.max(DeepCubeDomain.TECHNICAL_BOTTOM,camera.y-rangeDown),maxY=Math.min(ClientDeepRuntime.generatedAccepted(level)?level.getMaxBuildHeight():level.getMinBuildHeight(),camera.y+rangeUp);
        var committed=ClientDeepRuntime.generatedAccepted(level)?DeepCubeRenderer.nativeCoverage():null;
        if(ClientDeepRuntime.generatedAccepted(level)) {
            if(committed==null)return;
            minY=committed.minY();maxY=committed.maxY();radius=committed.radius();
        }
        long drawStarted=System.nanoTime();
        var submissions=new ArrayList<Submission>();
        int count=0;double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        for(var block:mesh){
            var pos=block.position();double dx=pos.getX()+.5-(committed==null?camera.x:committed.anchorX()),dz=pos.getZ()+.5-(committed==null?camera.z:committed.anchorZ());
            if(pos.getY()+1<minY || pos.getY()>maxY || !DeepCubeRenderer.nearDraw()&&dx*dx+dz*dz>radius*radius
                    || !event.getFrustum().isVisible(new AABB(pos)))continue;
            double ox=(long)block.key().x()*16,oy=(long)block.key().y()*16,oz=(long)block.key().z()*16;
            for(var quad:block.quads()){
                double x=0,y=0,z=0;for(var vertex:quad.vertices()){x+=vertex.x();y+=vertex.y();z+=vertex.z();}
                x=x/4+ox-camera.x;y=y/4+oy-camera.y;z=z/4+oz-camera.z;
                submissions.add(new Submission(block,quad,x*x+y*y+z*z));
            }
            count++;low=Math.min(low,pos.getY());high=Math.max(high,pos.getY()+1);
        }
        submissions.sort(Comparator.comparingDouble(Submission::distance).reversed());
        if(submissions.isEmpty())return;
        var poses=event.getPoseStack();var consumer=BUFFERS.getBuffer(TRANSLUCENT);
        boolean generated=ClientDeepRuntime.generatedAccepted(level);
        float fogStart=RenderSystem.getShaderFogStart(),fogEnd=RenderSystem.getShaderFogEnd();
        int unflushedFaces=0;
        var drawnCubeFaces=new HashMap<CubeKey,Integer>();
        try {
            if(visual){RenderSystem.setShaderFogStart(Math.max(fogStart,rangeDown));RenderSystem.setShaderFogEnd(Math.max(fogEnd,rangeDown+rangeUp));}
            int batchFaces=0;
            for(var submission:submissions){
                if(batchFaces==NativeFluidBudget.UPLOAD_BATCH_FACES){BUFFERS.endBatch(TRANSLUCENT);
                    if(event.getStage()==RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)drawnCubeFaces.forEach((key,faces)->DeepCubeRenderer.nativeWorldDraw(true,faces,key));
                    drawnCubeFaces.clear();
                    unflushedFaces=0;drawBatches++;consumer=BUFFERS.getBuffer(TRANSLUCENT);batchFaces=0;}
                var key=submission.block().key();poses.pushPose();
                var position=submission.block().position();
                boolean materialContext=generated && GeneratedShaderMaterialContext.beginFluid(consumer,
                        submission.block().materialState(),position.getX(),position.getY(),position.getZ());
                try{
                    // Native liquid vertices contain XYZ&15. Translation is the signed section origin, not block position.
                    poses.translate((long)key.x()*16-camera.x,(long)key.y()*16-camera.y,(long)key.z()*16-camera.z);
                    for(var vertex:submission.quad().vertices())consumer.vertex(poses.last().pose(),(float)vertex.x(),(float)vertex.y(),(float)vertex.z())
                            .color(vertex.r(),vertex.g(),vertex.b(),vertex.a()).uv(vertex.u(),vertex.v())
                            .uv2(generated?GeneratedShaderLightPresentation.presentPacked(vertex.light()):vertex.light())
                            .normal(poses.last().normal(),vertex.nx(),vertex.ny(),vertex.nz()).endVertex();
                } finally{if(materialContext)GeneratedShaderMaterialContext.end(consumer);poses.popPose();}
                batchFaces++;unflushedFaces++;
                drawnCubeFaces.merge(key,1,Integer::sum);
            }
            drawnFaces=submissions.size();drawnBlocks=count;drawBatches++;submittedMinY=low;submittedMaxY=high;
        } finally {
            try{BUFFERS.endBatch(TRANSLUCENT);
                if(event.getStage()==RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)drawnCubeFaces.forEach((key,faces)->DeepCubeRenderer.nativeWorldDraw(true,faces,key));
            }finally{RenderSystem.setShaderFogStart(fogStart);RenderSystem.setShaderFogEnd(fogEnd);lastDrawNanos=System.nanoTime()-drawStarted;maxDrawNanos=Math.max(maxDrawNanos,lastDrawNanos);}
        }
    }

    public static void status(JsonObject report, boolean current) {
        report.addProperty("fluidMeshFaces",current?meshFaces:0);report.addProperty("fluidMeshBlocks",current?mesh.size():0);
        report.addProperty("fluidDrawnFaces",current?drawnFaces:0);report.addProperty("fluidDrawnBlocks",current?drawnBlocks:0);
        report.addProperty("fluidUnknownBlocks",current?unknownBlocks:0);report.addProperty("fluidError",current?error:"");
        report.addProperty("fluidFaceBudget",current?meshBudget:0);report.addProperty("fluidMaximumFaces",NativeFluidBudget.MAX_FACES);
        report.addProperty("fluidUploadBatchFaces",NativeFluidBudget.UPLOAD_BATCH_FACES);report.addProperty("fluidDrawBatches",current?drawBatches:0);
        report.addProperty("fluidLastDrawMillis",lastDrawNanos/1_000_000.0);report.addProperty("fluidMaxDrawMillis",maxDrawNanos/1_000_000.0);
        report.addProperty("fluidMaxRetainedCpuEstimateBytes",NativeFluidBudget.maximumRetainedCpuBytes());
        report.addProperty("fluidMaxUploadBatchEstimateBytes",NativeFluidBudget.maximumBatchBytes());
        report.addProperty("fluidCap",current&&capped);report.addProperty("fluidRebuilds",rebuilds);
        boolean generated=ClientDeepRuntime.generatedAccepted(Minecraft.getInstance().level);
        report.addProperty("fluidLightMode",generated?"GENERATED_SKY_BLOCK_IMMUTABLE":blockMode?"BLOCK_ONLY_IMMUTABLE_VISUAL":"FULL_BRIGHT_DIAGNOSTIC");
        report.addProperty("fluidTintMode",generated?"FIXED_PLAINS_NATIVE_COLOR":"CACHED_NATIVE_NOISE_BIOME_UNBLENDED");
        if(current&&drawnFaces>0){report.addProperty("fluidSubmittedMinY",submittedMinY);report.addProperty("fluidSubmittedMaxY",submittedMaxY);}
    }

    private static NativeStamp stamp(ClientLevel level,CubeKey key) {
        var chunk=(LevelChunk)level.getChunkSource().getChunk(key.x(),key.z(),ChunkStatus.FULL,false);
        if(chunk==null)return new NativeStamp(null,null,-1,false);
        int sectionIndex=chunk.getSectionIndex(key.y()*16);
        if(sectionIndex<0 || sectionIndex>=chunk.getSections().length)return new NativeStamp(chunk,null,-1,false);
        var section=chunk.getSection(sectionIndex);
        if(!(section instanceof NativeLightRevision version))return new NativeStamp(chunk,section,-1,false);
        return new NativeStamp(chunk,section,version.mia$lightContentRevision(),version.mia$lightInputReady());
    }

    private static final class View implements BlockAndTintGetter {
        private final ClientLevel level;private final Map<CubeKey,int[]> snapshots;private final CubeBlockLight.Result light;private final boolean block;private ClientGeneratedLight.NativeSnapshot generatedSnapshot;
        private final Map<CubeKey,BlockState[]> nativeStates=new HashMap<>();
        private final Map<CubeKey,NativeStamp> dependencies=new HashMap<>();
        View(ClientLevel level,Map<CubeKey,int[]> snapshots,CubeBlockLight.Result light,boolean block){this.level=level;this.snapshots=snapshots;this.light=light;this.block=block;}
        private NativeStamp requireNative(CubeKey key){
            var result=dependencies.get(key);
            if(result==null){if(dependencies.size()>=MAX_NATIVE_INPUTS)throw new Unknown("Native fluid input budget exceeded");result=stamp(level,key);dependencies.put(key,result);}
            if(!result.ready())throw new Unknown("Unknown cached native fluid input "+key);return result;
        }
        @Override public BlockState getBlockState(BlockPos pos){
            var key=new CubeKey(Math.floorDiv(pos.getX(),16),Math.floorDiv(pos.getY(),16),Math.floorDiv(pos.getZ(),16));
            int index=(pos.getY()&15)<<8|(pos.getZ()&15)<<4|(pos.getX()&15);
            if(pos.getY()<level.getMinBuildHeight() || ClientDeepRuntime.generatedAccepted(level)){
                if(generatedSnapshot!=null) {
                    int id=generatedSnapshot.neighborState(pos.getX(),pos.getY(),pos.getZ());
                    var state=Block.BLOCK_STATE_REGISTRY.byId(id);if(state==null)throw new Unknown("Unknown fluid palette/geometry state");return state;
                }
                int[] states=snapshots.get(key);if(states==null)throw new Unknown("Unknown fluid geometry "+pos);
                var state=Block.BLOCK_STATE_REGISTRY.byId(states[index]);if(state==null)throw new Unknown("Unknown fluid palette state");return state;
            }
            var states=nativeStates.get(key);
            if(states==null){var section=requireNative(key).section();states=new BlockState[4096];for(int i=0;i<4096;i++)states[i]=section.getBlockState(i&15,i>>>8,(i>>>4)&15);nativeStates.put(key,states);}
            return states[index];
        }
        @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        @Override public BlockEntity getBlockEntity(BlockPos pos){if(getBlockState(pos).hasBlockEntity())throw new Unknown("Fluid renderer block entity callback unsupported");return null;}
        @Override public int getMinBuildHeight(){return DeepCubeDomain.TECHNICAL_BOTTOM;}
        @Override public int getHeight(){return level.getMaxBuildHeight()-getMinBuildHeight();}
        @Override public float getShade(Direction direction,boolean shade){return level.getShade(direction,shade);}
        @Override public LevelLightEngine getLightEngine(){throw new Unknown("Fluid renderer light-engine access unsupported");}
        @Override public int getBrightness(LightLayer layer,BlockPos pos){
            if(ClientDeepRuntime.generatedAccepted(level)){int packed=(generatedSnapshot==null?ClientGeneratedLight.packedLight(pos.getX(),pos.getY(),pos.getZ()):generatedSnapshot.packed(pos.getX(),pos.getY(),pos.getZ())).orElseThrow(()->new Unknown("Unknown generated fluid light"));return (packed >>> (layer==LightLayer.SKY?20:4))&15;}
            if(!block)return 15; // Declared FULL_BRIGHT fixture diagnostic, never a calculated-light fallback.
            if(layer==LightLayer.SKY)return 0; // Explicit BLOCK-only channel; SKY simulation is not claimed.
            if(light==null)throw new Unknown("Missing immutable fluid BLOCK batch");
            return light.value(new BlockCoordinate(pos.getX(),pos.getY(),pos.getZ())).orElseThrow(()->new Unknown("Unknown fluid BLOCK sample "+pos));
        }
        @Override public int getBlockTint(BlockPos pos,ColorResolver resolver){
            if(ClientDeepRuntime.generatedAccepted(level)){
                var original=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value();
                var source=ClientGeneratedLight.source();var recipe=source==null?null:source.livingRecipe();
                var biome=recipe!=null&&dev.madeinabyss.forge.worldgen.LivingAbyssColors.inShaft(pos.getX(),pos.getY(),pos.getZ())?dev.madeinabyss.forge.worldgen.LivingAbyssColors.biome(original,recipe.biomeColors()):original;
                return resolver.getColor(biome,pos.getX(),pos.getZ());}
            // Cube biomes are not implemented. Use actual cached native biome data, with native vertical clamp; no guessed tint or autoload.
            int y=Math.max(level.getMinBuildHeight(),Math.min(level.getMaxBuildHeight()-1,pos.getY()));
            var input=requireNative(new CubeKey(Math.floorDiv(pos.getX(),16),Math.floorDiv(y,16),Math.floorDiv(pos.getZ(),16)));
            return resolver.getColor(input.chunk().getNoiseBiome(Math.floorDiv(pos.getX(),4),Math.floorDiv(y,4),Math.floorDiv(pos.getZ(),4)).value(),pos.getX(),pos.getZ());
        }
    }

    private static final class Capture implements VertexConsumer {
        private final List<Vertex> vertices=new ArrayList<>();private double x,y,z;private int r=255,g=255,b=255,a=255,light;
        private float u,v,nx,ny,nz;private boolean positioned,colored,textured,lit,normal;
        @Override public VertexConsumer vertex(double x,double y,double z){this.x=x;this.y=y;this.z=z;positioned=true;return this;}
        @Override public VertexConsumer color(int r,int g,int b,int a){this.r=r;this.g=g;this.b=b;this.a=a;colored=true;return this;}
        @Override public VertexConsumer uv(float u,float v){this.u=u;this.v=v;textured=true;return this;}
        @Override public VertexConsumer overlayCoords(int u,int v){return this;}
        @Override public VertexConsumer uv2(int u,int v){light=(u&65535)|((v&65535)<<16);lit=true;return this;}
        @Override public VertexConsumer normal(float x,float y,float z){nx=x;ny=y;nz=z;normal=true;return this;}
        @Override public void endVertex(){
            if(!positioned||!colored||!textured||!lit||!normal || !Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                    || x<-.01 || x>16.01 || y<-.01 || y>16.01 || z<-.01 || z>16.01
                    || !Float.isFinite(u)||!Float.isFinite(v)||!Float.isFinite(nx)||!Float.isFinite(ny)||!Float.isFinite(nz)||vertices.size()>=64)
                throw new Unknown("Malformed or excessive native fluid vertex capture");
            vertices.add(new Vertex(x,y,z,r,g,b,a,u,v,light,nx,ny,nz));positioned=colored=textured=lit=normal=false;
        }
        @Override public void defaultColor(int r,int g,int b,int a){throw new Unknown("Native fluid default-color capture unsupported");}
        @Override public void unsetDefaultColor(){ }
        List<Quad> quads(){if(vertices.size()%4!=0 || positioned||colored||textured||lit||normal)throw new Unknown("Incomplete native fluid quad");var result=new ArrayList<Quad>();for(int i=0;i<vertices.size();i+=4)result.add(new Quad(List.copyOf(vertices.subList(i,i+4))));return List.copyOf(result);}
    }

    private static final class Unknown extends IllegalStateException {Unknown(String message){super(message);}}
}
