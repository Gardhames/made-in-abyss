package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/** Bounded horizontal LOD approximation; global vertical intervals are not physics occupancy. */
public final class TerrainLodColumns {
    private TerrainLodColumns() {}
    public record Bounds(int minX,int minY,int minZ,int maxXInclusive,int maxYInclusive,int maxZInclusive) {
        public Bounds {
            if(minX>maxXInclusive || minY>maxYInclusive || minZ>maxZInclusive)
                throw new IllegalArgumentException("Bounds must contain at least one voxel");
        }
    }
    public record Parameters(int horizontalStep,long maxSampleVoxels,int maxBoxes) {
        public Parameters {
            if(horizontalStep<1 || horizontalStep>64 || maxSampleVoxels<1 || maxBoxes<1)
                throw new IllegalArgumentException("Step must be 1..64; sample and box budgets must be positive");
        }
    }
    public record Box(long minX,long minY,long minZ,long maxXExclusive,long maxYExclusive,long maxZExclusive,
                      TerrainMaterial material) {
        public Box {
            Objects.requireNonNull(material,"material");
            if(minX>=maxXExclusive || minY>=maxYExclusive || minZ>=maxZExclusive || material==TerrainMaterial.AIR)
                throw new IllegalArgumentException("Box must have positive spans and a non-air material");
        }
    }
    public record Result(List<Box> boxes,long sampledVoxels) {
        public Result {
            boxes=CompactLodLists.immutableBoxes(boxes);
            if(sampledVoxels<0) throw new IllegalArgumentException("Negative sample count");
        }
    }
    public static final class BudgetExceededException extends IllegalArgumentException {
        public BudgetExceededException(String message) { super(message); }
    }
    public static final class UnknownSampleException extends IllegalArgumentException {
        private final int x,y,z;
        private final TerrainSample.Reason reason;
        private UnknownSampleException(int x,int y,int z,TerrainSample.Reason reason) {
            super("Unavailable terrain at "+x+","+y+","+z+": "+reason);
            this.x=x; this.y=y; this.z=z; this.reason=reason;
        }
        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public TerrainSample.Reason reason() { return reason; }
    }
    /**
     * Samples the lower integer midpoint of each clipped XZ tile, at EVERY integer Y.
     * Tile order is X then Z; intervals ascend in Y. No partial scene escapes on refusal.
     * Horizontal spreading is approximate geology: neither occupied-cell proof nor a collision mesh.
     */
    public static Result build(TrailNetwork.SampleSource source,Bounds bounds,Parameters parameters) {
        Objects.requireNonNull(source,"source");
        Objects.requireNonNull(bounds,"bounds");
        Objects.requireNonNull(parameters,"parameters");
        long endX=(long)bounds.maxXInclusive()+1, endY=(long)bounds.maxYInclusive()+1,
                endZ=(long)bounds.maxZInclusive()+1;
        long step=parameters.horizontalStep();
        long nx=(endX-bounds.minX()+step-1)/step, nz=(endZ-bounds.minZ()+step-1)/step;
        long height=endY-bounds.minY(), budget=parameters.maxSampleVoxels();
        // Division before multiplication also refuses theoretical int-world workloads above Long.MAX_VALUE.
        if(height>budget || nx>budget/height || nz>budget/(nx*height))
            throw new BudgetExceededException("Column samples exceed budget "+budget);
        long samples=nx*nz*height;
        var boxes=new ArrayList<Box>();
        for(long x=bounds.minX();x<endX;x+=step) {
            long tileEndX=Math.min(x+step,endX);
            int sampleX=(int)(x+(tileEndX-x-1)/2);
            for(long z=bounds.minZ();z<endZ;z+=step) {
                long tileEndZ=Math.min(z+step,endZ);
                int sampleZ=(int)(z+(tileEndZ-z-1)/2);
                TerrainMaterial runMaterial=null;
                long runStart=bounds.minY();
                for(long y=bounds.minY();y<endY;y++) {
                    TerrainSample sample=Objects.requireNonNull(source.sample(sampleX,(int)y,sampleZ),"terrain sample");
                    if(sample instanceof TerrainSample.Unavailable unavailable)
                        throw new UnknownSampleException(sampleX,(int)y,sampleZ,unavailable.reason());
                    TerrainMaterial material=Objects.requireNonNull(((TerrainSample.Known)sample).material(),"known material");
                    if(material!=runMaterial) {
                        emit(boxes,parameters.maxBoxes(),x,runStart,z,tileEndX,y,tileEndZ,runMaterial);
                        runStart=y; runMaterial=material;
                    }
                }
                emit(boxes,parameters.maxBoxes(),x,runStart,z,tileEndX,endY,tileEndZ,runMaterial);
            }
        }
        return new Result(boxes,samples);
    }
    private static void emit(List<Box> boxes,int cap,long x,long y,long z,long endX,long endY,long endZ,
                             TerrainMaterial material) {
        if(material==null || material==TerrainMaterial.AIR) return;
        if(boxes.size()>=cap) throw new BudgetExceededException("Column boxes exceed budget "+cap);
        boxes.add(new Box(x,y,z,endX,endY,endZ,material));
    }
}
