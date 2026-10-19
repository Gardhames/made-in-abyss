package dev.madeinabyss.kernel.light;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagedOpacityCacheTest {
    @Test void symmetricSkyPageLatticeUsesAvailableCacheCapacity() {
        var cache=new PagedOpacityCache(32768);
        for(int y=-8;y<=8;y++)for(int z=-16;z<=16;z++)for(int x=-16;x<=16;x++)cache.put(x*8,y*8,z*8,0);
        assertTrue(cache.residentPages()>13500,"18513 distinct symmetric pages must not collapse into signed XOR pairs");
        int retained=0;
        for(int y=-8;y<=8;y++)for(int z=-16;z<=16;z++)for(int x=-16;x<=16;x++)if(cache.get(x*8,y*8,z*8)==0)retained++;
        assertEquals(cache.residentPages(),retained);assertTrue(cache.evictions()<5013);
    }
    @Test void expandedCapacityIsExplicitAndKeepsLegacyDefault() {
        var cache=new PagedOpacityCache(32768);
        assertEquals(32768,cache.capacity());assertEquals(0,cache.residentPages());
        assertEquals(32768L*12,cache.residentBytes());
        assertEquals(4096,new PagedOpacityCache().capacity());
        assertThrows(IllegalArgumentException.class,()->new PagedOpacityCache(65536));
    }
    @Test void expandedCachePreservesFullCellsAtNegativeAndExtremePageCoordinates() {
        var cache=new PagedOpacityCache(32768);
        int[] starts={Integer.MIN_VALUE,Integer.MIN_VALUE+8,-20008,-4104,-16,-8,0,8,Integer.MAX_VALUE-7};
        for(int start:starts){
            for(int y=0;y<8;y++)for(int z=0;z<8;z++)for(int x=0;x<8;x++)
                cache.put(start+x,start+y,start+z,(x+3*y+7*z)&15);
            for(int y=0;y<8;y++)for(int z=0;z<8;z++)for(int x=0;x<8;x++)
                assertEquals((x+3*y+7*z)&15,cache.get(start+x,start+y,start+z));
            int other=start==Integer.MIN_VALUE?Integer.MAX_VALUE:start-1;
            assertEquals(-1,cache.get(other,start,start),"Adjacent full signed tag must not alias this page");
        }
        assertTrue(cache.residentPages()<=starts.length);assertEquals(starts.length*512,cache.hits());
    }
    @Test void expandedCacheCollisionEvictsEntirePageAndAdmitsReplacement() {
        var cache=new PagedOpacityCache(32768);
        cache.put(-8,-16,-24,9);cache.put(-7,-16,-24,13);
        boolean found=false;int replacement=0;
        // Black-box collision search: never duplicate the production hash in the test.
        for(int i=1;i<=500000;i++){
            replacement=i*8;cache.put(replacement,88,-128,7);
            if(cache.get(-8,-16,-24)==-1){found=true;break;}
        }
        assertTrue(found,"A bounded direct-mapped page must eventually collide");
        assertEquals(-1,cache.get(-7,-16,-24),"All old cells disappear together");
        assertEquals(7,cache.get(replacement,88,-128));
        assertEquals(-1,cache.get(replacement+1,88,-128),"New unvisited cells stay unknown");
        cache.put(replacement+1,88,-128,0);assertEquals(0,cache.get(replacement+1,88,-128));
        assertTrue(cache.evictions()>0);assertTrue(cache.residentPages()<=32768);
    }
    @Test void expandedCacheSaturatesWithinPayloadBoundAndNewHotPageStillHits() {
        var cache=new PagedOpacityCache(32768);
        long bound=32768L*(12+512);
        for(int i=0;i<500000;i++){
            int x=-8*i,y=-8*(i%2048),z=8*(i%8192);cache.put(x,y,z,i&15);
            assertEquals(i&15,cache.get(x,y,z));
        }
        assertTrue(cache.evictions()>400000);assertTrue(cache.residentPages()>32000);
        assertTrue(cache.residentPages()<=32768);assertTrue(cache.residentBytes()<=bound);
        assertEquals(17_170_432L,bound);
        cache.put(Integer.MAX_VALUE,Integer.MIN_VALUE,-20000,15);long evictions=cache.evictions();
        for(int i=0;i<1000;i++)assertEquals(15,cache.get(Integer.MAX_VALUE,Integer.MIN_VALUE,-20000));
        assertEquals(evictions,cache.evictions());assertTrue(cache.residentBytes()<=bound);
    }
    @Test void readsAreLazyAndUnknownDoesNotBecomeAir() {
        var cache=new PagedOpacityCache(4);
        long emptyBytes=cache.residentBytes();
        for(int i=0;i<100;i++)assertEquals(-1,cache.get(i,-i,i));
        assertEquals(0,cache.residentPages());assertEquals(emptyBytes,cache.residentBytes());
        cache.put(-1,-1,-1,0);assertEquals(0,cache.get(-1,-1,-1));
        assertEquals(-1,cache.get(-2,-1,-1));assertEquals(1,cache.residentPages());
        assertEquals(emptyBytes+512,cache.residentBytes());
        assertThrows(IllegalArgumentException.class,()->cache.put(8,8,8,-1));
        assertThrows(IllegalArgumentException.class,()->cache.put(8,8,8,16));
        assertEquals(1,cache.residentPages());assertEquals(-1,cache.get(8,8,8));
    }
    @Test void collidingPagesClearAllOldCellsAndLastPageCannotAlias() {
        var cache=new PagedOpacityCache(1);
        cache.put(-1,-4096,-1,15);cache.put(-2,-4096,-1,7);
        assertEquals(15,cache.get(-1,-4096,-1));
        cache.put(-1,0,-1,0);assertEquals(0,cache.get(-1,0,-1));
        assertEquals(-1,cache.get(-2,0,-1));assertEquals(-1,cache.get(-1,-4096,-1));
        assertEquals(1,cache.evictions());assertEquals(1,cache.residentPages());
        for(int i=0;i<10000;i++)cache.put(i*8,i*8,i*8,i%16);
        assertEquals(1,cache.residentPages());assertEquals(1,cache.capacity());
    }
    @Test void allLocalCellsRemainDistinctAcrossNegativePageAxes() {
        var cache=new PagedOpacityCache(1);
        for(int y=0;y<8;y++)for(int z=0;z<8;z++)for(int x=0;x<8;x++)
            cache.put(x-8,y-16,z-24,(x+3*y+7*z)%16);
        for(int y=0;y<8;y++)for(int z=0;z<8;z++)for(int x=0;x<8;x++)
            assertEquals((x+3*y+7*z)%16,cache.get(x-8,y-16,z-24));
        assertEquals(512,cache.hits());assertEquals(0,cache.misses());assertEquals(0,cache.evictions());
        assertEquals(-1,cache.get(0,-16,-24));assertEquals(1,cache.misses());
    }
    @Test void negativePageEdgesAndFullSignedExtremesKeepExactTags() {
        var cache=new PagedOpacityCache(1);
        int[] coordinates={Integer.MIN_VALUE,Integer.MIN_VALUE+7,-9,-8,-1,0,7,8,Integer.MAX_VALUE-7,Integer.MAX_VALUE};
        for(int coordinate:coordinates) {
            cache.put(coordinate,coordinate,coordinate,9);
            assertEquals(9,cache.get(coordinate,coordinate,coordinate));
            int other=coordinate==Integer.MAX_VALUE?Integer.MIN_VALUE:coordinate+1;
            assertEquals(-1,cache.get(other,coordinate,coordinate));
        }
        assertThrows(IllegalArgumentException.class,()->new PagedOpacityCache(0));
        assertThrows(IllegalArgumentException.class,()->new PagedOpacityCache(3));
        assertThrows(IllegalArgumentException.class,()->new PagedOpacityCache(65536));
        assertEquals(4096,new PagedOpacityCache().capacity());
    }
    @Test void cachedThirtyTwoRayOracleKeepsThinRoofPartialOpacityAndSignedTranslation() {
        for(int shift:new int[]{0,-4096}) {
            var cache=new PagedOpacityCache();var samples=new AtomicInteger();
            SkyVisibility.VoxelOpacity oracle=(x,y,z)->y==shift-8&&x<shift-3?15:y==shift?5:0;
            SkyVisibility.VoxelOpacity cached=(x,y,z)->{
                int known=cache.get(x,y,z);if(known>=0)return known;
                samples.incrementAndGet();int sampled=oracle.opacity(x,y,z);cache.put(x,y,z,sampled);return sampled;
            };
            double expected=SkyVisibility.visibleFraction(shift-7,shift-24,shift-9,shift+16,10000,oracle);
            double actual=SkyVisibility.visibleFraction(shift-7,shift-24,shift-9,shift+16,10000,cached);
            assertTrue(expected>0&&expected<1);assertEquals(expected,actual);
            int cold=samples.get();assertTrue(cold>0);
            assertEquals(expected,SkyVisibility.visibleFraction(shift-7,shift-24,shift-9,shift+16,10000,cached));
            assertTrue(samples.get()-cold<cold,"Second identical trace must reuse actual sampled opacity");
            assertTrue(cache.hits()>0);assertTrue(cache.misses()>0);
        }
    }
    @Test void failedUnknownLookupRemainsUnknownAndRayFailureIsPreserved() {
        var cache=new PagedOpacityCache();var unknown=new IllegalStateException("UNKNOWN saved boundary");
        SkyVisibility.VoxelOpacity cached=(x,y,z)->{
            int known=cache.get(x,y,z);if(known>=0)return known;
            if(y==-4)throw unknown;
            cache.put(x,y,z,0);return 0;
        };
        assertSame(unknown,assertThrows(IllegalStateException.class,()->SkyVisibility.visibleFraction(-1,-4,-1,8,100,cached)));
        assertEquals(-1,cache.get(-1,-4,-1));assertEquals(0,cache.residentPages());
        assertThrows(IllegalStateException.class,()->SkyVisibility.visibleFraction(-1,-8,-1,8,1,cached));
    }
}
