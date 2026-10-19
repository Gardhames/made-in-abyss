package dev.madeinabyss.kernel.light;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkyColumnKeyTest {
    @Test void signedNativeWindowColumnsHaveDistinctDistributedHashes() {
        var hashes=new java.util.HashSet<Integer>();int count=0;
        for(int x=-104;x<104;x++)for(int z=-104;z<104;z++){
            hashes.add(new SkyColumnKey(x,z).hashCode());count++;
        }
        assertTrue(hashes.size()>count*.999,"PackedLongXOR and default31record hashes collapse real native XZ columns");
    }
    @Test void fullSignedCoordinatesRetainExistingDiskPackedRepresentation() {
        int[] coordinates={Integer.MIN_VALUE,Integer.MIN_VALUE+1,-20000,-1,0,1,20000,Integer.MAX_VALUE};
        var keys=new java.util.HashSet<SkyColumnKey>();
        for(int x:coordinates)for(int z:coordinates){
            var key=new SkyColumnKey(x,z);long packed=((long)x<<32)|(z&0xffffffffL);
            assertEquals(packed,key.packed());assertEquals(key,SkyColumnKey.fromPacked(packed));keys.add(key);
        }
        assertEquals(coordinates.length*coordinates.length,keys.size());
        assertNotEquals(new SkyColumnKey(-1,0),new SkyColumnKey(0,-1));
    }
    @Test void concurrentColumnMapRetainsEverySignedExposureValue() throws Exception {
        var values=new java.util.concurrent.ConcurrentHashMap<SkyColumnKey,Long>();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int worker=0;worker<4;worker++){
                int id=worker;futures.add(pool.submit(()->{
                    for(int x=-104+id;x<104;x+=4)for(int z=-104;z<104;z++){
                        var key=new SkyColumnKey(x,z);values.put(key,key.packed());
                    }
                }));
            }
            for(var future:futures)future.get(5,java.util.concurrent.TimeUnit.SECONDS);
            for(int x=-104;x<104;x++)for(int z=-104;z<104;z++)assertEquals(new SkyColumnKey(x,z).packed(),values.get(new SkyColumnKey(x,z)));
            assertEquals(43264,values.size());
        }finally {pool.shutdownNow();}
    }
}
