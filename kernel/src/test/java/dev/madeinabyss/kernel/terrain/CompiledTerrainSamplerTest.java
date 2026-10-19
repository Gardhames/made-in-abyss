package dev.madeinabyss.kernel.terrain;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompiledTerrainSamplerTest {
    private static TerrainParameters parameters(long seed,double wavelength){return new TerrainParameters(seed,
        new TerrainParameters.Surface(0,-64,1000,950,80,64,650,800,100,160,3.25),
        new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),new TerrainParameters.Noise(wavelength,20,60,70));}
    private static void bits(double expected,double actual){assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(actual));}
    private static void compare(ParametricTerrainSampler old,CompiledTerrainSampler fast,int x,int y,int z){
        bits(Math.hypot((double)x,(double)z),fast.radialDistance(x,z));
        bits(old.surfaceHeight(x,z),fast.surfaceHeight(x,z));bits(old.cavityRadius(x,y,z),fast.cavityRadius(x,y,z));
        assertEquals(old.sample(x,y,z),fast.sample(x,y,z));
    }
    @Test void bitwiseOracleAcrossSignedWorldAndNumericalBoundaries(){
        int[] xs={Integer.MIN_VALUE,Integer.MAX_VALUE,-1024,-650,-500,-140,-16,-1,0,1,15,16,139,140,500,650,1024};
        int[] ys={Integer.MIN_VALUE,Integer.MAX_VALUE,-1287,-1286,-1285,-1000,-816,-657,-140,-1,0,63,64,65,140,320};
        for(long seed:new long[]{0,20261003,-18372513}){
            var p=parameters(seed,139.25);var old=new ParametricTerrainSampler(p);var fast=new CompiledTerrainSampler(p,256);
            for(int x:xs)for(int y:ys)for(int z:xs)compare(old,fast,x,y,z);
        }
    }
    @Test void randomPointsAndExactSurfaceTransitionsMatchAfterCacheReplacement(){
        var p=parameters(827,140);var old=new ParametricTerrainSampler(p);var fast=new CompiledTerrainSampler(p,16);var rng=new Random(763);
        for(int i=0;i<30000;i++){
            int x=rng.nextInt(2201)-1100,z=rng.nextInt(2201)-1100,y=rng.nextInt(1900)-1400;
            compare(old,fast,x,y,z);
            if(i%30==0){int surface=(int)Math.floor(old.surfaceHeight(x,z));for(int d=-5;d<=2;d++)compare(old,fast,x,surface+d,z);}
        }
        assertTrue(fast.cachedColumnCount()<=16);
    }
    @Test void verticalColumnsAndSoilHorizonProbesPreserveArithmeticOrder(){
        var p=parameters(20261003,140);var old=new ParametricTerrainSampler(p);var fast=new CompiledTerrainSampler(p);
        for(int[] q:new int[][]{{0,0},{-525,-33},{295,-440},{-1,650},{550,0},{-500,500},{750,-30}})
            for(int y=-1287;y<321;y++)compare(old,fast,q[0],y,q[1]);
        assertTrue(fast.cachedColumnCount()>0);
    }
    @Test void cacheCapacityIsExplicitAndEachWorkerOwnsItsColumns()throws Exception {
        var p=parameters(20261003,140);assertThrows(IllegalArgumentException.class,()->new CompiledTerrainSampler(p,3));
        var fast=new CompiledTerrainSampler(p,16);var pool=Executors.newFixedThreadPool(2);
        try {var start=new CountDownLatch(2);var values=new ArrayList<Future<Integer>>();
            for(int n=0;n<2;n++){int x=n+1;values.add(pool.submit(()->{start.countDown();start.await();for(int y=-1200;y<0;y++)fast.cavityRadius(x,y,500);return fast.cachedColumnCount();}));}
            for(var value:values)assertEquals(1,value.get());assertEquals(0,fast.cachedColumnCount());
        }finally{pool.shutdownNow();}
    }
    @Test void fourHeightMemoHitsAndEvictsWithoutReusingAnotherSignedHeight(){
        var p=parameters(20261003,140);var old=new ParametricTerrainSampler(p);var fast=new CompiledTerrainSampler(p,16);
        bits(old.cavityRadius(295,-663,-440),fast.cavityRadius(295,-663,-440));
        bits(old.cavityRadius(295,-663,-440),fast.cavityRadius(295,-663,-440));assertEquals(1,fast.cachedRadiusHits());
        for(int y=-662;y<=-659;y++)bits(old.cavityRadius(295,y,-440),fast.cavityRadius(295,y,-440));
        long hits=fast.cachedRadiusHits();bits(old.cavityRadius(295,-663,-440),fast.cavityRadius(295,-663,-440));assertEquals(hits,fast.cachedRadiusHits());
        bits(old.cavityRadius(295,-659,-440),fast.cavityRadius(295,-659,-440));assertEquals(hits+1,fast.cachedRadiusHits());
        for(int y:new int[]{Integer.MIN_VALUE,-1287,-1286,-1,0,63,64,Integer.MAX_VALUE})compare(old,fast,295,y,-440);
    }
    public static void main(String[] args)throws Exception{var t=new CompiledTerrainSamplerTest();t.fourHeightMemoHitsAndEvictsWithoutReusingAnotherSignedHeight();t.bitwiseOracleAcrossSignedWorldAndNumericalBoundaries();t.randomPointsAndExactSurfaceTransitionsMatchAfterCacheReplacement();t.verticalColumnsAndSoilHorizonProbesPreserveArithmeticOrder();t.cacheCapacityIsExplicitAndEachWorkerOwnsItsColumns();System.out.println("5 CompiledTerrainSampler oracle/cache tests passed");}
}
