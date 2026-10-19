package dev.madeinabyss.kernel;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.living.LivingAbyssSupportedFaces155B2;
import dev.madeinabyss.kernel.terrain.living.LivingAbyssSupportedFaces155B2.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LivingAbyssSupportedFaces155B2Test {
    private static final String PHASE="supported-rock-v1";
    private static final Owner INNER=new Owner("through-1/inner",Role.THROUGH_INNER);
    private static final Owner OUTER=new Owner("through-1/outer",Role.THROUGH_OUTER);
    private record P(int x,int y,int z) {}
    private static final class Rock implements Geometry {
        final Map<P,Cell> cells=new HashMap<>();
        void put(int x,int y,int z,Cell cell){cells.put(new P(x,y,z),cell);}
        public Cell cell(int x,int y,int z){return cells.getOrDefault(new P(x,y,z),Cell.AIR);}
    }
    private static LivingAbyssSupportedFaces155B2 scan(Rock rock,CubeKey... keys){
        return LivingAbyssSupportedFaces155B2.scan(PHASE,List.of(keys),rock,48);
    }

    @Test void retainsTwoRealUpperFacesAtOneXZWithDifferentOwners(){
        var r=new Rock();r.put(-1,-17,0,Cell.solid(INNER));r.put(-1,-2,0,Cell.solid(OUTER));
        var p=scan(r,new CubeKey(-1,-2,0),new CubeKey(-1,-1,0));
        var tops=p.faces().stream().filter(f->f.normal()==Normal.UP).toList();
        assertEquals(2,tops.size());
        assertEquals(List.of(-17,-2),tops.stream().map(Face::y).toList());
        assertEquals(List.of(INNER,OUTER),tops.stream().map(Face::owner).toList());
        assertEquals(14,tops.get(0).clearance());
        assertEquals(48,tops.get(1).clearance());
    }

    @Test void enumeratesUndersideAndAllFourWallsWithoutHeightfield(){
        var r=new Rock();r.put(0,0,0,Cell.solid(INNER));
        var p=scan(r,new CubeKey(0,0,0));
        assertEquals(6,p.faces().size());
        assertEquals(EnumSet.allOf(Normal.class),EnumSet.copyOf(p.faces().stream().map(Face::normal).toList()));
        assertTrue(p.faces().stream().allMatch(f->f.owner().equals(INNER)));
    }

    @Test void haloAcrossNegativeCubeBoundaryOccludesRatherThanDuplicatingFaces(){
        var r=new Rock();r.put(-17,-16,15,Cell.solid(INNER));r.put(-16,-16,15,Cell.solid(OUTER));
        var a=new CubeKey(-2,-1,0);var b=new CubeKey(-1,-1,0);
        var p=scan(r,a,b,a);
        assertEquals(10,p.faces().size());
        assertEquals(5,p.inCube(a).size());assertEquals(5,p.inCube(b).size());
        assertFalse(p.inCube(a).stream().anyMatch(f->f.normal()==Normal.EAST));
        assertFalse(p.inCube(b).stream().anyMatch(f->f.normal()==Normal.WEST));
        assertEquals(scan(r,b,a).digest(),p.digest());
    }

    @Test void nonSturdySideAndUnknownOrFluidNeighborAreNotPlantingFaces(){
        var r=new Rock();r.put(1,1,1,new Cell(true,false,Normal.UP.mask()|Normal.EAST.mask(),INNER));
        r.put(1,2,1,Cell.UNKNOWN);r.put(2,1,1,new Cell(true,false,0,null));
        assertTrue(scan(r,new CubeKey(0,0,0)).faces().isEmpty());
        r.cells.remove(new P(1,2,1));
        var faces=scan(r,new CubeKey(0,0,0)).faces();
        assertEquals(1,faces.size());assertEquals(Normal.UP,faces.get(0).normal());
    }

    @Test void freeVolumeUsesFaceNormalAndStopsAtUnknown(){
        var r=new Rock();r.put(0,0,0,Cell.solid(INNER));r.put(0,4,0,Cell.UNKNOWN);
        r.put(3,0,0,Cell.solid(OUTER));
        var faces=scan(r,new CubeKey(0,0,0)).faces();
        var up=faces.stream().filter(f->f.x()==0&&f.normal()==Normal.UP).findFirst().orElseThrow();
        var east=faces.stream().filter(f->f.x()==0&&f.normal()==Normal.EAST).findFirst().orElseThrow();
        assertEquals(3,up.clearance());assertEquals(2,east.clearance());
        assertEquals(1,east.airX());assertEquals(0,east.airY());
    }

    @Test void frozenIndexDoesNotRetainMutableGeometryOrExposeMutableCollections(){
        var r=new Rock();r.put(15,15,15,Cell.solid(INNER));var key=new CubeKey(0,0,0);
        var p=scan(r,key);String digest=p.digest();r.cells.clear();
        assertEquals(6,p.faces().size());assertEquals(digest,p.digest());
        assertThrows(UnsupportedOperationException.class,()->p.faces().clear());
        assertThrows(UnsupportedOperationException.class,()->p.inCube(key).clear());
        assertThrows(UnsupportedOperationException.class,()->p.cubeKeys().clear());
    }

    @Test void geometryPhaseParticipatesInPlanIdentity(){
        var r=new Rock();r.put(0,0,0,Cell.solid(INNER));
        var p=scan(r,new CubeKey(0,0,0));
        var q=LivingAbyssSupportedFaces155B2.scan("with-canopy",List.of(new CubeKey(0,0,0)),r,48);
        assertNotEquals(p.digest(),q.digest());
        assertEquals(PHASE,p.faces().get(0).geometryIdentity());
    }

    @Test void rejectsUnboundedOrContradictoryInputsInsteadOfInventingSupport(){
        var r=new Rock();var cubes=List.of(new CubeKey(0,0,0));
        assertThrows(IllegalArgumentException.class,()->LivingAbyssSupportedFaces155B2.scan(PHASE,cubes,r,0));
        assertThrows(IllegalArgumentException.class,()->LivingAbyssSupportedFaces155B2.scan("",cubes,r,48));
        assertThrows(IllegalArgumentException.class,()->new Cell(false,true,0,null));
        assertThrows(IllegalArgumentException.class,()->new Cell(true,true,63,INNER));
        assertThrows(IllegalArgumentException.class,()->Cell.solid(null));
        assertThrows(ArithmeticException.class,()->scan(r,new CubeKey(Integer.MAX_VALUE,0,0)));
    }
}
