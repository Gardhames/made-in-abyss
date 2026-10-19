package dev.madeinabyss.kernel.lod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable median-split bounds hierarchy. Visibility predicates must be conservative:
 * rejecting a node means that all bounds contained in that node are invisible. */
public final class SpatialBoundsIndex {
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        public Bounds {
            if(!Double.isFinite(minX)||!Double.isFinite(minY)||!Double.isFinite(minZ)
                    ||!Double.isFinite(maxX)||!Double.isFinite(maxY)||!Double.isFinite(maxZ)
                    ||minX>=maxX||minY>=maxY||minZ>=maxZ)throw new IllegalArgumentException("Finite positive bounds required");
        }
        public boolean intersects(Bounds other) {
            return minX<other.maxX&&maxX>other.minX&&minY<other.maxY&&maxY>other.minY&&minZ<other.maxZ&&maxZ>other.minZ;
        }
        private double center(int axis) {
            // Split quality is approximate; bounds/pruning remain exact even for extreme doubles.
            return switch(axis){case 0->minX*.5+maxX*.5;case 1->minY*.5+maxY*.5;default->minZ*.5+maxZ*.5;};
        }
    }
    public interface Visibility {
        boolean visibleNode(int nodeIndex);
        boolean visibleItem(int originalIndex);
    }
    public record Query(int[] indices,long visitedNodes,long testedItems,boolean overflow) {}
    private record Node(Bounds bounds,int start,int end,int left,int right) {}
    private final List<Bounds> items;
    private final Integer[] order;
    private final List<Node> nodes;
    private SpatialBoundsIndex(List<Bounds> items,Integer[] order,List<Node> nodes) {
        this.items=items;this.order=order;this.nodes=List.copyOf(nodes);
    }
    public static SpatialBoundsIndex create(List<Bounds> input,int leafSize) {
        if(leafSize<1||leafSize>4096)throw new IllegalArgumentException("Leaf size must be1..4096");
        var items=List.copyOf(input);var order=new Integer[items.size()];for(int i=0;i<order.length;i++)order[i]=i;
        var nodes=new ArrayList<Node>();if(!items.isEmpty())build(items,order,nodes,0,items.size(),leafSize);
        return new SpatialBoundsIndex(items,order,nodes);
    }
    private static int build(List<Bounds> items,Integer[] order,ArrayList<Node> nodes,int start,int end,int leafSize) {
        double x0=Double.POSITIVE_INFINITY,y0=x0,z0=x0,x1=Double.NEGATIVE_INFINITY,y1=x1,z1=x1;
        for(int i=start;i<end;i++) {
            var b=items.get(order[i]);x0=Math.min(x0,b.minX);y0=Math.min(y0,b.minY);z0=Math.min(z0,b.minZ);
            x1=Math.max(x1,b.maxX);y1=Math.max(y1,b.maxY);z1=Math.max(z1,b.maxZ);
        }
        var bounds=new Bounds(x0,y0,z0,x1,y1,z1);int index=nodes.size();nodes.add(null);
        int left=-1,right=-1;
        if(end-start>leafSize) {
            double dx=x1-x0,dy=y1-y0,dz=z1-z0;int axis=dx>=dy&&dx>=dz?0:dy>=dz?1:2;
            Arrays.sort(order,start,end,Comparator.comparingDouble((Integer i)->items.get(i).center(axis)).thenComparingInt(Integer::intValue));
            int middle=start+(end-start)/2;
            left=build(items,order,nodes,start,middle,leafSize);right=build(items,order,nodes,middle,end,leafSize);
        }
        nodes.set(index,new Node(bounds,start,end,left,right));return index;
    }
    public int nodeCount(){return nodes.size();}
    public Bounds nodeBounds(int index){return nodes.get(index).bounds;}
    public Bounds itemBounds(int originalIndex){return items.get(originalIndex);}
    public Query query(Bounds bounds,int limit) {
        Objects.requireNonNull(bounds);
        return query(new Visibility(){public boolean visibleNode(int index){return nodeBounds(index).intersects(bounds);}
            public boolean visibleItem(int index){return itemBounds(index).intersects(bounds);}},limit);
    }
    public Query query(Visibility visibility,int limit) {
        Objects.requireNonNull(visibility);if(limit<1)throw new IllegalArgumentException("Positive result limit required");
        if(nodes.isEmpty())return new Query(new int[0],0,0,false);
        int[] result=new int[Math.min(128,limit)],stack=new int[64];int size=0,top=0;long visited=0,tested=0;boolean overflow=false;
        stack[top++]=0;
        outer:while(top>0) {
            int index=stack[--top];visited++;if(!visibility.visibleNode(index))continue;var node=nodes.get(index);
            if(node.left>=0){stack[top++]=node.right;stack[top++]=node.left;continue;}
            for(int i=node.start;i<node.end;i++) {
                int original=order[i];tested++;if(!visibility.visibleItem(original))continue;
                if(size==limit){overflow=true;break outer;}
                if(size==result.length)result=Arrays.copyOf(result,(int)Math.min(limit,Math.max((long)size+1,(long)size*2)));
                result[size++]=original;
            }
        }
        result=Arrays.copyOf(result,size);Arrays.sort(result);
        return new Query(result,visited,tested,overflow);
    }
}
