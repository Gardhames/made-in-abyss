package dev.madeinabyss.kernel.lod;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Objects;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;

/** Pure camera-local XYZ refinement planning; terrain sampling remains caller-owned. */
public final class TerrainLodRefinement {
    private TerrainLodRefinement() {}
    public record Band(int radius,int horizontalStep) {
        public Band {
            if(radius<16 || radius%16!=0 || horizontalStep<1 || horizontalStep>64
                    || (horizontalStep&(horizontalStep-1))!=0)
                throw new IllegalArgumentException("Radius must be a positive multiple of16; step a power of2 in1..64");
        }
    }
    public record Region(TerrainLodColumns.Bounds bounds,int horizontalStep) {
        public Region {
            Objects.requireNonNull(bounds,"bounds");
            if(horizontalStep<1 || horizontalStep>64 || (horizontalStep&(horizontalStep-1))!=0)
                throw new IllegalArgumentException("Step must be a power of2 in1..64");
        }
        public long sampledVoxels() {
            long nx=((long)bounds.maxXInclusive()+1-bounds.minX()+horizontalStep-1)/horizontalStep;
            long nz=((long)bounds.maxZInclusive()+1-bounds.minZ()+horizontalStep-1)/horizontalStep;
            long height=(long)bounds.maxYInclusive()+1-bounds.minY();
            try { return Math.multiplyExact(Math.multiplyExact(nx,nz),height); }
            catch(ArithmeticException overflow) {
                throw new TerrainLodColumns.BudgetExceededException("Refinement sample count exceeds long range");
            }
        }
    }
    public record Plan(List<Region> regions,Optional<TerrainLodClipper.BoundsLong> replacement,long sampledVoxels) {
        public Plan {
            regions=List.copyOf(regions); Objects.requireNonNull(replacement,"replacement");
            if(sampledVoxels<0) throw new IllegalArgumentException("Negative sample count");
        }
    }
    /**
     * Finer bands first. Radii and steps strictly increase. All axes snap by floor division
     * to16m, including negativeY. Regions replace exactly the clipped outer box, without
     * overlap; each column still samples every integerY. No terrain is queried here.
     * Region boundaries are deterministic cache keys within the same camera anchor.
     */
    public static Plan plan(TerrainLodColumns.Bounds world,int cameraX,int cameraY,int cameraZ,
            List<Band> bands,long maxSampleVoxels,int maxRegions) {
        Objects.requireNonNull(world,"world");
        var levels=List.copyOf(bands);
        if(levels.isEmpty() || maxSampleVoxels<1 || maxRegions<1)
            throw new IllegalArgumentException("Nonempty bands and positive budgets required");
        int previousRadius=0,previousStep=0;
        for(var band:levels) {
            if(band.radius()<=previousRadius || band.horizontalStep()<=previousStep)
                throw new IllegalArgumentException("Radii and steps must strictly increase");
            previousRadius=band.radius(); previousStep=band.horizontalStep();
        }
        long x=Math.floorDiv(cameraX,16)*16L,y=Math.floorDiv(cameraY,16)*16L,z=Math.floorDiv(cameraZ,16)*16L;
        var regions=new ArrayList<Region>();
        TerrainLodClipper.BoundsLong inner=null;
        long samples=0;
        for(var band:levels) {
            long radius=band.radius();
            long x0=Math.max(x-radius,world.minX()),x1=Math.min(x+radius,(long)world.maxXInclusive()+1);
            long y0=Math.max(y-radius,world.minY()),y1=Math.min(y+radius,(long)world.maxYInclusive()+1);
            long z0=Math.max(z-radius,world.minZ()),z1=Math.min(z+radius,(long)world.maxZInclusive()+1);
            if(x0>=x1 || y0>=y1 || z0>=z1) continue;
            var outer=new TerrainLodClipper.BoundsLong(x0,y0,z0,x1,y1,z1);
            var volume=new TerrainLodColumns.Box(x0,y0,z0,x1,y1,z1,TerrainMaterial.ROCK);
            var slabs=TerrainLodClipper.subtract(List.of(volume),inner==null?List.of():List.of(inner),maxRegions);
            for(var slab:slabs) {
                if(regions.size()>=maxRegions)
                    throw new TerrainLodColumns.BudgetExceededException("Refinement regions exceed budget "+maxRegions);
                var b=new TerrainLodColumns.Bounds((int)slab.minX(),(int)slab.minY(),(int)slab.minZ(),
                        (int)(slab.maxXExclusive()-1),(int)(slab.maxYExclusive()-1),(int)(slab.maxZExclusive()-1));
                var region=new Region(b,band.horizontalStep());
                long count=region.sampledVoxels();
                if(count>maxSampleVoxels-samples)
                    throw new TerrainLodColumns.BudgetExceededException("Refinement samples exceed budget "+maxSampleVoxels);
                samples+=count; regions.add(region);
            }
            inner=outer;
        }
        return new Plan(regions,Optional.ofNullable(inner),samples);
    }
}
