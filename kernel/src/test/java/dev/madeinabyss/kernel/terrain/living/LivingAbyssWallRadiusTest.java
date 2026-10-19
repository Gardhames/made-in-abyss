package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.terrain.CompiledTerrainSampler;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class LivingAbyssWallRadiusTest {
    @FunctionalInterface private interface Radius { double at(int x,int y,int z); }

    static TerrainParameters parameters(long seed) {
        var p=LivingAbyssGeometryTest.parameters(seed);var c=p.cavity();
        return new TerrainParameters(seed,p.surface(),new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),
                c.declaredLowerBoundY(),c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),p.noise());
    }

    private static Radius fast(TerrainParameters p) {
        return new LivingAbyssWallRadius(p)::cavityRadius;
    }

    private static void same(Radius old,Radius fast,int x,int y,int z) {
        double expected=old.at(x,y,z),actual=fast.at(x,y,z);
        if(Double.doubleToRawLongBits(expected)!=Double.doubleToRawLongBits(actual))
            fail("x="+x+" y="+y+" z="+z+" expected="+Double.toHexString(expected)+" actual="+Double.toHexString(actual));
    }

    @Test void tenMillionRandomSignedPointsMatchPinnedSamplerBits() {
        long count=0;
        for(long seed:new long[]{0,1,20261003,-20261003,Long.MIN_VALUE+15}) {
            var p=parameters(seed);var old=new CompiledTerrainSampler(p,16384);var fast=fast(p);var random=new Random(seed^0x151515);
            for(int i=0;i<2_000_000;i++) {
                same(old::cavityRadius,fast,random.nextInt(3401)-1700,random.nextInt(1901)-1500,random.nextInt(3401)-1700);count++;
            }
        }
        assertEquals(10_000_000,count);System.out.println("MIA_V15_RADIUS randomRawBits="+count);
    }

    @Test void fullFastIntegerGridHasExactlyTheSameHypotBits() {
        for(int x=-1700;x<=1700;x++)for(int z=-1700;z<=1700;z++) {
            if(Double.doubleToRawLongBits(Math.hypot((double)x,(double)z))
                    !=Double.doubleToRawLongBits(Math.sqrt((double)x*x+(double)z*z)))fail("hypot x="+x+" z="+z);
        }
    }

    @Test void cubeScansLatticeBoundariesAndFullIntFallbackMatchBits() {
        var p=parameters(-15);var old=new CompiledTerrainSampler(p,16384);var fast=fast(p);var random=new Random(15);
        for(int cube=0;cube<128;cube++) {
            int sx=(random.nextInt(212)-106)*16,sy=(random.nextInt(118)-94)*16,sz=(random.nextInt(212)-106)*16;
            for(int dy=0;dy<16;dy++)for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++)same(old::cavityRadius,fast,sx+dx,sy+dy,sz+dz);
        }
        for(int x:new int[]{Integer.MIN_VALUE,-1701,-1700,-501,-500,-1,0,1,500,501,1700,1701,Integer.MAX_VALUE})
            for(int z:new int[]{Integer.MIN_VALUE,-1701,-140,-1,0,1,140,1701,Integer.MAX_VALUE})
                for(int y:new int[]{Integer.MIN_VALUE,-1501,-1500,-1401,-1400,-1399,-1,0,1,63,64,65,400,Integer.MAX_VALUE})same(old::cavityRadius,fast,x,y,z);
        for(int i=0;i<30_000;i++)same(old::cavityRadius,fast,random.nextInt(),random.nextInt(),random.nextInt());
        // This disproves a blanket sqrt replacement for arbitrary int coordinates.
        assertNotEquals(Double.doubleToRawLongBits(Math.hypot(-964884973.0,-1725765860.0)),
                Double.doubleToRawLongBits(Math.sqrt(964884973.0*964884973.0+1725765860.0*1725765860.0)));
    }

    @Test void concurrentColumnCollisionsCannotChangeAnyRadius() throws Exception {
        var p=parameters(20261003);var old=new CompiledTerrainSampler(p,16384);var fast=fast(p);
        var pool=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);var results=new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for(int worker=0;worker<4;worker++){int id=worker;results.add(pool.submit(()->{
                try{start.await();}catch(InterruptedException e){throw new AssertionError(e);}
                var random=new Random(100+id);
                for(int i=0;i<100_000;i++)same(old::cavityRadius,fast,random.nextInt(3401)-1700,random.nextInt(1901)-1500,random.nextInt(3401)-1700);
            }));}
            start.countDown();for(var result:results)result.get();
        } finally {pool.shutdownNow();}
    }

    @Test void nonstandardLatticesAndZeroProjectionPreconditionAreExact() {
        assertThrows(IllegalArgumentException.class,()->new LivingAbyssWallRadius(LivingAbyssGeometryTest.parameters(15)));
        for(double wavelength:new double[]{1,97.25,2000}) {
            var p=parameters(-15);var c=p.cavity();
            p=new TerrainParameters(p.seed(),p.surface(),new TerrainParameters.Cavity(500.25,c.firstLayerTopY(),c.declaredLowerBoundY(),c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),
                    new TerrainParameters.Noise(wavelength,20,60,0));
            var old=new CompiledTerrainSampler(p,16384);var fast=fast(p);var random=new Random(16);
            for(int i=0;i<10_000;i++)same(old::cavityRadius,fast,random.nextInt(3401)-1700,random.nextInt(6001)-3000,random.nextInt(3401)-1700);
        }
    }
}
