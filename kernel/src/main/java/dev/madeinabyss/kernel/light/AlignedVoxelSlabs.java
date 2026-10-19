package dev.madeinabyss.kernel.light;
import java.util.ArrayList;
import java.util.List;

/** Contiguous half-open owner ranges; internal seams always lie on global 16-block faces. */
public final class AlignedVoxelSlabs {
    private AlignedVoxelSlabs() {}
    public record Slab(int minimum,int limit) {}
    /** One independently claimable global cube interval, including clipped end intervals. */
    public static List<Slab> tiles(int minimum,int size) {
        long end=(long)minimum+size;
        if(size<1||end>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid bounded tile extent");
        var ranges=new ArrayList<Slab>();long cursor=minimum;
        while(cursor<end) {
            long limit=Math.min(end,Math.floorDiv(cursor,16)*16+16);
            ranges.add(new Slab((int)cursor,(int)limit));cursor=limit;
        }
        return List.copyOf(ranges);
    }
    public static List<Slab> plan(int minimum,int size,int workers) {
        long end=(long)minimum+size;
        if(size<1||workers<1||workers>4||end>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid bounded slab extent");
        var ranges=new ArrayList<Slab>();int cursor=minimum;
        for(int i=1;i<workers;i++) {
            long candidate=Math.floorDiv((long)minimum+(long)size*i/workers,16)*16;
            if(candidate>cursor&&candidate<end){ranges.add(new Slab(cursor,(int)candidate));cursor=(int)candidate;}
        }
        if(cursor<end)ranges.add(new Slab(cursor,(int)end));return List.copyOf(ranges);
    }
}
