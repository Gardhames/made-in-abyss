package dev.madeinabyss.kernel.light;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeAmbientWorkTest {
    @Test void thinNearSurfaceHasIndependentSpatialOwnersInsteadOfOneHotYSlab() {
        var b=new VoxelSkyLight.Bounds(256,-992,480,80,80,80);var jobs=plan(b,4);
        assertEquals(9,jobs.size());
        assertTrue(jobs.stream().allMatch(j->j.minY()==-992&&j.sizeY()==80));
        assertEquals(9,jobs.stream().filter(j->-950>=j.minY()&&-950<j.minY()+j.sizeY()).count(),"A thin horizontal surface reaches independently claimable XZ jobs");
        cover(b,jobs);
    }
    @Test void clippedNegativeNearCellsHaveExactlyOneOwnerAndFullKeepsBroadYOrder() {
        for(var b:List.of(new VoxelSkyLight.Bounds(-41,-351,-17,65,49,51),new VoxelSkyLight.Bounds(Integer.MIN_VALUE,-80,-32,17,33,65),new VoxelSkyLight.Bounds(Integer.MAX_VALUE-80,-64,-80,80,80,80)))cover(b,plan(b,4));
        var full=new VoxelSkyLight.Bounds(136,-1072,360,320,184,320);var jobs=plan(full,4);
        var old=AlignedVoxelSlabs.plan(-1072,184,4);assertEquals(old.size(),jobs.size());
        for(int i=0;i<old.size();i++)assertEquals(new VoxelSkyLight.Bounds(136,old.get(i).minimum(),360,320,old.get(i).limit()-old.get(i).minimum(),320),jobs.get(i));
        assertThrows(IllegalArgumentException.class,()->plan(new VoxelSkyLight.Bounds(Integer.MAX_VALUE,0,0,2,1,1),4));
    }
    private static void cover(VoxelSkyLight.Bounds b,List<VoxelSkyLight.Bounds> jobs){
        for(long y=b.minY();y<(long)b.minY()+b.sizeY();y++)for(long z=b.minZ();z<(long)b.minZ()+b.sizeZ();z++)for(long x=b.minX();x<(long)b.minX()+b.sizeX();x++){
            int n=0;for(var j:jobs)if(x>=j.minX()&&x<(long)j.minX()+j.sizeX()&&y>=j.minY()&&y<(long)j.minY()+j.sizeY()&&z>=j.minZ()&&z<(long)j.minZ()+j.sizeZ())n++;
            assertEquals(1,n,"Every clipped signed seed cell has one writer");
        }
    }
    @SuppressWarnings("unchecked") private static List<VoxelSkyLight.Bounds> plan(VoxelSkyLight.Bounds b,int workers){
        try{return (List<VoxelSkyLight.Bounds>)Class.forName("dev.madeinabyss.kernel.light.NativeAmbientWork").getMethod("plan",VoxelSkyLight.Bounds.class,int.class).invoke(null,b,workers);}
        catch(ClassNotFoundException|NoSuchMethodException missing){throw new AssertionError("Missing spatial NEAR ambient ownership",missing);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
}
