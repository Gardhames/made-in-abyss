package dev.madeinabyss.kernel.light;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DenseOpacityVolumeTest {
    @Test void everySignedCellRetainsExactScalarWithUnknownOutside() {
        var bounds=new VoxelSkyLight.Bounds(-17,-20008,-33,24,16,32);var values=new byte[24*16*32];
        for(int i=0;i<values.length;i++)values[i]=(byte)(i&15);
        var volume=DenseOpacityVolume.takeOwnership(bounds,values,40000000);
        for(int y=0;y<16;y++)for(int z=0;z<32;z++)for(int x=0;x<24;x++)
            assertEquals((x+24*(z+32*y))&15,volume.opacity(-17+x,-20008+y,-33+z));
        assertEquals(-1,volume.opacity(-18,-20008,-33));assertEquals(-1,volume.opacity(7,-20008,-33));
        assertEquals(-1,volume.opacity(-17,-20009,-33));assertEquals(-1,volume.opacity(-17,-19992,-33));
        assertEquals(-1,volume.opacity(-17,-20008,-34));assertEquals(-1,volume.opacity(-17,-20008,-1));
    }
    @Test void extremeCoordinatesDoNotAliasOrOverflowIntoVolume() {
        var low=new VoxelSkyLight.Bounds(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,2,2,2);
        var high=new VoxelSkyLight.Bounds(Integer.MAX_VALUE-1,Integer.MAX_VALUE-1,Integer.MAX_VALUE-1,2,2,2);
        byte[] lo={0,1,2,3,4,5,6,7},hi={8,9,10,11,12,13,14,15};
        var a=DenseOpacityVolume.takeOwnership(low,lo,8);var b=DenseOpacityVolume.takeOwnership(high,hi,8);
        assertEquals(0,a.opacity(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE));
        assertEquals(7,a.opacity(Integer.MIN_VALUE+1,Integer.MIN_VALUE+1,Integer.MIN_VALUE+1));
        assertEquals(15,b.opacity(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
        assertEquals(-1,a.opacity(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
        assertEquals(-1,b.opacity(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE));
    }
    @Test void invalidOrIncompleteDensityIsNeverPublishedAsAir() {
        var bounds=new VoxelSkyLight.Bounds(-1,-1,-1,2,2,2);
        assertThrows(IllegalArgumentException.class,()->DenseOpacityVolume.takeOwnership(bounds,new byte[7],8));
        byte[] invalid=new byte[8];invalid[7]=-1;
        assertThrows(IllegalArgumentException.class,()->DenseOpacityVolume.takeOwnership(bounds,invalid,8));
        invalid[7]=16;
        assertThrows(IllegalArgumentException.class,()->DenseOpacityVolume.takeOwnership(bounds,invalid,8));
        assertThrows(IllegalArgumentException.class,()->DenseOpacityVolume.takeOwnership(new VoxelSkyLight.Bounds(Integer.MAX_VALUE,0,0,2,1,1),new byte[2],8));
    }
    @Test void randomizedThirtyTwoRayAndQuantizedOraclesStayExact() {
        var bounds=new VoxelSkyLight.Bounds(-40,-20040,-40,80,64,80);var values=new byte[80*64*80];
        SkyVisibility.VoxelOpacity oracle=(x,y,z)->y==-20016&&x<0?15:y==-20000?4:x==17&&z<0?8:0;
        int index=0;for(int y=-20040;y<-19976;y++)for(int z=-40;z<40;z++)for(int x=-40;x<40;x++)values[index++]=(byte)oracle.opacity(x,y,z);
        var volume=DenseOpacityVolume.takeOwnership(bounds,values,40000000);
        SkyVisibility.VoxelOpacity memo=(x,y,z)->{int known=volume.opacity(x,y,z);return known<0?oracle.opacity(x,y,z):known;};
        var random=new Random(554901);
        for(int i=0;i<50;i++){
            int x=random.nextInt(51)-25,z=random.nextInt(51)-25,y=-20032;
            double expected=SkyVisibility.visibleFraction(x,y,z,-19984,10000,oracle);
            assertEquals(expected,SkyVisibility.visibleFraction(x,y,z,-19984,10000,memo));
            assertEquals(SkyVisibility.diffuseLevel(expected),QuantizedDiffuseSky.level(x,y,z,-19984,10000,memo,false));
        }
    }
    @Test void atomicVolumeReplacementKeepsEachReadBoundToOneCompletedArray() throws Exception {
        var boundsA=new VoxelSkyLight.Bounds(-8,-16,-8,16,16,16);var boundsB=new VoxelSkyLight.Bounds(0,-16,0,16,16,16);
        byte[] first=new byte[4096],second=new byte[4096];
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            int i=x+16*(z+16*y);first[i]=(byte)((x-8+y-16+z-8)&15);second[i]=(byte)((x+y-16+z)&15);
        }
        var a=DenseOpacityVolume.takeOwnership(boundsA,first,4096);var b=DenseOpacityVolume.takeOwnership(boundsB,second,4096);
        var current=new java.util.concurrent.atomic.AtomicReference<>(a);var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int worker=0;worker<4;worker++)futures.add(pool.submit(()->{
                for(int i=0;i<20000;i++){
                    int x=(i%32)-8,y=-16+(i%16),z=((i/32)%32)-8;
                    var volume=current.get();int value=volume.opacity(x,y,z);
                    if(value>=0)assertEquals((x+y+z)&15,value);
                }
            }));
            for(int i=0;i<10000;i++)current.set((i&1)==0?a:b);
            for(var future:futures)future.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }finally {pool.shutdownNow();}
    }
}
