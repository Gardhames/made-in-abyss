package dev.madeinabyss.kernel.terrain.natural;

import dev.madeinabyss.kernel.terrain.*;
import java.util.*;

/** Standalone exact digest/performance experiment; not a Minecraft loading benchmark. */
public final class SupportedCaveLookupBenchmark {
    private static volatile long sink;
    public static void main(String[] args) throws Exception {
        var p=new TerrainParameters(20261003,new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
                new TerrainParameters.Cavity(500,64,-1286,500,180,170,0),new TerrainParameters.Noise(140,0,0,0));
        CaveNetworkTerrainSampler.Source source=(x,y,z)->new TerrainSample.Known(Math.hypot(x,z)>=500?TerrainMaterial.ROCK:TerrainMaterial.AIR);
        var s=new SupportedCaveTerrainSampler(p,CaveNetworkParameters.proposal(827),source,(x,y,z)->false);
        int n=300000;int[] xs=new int[n],ys=new int[n],zs=new int[n];var random=new Random(91010);
        var feet=s.routes().stream().flatMap(r->r.feet().stream()).toList();
        for(int i=0;i<n;i++) {
            if(i%3==0){var f=feet.get(random.nextInt(feet.size()));xs[i]=f.x()+random.nextInt(5)-2;ys[i]=f.y()+random.nextInt(7)-3;zs[i]=f.z()+random.nextInt(5)-2;}
            else {xs[i]=random.nextInt(1401)-700;ys[i]=random.nextInt(1401)-1350;zs[i]=random.nextInt(1401)-700;}
        }
        int[] extremes={Integer.MIN_VALUE,-769,-768,-33,-32,-1,0,31,32,767,768,Integer.MAX_VALUE};
        for(int i=0;i<extremes.length;i++){xs[i]=extremes[i];ys[i]=extremes[extremes.length-1-i];zs[i]=-32;}
        for(String field:new String[]{"floors","clearanceNetworks"}) {
            var f=SupportedCaveTerrainSampler.class.getDeclaredField(field);f.setAccessible(true);var value=f.get(s);
            Collection<?> keys=value instanceof Map<?,?> m?m.keySet():(Collection<?>)value;
            int capacity=1;while(capacity<keys.size()/0.75)capacity<<=1;int[] buckets=new int[capacity];var hashes=new HashSet<Integer>();
            for(var key:keys){int h=key.hashCode();hashes.add(h);buckets[(h^(h>>>16))&(capacity-1)]++;}
            int maximum=Arrays.stream(buckets).max().orElse(0);long treeCandidates=Arrays.stream(buckets).filter(v->v>=8).count();
            System.out.println(field+" keys="+keys.size()+" uniqueHashes="+hashes.size()+" maxBucket="+maximum+" binsAtLeast8="+treeCandidates);
        }
        for(int mode=0;mode<5;mode++) {
            long digest=0;long[] times=new long[5];
            for(int round=0;round<8;round++) {
                long start=System.nanoTime(),hash=1;
                for(int i=0;i<n;i++) {
                    int value=switch(mode){
                        case 0->s.routeProtectedAt(xs[i],ys[i],zs[i])?1:0;
                        case 1->s.caveNetworkAt(xs[i],ys[i],zs[i]);
                        case 2->sampleCode(s.sample(xs[i],ys[i],zs[i]));
                        case 3->s.mayAffectShell(xs[i],ys[i],zs[i])?1:0;
                        default->s.featureAt(xs[i],ys[i],zs[i]).hashCode();
                    };
                    hash=hash*31+value;
                }
                sink=hash;if(round>=3)times[round-3]=System.nanoTime()-start;digest=hash;
            }
            Arrays.sort(times);System.out.println("mode="+mode+" queries="+n+" medianNanos="+times[2]+" digest="+digest);
        }
        long segmentDigest=1;
        for(var e:s.segments())for(long value:new long[]{Double.doubleToLongBits(e.a().x()),Double.doubleToLongBits(e.a().y()),Double.doubleToLongBits(e.a().z()),Double.doubleToLongBits(e.b().x()),Double.doubleToLongBits(e.b().y()),Double.doubleToLongBits(e.b().z()),Double.doubleToLongBits(e.radius()),e.kind().ordinal(),e.network()})segmentDigest=segmentDigest*31+value;
        System.out.println("routes="+s.routes().size()+" feet="+feet.size()+" routeDigest="+s.routes().hashCode()+" segmentDigest="+segmentDigest+" sink="+sink);
    }
    private static int sampleCode(TerrainSample sample){return sample instanceof TerrainSample.Known k?k.material().ordinal():100+((TerrainSample.Unavailable)sample).reason().ordinal();}
}
