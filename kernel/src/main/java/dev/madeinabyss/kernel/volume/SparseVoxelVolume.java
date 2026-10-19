package dev.madeinabyss.kernel.volume;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.madeinabyss.kernel.volume.VoxelVolumeArchive.*;

/** One immutable source epoch, incrementally materialized in16^3 bricks. All source
 * requests go through one producer; RAW reads this volume and never invokes a second sampler.
 * Explicit flush/evict permits full offline scans with bounded residency. */
public final class SparseVoxelVolume {
    @FunctionalInterface public interface Producer { VoxelBrick capture(CubeKey key,Runnable checkpoint); }
    private static final class Resident {
        final VoxelBrick[] bricks;final long[] revisions;boolean dirty,retired;long bytes=4096;
        Resident(Page page){bricks=page==null?new VoxelBrick[512]:page.bricks();revisions=page==null?new long[512]:page.revisions();for(var b:bricks)if(b!=null)bytes+=b.payloadBytes();}
    }
    private final VoxelPalette palette;
    private final VoxelVolumeArchive archive;
    private final Producer producer;
    private final Runnable authority;
    private final long maxResidentBytes;
    private final ConcurrentHashMap<PageKey,Resident> pages=new ConcurrentHashMap<>();
    private final AtomicLong residentBytes=new AtomicLong(),peakResidentBytes=new AtomicLong();
    private final LongAdder builds=new LongAdder(),hits=new LongAdder(),restored=new LongAdder(),written=new LongAdder();
    private volatile String lastRead="NOT_READ";
    public SparseVoxelVolume(VoxelPalette palette,VoxelVolumeArchive archive,Producer producer,Runnable authority,long maxResidentBytes) {
        this.palette=Objects.requireNonNull(palette);this.archive=Objects.requireNonNull(archive);this.producer=Objects.requireNonNull(producer);this.authority=Objects.requireNonNull(authority);
        if(maxResidentBytes<8192)throw new IllegalArgumentException("Positive volume residency budget required");this.maxResidentBytes=maxResidentBytes;
    }
    private void check(Runnable checkpoint){if(Thread.currentThread().isInterrupted())throw new CancellationException("Volume request cancelled");authority.run();checkpoint.run();}
    private void charge(long bytes) {
        for(;;){long old=residentBytes.get();if(bytes>maxResidentBytes-old)throw new IllegalStateException("Volume residency budget exceeded; flush/evict a completed page");
            if(residentBytes.compareAndSet(old,old+bytes)){peakResidentBytes.accumulateAndGet(old+bytes,Math::max);return;}}
    }
    private Resident page(PageKey key) {
        return pages.computeIfAbsent(key,k->{var read=archive.read(k);lastRead=read.status();var resident=new Resident(read.page());charge(resident.bytes);if(read.page()!=null)for(var b:resident.bricks)if(b!=null)restored.increment();return resident;});
    }
    public VoxelBrick requireBrick(CubeKey key,Runnable checkpoint) {
        var pageKey=PageKey.ofBrick(key.x(),key.y(),key.z());int slot=PageKey.slot(key.x(),key.y(),key.z());
        for(;;){check(checkpoint);var page=page(pageKey);synchronized(page){if(page.retired)continue;
            var old=page.bricks[slot];if(old!=null){hits.increment();return old;}
            var made=Objects.requireNonNull(producer.capture(key,()->check(checkpoint)));check(checkpoint);
            // Validation is against the captured stable palette, never opacity-inferred state.
            for(int i=0;i<4096;i++){int state=made.stateAt(i);if(state>=0&&made.opacityAt(i)!=palette.entry(state).opacity())throw new IllegalArgumentException("Producer state/opacity mismatch");}
            charge(made.payloadBytes());page.bytes+=made.payloadBytes();page.bricks[slot]=made;page.revisions[slot]=0;page.dirty=true;builds.increment();return made;
        }}
    }
    /** Missing residency and an unbuilt brick remain UNKNOWN; this does no IO/generation. */
    public int opacityIfResident(int x,int y,int z) {
        authority.run();var page=pages.get(PageKey.ofBrick(x>>4,y>>4,z>>4));if(page==null)return -1;
        synchronized(page){var brick=page.bricks[PageKey.slot(x>>4,y>>4,z>>4)];return page.retired||brick==null?-1:brick.opacityAt(index(x,y,z));}
    }
    public static int index(int x,int y,int z){return ((y&15)<<8)|((z&15)<<4)|(x&15);}
    /** Caller-owned cursor: old RAW midpoint/each-Y policy is preserved by TerrainLodColumns. */
    public TrailNetwork.SampleSource rawSource(Runnable checkpoint) {
        return new TrailNetwork.SampleSource(){CubeKey key;VoxelBrick brick;
            @Override public TerrainSample sample(int xx,int yy,int zz){check(checkpoint);var next=new CubeKey(xx>>4,yy>>4,zz>>4);
                if(!next.equals(key)){brick=requireBrick(next,checkpoint);key=next;}int state=brick.stateAt(index(xx,yy,zz));
                if(state<0)return new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER);
                var material=palette.entry(state).material();if(material==null)throw new IllegalArgumentException("Unsupported RAW material "+palette.entry(state).state());return new TerrainSample.Known(material);
            }};
    }
    public long flushAndEvict(PageKey key,Runnable checkpoint)throws IOException {
        check(checkpoint);var page=pages.get(key);if(page==null)return 0;
        synchronized(page){if(page.retired)return 0;check(checkpoint);long bytes=0;if(page.dirty){bytes=archive.write(key,new Page(page.bricks,page.revisions));written.add(bytes);page.dirty=false;}
            check(checkpoint);if(pages.remove(key,page)){page.retired=true;residentBytes.addAndGet(-page.bytes);}return bytes;}
    }
    public void checkpoint(Runnable checkpoint)throws IOException{for(var key:List.copyOf(pages.keySet()))flushAndEvict(key,checkpoint);}
    public Map<String,Object> status(){return Map.of("bricksBuilt",builds.sum(),"brickHits",hits.sum(),"bricksRestored",restored.sum(),"residentBytes",residentBytes.get(),"peakResidentBytes",peakResidentBytes.get(),"writtenBytes",written.sum(),"lastRead",lastRead,"scope",archive.scope());}
    public VoxelPalette palette(){return palette;}
    public VoxelVolumeArchive archive(){return archive;}
}
