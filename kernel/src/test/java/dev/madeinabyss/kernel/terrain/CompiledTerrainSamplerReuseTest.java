package dev.madeinabyss.kernel.terrain;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent bitwise oracle and measured allocation contracts under bounded cache churn.
 * Uses the public evaluator API and actual thread allocations, without synthetic counters. */
class CompiledTerrainSamplerReuseTest {
    private static TerrainParameters parameters(long seed,double wavelength,double projection){return new TerrainParameters(seed,
        new TerrainParameters.Surface(0,-64,1000,950,80,64,650,800,100,160,3.25),
        new TerrainParameters.Cavity(500,64,-1286,650,180,170,projection),new TerrainParameters.Noise(wavelength,20,60,70));}
    private static void bits(double expected,double actual){assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(actual));}
    private static void compare(ParametricTerrainSampler oracle,CompiledTerrainSampler actual,int x,int y,int z,int order){
        // Vary the lazy entry point, then repeat a radius before filling other memo slots.
        switch(order&3){
            case 0->bits(oracle.surfaceHeight(x,z),actual.surfaceHeight(x,z));
            case 1->assertEquals(oracle.sample(x,y,z),actual.sample(x,y,z));
            case 2->bits(oracle.cavityRadius(x,y,z),actual.cavityRadius(x,y,z));
            default->bits(Math.hypot((double)x,(double)z),actual.radialDistance(x,z));
        }
        bits(oracle.cavityRadius(x,y,z),actual.cavityRadius(x,y,z));
        bits(oracle.cavityRadius(x,y,z),actual.cavityRadius(x,y,z));
        bits(oracle.surfaceHeight(x,z),actual.surfaceHeight(x,z));
        assertEquals(oracle.sample(x,y,z),actual.sample(x,y,z));
        for(int offset=1;offset<=5;offset++){
            long shifted=(long)y+offset;
            if(shifted<=Integer.MAX_VALUE)bits(oracle.cavityRadius(x,(int)shifted,z),actual.cavityRadius(x,(int)shifted,z));
        }
        bits(oracle.cavityRadius(x,y,z),actual.cavityRadius(x,y,z));
    }

    @Test void signedExtremeNoiseSeamsAndLazyEntryOrdersRemainBitwiseAfterChurn(){
        int[] xz={Integer.MIN_VALUE,Integer.MAX_VALUE,-8192,-651,-650,-501,-500,-141,-140,-139,-17,-16,-1,0,1,15,16,139,140,141,499,500,501,650,8192};
        int[] ys={Integer.MIN_VALUE,Integer.MAX_VALUE,-1287,-1286,-1285,-901,-816,-701,-432,-431,-430,-401,-400,-251,-250,-141,-140,-139,-1,0,63,64,65,140,320};
        int sequence=0;
        for(var p:List.of(parameters(20261003,140,280),parameters(-18372513,139.25,280),parameters(827,1,0))){
            var oracle=new ParametricTerrainSampler(p);var actual=new CompiledTerrainSampler(p,16);
            for(int x:xz)for(int z:xz)for(int y:ys)compare(oracle,actual,x,y,z,sequence++);
            assertEquals(16,actual.cachedColumnCount(),"Bounded slots actually fill during signed churn");
        }
    }

    @Test void mixedRadiusMemoSurfaceAndMaterialQueriesKeepIndependentOracleAfterReplacement(){
        var p=parameters(20261003,140,280);var oracle=new ParametricTerrainSampler(p);var actual=new CompiledTerrainSampler(p,16);var random=new Random(164319);
        for(int i=0;i<16000;i++){
            int x=random.nextInt(2201)-1100,z=random.nextInt(2201)-1100,y=random.nextInt(1608)-1287;
            compare(oracle,actual,x,y,z,i);
            if((i&31)==0){
                int surface=(int)Math.floor(oracle.surfaceHeight(x,z));
                for(int offset=-5;offset<=2;offset++)compare(oracle,actual,x,surface+offset,z,i+offset);
            }
        }
        assertEquals(16,actual.cachedColumnCount());
        assertTrue(actual.cachedRadiusHits()>0,"Repeated heights really populate resident radius memo counters");
        for(int i=0;i<4096;i++){
            int x=2000+17*i,z=-3000-19*i;
            bits(Math.hypot((double)x,(double)z),actual.radialDistance(x,z));
        }
        assertEquals(16,actual.cachedColumnCount());
        assertEquals(0,actual.cachedRadiusHits(),"Replacing all resident columns drops historical radius hits; no old column metadata survives");
    }

    @Test void filledSlotsStopAllocatingColumnsAndNoiseArraysDuringSustainedChurn(){
        var bean=allocationBean();var actual=new CompiledTerrainSampler(parameters(20261003,140,280),16);
        int warm=drive(actual,24000,0);assertEquals(16,actual.cachedColumnCount());
        long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        int checksum=drive(actual,50000,24000);
        long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        assertTrue(allocated>=0&&allocated<=512*1024,"After all16 slots and both channels warm, churn allocation is bounded, actualbytes="+allocated);
        assertEquals(16,actual.cachedColumnCount());
        // Retain observable scalar output without adding allocations to the measured loop.
        assertNotEquals(0,warm|checksum);System.out.println("MIA_COMPILED_CHURN allocatedBytes="+allocated+" samples=50000 residentColumns="+actual.cachedColumnCount());
    }

    @Test void fourWorkersKeepTheirOwnReusedSlotsOracleValuesAndAllocationBounds()throws Exception {
        var p=parameters(20261003,140,280);var shared=new CompiledTerrainSampler(p,16);var bean=allocationBean();var pool=Executors.newFixedThreadPool(4);var ready=new CountDownLatch(4);var start=new CountDownLatch(1);
        var futures=new ArrayList<Future<WorkerResult>>();
        try{
            for(int thread=0;thread<4;thread++){
                final int worker=thread;
                futures.add(pool.submit(()->{
                    var oracle=new ParametricTerrainSampler(p);var random=new Random(4816+worker);
                    ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("Workers did not overlap");
                    for(int i=0;i<2500;i++)compare(oracle,shared,random.nextInt(2201)-1100,random.nextInt(1608)-1287,random.nextInt(2201)-1100,i);
                    drive(shared,16000,worker*100000);int resident=shared.cachedColumnCount();
                    long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
                    int checksum=drive(shared,24000,worker*100000+16000);
                    long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
                    return new WorkerResult(resident,shared.cachedColumnCount(),allocated,checksum);
                }));
            }
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            for(var future:futures){
                var result=future.get(60,TimeUnit.SECONDS);
                assertEquals(16,result.before());assertEquals(16,result.after());
                assertTrue(result.bytes()>=0&&result.bytes()<=512*1024,"Worker allocation stays bounded after its own cache warms, actualbytes="+result.bytes());
                assertNotEquals(0,result.checksum());
            }
            assertEquals(0,shared.cachedColumnCount(),"Main thread did not inherit another worker's mutable columns");
        }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    private record WorkerResult(int before,int after,long bytes,int checksum){}
    private static int drive(CompiledTerrainSampler sampler,int count,int offset){
        int checksum=1;
        for(int i=0;i<count;i++){
            int q=i+offset,x=2000+17*q,z=-3000-19*q,y=-431-(i&127);
            var value=sampler.sample(x,y,z);
            checksum=31*checksum+(value instanceof TerrainSample.Known known?known.material().ordinal()+1:0);
        }
        return checksum;
    }
    private static com.sun.management.ThreadMXBean allocationBean(){
        var base=ManagementFactory.getThreadMXBean();
        assertTrue(base instanceof com.sun.management.ThreadMXBean,"Allocation contract requires the actual HotSpot per-thread byte counter");
        var bean=(com.sun.management.ThreadMXBean)base;
        assertTrue(bean.isThreadAllocatedMemorySupported(),"No performance PASS may be claimed when allocation measurement is unavailable");
        if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }
}
