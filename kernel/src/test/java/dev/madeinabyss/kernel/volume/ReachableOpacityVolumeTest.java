package dev.madeinabyss.kernel.volume;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.SkyVisibility;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ReachableOpacityVolumeTest {
    private static final CubeKey ZERO=new CubeKey(0,0,0);
    private ReachableOpacityVolume build(SparseVoxelVolume.Producer producer) {
        return ReachableOpacityVolume.build(new CubeKey(-4,-4,-4),new CubeKey(4,4,4),ZERO::equals,producer,4,Duration.ofSeconds(10),()->{});
    }
    @Test void diagonalDdaContactMustOpenTheDiagonalBrick() {
        var diagonal=new CubeKey(1,0,1);
        SkyVisibility.VoxelOpacity exact=(x,y,z)->new CubeKey(x>>4,y>>4,z>>4).equals(ZERO)||new CubeKey(x>>4,y>>4,z>>4).equals(diagonal)?0:15;
        assertTrue(SkyVisibility.isOpenSegment(15,0,15,16,0,16,8,exact));
        var volume=build((key,check)->VoxelBrick.uniform(-1,key.equals(diagonal)?0:15));
        assertTrue(volume.reachable(diagonal));assertEquals(0,volume.opacityOrUnknown(16,0,16));
    }
    @Test void partialOpacityAndZeroOpacityNonAirRemainTraversable() {
        var water=new CubeKey(1,0,0);var glass=new CubeKey(2,0,0);var beyond=new CubeKey(3,0,0);
        var volume=build((key,check)->VoxelBrick.uniform(-1,key.equals(water)?1:key.equals(glass)||key.equals(beyond)?0:15));
        assertTrue(volume.reachable(beyond));assertEquals(1,volume.opacityOrUnknown(16,0,0));assertEquals(0,volume.opacityOrUnknown(32,0,0));
    }
    @Test void enclosedCavityIsUnavailableNotFakeOpaqueOrAir() {
        var cavity=new CubeKey(3,0,0);var calls=new AtomicInteger();
        var volume=build((key,check)->{if(key.equals(cavity))calls.incrementAndGet();return VoxelBrick.uniform(-1,key.equals(cavity)?0:15);});
        assertFalse(volume.reachable(cavity));assertEquals(-1,volume.opacityOrUnknown(48,0,0));assertEquals(0,calls.get());
        assertEquals(-1,volume.opacityOrUnknown(1000,0,0));
    }
    @Test void externalBoundaryIsASeedForLightArrivingOutsideTheBox() {
        var boundary=new CubeKey(-4,2,0);var interior=new CubeKey(-3,2,0);
        var volume=build((key,check)->VoxelBrick.uniform(-1,key.equals(boundary)||key.equals(interior)?0:15));
        assertTrue(volume.reachable(interior));assertEquals(0,volume.opacityOrUnknown(-48,32,0));
    }
    @Test void eachSignedBrickIsProducedAtMostOnceAcrossWorkers() {
        var counts=new ConcurrentHashMap<CubeKey,AtomicInteger>();
        var volume=build((key,check)->{assertTrue(counts.computeIfAbsent(key,k->new AtomicInteger()).incrementAndGet()==1);return VoxelBrick.uniform(-1,0);});
        assertTrue(volume.reachable(new CubeKey(-3,-3,-3)));assertEquals(0,volume.opacityOrUnknown(-33,-33,-33));
        assertEquals(729L,volume.status().get("completedBricks"));
    }
    @Test void unknownCannotPublishAReachabilitySeal() {
        assertThrows(IllegalArgumentException.class,()->build((key,check)->VoxelBrick.uniform(-1,-1)));
    }
    @Test void cancellationCannotPublishAReachabilitySeal() {
        assertThrows(CancellationException.class,()->ReachableOpacityVolume.build(new CubeKey(-4,-4,-4),new CubeKey(4,4,4),ZERO::equals,(key,check)->VoxelBrick.uniform(-1,0),4,Duration.ofSeconds(10),()->{throw new CancellationException();}));
    }
    @Test void coordinatorRechecksSourceAuthorityBeforePublishingTheSeal() {
        Thread coordinator=Thread.currentThread();var produced=new AtomicBoolean();
        assertThrows(CancellationException.class,()->ReachableOpacityVolume.build(new CubeKey(-1,-1,-1),new CubeKey(1,1,1),ZERO::equals,
            (key,check)->{produced.set(true);return VoxelBrick.uniform(-1,15);},4,Duration.ofSeconds(10),()->{
                if(Thread.currentThread()==coordinator&&produced.get())throw new CancellationException("source retired before publication");
            }));
    }
}
