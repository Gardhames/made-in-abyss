package dev.madeinabyss.kernel.terrain.habitat;

import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandscapeOverlayTest {
    private static final int Y=-20000;
    private static TrailNetwork.SampleSource flat(){return (x,y,z)->new TerrainSample.Known(y>Y?TerrainMaterial.AIR:y==Y?TerrainMaterial.GRASS:TerrainMaterial.ROCK);}
    private static LandscapeOverlay.Column column(int x,int z,int delta,Integer water){return new LandscapeOverlay.Column(x,z,Y,Y+delta,water);}
    private static LandscapeOverlay.Patch patch(List<LandscapeOverlay.Column> columns,List<HabitatOverlay.Placement> decorations){return new LandscapeOverlay.Patch("offline",-8,-8,16,16,Y,columns,decorations);}
    private static HabitatOverlay.Placement decoration(int x,int y,int z,HabitatBlock b){return new HabitatOverlay.Placement(x,y,z,b,"offline/tree");}
    private static LandscapeOverlay plan(LandscapeOverlay.Patch p){return LandscapeOverlay.plan(flat(),List.of(p),(x,y,z)->false);}

    @Test void raisingAndCuttingSignedColumnsKeepsSoilBackingAndExplicitAir(){
        var p=plan(patch(List.of(column(-1,-1,2,null),column(0,0,-2,null)),List.of()));
        assertEquals(HabitatBlock.GRASS,p.sample(-1,Y+2,-1).orElseThrow());
        assertEquals(HabitatBlock.SOIL,p.sample(-1,Y,-1).orElseThrow());
        assertEquals(HabitatBlock.SOIL,p.sample(0,Y-5,0).orElseThrow());
        assertEquals(HabitatBlock.ROCK,p.sample(0,Y-6,0).orElseThrow());
        assertEquals(HabitatBlock.AIR,p.sample(0,Y,0).orElseThrow());
        assertTrue(p.sample(8,Y,8).isEmpty());
        assertThrows(UnsupportedOperationException.class,()->p.placements().clear());
    }
    @Test void containedPondUsesFinalBedAndExistingBanks(){
        var p=plan(patch(List.of(column(0,0,-2,Y-1)),List.of()));
        assertEquals(HabitatBlock.WATER,p.sample(0,Y-1,0).orElseThrow());
        assertEquals(HabitatBlock.AIR,p.sample(0,Y,0).orElseThrow());
    }
    @Test void uncontainedRaisedWaterRejectsWholeRecipe(){assertThrows(IllegalArgumentException.class,()->plan(patch(List.of(column(0,0,0,Y+1)),List.of())));}
    @Test void unknownBackingAndUnknownHeadroomNeverBecomeAir(){
        for(int unknownY:new int[]{Y-7,Y+16}){
            TrailNetwork.SampleSource base=(x,y,z)->y==unknownY?new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER):flat().sample(x,y,z);
            assertThrows(IllegalArgumentException.class,()->LandscapeOverlay.plan(base,List.of(patch(List.of(column(0,0,0,null)),List.of())),(x,y,z)->false));
        }
    }
    @Test void protectedFootprintRejectsColumnEvenIfProtectedCellWouldRemainAir(){assertThrows(IllegalArgumentException.class,()->LandscapeOverlay.plan(flat(),List.of(patch(List.of(column(0,0,0,null)),List.of())),(x,y,z)->x==0&&z==0&&y==Y+8));}
    @Test void floatingCanopyAndUnsupportedFlowerAreRejected(){
        for(var d:List.of(decoration(0,Y+3,0,HabitatBlock.LEAVES),decoration(0,Y+2,0,HabitatBlock.FLOWER)))
            assertThrows(IllegalArgumentException.class,()->plan(patch(List.of(column(0,0,0,null)),List.of(d))));
    }
    @Test void connectedBranchesCanCrossSignedChunkSeam(){
        var p=plan(patch(List.of(column(-1,0,0,null),column(0,0,0,null)),List.of(decoration(-1,Y+1,0,HabitatBlock.LOG),decoration(-1,Y+2,0,HabitatBlock.LOG),decoration(0,Y+2,0,HabitatBlock.LEAVES))));
        assertEquals(HabitatBlock.LEAVES,p.sample(0,Y+2,0).orElseThrow());
    }
    @Test void duplicateColumnsAndDecorationOverwriteRejectWholeRecipe(){
        assertThrows(IllegalArgumentException.class,()->plan(patch(List.of(column(0,0,0,null),column(0,0,1,null)),List.of())));
        assertThrows(IllegalArgumentException.class,()->plan(patch(List.of(column(0,0,0,null)),List.of(decoration(0,Y,0,HabitatBlock.LOG)))));
    }
    @Test void boundsFloorMismatchAndOverflowAreRejected(){
        assertThrows(IllegalArgumentException.class,()->plan(patch(List.of(column(8,0,0,null)),List.of())));
        assertThrows(IllegalArgumentException.class,()->plan(patch(List.of(column(0,0,5,null)),List.of())));
        assertThrows(IllegalArgumentException.class,()->plan(new LandscapeOverlay.Patch("edge",Integer.MAX_VALUE,0,16,16,Y,List.of(),List.of())));
        TrailNetwork.SampleSource shifted=(x,y,z)->new TerrainSample.Known(y>Y+1?TerrainMaterial.AIR:TerrainMaterial.GRASS);
        assertThrows(IllegalArgumentException.class,()->LandscapeOverlay.plan(shifted,List.of(patch(List.of(column(0,0,0,null)),List.of())),(x,y,z)->false));
    }
    @Test void reorderedInputHasSameImmutableOrderedWrites(){
        var a=column(-1,0,-1,null);var b=column(0,0,1,null);
        assertEquals(plan(patch(List.of(a,b),List.of())).placements(),plan(patch(List.of(b,a),List.of())).placements());
    }
    @Test void plantsCanOccupyExplicitAirCutAfterLoweringSurface(){
        var p=plan(patch(List.of(column(0,0,-2,null)),List.of(decoration(0,Y-1,0,HabitatBlock.FLOWER))));
        assertEquals(HabitatBlock.FLOWER,p.sample(0,Y-1,0).orElseThrow());
    }
    @Test void maximumCutDoesNotFillBelowCertifiedBacking(){
        TrailNetwork.SampleSource hollow=(x,y,z)->y==Y-8?new TerrainSample.Known(TerrainMaterial.AIR):flat().sample(x,y,z);
        var p=LandscapeOverlay.plan(hollow,List.of(patch(List.of(column(0,0,-4,null)),List.of())),(x,y,z)->false);
        assertEquals(HabitatBlock.GRASS,p.sample(0,Y-4,0).orElseThrow());
        assertEquals(HabitatBlock.SOIL,p.sample(0,Y-7,0).orElseThrow());
        assertTrue(p.sample(0,Y-8,0).isEmpty());
    }
}

