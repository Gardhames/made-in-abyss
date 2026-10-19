package dev.madeinabyss.kernel.light;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectSkyColumnSeedTest {
    @Test void negativeColumnKeepsTopSeedAlreadyAttenuatedAndDescendsThroughWaterLeavesRock() {
        // Global Y -67..-63, top itself is water whose attenuation is already in topSky.
        byte[] opacity={0,15,1,3,3},copy=opacity.clone(),sky=new byte[5];
        DirectSkyColumnSeed.fill(opacity,sky,0,1,5,12);
        assertArrayEquals(new byte[]{0,0,8,9,12},sky);assertArrayEquals(copy,opacity);
    }
    @Test void randomSignedSlicesMatchReferenceWithoutMutatingOtherDenseColumns() {
        var random=new Random(9427);
        for(int minY:new int[]{-1286,-945,-65,-17,0,63})for(int top:new int[]{0,1,7,14,15}) {
            int height=97,stride=5,start=3;byte[] opacity=new byte[height*stride],sky=new byte[opacity.length];Arrays.fill(sky,(byte)-1);
            for(int y=minY;y<minY+height;y++)opacity[start+(y-minY)*stride]=(byte)(random.nextInt(8)==0?random.nextInt(16):0);
            byte[] original=opacity.clone(),expected=sky.clone();int light=top;
            expected[start+(height-1)*stride]=(byte)light;
            for(int y=minY+height-2;y>=minY;y--){int o=opacity[start+(y-minY)*stride];if(!(light==15&&o==0))light=Math.max(0,light-Math.max(1,o));expected[start+(y-minY)*stride]=(byte)light;}
            DirectSkyColumnSeed.fill(opacity,sky,start,stride,height,top);
            assertArrayEquals(expected,sky,"signed Y="+minY+",seed="+top);assertArrayEquals(original,opacity);
        }
    }
    @Test void invalidOpacityBoundsAndAliasingCannotSupplySeeds() {
        byte[] opacity={0,16},sky=new byte[2];
        assertThrows(IllegalArgumentException.class,()->DirectSkyColumnSeed.fill(opacity,sky,0,1,2,15));
        assertThrows(IllegalArgumentException.class,()->DirectSkyColumnSeed.fill(new byte[2],sky,0,1,2,16));
        assertThrows(IllegalArgumentException.class,()->DirectSkyColumnSeed.fill(new byte[2],sky,1,2,2,15));
        assertThrows(IllegalArgumentException.class,()->DirectSkyColumnSeed.fill(sky,sky,0,1,2,15));
    }
}
