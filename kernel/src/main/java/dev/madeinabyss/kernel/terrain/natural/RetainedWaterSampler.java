package dev.madeinabyss.kernel.terrain.natural;
import dev.madeinabyss.kernel.terrain.*;
import java.util.*;
/** v10 ORIGINAL finite pools/rills cut into retained soil. Every complete feature has
 * solid floors and closed banks; no unsupported spring, waterfall or runtime placement. */
public final class RetainedWaterSampler {
    public static final int MAX_CACHED_PLANS=1024;
    private static final int CELL=48,Y_CELL=16,EXTENT=12;
    @FunctionalInterface public interface Source{TerrainSample sample(int x,int y,int z);}
    @FunctionalInterface public interface Protection{boolean contains(int x,int y,int z);}
    @FunctionalInterface public interface SurfaceBand{boolean contains(int x,int y,int z);}
    public record PoolSite(int x,int y,int z,int radius,boolean rillAlongX,int depth) {}
    private record Cell(long x,long y,long z) {}
    private final long seed;private final Source source;private final Protection protection;private final SurfaceBand band;
    private final Map<Cell,Optional<PoolSite>> plans=new LinkedHashMap<>(128,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<Cell,Optional<PoolSite>> e){return size()>MAX_CACHED_PLANS;}};
    private static final TerrainSample WATER=new TerrainSample.Known(TerrainMaterial.WATER);
    public RetainedWaterSampler(long seed,Source source,Protection protection,SurfaceBand band){this.seed=seed;this.source=Objects.requireNonNull(source);this.protection=Objects.requireNonNull(protection);this.band=Objects.requireNonNull(band);}
    public TerrainSample sample(int x,int y,int z){return sample(x,y,z,source.sample(x,y,z));}
    public TerrainSample sample(int x,int y,int z,TerrainSample base) {
        Objects.requireNonNull(base);if(!(base instanceof TerrainSample.Known k)||!solid(k.material())||!band.contains(x,y,z)||protection.contains(x,y,z))return base;
        return siteAt(x,y,z).filter(s->wetDepth(s,x-s.x,z-s.z)>s.y-y&&s.y>=y).isPresent()?WATER:base;
    }
    public Optional<PoolSite> siteAt(int x,int y,int z) {
        if(!band.contains(x,y,z))return Optional.empty();long cx=Math.floorDiv(x,CELL),cz=Math.floorDiv(z,CELL);
        long tx=cx*CELL+16+(long)(unit(cx,0,cz,1)*16),tz=cz*CELL+16+(long)(unit(cx,0,cz,2)*16);
        if(!valid(tx)||!valid(tz)||Math.abs(tx-x)>EXTENT||Math.abs(tz-z)>EXTENT)return Optional.empty();
        for(long cy=Math.floorDiv(y,Y_CELL);cy<=Math.floorDiv((long)y+1,Y_CELL);cy++) {
            var key=new Cell(cx,cy,cz);Optional<PoolSite> site;
            synchronized(plans){site=plans.get(key);}if(site==null){site=plan(key,(int)tx,(int)tz);synchronized(plans){plans.put(key,site);}}
            if(site.isPresent()){var s=site.get();if(y<=s.y&&y>s.y-wetDepth(s,x-s.x,z-s.z))return site;}
        }
        return Optional.empty();
    }
    public int cachedPlanCount(){synchronized(plans){return plans.size();}}
    private Optional<PoolSite> plan(Cell cell,int x,int z) {
        if(unit(cell.x,cell.y,cell.z,3)>.68)return Optional.empty();
        for(long gy=cell.y*Y_CELL+Y_CELL-1;gy>=cell.y*Y_CELL;gy--) {
            if(!valid(gy)||!valid(gy-2)||!valid(gy+1)||!band.contains(x,(int)gy,z))continue;
            if(!fertile(material(x,gy,z))||material(x,gy+1,z)!=TerrainMaterial.AIR)continue;
            if(EcologyMaskFields.moisture(seed,x,(int)gy,z)<.35)return Optional.empty();
            var s=new PoolSite(x,(int)gy,z,3+(int)(unit(cell.x,cell.y,cell.z,4)*3),unit(cell.x,cell.y,cell.z,5)<.5,2);
            for(int dx=-EXTENT;dx<=EXTENT;dx++)for(int dz=-EXTENT;dz<=EXTENT;dz++) {
                int depth=wetDepth(s,dx,dz);if(depth==0)continue;long bx=(long)x+dx,bz=(long)z+dz;
                if(!valid(bx)||!valid(bz)||!fertile(material(bx,gy,bz))||material(bx,gy+1,bz)!=TerrainMaterial.AIR)return Optional.empty();
                for(int dy=0;dy<depth;dy++) {
                    if(!band.contains((int)bx,(int)gy-dy,(int)bz)||protection.contains((int)bx,(int)gy-dy,(int)bz)||!solid(material(bx,gy-dy,bz)))return Optional.empty();
                    for(int[] d:DIRECTIONS)if(wetDepth(s,dx+d[0],dz+d[1])<=dy&&!solid(material(bx+d[0],gy-dy,bz+d[1])))return Optional.empty();
                }
                if(!solid(material(bx,gy-depth,bz)))return Optional.empty();
            }
            return Optional.of(s);
        }
        return Optional.empty();
    }
    private int wetDepth(PoolSite s,int dx,int dz) {
        if(Math.abs(dx)>EXTENT||Math.abs(dz)>EXTENT)return 0;
        // Three unequal lobes and a crooked narrow outlet retained at the same water level.
        int r=s.radius;boolean pool=dx*dx+dz*dz<r*r || (dx-2)*(dx-2)+(dz+1)*(dz+1)<(r-1)*(r-1)
            ||(dx+2)*(dx+2)+(dz-2)*(dz-2)<(r-1)*(r-1);
        if(pool)return dx*dx+dz*dz<=4?2:1;
        int along=s.rillAlongX?dx:dz,across=s.rillAlongX?dz:dx;
        int meander=along<r+2?0:along<r+5?1:-1;
        boolean bend=along==r+2&&(across==0||across==1)||along==r+5&&Math.abs(across)<=1;
        return along>=r-1&&along<=r+6&&(bend||across==meander)?1:0;
    }
    private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1}};
    private TerrainMaterial material(long x,long y,long z){if(!valid(x)||!valid(y)||!valid(z))return null;var s=source.sample((int)x,(int)y,(int)z);return s instanceof TerrainSample.Known k?k.material():null;}
    private static boolean valid(long n){return n>=Integer.MIN_VALUE&&n<=Integer.MAX_VALUE;}
    private static boolean fertile(TerrainMaterial m){return m==TerrainMaterial.GRASS||m==TerrainMaterial.SOIL;}
    private static boolean solid(TerrainMaterial m){return m==TerrainMaterial.GRASS||m==TerrainMaterial.SOIL||m==TerrainMaterial.ROCK;}
    private double unit(long x,long y,long z,long salt){return (mix(seed^mix(x)^Long.rotateLeft(mix(y),21)^Long.rotateLeft(mix(z),42)^mix(salt))>>>11)*0x1.0p-53;}
    private static long mix(long n){n=(n^(n>>>30))*0xbf58476d1ce4e5b9L;n=(n^(n>>>27))*0x94d049bb133111ebL;return n^(n>>>31);}
}
