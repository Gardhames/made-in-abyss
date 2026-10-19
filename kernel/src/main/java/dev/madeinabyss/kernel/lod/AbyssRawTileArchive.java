package dev.madeinabyss.kernel.lod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Exact XYZ source tiles; archived data is independent of the current render selection. */
public final class AbyssRawTileArchive {
    private final Path directory;
    private final String scope;
    private static final long MAX_RETAINED_BYTES=16L*1024*1024;
    private static final int MAX_RETAINED_PAGES=32;
    private final int maxRetainedPages;
    private record Page(int x,int y,int z) { }
    private static final class Contents {
        final LinkedHashMap<TerrainTileDiskCache.Key,List<TerrainLodColumns.Box>> tiles=new LinkedHashMap<>();
        boolean dirty;
        long bytes=128;
        Contents(Map<TerrainTileDiskCache.Key,List<TerrainLodColumns.Box>> loaded) {
            loaded.forEach((key,boxes)->{tiles.put(key,CompactLodLists.boxes(boxes));bytes+=weight(boxes);});
        }
    }
    private final LinkedHashMap<Page,Contents> pages=new LinkedHashMap<>(16,.75f,true);
    private long retainedBytes,hits,misses,diskReads,writtenPages,writtenTiles;
    private String lastRead="NOT_READ";
    private boolean retired;
    public AbyssRawTileArchive(Path directory,String sourceCodeIdentity) {
        this(directory,sourceCodeIdentity,MAX_RETAINED_PAGES);
    }
    /** Page count may grow for sparse scans; the original16MiB retained-byte cap is invariant. */
    public AbyssRawTileArchive(Path directory,String sourceCodeIdentity,int maxRetainedPages) {
        this.directory=Objects.requireNonNull(directory);
        if(maxRetainedPages<1||maxRetainedPages>8192)throw new IllegalArgumentException("RAW archive page count must be1..8192");
        this.maxRetainedPages=maxRetainedPages;
        if(Objects.requireNonNull(sourceCodeIdentity).isBlank())throw new IllegalArgumentException("Source/code scope required");
        scope=TerrainLodDiskCache.identity("abyss-xyz-source-pages-v1",sourceCodeIdentity);
    }
    /** Null is unknown; an empty list certifies the complete requested lattice as AIR. */
    public synchronized List<TerrainLodColumns.Box> get(TerrainTileDiskCache.Key key) {
        Objects.requireNonNull(key);ensureCurrent();
        var page=page(key);var contents=load(page);var result=contents.tiles.get(key);
        if(result==null)misses++;else hits++;
        // A very large page is safe to read on a worker but must not become unbounded retained memory.
        try{trim();}catch(IOException failed){lastRead="CHECKPOINT_REFUSED:"+failed.getClass().getSimpleName();}
        return result;
    }
    public synchronized void put(TerrainTileDiskCache.Key key,List<TerrainLodColumns.Box> boxes)throws IOException {
        Objects.requireNonNull(key);Objects.requireNonNull(boxes);ensureCurrent();
        TerrainTileDiskCache.validate(key,boxes);
        for(var box:boxes)if(box.material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.AIR)
            throw new IllegalArgumentException("AIR is represented by complete tile coverage, never a solid box");
        var immutable=CompactLodLists.boxes(boxes);var contents=load(page(key));var prior=contents.tiles.get(key);
        if(prior!=null){
            if(!prior.equals(immutable))throw new IllegalStateException("Conflicting immutable source lattice");
            trim();return;
        }
        contents.tiles.put(key,immutable);long added=weight(immutable);contents.bytes+=added;retainedBytes+=added;
        contents.dirty=true;trim();
    }
    /** Called on a preparation worker before publishing a partial scene, never on the renderer. */
    public synchronized void checkpoint()throws IOException {
        ensureCurrent();
        for(var entry:pages.entrySet()){checkCanceled();save(entry.getKey(),entry.getValue());}
        trim();
    }
    /** Worker-only reload retirement fences IO under this archive's lock and discards unsaved mixed tiles. */
    public synchronized void quarantine()throws IOException {
        if(retired)return;checkCanceled();DerivedCacheQuarantine.moveChild(directory,scope);
        pages.clear();retainedBytes=0;retired=true;lastRead="QUARANTINED_PHYSICAL_RELOAD";
    }
    private void ensureCurrent(){checkCanceled();if(retired)throw new java.util.concurrent.CancellationException("XYZ archive authority retired");}
    private static long weight(List<TerrainLodColumns.Box> boxes){return 128L+80L*boxes.size();}
    private static Page page(TerrainTileDiskCache.Key key){return new Page(Math.floorDiv(key.x(),64),Math.floorDiv(key.y(),64),Math.floorDiv(key.z(),64));}
    private String identity(Page page){return TerrainLodDiskCache.identity(scope,Integer.toString(page.x()),Integer.toString(page.y()),Integer.toString(page.z()));}
    private Path pageDirectory(Page page){return directory.resolve(scope).resolve(page.x()+"_"+page.y()+"_"+page.z());}
    private Contents load(Page page) {
        var contents=pages.get(page);if(contents!=null)return contents;
        checkCanceled();var read=TerrainTileDiskCache.load(pageDirectory(page),identity(page));diskReads++;lastRead=read.status();
        for(var key:read.tiles().keySet())if(!page(key).equals(page)){
            lastRead="INVALID:Page lattice mismatch";read=new TerrainTileDiskCache.Read(Map.of(),lastRead);break;
        }
        contents=new Contents(read.tiles());pages.put(page,contents);retainedBytes+=contents.bytes;return contents;
    }
    private void save(Page page,Contents contents)throws IOException {
        if(!contents.dirty)return;checkCanceled();
        TerrainTileDiskCache.save(pageDirectory(page),identity(page),contents.tiles);
        contents.dirty=false;writtenPages++;writtenTiles+=contents.tiles.size();
    }
    private void trim()throws IOException {
        var iterator=pages.entrySet().iterator();
        while((pages.size()>maxRetainedPages||retainedBytes>MAX_RETAINED_BYTES)&&iterator.hasNext()){
            var entry=iterator.next();try{save(entry.getKey(),entry.getValue());}
            finally{retainedBytes-=entry.getValue().bytes;iterator.remove();}
        }
    }
    private static void checkCanceled(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("XYZ archive worker canceled");}
    public synchronized Map<String,Object> status(){var out=new LinkedHashMap<String,Object>();
        out.put("scope",scope);out.put("hits",hits);out.put("misses",misses);out.put("diskReads",diskReads);out.put("writtenPages",writtenPages);out.put("writtenTiles",writtenTiles);
        out.put("retainedPages",pages.size());out.put("maxRetainedPages",maxRetainedPages);out.put("retainedBytes",retainedBytes);out.put("maxRetainedBytes",MAX_RETAINED_BYTES);out.put("lastRead",lastRead);return Map.copyOf(out);}
}
