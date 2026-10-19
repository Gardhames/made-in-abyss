package dev.madeinabyss.kernel.cube;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRenderDistanceTest {
    @Test void retainedGeometryRequiresCurrentAuthorityAndResourceIdentity() {
        assertTrue(NativeRenderDistance.retain(true,7,7,true));
        assertFalse(NativeRenderDistance.retain(false,7,7,true));
        assertFalse(NativeRenderDistance.retain(true,7,8,true));
        assertFalse(NativeRenderDistance.retain(true,7,7,false));
    }
    @Test void partialSignedCubesAreExcludedFromCommittedCoverage() {
        assertEquals(-1280,NativeRenderDistance.completeCubeMinimum(-1286));
        assertEquals(-16,NativeRenderDistance.completeCubeMinimum(-17));
        assertEquals(16,NativeRenderDistance.completeCubeMinimum(1));
        assertEquals(320,NativeRenderDistance.completeCubeMaximum(320));
        assertEquals(304,NativeRenderDistance.completeCubeMaximum(319));
    }
    @Test void vanillaOptionsProduceActualDistinctNativeRadii() {
        assertEquals(32, NativeRenderDistance.plan(2, -1, -552, -17, -20000, 320).radius());
        assertEquals(64, NativeRenderDistance.plan(4, -1, -552, -17, -20000, 320).radius());
        var p = NativeRenderDistance.plan(8, -1, -552, -17, -20000, 320);
        assertEquals(128,p.radius());assertEquals(-16,p.anchorX());assertEquals(-32,p.anchorZ());
        assertEquals(320,p.lightBounds().sizeX());assertEquals(39_321_600L,p.cells());
        assertFalse(p.capped());
    }
    @Test void upperBoundIsExplicitAndInputNeverSilentlyWraps() {
        var p=NativeRenderDistance.plan(32,0,-552,0,-20000,320);
        assertEquals(512,p.requestedRadius());assertEquals(128,p.radius());assertTrue(p.capped());
        assertThrows(IllegalArgumentException.class,()->NativeRenderDistance.plan(0,0,0,0,-20000,320));
        assertThrows(IllegalArgumentException.class,()->NativeRenderDistance.plan(2,Integer.MIN_VALUE,0,0,-20000,320));
        assertThrows(IllegalArgumentException.class,()->NativeRenderDistance.plan(2,Integer.MAX_VALUE,0,0,-20000,320));
    }
    @Test void lightHaloCoversEveryCameraPositionWithinSignedAnchorCell() {
        for(int coordinate:new int[]{-33,-17,-16,-1,0,15,16})for(int chunks:new int[]{2,4,8}) {
            var p=NativeRenderDistance.plan(chunks,coordinate,-552,coordinate,-20000,320);var b=p.lightBounds();
            assertTrue(b.minX()<=p.anchorX()-p.radius()-16);
            assertTrue((long)b.minX()+b.sizeX()-1>=p.anchorX()+15+p.radius()+16);
            assertTrue(b.minY()<=p.anchorY()-256-2-1-16);assertTrue((long)b.minY()+b.sizeY()-1>=p.anchorY()+15+64+2+1+16);
        }
    }
    @Test void bottomClipsOnlyDeclaredKnownGeometryAndBudgetRemainsBounded() {
        var p=NativeRenderDistance.plan(8,0,-19990,0,-20000,320);
        assertEquals(-20000,p.lightBounds().minY());assertTrue(p.cells()<39_321_600);
        assertThrows(IllegalArgumentException.class,()->NativeRenderDistance.plan(8,0,-20001,0,-20000,320));
        assertTrue(p.peakBytes()>p.cells()*16);assertTrue(p.peakBytes()<1_100_000_000L);
    }
}
