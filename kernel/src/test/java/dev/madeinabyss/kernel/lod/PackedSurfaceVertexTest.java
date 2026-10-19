package dev.madeinabyss.kernel.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackedSurfaceVertexTest {
    @Test void transparentWaterAlphaAndOpaqueRgbaUseTheOfficialLayout(){
        var buffer=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        PackedSurfaceVertex.put(buffer,-32,-20000,-17,-16,-19984,-1,0xb42a64bf,11,3,5,1);
        PackedSurfaceVertex.put(buffer,-32,-20000,-17,-16,-19984,-1,0xff7e7b77,15,0,2,5);
        assertEquals(32,buffer.position());assertEquals(180,Byte.toUnsignedInt(buffer.get(11)),"WATER must not silently become opaque");
        assertEquals(255,Byte.toUnsignedInt(buffer.get(27)));assertEquals(0x2a,Byte.toUnsignedInt(buffer.get(8)));
        assertEquals(0x64,Byte.toUnsignedInt(buffer.get(9)));assertEquals(0xbf,Byte.toUnsignedInt(buffer.get(10)));
        assertEquals(0x3b,Short.toUnsignedInt(buffer.getShort(6)));assertEquals(5,Byte.toUnsignedInt(buffer.get(12)));
        assertEquals(1,Byte.toUnsignedInt(buffer.get(13)));assertEquals(0,buffer.getShort(14));
    }
    @Test void negativeGlobalAnchorsAndUnsignedLimitsNeverWrapOrPartiallyWrite(){
        var buffer=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        PackedSurfaceVertex.put(buffer,-2000000000L,-20000,-17,-1999934465L,45535,65518,0xff010203,0,15,255,5);
        assertEquals(65535,Short.toUnsignedInt(buffer.getShort(0)));assertEquals(65535,Short.toUnsignedInt(buffer.getShort(2)));
        assertEquals(65535,Short.toUnsignedInt(buffer.getShort(4)));assertEquals(0xf0,Short.toUnsignedInt(buffer.getShort(6)));
        buffer.clear();assertThrows(IllegalArgumentException.class,()->PackedSurfaceVertex.put(buffer,0,0,0,0,-1,0,0,0,0,2,0));
        assertThrows(IllegalArgumentException.class,()->PackedSurfaceVertex.put(buffer,0,0,0,0,65536,0,0,0,0,2,0));
        assertThrows(IllegalArgumentException.class,()->PackedSurfaceVertex.put(buffer,0,0,0,0,0,0,0,16,0,2,0));assertEquals(0,buffer.position());
    }
}
