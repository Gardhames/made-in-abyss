package dev.madeinabyss.kernel.lod;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/** Pure half-open global-coordinate clipping for LOD visuals, independent of loaders/renderers. */
public final class TerrainLodClipper {
    private TerrainLodClipper() {}
    public record BoundsLong(long minX,long minY,long minZ,long maxXExclusive,long maxYExclusive,long maxZExclusive) {
        public BoundsLong {
            if(minX>=maxXExclusive || minY>=maxYExclusive || minZ>=maxZExclusive)
                throw new IllegalArgumentException("Exclusion must have positive half-open spans");
        }
    }
    /**
     * Preserves input/material order. For each exclusion the deterministic slab order is
     * X-low, X-high, Y-low, Y-high, Z-low, Z-high. Fragments of each source are disjoint;
     * existing overlap between distinct source boxes is preserved. Both intermediate
     * fragment lists and the final scene obey maxBoxes; excess rejects the whole operation.
     */
    public static List<TerrainLodColumns.Box> subtract(List<TerrainLodColumns.Box> input,
            List<BoundsLong> exclusions,int maxBoxes) {
        Objects.requireNonNull(input,"input");
        var cuts=List.copyOf(exclusions);
        if(maxBoxes<1) throw new IllegalArgumentException("Positive box budget required");
        var result=new ArrayList<TerrainLodColumns.Box>();
        for(var source:input) {
            List<TerrainLodColumns.Box> fragments=List.of(Objects.requireNonNull(source,"source box"));
            for(var cut:cuts) {
                if(fragments.isEmpty()) break;
                // Every fragment is a subset of source, so a disjoint source needs no work/list allocation.
                if(source.minX()>=cut.maxXExclusive() || source.maxXExclusive()<=cut.minX()
                        || source.minY()>=cut.maxYExclusive() || source.maxYExclusive()<=cut.minY()
                        || source.minZ()>=cut.maxZExclusive() || source.maxZExclusive()<=cut.minZ()) continue;
                var next=new ArrayList<TerrainLodColumns.Box>();
                for(var box:fragments) clip(box,cut,next,maxBoxes);
                fragments=next;
            }
            for(var fragment:fragments) append(result,fragment,maxBoxes);
        }
        return List.copyOf(result);
    }
    private static void clip(TerrainLodColumns.Box b,BoundsLong cut,List<TerrainLodColumns.Box> out,int cap) {
        long x0=Math.max(b.minX(),cut.minX()),x1=Math.min(b.maxXExclusive(),cut.maxXExclusive());
        long y0=Math.max(b.minY(),cut.minY()),y1=Math.min(b.maxYExclusive(),cut.maxYExclusive());
        long z0=Math.max(b.minZ(),cut.minZ()),z1=Math.min(b.maxZExclusive(),cut.maxZExclusive());
        if(x0>=x1 || y0>=y1 || z0>=z1) { append(out,b,cap); return; }
        // Partition the complement into slabs without arithmetic on endpoint spans.
        emit(out,cap,b,b.minX(),b.minY(),b.minZ(),x0,b.maxYExclusive(),b.maxZExclusive());
        emit(out,cap,b,x1,b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());
        emit(out,cap,b,x0,b.minY(),b.minZ(),x1,y0,b.maxZExclusive());
        emit(out,cap,b,x0,y1,b.minZ(),x1,b.maxYExclusive(),b.maxZExclusive());
        emit(out,cap,b,x0,y0,b.minZ(),x1,y1,z0);
        emit(out,cap,b,x0,y0,z1,x1,y1,b.maxZExclusive());
    }
    private static void emit(List<TerrainLodColumns.Box> out,int cap,TerrainLodColumns.Box source,
                             long x0,long y0,long z0,long x1,long y1,long z1) {
        if(x0>=x1 || y0>=y1 || z0>=z1) return;
        append(out,new TerrainLodColumns.Box(x0,y0,z0,x1,y1,z1,source.material()),cap);
    }
    private static void append(List<TerrainLodColumns.Box> out,TerrainLodColumns.Box box,int cap) {
        if(out.size()>=cap) throw new TerrainLodColumns.BudgetExceededException("Clipped boxes exceed budget "+cap);
        out.add(box);
    }
}
