package dev.madeinabyss.kernel;

import dev.madeinabyss.kernel.light.SkyVisibility;
import dev.madeinabyss.kernel.terrain.living.LivingAbyssGeometrySky155B2;
import dev.madeinabyss.kernel.terrain.living.LivingAbyssSupportedFaces155B2.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LivingAbyssGeometrySky155B2Test {
    private static final String PHASE="support-geometry";
    private static final Owner OWNER=new Owner("floor-1",Role.HALL_FLOOR);
    private static Face face(int x,int airY,int z,String phase){return new Face(x,airY-1,z,Normal.UP,OWNER,48,phase);}
    private static LivingAbyssGeometrySky155B2 sky(SkyVisibility.VoxelOpacity geometry){
        return new LivingAbyssGeometrySky155B2(PHASE,32,10000,64,geometry);
    }

    @Test void exactDirectSkyKeepsOpenFloorAtFifteen(){
        assertEquals(15,sky((x,y,z)->0).illuminate(face(-17,-16,15,PHASE)).sky());
    }

    @Test void closedRoofHasNoSkyAndNoProvisionalAdmission(){
        assertEquals(0,sky((x,y,z)->y==8?15:0).illuminate(face(0,0,0,PHASE)).sky());
    }

    @Test void matchesFullThirtyTwoRayOracleAcrossThinAndPartialOccluders(){
        SkyVisibility.VoxelOpacity g=(x,y,z)->y==8&&x>=-2&&x<=2?15:y==10&&z%3==0?2:0;
        var light=sky(g);
        for(int x:new int[]{-17,-16,-1,0,1,15,16})for(int z:new int[]{-16,0,16}){
            int direct=15;
            for(int y=31;y>=0;y--){int o=g.opacity(x,y,z);if(direct!=15||o!=0)direct=Math.max(0,direct-Math.max(1,o));}
            int diffuse=SkyVisibility.diffuseLevel(SkyVisibility.visibleFraction(x,0,z,32,10000,g));
            assertEquals(Math.max(direct,diffuse),light.illuminate(face(x,0,z,PHASE)).sky(),x+","+z);
        }
    }

    @Test void manyPlantsOnSameFaceReuseOneSolveAndSameColumnForAnotherLevel(){
        var reads=new AtomicInteger();
        var light=sky((x,y,z)->{reads.incrementAndGet();return y==8&&x==0?15:0;});
        var f=face(0,0,0,PHASE);int first=light.illuminate(f).sky(),afterFirst=reads.get();
        assertTrue(first>0&&first<15);
        for(int i=0;i<1000;i++)assertEquals(first,light.illuminate(f).sky());
        assertEquals(afterFirst,reads.get());
        assertEquals(1,light.stats().raySolves());assertEquals(1000,light.stats().pointHits());
        light.illuminate(face(0,-1,0,PHASE));
        assertEquals(1,light.stats().columnBuilds());assertEquals(2,light.stats().raySolves());
    }

    @Test void geometryPhaseRejectsOldFaceAndCanopyHasIndependentCache(){
        var f=face(0,0,0,PHASE);var open=sky((x,y,z)->0);
        assertEquals(15,open.illuminate(f).sky());
        var canopy=new LivingAbyssGeometrySky155B2("with-canopy",32,10000,64,(x,y,z)->y==8?15:0);
        assertThrows(IllegalArgumentException.class,()->canopy.illuminate(f));
        var lit=canopy.illuminate(face(0,0,0,"with-canopy"));
        assertEquals(0,lit.sky());assertNotEquals(open.identity(),canopy.identity());
    }

    @Test void unknownAndFailedBudgetNeverBecomeCachedDarkness(){
        var unknown=new AtomicBoolean(true);
        var light=sky((x,y,z)->{if(unknown.get()&&y==20)throw new IllegalStateException("UNKNOWN");return 0;});
        var f=face(0,0,0,PHASE);
        assertThrows(IllegalStateException.class,()->light.illuminate(f));
        unknown.set(false);assertEquals(15,light.illuminate(f).sky());
        var limited=new LivingAbyssGeometrySky155B2(PHASE,32,2,64,(x,y,z)->0);
        assertThrows(IllegalStateException.class,()->limited.illuminate(f));
        assertThrows(IllegalArgumentException.class,()->sky((x,y,z)->16).illuminate(f));
    }

    @Test void boundedCacheEvictionChangesNoLightingAtNegativeCoordinates(){
        var light=new LivingAbyssGeometrySky155B2(PHASE,32,10000,2,(x,y,z)->y==8&&z<0?15:0);
        assertEquals(0,light.illuminate(face(-17,-1,-16,PHASE)).sky());
        assertEquals(15,light.illuminate(face(-16,-1,0,PHASE)).sky());
        assertEquals(15,light.illuminate(face(-1,-1,16,PHASE)).sky());
        assertEquals(0,light.illuminate(face(-17,-1,-16,PHASE)).sky());
        assertTrue(light.stats().cachedPoints()<=2);assertTrue(light.stats().cachedColumns()<=2);
    }
}
