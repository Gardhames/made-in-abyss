package dev.madeinabyss.kernel.light;
import dev.madeinabyss.kernel.concurrent.BoundedOrderedWork;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
class AlignedVoxelSlabsTest {
    @Test void signedPartialCubeBoundariesHaveExactlyOneOwner() {
        for(int minimum:new int[]{-1286,-840,-17,-1,0,17})for(int size:new int[]{1,15,16,31,384}) {
            var slabs=AlignedVoxelSlabs.plan(minimum,size,4);int cursor=minimum;
            for(int i=0;i<slabs.size();i++) {
                var s=slabs.get(i);assertEquals(cursor,s.minimum());assertTrue(s.limit()>s.minimum());
                if(i>0)assertEquals(0,Math.floorMod(s.minimum(),16));cursor=s.limit();
            }
            assertEquals(minimum+size,cursor);
            for(int y=minimum;y<minimum+size;y++) {int yy=y;assertEquals(1,slabs.stream().filter(s->yy>=s.minimum()&&yy<s.limit()).count());}
            for(int cube=Math.floorDiv(minimum,16)+1;(long)cube*16+15<minimum+size;cube++) {
                int y=cube*16;assertEquals(1,slabs.stream().filter(s->y>=s.minimum()&&y+15<s.limit()).count());
            }
        }
    }
    @Test void fourWorkerDensityAndDirectSeedsEqualSequentialAtNegativePartialBounds() {
        int minY=-840,sx=32,sy=384,sz=32;var serial=new int[sx*sy*sz];var parallel=new int[serial.length];
        var opacitySerial=new byte[serial.length];var opacityParallel=new byte[serial.length];
        for(int y=minY;y<minY+sy;y++)fill(y,minY,sx,sz,serial,opacitySerial);
        var pool=Executors.newFixedThreadPool(4);
        try {BoundedOrderedWork.map(AlignedVoxelSlabs.plan(minY,sy,4),4,pool,slab->{for(int y=slab.minimum();y<slab.limit();y++)fill(y,minY,sx,sz,parallel,opacityParallel);return true;});}
        finally {pool.shutdownNow();}
        assertArrayEquals(serial,parallel);assertArrayEquals(opacitySerial,opacityParallel);
        var skySerial=new byte[serial.length];var skyParallel=new byte[serial.length];
        for(int column=0;column<sx*sz;column++)DirectSkyColumnSeed.fill(opacitySerial,skySerial,column,sx*sz,sy,15);
        var directPool=Executors.newFixedThreadPool(4);
        try {BoundedOrderedWork.map(AlignedVoxelSlabs.plan(0,sz,4),4,directPool,slab->{for(int z=slab.minimum();z<slab.limit();z++)for(int x=0;x<sx;x++)DirectSkyColumnSeed.fill(opacityParallel,skyParallel,x+sx*z,sx*sz,sy,15);return true;});}
        finally {directPool.shutdownNow();}
        assertArrayEquals(skySerial,skyParallel);
        assertThrows(IllegalArgumentException.class,()->AlignedVoxelSlabs.plan(Integer.MAX_VALUE-1,4,4));
    }
    private static void fill(int y,int minY,int sx,int sz,int[] states,byte[] opacity) {
        for(int z=0;z<sz;z++)for(int x=0;x<sx;x++){int i=x+sx*(z+sz*(y-minY));states[i]=31*(x-24)+17*y+(z-24);opacity[i]=(byte)Math.floorMod(states[i],16);}
    }
}
