package dev.madeinabyss.kernel.light;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class QuantizedDiffuseSkyTest {
    private static int oracle(int x,int y,int z,int sky,SkyVisibility.VoxelOpacity opacity,boolean preview){
        return SkyVisibility.diffuseLevel(preview?SkyVisibility.visibleFractionPreview(x,y,z,sky,100000,opacity):SkyVisibility.visibleFraction(x,y,z,sky,100000,opacity));
    }
    @Test void denseWaterAndLeafDepthsMatchOriginalIntegerInBothModes(){
        for(boolean preview:new boolean[]{false,true})for(int opacity:new int[]{1,2,4,7,14})
            for(int depth:new int[]{1,2,3,5,8,13,21,34,55,89,144}){
                SkyVisibility.VoxelOpacity water=(x,y,z)->y<0?opacity:0;
                int expected=oracle(-17,-depth,-33,32,water,preview);
                assertEquals(expected,QuantizedDiffuseSky.level(-17,-depth,-33,32,100000,water,preview),"partial="+opacity+" depth="+depth+" preview="+preview);
            }
    }
    @Test void variedLeafCanopyAndWaterEdgesMatchDenseOracle(){
        for(boolean preview:new boolean[]{false,true})for(int seed=0;seed<40;seed++){
            final int recipe=seed;
            SkyVisibility.VoxelOpacity lookup=(x,y,z)->{
                if(y== -48)return 1;
                if(y<0 && Math.floorMod(x*13+z*17+recipe,11)<6)return 1;
                if(y>=0 && y<20 && Math.floorMod(x*19+y*7+z*23+recipe,29)<5)return 1+recipe%9;
                if(y==24 && Math.floorMod(x+z+recipe,13)==0)return 15;
                return 0;
            };
            assertEquals(oracle(-19,-48,-35,40,lookup,preview),QuantizedDiffuseSky.level(-19,-48,-35,40,100000,lookup,preview));
        }
    }
    @Test void intervalCannotCertifyAcrossOrAtRoundingBoundaries(){
        for(int k=0;k<14;k++){
            double boundary=(k+.5)/(60-3*(k+.5));
            assertEquals(-1,QuantizedDiffuseSky.certify(Math.nextDown(boundary),Math.nextUp(boundary)),"uncertain boundary "+k);
            for(double delta:new double[]{-1e-8,1e-8}){
                double fraction=boundary+delta;
                assertEquals(SkyVisibility.diffuseLevel(fraction),QuantizedDiffuseSky.certify(fraction,fraction));
            }
        }
        assertEquals(0,QuantizedDiffuseSky.certify(0,0));
    }
    @Test void thousandAndFourThousandMetreWaterCutsConsultedVoxels(){
        for(int depth:new int[]{1000,4000}){
            var original=new AtomicInteger();var fast=new AtomicInteger();
            SkyVisibility.VoxelOpacity originalLookup=(x,y,z)->{original.incrementAndGet();return y<0?1:0;};
            SkyVisibility.VoxelOpacity fastLookup=(x,y,z)->{fast.incrementAndGet();return y<0?1:0;};
            int expected=oracle(-17,-depth,-33,320,originalLookup,false);
            assertEquals(expected,QuantizedDiffuseSky.level(-17,-depth,-33,320,100000,fastLookup,false));
            assertEquals(0,expected);
            assertTrue(fast.get()<3000,"bounded attenuation prefix, depth="+depth+" reads="+fast);
            assertTrue(fast.get()*30L<original.get(),"at least30x fewer reads: old="+original+" new="+fast);
        }
    }
    @Test void consultedUnknownInvalidOpacityBudgetAndOverflowStillThrow(){
        assertThrows(IllegalStateException.class,()->QuantizedDiffuseSky.level(0,-4000,0,320,100000,(x,y,z)->{throw new IllegalStateException("UNKNOWN");},false));
        assertThrows(IllegalArgumentException.class,()->QuantizedDiffuseSky.level(0,0,0,20,1000,(x,y,z)->-1,false));
        assertThrows(IllegalArgumentException.class,()->QuantizedDiffuseSky.level(0,0,0,20,0,(x,y,z)->1,false));
        assertThrows(IllegalStateException.class,()->QuantizedDiffuseSky.level(0,0,0,20,1,(x,y,z)->1,false));
        assertThrows(IllegalStateException.class,()->QuantizedDiffuseSky.level(Integer.MAX_VALUE,0,0,20,1000,(x,y,z)->1,false));
        assertEquals(14,QuantizedDiffuseSky.level(-1,320,-1,320,1,(x,y,z)->{throw new AssertionError("already above sky");},false));
    }
    @Test void signedTranslationPreservesExactScalar(){
        SkyVisibility.VoxelOpacity first=(x,y,z)->y<16?1:0;
        SkyVisibility.VoxelOpacity moved=(x,y,z)->y< -19984?1:0;
        assertEquals(QuantizedDiffuseSky.level(2,-64,3,32,100000,first,false),QuantizedDiffuseSky.level(-4095,-20064,-30,-19968,100000,moved,false));
    }
}
