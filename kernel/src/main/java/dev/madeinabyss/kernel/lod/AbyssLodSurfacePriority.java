package dev.madeinabyss.kernel.lod;

import java.util.*;
import java.util.function.Predicate;

/** Surface witnesses are ordering hints; every returned grid still samples its exact source. */
public final class AbyssLodSurfacePriority {
    private AbyssLodSurfacePriority() { }
    public record Distributed(List<TerrainLodRefinement.Region> jobs,int bootstrapJobs,int angularSectors,int undispatchedJobs){
        public Distributed(List<TerrainLodRefinement.Region> jobs,int bootstrapJobs,int angularSectors){this(jobs,bootstrapJobs,angularSectors,0);}
        public Distributed{jobs=List.copyOf(jobs);if(bootstrapJobs<0||angularSectors<0||undispatchedJobs<0)throw new IllegalArgumentException("Negative distributed source work");}}
    /** Angular4m coverage is source-sampled, never inferred from the surface witness. */
    public static Distributed distributed(List<TerrainLodRefinement.Region> desired,double x,double y,double z,
            double forwardX,double forwardY,double forwardZ,int maxJobs){
        return distributed(desired,x,y,z,forwardX,forwardY,forwardZ,maxJobs,16);
    }
    /** A bounded cadence preserves both exact fine order and angular progress. */
    public static Distributed distributed(List<TerrainLodRefinement.Region> desired,double x,double y,double z,
            double forwardX,double forwardY,double forwardZ,int maxJobs,int bootstrapPerFiner){
        if(bootstrapPerFiner<1||bootstrapPerFiner>16)throw new IllegalArgumentException("Angular cadence must be1..16");
        if(maxJobs<1||desired.size()>maxJobs)throw new IllegalArgumentException("Bounded progressive queue required");
        double length=Math.hypot(Math.hypot(forwardX,forwardY),forwardZ);
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Double.isFinite(length)||length==0)throw new IllegalArgumentException("Finite distributed view required");
        forwardX/=length;forwardY/=length;forwardZ/=length;
        var sectors=new ArrayList<ArrayDeque<TerrainLodRefinement.Region>>();for(int i=0;i<24;i++)sectors.add(new ArrayDeque<>());
        var finer=new ArrayDeque<TerrainLodRefinement.Region>();var seen=new HashSet<TerrainLodColumns.Bounds>();
        for(var job:desired){if(!seen.add(job.bounds()))continue;var b=job.bounds();double dx=((long)b.minX()+b.maxXInclusive()+1)*.5-x,dy=((long)b.minY()+b.maxYInclusive()+1)*.5-y,dz=((long)b.minZ()+b.maxZInclusive()+1)*.5-z;
            int az=Math.floorMod((int)Math.floor(Math.atan2(dz,dx)/(Math.PI/4)),8);double horizontal=Math.hypot(dx,dz);
            int elevation=dy<-horizontal*.577?0:dy>horizontal*.577?2:1;
            sectors.get(elevation*8+az).add(new TerrainLodRefinement.Region(b,4));if(job.horizontalStep()<4)finer.add(job);}
        var angular=new ArrayList<Integer>();for(int i=0;i<24;i++)if(!sectors.get(i).isEmpty())angular.add(i);
        final double fx=forwardX,fy=forwardY,fz=forwardZ;
        angular.sort(Comparator.comparingDouble((Integer sector)->{double az=(sector%8+.5)*(Math.PI/4),el=(sector/8-1)*(Math.PI/4);
            return -(Math.cos(el)*Math.cos(az)*fx+Math.sin(el)*fy+Math.cos(el)*Math.sin(az)*fz);}).thenComparingInt(Integer::intValue));
        int total=seen.size()+finer.size(),bootstrap=0,cursor=0,remaining=seen.size();var out=new ArrayList<TerrainLodRefinement.Region>(Math.min(maxJobs,total));
        // Original nearest front retains immediate priority. Default sixteen4m source lattices
        // cost about one1m lattice, distributing coverage without exhausting the source reserve.
        if(!finer.isEmpty()&&out.size()<maxJobs)out.add(finer.removeFirst());
        while(out.size()<maxJobs&&(remaining>0||!finer.isEmpty())){
            int emitted=0;
            while(emitted<bootstrapPerFiner&&remaining>0&&out.size()<maxJobs){int scanned=0;ArrayDeque<TerrainLodRefinement.Region> queue=null;
                while(scanned++<angular.size()){queue=sectors.get(angular.get(cursor));cursor=(cursor+1)%angular.size();if(!queue.isEmpty())break;}
                if(queue==null||queue.isEmpty())break;out.add(queue.removeFirst());remaining--;emitted++;bootstrap++;}
            if(!finer.isEmpty()&&out.size()<maxJobs)out.add(finer.removeFirst());
        }
        return new Distributed(out,bootstrap,angular.size(),total-out.size());
    }

    /** Geometry-only tiers. Uncached sampling is admitted separately against the live source reserve. */
    public record SurfaceDescriptor(List<TerrainLodRefinement.Region> regions,
            Optional<TerrainLodClipper.BoundsLong> replacement) {
        public SurfaceDescriptor {regions=List.copyOf(regions);Objects.requireNonNull(replacement);}
        public TerrainLodRefinement.Plan geometry(){return new TerrainLodRefinement.Plan(regions,replacement,0);}
    }
    public record SampleAdmission(List<TerrainLodRefinement.Region> accepted,
            List<TerrainLodRefinement.Region> deferred,long freshSamples) {
        public SampleAdmission {accepted=List.copyOf(accepted);deferred=List.copyOf(deferred);if(freshSamples<0)throw new IllegalArgumentException("Negative source reserve");}
    }
    public static SurfaceDescriptor describe(TerrainLodColumns.Bounds world,int x,int y,int z,
            List<TerrainLodRefinement.Band> bands,long maxFreshSamples,int maxRegions) {
        Objects.requireNonNull(world);var levels=List.copyOf(bands);
        if(levels.isEmpty()||maxFreshSamples<1||maxRegions<1)throw new IllegalArgumentException("Nonempty tiers and positive budgets required");
        int previousRadius=0,previousStep=0;long cx=Math.floorDiv(x,16)*16L,cy=Math.floorDiv(y,16)*16L,cz=Math.floorDiv(z,16)*16L;
        var regions=new ArrayList<TerrainLodRefinement.Region>();TerrainLodClipper.BoundsLong inner=null;
        for(var band:levels){if(band.radius()<=previousRadius||band.horizontalStep()<=previousStep)throw new IllegalArgumentException("Radii and steps must strictly increase");
            previousRadius=band.radius();previousStep=band.horizontalStep();long radius=band.radius();
            long x0=Math.max(cx-radius,world.minX()),x1=Math.min(cx+radius,(long)world.maxXInclusive()+1),
                y0=Math.max(cy-radius,world.minY()),y1=Math.min(cy+radius,(long)world.maxYInclusive()+1),
                z0=Math.max(cz-radius,world.minZ()),z1=Math.min(cz+radius,(long)world.maxZInclusive()+1);
            if(x0>=x1||y0>=y1||z0>=z1)continue;
            var outer=new TerrainLodClipper.BoundsLong(x0,y0,z0,x1,y1,z1);
            var volume=new TerrainLodColumns.Box(x0,y0,z0,x1,y1,z1,dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK);
            for(var slab:TerrainLodClipper.subtract(List.of(volume),inner==null?List.of():List.of(inner),maxRegions)){
                if(regions.size()>=maxRegions)throw new TerrainLodColumns.BudgetExceededException("Surface tier descriptor exceeds region cap");
                regions.add(new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds((int)slab.minX(),(int)slab.minY(),(int)slab.minZ(),
                    (int)(slab.maxXExclusive()-1),(int)(slab.maxYExclusive()-1),(int)(slab.maxZExclusive()-1)),band.horizontalStep()));}
            inner=outer;
        }
        // These slabs describe ownership only. No full-volume sample lattice is acquired.
        return new SurfaceDescriptor(regions,Optional.ofNullable(inner));
    }
    public static SampleAdmission admitSamples(List<TerrainLodRefinement.Region> jobs,long remaining,
            Predicate<TerrainLodRefinement.Region> exactCached) {
        if(remaining<0)throw new IllegalArgumentException("Negative remaining source reserve");
        Objects.requireNonNull(exactCached);long fresh=0;var accepted=new ArrayList<TerrainLodRefinement.Region>();var deferred=new ArrayList<TerrainLodRefinement.Region>();
        for(var region:jobs){if(exactCached.test(region)){accepted.add(region);continue;}long count=region.sampledVoxels();
            if(count<=remaining-fresh){fresh+=count;accepted.add(region);}else deferred.add(region);}
        return new SampleAdmission(accepted,deferred,fresh);
    }
    /** Immutable geometry-only hints. Its faces never certify fine state, opacity or AIR. */
    public static final class SurfaceIndex {
        private final List<TerrainLodColumns.Box> boundaries;
        private final int sourceStep;
        private SurfaceIndex(List<TerrainLodColumns.Box> boundaries,int sourceStep){this.boundaries=List.copyOf(boundaries);this.sourceStep=sourceStep;}
        public List<TerrainLodColumns.Box> boundaries(){return boundaries;}
    }
    public static SurfaceIndex index(List<TerrainLodColumns.Box> raw,int maxHints){
        return index(raw,maxHints,1);
    }
    public static SurfaceIndex index(List<TerrainLodColumns.Box> raw,int maxHints,int sourceStep){
        return index(raw,maxHints,sourceStep,null);
    }
    /** The acquisition border has an unknown neighbour, not a witnessed AIR surface. */
    public static SurfaceIndex index(List<TerrainLodColumns.Box> raw,int maxHints,int sourceStep,TerrainLodColumns.Bounds sourceBounds){
        if(maxHints<1)throw new IllegalArgumentException("Positive boundary-hint cap required");
        if(sourceStep<1||sourceStep>64||(sourceStep&(sourceStep-1))!=0)throw new IllegalArgumentException("Valid source lattice required");
        var opaque=new ArrayList<TerrainLodColumns.Box>();
        var water=new ArrayList<TerrainLodColumns.Box>();
        for(var b:raw){if(b.material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER){water.add(b);continue;}
            opaque.add(b);}
        var result=new ArrayList<TerrainLodColumns.Box>();
        appendFaces(result,opaque,maxHints,sourceBounds);
        // WATER never occludes an opaque shore or bottom. Extract the water group separately
        // so only shared WATER/WATER interfaces disappear from refinement priority.
        appendFaces(result,water,maxHints,sourceBounds);
        return new SurfaceIndex(result,sourceStep);
    }
    private static void appendFaces(List<TerrainLodColumns.Box> result,List<TerrainLodColumns.Box> boxes,
            int maxHints,TerrainLodColumns.Bounds sourceBounds){
        var bounds=new ArrayList<OpaqueBoxFaces.Bounds>(boxes.size());
        for(var b:boxes)bounds.add(new OpaqueBoxFaces.Bounds(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive()));
        var faces=OpaqueBoxFaces.extract(bounds,maxHints);
        for(var f:faces.faces()){
            if(sourceBounds!=null&&closesDomain(f,sourceBounds))continue;
            var material=boxes.get(f.owner()).material();long inside=f.positive()?f.plane()-1:f.plane();
            var slab=switch(f.axis()){
                case 0->new TerrainLodColumns.Box(inside,f.v0(),f.u0(),inside+1,f.v1(),f.u1(),material);
                case 1->new TerrainLodColumns.Box(f.u0(),inside,f.v0(),f.u1(),inside+1,f.v1(),material);
                default->new TerrainLodColumns.Box(f.u0(),f.v0(),inside,f.u1(),f.v1(),inside+1,material);};
            add(result,slab,maxHints);
        }
    }
    private static boolean closesDomain(OpaqueBoxFaces.Face face,TerrainLodColumns.Bounds bounds){
        long plane=switch(face.axis()){
            case 0->face.positive()?(long)bounds.maxXInclusive()+1:bounds.minX();
            case 1->face.positive()?(long)bounds.maxYInclusive()+1:bounds.minY();
            default->face.positive()?(long)bounds.maxZInclusive()+1:bounds.minZ();};
        return face.plane()==plane;
    }
    private static void add(List<TerrainLodColumns.Box> hints,TerrainLodColumns.Box next,int cap){if(hints.size()>=cap)throw new TerrainLodColumns.BudgetExceededException("Exposed surface-hint cap exceeded");hints.add(next);}

    public static List<TerrainLodRefinement.Region> prefix(TerrainLodRefinement.Plan requested,
            List<TerrainLodColumns.Box> rawNear,List<TerrainLodClipper.BoundsLong> nativeCuts,
            double x,double y,double z,int count) {
        return prefix(requested,rawNear,nativeCuts,x,y,z,0,0,1,count);
    }
    public static List<TerrainLodRefinement.Region> prefix(TerrainLodRefinement.Plan requested,
            List<TerrainLodColumns.Box> rawNear,List<TerrainLodClipper.BoundsLong> nativeCuts,
            double x,double y,double z,double forwardX,double forwardY,double forwardZ,int count) {
        return prefix(null,requested,rawNear,nativeCuts,x,y,z,forwardX,forwardY,forwardZ,count);
    }
    public static List<TerrainLodRefinement.Region> prefix(TerrainLodColumns.Bounds world,TerrainLodRefinement.Plan requested,
            List<TerrainLodColumns.Box> rawNear,List<TerrainLodClipper.BoundsLong> nativeCuts,
            double x,double y,double z,double forwardX,double forwardY,double forwardZ,int count) {
        int cap=(int)Math.max(1,Math.min(Integer.MAX_VALUE,12L*rawNear.size()));
        return prefix(world,requested,index(rawNear,cap),nativeCuts,x,y,z,forwardX,forwardY,forwardZ,count);
    }
    public static List<TerrainLodRefinement.Region> prefix(TerrainLodColumns.Bounds world,TerrainLodRefinement.Plan requested,
            SurfaceIndex sourceIndex,List<TerrainLodClipper.BoundsLong> nativeCuts,
            double x,double y,double z,double forwardX,double forwardY,double forwardZ,int count) {
        Objects.requireNonNull(sourceIndex);Objects.requireNonNull(nativeCuts);Objects.requireNonNull(requested);
        var rawNear=sourceIndex.boundaries();
        if(count<1||count>262144||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Bounded jobs and finite camera required");
        if(world==null){if(requested.regions().isEmpty())return List.of();long x0=Integer.MAX_VALUE,y0=Integer.MAX_VALUE,z0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,y1=Integer.MIN_VALUE,z1=Integer.MIN_VALUE;
            for(var r:requested.regions()){var b=r.bounds();x0=Math.min(x0,b.minX());y0=Math.min(y0,b.minY());z0=Math.min(z0,b.minZ());x1=Math.max(x1,b.maxXInclusive());y1=Math.max(y1,b.maxYInclusive());z1=Math.max(z1,b.maxZInclusive());}
            world=new TerrainLodColumns.Bounds((int)x0,(int)y0,(int)z0,(int)x1,(int)y1,(int)z1);}
        double length=Math.hypot(Math.hypot(forwardX,forwardY),forwardZ);if(!Double.isFinite(length)||length==0)throw new IllegalArgumentException("Finite view required");
        forwardX/=length;forwardY/=length;forwardZ/=length;
        Comparator<Job> comparator=AbyssLodSurfacePriority::compareJobs;
        var selected=new TreeSet<Job>(comparator);var byGrid=new HashMap<TerrainLodColumns.Bounds,Job>();
        int fragmentCap=Math.max(1,(int)Math.min(Integer.MAX_VALUE,4L*Math.max(rawNear.size(),count)));
        var visible=TerrainLodClipper.subtract(rawNear,nativeCuts,fragmentCap);
        for(var box:visible){double px=clamp(x,box.minX(),box.maxXExclusive()-1),py=clamp(y,box.minY(),box.maxYExclusive()-1),pz=clamp(z,box.minZ(),box.maxZExclusive()-1);
            long ax=Math.floorDiv((long)Math.floor(px),16)*16,ay=Math.floorDiv((long)Math.floor(py),64)*64,az=Math.floorDiv((long)Math.floor(pz),16)*16;
            // A coarse horizontal midpoint can put a proxy face in the adjacent canonical
            // cell. Check that one normal neighbour too; Y is already sampled at every block.
            long displacement=Math.max(16,sourceIndex.sourceStep/2);
            long hx=count>8&&sourceIndex.sourceStep>1&&box.maxXExclusive()-box.minX()==1?displacement:0,
                 hz=count>8&&sourceIndex.sourceStep>1&&box.maxZExclusive()-box.minZ()==1?displacement:0;
            var hint=hx==0&&hz==0?box:new TerrainLodColumns.Box(box.minX()-hx,box.minY(),box.minZ()-hz,
                    box.maxXExclusive()+hx,box.maxYExclusive(),box.maxZExclusive()+hz,box.material());
            long x0=count<=8?ax-16:Math.floorDiv(hint.minX(),16)*16,x1=count<=8?ax+16:Math.floorDiv(hint.maxXExclusive()-1,16)*16;
            long y0=count<=8?ay-64:Math.floorDiv(box.minY(),64)*64,y1=count<=8?ay+64:Math.floorDiv(box.maxYExclusive()-1,64)*64;
            long z0=count<=8?az-16:Math.floorDiv(hint.minZ(),16)*16,z1=count<=8?az+16:Math.floorDiv(hint.maxZExclusive()-1,16)*16;
            for(long tx=x0;tx<=x1;tx+=16)for(long ty=y0;ty<=y1;ty+=64)for(long tz=z0;tz<=z1;tz+=16){
                var b=tile(world,tx,ty,tz);if(b==null||!overlaps(b,hint)||(count>8&&!requested(b,requested)))continue;
                // A long face can cross both the front and rear view. Its nearest point is
                // useful for choosing the first local anchor, but cannot rank all its tiles.
                double sx=surfacePoint(x,box.minX(),box.maxXExclusive()-1,b.minX(),b.maxXInclusive()),
                       sy=surfacePoint(y,box.minY(),box.maxYExclusive()-1,b.minY(),b.maxYInclusive()),
                       sz=surfacePoint(z,box.minZ(),box.maxZExclusive()-1,b.minZ(),b.maxZInclusive());
                double dx=sx-x,dy=sy-y,dz=sz-z,d=Math.hypot(Math.hypot(dx,dy),dz);
                int view=d==0||dx*forwardX+dy*forwardY+dz*forwardZ>=d*.25?0:1;
                // Initial witnessed surfaces get real1m grids even outside the legacy camera band.
                // Later jobs may use declared2/4/8m bands; neither path resamples coarse geometry.
                int step=count<=8?1:step(requested,b);var region=new TerrainLodRefinement.Region(b,step);
                offer(selected,byGrid,new Job(region,view,d*d,distance(b,x,y,z)),count,comparator);
            }
        }
        if(count<=8&&selected.size()<count){long ax=Math.floorDiv((long)Math.floor(x),16)*16,ay=Math.floorDiv((long)Math.floor(y),64)*64,az=Math.floorDiv((long)Math.floor(z),16)*16;
            // Missing coarse witnesses do not prove AIR. Bounded local exact jobs remain available.
            for(int shell=0;shell<=4&&selected.size()<count;shell++)for(int ix=-shell;ix<=shell;ix++)for(int iy=-shell;iy<=shell;iy++)for(int iz=-shell;iz<=shell;iz++){
                if(Math.max(Math.max(Math.abs(ix),Math.abs(iy)),Math.abs(iz))!=shell)continue;
                var b=tile(world,ax+ix*16L,ay+iy*64L,az+iz*16L);if(b==null)continue;
                var region=new TerrainLodRefinement.Region(b,count<=8?1:step(requested,b));
                offer(selected,byGrid,new Job(region,2,Double.POSITIVE_INFINITY,distance(b,x,y,z)),count,comparator);
            }
        }
        var out=new ArrayList<TerrainLodRefinement.Region>();for(var job:selected)out.add(job.region());return List.copyOf(out);
    }
    private record Job(TerrainLodRefinement.Region region,int view,double surfaceDistance,double distance) { }
    private static int compareJobs(Job a,Job b){
        int c=Integer.compare(a.view(),b.view());if(c!=0)return c;c=Double.compare(a.surfaceDistance(),b.surfaceDistance());if(c!=0)return c;
        c=Double.compare(a.distance(),b.distance());if(c!=0)return c;var x=a.region().bounds();var y=b.region().bounds();
        c=Integer.compare(x.minX(),y.minX());if(c!=0)return c;c=Integer.compare(x.minZ(),y.minZ());if(c!=0)return c;
        c=Integer.compare(x.minY(),y.minY());return c!=0?c:Integer.compare(a.region().horizontalStep(),b.region().horizontalStep());
    }
    private static void offer(TreeSet<Job> selected,Map<TerrainLodColumns.Bounds,Job> byGrid,Job next,int cap,Comparator<Job> comparator){
        // Every retained candidate is <= the worst; this also cannot improve a duplicate grid.
        if(selected.size()==cap&&comparator.compare(next,selected.last())>=0)return;
        var old=byGrid.get(next.region().bounds());if(old!=null){if(comparator.compare(next,old)>=0)return;selected.remove(old);}
        selected.add(next);byGrid.put(next.region().bounds(),next);if(selected.size()>cap){var removed=selected.pollLast();byGrid.remove(removed.region().bounds());}}
    private static int step(TerrainLodRefinement.Plan plan,TerrainLodColumns.Bounds b){int step=8;for(var r:plan.regions()){var p=r.bounds();
        if(p.minX()<=b.maxXInclusive()&&p.maxXInclusive()>=b.minX()&&p.minY()<=b.maxYInclusive()&&p.maxYInclusive()>=b.minY()&&p.minZ()<=b.maxZInclusive()&&p.maxZInclusive()>=b.minZ())step=Math.min(step,r.horizontalStep());}return step;}
    private static boolean requested(TerrainLodColumns.Bounds b,TerrainLodRefinement.Plan plan){for(var r:plan.regions()){var p=r.bounds();if(p.minX()<=b.maxXInclusive()&&p.maxXInclusive()>=b.minX()&&p.minY()<=b.maxYInclusive()&&p.maxYInclusive()>=b.minY()&&p.minZ()<=b.maxZInclusive()&&p.maxZInclusive()>=b.minZ())return true;}return false;}
    private static TerrainLodColumns.Bounds tile(TerrainLodColumns.Bounds world,long x,long y,long z){long x0=Math.max(x,world.minX()),y0=Math.max(y,world.minY()),z0=Math.max(z,world.minZ()),
        x1=Math.min(x+16,(long)world.maxXInclusive()+1),y1=Math.min(y+64,(long)world.maxYInclusive()+1),z1=Math.min(z+16,(long)world.maxZInclusive()+1);
        return x0<x1&&y0<y1&&z0<z1?new TerrainLodColumns.Bounds((int)x0,(int)y0,(int)z0,(int)(x1-1),(int)(y1-1),(int)(z1-1)):null;}
    private static boolean overlaps(TerrainLodColumns.Bounds a,TerrainLodColumns.Box b){return a.minX()<b.maxXExclusive()&&(long)a.maxXInclusive()+1>b.minX()&&a.minY()<b.maxYExclusive()&&(long)a.maxYInclusive()+1>b.minY()&&a.minZ()<b.maxZExclusive()&&(long)a.maxZInclusive()+1>b.minZ();}
    private static double clamp(double v,long lo,long hi){return Math.max(lo,Math.min(v,hi));}
    private static double surfacePoint(double v,long faceLow,long faceHigh,long tileLow,long tileHigh){
        long low=Math.max(faceLow,tileLow),high=Math.min(faceHigh,tileHigh);
        // The bounded normal-neighbour guard can be outside a coarse proxy plane. Keep
        // that plane's coordinate while clipping the intersecting tangential coordinates.
        return low<=high?clamp(v,low,high):clamp(v,faceLow,faceHigh);
    }
    private static double distance(TerrainLodColumns.Bounds b,double x,double y,double z){double dx=Math.max(0,Math.max(b.minX()-x,x-((long)b.maxXInclusive()+1))),dy=Math.max(0,Math.max(b.minY()-y,y-((long)b.maxYInclusive()+1))),dz=Math.max(0,Math.max(b.minZ()-z,z-((long)b.maxZInclusive()+1)));return dx*dx+dy*dy+dz*dz;}
}
