package dev.madeinabyss.forge.worldgen;

import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.block.state.BlockState;

/** Opt-in diagnostic (-Dmia.sampleStats=true): where do per-voxel generator samples go? Samples 1 in 8 calls per thread,
 * classifies them by position and reports every 30 s. Never changes a sampled value. */
public final class GeneratorSampleStats {
    public static final boolean ENABLED=Boolean.getBoolean("mia.sampleStats");
    private static final String[] SOURCES={"derived","exactTile"};
    private static final String[] CATS={"core-air r<380","open-shell","band-inside<24","band-outside<2","rock-behind","beyond-wall","deep-floor","surface y>=0","exterior r>=960","other"};
    private static final LongAdder[][] COUNT=new LongAdder[SOURCES.length][CATS.length],NANOS=new LongAdder[SOURCES.length][CATS.length],AIR=new LongAdder[SOURCES.length][CATS.length];
    private static final ThreadLocal<int[]> TICK=ThreadLocal.withInitial(()->new int[1]);
    private static volatile boolean started;
    static{
        for(int s=0;s<SOURCES.length;s++)for(int c=0;c<CATS.length;c++){COUNT[s][c]=new LongAdder();NANOS[s][c]=new LongAdder();AIR[s][c]=new LongAdder();}
    }
    private GeneratorSampleStats(){}
    public static BlockState sample(ParameterizedTerrainChunkGenerator g,int x,int y,int z,int source){
        int[] tick=TICK.get();
        if((++tick[0]&7)!=0)return g.sampleBlock(x,y,z);
        start();
        long t0=System.nanoTime();var state=g.sampleBlock(x,y,z);long dt=System.nanoTime()-t0;
        int cat=classify(g,x,y,z);
        COUNT[source][cat].add(8);NANOS[source][cat].add(dt*8);if(state.isAir())AIR[source][cat].add(8);
        return state;
    }
    private static int classify(ParameterizedTerrainChunkGenerator g,int x,int y,int z){
        var living=g.livingAbyss();if(living==null)return 9;
        double r2=(double)x*x+(double)z*z;
        if(r2>=960.0*960)return 8;
        var d=living.recipeValue().domain();
        if(y>=0)return 7;
        if(y<d.wallBottomY())return 6;
        if(r2<380.0*380)return 0;
        double r=Math.sqrt(r2),outer=living.geometry().wallRadius(x,y,z);
        if(r>g.parameters().cavity().lowerRadius()+g.parameters().noise().wallAmplitude())return 5;
        if(r<outer-24)return 1;
        if(r<outer)return 2;
        if(r<outer+2)return 3;
        return 4;
    }
    private static synchronized void start(){
        if(started)return;started=true;
        var t=new Thread(()->{
            while(true){
                try{Thread.sleep(30_000);}catch(InterruptedException e){return;}
                System.out.println(report());
            }
        },"mia-sample-stats");
        t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);t.start();
    }
    public static String report(){
        var sb=new StringBuilder("MIA_SAMPLE_STATS\n");
        for(int s=0;s<SOURCES.length;s++){
            long total=0,totalNanos=0;for(int c=0;c<CATS.length;c++){total+=COUNT[s][c].sum();totalNanos+=NANOS[s][c].sum();}
            sb.append(String.format("  [%s] samples~%d, time~%.1fs%n",SOURCES[s],total,totalNanos/1e9));
            for(int c=0;c<CATS.length;c++){
                long n=COUNT[s][c].sum();if(n==0)continue;long ns=NANOS[s][c].sum(),air=AIR[s][c].sum();
                sb.append(String.format("    %-16s n=%5.1f%%  time=%5.1f%%  avg=%6.0fns  air=%5.1f%%%n",CATS[c],100.0*n/Math.max(1,total),100.0*ns/Math.max(1,totalNanos),(double)ns/n,100.0*air/n));
            }
        }
        return sb.toString();
    }
}
