package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.light.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BoxLightWorkOrderTest {
    @Test void hybrid64SignedExtremeCentersLargeBoxesAndTiesMatchIndependentWideMorton(){
        var input=new ArrayList<TerrainLodColumns.Box>();
        for(long coordinate:new long[]{Long.MIN_VALUE,Integer.MIN_VALUE,-1025,-65,-64,-63,-1,0,1,63,64,65,1024,Integer.MAX_VALUE,Long.MAX_VALUE-1})
            input.add(box(coordinate,-65,coordinate,coordinate+1,-64,coordinate+1));
        input.add(box(Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE));
        input.add(box(-1,-1,-1,1,1,1));input.add(box(0,0,0,1,1,1));input.add(input.get(input.size()-1));
        var random=new Random(641082);
        for(int i=0;i<600;i++){long x=random.nextInt(4096)-2048,y=random.nextInt(4096)-2048,z=random.nextInt(4096)-2048;input.add(box(x,y,z,x+1+random.nextInt(97),y+1+random.nextInt(193),z+1+random.nextInt(97)));}
        var expected=referenceOrder(input);var actual=BoxLightWorkOrder.order(input,input.size());
        assertEquals(input.size(),actual.size());
        for(int i=0;i<expected.size();i++)assertEquals(expected.get(i).intValue(),actual.originalIndex(i),"Exact hybrid64 order at slot "+i);
    }

    @Test void resultsScatterToOriginalPositionsWithoutDroppingDuplicatesOrMutatingGeometry(){
        var input=new ArrayList<>(List.of(box(128,-256,64,144,-200,80),box(-17,-431,-33,-16,-430,-32),box(0,-900,0,1,-899,1)));
        input.add(input.get(1));var original=List.copyOf(input);var permutation=BoxLightWorkOrder.order(input,4);
        input.clear();var sortedValues=new ArrayList<String>();var seen=new BitSet();
        for(int i=0;i<permutation.size();i++){
            int index=permutation.originalIndex(i);assertFalse(seen.get(index));seen.set(index);
            assertSame(original.get(index),permutation.orderedBoxes().get(i));sortedValues.add("original="+index);
        }
        assertEquals(4,seen.cardinality());
        assertEquals(List.of("original=0","original=1","original=2","original=3"),permutation.restore(sortedValues));
        assertThrows(UnsupportedOperationException.class,()->permutation.orderedBoxes().clear());
        assertThrows(IllegalArgumentException.class,()->permutation.restore(List.of("partial")));
        assertThrows(NullPointerException.class,()->permutation.restore(Arrays.asList("a","b",null,"d")));
    }

    @Test void realExposedFaceOpacityPageCacheHasFewerMissesThanColumnHeightSweep(){
        var input=new ArrayList<TerrainLodColumns.Box>();
        for(int x=4;x<12;x++)for(int z=4;z<12;z++)for(int level=0;level<32;level++){
            int y=-1024+64*level;input.add(box(x,y,z,x+1,y+1,z+1));
        }
        var original=faceCacheWork(input);var ordered=faceCacheWork(BoxLightWorkOrder.order(input,input.size()).orderedBoxes());
        assertEquals(original.values().size(),ordered.values().size());
        assertTrue(original.values().stream().allMatch(value->value==14)&&ordered.values().stream().allMatch(value->value==14));
        assertTrue(original.misses()>1000,"Adversarial current X/Z/height sweep really churns the actual paged cache");
        assertTrue(ordered.misses()<original.misses(),"Hybrid64 groups actual exposed-face page lookups; original="+original.misses()+", ordered="+ordered.misses());
        assertTrue(ordered.hits()>original.hits(),"Improvement is actual PagedOpacityCache hits, not only center distance");
    }

    @Test void actual32RayFractionsQuantizationAndExposedSkyMatchAfterOneAndFourWorkerPermutation(){
        var input=new ArrayList<TerrainLodColumns.Box>();
        for(int x:new int[]{-65,-17,-1,0,15,64})for(int z:new int[]{-33,-1,0,32})for(int y:new int[]{-32,-17,-2})input.add(box(x,y,z,x+1,y+1,z+1));
        SkyVisibility.VoxelOpacity oracle=BoxLightWorkOrderTest::opacity;
        var expected=input.stream().map(box->solve(box,oracle)).toList();
        for(int workers:new int[]{1,4}){
            var executor=Executors.newFixedThreadPool(workers);var caches=ThreadLocal.withInitial(()->new PagedOpacityCache(64));
            try{
                var actual=BoxLightWorkOrder.map(input,workers,executor,box->solve(box,(x,y,z)->memo(caches.get(),x,y,z)),input.size());
                assertEquals(expected,actual,"Every full32-ray bit/scalar result is restored to its original geometry slot");
            }finally{executor.shutdownNow();}
        }
        assertTrue(expected.stream().map(Light::fractionBits).distinct().count()>1,"Real partial/opaque fixture changes irradiance");
    }

    @Test void cancellationAndOriginalUnknownFailureCannotPublishPartialResults()throws Exception {
        var executor=Executors.newFixedThreadPool(2);var calls=new AtomicInteger();
        var first=box(128,-32,0,129,-31,1);var second=box(-64,-32,0,-63,-31,1);
        try{
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class,()->BoxLightWorkOrder.map(List.of(first,second),2,executor,box->calls.incrementAndGet(),2));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,calls.get());Thread.interrupted();
            var gate=new CyclicBarrier(2);var cancelled=new CountDownLatch(1);var failure=new IllegalStateException("UNKNOWN authoritative voxel");var published=new AtomicReference<List<Integer>>();
            assertSame(failure,assertThrows(IllegalStateException.class,()->published.set(BoxLightWorkOrder.map(List.of(first,second),2,executor,box->{
                try{gate.await(2,TimeUnit.SECONDS);if(box==first)throw failure;Thread.sleep(10000);return 14;}
                catch(InterruptedException interrupted){cancelled.countDown();Thread.currentThread().interrupt();throw new CancellationException();}
                catch(BrokenBarrierException|TimeoutException broken){throw new IllegalStateException(broken);}
            },2))));
            assertNull(published.get());assertTrue(cancelled.await(2,TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class,()->BoxLightWorkOrder.order(List.of(first,second),1));
            assertThrows(IllegalArgumentException.class,()->BoxLightWorkOrder.order(List.of(),0));
            assertEquals(List.of(),BoxLightWorkOrder.map(List.of(),2,executor,box->14,1));
        }finally{Thread.interrupted();executor.shutdownNow();}
    }

    @Test void temporaryOrderingAllocationIsLinearAndBoundedForFiftyThousandJobs(){
        var base=ManagementFactory.getThreadMXBean();assumeTrue(base instanceof com.sun.management.ThreadMXBean,"Actual allocation measurement unavailable on this JVM");
        var bean=(com.sun.management.ThreadMXBean)base;assumeTrue(bean.isThreadAllocatedMemorySupported(),"Actual allocation measurement unavailable on this JVM");
        if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        var input=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<50000;i++){long x=(i*197L)%100000-50000,y=(i*61L)%3000-1500,z=(i*117L)%100000-50000;input.add(box(x,y,z,x+1,y+1,z+1));}
        for(int i=0;i<4;i++)BoxLightWorkOrder.order(input.subList(0,1000),1000);
        long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        var order=BoxLightWorkOrder.order(input,input.size());
        long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        assertEquals(input.size(),order.size());
        assertTrue(bytes>=0&&bytes<=48L*input.size()+1024*1024,"Primitive indices/centers, not per-comparison objects; actualbytes="+bytes);
    }

    private record CacheWork(List<Integer> values,long hits,long misses){}
    private static CacheWork faceCacheWork(List<TerrainLodColumns.Box> boxes){
        var cache=new PagedOpacityCache(16);var values=new ArrayList<Integer>();
        for(var box:boxes)values.add(LodBoxSkyLight.maximum(box,(x,y,z)->{int known=cache.get(x,y,z);if(known<0){known=0;cache.put(x,y,z,known);}return known;},(x,y,z)->14,(x,y,z)->{throw new AssertionError("Direct14 fixture has no diffuse fallback");}));
        return new CacheWork(values,cache.hits(),cache.misses());
    }
    private record Light(long fractionBits,int quantized,int exposed){}
    private static Light solve(TerrainLodColumns.Box box,SkyVisibility.VoxelOpacity lookup){
        int x=Math.toIntExact(center(box.minX(),box.maxXExclusive())),y=Math.toIntExact(center(box.minY(),box.maxYExclusive())),z=Math.toIntExact(center(box.minZ(),box.maxZExclusive()));
        double fraction=SkyVisibility.visibleFraction(x,y,z,32,10000,lookup);
        int quantized=QuantizedDiffuseSky.level(x,y,z,32,10000,lookup,false);
        int exposed=LodBoxSkyLight.maximum(box,(px,py,pz)->lookup.opacity(px,py,pz),(px,py,pz)->0,
                (px,py,pz)->SkyVisibility.diffuseLevel(SkyVisibility.visibleFraction(px,py,pz,32,10000,lookup)));
        return new Light(Double.doubleToRawLongBits(fraction),quantized,exposed);
    }
    private static int opacity(int x,int y,int z){return y==0&&Math.floorMod(x+2L*z,5)!=0?15:y==-3&&Math.floorMod((long)x-z,7)==0?1:0;}
    private static int memo(PagedOpacityCache cache,int x,int y,int z){int known=cache.get(x,y,z);if(known<0){known=opacity(x,y,z);cache.put(x,y,z,known);}return known;}
    private static TerrainLodColumns.Box box(long x,long y,long z,long xx,long yy,long zz){return new TerrainLodColumns.Box(x,y,z,xx,yy,zz,TerrainMaterial.ROCK);}
    private static long center(long minimum,long maximumExclusive){return BigInteger.valueOf(minimum).add(BigInteger.valueOf(maximumExclusive)).subtract(BigInteger.ONE).shiftRight(1).longValueExact();}
    private static List<Integer> referenceOrder(List<TerrainLodColumns.Box> input){
        long[] x=new long[input.size()],y=new long[input.size()],z=new long[input.size()];long minX=Long.MAX_VALUE,minY=Long.MAX_VALUE,minZ=Long.MAX_VALUE;
        for(int i=0;i<input.size();i++){var box=input.get(i);x[i]=Math.floorDiv(center(box.minX(),box.maxXExclusive()),64);y[i]=Math.floorDiv(center(box.minY(),box.maxYExclusive()),64);z[i]=Math.floorDiv(center(box.minZ(),box.maxZExclusive()),64);minX=Math.min(minX,x[i]);minY=Math.min(minY,y[i]);minZ=Math.min(minZ,z[i]);}
        var keys=new BigInteger[input.size()];var result=new ArrayList<Integer>();
        for(int i=0;i<input.size();i++){keys[i]=morton(BigInteger.valueOf(x[i]).subtract(BigInteger.valueOf(minX)),BigInteger.valueOf(y[i]).subtract(BigInteger.valueOf(minY)),BigInteger.valueOf(z[i]).subtract(BigInteger.valueOf(minZ)));result.add(i);}
        result.sort(Comparator.<Integer,BigInteger>comparing(i->keys[i]).thenComparingLong(i->center(input.get(i).minX(),input.get(i).maxXExclusive())).thenComparingLong(i->center(input.get(i).minZ(),input.get(i).maxZExclusive())).thenComparingLong(i->center(input.get(i).minY(),input.get(i).maxYExclusive())).thenComparingInt(i->i));
        return result;
    }
    private static BigInteger morton(BigInteger x,BigInteger y,BigInteger z){
        BigInteger value=BigInteger.ZERO;
        for(int bit=63;bit>=0;bit--)value=value.shiftLeft(3).or(BigInteger.valueOf((x.testBit(bit)?4:0)|(y.testBit(bit)?2:0)|(z.testBit(bit)?1:0)));
        return value;
    }
}
