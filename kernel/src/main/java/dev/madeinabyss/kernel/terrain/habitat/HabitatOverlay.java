package dev.madeinabyss.kernel.terrain.habitat;

import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import java.util.*;

/** ORIGINAL preview plan; visual approval and runtime ecology remain separate. */
public final class HabitatOverlay {
    public static final int MAX_PATCHES=32,MAX_WRITES=262144;
    public static final long MAX_SAMPLES=12000000;
    public record Patch(String id,int minX,int minZ,int width,int depth,int referenceY,long seed) { }
    public record Placement(int x,int y,int z,HabitatBlock block,String featureId) { }
    public record Rejection(String patchId,String featureId,String reason) { }
    public record Summary(int trees,int flowers,int waterCells,int structureCells,int placementCount,long sampledVoxels,List<Rejection> rejections) { }
    @FunctionalInterface public interface ProtectedCells { boolean contains(int x,int y,int z); }
    private record Pos(int x,int y,int z) { }
    private final Map<Pos,HabitatBlock> writes;
    private final List<Placement> placements;
    private final Summary summary;
    private final Set<Long> occupiedColumns;
    private final int minX,maxX,minY,maxY,minZ,maxZ;
    private HabitatOverlay(Map<Pos,Placement> staged,int trees,int flowers,long sampled,List<Rejection> rejected) {
        var values=new HashMap<Pos,HabitatBlock>();staged.forEach((p,v)->values.put(p,v.block()));writes=Map.copyOf(values);
        var columns=new HashSet<Long>();int lx=Integer.MAX_VALUE,ly=Integer.MAX_VALUE,lz=Integer.MAX_VALUE,hx=Integer.MIN_VALUE,hy=Integer.MIN_VALUE,hz=Integer.MIN_VALUE;
        for(var pos:staged.keySet()){
            lx=Math.min(lx,pos.x());hx=Math.max(hx,pos.x());ly=Math.min(ly,pos.y());hy=Math.max(hy,pos.y());lz=Math.min(lz,pos.z());hz=Math.max(hz,pos.z());
            columns.add(((long)pos.x()<<32)|(pos.z()&0xffffffffL));
        }
        // Packed XZ Long hashes collide along dense diagonals. Set.copyOf's open-addressed
        // SetN makes broad DH misses scan thousands of slots; HashSet treeifies such buckets.
        // This locally owned set never escapes except through this read-only wrapper.
        occupiedColumns=Collections.unmodifiableSet(columns);minX=lx;maxX=hx;minY=ly;maxY=hy;minZ=lz;maxZ=hz;
        var ordered=new ArrayList<>(staged.values());ordered.sort(Comparator.comparingInt(Placement::x).thenComparingInt(Placement::y).thenComparingInt(Placement::z));
        placements=List.copyOf(ordered);
        int water=0,structure=0;for(var p:placements){if(p.block()==HabitatBlock.WATER)water++;if(p.featureId().contains("/ruin"))structure++;}
        summary=new Summary(trees,flowers,water,structure,placements.size(),sampled,List.copyOf(rejected));
    }
    public static HabitatOverlay plan(TrailNetwork.SampleSource base,List<Patch> patches) { return plan(base,patches,(x,y,z)->false); }
    public static HabitatOverlay plan(TrailNetwork.SampleSource base,List<Patch> patches,ProtectedCells protectedCells) {
        Objects.requireNonNull(base);Objects.requireNonNull(protectedCells);patches=List.copyOf(patches);
        if(patches.size()>MAX_PATCHES)throw new IllegalArgumentException("Habitat patch budget exceeded");
        var ids=new HashSet<String>();for(var p:patches){
            if(p.id()==null || p.id().isBlank() || !ids.add(p.id()) || p.width()<16 || p.width()>80 || p.depth()<16 || p.depth()>80)
                throw new IllegalArgumentException("Unique named habitat patches of16..80metres required");
            checked((long)p.minX()+p.width()-1);checked((long)p.minZ()+p.depth()-1);
            checked((long)p.referenceY()-28);checked((long)p.referenceY()+36);
        }
        var ordered=new ArrayList<>(patches);ordered.sort(Comparator.comparing(Patch::id));
        var builder=new Builder(base,protectedCells);for(var p:ordered)builder.patch(p);
        return new HabitatOverlay(builder.writes,builder.trees,builder.flowers,builder.samples,builder.rejected);
    }
    public Optional<HabitatBlock> sample(int x,int y,int z) {
        if(x<minX || x>maxX || y<minY || y>maxY || z<minZ || z>maxZ
                || !occupiedColumns.contains(((long)x<<32)|(z&0xffffffffL)))return Optional.empty();
        return Optional.ofNullable(writes.get(new Pos(x,y,z)));
    }
    public List<Placement> placements() { return placements; }
    public Summary summary() { return summary; }

    private static int checked(long n){if(n<Integer.MIN_VALUE || n>Integer.MAX_VALUE)throw new IllegalArgumentException("Habitat signedXYZoverflow");return (int)n;}
    private static boolean solid(TerrainMaterial m){return m==TerrainMaterial.ROCK || m==TerrainMaterial.SOIL || m==TerrainMaterial.GRASS;}
    private static boolean solid(HabitatBlock m){return m==HabitatBlock.ROCK || m==HabitatBlock.GRAVEL || m==HabitatBlock.COBBLE || m==HabitatBlock.SOIL || m==HabitatBlock.GRASS;}
    private static long mix(long n){n=(n^(n>>>30))*0xbf58476d1ce4e5b9L;n=(n^(n>>>27))*0x94d049bb133111ebL;return n^(n>>>31);}

    private static final class Builder {
        final TrailNetwork.SampleSource base;final ProtectedCells protectedCells;
        final Map<Pos,Placement> writes=new HashMap<>();final List<Rejection> rejected=new ArrayList<>();
        long samples;int trees,flowers;
        Builder(TrailNetwork.SampleSource base,ProtectedCells protectedCells){this.base=base;this.protectedCells=protectedCells;}
        TerrainMaterial material(int x,int y,int z){
            if(++samples>MAX_SAMPLES)throw new IllegalArgumentException("Habitat sample budget exceeded");
            var composed=writes.get(new Pos(x,y,z));
            if(composed!=null) return switch(composed.block()) {
                case AIR->TerrainMaterial.AIR;case SOIL->TerrainMaterial.SOIL;case GRASS->TerrainMaterial.GRASS;
                case WATER->TerrainMaterial.WATER;default->TerrainMaterial.ROCK;
            };
            var s=base.sample(x,y,z);return s instanceof TerrainSample.Known k?k.material():null;
        }
        void reject(Patch p,String feature,String reason){rejected.add(new Rejection(p.id(),feature,reason));}
        void patch(Patch p){
            int[] floors=new int[p.width()*p.depth()];Arrays.fill(floors,Integer.MIN_VALUE);int valid=0;
            for(int z=0;z<p.depth();z++)for(int x=0;x<p.width();x++){
                int bx=p.minX()+x,bz=p.minZ()+z;
                for(int y=p.referenceY()+24;y>=p.referenceY()-24;y--){
                    var m=material(bx,y,bz);if(m==null)break;if(m==TerrainMaterial.AIR)continue;
                    if(m!=TerrainMaterial.GRASS && m!=TerrainMaterial.SOIL)break;
                    boolean supported=true;for(int d=1;d<=3;d++){var below=material(bx,y-d,bz);if(below==null || !solid(below)){supported=false;break;}}
                    if(supported && material(bx,y+1,bz)==TerrainMaterial.AIR && material(bx,y+2,bz)==TerrainMaterial.AIR){floors[x+p.width()*z]=y;valid++;}
                    break;
                }
            }
            if(valid==0){reject(p,p.id()+"/patch","No known fertile supported columns in referenceY±24");return;}
            // Reserve supported water and buildings before any added ground or vegetation.
            pond(p,floors);ruin(p,floors);
            microrelief(p,floors);
            // Seeded rejection sampling produces finite irregular groves, preserving the middle clearing.
            for(int side=0;side<2;side++)for(int attempt=0;attempt<100;attempt++){
                long n=mix(p.seed()+side*0x632be59bd9b4e019L+attempt*131L);
                int band=Math.min(19,Math.max(1,p.width()/3-5));
                int x=5+(int)Math.floorMod(n,band);if(side==1)x=p.width()-1-x;
                int z=5+(int)Math.floorMod(n>>>16,Math.max(1,p.depth()-10));
                int bx=p.minX()+x,bz=p.minZ()+z;
                boolean close=writes.values().stream().anyMatch(v->v.block()==HabitatBlock.LOG && v.featureId().contains("/tree-")
                        && Math.abs((long)v.x()-bx)<=4 && Math.abs((long)v.z()-bz)<=4);
                if(!close)tree(p,floors,x,z,p.id()+"/tree-"+side+"-"+attempt,5+(int)Math.floorMod(n>>>32,5));
            }
            marginFeatures(p,floors);
            for(int z=6;z<p.depth()-6;z+=4)for(int x=6;x<p.width()-6;x+=4){
                if((mix(p.seed()+x*131L+z*71L)&3)!=0)continue;
                int y=floor(p,floors,x,z);if(y==Integer.MIN_VALUE)continue;
                String id=p.id()+"/flower-"+x+"-"+z;
                var support=writes.get(new Pos(p.minX()+x,y,p.minZ()+z));
                var supportBlock=support==null?null:support.block();
                if(supportBlock!=null && supportBlock!=HabitatBlock.GRASS && supportBlock!=HabitatBlock.SOIL){
                    reject(p,id,"Flower lost fertile support in composed habitat");continue;
                }
                var candidate=new LinkedHashMap<Pos,HabitatBlock>();candidate.put(new Pos(p.minX()+x,y+1,p.minZ()+z),HabitatBlock.FLOWER);
                if(commit(p,id,candidate,true))flowers++;
            }
            groundCover(p,floors);
        }
        int floor(Patch p,int[] floors,int x,int z){return x<0 || z<0 || x>=p.width() || z>=p.depth()?Integer.MIN_VALUE:floors[x+p.width()*z];}
        void microrelief(Patch p,int[] floors) {
            for(int attempt=0;attempt<10;attempt++) {
                long n=mix(p.seed()+0x1234abcdL+attempt*73L);
                int x=9+(int)Math.floorMod(n,Math.max(1,p.width()/4-9));
                if((attempt&1)!=0)x=p.width()-1-x;
                int z=8+(int)Math.floorMod(n>>>16,Math.max(1,p.depth()-16));
                int radius=4+(int)Math.floorMod(n>>>32,3);
                var candidate=new LinkedHashMap<Pos,HabitatBlock>();var raised=new HashMap<Integer,Integer>();
                boolean valid=true;
                for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++) {
                    int distance=dx*dx+dz*dz;if(distance>radius*radius)continue;
                    int xx=x+dx,zz=z+dz,f=floor(p,floors,xx,zz);
                    if(f==Integer.MIN_VALUE || xx<3 || xx>=p.width()-3 || zz<3 || zz>=p.depth()-3
                            || xx>=26 && xx<p.width()-26) {valid=false;continue;}
                    int rise=distance<radius*radius/4?2:1;
                    int bx=p.minX()+xx,bz=p.minZ()+zz;
                    for(int y=f;y<=f+4;y++)if(protectedCells.contains(bx,y,bz) || writes.containsKey(new Pos(bx,y,bz)))valid=false;
                    // Fertile surface is backed by the original three-metre ground support.
                    for(int y=1;y<=rise;y++)candidate.put(new Pos(bx,f+y,bz),y==rise?HabitatBlock.GRASS:HabitatBlock.SOIL);
                    raised.put(xx+p.width()*zz,f+rise);
                }
                if(valid && !candidate.isEmpty() && commit(p,p.id()+"/microrelief-"+attempt,candidate,true))
                    raised.forEach((index,height)->floors[index]=height);
            }
        }
        boolean freeMarginColumns(Patch p,int[] floors,int x,int z,int radius) {
            for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++) {
                int xx=x+dx,zz=z+dz,f=floor(p,floors,xx,zz);
                if(f==Integer.MIN_VALUE)return false;
                var m=material(p.minX()+xx,f,p.minZ()+zz);
                if(m!=TerrainMaterial.GRASS && m!=TerrainMaterial.SOIL)return false;
                for(int y=f;y<=f+4;y++) {
                    var placed=writes.get(new Pos(p.minX()+xx,y,p.minZ()+zz));
                    if(protectedCells.contains(p.minX()+xx,y,p.minZ()+zz)
                            || placed!=null && !placed.featureId().contains("/microrelief-"))return false;
                }
                // Canopy, excavated water and ruins reserve their whole columns.
                for(var placed:writes.values())if(placed.x()==p.minX()+xx && placed.z()==p.minZ()+zz
                        && !placed.featureId().contains("/microrelief-"))return false;
            }
            return true;
        }
        void marginFeatures(Patch p,int[] floors) {
            for(int attempt=0;attempt<64;attempt++) {
                long n=mix(p.seed()+0x43fab19L+attempt*97L);
                int x=4+(int)Math.floorMod(n,Math.min(20,Math.max(1,p.width()/3-4)));
                if((attempt&1)!=0)x=p.width()-1-x;
                int z=4+(int)Math.floorMod(n>>>16,Math.max(1,p.depth()-8));
                boolean shrub=attempt%3!=0;
                String id=p.id()+(shrub?"/shrub-":"/rock-group-")+attempt;
                int f=floor(p,floors,x,z);if(f==Integer.MIN_VALUE || !freeMarginColumns(p,floors,x,z,1)
                        || !terrain(p,floors,x,z,1,f,0,3)) {
                    reject(p,id,"Margin feature lacks free supported fertile footprint or crosses protected/prior feature columns");continue;
                }
                int bx=p.minX()+x,bz=p.minZ()+z;var candidate=new LinkedHashMap<Pos,HabitatBlock>();
                candidate.put(new Pos(bx,f+1,bz),shrub?HabitatBlock.LOG:HabitatBlock.ROCK);
                candidate.put(new Pos(bx+1,f+1,bz),shrub?HabitatBlock.LOG:HabitatBlock.ROCK);
                candidate.put(new Pos(bx,f+1,bz+1),shrub?HabitatBlock.LEAVES:HabitatBlock.ROCK);
                candidate.put(new Pos(bx-1,f+1,bz),shrub?HabitatBlock.LEAVES:HabitatBlock.ROCK);
                candidate.put(new Pos(bx,f+1,bz-1),shrub?HabitatBlock.LEAVES:HabitatBlock.ROCK);
                candidate.put(new Pos(bx,f+2,bz),shrub?HabitatBlock.LEAVES:HabitatBlock.ROCK);
                if(shrub)candidate.put(new Pos(bx+1,f+2,bz),HabitatBlock.LEAVES);
                commit(p,id,candidate,true);
            }
        }
        void groundCover(Patch p,int[] floors) {
            for(int z=2;z<p.depth()-2;z++)for(int x=2;x<p.width()-2;x++) {
                int f=floor(p,floors,x,z);if(f==Integer.MIN_VALUE)continue;
                int edge=Math.min(x,p.width()-1-x);
                int chance=edge<25?58:edge<31?32:12;
                if(Math.floorMod(mix(p.seed()+x*13417L+z*7919L),100)>=chance)continue;
                int bx=p.minX()+x,bz=p.minZ()+z;
                var support=material(bx,f,bz);
                if(support!=TerrainMaterial.GRASS && support!=TerrainMaterial.SOIL)continue;
                if(writes.containsKey(new Pos(bx,f+1,bz)) || protectedCells.contains(bx,f+1,bz))continue;
                commit(p,p.id()+"/grass-"+x+"-"+z,Map.of(new Pos(bx,f+1,bz),HabitatBlock.TALL_GRASS),true);
            }
        }
        boolean terrain(Patch p,int[] floors,int x,int z,int radius,int ground,int tolerance,int headroom){
            for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
                int f=floor(p,floors,x+dx,z+dz);if(f==Integer.MIN_VALUE || Math.abs((long)f-ground)>tolerance)return false;
                var support=material(p.minX()+x+dx,f,p.minZ()+z+dz);
                if(support!=TerrainMaterial.GRASS && support!=TerrainMaterial.SOIL)return false;
                for(int d=1;d<=headroom;d++)if(material(p.minX()+x+dx,f+d,p.minZ()+z+dz)!=TerrainMaterial.AIR)return false;
            }
            return true;
        }
        void tree(Patch p,int[] floors,int x,int z,String id,int height){
            int ground=floor(p,floors,x,z);
            if(ground==Integer.MIN_VALUE || !terrain(p,floors,x,z,2,ground,2,height+1)){reject(p,id,"Tree requires fertile rooted footprint, gentle slope and free canopy headroom");return;}
            int bx=p.minX()+x,bz=p.minZ()+z;var candidate=new LinkedHashMap<Pos,HabitatBlock>();
            for(int y=1;y<=height;y++)candidate.put(new Pos(bx,ground+y,bz),HabitatBlock.LOG);
            for(int d=1;d<=2;d++)for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                candidate.put(new Pos(bx+dir[0]*d,ground+height-2,bz+dir[1]*d),HabitatBlock.LOG);
            for(int dy=height-2;dy<=height+1;dy++)for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++){
                int radius=dy==height+1?1:2;if(dx*dx+dz*dz>radius*radius+1)continue;
                candidate.putIfAbsent(new Pos(bx+dx,ground+dy,bz+dz),HabitatBlock.LEAVES);
            }
            if(commit(p,id,candidate,true))trees++;
        }
        void pond(Patch p,int[] floors){
            String id=p.id()+"/pond-stream";
            int cx=p.width()/2+Math.min(17,p.width()/5),cz=p.depth()/2+Math.min(10,p.depth()/8);
            int pondLevel=Integer.MAX_VALUE;
            // The basin fits existing fertile terrain: its water is one metre below the lowest
            // accepted footprint surface, rather than a raised flume on artificial pillars.
            for(int dz=-3;dz<=3;dz++)for(int dx=-4;dx<=4;dx++)if(dx*dx*9+dz*dz*16<=144){
                int f=floor(p,floors,cx+dx,cz+dz);
                if(f==Integer.MIN_VALUE){reject(p,id,"Pond footprint lacks known supported fertile ground");return;}
                pondLevel=Math.min(pondLevel,f-1);
            }
            var water=new LinkedHashMap<Pos,HabitatBlock>();
            for(int dz=-3;dz<=3;dz++)for(int dx=-4;dx<=4;dx++)
                if(dx*dx*9+dz*dz*16<=144)water.put(new Pos(p.minX()+cx+dx,pondLevel,p.minZ()+cz+dz),HabitatBlock.WATER);
            int previous=Integer.MAX_VALUE;
            for(int d=10;d>=4;d--){
                int f=floor(p,floors,cx,cz-d);
                if(f==Integer.MIN_VALUE){reject(p,id,"Stream lacks known supported ground");return;}
                int level=Math.min(f-1,pondLevel+d-3);
                if(previous!=Integer.MAX_VALUE)level=Math.max(previous-1,Math.min(previous,level));
                if(f-level<1 || f-level>3 || level<pondLevel){reject(p,id,"Stream gradient cannot reach basin within shallow excavation budget");return;}
                water.put(new Pos(p.minX()+cx,level,p.minZ()+cz-d),HabitatBlock.WATER);previous=level;
            }
            if(previous-pondLevel>1){reject(p,id,"Stream terminal drop exceeds one metre");return;}
            var candidate=new LinkedHashMap<Pos,HabitatBlock>(water);
            for(var v:water.keySet()){
                int f=floor(p,floors,v.x()-p.minX(),v.z()-p.minZ());
                if(f==Integer.MIN_VALUE || (long)f-v.y()<1 || (long)f-v.y()>3 || v.y()<Integer.MIN_VALUE+2){reject(p,id,"Basin/channel excavation exceeds three metres");return;}
                var bed=material(v.x(),v.y()-1,v.z());var beneath=material(v.x(),v.y()-2,v.z());
                if(bed==null || beneath==null || !solid(bed) || !solid(beneath)){reject(p,id,"Recessed water bed would open into unknown or unsupported cavity");return;}
                candidate.put(new Pos(v.x(),v.y()-1,v.z()),HabitatBlock.GRAVEL);
                for(int y=v.y()+1;y<=f;y++)candidate.put(new Pos(v.x(),y,v.z()),HabitatBlock.AIR);
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
                    int xx=v.x()+d[0],zz=v.z()+d[1];
                    var adjacent=water.keySet().stream().filter(w->w.x()==xx && w.z()==zz).findFirst();
                    if(adjacent.isPresent()){
                        if(Math.abs((long)adjacent.get().y()-v.y())>1){reject(p,id,"Adjacent water columns must form gentle one-metre descents");return;}
                        continue;
                    }
                    int bankFloor=floor(p,floors,xx-p.minX(),zz-p.minZ());
                    var bank=material(xx,v.y(),zz);
                    if(bankFloor==Integer.MIN_VALUE || bank==null || !solid(bank)){reject(p,id,"Water requires existing natural retaining banks");return;}
                }
            }
            // No bank writes: existing grass/soil/rock contains the shallow recessed water.
            for(var e:candidate.entrySet()){
                var v=e.getKey();var original=material(v.x(),v.y(),v.z());
                var rootedLog=v.y()==Integer.MAX_VALUE?null:writes.get(new Pos(v.x(),v.y()+1,v.z()));
                if(original==null || original==TerrainMaterial.WATER || rootedLog!=null && rootedLog.block()==HabitatBlock.LOG){reject(p,id,"Water excavation encountered unknown/existing water or another tree root");return;}
            }
            commit(p,id,candidate,false);
        }
        void ruin(Patch p,int[] floors){
            String id=p.id()+"/ruin";int cx=p.width()/2-10,cz=p.depth()/2-14,min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
            for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++){
                int f=floor(p,floors,cx+dx,cz+dz);if(f==Integer.MIN_VALUE){reject(p,id,"Ruin footprint lacks existing supported ground");return;}min=Math.min(min,f);max=Math.max(max,f);
            }
            if((long)max-min>2 || !terrain(p,floors,cx,cz,2,max,2,4)){reject(p,id,"Ruin needs ≤3metre footings and free headroom across full5×5 footprint");return;}
            var candidate=new LinkedHashMap<Pos,HabitatBlock>();
            for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++){
                int bx=p.minX()+cx+dx,bz=p.minZ()+cz+dz,f=floor(p,floors,cx+dx,cz+dz);
                for(int y=f+1;y<=max+1;y++)candidate.put(new Pos(bx,y,bz),HabitatBlock.COBBLE);
                if((Math.abs(dx)==2 || Math.abs(dz)==2) && !(dz==-2 && dx==0)){
                    candidate.put(new Pos(bx,max+2,bz),HabitatBlock.COBBLE);
                    if((mix(p.seed()+dx*17L+dz*31L)&1)==0)candidate.put(new Pos(bx,max+3,bz),HabitatBlock.COBBLE);
                }
            }
            commit(p,id,candidate,true);
        }
        boolean commit(Patch p,String id,Map<Pos,HabitatBlock> candidate,boolean airOnly){
            for(var e:candidate.entrySet()){
                var v=e.getKey();
                if(v.x()<p.minX() || (long)v.x()>= (long)p.minX()+p.width() || v.z()<p.minZ() || (long)v.z()>=(long)p.minZ()+p.depth()
                        || protectedCells.contains(v.x(),v.y(),v.z()) || writes.containsKey(v)){
                    reject(p,id,"Feature crosses patch/protected corridor/previous feature; entire feature rejected");return false;
                }
                var m=material(v.x(),v.y(),v.z());
                if(m==null || airOnly && m!=TerrainMaterial.AIR || !airOnly && m!=TerrainMaterial.AIR && !solid(m)){
                    reject(p,id,"Feature would replace UNKNOWN or incompatible base geometry");return false;
                }
            }
            if((long)writes.size()+candidate.size()>MAX_WRITES)throw new IllegalArgumentException("Habitat write budget exceeded");
            candidate.forEach((v,b)->writes.put(v,new Placement(v.x(),v.y(),v.z(),b,id)));return true;
        }
    }
}
