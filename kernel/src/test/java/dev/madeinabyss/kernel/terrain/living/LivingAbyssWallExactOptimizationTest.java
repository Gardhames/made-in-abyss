package dev.madeinabyss.kernel.terrain.living;

import org.junit.jupiter.api.Test;
import java.lang.management.ManagementFactory;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class LivingAbyssWallExactOptimizationTest {
    private static volatile int sink;
    @Test void scalarProtrusionWeightsAndMaterialMatchAtRandomPointsAndTableEdges() {
        for(long seed:new long[]{0,20261003,-20261003}) {
            var recipe=LivingAbyssWallTest.recipe(seed);var fast=new LivingAbyssWall(seed,650,recipe.wall());
            var old=new LivingAbyssWallScalarReference(seed,650,recipe.wall());var random=new Random(seed);
            for(int i=0;i<100_000;i++) {
                double theta=random.nextDouble()*2160-1080,depth=random.nextDouble()*4000-1800;int zone=i%5;
                if(i<20)depth=new double[]{-1,-0.0,0,1,1499.999999,1500,1500.000001,1501,1700,4000}[i%10];
                assertEquals(Double.doubleToRawLongBits(old.scalarProtrusion(theta,depth)),Double.doubleToRawLongBits(fast.protrusion(theta,depth)),"protrusion");
                double[] expected=old.weights(zone,theta,depth),actual=fast.weights(zone,theta,depth);
                for(int j=0;j<expected.length;j++)assertEquals(Double.doubleToRawLongBits(expected[j]),Double.doubleToRawLongBits(actual[j]),"weights");
                int x=random.nextInt(3401)-1700,z=random.nextInt(3401)-1700,y=-(int)depth;
                assertEquals(old.material(zone,theta,x,y,z),fast.material(zone,theta,x,y,z));
            }
        }
    }
    @Test void hotMaterialDoesNotAllocateAWeightsArrayPerVoxel() {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().getId();
        var r=LivingAbyssWallTest.recipe(15);var wall=new LivingAbyssWall(15,650,r.wall());int sum=0;
        for(int i=0;i<100_000;i++)sum+=wall.material(i%5,90,-650,-900,0).length();
        long before=bean.getThreadAllocatedBytes(thread);
        for(int i=0;i<100_000;i++)sum+=wall.material(i%5,90,-650,-900,0).length();
        long allocated=bean.getThreadAllocatedBytes(thread)-before;sink=sum;
        System.out.println("MIA_V15_MATERIAL hotBytesPerCall="+(allocated/100_000.0));
        assertTrue(allocated<100_000,"hot material allocated "+allocated+" bytes; weights must use thread-confined scratch");
    }
    @Test void callerOwnedWeightsRemainIndependentOfMaterialScratch() throws Exception {
        var r=LivingAbyssWallTest.recipe(-15);var wall=new LivingAbyssWall(-15,650,r.wall());
        var old=new LivingAbyssWallScalarReference(-15,650,r.wall());
        var retained=wall.weights(0,19,110);var copy=retained.clone();var output=new double[16];java.util.Arrays.fill(output,-1);
        wall.weights(4,20,1300,output);var expected=old.weights(4,20,1300);
        for(int i=0;i<expected.length;i++)assertEquals(Double.doubleToRawLongBits(expected[i]),Double.doubleToRawLongBits(output[i]));
        assertEquals(-1,output[expected.length]);assertThrows(IllegalArgumentException.class,()->wall.weights(0,0,0,new double[0]));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);var results=new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for(int id=0;id<4;id++){int worker=id;results.add(pool.submit(()->{
                var scalar=new LivingAbyssWallScalarReference(-15,650,r.wall());var random=new Random(worker);
                for(int i=0;i<20_000;i++){int zone=i%5,x=random.nextInt(1401)-700,y=-random.nextInt(1400),z=random.nextInt(1401)-700;
                    double theta=LivingAbyssFields.azimuth(x,z);assertEquals(scalar.material(zone,theta,x,y,z),wall.material(zone,theta,x,y,z));}
            }));}
            for(var result:results)result.get();
        }finally{pool.shutdownNow();}
        assertArrayEquals(copy,retained);
    }
}
