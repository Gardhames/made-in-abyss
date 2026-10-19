package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;

/** Each fallback grid samples the authoritative source at its own original midpoints. */
public final class AbyssLodFarField {
    private AbyssLodFarField() { }
    public record Built(TerrainLodColumns.Result geometry,int step,long chargedSamples) { }
    public static Built build(TrailNetwork.SampleSource source,TerrainLodColumns.Bounds bounds,long sampleCap,int boxCap) {
        return build(source,bounds,sampleCap,boxCap,16);
    }
    public static Built build(TrailNetwork.SampleSource source,TerrainLodColumns.Bounds bounds,long sampleCap,int boxCap,int firstStep) {
        if(firstStep!=16&&firstStep!=32&&firstStep!=64)throw new IllegalArgumentException("Exact far source step16/32/64 required");
        long charged=0;
        for(int step=firstStep;step<=64;step*=2){
            long count=new TerrainLodRefinement.Region(bounds,step).sampledVoxels();
            if(count>sampleCap-charged)throw new TerrainLodColumns.BudgetExceededException("Far source attempts exceed fresh sample reserve");
            try{var result=TerrainLodColumns.build(source,bounds,new TerrainLodColumns.Parameters(step,count,boxCap));
                return new Built(result,step,Math.addExact(charged,count));}
            catch(TerrainLodColumns.BudgetExceededException actualBoxOverflow){charged=Math.addExact(charged,count);if(step==64)throw actualBoxOverflow;}
        }
        throw new AssertionError("No source grid attempt");
    }
}
