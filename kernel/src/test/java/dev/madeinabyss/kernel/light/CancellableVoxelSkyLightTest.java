package dev.madeinabyss.kernel.light;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CancellableVoxelSkyLightTest {
    @Test void cancellationInsideValidationSeedingOrPropagationNeverReturnsPartialLight() {
        var b=new VoxelSkyLight.Bounds(-48,-368,464,80,80,80);int cells=512000;
        for(boolean block:new boolean[]{false,true})for(int stopAfter:new int[]{4,700,1600,2700}) {
            byte[] op=new byte[cells],seed=new byte[cells];java.util.Arrays.fill(seed,(byte)15);
            var visits=new AtomicInteger();Runnable checkpoint=()->{if(visits.incrementAndGet()==stopAfter)throw new CancellationException("latest target");};
            assertThrows(CancellationException.class,()->solve(b,op,seed,block,checkpoint));
        }
    }
    @Test void checkpointedSkyAndBlockEqualExistingExactSolverWithSignedPartialOpacity() {
        var b=new VoxelSkyLight.Bounds(-16,-32,-48,16,16,16);byte[] op=new byte[4096],seed=new byte[4096];
        for(int i=0;i<4096;i++){op[i]=(byte)(i%17==0?3:0);seed[i]=(byte)(i%131==0?15:0);}
        for(boolean block:new boolean[]{false,true}){
            var expected=block?VoxelSkyLight.solveBlockExpanded(b,op,seed,4096):VoxelSkyLight.solveExpanded(b,op,seed,4096);
            var checks=new AtomicInteger();var actual=solve(b,op,seed,block,checks::incrementAndGet);
            assertTrue(checks.get()>0);assertArrayEquals(expected.lowerSnapshot(),actual.lowerSnapshot());assertArrayEquals(expected.upperSnapshot(),actual.upperSnapshot());
        }
    }
    private static VoxelSkyLight.Result solve(VoxelSkyLight.Bounds b,byte[] opacity,byte[] seed,boolean block,Runnable check){
        return block?VoxelSkyLight.solveBlockExpanded(b,opacity,seed,opacity.length,check):VoxelSkyLight.solveExpanded(b,opacity,seed,opacity.length,check);
    }
}
