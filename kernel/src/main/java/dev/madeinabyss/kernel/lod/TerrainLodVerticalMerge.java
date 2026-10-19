package dev.madeinabyss.kernel.lod;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/** Exact vertical compaction of cache tile fragments; never bridges air or changes XZ occupancy. */
public final class TerrainLodVerticalMerge {
    private TerrainLodVerticalMerge() {}
    public static List<TerrainLodColumns.Box> merge(List<TerrainLodColumns.Box> boxes,int maxBoxes) {
        if(maxBoxes<1)throw new IllegalArgumentException("Positive merged box budget required");
        if(boxes.size()>4L*maxBoxes)throw new TerrainLodColumns.BudgetExceededException("Temporary fragment budget exceeded");
        var sorted=new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingLong(TerrainLodColumns.Box::minX).thenComparingLong(TerrainLodColumns.Box::maxXExclusive)
                .thenComparingLong(TerrainLodColumns.Box::minZ).thenComparingLong(TerrainLodColumns.Box::maxZExclusive)
                .thenComparing(b->b.material().ordinal()).thenComparingLong(TerrainLodColumns.Box::minY));
        var merged=new ArrayList<TerrainLodColumns.Box>();
        for(var box:sorted) {
            if(!merged.isEmpty()) {
                var previous=merged.get(merged.size()-1);
                boolean sameColumn=previous.minX()==box.minX()&&previous.maxXExclusive()==box.maxXExclusive()
                        &&previous.minZ()==box.minZ()&&previous.maxZExclusive()==box.maxZExclusive()&&previous.material()==box.material();
                if(sameColumn&&previous.maxYExclusive()>box.minY())throw new IllegalArgumentException("Overlapping equal-material refinement fragments");
                if(sameColumn&&previous.maxYExclusive()==box.minY()) {
                    merged.set(merged.size()-1,new TerrainLodColumns.Box(previous.minX(),previous.minY(),previous.minZ(),
                            previous.maxXExclusive(),box.maxYExclusive(),previous.maxZExclusive(),previous.material()));continue;
                }
            }
            merged.add(box);
        }
        if(merged.size()>maxBoxes)throw new TerrainLodColumns.BudgetExceededException("Merged box budget exceeded");
        return List.copyOf(merged);
    }
}
