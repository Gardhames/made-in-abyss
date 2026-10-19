package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.ExactXYZStateCache;
import dev.madeinabyss.kernel.cube.ExactCubeTileCache;
import dev.madeinabyss.kernel.light.SkyColumnKey;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Immutable generated geometry and authoritative visited overrides for derived sky columns. */
public final class GeneratedSkySource {
    public static final int MAX_SKY_COLUMNS=131072;
    private final ParameterizedTerrainChunkGenerator generator;
    private final GeneratedIslandAirCertificate islandAirCertificate;
    private final GeneratedDeepAirCertificate deepAirCertificate;
    private final GeneratedDeepAirCellCertificate deepAirCellCertificate;
    private final GeneratedCaveOpacityProjection caveOpacityProjection;
    private final Generated155DerivedProjection projection155;
    private final boolean caveProjectionEligible;
    private final dev.madeinabyss.kernel.concurrent.BoundedReuseCache<Probe,Double> recentVisibility155;
    private final java.util.concurrent.atomic.LongAdder projection155Hits=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder stateProjection155Hits=new java.util.concurrent.atomic.LongAdder();
    private final boolean caveOpacityProjectionRequested=Boolean.parseBoolean(System.getProperty("mia.generated.caveOpacityProjection","true"));
    private final java.util.concurrent.atomic.LongAdder caveOpacityProjectionCoreSamples=new java.util.concurrent.atomic.LongAdder(),caveOpacityProjectionRockHits=new java.util.concurrent.atomic.LongAdder(),caveOpacityProjectionSoilHits=new java.util.concurrent.atomic.LongAdder(),caveOpacityProjectionGrassHits=new java.util.concurrent.atomic.LongAdder(),caveOpacityProjectionContinued=new java.util.concurrent.atomic.LongAdder(),caveOpacityProjectionRefusals=new java.util.concurrent.atomic.LongAdder();
    private final Map<CubeKey,int[]> overrides;
    private final int minY,maxY,air;
    private final Map<Integer,Integer> opacity,emission;
    private final byte[] indexedOpacity,indexedEmission;
    private volatile String cacheGeometryIdentity;
    private volatile CacheAuthority cacheAuthority;
    /** Immutable derived-cache metadata; the global geometry identity remains its existing bytes. */
    public record CacheAuthority(String baseGeometryIdentity,String globalRevision,Map<CubeKey,String> editedCubeDigests) {
        public CacheAuthority {
            java.util.Objects.requireNonNull(baseGeometryIdentity);java.util.Objects.requireNonNull(globalRevision);
            editedCubeDigests=Map.copyOf(editedCubeDigests);
        }
    }
    private final Map<Integer,String> paletteIdentities;
    private final GeneratedPhysicalSemantics.Snapshot physicalSemantics;
    private volatile boolean physicalAuthorityRetired;
    // Geometry and physical authority are immutable. These controls affect derived reuse only.
    private final boolean derivedCachesBypassed;
    private final boolean uniformAirCubeSkipEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.uniformAirCubeSkip","true"));
    private volatile boolean derivedDiskRestoreDisabled;
    private final boolean exactTilesRequested=Boolean.parseBoolean(System.getProperty("mia.generated.exactTiles","true"));
    private final ExactCubeTileCache exactTiles=new ExactCubeTileCache(ExactCubeTileCache.DEFAULT_MAX_BYTES,ExactCubeTileCache.DEFAULT_MAX_TILES);
    private final java.util.concurrent.atomic.LongAdder exactTileCursorHits=new java.util.concurrent.atomic.LongAdder(),exactTileBoundaryFallbacks=new java.util.concurrent.atomic.LongAdder();
    private static final class TileCursor {int x,y,z;ExactCubeTileCache.Tile tile;}
    // Like the old ray memos, values retain neither this source nor a sampling callback.
    private final ThreadLocal<TileCursor> tileCursor=ThreadLocal.withInitial(TileCursor::new);
    private final ThreadLocal<Runnable> requestCheckpoints=new ThreadLocal<>();
    public static final class CheckpointScope implements AutoCloseable {
        private final Thread owner=Thread.currentThread();
        private final ThreadLocal<Runnable> checkpoints;
        private final Runnable previous,installed;
        private boolean closed;
        private CheckpointScope(ThreadLocal<Runnable> checkpoints,Runnable installed){this.checkpoints=checkpoints;this.installed=installed;previous=checkpoints.get();checkpoints.set(installed);}
        @Override public void close(){
            if(closed)return;
            if(owner!=Thread.currentThread()||checkpoints.get()!=installed)throw new IllegalStateException("Generated request checkpoint scopes must close in order on their owner thread");
            closed=true;if(previous==null)checkpoints.remove();else checkpoints.set(previous);
        }
    }
    /** Install separately on each phase worker; closing releases the target callback. */
    public CheckpointScope requestCheckpoint(Runnable checkpoint){return new CheckpointScope(requestCheckpoints,java.util.Objects.requireNonNull(checkpoint));}
    private void checkRequest(){
        requirePhysicalAuthority();
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Interrupted generated geometry request");
        var checkpoint=requestCheckpoints.get();if(checkpoint!=null)checkpoint.run();
    }
    /** A true cold benchmark starts with this new source's empty RAM caches but keeps reuse
     * built during the solve. Metadata may still support later background writes. */
    public synchronized void disableDerivedDiskRestore(){
        if(lightCacheDirectory!=null||proceduralKey!=null)throw new IllegalStateException("Disk restore must be disabled before derived cache initialization");
        derivedDiskRestoreDisabled=true;
    }
    public boolean derivedDiskRestoreDisabled(){return derivedDiskRestoreDisabled;}
    /** Full bypass exists only for an independent uncached equivalence oracle. */
    public boolean derivedCachesBypassed(){return derivedCachesBypassed;}
    private final java.util.concurrent.ConcurrentLinkedQueue<java.lang.ref.WeakReference<ExactXYZStateCache>> stateCaches=new java.util.concurrent.ConcurrentLinkedQueue<>();
    // Values contain only primitive arrays/counters, never this source or a sampling callback.
    private final ThreadLocal<ExactXYZStateCache> stateCache=ThreadLocal.withInitial(()->{
        var cache=new ExactXYZStateCache(ExactXYZStateCache.MAX_CAPACITY);
        stateCaches.add(new java.lang.ref.WeakReference<>(cache));return cache;
    });
    private final boolean opacityPagesEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.opacityPages","true"));
    // Native cold evidence shows sparse long rays churn shared pages; retain as an opt-in experiment.
    private final boolean sharedOpacityEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.sharedOpacity","false"));
    private final dev.madeinabyss.kernel.light.SharedOpacityCache sharedOpacity=new dev.madeinabyss.kernel.light.SharedOpacityCache(32768);
    private final dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity uncachedOpacity=this::sampleOpacityUncached;
    private final dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity rayOpacity=new dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity(){
        @Override public int opacity(int x,int y,int z){return opacityFast(x,y,z);}
        @Override public boolean uniformAirCube(int cx,int cy,int cz){return uniformRayAirCube(cx,cy,cz);}
    };
    private final java.util.concurrent.ConcurrentLinkedQueue<java.lang.ref.WeakReference<dev.madeinabyss.kernel.light.PagedOpacityCache>> opacityCaches=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final int opacityPageCapacity=Integer.getInteger("mia.generated.opacityPageCapacity",4096);
    private final ThreadLocal<dev.madeinabyss.kernel.light.PagedOpacityCache> opacityCache=ThreadLocal.withInitial(()->{
        var cache=new dev.madeinabyss.kernel.light.PagedOpacityCache(opacityPageCapacity);
        opacityCaches.add(new java.lang.ref.WeakReference<>(cache));return cache;
    });
    private record Column(int minY,int[] states,byte[] sky) {}
    private final ConcurrentHashMap<SkyColumnKey,Column> columns=new ConcurrentHashMap<>();
    /** Immutable global exposure metadata, safe to retain with a published native light window. */
    public static final class SkyExposure {
        private final int minY,firstY,rainY;
        private final byte[] attenuated;
        private SkyExposure(int minY,int firstY,byte[] attenuated,int rainY) {
            this.minY=minY;this.firstY=firstY;this.attenuated=attenuated;this.rainY=rainY;
        }
        public int directSky(int y) {
            if(y<minY)throw new IllegalArgumentException("Unknown generated layer at Y="+y);
            if(y>firstY)return 15;
            int offset=firstY-y;return offset<attenuated.length?attenuated[offset]:0;
        }
        public int rainHeight(){return rainY;}
        public int minimumY(){return minY;}
    }
    private final ConcurrentHashMap<SkyColumnKey,SkyExposure> skyColumns=new ConcurrentHashMap<>();
    private final java.util.ArrayDeque<SkyColumnKey> skyColumnOrder=new java.util.ArrayDeque<>();
    private final java.util.concurrent.atomic.LongAdder skyColumnBuilds=new java.util.concurrent.atomic.LongAdder(),skyColumnEvictions=new java.util.concurrent.atomic.LongAdder();
    private record Probe(int x,int y,int z) {
        @Override public int hashCode(){return dev.madeinabyss.kernel.light.SkyProbeSingleFlight.hashCoordinates(x,y,z);}
    }
    private final ConcurrentHashMap<Probe,Double> skyVisibility=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Probe,Double> previewSkyVisibility=new ConcurrentHashMap<>();
    // Integer-only attenuation certificates stay separate from exact persisted fractions.
    private final ConcurrentHashMap<Probe,Integer> quantizedSky=new ConcurrentHashMap<>(),previewQuantizedSky=new ConcurrentHashMap<>();
    private final boolean singleFlightEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.singleFlight","true"));
    private final boolean airCertificateEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.airCertificate","true"));
    private final java.util.concurrent.atomic.LongAdder airCertificateHits=new java.util.concurrent.atomic.LongAdder();
    private final boolean denseOpacityEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.denseOpacity","true"));
    private volatile dev.madeinabyss.kernel.light.DenseOpacityVolume denseOpacity;
    private final java.util.concurrent.atomic.LongAdder denseOpacityHits=new java.util.concurrent.atomic.LongAdder();
    private final boolean islandAirCertificateEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.islandAirCertificate","true"));
    private final java.util.concurrent.atomic.LongAdder islandAirCertificateHits=new java.util.concurrent.atomic.LongAdder();
    private final boolean deepAirCertificateEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.deepAirCertificate","true"));
    private final java.util.concurrent.atomic.LongAdder deepAirCertificateHits=new java.util.concurrent.atomic.LongAdder();
    private final boolean deepAirCellCertificateEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.deepAirCellCertificate","true"));
    private final java.util.concurrent.atomic.LongAdder deepAirCellCertificateHits=new java.util.concurrent.atomic.LongAdder();
    private final boolean derivedGeometryAirCertificateEnabled=Boolean.parseBoolean(System.getProperty("mia.generated.derivedGeometryAirCertificate","true"));
    private final java.util.concurrent.atomic.LongAdder derivedGeometryAirCertificateHits=new java.util.concurrent.atomic.LongAdder();
    private final dev.madeinabyss.kernel.light.SkyProbeSingleFlight<Probe,Double> exactFlights=new dev.madeinabyss.kernel.light.SkyProbeSingleFlight<>(64),previewFlights=new dev.madeinabyss.kernel.light.SkyProbeSingleFlight<>(64);
    private final dev.madeinabyss.kernel.light.SkyProbeSingleFlight<Probe,Integer> quantizedFlights=new dev.madeinabyss.kernel.light.SkyProbeSingleFlight<>(64),previewQuantizedFlights=new dev.madeinabyss.kernel.light.SkyProbeSingleFlight<>(64);
    private java.nio.file.Path lightCacheDirectory;
    private volatile String lightCacheKey;
    private volatile String lightCacheStatus="NOT_STARTED",lightCacheWarning="";
    private volatile long lightCacheReadMillis,lightCacheWriteMillis;
    private int savedVisibility=-1,savedColumns=-1;
    private long savedColumnBuilds=-1;
    private final Map<CubeKey,byte[]> proceduralDigests=new HashMap<>();
    private java.nio.file.Path proceduralDirectory;
    private String proceduralKey;
    private volatile String proceduralCacheStatus="NOT_STARTED",proceduralCacheWarning="";
    private volatile long proceduralCacheHits,proceduralGeneratedCubes,proceduralReadMillis,proceduralWriteMillis;
    private boolean proceduralDirty;
    /** Worker-only: a procedural digest is derived from generator output, never saved overrides. */
    private void restoreProceduralCache(java.nio.file.Path directory,String fingerprint) {
        if(derivedCachesBypassed){proceduralCacheStatus="BYPASSED";return;}
        if(proceduralKey!=null)return;
        long started=System.nanoTime();
        try {
            var bytes=new java.io.ByteArrayOutputStream();var palette=new java.io.DataOutputStream(bytes);
            palette.writeUTF(physicalSemantics.identity());palette.writeInt(minY);palette.writeInt(maxY);palette.writeInt(paletteIdentities.size());
            for(var entry:new java.util.TreeMap<>(paletteIdentities).entrySet()){palette.writeInt(entry.getKey());palette.writeUTF(entry.getValue());}
            palette.flush();
            var paletteKey=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            proceduralDirectory=directory.resolve("procedural-cubes");
            proceduralKey=dev.madeinabyss.kernel.lod.TerrainLodDiskCache.identity("procedural-cube-digests-v1",fingerprint,paletteKey,
                dev.madeinabyss.kernel.lod.TerrainLodDiskCache.codeIdentity(ParameterizedTerrainChunkGenerator.class,GeneratedSkySource.class,dev.madeinabyss.kernel.terrain.CompiledTerrainSampler.class,dev.madeinabyss.kernel.cube.ProceduralCubeDigestCache.class),GeneratedCaveOpacityProjection.proofCodeIdentity());
            if(derivedDiskRestoreDisabled){proceduralCacheStatus="RESTORE_DISABLED";return;}
            var read=dev.madeinabyss.kernel.cube.ProceduralCubeDigestCache.read(proceduralDirectory,proceduralKey);
            proceduralCacheStatus=read.status();read.entry().ifPresent(proceduralDigests::putAll);
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException|RuntimeException unavailable){proceduralCacheStatus="UNAVAILABLE";proceduralCacheWarning=unavailable.toString();}
        finally{proceduralReadMillis=(System.nanoTime()-started)/1_000_000;}
    }
    private void persistProceduralCache() {
        requirePhysicalAuthority();
        if(derivedCachesBypassed)return;
        if(!proceduralDirty||proceduralKey==null||proceduralDirectory==null)return;
        long started=System.nanoTime();
        try {
            dev.madeinabyss.kernel.cube.ProceduralCubeDigestCache.write(proceduralDirectory,proceduralKey,proceduralDigests);
            proceduralDirty=false;proceduralCacheWarning="";
        }catch(java.io.IOException|RuntimeException unavailable){proceduralCacheWarning=unavailable.toString();}
        finally{proceduralWriteMillis=(System.nanoTime()-started)/1_000_000;}
    }
    private boolean matchesProcedural(CubeKey key,int[] authoritative) {
        var expected=derivedCachesBypassed?null:proceduralDigests.get(key);
        if(expected!=null) {
            proceduralCacheHits++;
            return java.security.MessageDigest.isEqual(expected,dev.madeinabyss.kernel.cube.ProceduralCubeDigestCache.digest(authoritative));
        }
        var generated=generator.generateCube(key).states();proceduralGeneratedCubes++;
        if(!derivedCachesBypassed&&proceduralKey!=null&&proceduralDigests.size()<dev.madeinabyss.kernel.cube.ProceduralCubeDigestCache.MAX_ENTRIES) {
            proceduralDigests.put(key,dev.madeinabyss.kernel.cube.ProceduralCubeDigestCache.digest(generated));proceduralDirty=true;
        }
        return java.util.Arrays.equals(authoritative,generated);
    }
    public String proceduralCacheStatus(){return proceduralCacheStatus;}
    public String proceduralCacheWarning(){return proceduralCacheWarning;}
    public long proceduralCacheHits(){return proceduralCacheHits;}
    public long proceduralGeneratedCubes(){return proceduralGeneratedCubes;}
    public long proceduralReadMillis(){return proceduralReadMillis;}
    public long proceduralWriteMillis(){return proceduralWriteMillis;}

    /** Worker-only restoration. Identity includes saved edits, palette, profile and actual lighting code. */
    public void restoreLightCache(java.nio.file.Path directory,String profileFingerprint){
        requirePhysicalAuthority();
        if(derivedCachesBypassed){lightCacheStatus="BYPASSED";proceduralCacheStatus="BYPASSED";return;}
        if(lightCacheKey!=null)return;
        // A newer solve already has this immutable metadata. It must not wait behind
        // background serialization holding the source monitor for an earlier target.
        synchronized(this){
        requirePhysicalAuthority();if(lightCacheKey!=null)return;
        long start=System.nanoTime();
        try {
            lightCacheDirectory=directory;
            restoreProceduralCache(directory,profileFingerprint);
            lightCacheKey=dev.madeinabyss.kernel.lod.TerrainLodDiskCache.identity("sky-source-probes-v1",profileFingerprint,cacheGeometryIdentity(),
                    dev.madeinabyss.kernel.lod.TerrainLodDiskCache.codeIdentity(GeneratedSkySource.class,Generated155DerivedProjection.class,Generated155BDerivedProjection.class,dev.madeinabyss.kernel.terrain.CompiledTerrainSampler.class,dev.madeinabyss.kernel.light.SkyVisibility.class,dev.madeinabyss.kernel.light.QuantizedDiffuseSky.class,dev.madeinabyss.kernel.light.SkySourceDiskCache.class,
                            dev.madeinabyss.kernel.light.DenseOpacityVolume.class,GeneratedIslandAirCertificate.class,GeneratedDeepAirCertificate.class,dev.madeinabyss.kernel.light.DeepCavityAirBounds.class,
                            GeneratedDeepAirCellCertificate.class,dev.madeinabyss.kernel.light.DeepCavityAirCellBounds.class),GeneratedCaveOpacityProjection.proofCodeIdentity(),Generated155ExactView.codeIdentity());
            if(derivedDiskRestoreDisabled){lightCacheStatus="RESTORE_DISABLED";return;}
            var read=dev.madeinabyss.kernel.light.SkySourceDiskCache.read(directory,lightCacheKey,minY,maxY);lightCacheStatus=read.status();
            if(read.entry().isPresent()){
                var entry=read.entry().get();
                for(var v:entry.visibility()){
                    if(skyVisibility.size()>=262144)break;
                    if(v.y()<knownLowerY(v.x(),v.z()))throw new IllegalArgumentException("Cached visibility outside spatial domain");
                    skyVisibility.putIfAbsent(new Probe(v.x(),v.y(),v.z()),v.fraction());
                }
                synchronized(skyColumns){for(var c:entry.exposure()){
                    if(skyColumns.size()>=MAX_SKY_COLUMNS)break;
                    var key=new SkyColumnKey(c.x(),c.z());
                    int localMin=knownLowerY(c.x(),c.z());
                    if(c.firstY()<localMin-1 || c.rainY()<localMin)throw new IllegalArgumentException("Cached exposure outside spatial domain");
                    if(skyColumns.putIfAbsent(key,new SkyExposure(localMin,c.firstY(),c.attenuation(),c.rainY()))==null)skyColumnOrder.addLast(key);
                }}
                savedVisibility=skyVisibility.size();savedColumns=skyColumns.size();
                savedColumnBuilds=skyColumnBuilds.sum();
            }
        }catch(RuntimeException failed){lightCacheStatus="UNAVAILABLE";lightCacheWarning=failed.toString();}
        finally {lightCacheReadMillis=(System.nanoTime()-start)/1_000_000;}
        }
    }
    /** Optional derived data, never a world save. Exact values survive restart without recomputing rays. */
    public synchronized void persistLightCache(){
        requirePhysicalAuthority();
        if(derivedCachesBypassed)return;
        persistProceduralCache();
        if(lightCacheKey==null||lightCacheDirectory==null)return;
        if(savedVisibility==skyVisibility.size()&&savedColumns==skyColumns.size()&&savedColumnBuilds==skyColumnBuilds.sum())return;
        long start=System.nanoTime();
        long buildsAtSnapshot=skyColumnBuilds.sum();
        try {
            var visibility=new java.util.ArrayList<dev.madeinabyss.kernel.light.SkySourceDiskCache.Visibility>();
            for(var e:skyVisibility.entrySet()){
                if(visibility.size()==262144)break;var p=e.getKey();
                visibility.add(new dev.madeinabyss.kernel.light.SkySourceDiskCache.Visibility(p.x(),p.y(),p.z(),e.getValue()));
            }
            var exposures=new java.util.ArrayList<dev.madeinabyss.kernel.light.SkySourceDiskCache.Exposure>();
            for(var e:skyColumns.entrySet()){
                if(exposures.size()==MAX_SKY_COLUMNS)break;var key=e.getKey();var c=e.getValue();
                exposures.add(new dev.madeinabyss.kernel.light.SkySourceDiskCache.Exposure(key.x(),key.z(),c.firstY,c.attenuated,c.rainY));
            }
            dev.madeinabyss.kernel.light.SkySourceDiskCache.write(lightCacheDirectory,lightCacheKey,
                    new dev.madeinabyss.kernel.light.SkySourceDiskCache.Entry(minY,maxY,visibility,exposures));
            savedVisibility=visibility.size();savedColumns=exposures.size();lightCacheWarning="";
            savedColumnBuilds=buildsAtSnapshot;
        }catch(java.io.IOException|RuntimeException failed){lightCacheWarning=failed.toString();}
        finally {lightCacheWriteMillis=(System.nanoTime()-start)/1_000_000;}
    }
    public String lightCacheStatus(){return lightCacheStatus;}
    public String lightCacheWarning(){return lightCacheWarning;}
    public long lightCacheReadMillis(){return lightCacheReadMillis;}
    public long lightCacheWriteMillis(){return lightCacheWriteMillis;}
    public int cachedVisibilityProbes(){return skyVisibility.size();}
    public GeneratedSkySource(ParameterizedTerrainChunkGenerator generator, Map<CubeKey,int[]> overrides) {
        this(generator,overrides,null,null,null,null,false);
    }
    /** Only a validated profile can enable its fixed composed world's conservative AIR proofs. */
    public GeneratedSkySource(GeneratedPreviewProfile profile,Map<CubeKey,int[]> overrides) {
        this(profile,overrides,false);
    }
    /** The boolean bypasses every source memo for exact-equivalence testing, not for a
     * production cold benchmark; use a fresh normal source+disableDerivedDiskRestore there. */
    public GeneratedSkySource(GeneratedPreviewProfile profile,Map<CubeKey,int[]> overrides,boolean derivedCachesBypassed) {
        this(java.util.Objects.requireNonNull(profile).generator(),overrides,new GeneratedIslandAirCertificate(profile),new GeneratedDeepAirCertificate(profile),new GeneratedDeepAirCellCertificate(profile),profile,derivedCachesBypassed);
    }
    private GeneratedSkySource(ParameterizedTerrainChunkGenerator generator,Map<CubeKey,int[]> overrides,GeneratedIslandAirCertificate certificate,GeneratedDeepAirCertificate deepCertificate,GeneratedDeepAirCellCertificate cellCertificate,GeneratedPreviewProfile profile,boolean derivedCachesBypassed) {
        this.generator=java.util.Objects.requireNonNull(generator);
        this.derivedCachesBypassed=derivedCachesBypassed;
        islandAirCertificate=certificate;
        deepAirCertificate=deepCertificate;
        deepAirCellCertificate=cellCertificate;
        if(opacityPageCapacity<1 || opacityPageCapacity>dev.madeinabyss.kernel.light.PagedOpacityCache.MAX_CAPACITY || (opacityPageCapacity&(opacityPageCapacity-1))!=0)
            throw new IllegalArgumentException("Opacity page capacity must be a supported power of two");
        minY=generator.minimumKnownY();
        air=Block.getId(Blocks.AIR.defaultBlockState());
        // Native columns end at320, but saved cubes and the camera continue into
        // known sky. Only real upper-world blocks extend the occluder scan.
        int[] occluderTop={generator.getMinY()+generator.getGenDepth()};
        var copy=new HashMap<CubeKey,int[]>();var ids=new java.util.HashSet<Integer>();
        for(var block:java.util.List.of(Blocks.AIR,Blocks.STONE,Blocks.DIRT,Blocks.GRASS_BLOCK,Blocks.WATER))ids.add(Block.getId(block.defaultBlockState()));
        for(var state:HabitatBlockStates.palette())ids.add(Block.getId(state));
        for(var kind:dev.madeinabyss.kernel.terrain.natural.NaturalBlock.values())ids.add(Block.getId(NaturalBlockStates.state(kind)));
        if(generator.caveExpansion()!=null)for(var state:generator.caveExpansion().palette())ids.add(Block.getId(state));
        if(generator.alexEcology()!=null)for(var state:generator.alexEcology().palette())ids.add(Block.getId(state));
        if(generator.wilderness()!=null)for(var state:generator.wilderness().palette().palette())ids.add(Block.getId(state));
        if(generator.islandSurface()!=null)for(var state:generator.islandSurface().palette())ids.add(Block.getId(state));
        if(generator.livingAbyss()!=null)for(var state:generator.livingAbyss().palette())ids.add(Block.getId(state));
        overrides.forEach((key,states)->{
            if(states.length!=4096)throw new IllegalArgumentException("Invalid cube geometry");
            if(!generator.supportsCube(key) || (long)key.y()*16+15>=knownMaxY())throw new IllegalArgumentException("Saved cube outside declared generated geometry");
            for(int i=states.length-1;i>=0;i--)if(states[i]!=air){
                occluderTop[0]=Math.max(occluderTop[0],key.y()*16+(i>>8)+1);break;
            }
            var values=states.clone();copy.put(key,values);for(int id:values)ids.add(id);
        });
        maxY=occluderTop[0];
        var op=new HashMap<Integer,Integer>();var em=new HashMap<Integer,Integer>();
        // Resolve native state callbacks on the client/server owner thread, never on a worker Level.
        for(int id:ids){
            var state=Block.BLOCK_STATE_REGISTRY.byId(id);
            if(state==null || state.hasBlockEntity() || state.useShapeForLightOcclusion())throw new IllegalArgumentException("Unsupported scalar light state "+id);
            int o=state.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            int e=state.getLightEmission(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            if(o<0||o>15||e<0||e>15)throw new IllegalArgumentException("Non-scalar native light state "+id);
            op.put(id,o);em.put(id,e);
        }
        // Dense signed cube keys cluster in MapN's linear-probing table. Keep the locally
        // owned cloned HashMap's collision buckets; only its read-only wrapper escapes.
        this.overrides=java.util.Collections.unmodifiableMap(copy);opacity=Map.copyOf(op);emission=Map.copyOf(em);
        int paletteSize=Block.BLOCK_STATE_REGISTRY.size();
        indexedOpacity=new byte[paletteSize];indexedEmission=new byte[paletteSize];
        java.util.Arrays.fill(indexedOpacity,(byte)-1);java.util.Arrays.fill(indexedEmission,(byte)-1);
        for(int id:ids){if(id<0||id>=paletteSize)throw new IllegalArgumentException("Native state ID outside registry");indexedOpacity[id]=(byte)(int)op.get(id);indexedEmission[id]=(byte)(int)em.get(id);}
        var paletteNames=new HashMap<Integer,String>();for(int id:ids)paletteNames.put(id,Block.BLOCK_STATE_REGISTRY.byId(id).toString());
        paletteIdentities=Map.copyOf(paletteNames);
        physicalSemantics=GeneratedPhysicalSemantics.capture(ids,generator.alexEcology()!=null,generator.upperShaftOpening()!=null);
        // Refuse inconsistent callbacks rather than persist scalars under different captured values.
        for(var state:physicalSemantics.states())if(state.opacity()!=op.get(state.id())||state.emission()!=em.get(state.id()))
            throw new IllegalArgumentException("Unstable scalar native light state "+state.id());
        caveOpacityProjection=profile==null?null:new GeneratedCaveOpacityProjection(profile,this::opacity);
        caveProjectionEligible=caveOpacityProjection!=null&&caveOpacityProjection.eligible();
        projection155=Boolean.parseBoolean(System.getProperty("mia.generated.derived155","true"))?Generated155DerivedProjection.bind(profile,this::opacity):null;
        recentVisibility155=projection155!=null&&Boolean.parseBoolean(System.getProperty("mia.generated.recentVisibility155","true"))
            ?new dev.madeinabyss.kernel.concurrent.BoundedReuseCache<>(1_048_576,1_048_576,v->1L):null;
    }
    /** Lazy worker-safe derived-cache identity; never changes the profile or visited namespace. */
    public String cacheGeometryIdentity(){
        requirePhysicalAuthority();
        String cached=cacheGeometryIdentity;
        if(cached!=null)return cached;
        synchronized(this){
            if(cacheGeometryIdentity!=null)return cacheGeometryIdentity;
            try {
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest));
                writeCacheIdentityHeader(out);
                // Loading an untouched cube must not invalidate a panorama cached before that visit.
                // This exact comparison runs only in the derived-cache worker. Saved arrays remain
                // authoritative in overrides regardless of their contribution to the derived identity.
                var keys=new java.util.ArrayList<CubeKey>();
                for(var entry:overrides.entrySet()) {
                    if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                    if(!matchesProcedural(entry.getKey(),entry.getValue()))keys.add(entry.getKey());
                }
                keys.sort(java.util.Comparator.comparingInt(CubeKey::x).thenComparingInt(CubeKey::y).thenComparingInt(CubeKey::z));
                out.writeInt(keys.size());for(var key:keys){out.writeInt(key.x());out.writeInt(key.y());out.writeInt(key.z());for(int state:overrides.get(key))out.writeInt(state);}
                out.flush();
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                String computed=java.util.HexFormat.of().formatHex(digest.digest());
                var baseDigest=java.security.MessageDigest.getInstance("SHA-256");
                var baseOut=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),baseDigest));
                writeCacheIdentityHeader(baseOut);baseOut.flush();
                var edits=new HashMap<CubeKey,String>();
                for(var key:keys){
                    if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                    edits.put(key,java.util.HexFormat.of().formatHex(dev.madeinabyss.kernel.cube.ProceduralCubeDigestCache.digest(overrides.get(key))));
                }
                cacheAuthority=new CacheAuthority(java.util.HexFormat.of().formatHex(baseDigest.digest()),computed,edits);
                // Derived disk writes belong to the explicit background persistence path.
                cacheGeometryIdentity=computed;return cacheGeometryIdentity;
            }catch(java.io.IOException|java.security.NoSuchAlgorithmException failed){throw new IllegalStateException("Cannot identify immutable generated geometry",failed);}
        }
    }
    private void writeCacheIdentityHeader(java.io.DataOutputStream out)throws java.io.IOException {
        out.writeUTF("mia-immutable-sky-source-v3");out.writeUTF(physicalSemantics.identity());out.writeInt(minY);out.writeInt(maxY);
        if(!generator.islandIdentity().isEmpty())out.writeUTF(generator.islandIdentity());
        if(generator.upperShaftOpening()!=null)out.writeUTF(generator.upperShaftOpening().status().toString());
        if(generator.livingAbyss()!=null)out.writeUTF(generator.livingAbyss().status().toString());
        var palette=new java.util.TreeSet<Integer>(paletteIdentities.keySet());out.writeInt(palette.size());
        for(int id:palette){out.writeInt(id);out.writeUTF(paletteIdentities.get(id));out.writeInt(opacity.get(id));out.writeInt(emission.get(id));}
    }
    /** Worker-only authority snapshot. Pristine visits are absent; no mutable saved arrays escape. */
    public CacheAuthority cacheAuthority(){cacheGeometryIdentity();return cacheAuthority;}
    public String physicalSemanticsIdentity(){return physicalSemantics.identity();}
    /** Owner-thread reload check. A changed classification requires a fresh profile, including Alex memoization. */
    public void verifyPhysicalSemantics(){
        requirePhysicalAuthority();
        try{physicalSemantics.requireCurrent();}
        catch(RuntimeException changed){physicalAuthorityRetired=true;throw changed;}
    }
    public boolean physicalAuthorityRetired(){return physicalAuthorityRetired;}
    public void requirePhysicalAuthority(){if(physicalAuthorityRetired)throw new java.util.concurrent.CancellationException("Generated physical authority retired after resource change; rejoin the world");}
    /** Queued behind the sole native-light worker, so previous native-window/probe IO has finished. */
    public synchronized void quarantineLightCaches()throws java.io.IOException {
        if(!physicalAuthorityRetired)throw new IllegalStateException("Only retired physical light caches may be quarantined");
        if(lightCacheDirectory!=null)dev.madeinabyss.kernel.lod.DerivedCacheQuarantine.moveChild(lightCacheDirectory.getParent(),lightCacheDirectory.getFileName().toString());
    }
    /** Owner-thread lookup only: null defers work until a worker publishes the exact identity. */
    public String cachedGeometryIdentity(){return cacheGeometryIdentity;}
    /** Spatial availability; the global minimum is only a planning/cache envelope. */
    public boolean hasIslandSurface(){return generator.islandSurface()!=null;}
    public dev.madeinabyss.kernel.terrain.living.LivingAbyssRecipe livingRecipe(){return generator.livingAbyss()==null?null:generator.livingAbyss().recipeValue();}
    /** Explicit diagnostic snapshot of this source's generator; never sampled by render/status polling. */
    public com.google.gson.JsonObject islandStatus(){
        var surface=generator.islandSurface();
        return surface==null?new com.google.gson.JsonObject():surface.status();
    }
    public int oceanLevelY(){return generator.getSeaLevel();}
    public int knownLowerY(int x,int z){return generator.knownLowerY(x,z);}
    public int minKnownYForBox(int minX,int minZ,int maxX,int maxZ){return generator.minKnownYForBox(minX,minZ,maxX,maxZ);}
    private void requireKnown(int x,int y,int z){
        if(y<knownLowerY(x,z))throw new TerrainUnavailableException(dev.madeinabyss.kernel.terrain.TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER,new dev.madeinabyss.kernel.BlockCoordinate(x,y,z));
    }
    /** A rectangular native solve must be supported over its entire horizontal halo. */
    public dev.madeinabyss.kernel.cube.NativeRenderDistance.Plan nativePlan(int chunks,double x,double y,double z){
        var initial=dev.madeinabyss.kernel.cube.NativeRenderDistance.plan(chunks,x,y,z,minY,knownMaxY());
        var b=initial.lightBounds();
        int lower=minKnownYForBox(b.minX(),b.minZ(),Math.addExact(b.minX(),b.sizeX()-1),Math.addExact(b.minZ(),b.sizeZ()-1));
        return dev.madeinabyss.kernel.cube.NativeRenderDistance.plan(chunks,x,y,z,lower,knownMaxY());
    }
    public int minY(){return minY;}
    public int maxY(){return maxY;}
    /** Camera/light allocation domain, distinct from the highest possible occluder. */
    public int knownMaxY(){return dev.madeinabyss.forge.probe.deep.DeepCubeDomain.GENERATED_TOP;}
    public java.util.Set<CubeKey> overrideKeys(){return overrides.keySet();}
    public int opacity(int stateId){if(stateId<0||stateId>=indexedOpacity.length||indexedOpacity[stateId]<0)throw new IllegalArgumentException("Uncompiled opacity "+stateId);return indexedOpacity[stateId];}
    public int emission(int stateId){if(stateId<0||stateId>=indexedEmission.length||indexedEmission[stateId]<0)throw new IllegalArgumentException("Uncompiled emission "+stateId);return indexedEmission[stateId];}
    public int stateId(int x,int y,int z){
        checkRequest();
        requireKnown(x,y,z);
        if(y>=maxY)return air;
        var full=column(x,z);return full.states[y-full.minY];
    }
    public int stateIdFast(int x,int y,int z){
        checkRequest();
        requireKnown(x,y,z);
        if(y>=maxY)return air;
        if(derivedCachesBypassed)return stateIdUncached(x,y,z);
        var cache=stateCache.get();int known=cache.get(x,y,z);if(known>=0)return known;
        int sampled=-1;
        if(projection155!=null&&(overrides.isEmpty()||!overrides.containsKey(new CubeKey(x>>4,y>>4,z>>4)))){
            sampled=projection155.stateId(x,y,z);if(sampled>=0)stateProjection155Hits.increment();
        }
        if(sampled<0)sampled=stateIdUncached(x,y,z);cache.put(x,y,z,sampled);return sampled;
    }
    /** Exact immutable opacity only; every unknown/missing cell still samples authoritative geometry. */
    public int opacityFast(int x,int y,int z){
        checkRequest();
        requireKnown(x,y,z);
        if(y>=maxY)return 0;
        if(derivedCachesBypassed)return opacity(stateIdUncached(x,y,z));
        var dense=denseOpacity;
        if(dense!=null){int known=dense.opacity(x,y,z);if(known>=0){denseOpacityHits.increment();return known;}}
        if(deepAirCellCertificateEnabled&&deepAirCellCertificate!=null&&deepAirCellCertificate.guaranteedAir(x,y,z,overrides.keySet())){
            deepAirCellCertificateHits.increment();return 0;
        }
        if(deepAirCertificateEnabled&&deepAirCertificate!=null&&deepAirCertificate.guaranteedAir(x,y,z,overrides.keySet())){
            deepAirCertificateHits.increment();return 0;
        }
        if(islandAirCertificateEnabled&&islandAirCertificate!=null&&islandAirCertificate.guaranteedAir(x,y,z,overrides.keySet())){
            islandAirCertificateHits.increment();return 0;
        }
        if(!opacityPagesEnabled)return opacity(stateIdFast(x,y,z));
        var cache=opacityCache.get();int known=cache.get(x,y,z);if(known>=0)return known;
        // The exact top-down exposure scan proved that every voxel above firstY has opacity0.
        // Consult only published metadata; never generate a new column merely to accelerate rays.
        if(airCertificateEnabled){
            var exposure=skyColumns.get(new SkyColumnKey(x,z));
            if(exposure!=null&&y>exposure.firstY){airCertificateHits.increment();cache.put(x,y,z,0);return 0;}
        }
        int sampled=sharedOpacityEnabled?sharedOpacity.getOrCompute(x,y,z,uncachedOpacity):sampleOpacityUncached(x,y,z);
        cache.put(x,y,z,sampled);return sampled;
    }
    /** One mutable cursor per native phase worker. A complete known immutable tile
     * supplies exact opacity without repeating point availability/certificate/map work.
     * Do not share this callback between workers or retain it after their phase exits. */
    public dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity nativeRayOpacity(){
        requirePhysicalAuthority();
        // Preserve the accepted sparse/projection v14 path and the independent bypass oracle.
        return nativeTileQueriesEnabled()?new NativeRayOpacity():rayOpacity;
    }
    private final class NativeRayOpacity implements dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity {
        private int cx,cy,cz,untilCheck=256;
        private ExactCubeTileCache.Tile tile;
        @Override public boolean uniformAirCube(int cx,int cy,int cz){return uniformRayAirCube(cx,cy,cz);}
        @Override public int opacity(int x,int y,int z){
            // Retirement is checked even on the cheapest resident homogeneous-AIR hit.
            requirePhysicalAuthority();int xx=x>>4,yy=y>>4,zz=z>>4;
            if(tile!=null&&xx==cx&&yy==cy&&zz==cz){
                if(--untilCheck<=0){untilCheck=256;checkRequest();}
                return tile.opacityAt(cubeIndex(x,y,z));
            }
            checkRequest();untilCheck=256;
            if(y>=maxY){requireKnown(x,y,z);return 0;}
            var key=new CubeKey(xx,yy,zz);
            // A known exterior voxel can share its cube with UNKNOWN inland columns.
            // Refusal of the complete proof retains the accepted exact point path.
            if(!generator.supportsCube(key)){exactTileBoundaryFallbacks.increment();return opacityFast(x,y,z);}
            var next=exactTile(key,GeneratedSkySource.this::checkRequest);
            cx=xx;cy=yy;cz=zz;tile=next;return next.opacityAt(cubeIndex(x,y,z));
        }
    }
    private boolean uniformAirCube(int cx,int cy,int cz){
        return !derivedCachesBypassed&&uniformAirCubeSkipEnabled&&generator.uniformAirCube(cx,cy,cz)
                &&(overrides.isEmpty()||!overrides.containsKey(new CubeKey(cx,cy,cz)));
    }
    private boolean uniformRayAirCube(int cx,int cy,int cz){
        checkRequest();if(!uniformAirCube(cx,cy,cz))return false;
        requireKnown(cx*16,cy*16,cz*16);return true;
    }
    private int sampleOpacityUncached(int x,int y,int z){
        // opacityFast already enforced known/max/dense/certificate/memo authority.
        // Whole saved cubes, raw sources and final modifiers refuse BEFORE Cave.sample.
        if(projection155!=null&&(overrides.isEmpty()||!overrides.containsKey(new CubeKey(x>>4,y>>4,z>>4)))){
            int projected=projection155.opacity(x,y,z);if(projected>=0){projection155Hits.increment();return projected;}
        }
        if(!caveOpacityProjectionRequested||!caveProjectionEligible||!caveOpacityProjection.mayEnter(x,y,z,overrides.keySet())){
            if(caveOpacityProjectionRequested&&caveProjectionEligible)caveOpacityProjectionRefusals.increment();
            return opacity(stateIdUncached(x,y,z));
        }
        caveOpacityProjectionCoreSamples.increment();
        var composed=generator.caveExpansion().sample(x,y,z);
        if(composed instanceof dev.madeinabyss.kernel.terrain.TerrainSample.Unavailable unavailable)
            throw new TerrainUnavailableException(unavailable.reason(),new dev.madeinabyss.kernel.BlockCoordinate(x,y,z));
        var material=((dev.madeinabyss.kernel.terrain.TerrainSample.Known)composed).material();
        int projected=caveOpacityProjection.opacityFor(material,x,y,z);
        if(projected>=0){
            if(material==dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK)caveOpacityProjectionRockHits.increment();
            else if(material==dev.madeinabyss.kernel.terrain.TerrainMaterial.SOIL)caveOpacityProjectionSoilHits.increment();
            else if(material==dev.madeinabyss.kernel.terrain.TerrainMaterial.GRASS)caveOpacityProjectionGrassHits.increment();
            return projected;
        }
        caveOpacityProjectionContinued.increment();
        return opacity(Block.getId(generator.continueCaveSampleForDerivedOpacity(x,y,z,composed)));
    }
    public boolean sharedOpacityEnabled(){return sharedOpacityEnabled;}
    public long sharedOpacityHits(){return sharedOpacity.hits();}
    public long sharedOpacityMisses(){return sharedOpacity.misses();}
    public long sharedOpacityEvictions(){return sharedOpacity.evictions();}
    public long sharedOpacityResidentBytes(){return sharedOpacity.residentBytes();}
    /** Worker handoff after all density writers joined. The caller relinquishes future array writes. */
    public void publishDenseOpacity(dev.madeinabyss.kernel.light.VoxelSkyLight.Bounds bounds,byte[] values) {
        checkRequest();
        if(denseOpacityEnabled&&!derivedCachesBypassed)denseOpacity=dev.madeinabyss.kernel.light.DenseOpacityVolume.takeOwnership(bounds,values,40_000_000);
    }
    public boolean denseOpacityEnabled(){return denseOpacityEnabled;}
    public long denseOpacityHits(){return denseOpacityHits.sum();}
    public long denseOpacityResidentBytes(){var dense=denseOpacity;return dense==null?0:dense.residentBytes();}
    public boolean islandAirCertificateEnabled(){return islandAirCertificateEnabled&&islandAirCertificate!=null&&islandAirCertificate.eligible();}
    public long islandAirCertificateHits(){return islandAirCertificateHits.sum();}
    public boolean deepAirCertificateEnabled(){return deepAirCertificateEnabled&&deepAirCertificate!=null&&deepAirCertificate.eligible();}
    public long deepAirCertificateHits(){return deepAirCertificateHits.sum();}
    public boolean deepAirCellCertificateEnabled(){return deepAirCellCertificateEnabled&&deepAirCellCertificate!=null&&deepAirCellCertificate.eligible();}
    public long deepAirCellCertificateHits(){return deepAirCellCertificateHits.sum();}
    public int deepAirCellCertificateResidentBytes(){return deepAirCellCertificate==null?0:deepAirCellCertificate.residentArrayBytes();}
    public long deepAirCellCertificateConstructionNanos(){return deepAirCellCertificate==null?0:deepAirCellCertificate.constructionNanos();}
    public int deepAirCellCertificateAdmittedCells(){return deepAirCellCertificate==null?0:deepAirCellCertificate.admittedCellCount();}
    public boolean derivedGeometryAirCertificateEnabled(){return derivedGeometryAirCertificateEnabled&&deepAirCellCertificateEnabled&&deepAirCellCertificate!=null&&deepAirCellCertificate.eligible();}
    public long derivedGeometryAirCertificateHits(){return derivedGeometryAirCertificateHits.sum();}
    public boolean caveOpacityProjectionEnabled(){return caveOpacityProjectionRequested&&opacityPagesEnabled&&caveOpacityProjection!=null&&caveOpacityProjection.eligible();}
    public String caveOpacityProjectionMode(){return !caveOpacityProjectionRequested?"DISABLED":caveOpacityProjection==null?"UNBOUND":!caveOpacityProjection.eligible()?"REFUSED":!opacityPagesEnabled?"OPACITY_PAGES_DISABLED":"COMPOSED_CAVE_PALETTE";}
    public long caveOpacityProjectionCoreSamples(){return caveOpacityProjectionCoreSamples.sum();}
    public long caveOpacityProjectionRockHits(){return caveOpacityProjectionRockHits.sum();}
    public long caveOpacityProjectionSoilHits(){return caveOpacityProjectionSoilHits.sum();}
    public long caveOpacityProjectionGrassHits(){return caveOpacityProjectionGrassHits.sum();}
    public long caveOpacityProjectionHits(){return caveOpacityProjectionRockHits()+caveOpacityProjectionSoilHits()+caveOpacityProjectionGrassHits();}
    public long caveOpacityProjectionContinued(){return caveOpacityProjectionContinued.sum();}
    public long caveOpacityProjectionRefusals(){return caveOpacityProjectionRefusals.sum();}
    public boolean projection155Enabled(){return projection155!=null&&!derivedCachesBypassed;}
    public long projection155Hits(){return projection155Hits.sum();}
    public long stateProjection155Hits(){return stateProjection155Hits.sum();}
    public boolean exact155ViewEnabled(){return projection155!=null&&projection155.exactViewEnabled()&&!derivedCachesBypassed;}
    public int recentVisibility155Entries(){return recentVisibility155==null?0:recentVisibility155.size();}
    public long recentVisibility155Evictions(){return recentVisibility155==null?0:recentVisibility155.evictions();}
    public boolean airCertificateEnabled(){return airCertificateEnabled;}
    public long airCertificateHits(){return airCertificateHits.sum();}
    public boolean singleFlightEnabled(){return singleFlightEnabled;}
    public long probeFlightEvaluations(){return exactFlights.evaluations()+previewFlights.evaluations()+quantizedFlights.evaluations()+previewQuantizedFlights.evaluations();}
    public long probeFlightJoins(){return exactFlights.joins()+previewFlights.joins()+quantizedFlights.joins()+previewQuantizedFlights.joins();}
    public boolean opacityPagesEnabled(){return opacityPagesEnabled;}
    public int opacityPageCapacity(){return opacityPageCapacity;}
    public long opacityCacheHits(){return opacityCacheMetric(0);}
    public long opacityCacheMisses(){return opacityCacheMetric(1);}
    public long opacityCacheEvictions(){return opacityCacheMetric(2);}
    public long opacityCacheResidentBytes(){return opacityCacheMetric(3);}
    private long opacityCacheMetric(int metric){
        long sum=0;for(var reference:opacityCaches){var cache=reference.get();if(cache==null)opacityCaches.remove(reference);
            else sum+=switch(metric){case 0->cache.hits();case 1->cache.misses();case 2->cache.evictions();default->cache.residentBytes();};}return sum;
    }
    /** Exact AIR state for derived geometry only; opacity/density never substitutes for a state.
     * The profile-bound wrapper proves spatial availability and vetoes whole saved cubes.
     * Every refused target retains the independent full-state oracle and its UNKNOWN guard. */
    public int stateIdForDerivedGeometry(int x,int y,int z){
        checkRequest();requireKnown(x,y,z);if(y>=maxY)return air;
        if(!derivedCachesBypassed&&derivedGeometryAirCertificateEnabled&&deepAirCellCertificateEnabled&&deepAirCellCertificate!=null
                &&deepAirCellCertificate.guaranteedAir(x,y,z,overrides.keySet())){
            derivedGeometryAirCertificateHits.increment();return air;
        }
        return stateIdUncached(x,y,z);
    }
    /** Exact final material only. Full states and saved whole cubes keep their original authority. */
    public dev.madeinabyss.kernel.terrain.TerrainMaterial materialForDerivedGeometry(int x,int y,int z){
        if(derivedCachesBypassed||projection155==null)return dev.madeinabyss.forge.probe.client.compat.LodBlockStateMaterials.materialById(stateIdForDerivedGeometry(x,y,z));
        checkRequest();requireKnown(x,y,z);if(y>=maxY)return dev.madeinabyss.kernel.terrain.TerrainMaterial.AIR;
        if(!derivedCachesBypassed&&projection155!=null&&(overrides.isEmpty()||!overrides.containsKey(new CubeKey(x>>4,y>>4,z>>4)))){
            var material=projection155.material(x,y,z);if(material!=null)return material;
        }
        return dev.madeinabyss.forge.probe.client.compat.LodBlockStateMaterials.materialById(stateIdForDerivedGeometry(x,y,z));
    }
    // Native bulk work is explicit: sparse LOD/state/column APIs retain their accepted
    // point samplers instead of expanding each request to16x16 neighbouring columns.
    private boolean nativeTileQueriesEnabled(){return exactTilesRequested&&!derivedCachesBypassed&&generator.livingAbyss()!=null;}
    private static int cubeIndex(int x,int y,int z){return ((y&15)<<8)|((z&15)<<4)|(x&15);}
    private int nativeColumnState(int x,int y,int z){
        if(!nativeTileQueriesEnabled())return stateIdUncached(x,y,z);
        checkRequest();requireKnown(x,y,z);if(y>=maxY)return air;
        var tile=exactTileAt(x,y,z);return tile==null?stateIdUncached(x,y,z):tile.stateAt(cubeIndex(x,y,z));
    }
    private ExactCubeTileCache.Tile exactTileAt(int x,int y,int z){
        int cx=x>>4,cy=y>>4,cz=z>>4;var cursor=tileCursor.get();
        if(cursor.tile!=null&&cursor.x==cx&&cursor.y==cy&&cursor.z==cz){exactTileCursorHits.increment();return cursor.tile;}
        var key=new CubeKey(cx,cy,cz);
        // An exterior known voxel can share a cube with unavailable inland columns.
        // In that case use its exact point oracle, never infer AIR or refuse the known point.
        if(!generator.supportsCube(key)){exactTileBoundaryFallbacks.increment();return null;}
        var tile=exactTile(key,this::checkRequest);
        cursor.x=cx;cursor.y=cy;cursor.z=cz;cursor.tile=tile;return tile;
    }
    /** Caller-owned exact full-state array in native y/z/x order. Saved arrays win before
     * procedural preparation; no mutable density array can alter a completed tile. */
    public int[] captureDerivedCube(CubeKey key,Runnable checkpoint){
        java.util.Objects.requireNonNull(key);java.util.Objects.requireNonNull(checkpoint);
        Runnable combined=()->{checkRequest();checkpoint.run();};combined.run();
        if(!generator.supportsCube(key))throw new TerrainUnavailableException(dev.madeinabyss.kernel.terrain.TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER,
                new dev.madeinabyss.kernel.BlockCoordinate(Math.multiplyExact(key.x(),16),Math.multiplyExact(key.y(),16),Math.multiplyExact(key.z(),16)));
        var values=exactTile(key,combined).states();combined.run();return values;
    }
    private ExactCubeTileCache.Tile exactTile(CubeKey key,Runnable checkpoint){
        if(derivedCachesBypassed||!exactTilesRequested)return buildExactTile(key,checkpoint);
        return exactTiles.getOrCompute(key,()->buildExactTile(key,checkpoint),checkpoint);
    }
    /** Same prepare/sample/finally sequence as generateCube, certified by the independent
     * 86016-state v14/v15 oracle. Local loops add cooperative checkpoints without changing
     * generator bytes, fingerprints, spatial support or the final 4096 states. */
    private ExactCubeTileCache.Tile buildExactTile(CubeKey key,Runnable checkpoint){
        checkpoint.run();int sx=Math.multiplyExact(key.x(),16),sy=Math.multiplyExact(key.y(),16),sz=Math.multiplyExact(key.z(),16);
        var saved=overrides.get(key);int[] states;
        if(saved==null) {
            var living=generator.livingAbyss();
            try {
                checkpoint.run();
                if(living!=null&&living.prepareCube(sx,sy,sz)){
                    checkpoint.run();return ExactCubeTileCache.Tile.uniform(air,opacity(air));
                }
                states=new int[4096];
                for(int y=0;y<16;y++) {
                    checkpoint.run();
                    for(int z=0;z<16;z++) {
                        checkpoint.run();
                        for(int x=0;x<16;x++)states[(y<<8)|(z<<4)|x]=sy+y>=maxY?air:Block.getId(GeneratorSampleStats.ENABLED?GeneratorSampleStats.sample(generator,sx+x,sy+y,sz+z,1):generator.sampleBlock(sx+x,sy+y,sz+z));
                    }
                }
            } finally {if(living!=null)living.finishCube();}
        }else states=saved.clone();
        var values=new byte[4096];
        for(int i=0;i<states.length;i++){if((i&15)==0)checkpoint.run();values[i]=(byte)opacity(states[i]);}
        checkpoint.run();return ExactCubeTileCache.Tile.copyOf(states,values);
    }
    public boolean exactTilesEnabled(){return exactTilesRequested&&!derivedCachesBypassed;}
    public long exactTileHits(){return exactTiles.hits()+exactTileCursorHits.sum();}
    public long exactTileMisses(){return exactTiles.misses();}
    public long exactTileBuilds(){return exactTiles.builds();}
    public long exactTileJoins(){return exactTiles.joins();}
    public long exactTileEvictions(){return exactTiles.evictions();}
    public long exactTileResidentBytes(){return exactTiles.residentBytes();}
    public int exactTileResidentTiles(){return exactTiles.residentTiles();}
    public long exactTileBoundaryFallbacks(){return exactTileBoundaryFallbacks.sum();}
    /** Exact oracle and single-pass density path; keep unique scans out of the sparse ray memo. */
    public int stateIdUncached(int x,int y,int z){
        requirePhysicalAuthority();
        requireKnown(x,y,z);
        if(y>=maxY)return air;
        // Most sessions have no saved cubes: skip the per-voxel key allocation and map probe.
        var saved=overrides.isEmpty()?null:overrides.get(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)));
        int result=saved==null?Block.getId(GeneratorSampleStats.ENABLED?GeneratorSampleStats.sample(generator,x,y,z,0):generator.sampleBlock(x,y,z)):saved[(Math.floorMod(y,16)<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16)];
        requirePhysicalAuthority();return result;
    }
    /** Captured registry domain for the stable, independently named volume palette.
     * A registry integer is used only while adapting this immutable source in memory. */
    public int[] volumeStateIds(){requirePhysicalAuthority();return paletteIdentities.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();}
    public boolean volumeCubeOverridden(CubeKey key){requirePhysicalAuthority();return overrides.containsKey(key);}
    /** A full-state proof, never an opacity proof. -1 means no uniform proof.
     * Saved whole cubes veto procedural AIR, including non-AIR zero-opacity blocks. */
    public int uniformVolumeState(CubeKey key) {
        checkRequest();int sx=Math.multiplyExact(key.x(),16),sy=Math.multiplyExact(key.y(),16),sz=Math.multiplyExact(key.z(),16);
        if(!generator.supportsCube(key))return -1;
        var saved=overrides.get(key);if(saved!=null){int first=saved[0];for(int id:saved)if(id!=first)return -1;return first;}
        if(sy>=maxY||!derivedCachesBypassed&&generator.uniformAirCube(key.x(),key.y(),key.z()))return air;
        return -1;
    }
    /** Dense producer's one final-state query. Opacity/material derive from this state;
     * it deliberately does not fill the sparse ray point caches with a full volume scan. */
    public int stateIdForVolume(int x,int y,int z) {
        checkRequest();requireKnown(x,y,z);if(y>=maxY)return air;
        var saved=overrides.isEmpty()?null:overrides.get(new CubeKey(x>>4,y>>4,z>>4));
        if(saved!=null)return saved[cubeIndex(x,y,z)];
        if(!derivedCachesBypassed&&projection155!=null){int projected=projection155.stateId(x,y,z);if(projected>=0)return projected;}
        return stateIdUncached(x,y,z);
    }
    /** Dense opacity-only producer price/path. Full state is deliberately absent
     * when the admitted scalar projection proves opacity without lithology. */
    public int opacityForVolume(int x,int y,int z) {
        checkRequest();requireKnown(x,y,z);if(y>=maxY)return 0;
        var saved=overrides.isEmpty()?null:overrides.get(new CubeKey(x>>4,y>>4,z>>4));
        if(saved!=null)return opacity(saved[cubeIndex(x,y,z)]);
        if(!derivedCachesBypassed&&projection155!=null){int projected=projection155.opacity(x,y,z);if(projected>=0)return projected;}
        return opacity(stateIdUncached(x,y,z));
    }
    /** Current resident thread-cache estimates; terminated worker caches may be collected. */
    public long stateCacheHits(){return stateCacheMetric(true);}
    public long stateCacheMisses(){return stateCacheMetric(false);}
    private long stateCacheMetric(boolean hits){
        long sum=0;for(var reference:stateCaches){var cache=reference.get();if(cache==null)stateCaches.remove(reference);else sum+=hits?cache.hits():cache.misses();}return sum;
    }
    public int residentStateCaches(){
        int count=0;for(var reference:stateCaches){if(reference.get()==null)stateCaches.remove(reference);else count++;}return count;
    }
    public int directSkyFast(int x,int y,int z){
        checkRequest();
        requireKnown(x,y,z);
        var full=derivedCachesBypassed?null:columns.get(new SkyColumnKey(x,z));
        if(full!=null&&y<maxY)return full.sky[y-full.minY];
        return columnExposure(x,z).directSky(y);
    }
    /**
     * Caller-owned ascending direct SKY slice. Scans the global column at most once even when
     * shared cache admission has closed; subsequent voxels use exact downward attenuation.
     */
    public byte[] directSkySlice(int x,int z,int sliceMinY,int height){
        long end=(long)sliceMinY+height;
        if(height<1 || sliceMinY<knownLowerY(x,z) || end>knownMaxY())
            throw new IllegalArgumentException("Direct SKY slice outside known bounded column");
        var slice=new byte[height];int top=(int)end-1;
        int light=directSkyFast(x,top,z);slice[height-1]=(byte)light;
        for(int i=height-2;i>=0;i--){
            checkRequest();int attenuation=opacity(columnState(x,sliceMinY+i,z));
            if(!(light==15 && attenuation==0))light=Math.max(0,light-Math.max(1,attenuation));
            slice[i]=(byte)light;
        }
        return slice;
    }
    /** First rain-blocking voxel plus one; minY means the known shaft remains open. */
    public int rainHeight(int x,int z){return columnExposure(x,z).rainHeight();}
    /** Owner-thread lookup: a miss stays unknown and never invokes terrain generation. */
    public java.util.Optional<SkyExposure> cachedColumnExposure(int x,int z){
        return java.util.Optional.ofNullable(skyColumns.get(new SkyColumnKey(x,z)));
    }
    public int cachedSkyColumns(){return skyColumns.size();}
    public long skyColumnBuilds(){return skyColumnBuilds.sum();}
    public long skyColumnEvictions(){return skyColumnEvictions.sum();}
    /** Render irradiance from visible upper sky. Direct SKY and rain exposure remain separate. */
    public int ambientSky(int x,int y,int z){
        return ambientSky(x,y,z,8);
    }
    /** LODs share a coarser irradiance lattice; geometry and exact direct exposure stay unchanged. */
    public int ambientSky(int x,int y,int z,dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity lookup) {
        return ambientSky(x,y,z,8,lookup,directSkyFast(x,y,z));
    }
    /** The dense direct seed was computed from the same immutable geometry before ambient seeding. */
    public int ambientSky(int x,int y,int z,dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity lookup,int direct) {
        return ambientSky(x,y,z,8,lookup,direct);
    }
    public int ambientSky(int x,int y,int z,int spacing){
        return ambientSky(x,y,z,spacing,rayOpacity,directSkyFast(x,y,z));
    }
    /** Render-only temporary panorama; never seeds physical SKY or final/native visibility. */
    public int ambientSkyPreview(int x,int y,int z,int spacing){
        return ambientSky(x,y,z,spacing,rayOpacity,directSkyFast(x,y,z),true);
    }
    private int ambientSky(int x,int y,int z,int spacing,dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity lookup,int direct){
        return ambientSky(x,y,z,spacing,lookup,direct,false);
    }
    private int ambientSky(int x,int y,int z,int spacing,dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity lookup,int direct,boolean preview){
        checkRequest();
        requireKnown(x,y,z);
        if(spacing!=8 && spacing!=32)throw new IllegalArgumentException("Unsupported irradiance spacing");
        java.util.Objects.requireNonNull(lookup);if(direct<0||direct>15)throw new IllegalArgumentException("Direct SKY seed outside0..15");if(direct==15)return 15;
        int ownOpacity=lookup.opacity(x,y,z);
        if(ownOpacity>=15)return 0;
        // Every interpolation segment includes this starting voxel; partial opacity rejects all
        // those segments. Therefore the original fallback is precisely this point's ray response.
        if(ownOpacity>0)return Math.max(direct,quantizedDiffuse(x,y,z,lookup,preview));
        // Eight-metre irradiance lattice, interpolated only through known unoccluded air.
        // This is a bounded diffuse rendering approximation, not a replacement for block geometry.
        int bx=Math.floorDiv(x,spacing)*spacing,by=Math.floorDiv(y,spacing)*spacing,bz=Math.floorDiv(z,spacing)*spacing;
        double fx=(x-bx)/(double)spacing,fy=(y-by)/(double)spacing,fz=(z-bz)/(double)spacing,sum=0,weights=0;
        for(int dy=0;dy<=1;dy++)for(int dz=0;dz<=1;dz++)for(int dx=0;dx<=1;dx++){
            double w=(dx==0?1-fx:fx)*(dy==0?1-fy:fy)*(dz==0?1-fz:fz);if(w==0)continue;
            int xx=bx+dx*spacing,yy=by+dy*spacing,zz=bz+dz*spacing;
            if(yy<knownLowerY(xx,zz) || lookup.opacity(xx,yy,zz)>=15 || !openSegment(x,y,z,xx,yy,zz,lookup))continue;
            sum+=w*(preview?previewVisibility(xx,yy,zz,lookup):visibility(xx,yy,zz,lookup));weights+=w;
        }
        double fraction=weights>0?sum/weights:(preview?previewVisibility(x,y,z,lookup):visibility(x,y,z,lookup));
        return Math.max(direct,dev.madeinabyss.kernel.light.SkyVisibility.diffuseLevel(fraction));
    }
    /** Render-only distant-LOD diffuse sky. Same cached 32m irradiance lattice as ambientSky, but a
     * corner is accepted after four 8m opacity probes instead of an exact voxel segment, and partial
     * opacity uses the lattice instead of a per-point 32-ray solve. Thin (<8m) occluders between a
     * face and its lattice corner may leak; native near blocks keep the exact solver. */
    public int ambientSkyLod(int x,int y,int z){
        checkRequest();
        requireKnown(x,y,z);
        if(opacityFast(x,y,z)>=15)return 0;
        return ambientSkyCoarse(x,y,z,rayOpacity,directSkyFast(x,y,z));
    }
    /** Shared native/LOD diffuse seed (v15.4): the same 32m lattice for near blocks and DH, so a
     * native window and its distant LOD read the same sky level. Propagation stays exact. */
    public int ambientSkyCoarse(int x,int y,int z,dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity lookup,int direct){
        if(generator.livingAbyss()!=null&&generator.livingAbyss().revision155()!=null)return ambientSky(x,y,z,lookup,direct);
        checkRequest();
        if(direct<0||direct>15)throw new IllegalArgumentException("Direct SKY seed outside0..15");if(direct==15)return 15;
        if(lookup.opacity(x,y,z)>=15)return 0;
        final int spacing=32;
        int bx=Math.floorDiv(x,spacing)*spacing,by=Math.floorDiv(y,spacing)*spacing,bz=Math.floorDiv(z,spacing)*spacing;
        double fx=(x-bx)/(double)spacing,fy=(y-by)/(double)spacing,fz=(z-bz)/(double)spacing,sum=0,weights=0;
        for(int dy=0;dy<=1;dy++)for(int dz=0;dz<=1;dz++)for(int dx=0;dx<=1;dx++){
            double w=(dx==0?1-fx:fx)*(dy==0?1-fy:fy)*(dz==0?1-fz:fz);if(w==0)continue;
            int xx=bx+dx*spacing,yy=by+dy*spacing,zz=bz+dz*spacing;
            if(yy<knownLowerY(xx,zz)||lookup.opacity(xx,yy,zz)>=15||!coarseOpen(x,y,z,xx,yy,zz,lookup))continue;
            sum+=w*visibility(xx,yy,zz,lookup);weights+=w;
        }
        if(weights==0)return Math.max(direct,ambientSky(x,y,z,spacing,lookup,direct));
        return Math.max(direct,dev.madeinabyss.kernel.light.SkyVisibility.diffuseLevel(sum/weights));
    }
    private boolean coarseOpen(int x,int y,int z,int xx,int yy,int zz,dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity lookup){
        for(int i=1;i<=3;i++){
            int px=x+(xx-x)*i/4,py=y+(yy-y)*i/4,pz=z+(zz-z)*i/4;
            if(py<knownLowerY(px,pz)||lookup.opacity(px,py,pz)>=15)return false;
        }
        return true;
    }
    private int quantizedDiffuse(int x,int y,int z,dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity lookup,boolean preview){
        checkRequest();
        if(derivedCachesBypassed)return dev.madeinabyss.kernel.light.QuantizedDiffuseSky.level(x,y,z,maxY,100000,lookup,preview);
        var key=new Probe(x,y,z);var fractions=preview?previewSkyVisibility:skyVisibility;
        var exact=fractions.get(key);if(exact!=null)return dev.madeinabyss.kernel.light.SkyVisibility.diffuseLevel(exact);
        var cache=preview?previewQuantizedSky:quantizedSky;var known=cache.get(key);if(known!=null)return known;
        if(singleFlightEnabled)return (preview?previewQuantizedFlights:quantizedFlights).getOrCompute(cache,key,262144,
                ()->dev.madeinabyss.kernel.light.QuantizedDiffuseSky.level(x,y,z,maxY,100000,lookup,preview));
        int value=dev.madeinabyss.kernel.light.QuantizedDiffuseSky.level(x,y,z,maxY,100000,lookup,preview);
        if(cache.size()<262144){var raced=cache.putIfAbsent(key,value);if(raced!=null)return raced;}
        return value;
    }
    private boolean openSegment(int x,int y,int z,int xx,int yy,int zz,dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity lookup){
        int budget=Math.toIntExact(1L+Math.abs((long)xx-x)+Math.abs((long)yy-y)+Math.abs((long)zz-z));
        return dev.madeinabyss.kernel.light.SkyVisibility.isOpenSegment(x,y,z,xx,yy,zz,budget,lookup);
    }
    private double visibility(int x,int y,int z,dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity lookup){
        checkRequest();
        if(y>=maxY)return 1;
        if(derivedCachesBypassed)return dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(x,y,z,maxY,100000,lookup);
        var key=new Probe(x,y,z);var known=skyVisibility.get(key);if(known!=null)return known;
        if(recentVisibility155!=null){var recent=recentVisibility155.get(key);if(recent!=null)return recent;}
        if(singleFlightEnabled){double made=exactFlights.getOrCompute(skyVisibility,key,262144,
                ()->{if(recentVisibility155!=null){var recent=recentVisibility155.get(key);if(recent!=null)return recent;}
                    double value=dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(x,y,z,maxY,100000,lookup);
                    if(recentVisibility155!=null)recentVisibility155.put(key,value);return value;});return made;}
        double value=dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(x,y,z,maxY,100000,lookup);
        if(recentVisibility155!=null)recentVisibility155.put(key,value);
        if(skyVisibility.size()<262144){var raced=skyVisibility.putIfAbsent(key,value);if(raced!=null)return raced;}
        return value;
    }
    private double previewVisibility(int x,int y,int z,dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity lookup){
        checkRequest();
        if(y>=maxY)return 1;
        if(derivedCachesBypassed)return dev.madeinabyss.kernel.light.SkyVisibility.visibleFractionPreview(x,y,z,maxY,100000,lookup);
        var key=new Probe(x,y,z);var known=previewSkyVisibility.get(key);if(known!=null)return known;
        if(singleFlightEnabled)return previewFlights.getOrCompute(previewSkyVisibility,key,262144,
                ()->dev.madeinabyss.kernel.light.SkyVisibility.visibleFractionPreview(x,y,z,maxY,100000,lookup));
        double value=dev.madeinabyss.kernel.light.SkyVisibility.visibleFractionPreview(x,y,z,maxY,100000,lookup);
        if(previewSkyVisibility.size()<262144){var raced=previewSkyVisibility.putIfAbsent(key,value);if(raced!=null)return raced;}
        return value;
    }
    public SkyExposure columnExposure(int x,int z){return columnExposure(x,z,false);}
    /** Dense native direct-SKY phase explicitly admits prepared tile reuse. Exposure
     * values/cache authority are identical to the ordinary exact point column. */
    public SkyExposure nativeColumnExposure(int x,int z){return columnExposure(x,z,true);}
    private SkyExposure columnExposure(int x,int z,boolean nativeTiles){
        checkRequest();var key=new SkyColumnKey(x,z);var cached=derivedCachesBypassed?null:skyColumns.get(key);if(cached!=null)return cached;
        skyColumnBuilds.increment();
        int localMin=knownLowerY(x,z);
        int first=localMin-1,light=15,rain=localMin;var levels=new byte[15];int size=0;
        for(int y=maxY-1;y>=localMin;y--){
            checkRequest();
            if(first<localMin&&uniformAirCube(x>>4,y>>4,z>>4)){
                int bottom=Math.max(localMin,(y>>4)*16);requireKnown(x,y,z);requireKnown(x,bottom,z);y=bottom;continue;
            }
            int id=nativeTiles?nativeColumnState(x,y,z):exact155ViewEnabled()?stateIdFast(x,y,z):stateIdUncached(x,y,z),o=opacity(id);
            if(rain==localMin && id!=air)rain=y+1;
            if(first<localMin&&o==0)continue;
            if(first<localMin)first=y;
            light=Math.max(0,light-Math.max(1,o));levels[size++]=(byte)light;
            if(light==0)break;
        }
        var made=new SkyExposure(localMin,first,java.util.Arrays.copyOf(levels,size),rain);
        checkRequest();if(derivedCachesBypassed)return made;
        synchronized(skyColumns){
            var raced=skyColumns.get(key);if(raced!=null)return raced;
            // Bounded FIFO: exploration must not permanently close cache admission. Published
            // native windows retain their immutable exposure values independently of eviction.
            while(skyColumns.size()>=MAX_SKY_COLUMNS){
                SkyColumnKey oldest=skyColumnOrder.pollFirst();
                if(oldest==null)oldest=skyColumns.keys().nextElement();
                if(skyColumns.remove(oldest)!=null)skyColumnEvictions.increment();
            }
            skyColumns.put(key,made);skyColumnOrder.addLast(key);
        }
        return made;
    }
    public int directSky(int x,int y,int z) {
        return directSkyFast(x,y,z);
    }
    private int columnState(int x,int y,int z){return stateIdUncached(x,y,z);}
    private Column column(int x,int z){
        checkRequest();var key=new SkyColumnKey(x,z);var existing=derivedCachesBypassed?null:columns.get(key);if(existing!=null)return existing;
        int localMin=knownLowerY(x,z);
        int[] states=new int[maxY-localMin];byte[] sky=new byte[states.length];int light=15;
        for(int y=maxY-1;y>=localMin;y--){
            checkRequest();
            if(light==15&&uniformAirCube(x>>4,y>>4,z>>4)){
                int bottom=Math.max(localMin,(y>>4)*16);requireKnown(x,y,z);requireKnown(x,bottom,z);
                java.util.Arrays.fill(states,bottom-localMin,y-localMin+1,air);java.util.Arrays.fill(sky,bottom-localMin,y-localMin+1,(byte)15);y=bottom;continue;
            }
            int id=columnState(x,y,z);
            states[y-localMin]=id;int attenuation=opacity(id);
            if(!(light==15&&attenuation==0))light=Math.max(0,light-Math.max(1,attenuation));
            sky[y-localMin]=(byte)light;
        }
        var made=new Column(localMin,states,sky);
        // Cache is optional derived data. Its cap never changes geometry or infers unknown light.
        checkRequest();if(!derivedCachesBypassed&&columns.size()<16384){var raced=columns.putIfAbsent(key,made);if(raced!=null)return raced;}
        return made;
    }
}
