package dev.madeinabyss.kernel.terrain.living;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.lang.ref.WeakReference;

/** Exact, point-scoped reuse for the frozen155 derived view. The canonical generator
 * never calls this class. Every key retains all scalar input bits; misses execute the
 * original function. Per-thread storage is finite and contains no source callbacks. */
public final class Exact155Math {
    private Exact155Math(){}
    private static final int CAPACITY=256,MASK=CAPACITY-1;
    private static final ConcurrentLinkedQueue<WeakReference<Frame>> FRAMES=new ConcurrentLinkedQueue<>();
    private static final class Frame {
        int generation,hardnessCount,hardnessCursor,hardnessLast=-1;
        final byte[] replacements=new byte[64];
        final int[] stamps=new int[CAPACITY];
        final long[] seeds=new long[CAPACITY],thetas=new long[CAPACITY],depths=new long[CAPACITY],pxs=new long[CAPACITY],pys=new long[CAPACITY],salts=new long[CAPACITY],radii=new long[CAPACITY];
        final double[] values=new double[CAPACITY];
        final LivingAbyssStrata155[] strata=new LivingAbyssStrata155[16];
        final long[] hardnessTheta=new long[16],hardnessDepth=new long[16];final double[] hardnessValue=new double[16];
        long noiseEvaluations,noiseHits,hardnessEvaluations,hardnessHits,columnSkips;
    }
    private static final ThreadLocal<Frame> LOCAL=ThreadLocal.withInitial(()->{var frame=new Frame();FRAMES.add(new WeakReference<>(frame));return frame;});
    public static void begin(){var f=LOCAL.get();if(++f.generation==0){Arrays.fill(f.stamps,0);f.generation=1;}
        Arrays.fill(f.strata,0,f.hardnessCount,null);f.hardnessCount=0;}
    public static double noise(long seed,double theta,double depth,double px,double py,long salt,double radius){
        var f=LOCAL.get();if(f.generation==0)begin();
        long t=Double.doubleToRawLongBits(theta),d=Double.doubleToRawLongBits(depth),xx=Double.doubleToRawLongBits(px),yy=Double.doubleToRawLongBits(py),rr=Double.doubleToRawLongBits(radius);
        long hash=seed^Long.rotateLeft(t,7)^Long.rotateLeft(d,17)^Long.rotateLeft(xx,27)^Long.rotateLeft(yy,37)^Long.rotateLeft(salt,47)^rr;
        hash=(hash^(hash>>>30))*0xbf58476d1ce4e5b9L;hash=(hash^(hash>>>27))*0x94d049bb133111ebL;int set=(int)(hash^(hash>>>31))&63,base=set*4,free=-1;
        for(int way=0;way<4;way++){int at=base+way;
            if(f.stamps[at]!=f.generation){if(free<0)free=at;continue;}
            if(f.seeds[at]==seed&&f.thetas[at]==t&&f.depths[at]==d&&f.pxs[at]==xx&&f.pys[at]==yy&&f.salts[at]==salt&&f.radii[at]==rr){f.noiseHits++;return f.values[at];}
        }
        int at=free>=0?free:base+(f.replacements[set]++&3);double value=LivingAbyssMath155.noise(seed,theta,depth,px,py,salt,radius);f.noiseEvaluations++;
        f.stamps[at]=f.generation;f.seeds[at]=seed;f.thetas[at]=t;f.depths[at]=d;f.pxs[at]=xx;f.pys[at]=yy;f.salts[at]=salt;f.radii[at]=rr;f.values[at]=value;return value;
    }
    public static double hardness(LivingAbyssStrata155 strata,double theta,double depth){
        var f=LOCAL.get();long t=Double.doubleToRawLongBits(theta),d=Double.doubleToRawLongBits(depth);
        int last=f.hardnessLast;if(last>=0&&f.strata[last]==strata&&f.hardnessTheta[last]==t&&f.hardnessDepth[last]==d){f.hardnessHits++;return f.hardnessValue[last];}
        for(int i=0;i<f.hardnessCount;i++)if(f.strata[i]==strata&&f.hardnessTheta[i]==t&&f.hardnessDepth[i]==d){f.hardnessLast=i;f.hardnessHits++;return f.hardnessValue[i];}
        double value=strata.hardness(theta,depth);f.hardnessEvaluations++;
        int at=f.hardnessCount<16?f.hardnessCount++:f.hardnessCursor++&15;f.strata[at]=strata;f.hardnessTheta[at]=t;f.hardnessDepth[at]=d;f.hardnessValue[at]=value;f.hardnessLast=at;return value;
    }
    /** reliefAt clamps displacement to +/- .25*thickness; a column passes2*radius.
     * Thus its final radius cannot exceed radius+.5*radius. Outward rounding makes
     * the rejection conservative at every signed/tied boundary; nonfinite data falls back. */
    public static boolean mayReachColumn(double dx,double dr,double radius){
        if(!(radius>0)||!Double.isFinite(radius))return true;
        double distance=dx*dx+dr*dr,maximum=radius+radius*.5,bound=maximum*maximum;
        if(!Double.isFinite(distance)||!Double.isFinite(bound))return true;
        if(distance>=Math.nextUp(bound)){LOCAL.get().columnSkips++;return false;}return true;
    }
    private static Map<String,Long> stats(Frame f){return Map.of("noiseEvaluations",f.noiseEvaluations,"noiseHits",f.noiseHits,"hardnessEvaluations",f.hardnessEvaluations,"hardnessHits",f.hardnessHits,"columnSkips",f.columnSkips);}
    public static Map<String,Long> threadStats(){return stats(LOCAL.get());}
    public static Map<String,Long> liveThreadStats(){var out=new HashMap<String,Long>();for(var reference:FRAMES){var f=reference.get();if(f==null){FRAMES.remove(reference);continue;}stats(f).forEach((key,value)->out.merge(key,value,Long::sum));}return Map.copyOf(out);}
}
