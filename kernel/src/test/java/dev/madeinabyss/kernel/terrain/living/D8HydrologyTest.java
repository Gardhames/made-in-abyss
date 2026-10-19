package dev.madeinabyss.kernel.terrain.living;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class D8HydrologyTest {
    @Test void knownSlopeRoutesIntoShaftConservesAreaAndNeverMutatesDem() {
        double[] dem=new double[81];for(int x=0;x<9;x++)for(int z=0;z<9;z++)dem[x*9+z]=30+Math.hypot(x-4,z-4)+x*.13;
        var before=dem.clone();var p=D8Hydrology.plan(dem,9,9,-16,-16,4,5,20);
        assertArrayEquals(before,dem);assertTrue(p.shaftArea()>0);assertFalse(p.outlets().isEmpty());
        assertEquals(81*16,p.shaftArea()+p.oceanArea()+p.boundaryArea(),1e-6);
        assertEquals(p.digest(),D8Hydrology.plan(dem,9,9,-16,-16,4,5,20).digest());
    }
    @Test void ambiguousAndAbsentInflowsExplainFallbackRatherThanSilentlySelecting() {
        double[] flat=new double[121];Arrays.fill(flat,30);
        var absent=D8Hydrology.plan(flat,11,11,100,100,4,5,20);
        assertEquals(20,absent.wetDegrees());assertEquals("NO_SHAFT_INFLOW",absent.reason());
        var ambiguous=D8Hydrology.selectWet(List.of(new D8Hydrology.Outlet(-10,0,100),new D8Hydrology.Outlet(10,0,100)),20);
        assertEquals("AMBIGUOUS_MAX_SHAFT_INFLOW",ambiguous.reason());assertEquals(20,ambiguous.degrees());
        assertEquals(270,D8Hydrology.selectWet(List.of(new D8Hydrology.Outlet(-10,0,200),new D8Hydrology.Outlet(10,0,100)),20).degrees());
    }
    @Test void pitsAndFlatTerraceResolveWithoutCyclesAndOceanDoesNotWinShaftRanking() {
        double[] pit=new double[121];Arrays.fill(pit,64);pit[5*11+5]=70;pit[8*11+5]=3;pit[10*11+5]=-1;
        var p=D8Hydrology.plan(pit,11,11,-20,-20,4,5,20);
        assertTrue(p.shaftArea()>0);assertTrue(p.filledCells()>0);assertTrue(p.oceanArea()>0);
        assertEquals(p.wetDegrees(),D8Hydrology.plan(pit,11,11,-20,-20,4,5,20).wetDegrees());
    }
    @Test void malformedAndNonfiniteDemRefused() {
        assertThrows(IllegalArgumentException.class,()->D8Hydrology.plan(new double[2],9,9,0,0,4,5,20));
        double[] nan=new double[9];nan[2]=Double.NaN;assertThrows(IllegalArgumentException.class,()->D8Hydrology.plan(nan,3,3,0,0,4,5,20));
    }
}
