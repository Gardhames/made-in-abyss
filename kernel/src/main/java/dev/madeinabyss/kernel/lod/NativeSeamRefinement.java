package dev.madeinabyss.kernel.lod;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;

/** Fine transition measured from an accepted native volume, rather than the player's centre.
 * The native interior is omitted only where its inscribed rectangle guarantees coverage.
 * Returned regions require per-tile replacement; replacement bounds describe the extent,
 * not a solid region to subtract from the retained base. Sampling every integer Y remains exact. */
public final class NativeSeamRefinement {
    private NativeSeamRefinement() {}
    public record NativeVolume(double anchorX,double anchorZ,int radius,int minY,int maxYExclusive) {
        public NativeVolume {
            if(!Double.isFinite(anchorX)||!Double.isFinite(anchorZ)||anchorX<Integer.MIN_VALUE||anchorX>Integer.MAX_VALUE
                ||anchorZ<Integer.MIN_VALUE||anchorZ>Integer.MAX_VALUE||radius<32||radius>128
                ||minY>=maxYExclusive||(long)maxYExclusive-minY>321)
                throw new IllegalArgumentException("Native seam requires radius32..128 and accepted height1..321 in signed coordinates");
        }
    }
    public static TerrainLodRefinement.Plan plan(TerrainLodColumns.Bounds world,NativeVolume nativeVolume,long maxSampleVoxels,int maxRegions) {
        Objects.requireNonNull(world);Objects.requireNonNull(nativeVolume);
        if(maxSampleVoxels<1||maxRegions<1)throw new IllegalArgumentException("Positive seam budgets required");
        var regions=new ArrayList<TerrainLodRefinement.Region>();
        double safe=nativeVolume.radius/Math.sqrt(2)-2;
        TerrainLodClipper.BoundsLong inner=bounds(ceil16(nativeVolume.anchorX-safe),ceil16(nativeVolume.minY),ceil16(nativeVolume.anchorZ-safe),
            floor16(nativeVolume.anchorX+safe),floor16(nativeVolume.maxYExclusive),floor16(nativeVolume.anchorZ+safe));
        TerrainLodClipper.BoundsLong extent=null;long samples=0;
        int[] margins={32,64,128},steps={1,2,4};
        for(int i=0;i<margins.length;i++) {
            long radius=(long)nativeVolume.radius+margins[i];
            var outer=bounds(Math.max(world.minX(),floor16(nativeVolume.anchorX-radius)),Math.max(world.minY(),floor16((long)nativeVolume.minY-margins[i])),
                Math.max(world.minZ(),floor16(nativeVolume.anchorZ-radius)),Math.min((long)world.maxXInclusive()+1,ceil16(nativeVolume.anchorX+radius)),
                Math.min((long)world.maxYInclusive()+1,ceil16((long)nativeVolume.maxYExclusive+margins[i])),Math.min((long)world.maxZInclusive()+1,ceil16(nativeVolume.anchorZ+radius)));
            if(outer==null)continue;
            var volume=new TerrainLodColumns.Box(outer.minX(),outer.minY(),outer.minZ(),outer.maxXExclusive(),outer.maxYExclusive(),outer.maxZExclusive(),TerrainMaterial.ROCK);
            var slabs=TerrainLodClipper.subtract(List.of(volume),inner==null?List.of():List.of(inner),6);
            for(var slab:slabs) {
                if(regions.size()>=maxRegions)throw new TerrainLodColumns.BudgetExceededException("Native seam region budget exceeded");
                var r=new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds((int)slab.minX(),(int)slab.minY(),(int)slab.minZ(),
                    (int)(slab.maxXExclusive()-1),(int)(slab.maxYExclusive()-1),(int)(slab.maxZExclusive()-1)),steps[i]);
                long count=r.sampledVoxels();if(count>maxSampleVoxels-samples)throw new TerrainLodColumns.BudgetExceededException("Native seam sample budget exceeded");
                samples+=count;regions.add(r);
            }
            inner=outer;extent=outer;
        }
        return new TerrainLodRefinement.Plan(regions,Optional.ofNullable(extent),samples);
    }
    private static long floor16(double n){return (long)Math.floor(n/16)*16;}
    private static long ceil16(double n){return (long)Math.ceil(n/16)*16;}
    private static TerrainLodClipper.BoundsLong bounds(long x0,long y0,long z0,long x1,long y1,long z1) {
        return x0>=x1||y0>=y1||z0>=z1?null:new TerrainLodClipper.BoundsLong(x0,y0,z0,x1,y1,z1);
    }
}
