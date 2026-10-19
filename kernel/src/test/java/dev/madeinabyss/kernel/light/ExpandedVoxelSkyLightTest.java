package dev.madeinabyss.kernel.light;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExpandedVoxelSkyLightTest {
    @Test void expandedBudgetIsOptInAndStillRefusesUnboundedAllocations() {
        var b=new VoxelSkyLight.Bounds(-1,-1,-1,1,1,1);
        assertThrows(IllegalArgumentException.class,()->VoxelSkyLight.solve(b,new byte[]{0},new byte[]{15},40_000_000));
        assertEquals(15,VoxelSkyLight.solveExpanded(b,new byte[]{0},new byte[]{15},40_000_000).value(-1,-1,-1).orElseThrow());
        assertThrows(IllegalArgumentException.class,()->VoxelSkyLight.solveExpanded(b,new byte[]{0},new byte[]{15},40_000_001));
        assertThrows(IllegalArgumentException.class,()->VoxelSkyLight.solveExpanded(new VoxelSkyLight.Bounds(0,0,0,320,400,320),null,null,40_000_000));
    }
    @Test void expandedAndOrdinarySolversAgreeIncludingUnknownEdges() {
        var b=new VoxelSkyLight.Bounds(-8,-8,-8,16,16,16);var opacity=new byte[4096];var emission=new byte[4096];emission[2048]=15;
        var a=VoxelSkyLight.solveBlock(b,opacity,emission,4096);var c=VoxelSkyLight.solveBlockExpanded(b,opacity,emission,40_000_000);
        for(int y=-8;y<8;y++)for(int z=-8;z<8;z++)for(int x=-8;x<8;x++)assertEquals(a.value(x,y,z),c.value(x,y,z));
    }
}
