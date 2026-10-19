package dev.madeinabyss.kernel.volume;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class VoxelBrickTest {
    @Test void opacityZeroDoesNotMeanAirOrUnknown() {
        var brick=VoxelBrick.uniform(37,0);
        assertEquals(37,brick.stateAt(4095));
        assertEquals(0,brick.opacityAt(0));
        assertTrue(brick.allKnown());
    }
    @Test void stateAndOpacityCompressIndependently() {
        int[] states=new int[4096],opacity=new int[4096];
        for(int i=0;i<4096;i++){states[i]=i%3;opacity[i]=15;}
        var brick=VoxelBrick.copyOf(states,opacity);
        assertTrue(brick.uniformOpacity());assertFalse(brick.uniformState());
        states[17]=999;opacity[17]=0;
        for(int i=0;i<4096;i++){assertEquals(i%3,brick.stateAt(i));assertEquals(15,brick.opacityAt(i));}
        assertTrue(brick.payloadBytes()<4096*4);
    }
    @Test void partialUnknownSurvivesAtEveryNativeCubeBoundary() {
        int[] states=new int[4096],opacity=new int[4096];Arrays.fill(states,5);Arrays.fill(opacity,1);
        for(int i:new int[]{0,15,16,255,256,4080,4095}){states[i]=-1;opacity[i]=-1;}
        var brick=VoxelBrick.copyOf(states,opacity);assertFalse(brick.allKnown());
        for(int i=0;i<4096;i++){assertEquals(states[i],brick.stateAt(i));assertEquals(opacity[i],brick.opacityAt(i));}
        var unknown=VoxelBrick.uniform(-1,-1);assertFalse(unknown.allKnown());assertEquals(-1,unknown.opacityAt(0));
    }
    @Test void invalidOrMismatchedChannelsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->VoxelBrick.uniform(0,-1));
        assertThrows(IllegalArgumentException.class,()->VoxelBrick.uniform(0,16));
        assertThrows(IllegalArgumentException.class,()->VoxelBrick.copyOf(new int[16],new int[16]));
        assertThrows(IndexOutOfBoundsException.class,()->VoxelBrick.uniform(0,0).stateAt(4096));
    }
    @Test void opacityMayBeCompleteWhileStateIsLazy()throws Exception {
        var solid=VoxelBrick.uniform(-1,15);assertTrue(solid.allKnown());assertEquals(15,solid.opacityAt(17));assertEquals(-1,solid.stateAt(17));
        int[] states=new int[4096],opacity=new int[4096];Arrays.fill(states,-1);
        for(int i=0;i<4096;i++)opacity[i]=i%16;
        var mixed=VoxelBrick.copyOf(states,opacity);assertTrue(mixed.allKnown());assertFalse(mixed.uniformOpacity());
        var opacityOnly=VoxelBrick.opacityOnly(opacity);for(int i=0;i<4096;i++){assertEquals(mixed.opacityAt(i),opacityOnly.opacityAt(i));assertEquals(-1,opacityOnly.stateAt(i));}
        var bytes=new java.io.ByteArrayOutputStream();mixed.write(new java.io.DataOutputStream(bytes));
        var restored=VoxelBrick.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())),1);var gpu=restored.gpuWords();
        for(int i=0;i<4096;i++){assertEquals(-1,restored.stateAt(i));assertEquals(i%16,restored.opacityAt(i));assertEquals(i%16,VoxelBrick.gpuOpacityAt(gpu,i));}
    }
    @Test void diskAndGpuWordsRoundTripEveryBitWidth()throws Exception {
        for(int count:new int[]{1,2,3,5,17,65,257,2049,4096}) {
            int[] s=new int[4096],o=new int[4096];for(int i=0;i<4096;i++){s[i]=i%count;o[i]=s[i]%16;if(i%97==0){s[i]=-1;o[i]=-1;}}
            var brick=VoxelBrick.copyOf(s,o);var bytes=new java.io.ByteArrayOutputStream();brick.write(new java.io.DataOutputStream(bytes));
            var restored=VoxelBrick.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())),4096);
            int[] gpu=restored.gpuWords();assertEquals(restored.gpuBytes(),gpu.length*4L);
            for(int i=0;i<4096;i++){assertEquals(s[i],restored.stateAt(i));assertEquals(o[i],restored.opacityAt(i));assertEquals(s[i],VoxelBrick.gpuStateAt(gpu,i));assertEquals(o[i],VoxelBrick.gpuOpacityAt(gpu,i));}
        }
    }
}
