package dev.madeinabyss.kernel.lod;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ShadowBoundaryPlanTest {
    private TerrainLodColumns.Box box(long x,TerrainMaterial material){return new TerrainLodColumns.Box(x,-40,-17,x+1,-39,-16,material);}
    @Test void wholeBoundaryAcceptsMoreThanOldBoxLimitAndPartitionsEveryFaceOnce() {
        var boxes=new ArrayList<TerrainLodColumns.Box>();var light=new HashMap<TerrainLodColumns.Box,Integer>();
        for(int i=0;i<70000;i++){var b=box(i-35000,TerrainMaterial.ROCK);boxes.add(b);light.put(b,i%16);}
        var plan=ShadowBoundaryPlan.prepare(boxes,light);assertEquals(70000,plan.boxes().size());assertTrue(plan.surface().faces().size()>0);
        int face=0;for(int chunk=0;chunk<plan.chunkCount();chunk++){
            assertEquals(face,plan.chunkStart(chunk));assertTrue(plan.chunkEnd(chunk)-face<=ShadowBoundaryPlan.CHUNK_FACES);face=plan.chunkEnd(chunk);
        }
        assertEquals(plan.surface().faces().size(),face);assertTrue(plan.maximumVertexBytes()<=ShadowBoundaryPlan.MAX_GPU_BYTES);
        for(var f:plan.surface().faces())assertEquals(Math.floorMod((int)plan.boxes().get(f.owner()).minX()+35000,16),plan.sky(f.owner()));
    }
    @Test void waterIsExcludedAndSignedOwnerLightingAndInputOwnershipSurvive() {
        var rock=box(-17,TerrainMaterial.ROCK);var grass=box(-16,TerrainMaterial.GRASS);var water=box(-15,TerrainMaterial.WATER);
        var boxes=new ArrayList<>(List.of(rock,water,grass));var light=new HashMap<TerrainLodColumns.Box,Integer>();light.put(rock,3);light.put(grass,11);
        var plan=ShadowBoundaryPlan.prepare(boxes,light);boxes.clear();light.clear();
        assertEquals(List.of(rock,grass),plan.boxes());assertEquals(3,plan.sky(0));assertEquals(11,plan.sky(1));
        assertTrue(plan.surface().hiddenFaces()>=2);assertThrows(UnsupportedOperationException.class,()->plan.boxes().clear());
    }
    @Test void missingInvalidLightAndInputBudgetsRefuseWholeCandidate() {
        var b=box(-1,TerrainMaterial.ROCK);
        assertThrows(IllegalArgumentException.class,()->ShadowBoundaryPlan.prepare(List.of(b),Map.of()));
        assertThrows(IllegalArgumentException.class,()->ShadowBoundaryPlan.prepare(List.of(b),Map.of(b,16)));
        assertThrows(IllegalArgumentException.class,()->ShadowBoundaryPlan.prepare(Collections.nCopies(ShadowBoundaryPlan.MAX_INPUT_BOXES+1,b),Map.of(b,1)));
        var empty=ShadowBoundaryPlan.prepare(List.of(),Map.of());assertEquals(0,empty.chunkCount());assertEquals(0,empty.maximumVertexBytes());
    }
    @Test void staticChunkBoundsContainEverySignedFaceVertexAndStayLocalToTheirFaces() {
        var boxes=new ArrayList<TerrainLodColumns.Box>();var light=new HashMap<TerrainLodColumns.Box,Integer>();
        for(int i=0;i<1800;i++){long x=(i<900?-2000000000L:2000000000L)+(i%900)*2;
            var b=new TerrainLodColumns.Box(x,-20000,-17,x+1,-19999,-16,TerrainMaterial.ROCK);boxes.add(b);light.put(b,7);}
        var plan=ShadowBoundaryPlan.prepare(boxes,light);assertEquals(plan.chunkCount(),plan.chunkBounds().size());
        for(int c=0;c<plan.chunkCount();c++) {
            var bounds=plan.chunkBounds().get(c);
            long minX=Long.MAX_VALUE,maxX=Long.MIN_VALUE;
            for(int f=plan.chunkStart(c);f<plan.chunkEnd(c);f++) {
                var face=plan.surface().faces().get(f);
                for(long u:new long[]{face.u0(),face.u1()})for(long v:new long[]{face.v0(),face.v1()}) {
                    long x=face.axis()==0?face.plane():u;
                    long y=face.axis()==1?face.plane():v;
                    long z=face.axis()==0?u:face.axis()==1?v:face.plane();
                    assertTrue(x>=bounds.minX()&&x<=bounds.maxX());assertTrue(y>=bounds.minY()&&y<=bounds.maxY());
                    assertTrue(z>=bounds.minZ()&&z<=bounds.maxZ());minX=Math.min(minX,x);maxX=Math.max(maxX,x);
                }
            }
            assertEquals(minX,bounds.minX());assertEquals(maxX,bounds.maxX());
        }
        assertThrows(UnsupportedOperationException.class,()->plan.chunkBounds().clear());
        assertTrue(ShadowBoundaryPlan.prepare(List.of(),Map.of()).chunkBounds().isEmpty());
    }
}
