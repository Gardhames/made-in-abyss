package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;
import dev.madeinabyss.kernel.lod.ExactBoxLightDiskCache;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;

/** Exact whole-owner scalars, spatially paged using the existing checksummed scalar file format. */
public final class AbyssLodScalarArchive implements AutoCloseable {
    public static final long DEFAULT_MAX_RESIDENT_BYTES=64L*1024*1024;
    @FunctionalInterface public interface PageAuthority {String identity(long pageMinX,long pageMinY,long pageMinZ);}
    private static final long PAGE_BYTES=128,ENTRY_BYTES=128;
    private static final int MAX_RESIDENT_PAGES=512;
    private record Page(long x,long y,long z) { }
    private static final class Contents {
        final Map<TerrainLodColumns.Box,TerrainLodDiskCache.Light> values;
        final ExactBoxLightDiskCache.Scope scope;
        boolean dirty;
        Contents(ExactBoxLightDiskCache.Scope scope,Map<TerrainLodColumns.Box,TerrainLodDiskCache.Light> values){this.scope=scope;this.values=new LinkedHashMap<>(values);}
        long bytes(){return PAGE_BYTES+ENTRY_BYTES*values.size();}
    }
    private final Path directory;
    private final ExactBoxLightDiskCache.Scope scope;
    private final long maximumResidentBytes;
    private final PageAuthority authority;
    private final LinkedHashMap<Page,Contents> pages=new LinkedHashMap<>(16,.75f,true);
    private long mutations,checkpointMutations,lastCheckpointNanos;
    private volatile boolean closed;
    private volatile String status="PAGED_LAZY",lastRead="NOT_READ";
    private volatile long hits,misses,diskHits,diskMisses,diskReads,entriesRead,writes,entriesWritten,evictions;
    private volatile long residentBytes,peakResidentBytes,residentEntries,dirtyPages,dirtyEntries;
    private volatile int residentPages;
    public AbyssLodScalarArchive(Path directory,String sourceIdentity,String physicalCode,
                                  TerrainLodColumns.Bounds bounds,int maximumEntries) {
        this(directory,sourceIdentity,physicalCode,bounds,maximumEntries,DEFAULT_MAX_RESIDENT_BYTES);
    }
    /** maximumEntries is a per-page file limit; the complete archive has no global entry-count cap. */
    public AbyssLodScalarArchive(Path directory,String sourceIdentity,String physicalCode,
                                  TerrainLodColumns.Bounds bounds,int maximumEntries,long maximumResidentBytes) {
        this(directory,sourceIdentity,physicalCode,bounds,maximumEntries,maximumResidentBytes,(x,y,z)->sourceIdentity);
    }
    public AbyssLodScalarArchive(Path directory,String archiveFamilyKey,String physicalCode,
                                  TerrainLodColumns.Bounds bounds,int maximumEntries,PageAuthority authority) {
        this(directory,archiveFamilyKey,physicalCode,bounds,maximumEntries,DEFAULT_MAX_RESIDENT_BYTES,authority);
    }
    /** Authority must describe an immutable source snapshot; dirty contents retain their original Scope. */
    public AbyssLodScalarArchive(Path directory,String sourceIdentity,String physicalCode,
                                  TerrainLodColumns.Bounds bounds,int maximumEntries,long maximumResidentBytes,PageAuthority authority) {
        this.directory=Objects.requireNonNull(directory);scope=new ExactBoxLightDiskCache.Scope(sourceIdentity,physicalCode,bounds,maximumEntries);
        if(maximumResidentBytes<PAGE_BYTES+ENTRY_BYTES||maximumResidentBytes>DEFAULT_MAX_RESIDENT_BYTES)
            throw new IllegalArgumentException("Scalar resident budget must be256 bytes..64MiB");
        this.maximumResidentBytes=maximumResidentBytes;this.authority=Objects.requireNonNull(authority);lastCheckpointNanos=System.nanoTime();
    }
    /** Worker-only: null is unknown, never geometry or render acknowledgement. */
    public AbyssLodPipeline.Light get(TerrainLodColumns.Box box)throws IOException {return get(box,()->{});}
    public synchronized AbyssLodPipeline.Light get(TerrainLodColumns.Box box,Runnable authorityGuard)throws IOException {
        authorityGuard.run();ensureOpen();validate(box);var value=load(page(box)).values.get(box);authorityGuard.run();
        if(value==null){misses++;return null;}hits++;return new AbyssLodPipeline.Light(value.sky(),value.block());
    }
    /** Worker-only. Buffer scalars; dirty eviction atomically publishes a complete page file. */
    public void put(TerrainLodColumns.Box box,AbyssLodPipeline.Light light)throws IOException {put(box,light,()->{});}
    public synchronized void put(TerrainLodColumns.Box box,AbyssLodPipeline.Light light,Runnable authorityGuard)throws IOException {
        authorityGuard.run();ensureOpen();validate(box);Objects.requireNonNull(light);var key=page(box);var contents=load(key);authorityGuard.run();
        var exact=new TerrainLodDiskCache.Light(light.sky(),light.block());var previous=contents.values.get(box);
        if(previous!=null){if(!previous.equals(exact))throw new IllegalStateException("Conflicting exact scalar in one authoritative scope");return;}
        if(contents.values.size()>=scope.maxBoxes())throw new IllegalArgumentException("Scalar owner page exceeds entry budget");
        if(contents.bytes()+ENTRY_BYTES>maximumResidentBytes)throw new IllegalArgumentException("Single scalar owner page exceeds resident budget");
        reserve(ENTRY_BYTES,0,key);checkCanceled();authorityGuard.run();contents.values.put(box,exact);residentBytes+=ENTRY_BYTES;residentEntries++;mutations++;
        if(!contents.dirty){contents.dirty=true;dirtyPages++;dirtyEntries+=contents.values.size()-1;}
        dirtyEntries++;peakResidentBytes=Math.max(peakResidentBytes,residentBytes);status="DIRTY";
    }
    /** Compatibility hook: paged reads are lazy; old root slots are preserved and never imported. */
    public void restore(BoundedReuseCache<TerrainLodColumns.Box,AbyssLodPipeline.Light> values){Objects.requireNonNull(values);ensureOpen();}
    /** Compatibility only: callers must record every completed scalar with put, not a FIFO snapshot. */
    public void checkpoint(BoundedReuseCache<TerrainLodColumns.Box,AbyssLodPipeline.Light> values,long computed,boolean force)throws IOException {
        Objects.requireNonNull(values);checkpoint(force);
    }
    public void checkpoint(boolean force)throws IOException {checkpoint(force,()->{});}
    public synchronized void checkpoint(boolean force,Runnable authorityGuard)throws IOException {
        authorityGuard.run();ensureOpen();long now=System.nanoTime();
        if(!force&&(mutations-checkpointMutations<4096||now-lastCheckpointNanos<2_000_000_000L))return;
        flushDirty();checkpointMutations=mutations;lastCheckpointNanos=System.nanoTime();status="SAVED";
    }
    public void flush()throws IOException {checkpoint(true);}
    /** Failure/cancellation keeps the archive open and uncommitted pages dirty for retry. */
    @Override public synchronized void close()throws IOException {
        if(closed)return;checkCanceled();flushDirty();closed=true;pages.clear();residentPages=0;residentEntries=0;residentBytes=0;status="CLOSED";
    }
    /** Worker-only strict reload retirement. Preserve old files in an ignored sibling and discard dirty buffers.
     * This also invalidates values written between native tag rebinding and its notification event. */
    public synchronized void quarantine()throws IOException {
        if(closed)return;checkCanceled();
        dev.madeinabyss.kernel.lod.DerivedCacheQuarantine.moveChild(directory.resolve("owner-pages-v1"),scope.key());
        closed=true;pages.clear();residentPages=0;residentEntries=0;residentBytes=dirtyPages=dirtyEntries=0;status="QUARANTINED_PHYSICAL_RELOAD";
    }
    private Contents load(Page key)throws IOException {
        var contents=pages.get(key);if(contents!=null)return contents;checkCanceled();var expected=pageScope(key);
        // One bounded file/decode is staged under this lock, independently of the resident map.
        // Scope.maxBoxes is <=400k; install occurs only after resident admission succeeds.
        var read=ExactBoxLightDiskCache.load(pageDirectory(key),expected);diskReads++;lastRead=read.status();
        if("HIT".equals(read.status()))diskHits++;else diskMisses++;
        for(var box:read.values().keySet())if(!page(box).equals(key)){
            lastRead="INVALID:Owner page mismatch";read=new ExactBoxLightDiskCache.Read(Map.of(),lastRead);break;
        }
        long bytes=PAGE_BYTES+ENTRY_BYTES*read.values().size();
        if(bytes>maximumResidentBytes)throw new IllegalArgumentException("Stored scalar owner page exceeds resident budget");
        reserve(bytes,1,null);checkCanceled();contents=new Contents(expected,read.values());pages.put(key,contents);
        residentBytes+=bytes;residentEntries+=contents.values.size();residentPages=pages.size();
        peakResidentBytes=Math.max(peakResidentBytes,residentBytes);entriesRead+=contents.values.size();return contents;
    }
    private void reserve(long bytes,int additionalPages,Page protectedPage)throws IOException {
        var iterator=pages.entrySet().iterator();
        while(residentBytes>maximumResidentBytes-bytes||pages.size()+additionalPages>MAX_RESIDENT_PAGES){
            if(!iterator.hasNext())throw new IllegalArgumentException("Scalar resident admission cannot fit one page");
            var entry=iterator.next();if(entry.getKey().equals(protectedPage))continue;
            // A failed atomic save must never discard dirty exact owners.
            save(entry.getKey(),entry.getValue());residentBytes-=entry.getValue().bytes();residentEntries-=entry.getValue().values.size();
            iterator.remove();residentPages=pages.size();evictions++;
        }
    }
    private void flushDirty()throws IOException {for(var entry:pages.entrySet()){checkCanceled();save(entry.getKey(),entry.getValue());}}
    private void save(Page key,Contents contents)throws IOException {
        if(!contents.dirty)return;checkCanceled();ExactBoxLightDiskCache.save(pageDirectory(key),contents.scope,contents.values);
        contents.dirty=false;dirtyPages--;dirtyEntries-=contents.values.size();writes++;entriesWritten+=contents.values.size();
    }
    private void validate(TerrainLodColumns.Box box){
        Objects.requireNonNull(box);var b=scope.bounds();
        if(box.minX()<b.minX()||box.minY()<b.minY()||box.minZ()<b.minZ()||box.maxXExclusive()>(long)b.maxXInclusive()+1
                ||box.maxYExclusive()>(long)b.maxYInclusive()+1||box.maxZExclusive()>(long)b.maxZInclusive()+1||box.material()==TerrainMaterial.AIR)
            throw new IllegalArgumentException("Scalar key outside authoritative geometry domain");
    }
    private static Page page(TerrainLodColumns.Box box){return new Page(Math.floorDiv(box.minX(),128),Math.floorDiv(box.minY(),128),Math.floorDiv(box.minZ(),128));}
    private ExactBoxLightDiskCache.Scope pageScope(Page page){return new ExactBoxLightDiskCache.Scope(authority.identity(Math.multiplyExact(page.x(),128),Math.multiplyExact(page.y(),128),Math.multiplyExact(page.z(),128)),
        TerrainLodDiskCache.identity("exact-owner-page-v1",scope.lightingCode(),page.x()+","+page.y()+","+page.z()),scope.bounds(),scope.maxBoxes());}
    private Path pageDirectory(Page page){return directory.resolve("owner-pages-v1").resolve(scope.key()).resolve(page.x()+"_"+page.y()+"_"+page.z());}
    private void ensureOpen(){checkCanceled();if(closed)throw new IllegalStateException("Scalar archive closed");}
    private static void checkCanceled(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Scalar archive worker canceled");}
    /** Approximate live counters only: diagnostics never wait behind filesystem IO. */
    public Map<String,Object> status(){var out=new LinkedHashMap<String,Object>();
        out.put("status",status);out.put("lastRead",lastRead);out.put("key",scope.key());out.put("layout","owner-pages-v1/128m/existing-scalar-v1");
        out.put("legacyRootSlots","PRESERVED_NOT_IMPORTED");out.put("hits",hits);out.put("misses",misses);out.put("diskHits",diskHits);out.put("diskMisses",diskMisses);
        out.put("diskReads",diskReads);out.put("entriesRead",entriesRead);out.put("restoredEntries",entriesRead);out.put("writes",writes);out.put("entriesWritten",entriesWritten);
        out.put("savedEntries",entriesWritten);out.put("evictions",evictions);out.put("residentPages",residentPages);out.put("residentEntries",residentEntries);
        out.put("entryCounters","CUMULATIVE_IO_NOT_UNIQUE_ARCHIVE_TOTAL");
        out.put("residentBytes",residentBytes);out.put("peakResidentBytes",peakResidentBytes);out.put("maxResidentBytes",maximumResidentBytes);out.put("maxResidentPages",MAX_RESIDENT_PAGES);
        out.put("dirtyPages",dirtyPages);out.put("dirtyEntries",dirtyEntries);out.put("maxPageEntries",scope.maxBoxes());out.put("globalEntryCap",false);return Map.copyOf(out);}
}
