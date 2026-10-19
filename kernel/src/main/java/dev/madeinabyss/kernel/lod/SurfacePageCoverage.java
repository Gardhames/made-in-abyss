package dev.madeinabyss.kernel.lod;

import java.util.Objects;
import java.util.Set;

/** Current page versions required before a complete RAW grid can become displayed proof. */
public final class SurfacePageCoverage {
    private SurfacePageCoverage(){}
    public static boolean covers(TerrainLodColumns.Bounds grid,Set<SurfaceQuadPlan.PageKey> current,
            Set<SurfaceQuadPlan.PageKey> target,Set<SurfaceQuadPlan.PageKey> published,int pageMetres){
        Objects.requireNonNull(grid);Objects.requireNonNull(current);Objects.requireNonNull(target);Objects.requireNonNull(published);
        if(pageMetres!=64&&pageMetres!=128)throw new IllegalArgumentException("Surface packing is64 or128 metres");
        // Include the positive face plane, which may be in the next signed page.
        long x0=Math.floorDiv(grid.minX(),pageMetres),x1=Math.floorDiv((long)grid.maxXInclusive()+1,pageMetres),
             y0=Math.floorDiv(grid.minY(),pageMetres),y1=Math.floorDiv((long)grid.maxYInclusive()+1,pageMetres),
             z0=Math.floorDiv(grid.minZ(),pageMetres),z1=Math.floorDiv((long)grid.maxZInclusive()+1,pageMetres);
        for(long x=x0;x<=x1;x++)for(long y=y0;y<=y1;y++)for(long z=z0;z<=z1;z++){
            var key=new SurfaceQuadPlan.PageKey(x,y,z);
            if((target.contains(key)||published.contains(key))&&!current.contains(key))return false;
        }
        return true;
    }
}
