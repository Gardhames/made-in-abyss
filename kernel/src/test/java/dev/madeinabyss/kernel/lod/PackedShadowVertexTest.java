package dev.madeinabyss.kernel.lod;
import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;
class PackedShadowVertexTest {
    @Test void unsignedLocalCoordinatesAndOfficialLightNormalColorLayoutRoundTrip() {
        var b=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        PackedShadowVertex.put(b,-2000000000L,-20000,-17,-1999934465L,-19999,-16,0x7e7b77,11,3,2,5);
        assertEquals(16,b.position());assertEquals(65535,Short.toUnsignedInt(b.getShort(0)));assertEquals(1,b.getShort(2));assertEquals(1,b.getShort(4));
        assertEquals(0x3b,Short.toUnsignedInt(b.getShort(6)));assertEquals(0x7e,Byte.toUnsignedInt(b.get(8)));
        assertEquals(0x7b,Byte.toUnsignedInt(b.get(9)));assertEquals(0x77,Byte.toUnsignedInt(b.get(10)));assertEquals(255,Byte.toUnsignedInt(b.get(11)));
        assertEquals(2,b.get(12));assertEquals(5,b.get(13));assertEquals(0,b.getShort(14));
    }
    @Test void coordinateOverflowAndInvalidLightRefuseBeforeWriting() {
        var b=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        assertThrows(IllegalArgumentException.class,()->PackedShadowVertex.put(b,-17,-64,-17,-18,-64,-17,0,0,0,2,0));
        assertThrows(IllegalArgumentException.class,()->PackedShadowVertex.put(b,-17,-64,-17,65519,-64,-17,0,0,0,2,0));
        assertThrows(IllegalArgumentException.class,()->PackedShadowVertex.put(b,0,0,0,0,0,0,0,16,0,2,0));assertEquals(0,b.position());
        assertArrayEquals(new int[]{4,5,0,1,2,3},new int[]{PackedShadowVertex.normalIndex(0,false),PackedShadowVertex.normalIndex(0,true),
            PackedShadowVertex.normalIndex(1,false),PackedShadowVertex.normalIndex(1,true),PackedShadowVertex.normalIndex(2,false),PackedShadowVertex.normalIndex(2,true)});
    }
}
