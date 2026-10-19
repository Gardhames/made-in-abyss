package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A completed exact view, stored as an empty entry of the existing checksummed terrain cache.
 * This is a scheduling hint, never light, geometry or a WORLD acknowledgement. Missing owner
 * values still require exact evaluation once final source geometry is known. A writer may seal
 * only after its terminal proposal's complete owner archive has been force-flushed successfully.
 */
public final class AbyssLodCompletionSeal {
    private static final TerrainLodColumns.Bounds SENTINEL=new TerrainLodColumns.Bounds(0,0,0,0,0,0);
    private static final TerrainLodColumns.Parameters LIMITS=new TerrainLodColumns.Parameters(1,1,1);
    private static final TerrainLodDiskCache.Entry EMPTY=new TerrainLodDiskCache.Entry(new TerrainLodColumns.Result(List.of(),1),Map.of());
    private final Path directory;
    private final String familyIdentity,globalRevision,geometryCode;
    private volatile String lastKey="",lastStatus="NOT_READ";
    private volatile boolean lastHit;

    public AbyssLodCompletionSeal(Path directory,String familyIdentity,String globalRevision,String geometryCode){
        this.directory=Objects.requireNonNull(directory).resolve("completed-views-v1");
        this.familyIdentity=required(familyIdentity);this.globalRevision=required(globalRevision);this.geometryCode=required(geometryCode);
    }
    /** Runtime session/native revision counters do not identify persistent source content. */
    public String key(AbyssLodPipeline.Input input){
        return TerrainLodDiskCache.identity("exact-scalar-completed-view-v1",Code.VALUE,familyIdentity,globalRevision,geometryCode,
                input.target().sourceIdentity(),input.camera().toString(),input.nearBounds().toString(),input.worldBounds().toString(),
                input.bands().toString(),input.nativeCuts().toString(),input.limits().toString());
    }
    public synchronized boolean contains(AbyssLodPipeline.Input input){
        checkCanceled();String key=key(input);if(key.equals(lastKey))return lastHit;
        var read=TerrainLodDiskCache.load(directory.resolve(key),key,SENTINEL,LIMITS);checkCanceled();
        lastHit=read.entry().filter(value->value.base().boxes().isEmpty()&&value.lights().isEmpty()).isPresent();
        lastStatus=lastHit?"HIT":read.entry().isPresent()?"INVALID:Nonempty completion seal":read.status();lastKey=key;return lastHit;
    }
    public synchronized void save(AbyssLodPipeline.Input input)throws IOException {
        checkCanceled();String key=key(input);
        TerrainLodDiskCache.save(directory.resolve(key),key,EMPTY,SENTINEL,LIMITS);checkCanceled();
        lastHit=true;lastStatus="SAVED_AFTER_SCALAR_FLUSH";lastKey=key;
    }
    public Map<String,Object> status(){return Map.of("key",lastKey,"status",lastStatus,"hit",lastHit,"format","TerrainLodDiskCache-v1-empty-entry");}
    Path file(AbyssLodPipeline.Input input){String key=key(input);return TerrainLodDiskCache.file(directory.resolve(key),key);}
    private static String required(String value){if(Objects.requireNonNull(value).isEmpty())throw new IllegalArgumentException("Complete view authority required");return value;}
    private static void checkCanceled(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Exact view seal canceled");}
    private static final class Code {static final String VALUE=TerrainLodDiskCache.codeIdentity(AbyssLodCompletionSeal.class);}
}
