package dev.madeinabyss.kernel.lod;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/** The primitive-array extraction must equal the original record/HashMap formulation exactly, order included. */
class OpaqueBoxFacesOracleTest {
    private static final class Reference {
    private record Side(int axis,boolean positive,long plane,long u0,long u1) {}
    private record Flat(boolean positive,long plane,long u0,long v0,long u1,long v1) {}
    private record Interval(long start,long end) {}
    private static OpaqueBoxFaces.Face face(OpaqueBoxFaces.Bounds b,int owner,int axis,boolean positive) {
        return switch(axis) {
            case 0->new OpaqueBoxFaces.Face(owner,axis,positive,positive?b.maxX():b.minX(),b.minZ(),b.minY(),b.maxZ(),b.maxY());
            case 1->new OpaqueBoxFaces.Face(owner,axis,positive,positive?b.maxY():b.minY(),b.minX(),b.minZ(),b.maxX(),b.maxZ());
            default->new OpaqueBoxFaces.Face(owner,axis,positive,positive?b.maxZ():b.minZ(),b.minX(),b.minY(),b.maxX(),b.maxY());
        };
    }
    private static Side side(OpaqueBoxFaces.Face f,boolean positive){return new Side(f.axis(),positive,f.plane(),f.u0(),f.u1());}
    private static Flat flat(OpaqueBoxFaces.Face f,boolean positive){return new Flat(positive,f.plane(),f.u0(),f.v0(),f.u1(),f.v1());}
    private static void add(ArrayList<OpaqueBoxFaces.Face> output,OpaqueBoxFaces.Face face,int limit) {
        if(output.size()==limit)throw new OpaqueBoxFaces.BudgetExceededException(limit);output.add(face);
    }
    static OpaqueBoxFaces.Result extract(List<OpaqueBoxFaces.Bounds> input,int maxFaces) {
        if(maxFaces<1)throw new IllegalArgumentException("Positive face budget required");
        var boxes=List.copyOf(input);var sides=new HashMap<Side,ArrayList<Interval>>();var flats=new HashSet<Flat>();
        for(int i=0;i<boxes.size();i++)for(int axis=0;axis<3;axis++)for(int sign=0;sign<2;sign++) {
            var f=face(boxes.get(i),i,axis,sign==1);
            if(axis==1)flats.add(flat(f,f.positive()));
            else sides.computeIfAbsent(side(f,f.positive()),key->new ArrayList<>()).add(new Interval(f.v0(),f.v1()));
        }
        // Premerge each opposite bucket once; subtracting touching intervals keeps exact integer boundaries.
        for(var values:sides.values()) {
            values.sort(Comparator.comparingLong(Interval::start).thenComparingLong(Interval::end));
            int size=0;
            for(int i=0;i<values.size();i++) {
                var next=values.get(i);
                if(size>0&&values.get(size-1).end>=next.start) {
                    var last=values.get(size-1);values.set(size-1,new Interval(last.start,Math.max(last.end,next.end)));
                } else values.set(size++,next);
            }
            values.subList(size,values.size()).clear();
        }
        var output=new ArrayList<OpaqueBoxFaces.Face>();int hidden=0,split=0;
        for(int i=0;i<boxes.size();i++)for(int axis=0;axis<3;axis++)for(int sign=0;sign<2;sign++) {
            var f=face(boxes.get(i),i,axis,sign==1);int before=output.size();
            if(axis==1) {if(!flats.contains(flat(f,!f.positive())))add(output,f,maxFaces);}
            else {
                var opposite=sides.get(side(f,!f.positive()));long start=f.v0();
                if(opposite!=null)for(var interval:opposite) {
                    if(interval.end<=start)continue;if(interval.start>=f.v1())break;
                    if(interval.start>start)add(output,new OpaqueBoxFaces.Face(i,axis,f.positive(),f.plane(),f.u0(),start,f.u1(),interval.start),maxFaces);
                    start=Math.max(start,interval.end);if(start>=f.v1())break;
                }
                if(start<f.v1())add(output,new OpaqueBoxFaces.Face(i,axis,f.positive(),f.plane(),f.u0(),start,f.u1(),f.v1()),maxFaces);
            }
            int emitted=output.size()-before;if(emitted==0)hidden++;else split+=emitted-1;
        }
        return new OpaqueBoxFaces.Result(output,hidden,split);
    }

    }
    private static OpaqueBoxFaces.Bounds box(long x0,long y0,long z0,long x1,long y1,long z1){return new OpaqueBoxFaces.Bounds(x0,y0,z0,x1,y1,z1);}
    private static List<OpaqueBoxFaces.Bounds> random(Random r,int count,int grid,int step,long base){
        var out=new ArrayList<OpaqueBoxFaces.Bounds>();
        for(int i=0;i<count;i++){
            long x=base+(long)r.nextInt(grid)*step,y=-20000+(long)r.nextInt(grid)*step,z=base+(long)r.nextInt(grid)*step;
            long w=step*(1+r.nextInt(3)),h=step*(1+r.nextInt(4)),d=step*(1+r.nextInt(3));
            if(r.nextInt(5)==0){w=step;d=step;} // unit columns tile exactly
            out.add(box(x,y,z,x+w,y+h,z+d));
        }
        return out;
    }
    @Test void matchesReferenceOnRandomDenseAndSparseInputs(){
        var r=new Random(90210);
        for(int round=0;round<600;round++){
            int count=1+r.nextInt(round<500?120:900),grid=2+r.nextInt(round%3==0?3:12),step=1<<r.nextInt(4);
            var boxes=random(r,count,grid,step,round%2==0?-37:Integer.MAX_VALUE-64L*step);
            var expected=Reference.extract(boxes,1<<20);
            var actual=OpaqueBoxFaces.extract(boxes,1<<20);
            assertEquals(expected,actual,"round "+round);
        }
    }
    @Test void budgetBehavesLikeReference(){
        var r=new Random(31337);
        for(int round=0;round<300;round++){
            var boxes=random(r,1+r.nextInt(60),2+r.nextInt(6),4,-9);
            var full=Reference.extract(boxes,1<<20);int exact=full.faces().size();
            for(int limit:new int[]{1,Math.max(1,exact-1),Math.max(1,exact),exact+1,exact+7}){
                Object expected,actual;
                try{expected=Reference.extract(boxes,limit);}catch(OpaqueBoxFaces.BudgetExceededException e){expected=e.getMessage();}
                try{actual=OpaqueBoxFaces.extract(boxes,limit);}catch(OpaqueBoxFaces.BudgetExceededException e){actual=e.getMessage();}
                assertEquals(expected,actual,"round "+round+" limit "+limit);
            }
        }
    }
    @Test void tallStacksOfIdenticalFootprintsUseTheLargeGroupSortExactly(){
        var boxes=new ArrayList<OpaqueBoxFaces.Bounds>();var r=new Random(5);
        for(int i=0;i<300;i++){long y=-20000+r.nextInt(400)*3;boxes.add(box(0,y,0,4,y+1+r.nextInt(5),4));}
        for(int i=0;i<300;i++){long y=-20000+r.nextInt(400)*3;boxes.add(box(4,y,0,8,y+1+r.nextInt(5),4));}
        assertEquals(Reference.extract(boxes,1<<20),OpaqueBoxFaces.extract(boxes,1<<20));
    }
}
