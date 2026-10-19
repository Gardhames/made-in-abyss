package dev.madeinabyss.kernel.light.gpu;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PackedHeightHaloTest {
    @Test void everyIntegerColumnIsSampledOnceAndDecodedAtSignedBorders(){var calls=new AtomicInteger();var h=PackedHeightHalo.build(-32,-32,64,(x,z)->{calls.incrementAndGet();return-1000+Math.floorMod(x*37+z*11,123);},4);
        assertEquals(4096,calls.get());for(int z=-32;z<32;z++)for(int x=-32;x<32;x++)assertEquals(-1000+Math.floorMod(x*37+z*11,123),h.top(x,z));
        assertEquals(Integer.MIN_VALUE,h.top(-33,0));assertEquals(Integer.MIN_VALUE,h.top(0,32));assertTrue(h.descriptors().isReadOnly());}
    @Test void uniformTilesNeedNoPayloadAndAreNotUnknown(){var h=PackedHeightHalo.build(-16,-16,32,(x,z)->-4100,2);assertEquals(-4100,h.minimumTop());assertEquals(-4100,h.maximumTop());assertEquals(0,h.data().remaining());assertEquals(-4100,h.top(0,0));}
    @Test void allBitWidthsAndCrossWordBoundariesAreExact(){for(int bits=1;bits<=14;bits++){final int mask=(1<<bits)-1;var h=PackedHeightHalo.build(0,0,16,(x,z)->-5000+((x+z*16)*131&mask),2);for(int z=0;z<16;z++)for(int x=0;x<16;x++)assertEquals(-5000+((x+z*16)*131&mask),h.top(x,z));}}
    @Test void unalignedOrOverflowingEnvelopesRefuse(){assertThrows(IllegalArgumentException.class,()->PackedHeightHalo.build(1,0,16,(x,z)->0,1));assertThrows(IllegalArgumentException.class,()->PackedHeightHalo.build(0,0,0,(x,z)->0,1));}
}
