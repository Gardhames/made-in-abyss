package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.worldgen.GeneratedSkySource;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;
import dev.madeinabyss.kernel.light.LodBoxSkyLight;
import dev.madeinabyss.kernel.light.ProvisionalSkyPages;
import dev.madeinabyss.kernel.lod.SurfaceProvisionalLight;
import dev.madeinabyss.kernel.lod.SurfaceQuadPlan;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.*;
import java.util.concurrent.*;
import dev.madeinabyss.forge.probe.client.compat.lightgpu.ClientGpuLight;

/** Physical original32 scalar evaluation, independent of render groups, views and cache selection. */
public final class AbyssLodPhysicalLighting implements AbyssLodPipeline.BoxLighting {
    private final GeneratedSkySource source;
    private final String worldFingerprint;
    private final Supplier<String> resourceIdentity;
    private volatile AbyssLodScalarAuthority scalarAuthority;
    private final BoundedReuseCache<BlockCoordinate,Integer> exposure=new BoundedReuseCache<>(1048576,1048576,p->1L);
    private final ProvisionalSkyPages provisionalPages;
    private record LegacyPage(long x,long y,long z){}
    private final BoundedReuseCache<LegacyPage,AbyssLodPipeline.Light> legacyPages=new BoundedReuseCache<>(16384,16384,p->1L);
    private final java.util.concurrent.atomic.LongAdder legacyEvaluations=new java.util.concurrent.atomic.LongAdder();
    private final String probePhase=System.getProperty("mia.lod.provisionalProbePhase","after-ack");
    public AbyssLodPhysicalLighting(GeneratedSkySource source){this(source,null);}
    public AbyssLodPhysicalLighting(GeneratedSkySource source,String worldFingerprint){this(source,worldFingerprint,source::physicalSemanticsIdentity);}
    public AbyssLodPhysicalLighting(GeneratedSkySource source,String worldFingerprint,Supplier<String> resourceIdentity){
        this.source=Objects.requireNonNull(source);this.worldFingerprint=worldFingerprint;this.resourceIdentity=Objects.requireNonNull(resourceIdentity);
        if(!probePhase.equals("sync")&&!probePhase.equals("after-ack"))throw new IllegalArgumentException("provisionalProbePhase must be sync or after-ack");
        provisionalPages=new ProvisionalSkyPages((x,y,z)->y>=source.maxY()?0:net.minecraft.world.level.block.Block.stateById(source.stateIdFast(x,y,z)).isAir()?0:15,
                (x,y,z)->{requireAuthority();int sky=y>=source.maxY()?15:ProvisionalSkyPages.pointSky(x,y,z,source.maxY(),100000,source::opacityFast,source.directSkyFast(x,y,z));requireAuthority();return sky;},
                new ProvisionalSkyPages.View(0,0,0,0,0,1,90,16.0/9),16384,p->p.y()>=source.knownLowerY(p.x(),p.z()),ProvisionalSkyPages.Aggregation.SECOND_BRIGHTEST);
    }
    /** Worker-only: hashes immutable captured physical semantics and edits, never client pack bytes. */
    public AbyssLodScalarAuthority archiveAuthority(String fallbackSourceIdentity){
        requireAuthority();
        var ready=scalarAuthority;if(ready!=null)return ready;
        synchronized(this){if(scalarAuthority==null)scalarAuthority=AbyssLodScalarAuthority.create(
                worldFingerprint==null?fallbackSourceIdentity:worldFingerprint,resourceIdentity.get(),source);return scalarAuthority;}
    }
    @Override public AbyssLodPipeline.Light light(TerrainLodColumns.Box box) {
        if(batchLightEnabled())return lightBatch(List.of(box),this::requireAuthority).get(0);
        return cpuLight(box);
    }
    private AbyssLodPipeline.Light cpuLight(TerrainLodColumns.Box box) {
        requireAuthority();
        int local=source.minKnownYForBox(Math.toIntExact(box.minX()-1),Math.toIntExact(box.minZ()-1),
                Math.toIntExact(box.maxXExclusive()),Math.toIntExact(box.maxZExclusive()));
        int sky=LodBoxSkyLight.maximum(box,local,source::opacityFast,source::directSkyFast,(qx,qy,qz)->{
            var key=new BlockCoordinate(qx,qy,qz);var cached=exposure.get(key);if(cached!=null)return cached;
            int value=source.ambientSkyLod(qx,qy,qz);
            exposure.put(key,value);return value;
        });
        int block=source.emission(source.stateIdFast(Math.toIntExact(box.minX()+(box.maxXExclusive()-box.minX()-1)/2),
                Math.toIntExact(box.minY()+(box.maxYExclusive()-box.minY()-1)/2),
                Math.toIntExact(box.minZ()+(box.maxZExclusive()-box.minZ()-1)/2)));
        requireAuthority();return new AbyssLodPipeline.Light(sky,block);
    }
    @Override public boolean batchLightEnabled(){return ClientGpuLight.enabled();}
    @Override public List<AbyssLodPipeline.Light> lightBatch(List<TerrainLodColumns.Box> owners,Runnable checkpoint){
        if(!batchLightEnabled())return AbyssLodPipeline.BoxLighting.super.lightBatch(owners,checkpoint);
        if(owners.size()>8192){var out=new ArrayList<AbyssLodPipeline.Light>(owners.size());for(int offset=0;offset<owners.size();offset+=8192)out.addAll(lightBatch(owners.subList(offset,Math.min(owners.size(),offset+8192)),checkpoint));return List.copyOf(out);}
        if(owners.isEmpty())return List.of();requireAuthority();checkpoint.run();long packStart=System.nanoTime();int[] packed=new int[Math.multiplyExact(owners.size(),8)];
        for(int i=0;i<owners.size();i++){var b=owners.get(i);int p=i*8;packed[p]=Math.toIntExact(b.minX());packed[p+1]=Math.toIntExact(b.minY());packed[p+2]=Math.toIntExact(b.minZ());packed[p+3]=Math.toIntExact(b.maxXExclusive());packed[p+4]=Math.toIntExact(b.maxYExclusive());packed[p+5]=Math.toIntExact(b.maxZExclusive());packed[p+6]=source.minKnownYForBox(packed[p]-1,packed[p+2]-1,packed[p+3],packed[p+5]);}
        ClientGpuLight.noteOwnerPack(System.nanoTime()-packStart,owners.size());var future=ClientGpuLight.solve(source,worldFingerprint,packed,()->{requireAuthority();checkpoint.run();});int[] sky;long waitStart=System.nanoTime();
        try{
            try{while(true){try{sky=future.get(250,TimeUnit.MILLISECONDS);break;}catch(TimeoutException waiting){requireAuthority();checkpoint.run();}}}
            finally{ClientGpuLight.noteFutureWait(System.nanoTime()-waitStart);}
        }catch(InterruptedException canceled){future.cancel(false);Thread.currentThread().interrupt();throw new CancellationException("GPU exact caller interrupted");}
        catch(CancellationException canceled){future.cancel(false);throw canceled;}
        catch(ExecutionException failed){
            if(failed.getCause() instanceof CancellationException canceled)throw canceled;
            requireAuthority();checkpoint.run();ClientGpuLight.fallback(owners.size(),failed.getCause());
            var out=new ArrayList<AbyssLodPipeline.Light>(owners.size());for(var b:owners){checkpoint.run();out.add(cpuLight(b));}checkpoint.run();requireAuthority();return List.copyOf(out);
        }
        requireAuthority();checkpoint.run();if(sky.length!=owners.size())throw new IllegalStateException("GPU owner result count mismatch");
        long emissionStart=System.nanoTime();try{var out=new ArrayList<AbyssLodPipeline.Light>(owners.size());for(int i=0;i<sky.length;i++){checkpoint.run();out.add(new AbyssLodPipeline.Light(sky[i],emission(owners.get(i))));}requireAuthority();checkpoint.run();return List.copyOf(out);}finally{ClientGpuLight.noteResultEmission(System.nanoTime()-emissionStart);}
    }
    public void requireAuthority(){source.requirePhysicalAuthority();}
    @Override public boolean provisionalLightEnabled(){return source.projection155Enabled()&&Boolean.parseBoolean(System.getProperty("mia.lod.provisionalLight155","true"));}
    /** Original representative until current-target geometry has actually reached WORLD. */
    @Override public AbyssLodPipeline.Light provisionalLight(TerrainLodColumns.Box box){
        requireAuthority();
        var key=new LegacyPage(Math.floorDiv(box.minX(),128),Math.floorDiv(box.minY(),128),Math.floorDiv(box.minZ(),128));
        var representative=legacyPages.get(key);
        if(representative==null){var sampled=cpuLight(box);legacyEvaluations.increment();legacyPages.put(key,sampled);representative=legacyPages.get(key);}
        return new AbyssLodPipeline.Light(representative.sky(),emission(box));
    }
    public boolean probesAfterGeometryAck(){return probePhase.equals("after-ack");}
    public boolean gpuExactReady(){return ClientGpuLight.ready();}
    private int emission(TerrainLodColumns.Box box){
        int block=source.emission(source.stateIdFast(Math.toIntExact(box.minX()+(box.maxXExclusive()-box.minX()-1)/2),
            Math.toIntExact(box.minY()+(box.maxYExclusive()-box.minY()-1)/2),Math.toIntExact(box.minZ()+(box.maxZExclusive()-box.minZ()-1)/2)));
        requireAuthority();return block;
    }
    private AbyssLodPipeline.Light provisionalValue(TerrainLodColumns.Box box){return new AbyssLodPipeline.Light(provisionalPages.sky(box),emission(box));}
    public SurfaceProvisionalLight provisionalLighting(AbyssLodPipeline.Camera camera,java.util.function.ToIntFunction<TerrainLodColumns.Box> colours){
        provisionalPages.view(new ProvisionalSkyPages.View(camera.x(),camera.y(),camera.z(),camera.forwardX(),camera.forwardY(),camera.forwardZ(),camera.fov(),camera.aspect()));
        return new SurfaceProvisionalLight(){
            public void prepare(java.util.List<Page> pages,java.util.concurrent.ExecutorService executor,int parallelism){requireAuthority();provisionalPages.prepare(pages,executor,parallelism);requireAuthority();}
            public Object dependencies(SurfaceQuadPlan.Geometry geometry){return provisionalPages.dependencies(geometry);}
            public long revision(Object dependencies){return provisionalPages.revision(dependencies);}
            public long viewRevision(){return provisionalPages.viewRevision();}
            public int priority(SurfaceQuadPlan.PageKey page){return visiblePage(page,camera)?provisionalPages.priority(page):0;}
            public void noteExact(TerrainLodColumns.Box box,int sky){provisionalPages.noteExact(box,sky);}
            public int skyForAudit(TerrainLodColumns.Box box){return provisionalPages.sky(box);}
            public SurfaceQuadPlan.Owner apply(TerrainLodColumns.Box box){var light=provisionalValue(box);return new SurfaceQuadPlan.Owner(box,light.sky(),light.block(),colours.applyAsInt(box));}
        };
    }
    static boolean visiblePage(SurfaceQuadPlan.PageKey page,AbyssLodPipeline.Camera camera){
        var v=new ProvisionalSkyPages.View(camera.x(),camera.y(),camera.z(),camera.forwardX(),camera.forwardY(),camera.forwardZ(),Math.min(170,camera.fov()+20),camera.aspect());
        return ProvisionalSkyPages.intersectsPage(page,v);
    }
    public boolean visibleAirProbe(AbyssLodPipeline.Camera camera,ProvisionalSkyPages.Point point){
        requireAuthority();if(point.y()<source.knownLowerY(point.x(),point.z())||point.y()>=source.maxY())return false;
        if(!net.minecraft.world.level.block.Block.stateById(source.stateIdFast(point.x(),point.y(),point.z())).isAir())return false;
        boolean open=dev.madeinabyss.kernel.light.SkyVisibility.isOpenSegment((int)Math.floor(camera.x()),(int)Math.floor(camera.y()),(int)Math.floor(camera.z()),
                point.x(),point.y(),point.z(),20000,source::opacityFast);requireAuthority();return open;
    }
    @Override public java.util.Map<String,Object> presentationLightStatus(){var stats=provisionalPages.stats();return java.util.Map.ofEntries(
        java.util.Map.entry("mode","FOUR_OPEN_FACE_PROBES"),java.util.Map.entry("aggregate","SECOND_BRIGHTEST"),java.util.Map.entry("phase",probePhase),java.util.Map.entry("pageMetres",128),
        java.util.Map.entry("pages",stats.pages()),java.util.Map.entry("maximumPages",16384),java.util.Map.entry("evaluations",stats.skyEvaluations()),
        java.util.Map.entry("openChecks",stats.openChecks()),java.util.Map.entry("buildMillis",stats.buildNanos()/1e6),
        java.util.Map.entry("legacyPages",legacyPages.size()),java.util.Map.entry("legacyEvaluations",legacyEvaluations.sum()));}
    @Override public int rgba(TerrainLodColumns.Box box){return dev.madeinabyss.forge.worldgen.LivingAbyssLodColors155.lod(source,box);}
    @Override public long maxSurfaceGpuBytes(){return maxSurfaceGpuBytes(source);}
    public static long maxSurfaceGpuBytes(GeneratedSkySource source){
        var recipe=source==null?null:source.livingRecipe();
        return recipe!=null&&recipe.revision().startsWith("15.5")?AbyssLodPipeline.MAX_SURFACE_GPU_BYTES_155:AbyssLodPipeline.MAX_SURFACE_GPU_BYTES;
    }
    public boolean authorityRetired(){return source.physicalAuthorityRetired();}
    /** Worker-only closure hash; a camera/GPU change cannot invalidate immutable physical scalars. */
    public static String codeIdentity(){return Identity.VALUE;}
    private static final class Identity {
        static final String VALUE=TerrainLodDiskCache.identity("abyss-physical-lodsky-q30-prefix-v3",ClientGpuLight.physicalCodeIdentity(),TerrainLodDiskCache.codeIdentity(
                AbyssLodPhysicalLighting.class,LegacyPage.class,GeneratedSkySource.class,dev.madeinabyss.forge.worldgen.Generated155DerivedProjection.class,LodBoxSkyLight.class,
                dev.madeinabyss.forge.worldgen.Generated155BDerivedProjection.class,
                dev.madeinabyss.forge.worldgen.Generated155ExactView.class,
                dev.madeinabyss.kernel.terrain.living.Exact155ShapeView.class,dev.madeinabyss.kernel.terrain.living.Exact155Math.class,
                dev.madeinabyss.forge.worldgen.LivingAbyssLodColors155.class,
                dev.madeinabyss.forge.worldgen.GeneratedPhysicalSemantics.class,
                dev.madeinabyss.forge.worldgen.GeneratedPhysicalSemantics.Snapshot.class,
                dev.madeinabyss.forge.worldgen.GeneratedPhysicalSemantics.State.class,
                dev.madeinabyss.forge.worldgen.ParameterizedTerrainChunkGenerator.class,
                dev.madeinabyss.kernel.terrain.CompiledTerrainSampler.class,
                dev.madeinabyss.kernel.light.SkyVisibility.class,dev.madeinabyss.kernel.light.QuantizedDiffuseSky.class,
                dev.madeinabyss.kernel.light.DenseOpacityVolume.class,
                dev.madeinabyss.forge.worldgen.GeneratedIslandAirCertificate.class,
                dev.madeinabyss.forge.worldgen.GeneratedDeepAirCertificate.class,
                dev.madeinabyss.forge.worldgen.GeneratedDeepAirCellCertificate.class,
                dev.madeinabyss.kernel.light.DeepCavityAirBounds.class,
                dev.madeinabyss.kernel.light.DeepCavityAirCellBounds.class),dev.madeinabyss.forge.worldgen.Generated155ExactView.codeIdentity(),
                dev.madeinabyss.forge.worldgen.GeneratedCaveOpacityProjection.proofCodeIdentity());
    }
}
