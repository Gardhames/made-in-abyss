package dev.madeinabyss.kernel.light.gpu;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightOwnerCacheTest {
    private static LightOwnerCache.Key key(int x,int y,int z){return new LightOwnerCache.Key(x,y,z,x+1,y+1,z+1,-32);}
    @Test void negativeKeysAndBottomAdmissionStayDistinct(){
        var cache=new LightOwnerCache(4);var a=key(-16,-1,-1);var b=new LightOwnerCache.Key(-16,-1,-1,-15,0,0,-1);
        var leases=cache.reserve(List.of(a,b));assertNotEquals(leases.get(0).id(),leases.get(1).id());
        cache.publish(leases.get(0),13,5,false);cache.publish(leases.get(1),7,0,true);
        assertEquals(13,cache.sky(a).orElseThrow());assertEquals(7,cache.sky(b).orElseThrow());assertEquals(2,cache.size());
        var words=cache.record(leases.get(0).id());assertArrayEquals(new int[]{-16,-1,-1,-15,0,0,-32},Arrays.copyOf(words,7));
        assertEquals(13,words[7]&15);assertEquals(5,(words[7]>>4)&63);assertNotEquals(0,words[7]&LightOwnerCache.VALID);
    }
    @Test void editInvalidatesOnlyNamedValuesAndRetainsTheirKeys(){
        var cache=new LightOwnerCache(4);var a=key(0,0,0);var b=key(512,-10,512);var lease=cache.reserve(List.of(a,b));
        for(var l:lease)cache.publish(l,14,1,false);
        cache.invalidate(new int[]{lease.get(0).id()});assertTrue(cache.sky(a).isEmpty());assertEquals(14,cache.sky(b).orElseThrow());
        assertThrows(IllegalStateException.class,()->cache.publish(lease.get(0),14,1,false));
        var again=cache.reserve(List.of(a));assertEquals(lease.get(0).id(),again.get(0).id());assertFalse(again.get(0).created());
        cache.publish(again.get(0),8,0,false);assertEquals(8,cache.sky(a).orElseThrow());
    }
    @Test void boundedEvictionCannotRetargetAnOutstandingLease(){
        var cache=new LightOwnerCache(2);var a=key(0,0,0);var b=key(1,0,0);var old=cache.reserve(List.of(a,b));
        cache.publish(old.get(0),5,0,true);cache.publish(old.get(1),9,0,true);
        var newer=cache.reserve(List.of(key(2,0,0))).get(0);cache.publish(newer,10,0,true);
        assertTrue(cache.sky(a).isEmpty());assertEquals(9,cache.sky(b).orElseThrow());
        assertThrows(IllegalStateException.class,()->cache.publish(old.get(0),4,0,true));
        assertEquals(10,cache.sky(newer.key()).orElseThrow());assertEquals(2,cache.size());
    }
    @Test void aBatchPinsExistingKeysBeforeEvictingForAnyNewKey(){
        var cache=new LightOwnerCache(2);var a=key(0,0,0);var b=key(1,0,0);var old=cache.reserve(List.of(a,b));
        cache.publish(old.get(0),5,0,true);cache.publish(old.get(1),9,0,true);
        var batch=cache.reserve(List.of(key(2,0,0),a));assertEquals(old.get(0).id(),batch.get(1).id());assertEquals(5,cache.sky(a).orElseThrow());assertTrue(cache.sky(b).isEmpty());
        cache.publish(batch.get(0),12,0,true);assertEquals(12,cache.sky(batch.get(0).key()).orElseThrow());
    }
    @Test void signedCoordinateExtremesAreNeverTruncatedToAShort(){
        var cache=new LightOwnerCache(4);var a=key(Integer.MIN_VALUE+8,-20000,Integer.MAX_VALUE-8);var b=key(8,-20000,-8);
        var leases=cache.reserve(List.of(a,b));cache.publish(leases.get(0),3,0,false);cache.publish(leases.get(1),11,0,false);
        assertEquals(3,cache.sky(a).orElseThrow());assertEquals(11,cache.sky(b).orElseThrow());
    }
    @Test void spatialCandidatesIncludeLocalDirectAndInversePrefixButExcludeDistantSaturatedOwner(){
        long q=1L<<30;var cache=new LightOwnerCache(8);var near=key(0,-8,0);var prefix=key(-8,-8,0);var far=key(2048,-8,2048);
        var leases=cache.reserve(List.of(near,prefix,far));cache.publish(leases.get(0),15,0,true);cache.publish(leases.get(1),4,63,false);cache.publish(leases.get(2),15,0,true);
        int[] ids=cache.candidates(List.of(new Q30EditDependencies.Box(0,0,0,1,1,1)),List.of(new Q30EditDependencies.Point(0,-8,0)),List.of(new Q30EditDependencies.Direction(q,q,0)),32);
        assertTrue(Arrays.stream(ids).anyMatch(i->i==leases.get(0).id()));assertTrue(Arrays.stream(ids).anyMatch(i->i==leases.get(1).id()));assertFalse(Arrays.stream(ids).anyMatch(i->i==leases.get(2).id()));
        cache.clear();assertEquals(0,cache.size());assertTrue(cache.sky(near).isEmpty());
    }
}
