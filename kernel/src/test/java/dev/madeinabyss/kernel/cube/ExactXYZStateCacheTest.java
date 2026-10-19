package dev.madeinabyss.kernel.cube;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExactXYZStateCacheTest {
    @Test void fullSignedTagsRefuseCollisionsIncludingAliasedNegativeDepth() {
        var cache=new ExactXYZStateCache(1);
        cache.put(-1,-4096,-1,0);assertEquals(0,cache.get(-1,-4096,-1));
        assertEquals(-1,cache.get(-1,0,-1));cache.put(-1,0,-1,42);
        assertEquals(-1,cache.get(-1,-4096,-1));assertEquals(42,cache.get(-1,0,-1));
        cache.put(Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,7);
        assertEquals(7,cache.get(Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE));
        assertEquals(-1,cache.get(Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE));
        assertEquals(3,cache.hits());assertEquals(3,cache.misses());
    }
    @Test void zeroIsKnownAirAndCapacityNeverGrows() {
        var cache=new ExactXYZStateCache(64);assertEquals(-1,cache.get(0,0,0));cache.put(0,0,0,0);assertEquals(0,cache.get(0,0,0));
        for(int i=0;i<10000;i++)cache.put(i,-i,i*17,i);assertEquals(64,cache.capacity());
        assertThrows(IllegalArgumentException.class,()->cache.put(0,0,0,-1));
        assertThrows(IllegalArgumentException.class,()->new ExactXYZStateCache(63));
        assertThrows(IllegalArgumentException.class,()->new ExactXYZStateCache(131072));
    }
}
