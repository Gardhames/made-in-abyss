package dev.madeinabyss.kernel.light;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detects skipped thin/partial occluders, changed DDA arithmetic or forgiven budgets. */
class SkyVisibilityCubeSkipTest {
    private static final class Certified implements SkyVisibility.VoxelOpacity {
        int reads, proofs;
        final boolean allAir;
        Certified(boolean allAir){this.allAir=allAir;}
        public boolean uniformAirCube(int x,int y,int z){proofs++;return allAir || y!=-4&&y!=-2;}
        @Override public int opacity(int x,int y,int z){reads++;return allAir?0:scalar(x,y,z);}
    }
    private static int scalar(int x,int y,int z){
        if(y==-63&&((x^z)&3)==0)return 7;
        if(y==-25&&(Math.floorMod(x,13)<3||Math.floorMod(z,17)<2))return 15;
        return 0;
    }
    @Test void certifiedAirAvoidsVoxelCallbacksWithoutChangingTheVisitedStepBudget(){
        var lookup=new Certified(true);
        assertEquals(1.0,SkyVisibility.visibleFraction(-17,-160,-33,64,10000,lookup));
        assertEquals(0,lookup.reads,"An exact whole-AIR proof must suppress per-voxel geometry calls");
        assertTrue(lookup.proofs>=32,"Each ray reaches cooperative cube proof callbacks");
        assertThrows(IllegalStateException.class,()->SkyVisibility.visibleFraction(-17,-160,-33,64,1,new Certified(true)));
        assertThrows(IllegalStateException.class,()->SkyVisibility.visibleFraction(Integer.MAX_VALUE,-2,0,10,100,new Certified(true)));
    }
    @Test void signedCubeCrossingsAndPartialTransmissionKeepEveryRawFractionBit(){
        for(int[] p:new int[][]{{-17,-128,-33},{-1,-65,-1},{15,-64,16},{0,-33,0},{-32,-96,31},{511,-160,-513}}){
            var lookup=new Certified(false);
            double expected=SkyVisibility.visibleFraction(p[0],p[1],p[2],64,10000,SkyVisibilityCubeSkipTest::scalar);
            double actual=SkyVisibility.visibleFraction(p[0],p[1],p[2],64,10000,lookup);
            assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(actual));
            double preview=SkyVisibility.visibleFractionPreview(p[0],p[1],p[2],64,10000,lookup);
            assertEquals(Double.doubleToRawLongBits(SkyVisibility.visibleFractionPreview(p[0],p[1],p[2],64,10000,SkyVisibilityCubeSkipTest::scalar)),Double.doubleToRawLongBits(preview));
            assertTrue(lookup.proofs>0);
        }
    }
    @Test void cubeProofCancellationAndRefusedUnknownRemainObservable(){
        var checks=new AtomicInteger();
        SkyVisibility.VoxelOpacity cancel=new SkyVisibility.VoxelOpacity(){
            public boolean uniformAirCube(int x,int y,int z){if(checks.incrementAndGet()==3)throw new CancellationException("retired cube authority");return true;}
            public int opacity(int x,int y,int z){return 0;}
        };
        assertThrows(CancellationException.class,()->SkyVisibility.visibleFraction(-17,-128,-33,64,10000,cancel));
        SkyVisibility.VoxelOpacity unknown=new SkyVisibility.VoxelOpacity(){
            public boolean uniformAirCube(int x,int y,int z){return y< -3;}
            public int opacity(int x,int y,int z){throw new IllegalStateException("UNKNOWN");}
        };
        assertThrows(IllegalStateException.class,()->SkyVisibility.visibleFraction(-17,-128,-33,64,10000,unknown));
    }
}
