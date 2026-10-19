package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import dev.madeinabyss.kernel.concurrent.BoundedOrderedWork;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Same sampling tiles and vertical intervals as the serial builder. */
public final class TerrainLodParallelBuild {
    private TerrainLodParallelBuild() {}
    public static List<TerrainLodColumns.Bounds> splitX(TerrainLodColumns.Bounds bounds,int step,int workers) {
        if(step<1||step>64||workers<1||workers>4)throw new IllegalArgumentException("Step1..64 and workers1..4 required");
        long tiles=((long)bounds.maxXInclusive()+1-bounds.minX()+step-1)/step;
        int count=(int)Math.min(workers,tiles);var result=new ArrayList<TerrainLodColumns.Bounds>(count);
        for(int i=0;i<count;i++) {
            long start=tiles*i/count,end=tiles*(i+1)/count;
            int min=Math.toIntExact((long)bounds.minX()+start*step);
            int max=Math.toIntExact(Math.min(bounds.maxXInclusive(),(long)bounds.minX()+end*step-1));
            result.add(new TerrainLodColumns.Bounds(min,bounds.minY(),bounds.minZ(),max,bounds.maxYInclusive(),bounds.maxZInclusive()));
        }
        return List.copyOf(result);
    }
    public static TerrainLodColumns.Result build(TrailNetwork.SampleSource source,TerrainLodColumns.Bounds bounds,
                                                TerrainLodColumns.Parameters limits,int workers,ExecutorService executor) {
        Objects.requireNonNull(source);
        long nx=((long)bounds.maxXInclusive()+1-bounds.minX()+limits.horizontalStep()-1)/limits.horizontalStep();
        long nz=((long)bounds.maxZInclusive()+1-bounds.minZ()+limits.horizontalStep()-1)/limits.horizontalStep();
        long height=(long)bounds.maxYInclusive()+1-bounds.minY(),budget=limits.maxSampleVoxels();
        if(height>budget||nx>budget/height||nz>budget/(nx*height))
            throw new TerrainLodColumns.BudgetExceededException("Parallel column samples exceed global budget "+budget);
        long expected=nx*nz*height;
        var slabs=splitX(bounds,limits.horizontalStep(),workers);
        var results=BoundedOrderedWork.map(slabs,workers,executor,slab->TerrainLodColumns.build(source,slab,limits));
        var boxes=new ArrayList<TerrainLodColumns.Box>();long sampled=0;
        for(var result:results) {
            sampled=Math.addExact(sampled,result.sampledVoxels());
            if((long)boxes.size()+result.boxes().size()>limits.maxBoxes())
                throw new TerrainLodColumns.BudgetExceededException("Parallel boxes exceed global budget "+limits.maxBoxes());
            boxes.addAll(result.boxes());
        }
        if(sampled!=expected)throw new IllegalStateException("Parallel sample coverage changed");
        return new TerrainLodColumns.Result(boxes,sampled);
    }
}
