package dev.madeinabyss.kernel.lod;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class OpaqueBoxFacesTest {
    private static OpaqueBoxFaces.Bounds box(long x0,long y0,long z0,long x1,long y1,long z1) {
        return new OpaqueBoxFaces.Bounds(x0,y0,z0,x1,y1,z1);
    }
    @Test void cancelsSharedFacesWithoutChangingOwner() {
        var result=OpaqueBoxFaces.extract(List.of(box(-8,-20000,-8,0,-19992,0),box(0,-20000,-8,8,-19992,0)),12);
        assertEquals(10,result.faces().size());assertEquals(2,result.hiddenFaces());
        assertFalse(result.faces().stream().anyMatch(f->f.axis()==0&&f.plane()==0));
        assertTrue(result.faces().stream().anyMatch(f->f.owner()==0&&f.axis()==0&&f.plane()==-8));
        assertTrue(result.faces().stream().anyMatch(f->f.owner()==1&&f.axis()==0&&f.plane()==8));
    }
    @Test void subtractsUnionOfNeighborYIntervalsAndKeepsExactBoundaries() {
        var result=OpaqueBoxFaces.extract(List.of(box(-8,-20,-8,0,0,0),box(0,-17,-8,8,-13,0),box(0,-15,-8,8,-9,0)),30);
        var faces=result.faces().stream().filter(f->f.owner()==0&&f.axis()==0&&f.positive()).toList();
        assertEquals(2,faces.size());assertEquals(-20,faces.get(0).v0());assertEquals(-17,faces.get(0).v1());
        assertEquals(-9,faces.get(1).v0());assertEquals(0,faces.get(1).v1());
        assertEquals(-8,faces.get(0).u0());assertEquals(0,faces.get(0).u1());
    }
    @Test void conservativelyKeepsPartialHorizontalSpansAndYRectangles() {
        var result=OpaqueBoxFaces.extract(List.of(box(-8,-16,-8,0,0,0),box(0,-16,-4,8,0,0),box(-8,0,-4,0,8,0)),18);
        assertTrue(result.faces().stream().anyMatch(f->f.owner()==0&&f.axis()==0&&f.positive()&&f.v0()==-16&&f.v1()==0));
        assertTrue(result.faces().stream().anyMatch(f->f.owner()==0&&f.axis()==1&&f.positive()));
        assertEquals(18,result.faces().size());
    }
    @Test void cancelsFullYRectangleAndZFaces() {
        var stacked=OpaqueBoxFaces.extract(List.of(box(-8,-16,-8,0,0,0),box(-8,0,-8,0,8,0)),12);
        assertEquals(10,stacked.faces().size());
        var z=OpaqueBoxFaces.extract(List.of(box(-8,-16,-8,0,0,0),box(-8,-16,0,0,0,8)),12);
        assertEquals(10,z.faces().size());assertFalse(z.faces().stream().anyMatch(f->f.axis()==2&&f.plane()==0));
    }
    @Test void refusesEntireResultOverBudgetAndValidatesBounds() {
        assertThrows(OpaqueBoxFaces.BudgetExceededException.class,()->OpaqueBoxFaces.extract(List.of(box(0,0,0,1,1,1)),5));
        assertThrows(IllegalArgumentException.class,()->box(0,0,0,0,1,1));
        assertEquals(0,OpaqueBoxFaces.extract(List.of(),1).faces().size());
        assertEquals(6,OpaqueBoxFaces.extract(List.of(box(Integer.MIN_VALUE,-22000,Integer.MIN_VALUE,Integer.MAX_VALUE,320,Integer.MAX_VALUE)),6).faces().size());
    }
    @Test void matchesExposedVoxelFaceOracleInNegativeSpace() {
        record Cell(int x,int y,int z) {}
        var cells=new HashSet<Cell>();var random=new Random(2281);
        for(int i=0;i<500;i++)cells.add(new Cell(random.nextInt(10)-5,random.nextInt(10)-20010,random.nextInt(10)-5));
        var ordered=new ArrayList<>(cells);ordered.sort(java.util.Comparator.comparingInt(Cell::x).thenComparingInt(Cell::y).thenComparingInt(Cell::z));
        var boxes=ordered.stream().map(c->box(c.x,c.y,c.z,c.x+1,c.y+1,c.z+1)).toList();
        var result=OpaqueBoxFaces.extract(boxes,boxes.size()*6);int expected=0;
        for(var cell:ordered)for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
            var neighbor=new Cell(cell.x+(axis==0?sign:0),cell.y+(axis==1?sign:0),cell.z+(axis==2?sign:0));
            if(!cells.contains(neighbor))expected++;
        }
        assertEquals(expected,result.faces().size());
        for(var face:result.faces()) {
            var cell=ordered.get(face.owner());int sign=face.positive()?1:-1;
            assertFalse(cells.contains(new Cell(cell.x+(face.axis()==0?sign:0),cell.y+(face.axis()==1?sign:0),cell.z+(face.axis()==2?sign:0))));
        }
    }
}
