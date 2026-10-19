package dev.madeinabyss.kernel.lod;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class T9SurfaceUploadCursorTest {
    @Test void elapsedBudgetStopsInsideAChunkBeforeAnyFurtherVertexOrIndex() {
        var now=new AtomicLong();var vertices=new ArrayList<Integer>();var indices=new ArrayList<Integer>();
        var cursor=new SurfaceUploadCursor(4096,4102,i->i%2==0);
        assertFalse(cursor.advance(()->now.get()<3,i->{vertices.add(i);now.incrementAndGet();},indices::add));
        assertEquals(java.util.List.of(4096,4097,4098),vertices);
        assertTrue(indices.isEmpty());
        assertFalse(cursor.advance(()->false,vertices::add,indices::add));
        assertEquals(3,vertices.size());
        while(!cursor.done()){
            long end=now.get()+2;
            cursor.advance(()->now.get()<end,i->{vertices.add(i);now.incrementAndGet();},i->{indices.add(i);now.incrementAndGet();});
        }
        assertEquals(java.util.List.of(4096,4097,4098,4099,4100,4101),vertices);
        assertEquals(java.util.List.of(4,5,6,6,7,4,12,13,14,14,15,12,20,21,22,22,23,20,
                0,1,2,2,3,0,8,9,10,10,11,8,16,17,18,18,19,16),indices);
        assertEquals(3,cursor.opaque());assertEquals(3,cursor.water());
    }
    @Test void arbitraryYieldBoundariesKeepTheFrozenVertexAndOpaqueThenWaterIndexBytes() {
        var random=new Random(904567123L);
        for(int trial=0;trial<240;trial++){
            int start=(trial%4)*4096,n=trial==0?4096:1+random.nextInt(4096);boolean[] water=new boolean[n];
            for(int i=0;i<n;i++)water[i]=random.nextBoolean();
            var expected=ByteBuffer.allocate(n*76).order(ByteOrder.nativeOrder());
            // Frozen pre-T9 order: all vertices, then all opaque indices, then all water indices.
            for(int i=start;i<start+n;i++)for(int k=0;k<16;k++)expected.putInt(i*31+k);
            for(int pass=0;pass<2;pass++)for(int i=start;i<start+n;i++){
                if(water[i-start]!=(pass==1))continue;int v=(i-start)*4;
                for(int index:new int[]{v,v+1,v+2,v+2,v+3,v})expected.putShort((short)index);
            }
            var actual=ByteBuffer.allocate(n*76).order(ByteOrder.nativeOrder());
            var cursor=new SurfaceUploadCursor(start,start+n,i->water[i-start],new int[]{1,16,32,64}[trial%4]);
            while(!cursor.done()){
                var permits=new AtomicLong(1+random.nextInt(129));
                cursor.advance(()->permits.getAndDecrement()>0,i->{for(int k=0;k<16;k++)actual.putInt(i*31+k);},i->actual.putShort((short)i));
            }
            assertArrayEquals(expected.array(),actual.array(),"transaction "+trial);
            assertEquals(n,cursor.opaque()+cursor.water());
        }
    }
    @Test void oneSlowCallbackCannotAdmitAnotherAndTheNextFrameContinuesInOrder() {
        var now=new AtomicLong();var seen=new ArrayList<Integer>();
        var cursor=new SurfaceUploadCursor(0,4,i->false);
        cursor.advance(()->now.get()<3,i->{seen.add(i);now.addAndGet(7);},i->fail());
        assertEquals(java.util.List.of(0),seen);assertFalse(cursor.done());
        cursor.advance(()->true,seen::add,i->{});
        assertEquals(java.util.List.of(0,1,2,3),seen);assertTrue(cursor.done());
    }
    @Test void emptyRangeAndInvalidRangesHaveExplicitBehavior() {
        var empty=new SurfaceUploadCursor(0,0,i->false);
        assertTrue(empty.advance(()->true,i->fail(),i->fail()));
        assertThrows(IllegalArgumentException.class,()->new SurfaceUploadCursor(-1,1,i->false));
        assertThrows(IllegalArgumentException.class,()->new SurfaceUploadCursor(5,4,i->false));
        assertThrows(IllegalArgumentException.class,()->new SurfaceUploadCursor(0,4097,i->false));
        assertThrows(IllegalArgumentException.class,()->new SurfaceUploadCursor(0,10,i->false,0));
        assertThrows(IllegalArgumentException.class,()->new SurfaceUploadCursor(0,10,i->false,65));
    }
    @Test void boundedBatchesYieldInsideTheChunkAndNeverSpendAnExtraBatchAfterDeadline() {
        var now=new AtomicLong();var vertices=new ArrayList<Integer>();var indices=new ArrayList<Integer>();
        var cursor=new SurfaceUploadCursor(0,4096,i->false,32);
        assertFalse(cursor.advance(()->now.get()<35,i->{vertices.add(i);now.incrementAndGet();},indices::add));
        assertEquals(64,vertices.size());assertTrue(indices.isEmpty());
        assertTrue(now.get()-35<32,"one CPU face quantum is the maximum nonpreemptible overrun");
        cursor.advance(()->true,vertices::add,indices::add);
        assertEquals(4096,vertices.size());assertEquals(4096*6,indices.size());
        assertEquals(0,vertices.get(0));assertEquals(4095,vertices.get(4095));
    }
}
