package dev.madeinabyss.kernel.cube;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class CubeDensityReuseTest {
    @Test void exactSignedCubeReuseAvoidsSamplingEveryOverlappingCell() {
        var key=new CubeKey(-2,-5,-2);var calls=new AtomicInteger();
        CubeDensityReuse.StateSource source=(x,y,z)->{calls.incrementAndGet();return x*10000+y*100+z;};
        var first=CubeDensityReuse.capture(key,7,6,Map.of(),source);
        assertFalse(first.reused());assertEquals(4096,calls.get());
        assertEquals(-32*10000-80*100-32,first.states()[0]);
        assertEquals(-17*10000-65*100-17,first.states()[4095]);
        var original=first.states().clone();calls.set(0);
        var reused=CubeDensityReuse.capture(key,7,7,Map.of(key,first.states()),source);
        assertTrue(reused.reused());assertSame(first.states(),reused.states());assertEquals(0,calls.get());assertArrayEquals(original,reused.states());
        var changed=CubeDensityReuse.capture(key,8,7,Map.of(key,first.states()),(x,y,z)->{calls.incrementAndGet();return 999;});
        assertFalse(changed.reused());assertEquals(4096,calls.get());assertEquals(999,changed.states()[4095]);assertArrayEquals(original,first.states());
    }
    @Test void incompleteOldCubeCannotBeReusedAsAir() {
        var key=new CubeKey(-1,-1,-1);
        assertThrows(IllegalArgumentException.class,()->CubeDensityReuse.capture(key,1,1,Map.of(key,new int[4095]),(x,y,z)->0));
    }
}
