package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real cache keys and paired frozen/current coordinator replay; no timing assertions. */
public final class T8CoordinatorBenchmark {
    private static volatile long sink;
    private static final com.sun.management.ThreadMXBean BEAN=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    interface Task {long run();}
    record Sample(double ns,double bytes,long sum){}
    static Sample run(Task task,int repeats){
        long thread=Thread.currentThread().getId(),bytes=BEAN.getThreadAllocatedBytes(thread),sum=0,start=System.nanoTime();
        for(int i=0;i<repeats;i++)sum+=task.run();
        long elapsed=System.nanoTime()-start;bytes=BEAN.getThreadAllocatedBytes(thread)-bytes;sink=sum;
        return new Sample((double)elapsed/repeats,(double)bytes/repeats,sum);
    }
    static void pair(String name,Task before,Task after,int repeats){
        for(int i=0;i<3;i++){run(before,repeats);run(after,repeats);}
        double[] a=new double[7],b=new double[7],ab=new double[7],bb=new double[7];
        for(int i=0;i<7;i++){
            Sample x,y;if((i&1)==0){x=run(before,repeats);y=run(after,repeats);}else{y=run(after,repeats);x=run(before,repeats);}
            if(x.sum()!=y.sum())throw new AssertionError(name+" output mismatch");
            a[i]=x.ns();b[i]=y.ns();ab[i]=x.bytes();bb[i]=y.bytes();
            System.out.printf(Locale.ROOT,"ROUND,%s,%d,%.3f,%.3f,%.1f,%.1f%n",name,i,x.ns(),y.ns(),x.bytes(),y.bytes());
        }
        Arrays.sort(a);Arrays.sort(b);Arrays.sort(ab);Arrays.sort(bb);
        System.out.printf(Locale.ROOT,"MEDIAN,%s,%.3f,%.3f,%.4f,%.1f,%.1f%n",name,a[3],b[3],a[3]/b[3],ab[3],bb[3]);
    }
    static List<TerrainLodColumns.Box> load(String path)throws Exception {
        var result=new ArrayList<TerrainLodColumns.Box>();
        for(var line:Files.readAllLines(Path.of(path))){var v=line.split("\t");if(v.length!=7)continue;
            result.add(new TerrainLodColumns.Box(Long.parseLong(v[0]),Long.parseLong(v[1]),Long.parseLong(v[2]),Long.parseLong(v[3]),Long.parseLong(v[4]),Long.parseLong(v[5]),TerrainMaterial.values()[Integer.parseInt(v[6])]));}
        return List.copyOf(result);
    }
    static void distribution(List<TerrainLodColumns.Box> boxes){
        int capacity=1;while(capacity<boxes.size()/0.75)capacity*=2;
        for(boolean strong:new boolean[]{false,true}){
            var hashes=new HashSet<Integer>();int[] bins=new int[capacity];
            for(var b:boxes){int h=strong?StrongBoxKey.hash(b):b.hashCode();hashes.add(h);bins[(h^(h>>>16))&(capacity-1)]++;}
            long squares=0;int max=0,occupied=0,heavy=0;
            for(int n:bins){squares+=(long)n*n;max=Math.max(max,n);if(n>0)occupied++;if(n>=8)heavy++;}
            System.out.printf(Locale.ROOT,"HASH,%s,%d,%d,%d,%d,%d,%d,%d%n",strong?"strong":"legacy",boxes.size(),hashes.size(),capacity,occupied,max,heavy,squares);
        }
    }
    static void cache(List<TerrainLodColumns.Box> boxes){
        var before=new BoundedReuseCache<TerrainLodColumns.Box,Integer>(128L*boxes.size(),boxes.size(),v->128L);
        var after=new BoundedBoxReuseCache<Integer>(128L*boxes.size(),boxes.size(),v->128L);
        var hits=new ArrayList<TerrainLodColumns.Box>();var misses=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<boxes.size();i++){
            var b=boxes.get(i);before.put(b,i);after.put(b,i);
            hits.add(new TerrainLodColumns.Box(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive(),b.material()));
            misses.add(new TerrainLodColumns.Box(b.minX(),b.minY()+100_000,b.minZ(),b.maxXExclusive(),b.maxYExclusive()+100_000,b.maxZExclusive(),b.material()));
        }
        Collections.shuffle(hits,new Random(739));Collections.shuffle(misses,new Random(819));
        for(int warm=0;warm<12;warm++)for(var b:hits){sink=before.get(b);sink=after.get(b);}
        pair("real-cache-hits-"+boxes.size(),()->{long sum=0;for(var b:hits)sum+=before.get(b);return sum;},()->{long sum=0;for(var b:hits)sum+=after.get(b);return sum;},4);
        pair("real-cache-misses-"+boxes.size(),()->{long sum=0;for(var b:misses)if(before.get(b)==null)sum++;return sum;},()->{long sum=0;for(var b:misses)if(after.get(b)==null)sum++;return sum;},4);
    }
    static void updates(ExecutorService executor){
        var a=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var b=new T8ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var all=new ArrayList<TerrainLodColumns.Box>();var old=new ArrayList<TerrainLodColumns.Box>();var next=new ArrayList<TerrainLodColumns.Box>();
        for(int page=0;page<32;page++)for(int i=0;i<512;i++){
            var box=T3LodOracleTest.box((page-16)*128L+1+i%16*4,1+i/256*16,i/16%16*4+1,2,8,2,TerrainMaterial.ROCK);all.add(box);
            if(page==16&&i%32==0){old.add(box);next.add(new TerrainLodColumns.Box(box.minX(),box.minY(),box.minZ(),box.maxXExclusive(),box.maxYExclusive()+1,box.maxZExclusive(),box.material()));}
        }
        a.update(List.of(),all);b.update(List.of(),all);int[] x={0},y={0};
        pair(executor==null?"page-update-sequential":"page-update-worker",()->{boolean f=(x[0]++&1)==0;b.update(f?old:next,f?next:old,List.of(),false,executor,executor==null?1:3);return 1;},
            ()->{boolean f=(y[0]++&1)==0;a.update(f?old:next,f?next:old,List.of(),false,executor,executor==null?1:3);return 1;},32);
    }
    static void preparation(){
        var a=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64);
        var b=new T8ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64);
        var all=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<128;i++)all.add(T3LodOracleTest.box((i%16)*256L-2049,-65,(i/16)*256L-2049,130,130,130,TerrainMaterial.ROCK));
        a.update(List.of(),all);b.update(List.of(),all);
        pair("shared-negative-restore-128-owners",()->b.preparePartial(null,256,T3LodOracleTest.ORDER).pendingPages(),()->a.preparePartial(null,256,T3LodOracleTest.ORDER).pendingPages(),8);
        a.setLightLookup(T3LodOracleTest::light);b.setLightLookup(T3LodOracleTest::light);
        var ar=a.preparePartial(null,100000,T3LodOracleTest.ORDER).published();var br=b.preparePartial(null,100000,T3LodOracleTest.ORDER).published();
        if(!ar.equals(br))throw new AssertionError("Full prepared bytes/order differ");
        pair("unchanged-ready-page-union",()->b.preparePartial(br,256,T3LodOracleTest.ORDER).published().faces(),()->a.preparePartial(ar,256,T3LodOracleTest.ORDER).published().faces(),16);
    }
    static void realPages(List<TerrainLodColumns.Box> all){
        var boxes=all.subList(0,Math.min(8192,all.size()));
        var a=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var b=new T8ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        var oldCache=new BoundedReuseCache<TerrainLodColumns.Box,SurfaceQuadPlan.Owner>(128L*boxes.size(),boxes.size(),v->128L);
        var newCache=new BoundedReuseCache<TerrainLodColumns.Box,SurfaceQuadPlan.Owner>(128L*boxes.size(),boxes.size(),v->128L);
        for(int i=0;i<boxes.size();i+=4){var box=boxes.get(i);oldCache.put(box,T3LodOracleTest.light(box));newCache.put(box,T3LodOracleTest.light(box));}
        a.update(List.of(),boxes);b.update(List.of(),boxes);a.setLightLookup(newCache::get,()->BoxCacheRevision.read(newCache));b.setLightLookup(oldCache::get);
        var ar=new SurfaceQuadPlan.PageSet[1];var br=new SurfaceQuadPlan.PageSet[1];
        pair("real-pages-8192-stable-mixed-light",()->{var p=b.preparePartial(br[0],256,T3LodOracleTest.ORDER);br[0]=p.published();return p.pendingPages()+p.published().faces();},
            ()->{var p=a.preparePartial(ar[0],256,T3LodOracleTest.ORDER);ar[0]=p.published();return p.pendingPages()+p.published().faces();},256);
        if(!ar[0].equals(br[0]))throw new AssertionError("Real partial page values differ");
        for(var box:boxes){oldCache.put(box,T3LodOracleTest.light(box));newCache.put(box,T3LodOracleTest.light(box));}
        var completeA=a.preparePartial(ar[0],100000,T3LodOracleTest.ORDER);var completeB=b.preparePartial(br[0],100000,T3LodOracleTest.ORDER);
        if(!completeA.equals(completeB))throw new AssertionError("Real complete page values differ");
    }
    static void adversarial(){
        var boxes=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<2048;i++)boxes.add(T3LodOracleTest.box(100+i,100,10_000_000L-961L*i,2,8,2,TerrainMaterial.ROCK));
        cache(boxes);
        var a=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);var b=new T8ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128);
        a.update(List.of(),boxes);b.update(List.of(),boxes);
        pair("aligned-owner-index-2048",()->{long sum=0;for(var box:boxes)if(b.containsOwner(box))sum++;return sum;},()->{long sum=0;for(var box:boxes)if(a.containsOwner(box))sum++;return sum;},4);
    }
    static void admissions(List<TerrainLodColumns.Box> all){
        var cache=new BoundedReuseCache<TerrainLodColumns.Box,Integer>(128L*4096,4096,v->128L);for(var box:all.subList(0,4096))cache.put(box,1);
        pair("coherent-cache-revision-observer",()->cache.evictions()+(long)cache.size(),()->BoxCacheRevision.read(cache),65536);
    }
    public static void main(String[] args)throws Exception {
        BEAN.setThreadAllocatedMemoryEnabled(true);var boxes=load(args[0]);
        System.out.println("VM,"+System.getProperty("java.runtime.version")+","+ManagementFactory.getGarbageCollectorMXBeans().stream().map(x->x.getName()).toList());
        distribution(boxes);cache(boxes);
        var far=load(Path.of(args[0]).resolveSibling("far-boxes.tsv").toString());distribution(far);cache(far);updates(null);
        var executor=Executors.newFixedThreadPool(3);try{updates(executor);}finally{executor.shutdownNow();}
        preparation();realPages(boxes);admissions(boxes);adversarial();
    }
}
