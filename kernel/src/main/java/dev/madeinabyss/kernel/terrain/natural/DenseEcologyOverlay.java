package dev.madeinabyss.kernel.terrain.natural;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import java.util.*;

/** Lazy ORIGINAL ecology over real global XYZ terrain, including overlapping shelves.
 * No heightmap, mutable RNG or world-sized placement map. Caller supplies a conservative cheap
 * surface envelope; UNKNOWN and water are never replaced. Saved cubes remain authoritative.
 * v10 mixed groves have jittered12m cells, >=9m centre separation, radius3 and <=11m crowns.
 * The only retained data are at most 1024 immutable accepted/rejected local tree plans. */
public final class DenseEcologyOverlay {
    public static final int MAX_CACHED_PLANS=1024;
    private static final int CELL=12, RADIUS=3, MAX_HEIGHT=12;
    @FunctionalInterface public interface Source { TerrainSample sample(int x,int y,int z); }
    @FunctionalInterface public interface Protection { boolean contains(int x,int y,int z); }
    @FunctionalInterface public interface SurfaceBand { boolean contains(int x,int y,int z); }
    public record Masks(double moisture,double exposure,double density,boolean clearing) {}
    public enum Community { MOIST_GROVE, EXPOSED_EDGE, SHADE_UNDERSTORY, RIPARIAN }
    public Community communityAt(int x,int y,int z){if(nearWater(x,y,z))return Community.RIPARIAN;var m=masks(x,y,z);return m.moisture()>.65?Community.MOIST_GROVE:m.exposure()>.65?Community.EXPOSED_EDGE:Community.SHADE_UNDERSTORY;}
    private record Cell(long x,long y,long z) {}
    private record Tree(int x,int ground,int z,int height,int species) {}
    private static final Optional<Tree> NO_TREE=Optional.empty();
    private final long seed;
    private final Source source;
    private final Protection protection;
    private final SurfaceBand surfaceBand;
    private final Map<Cell,Optional<Tree>> plans=new LinkedHashMap<>(128,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Cell,Optional<Tree>> entry) {return size()>MAX_CACHED_PLANS;}
    };
    public DenseEcologyOverlay(long seed,Source source,Protection protection,SurfaceBand surfaceBand) {
        this.seed=seed;this.source=Objects.requireNonNull(source);this.protection=Objects.requireNonNull(protection);this.surfaceBand=Objects.requireNonNull(surfaceBand);
    }
    public Optional<NaturalBlock> sampleOverride(int x,int y,int z) {
        if(!eligible(x,y,z))return Optional.empty();
        return sampleEligible(x,y,z,source.sample(x,y,z));
    }
    /** Integration overload avoids duplicating the caller's composed geological sample. */
    public Optional<NaturalBlock> sampleOverride(int x,int y,int z,TerrainSample base) {
        if(!eligible(x,y,z))return Optional.empty();
        return sampleEligible(x,y,z,Objects.requireNonNull(base));
    }
    private boolean eligible(int x,int y,int z) {return surfaceBand.contains(x,y,z)&&!protection.contains(x,y,z);}
    private Optional<NaturalBlock> sampleEligible(int x,int y,int z,TerrainSample sample) {
        if(!(sample instanceof TerrainSample.Known k)||k.material()==TerrainMaterial.WATER)return Optional.empty();
        if(k.material()!=TerrainMaterial.AIR) {
            // Soil/grass have no ecological replacement; rock moss uses humidity only.
            // Preserve the identical noise field while avoiding two unused lattice evaluations.
            if(k.material()==TerrainMaterial.ROCK&&mossAt(x,y,z,noise(x,y,z,96,41)))return Optional.of(NaturalBlock.MOSS);
            return Optional.empty();
        }
        NaturalBlock tree=treeBlock(x,y,z);if(tree!=null)return Optional.of(tree);
        var masks=masks(x,y,z);
        if(!masks.clearing()&&fertile(material(x,(long)y-1,z))&&solid(material(x,(long)y-2,z))
                &&material(x,(long)y+1,z)==TerrainMaterial.AIR) {
            double slope=slope(x,y-1,z);
            boolean riparian=nearWater(x,y,z);
            if(slope<.7&&random(x,y,z,61)<Math.min(.94,masks.density()*.85+(riparian?.18:0))*(1-slope)) {
                double choice=random(x,y,z,62);
                return Optional.of(choice<.12?NaturalBlock.FLOWER_WHITE:choice<.24?NaturalBlock.FLOWER_YELLOW:
                        (riparian||masks.moisture()>.45)&&choice<.78?NaturalBlock.FERN:NaturalBlock.GRASS_PLANT);
            }
        }
        // Coherent wall patches and vertical runs, attached directly on every voxel; no floating chains.
        if(masks.moisture()>.38&&random(Math.floorDiv(x,4),Math.floorDiv(y,20),Math.floorDiv(z,4),72)<.32
                &&random(x,y,z,73)>.035) {
            if(solid(material(x,y,(long)z-1)))return Optional.of(NaturalBlock.VINE_NORTH);
            if(solid(material(x,y,(long)z+1)))return Optional.of(NaturalBlock.VINE_SOUTH);
            if(solid(material((long)x+1,y,z)))return Optional.of(NaturalBlock.VINE_EAST);
            if(solid(material((long)x-1,y,z)))return Optional.of(NaturalBlock.VINE_WEST);
        }
        return Optional.empty();
    }
    private boolean mossAt(int x,int y,int z,double moisture) {
        return moisture>.42&&random(Math.floorDiv(x,8),Math.floorDiv(y,12),Math.floorDiv(z,8),53)<.30&&exposed(x,y,z);
    }
    /** Coherent humidity/exposure fields, with explicit sparse clearings. Soil slope is sampled separately. */
    public Masks masks(int x,int y,int z) {
        double moisture=EcologyMaskFields.moisture(seed,x,y,z);
        double exposure=noise(x,y,z,144,42);
        boolean clearing=noise(x,y,z,48,43)<.18;
        return new Masks(moisture,exposure,Math.min(.92,Math.max(.25,.15+moisture*(1-.30*exposure)*1.35)),clearing);
    }
    public int cachedPlanCount() {synchronized(plans){return plans.size();}}
    private NaturalBlock treeBlock(int x,int y,int z) {
        long cellX=Math.floorDiv(x,CELL),cellZ=Math.floorDiv(z,CELL);
        long loY=Math.floorDiv((long)y-MAX_HEIGHT,CELL),hiY=Math.floorDiv((long)y-1,CELL);
        // Centres 4..7 plus radius3 fit strictly inside their12m cell: neighbouring
        // cells cannot affect this query. Avoid nine candidate hashes per distant-LOD air voxel.
        long cx=cellX,cz=cellZ;
        {
            // Jitter stays within the central4m; neighboring cells cannot put overlapping trunks together.
            long tx=cx*CELL+4+(long)(random(cx,0,cz,21)*4),tz=cz*CELL+4+(long)(random(cx,0,cz,22)*4);
            if(Math.abs(tx-x)>RADIUS||Math.abs(tz-z)>RADIUS||!valid(tx)||!valid(tz))return null;
            for(long cy=loY;cy<=hiY;cy++) {
                if(random(cx,cy,cz,23)>.94)continue;
                var cell=new Cell(cx,cy,cz);Optional<Tree> plan;
                synchronized(plans){plan=plans.get(cell);}
                if(plan==null){plan=planTree(cell,(int)tx,(int)tz);synchronized(plans){plans.put(cell,plan);}}
                if(plan.isEmpty())continue;
                var t=plan.get();long dy=(long)y-t.ground();int dx=x-t.x(),dz=z-t.z();
                if(dx==0&&dz==0&&dy>=1&&dy<=t.height())return logs(t.species());
                if(dy>=t.height()-2&&dy<=t.height()+2) {
                    int r=dy==t.height()+2?1:dy==t.height()+1?2:RADIUS;
                    if(dx*dx+dz*dz<=r*r+1&&(dx!=0||dz!=0||dy>t.height()))return leaves(t.species());
                }
            }
        }
        return null;
    }
    private Optional<Tree> planTree(Cell c,int x,int z) {
        long top=c.y()*CELL+CELL-1,bottom=c.y()*CELL;
        for(long ground=top;ground>=bottom;ground--) {
            if(!valid(ground)||!valid(ground+MAX_HEIGHT)||!surfaceBand.contains(x,(int)ground+1,z))continue;
            if(!fertile(material(x,ground,z))||material(x,ground+1,z)!=TerrainMaterial.AIR)continue;
            var masks=masks(x,(int)ground,z);
            if(masks.clearing()||masks.density()<.25||random(c.x(),c.y(),c.z(),24)>masks.density())return NO_TREE;
            if(!solid(material(x,ground-1,z))||slope(x,(int)ground,z)>.25)return NO_TREE;
            int height=6+(int)(random(c.x(),c.y(),c.z(),25)*4);
            int species=masks.exposure()>.65?2:random(c.x(),c.y(),c.z(),26)<.3?1:0;
            // The entire crown/trunk must fit: rejecting a blocked voxel rejects the complete feature.
            for(int dx=-RADIUS;dx<=RADIUS;dx++)for(int dz=-RADIUS;dz<=RADIUS;dz++) {
                long bx=(long)x+dx,bz=(long)z+dz;if(!valid(bx)||!valid(bz))return NO_TREE;
                if(Math.abs(dx)<=1&&Math.abs(dz)<=1&&!solid(material(bx,ground-1,bz)))return NO_TREE;
                for(int dy=1;dy<=height+2;dy++) {
                    boolean trunk=dx==0&&dz==0&&dy<=height;
                    int r=dy==height+2?1:dy==height+1?2:RADIUS;
                    boolean crown=dy>=height-2&&dx*dx+dz*dz<=r*r+1;
                    if((trunk||crown)&&(!surfaceBand.contains((int)bx,(int)ground+dy,(int)bz)
                            ||protection.contains((int)bx,(int)ground+dy,(int)bz)||material(bx,ground+dy,bz)!=TerrainMaterial.AIR))return NO_TREE;
                }
            }
            if(protection.contains(x,(int)ground,z))return NO_TREE;
            return Optional.of(new Tree(x,(int)ground,z,height,species));
        }
        return NO_TREE;
    }
    private double slope(int x,int y,int z) {
        int steep=0;
        for(int[] d:DIRECTIONS)if(!solid(material((long)x+d[0],(long)y-1,(long)z+d[1]))||solid(material((long)x+d[0],(long)y+1,(long)z+d[1])))steep++;
        return steep/4.0;
    }
    private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1}};
    private boolean nearWater(int x,int y,int z){for(int[] d:DIRECTIONS)if(material((long)x+d[0]*3,(long)y-1,(long)z+d[1]*3)==TerrainMaterial.WATER)return true;return false;}
    private boolean exposed(int x,int y,int z) {
        if(material(x,(long)y+1,z)==TerrainMaterial.AIR)return true;
        for(int[] d:DIRECTIONS)if(material((long)x+d[0],y,(long)z+d[1])==TerrainMaterial.AIR)return true;
        return false;
    }
    private TerrainMaterial material(long x,long y,long z) {
        if(!valid(x)||!valid(y)||!valid(z))return null;
        var value=source.sample((int)x,(int)y,(int)z);return value instanceof TerrainSample.Known k?k.material():null;
    }
    private static boolean valid(long v){return v>=Integer.MIN_VALUE&&v<=Integer.MAX_VALUE;}
    private static boolean fertile(TerrainMaterial m){return m==TerrainMaterial.GRASS||m==TerrainMaterial.SOIL;}
    private static boolean solid(TerrainMaterial m){return m==TerrainMaterial.ROCK||m==TerrainMaterial.SOIL||m==TerrainMaterial.GRASS;}
    private static NaturalBlock logs(int species){return species==1?NaturalBlock.BIRCH_LOG:species==2?NaturalBlock.SPRUCE_LOG:NaturalBlock.OAK_LOG;}
    private static NaturalBlock leaves(int species){return species==1?NaturalBlock.BIRCH_LEAVES:species==2?NaturalBlock.SPRUCE_LEAVES:NaturalBlock.OAK_LEAVES;}
    private double noise(int x,int y,int z,int scale,long salt) {
        double fx=(double)x/scale,fy=(double)y/(scale*2),fz=(double)z/scale;
        long ix=(long)Math.floor(fx),iy=(long)Math.floor(fy),iz=(long)Math.floor(fz);
        fx=smooth(fx-ix);fy=smooth(fy-iy);fz=smooth(fz-iz);double value=0;
        for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++)
            value+=(dx==0?1-fx:fx)*(dy==0?1-fy:fy)*(dz==0?1-fz:fz)*random(ix+dx,iy+dy,iz+dz,salt);
        return value;
    }
    private static double smooth(double x){return x*x*(3-2*x);}
    private double random(long x,long y,long z,long salt){return (mix(seed^mix(x)^Long.rotateLeft(mix(y),21)^Long.rotateLeft(mix(z),42)^mix(salt))>>>11)*0x1.0p-53;}
    private static long mix(long n){n=(n^(n>>>30))*0xbf58476d1ce4e5b9L;n=(n^(n>>>27))*0x94d049bb133111ebL;return n^(n>>>31);}
}
