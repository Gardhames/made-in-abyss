package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.lod.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import dev.madeinabyss.kernel.concurrent.BoundedOrderedWork;
import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;

/** Source-scoped presentation samples from known open face neighbours, not solid owner centres. */
public final class ProvisionalSkyPages {
    public enum Aggregation {MAX,SECOND_BRIGHTEST,MEDIAN}
    public record Point(int x,int y,int z){}
    public record View(double x,double y,double z,double forwardX,double forwardY,double forwardZ,double fov,double aspect){
        public View {if(!Double.isFinite(x+y+z+forwardX+forwardY+forwardZ+fov+aspect)||fov<=0||fov>=179||aspect<=0)throw new IllegalArgumentException("Finite view required");}
    }
    @FunctionalInterface public interface Probe {int at(int x,int y,int z);}
    public record Stats(int pages,long skyEvaluations,long openChecks,long buildNanos){}
    private record Key(int x,int y,int z){@Override public int hashCode(){return dev.madeinabyss.kernel.concurrent.FrozenCubeTable.hash(x,y,z);}}
    private record Candidate(Point point,int axis,boolean positive,double area,int rank,double distance){}
    private record Result(List<Candidate> candidates,List<Point> points,int sky,int minimum,int maximum,int sampled){}
    private static final Comparator<Candidate> ORDER=Comparator.comparingInt(Candidate::rank)
            .thenComparing(Comparator.comparingDouble(Candidate::area).reversed()).thenComparingDouble(Candidate::distance)
            .thenComparingInt(c->c.point.x).thenComparingInt(c->c.point.y).thenComparingInt(c->c.point.z);
    private static final int[][] NEIGHBOURS={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final class Data {
        volatile Result result;volatile long revision;
        int exactMin=15,exactMax,exactCount;final Set<TerrainLodColumns.Box> exactWitnesses=new HashSet<>();
    }
    private static final class Bag {
        final TreeSet<Candidate> sorted=new TreeSet<>(ORDER);final Map<Point,Candidate> points=new HashMap<>();
        void offer(Candidate c){var old=points.get(c.point);if(old!=null){if(ORDER.compare(c,old)>=0)return;sorted.remove(old);points.remove(c.point);}
            if(sorted.size()>=32&&ORDER.compare(c,sorted.last())>=0)return;sorted.add(c);points.put(c.point,c);
            if(sorted.size()>32){var removed=sorted.pollLast();points.remove(removed.point);}}
    }
    private final Probe opacity,probeSky;private final int maximumPages;
    private final Aggregation aggregation;
    private final java.util.function.Predicate<Point> domain;
    private volatile View view;private final ConcurrentHashMap<Key,Data> cache=new ConcurrentHashMap<>();
    private long viewRevision;
    private final LinkedHashMap<SurfaceQuadPlan.PageKey,Key[]> geometryDependencies=new LinkedHashMap<>();
    private final BoundedReuseCache<Point,Integer> pointSky;
    private final LongAdder evaluations=new LongAdder(),openChecks=new LongAdder();private volatile long buildNanos;
    /** One presentation point: the original integer 32-ray response, without lattice corners. */
    public static int pointSky(int x,int y,int z,int openSkyY,int maxSteps,SkyVisibility.VoxelOpacity opacity,int direct){
        valid(direct);if(direct==15)return direct;
        return Math.max(direct,QuantizedDiffuseSky.level(x,y,z,openSkyY,maxSteps,opacity,false));
    }
    public ProvisionalSkyPages(Probe opacity,Probe sky,View view,int maximumPages){
        this(opacity,sky,view,maximumPages,p->true);
    }
    public ProvisionalSkyPages(Probe opacity,Probe sky,View view,int maximumPages,java.util.function.Predicate<Point> domain){
        this(opacity,sky,view,maximumPages,domain,Aggregation.MAX);
    }
    public ProvisionalSkyPages(Probe opacity,Probe sky,View view,int maximumPages,java.util.function.Predicate<Point> domain,Aggregation aggregation){
        this.opacity=Objects.requireNonNull(opacity);probeSky=Objects.requireNonNull(sky);this.view=Objects.requireNonNull(view);
        this.aggregation=Objects.requireNonNull(aggregation);
        this.domain=Objects.requireNonNull(domain);
        if(maximumPages<1)throw new IllegalArgumentException("Positive page bound required");this.maximumPages=maximumPages;
        pointSky=new BoundedReuseCache<>(maximumPages*8L,Math.multiplyExact(maximumPages,8),p->1L);
    }
    public void view(View view){Objects.requireNonNull(view);if(!this.view.equals(view)){this.view=view;viewRevision++;}}
    public long viewRevision(){return viewRevision;}
    private static Key key(TerrainLodColumns.Box b){return new Key(Math.toIntExact(Math.floorDiv(b.minX(),128)),Math.toIntExact(Math.floorDiv(b.minY(),128)),Math.toIntExact(Math.floorDiv(b.minZ(),128)));}
    public void prepare(List<SurfaceProvisionalLight.Page> pages,ExecutorService executor,int parallelism){
        long start=System.nanoTime();var proposed=new LinkedHashMap<Key,Bag>();var nextDependencies=new LinkedHashMap<SurfaceQuadPlan.PageKey,Key[]>();
        var currentView=view;
        for(var page:pages){check();var geometry=page.geometry();var deps=new LinkedHashSet<Key>();var ownerKeys=new Key[geometry.owners().size()];
            for(var face:geometry.faces()){
                int owner=face.owner();Key k=ownerKeys[owner];if(k==null){k=key(geometry.owners().get(owner));ownerKeys[owner]=k;}deps.add(k);
                var bag=proposed.get(k);if(bag==null){bag=new Bag();var previous=cache.get(k);
                    if(previous!=null&&previous.result!=null)for(var c:previous.result.candidates)bag.offer(candidate(c.point,c.axis,c.positive,c.area,currentView));proposed.put(k,bag);}
                long u=face.u0()+(face.u1()-face.u0()-1)/2,v=face.v0()+(face.v1()-face.v0()-1)/2,n=face.plane()+(face.positive()?0:-1);
                var point=new Point(Math.toIntExact(face.axis()==0?n:u),Math.toIntExact(face.axis()==1?n:v),Math.toIntExact(face.axis()==0?u:face.axis()==1?v:n));
                double area=(double)(face.u1()-face.u0())*(face.v1()-face.v0());bag.offer(candidate(point,face.axis(),face.positive(),area,currentView));
            }
            nextDependencies.put(page.key(),deps.toArray(Key[]::new));
        }
        var keys=new ArrayList<>(proposed.keySet());
        long births=keys.stream().filter(k->!cache.containsKey(k)).count();
        if(cache.size()+births>maximumPages)throw new TerrainLodColumns.BudgetExceededException("Provisional source-page admission exceeds bounded context");
        var evaluated=executor==null?keys.stream().map(k->evaluate(proposed.get(k),cache.get(k))).toList():
                BoundedOrderedWork.map(keys,parallelism,executor,k->evaluate(proposed.get(k),cache.get(k)));
        check();
        // UNKNOWN/cancellation above cannot publish half a new page plan.
        for(int i=0;i<keys.size();i++){var k=keys.get(i);var value=evaluated.get(i);var data=cache.get(k);
            if(data==null){data=new Data();cache.put(k,data);}
            var old=data.result;data.result=value;if(old==null||old.sky!=value.sky)data.revision++;
        }
        nextDependencies.forEach((k,v)->{if(!geometryDependencies.containsKey(k)&&geometryDependencies.size()>=maximumPages*8)geometryDependencies.remove(geometryDependencies.keySet().iterator().next());geometryDependencies.put(k,v);});
        buildNanos+=System.nanoTime()-start;
    }
    private Candidate candidate(Point p,int axis,boolean positive,double area,View v){
        double dx=p.x+.5-v.x,dy=p.y+.5-v.y,dz=p.z+.5-v.z,side=axis==0?-dx:axis==1?-dy:-dz;
        boolean facing=positive?side>=0:side<=0;int rank=facing?(inView(p,v)?0:1):2;
        return new Candidate(p,axis,positive,area,rank,dx*dx+dy*dy+dz*dz);
    }
    private Result evaluate(Bag bag,Data old){
        var points=new ArrayList<Point>(4);var candidates=List.copyOf(bag.sorted);
        if(old!=null&&old.result!=null&&old.result.candidates.equals(candidates))return old.result;
        for(var c:candidates){check();if(!domain.test(c.point))continue;openChecks.increment();int value=valid(opacity.at(c.point.x,c.point.y,c.point.z));
            if(value==0&&!points.contains(c.point)){points.add(c.point);if(points.size()==4)break;}}
        if(old!=null&&old.result!=null&&old.result.points.equals(points))return old.result;
        int maximum=0,minimum=15,sampled=0;int[] samples=new int[points.size()];
        for(var p:points){var known=pointSky.get(p);int value;
            if(known==null){value=valid(probeSky.at(p.x,p.y,p.z));pointSky.put(p,value);evaluations.increment();}else value=known;
            samples[sampled++]=value;maximum=Math.max(maximum,value);minimum=Math.min(minimum,value);
            if(aggregation==Aggregation.MAX&&maximum==15)break;
        }
        return new Result(candidates,List.copyOf(points),aggregate(Arrays.copyOf(samples,sampled),aggregation),points.isEmpty()?0:minimum,maximum,sampled);
    }
    public static int aggregate(int[] samples,Aggregation aggregation){
        Objects.requireNonNull(samples);Objects.requireNonNull(aggregation);
        if(samples.length>4)throw new IllegalArgumentException("At most four open probes");
        int[] values=samples.clone();for(int value:values)valid(value);Arrays.sort(values);int n=values.length;
        if(n==0)return 0;
        return switch(aggregation){case MAX->values[n-1];case SECOND_BRIGHTEST->values[Math.max(0,n-2)];case MEDIAN->(values[(n-1)/2]+values[n/2]+1)/2;};
    }
    public int sky(TerrainLodColumns.Box box){var data=cache.get(key(box));if(data==null||data.result==null)throw new IllegalStateException("Provisional owner has no prepared geometry plan");return data.result.sky;}
    public Object dependencies(SurfaceQuadPlan.Geometry geometry){var keys=new LinkedHashSet<Key>();for(int id:geometry.visibleOwners())keys.add(key(geometry.owners().get(id)));return keys.toArray(Key[]::new);}
    public long revision(Object dependencies){long result=1+viewRevision;for(var k:(Key[])dependencies){var data=cache.get(k);result=result*31+(data==null?0:data.revision);}return result;}
    public int priority(SurfaceQuadPlan.PageKey page){
        var keys=geometryDependencies.get(page);if(keys==null)return 0;int risk=0;
        for(var k:keys){var data=cache.get(k);if(data==null||data.result==null)continue;var r=data.result;
            risk=Math.max(risk,Math.max(r.sky-r.minimum,r.maximum-r.sky));
            if(data.exactCount>0)risk=Math.max(risk,Math.max(Math.abs(r.sky-data.exactMin),Math.abs(r.sky-data.exactMax)));
            if(data.exactCount<4&&r.sampled<r.points.size())risk=Math.max(risk,r.sky);
            for(var d:NEIGHBOURS){var neighbor=cache.get(new Key(k.x+d[0],k.y+d[1],k.z+d[2]));if(neighbor!=null&&neighbor.result!=null)risk=Math.max(risk,Math.abs(r.sky-neighbor.result.sky));}
        }return risk;
    }
    public void noteExact(TerrainLodColumns.Box box,int sky){var data=cache.get(key(box));if(data==null)return;sky=valid(sky);
        data.exactMin=Math.min(data.exactMin,sky);data.exactMax=Math.max(data.exactMax,sky);
        if(data.exactCount<4&&data.exactWitnesses.add(box))data.exactCount++;
    }
    public Stats stats(){return new Stats(cache.size(),evaluations.sum(),openChecks.sum(),buildNanos);}
    public static boolean inView(Point p,View v){
        double dx=p.x+.5-v.x,dy=p.y+.5-v.y,dz=p.z+.5-v.z,forward=dx*v.forwardX+dy*v.forwardY+dz*v.forwardZ;
        if(forward<=0)return false;
        double length=Math.hypot(v.forwardX,v.forwardZ),rx=length==0?1:-v.forwardZ/length,rz=length==0?0:v.forwardX/length;
        double ux=-rz*v.forwardY,uy=rz*v.forwardX-rx*v.forwardZ,uz=rx*v.forwardY,tan=Math.tan(Math.toRadians(v.fov)/2);
        return Math.abs(dx*rx+dz*rz)<=forward*tan*v.aspect&&Math.abs(dx*ux+dy*uy+dz*uz)<=forward*tan;
    }
    /** Conservative five-plane frustum/AABB test, including close faces with no corner on screen. */
    public static boolean intersectsPage(SurfaceQuadPlan.PageKey page,View v){
        double x=page.x()*128.0+64-v.x,y=page.y()*128.0+64-v.y,z=page.z()*128.0+64-v.z;
        double length=Math.hypot(v.forwardX,v.forwardZ),rx=length==0?1:-v.forwardZ/length,rz=length==0?0:v.forwardX/length;
        double ux=-rz*v.forwardY,uy=rz*v.forwardX-rx*v.forwardZ,uz=rx*v.forwardY,tan=Math.tan(Math.toRadians(v.fov)/2);
        if(outside(x,y,z,v.forwardX,v.forwardY,v.forwardZ))return false;
        for(int sign:new int[]{-1,1}){
            if(outside(x,y,z,v.forwardX*tan*v.aspect+sign*rx,v.forwardY*tan*v.aspect,v.forwardZ*tan*v.aspect+sign*rz))return false;
            if(outside(x,y,z,v.forwardX*tan+sign*ux,v.forwardY*tan+sign*uy,v.forwardZ*tan+sign*uz))return false;
        }return true;
    }
    private static boolean outside(double x,double y,double z,double nx,double ny,double nz){return x*nx+y*ny+z*nz+64*(Math.abs(nx)+Math.abs(ny)+Math.abs(nz))<0;}
    private static int valid(int sky){if(sky<0||sky>15)throw new IllegalArgumentException("SKY/opacity must be0..15");return sky;}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Provisional geometry retired");}
}
