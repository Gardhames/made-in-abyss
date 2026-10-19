package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.VoxelSkyLight;
import java.lang.management.ManagementFactory;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;

@GameTestHolder("near_scope")
@PrefixGameTestTemplate(false)
public final class T4NativeWindowGameTests {
    private static volatile long sink;
    @GameTest(template="empty",timeoutTicks=1200)
    public static void immutableNativeNeighborIndexKeepsSignedStatesUnknownAndAllocationBound(GameTestHelper h) {
        var b=new VoxelSkyLight.Bounds(-160,-224,-160,80,80,80);int cells=80*80*80;
        var sky=VoxelSkyLight.solveExpanded(b,new byte[cells],new byte[cells],cells);
        var block=VoxelSkyLight.solveBlockExpanded(b,new byte[cells],new byte[cells],cells);
        var cubes=new HashMap<CubeKey,int[]>();
        for(int y=-14;y<-2;y++)for(int z=-10;z<10;z++)for(int x=-10;x<10;x++) {
            int[] states=new int[4096];for(int i=0;i<4096;i++)states[i]=Math.floorMod(x*31+y*17+z*7+i,32768);
            cubes.put(new CubeKey(x,y,z),states);
        }
        var window=GeneratedNativeWindow.full(1,-128,-192,-128,128,sky,block,cubes);
        int[][] points=new int[65536][3];var random=new Random(0x7454);
        for(var p:points){p[0]=random.nextInt(320)-160;p[1]=random.nextInt(192)-224;p[2]=random.nextInt(320)-160;}
        for(var p:points)h.assertTrue(window.neighborState(p[0],p[1],p[2])==reference(cubes,p),"Exact signed full-state lookup");
        for(long[] p:new long[][]{{-161,-100,0},{0,-225,0},{0,-100,160},{Integer.MIN_VALUE,-100,0},{(long)Integer.MAX_VALUE+1,0,0}}) {
            boolean refused=false;try{window.neighborState(p[0],p[1],p[2]);}catch(IllegalStateException expected){refused=true;}
            h.assertTrue(refused,"Unknown/outside geometry must retain refusal");
        }
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        for(int i=0;i<8;i++)lookup(window,points);
        long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        for(int i=0;i<8;i++)lookup(window,points);
        long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        System.out.println("MIA_T4_NEIGHBOR allocated="+allocated+" queries="+(8L*points.length)+" sink="+sink);
        h.assertTrue(allocated<8L*points.length,"Immutable native neighbor lookup must allocate less than1 byte/query");
        for(int i=0;i<8;i++)referenceLoop(cubes,points);
        for(int i=0;i<7;i++) {
            long a=System.nanoTime();if((i&1)==0)lookup(window,points);else referenceLoop(cubes,points);long first=System.nanoTime()-a;
            a=System.nanoTime();if((i&1)==0)referenceLoop(cubes,points);else lookup(window,points);long second=System.nanoTime()-a;
            System.out.println("MIA_T4_NEIGHBOR_BENCH round="+i+" oldNanos="+((i&1)==0?second:first)+" newNanos="+((i&1)==0?first:second)+" queries="+points.length);
        }
        h.succeed();
    }
    private static int reference(Map<CubeKey,int[]> cubes,int[] p) {
        return cubes.get(new CubeKey(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16),Math.floorDiv(p[2],16)))[(p[1]&15)<<8|(p[2]&15)<<4|(p[0]&15)];
    }
    private static void lookup(GeneratedNativeWindow window,int[][] points) {
        long sum=0;for(var p:points)sum+=window.neighborState(p[0],p[1],p[2]);sink=sum;
    }
    private static void referenceLoop(Map<CubeKey,int[]> cubes,int[][] points) {
        long sum=0;for(var p:points)sum+=reference(cubes,p);sink=sum;
    }
}
