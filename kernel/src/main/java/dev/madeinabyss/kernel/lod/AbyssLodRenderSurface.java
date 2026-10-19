package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

/** Render-only owner selection. Never rewrites RAW, samples the generator or computes light. */
public final class AbyssLodRenderSurface {
    private AbyssLodRenderSurface(){}
    public record Result(List<TerrainLodColumns.Box> kept,List<Integer> keptIndices,int opaqueInput,int removed,int keptWater,long examinedFaces,boolean budgetRefused){
        public Result{kept=List.copyOf(kept);keptIndices=List.copyOf(keptIndices);if(kept.size()!=keptIndices.size())throw new IllegalArgumentException("Owner mask differs from kept geometry");}
    }
    public static Result cull(List<TerrainLodColumns.Box> geometry,int maxFaces){
        Objects.requireNonNull(geometry);if(maxFaces<0)throw new IllegalArgumentException("Negative surface work budget");
        int opaque=0,water=0;
        for(var box:geometry){checkCancelled();Objects.requireNonNull(box);if(box.material()==TerrainMaterial.WATER)water++;else opaque++;}
        var work=new Work(maxFaces);int[] covered=new int[geometry.size()];boolean refused=6L*opaque>maxFaces;
        if(!refused)try{
            // One axis at a time bounds peak indexing at two faces per opaque owner.
            // These keys refer to immutable boxes; no six-face rectangle object graph is built.
            for(int axis=0;axis<3;axis++){
                var exact=new HashSet<Face>();
                for(var box:geometry){checkCancelled();if(box.material()==TerrainMaterial.WATER)continue;
                    for(int side=0;side<2;side++){work.take();exact.add(new Face(box,axis,side,side,work));}}
                for(int i=0;i<geometry.size();i++){checkCancelled();var box=geometry.get(i);if(box.material()==TerrainMaterial.WATER)continue;
                    // Finish every exact face before any potentially expensive partial proof.
                    for(int side=0;side<2;side++){work.take();if(exact.contains(new Face(box,axis,side,side^1,work)))covered[i]|=1<<(axis*2+side);}
                }
            }
            for(int axis=0;axis<3;axis++){
                boolean needed=false;int axisMask=3<<(axis*2);
                for(int i=0;i<geometry.size();i++){checkCancelled();if(geometry.get(i).material()!=TerrainMaterial.WATER&&(covered[i]&axisMask)!=axisMask){needed=true;break;}}
                if(!needed)continue;
                var spans=new HashMap<Span,Ranges>();var planes=new HashMap<Plane,ArrayList<TerrainLodColumns.Box>>();
                int horizontal=axis==0?2:0,vertical=axis==1?2:1;
                for(var box:geometry){checkCancelled();if(box.material()==TerrainMaterial.WATER)continue;
                    for(int side=0;side<2;side++){work.take();long position=side==0?low(box,axis):high(box,axis);
                        spans.computeIfAbsent(new Span(side,position,low(box,horizontal),high(box,horizontal)),unused->new Ranges())
                            .values.add(new Interval(low(box,vertical),high(box,vertical)));
                        planes.computeIfAbsent(new Plane(side,position),unused->new ArrayList<>()).add(box);}}
                for(int i=0;i<geometry.size();i++){checkCancelled();var box=geometry.get(i);if(box.material()==TerrainMaterial.WATER||covered[i]==63)continue;
                    for(int side=0;side<2;side++){int bit=1<<(axis*2+side);if((covered[i]&bit)!=0)continue;
                        if(covered(box,axis,side,spans,planes,work))covered[i]|=bit;}
                }
            }
        }catch(WorkExhausted exhausted){refused=true;}
        var kept=new ArrayList<TerrainLodColumns.Box>(geometry.size());var indices=new ArrayList<Integer>(geometry.size());int removed=0;
        for(int i=0;i<geometry.size();i++){checkCancelled();if(covered[i]==63)removed++;else{kept.add(geometry.get(i));indices.add(i);}}
        // Exhaustion cannot invalidate an already complete six-face proof. Every unproved owner remains.
        return new Result(kept,indices,opaque,removed,water,work.used,refused);
    }

    private static boolean covered(TerrainLodColumns.Box box,int axis,int side,HashMap<Span,Ranges> spans,
                                   HashMap<Plane,ArrayList<TerrainLodColumns.Box>> planes,Work work){
        work.take();long position=side==0?low(box,axis):high(box,axis);int horizontal=axis==0?2:0,vertical=axis==1?2:1;
        var sameSpan=spans.get(new Span(side^1,position,low(box,horizontal),high(box,horizontal)));
        if(sameSpan!=null&&sameSpan.covers(low(box,vertical),high(box,vertical),work))return true;
        var neighbors=planes.get(new Plane(side^1,position));
        // Wide mixed-span planes are deliberately conservative: no quadratic scan of all Z columns.
        if(neighbors==null||neighbors.size()>64)return false;
        long u0=low(box,(axis+1)%3),u1=high(box,(axis+1)%3),v0=low(box,(axis+2)%3),v1=high(box,(axis+2)%3);
        var rectangles=new ArrayList<Rect>();
        for(var neighbor:neighbors){work.take();
            long a=Math.max(u0,low(neighbor,(axis+1)%3)),b=Math.min(u1,high(neighbor,(axis+1)%3));
            long c=Math.max(v0,low(neighbor,(axis+2)%3)),d=Math.min(v1,high(neighbor,(axis+2)%3));
            if(a>=b||c>=d)continue;if(a==u0&&b==u1&&c==v0&&d==v1)return true;
            rectangles.add(new Rect(a,b,c,d));
        }
        if(rectangles.isEmpty())return false;
        // Exact union, not a bounding rectangle or summed areas: overlapping rectangles cannot fill a gap.
        var cuts=new TreeSet<Long>((a,b)->{work.take();return Long.compare(a,b);});cuts.add(u0);cuts.add(u1);
        for(var rectangle:rectangles){work.take();cuts.add(rectangle.u0);cuts.add(rectangle.u1);}
        Long left=null;var strip=new ArrayList<Rect>();
        for(long right:cuts){work.take();if(left==null){left=right;continue;}strip.clear();
            for(var rectangle:rectangles){work.take();if(rectangle.u0<=left&&rectangle.u1>=right)strip.add(rectangle);}
            strip.sort((a,b)->{work.take();return Long.compare(a.v0,b.v0);});long cursor=v0;
            for(var rectangle:strip){work.take();if(rectangle.v0>cursor)return false;cursor=Math.max(cursor,rectangle.v1);if(cursor>=v1)break;}
            if(cursor<v1)return false;left=right;
        }
        return true;
    }
    private record Plane(int side,long position){}
    private record Span(int side,long position,long start,long end){}
    private record Interval(long start,long end){}
    private static final class Ranges {
        final ArrayList<Interval> values=new ArrayList<>();boolean merged;
        boolean covers(long start,long end,Work work){
            if(!merged){values.sort((a,b)->{work.take();return Long.compare(a.start,b.start);});int size=0;
                for(int i=0;i<values.size();i++){work.take();var next=values.get(i);
                    if(size>0&&values.get(size-1).end>=next.start){var last=values.get(size-1);values.set(size-1,new Interval(last.start,Math.max(last.end,next.end)));}
                    else values.set(size++,next);}
                values.subList(size,values.size()).clear();merged=true;}
            // Union ranges are disjoint and sorted: only the predecessor can cover the requested span.
            int left=0,right=values.size();while(left<right){work.take();int middle=(left+right)>>>1;
                if(values.get(middle).start<=start)left=middle+1;else right=middle;}
            return left>0&&values.get(left-1).end>=end;
        }
    }
    private record Rect(long u0,long u1,long v0,long v1){}
    private static long low(TerrainLodColumns.Box b,int axis){return axis==0?b.minX():axis==1?b.minY():b.minZ();}
    private static long high(TerrainLodColumns.Box b,int axis){return axis==0?b.maxXExclusive():axis==1?b.maxYExclusive():b.maxZExclusive();}
    private static final class Face {
        final TerrainLodColumns.Box box;final int axis,keySide,hash;final long plane;final Work work;
        Face(TerrainLodColumns.Box box,int axis,int ownSide,int keySide,Work work){this.box=box;this.axis=axis;this.keySide=keySide;this.work=work;plane=ownSide==0?low(box,axis):high(box,axis);
            int h=31*keySide+Long.hashCode(plane);h=31*h+Long.hashCode(low(box,(axis+1)%3));h=31*h+Long.hashCode(high(box,(axis+1)%3));
            h=31*h+Long.hashCode(low(box,(axis+2)%3));hash=31*h+Long.hashCode(high(box,(axis+2)%3));}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object value){work.take();if(!(value instanceof Face other)||keySide!=other.keySide||plane!=other.plane||axis!=other.axis)return false;
            return low(box,(axis+1)%3)==low(other.box,(axis+1)%3)&&high(box,(axis+1)%3)==high(other.box,(axis+1)%3)
                &&low(box,(axis+2)%3)==low(other.box,(axis+2)%3)&&high(box,(axis+2)%3)==high(other.box,(axis+2)%3);}
    }
    private static void checkCancelled(){if(Thread.currentThread().isInterrupted())throw new CancellationException();}
    /** Includes indexing, candidate checks, rectangle sweep and sort comparisons, not just six owner faces. */
    private static final class Work {final long limit;long used;Work(long limit){this.limit=limit;}void take(){checkCancelled();if(used>=limit)throw new WorkExhausted();used++;}}
    private static final class WorkExhausted extends RuntimeException {WorkExhausted(){super(null,null,false,false);}}
}
