package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.concurrent.BoundedOrderedWork;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/** Bounded hybrid64 execution order for independent scalar lighting jobs.
 * Geometry and published scalar results retain their original indices. */
public final class BoxLightWorkOrder {
    private BoxLightWorkOrder(){}
    public static Permutation order(List<TerrainLodColumns.Box> input,int maximumJobs){
        Objects.requireNonNull(input);
        if(maximumJobs<1||input.size()>maximumJobs)throw new IllegalArgumentException("Bounded box-light job count required");
        checkInterrupted();
        var stable=List.copyOf(input);int size=stable.size();var indices=new int[size];
        var x=new long[size];var y=new long[size];var z=new long[size];
        long minX=Long.MAX_VALUE,minY=Long.MAX_VALUE,minZ=Long.MAX_VALUE;
        for(int i=0;i<size;i++){
            if((i&1023)==0)checkInterrupted();
            var box=stable.get(i);indices[i]=i;
            x[i]=center(box.minX(),box.maxXExclusive());y[i]=center(box.minY(),box.maxYExclusive());z[i]=center(box.minZ(),box.maxZExclusive());
            minX=Math.min(minX,x[i]>>6);minY=Math.min(minY,y[i]>>6);minZ=Math.min(minZ,z[i]>>6);
        }
        // Primitive merge buffers and centers require32 bytes/job, independent of
        // comparator count. The per-list tile spans fit58 bits even for full longs.
        var scratch=new int[size];
        for(long width=1;width<size;width<<=1){
            checkInterrupted();
            for(long start=0;start<size;start+=2*width){
                int left=(int)start,middle=(int)Math.min(size,start+width),right=middle,end=(int)Math.min(size,start+2*width),output=left;
                while(left<middle&&right<end){
                    if((output&1023)==0)checkInterrupted();
                    scratch[output++]=compare(indices[left],indices[right],x,y,z,minX,minY,minZ)<=0?indices[left++]:indices[right++];
                }
                while(left<middle){if((output&1023)==0)checkInterrupted();scratch[output++]=indices[left++];}
                while(right<end){if((output&1023)==0)checkInterrupted();scratch[output++]=indices[right++];}
            }
            var swap=indices;indices=scratch;scratch=swap;
        }
        checkInterrupted();
        return new Permutation(stable,indices);
    }
    private static long center(long minimum,long maximumExclusive){
        long upper=maximumExclusive-1;
        // Floor((minimum+upper)/2) without overflowing the signed sum.
        return (minimum&upper)+((minimum^upper)>>1);
    }
    private static int compare(int a,int b,long[] x,long[] y,long[] z,long minX,long minY,long minZ){
        long ax=(x[a]>>6)-minX,bx=(x[b]>>6)-minX;
        long ay=(y[a]>>6)-minY,by=(y[b]>>6)-minY;
        long az=(z[a]>>6)-minZ,bz=(z[b]>>6)-minZ;
        long dx=ax^bx,dy=ay^by,dz=az^bz;
        long highest=Long.highestOneBit(dx|dy|dz);
        // The highest differing interleaved bit decides an exact wide Morton
        // comparison. Axis precedence within that bit is X,Y,Z; no truncation.
        if(highest!=0){
            if((dx&highest)!=0)return Long.compare(ax,bx);
            if((dy&highest)!=0)return Long.compare(ay,by);
            return Long.compare(az,bz);
        }
        int order=Long.compare(x[a],x[b]);if(order!=0)return order;
        order=Long.compare(z[a],z[b]);if(order!=0)return order;
        order=Long.compare(y[a],y[b]);return order!=0?order:Integer.compare(a,b);
    }
    private static void checkInterrupted(){
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Box-light order interrupted");
    }
    public static <R> List<R> map(List<TerrainLodColumns.Box> input,int workers,ExecutorService executor,
            Function<TerrainLodColumns.Box,R> operation,int maximumJobs){
        var permutation=order(input,maximumJobs);
        return permutation.restore(BoundedOrderedWork.map(permutation.orderedBoxes(),workers,executor,operation));
    }
    public static final class Permutation {
        private final int[] indices;
        private final List<TerrainLodColumns.Box> ordered;
        private Permutation(List<TerrainLodColumns.Box> source,int[] indices){this.indices=indices;this.ordered=new OrderedBoxes(source,indices);}
        public int size(){return indices.length;}
        public int originalIndex(int orderedIndex){return indices[orderedIndex];}
        public List<TerrainLodColumns.Box> orderedBoxes(){return ordered;}
        public <R> List<R> restore(List<R> orderedValues){
            if(orderedValues.size()!=size())throw new IllegalArgumentException("Complete box-light result count required");
            checkInterrupted();var restored=new ArrayList<R>(Collections.nCopies(size(),null));
            for(int i=0;i<size();i++){
                if((i&1023)==0)checkInterrupted();
                restored.set(indices[i],Objects.requireNonNull(orderedValues.get(i),"Box-light result"));
            }
            checkInterrupted();return List.copyOf(restored);
        }
    }
    private static final class OrderedBoxes extends AbstractList<TerrainLodColumns.Box> implements RandomAccess {
        private final List<TerrainLodColumns.Box> source;private final int[] indices;
        private OrderedBoxes(List<TerrainLodColumns.Box> source,int[] indices){this.source=source;this.indices=indices;}
        @Override public TerrainLodColumns.Box get(int index){return source.get(indices[index]);}
        @Override public int size(){return indices.length;}
    }
}
