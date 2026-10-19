package dev.madeinabyss.kernel.lod;

import java.util.*;

/** Acquisition order only: same complete source lattices, grouped by their64m archive page.
 * The displayed FAR panorama is independent. Finer data precedes redundant coarse work
 * within a page, so the existing exact coverage check can skip the latter. */
public final class AbyssLodSourcePages {
    private AbyssLodSourcePages(){}
    private record Page(int x,int y,int z){}
    public static List<TerrainLodRefinement.Region> order(List<TerrainLodRefinement.Region> regions,
            double x,double y,double z,double forwardX,double forwardY,double forwardZ){
        Objects.requireNonNull(regions);if(regions.size()>262144)throw new IllegalArgumentException("Source page order exceeds bounded job count");
        for(double value:new double[]{x,y,z,forwardX,forwardY,forwardZ})if(!Double.isFinite(value))throw new IllegalArgumentException("Finite source view required");
        var groups=new HashMap<Page,ArrayList<TerrainLodRefinement.Region>>();
        for(var region:regions){var b=Objects.requireNonNull(region).bounds();var key=new Page(Math.floorDiv(b.minX(),64),Math.floorDiv(b.minY(),64),Math.floorDiv(b.minZ(),64));
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(region);}
        var pages=new ArrayList<>(groups.keySet());pages.sort(Comparator.comparingInt((Page p)->{
            double dx=clamp(x,p.x)-x,dy=clamp(y,p.y)-y,dz=clamp(z,p.z)-z,d=Math.hypot(Math.hypot(dx,dy),dz);
            return d==0||dx*forwardX+dy*forwardY+dz*forwardZ>=d*.25?0:1;
        }).thenComparingDouble(p->{double dx=clamp(x,p.x)-x,dy=clamp(y,p.y)-y,dz=clamp(z,p.z)-z;return dx*dx+dy*dy+dz*dz;})
            .thenComparingInt(Page::x).thenComparingInt(Page::y).thenComparingInt(Page::z));
        var inside=Comparator.comparingInt(TerrainLodRefinement.Region::horizontalStep)
            .thenComparingInt(r->r.bounds().minX()).thenComparingInt(r->r.bounds().minZ()).thenComparingInt(r->r.bounds().minY())
            .thenComparingInt(r->r.bounds().maxXInclusive()).thenComparingInt(r->r.bounds().maxZInclusive()).thenComparingInt(r->r.bounds().maxYInclusive());
        var result=new ArrayList<TerrainLodRefinement.Region>(regions.size());
        for(var page:pages){var values=groups.get(page);values.sort(inside);result.addAll(values);}
        return List.copyOf(result);
    }
    private static double clamp(double value,int page){long min=(long)page*64;return Math.max(min,Math.min(min+63,value));}
}
