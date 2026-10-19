package dev.madeinabyss.kernel;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class SectionAddressTest {
    @Test void negativeDepthUsesFloorDivision() {
        assertEquals(new SectionAddress(-2, -1250, 2, 15, 0, 0),
                SectionAddress.fromBlock(new BlockCoordinate(-17, -20000, 32)));
    }

    @Test void boundariesHaveHandCheckedAddresses() {
        int[] blocks = {-17, -16, -1, 0, 15, 16, 17};
        int[] sections = {-2, -1, -1, 0, 0, 1, 1};
        int[] locals = {15, 0, 15, 0, 15, 0, 1};
        for (int index = 0; index < blocks.length; index++) {
            assertEquals(new SectionAddress(0, sections[index], 0, 0, locals[index], 0),
                    SectionAddress.fromBlock(new BlockCoordinate(0, blocks[index], 0)));
        }
    }

    @Test void sectionLocalAddressReconstructsDeepNegativeBlock() {
        assertEquals(new BlockCoordinate(-17, -20001, 47),
                new SectionAddress(-2, -1251, 2, 15, 15, 15).block());
    }

    @Test void nearbyDepthsNeverAliasAcross4096Blocks() {
        assertNotEquals(SectionAddress.fromBlock(new BlockCoordinate(5, -20000, -7)),
                SectionAddress.fromBlock(new BlockCoordinate(5, -15904, -7)));
    }

    @Test void wholeIntegerRangeRoundTrips() {
        BlockCoordinate minimum = new BlockCoordinate(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        BlockCoordinate maximum = new BlockCoordinate(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(minimum, SectionAddress.fromBlock(minimum).block());
        assertEquals(maximum, SectionAddress.fromBlock(maximum).block());
    }

    @Test void seededRandomCoordinatesRoundTripWithoutMutation() {
        Random random = new Random(0xAB155);
        for (int sample = 0; sample < 10000; sample++) {
            BlockCoordinate position = new BlockCoordinate(random.nextInt(), random.nextInt(), random.nextInt());
            SectionAddress address = SectionAddress.fromBlock(position);
            assertEquals(position, address.block(), "sample=" + sample);
            assertTrue(address.localX() >= 0 && address.localX() < 16);
            assertTrue(address.localY() >= 0 && address.localY() < 16);
            assertTrue(address.localZ() >= 0 && address.localZ() < 16);
        }
    }

    @Test void localCoordinatesOutsideSectionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SectionAddress(0, 0, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SectionAddress(0, 0, 0, 0, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> new SectionAddress(0, 0, 0, 0, 0, 16));
    }

    @Test void reconstructionOutsideBlockRangeFailsInsteadOfWrapping() {
        assertThrows(ArithmeticException.class,
                () -> new SectionAddress(Integer.MAX_VALUE, 0, 0, 0, 0, 0).block());
    }
}
