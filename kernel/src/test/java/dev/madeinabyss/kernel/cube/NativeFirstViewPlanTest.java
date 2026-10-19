package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.light.VoxelSkyLight;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeFirstViewPlanTest {
    @Test void relocationNearCoversExactlyThreeCubesWithSixteenMetreSolveHalo() {
        var p=NativeFirstViewPlan.near(4,-.01,-330.2,500.1,-1424,320,EPOCH,1_000_000).orElseThrow();
        assertEquals(new VoxelSkyLight.Bounds(-32,-352,480,48,48,48),p.drawBounds());
        assertEquals(new VoxelSkyLight.Bounds(-48,-368,464,80,80,80),p.solveBounds());
        assertEquals(512_000,p.cells());
        assertTrue(p.drawBounds().minX()<=-17&&p.drawBounds().minX()+p.drawBounds().sizeX()>15);
    }
    private static final long EPOCH = 19;

    @Test void boundedNearSolvePreservesSignedCubeAnchorsAndRequestedDistance() {
        var candidate = NativeFirstViewPlan.near(4, -269.153355, -337.365774, 405.973425,
                -5000, 320, EPOCH, 884_736);
        assertTrue(candidate.isPresent(), "known signed 80-cubed halo must admit a relocated first view");
        var near = candidate.orElseThrow();
        assertEquals(new VoxelSkyLight.Bounds(-288, -368, 384, 48, 48, 48), near.drawBounds());
        assertEquals(new VoxelSkyLight.Bounds(-304, -384, 368, 80, 80, 80), near.solveBounds());
        assertEquals(64, near.requestedRadius());
        assertEquals(24, near.effectiveRadius());
        assertEquals(NativeFirstViewPlan.Stage.NEAR, near.stage());
        assertEquals(512_000L, near.cells());
        var full = NativeRenderDistance.plan(4, -269.153355, -337.365774, 405.973425, -5000, 320);
        assertEquals(14_155_776L, full.cells());
        assertTrue(full.cells()>near.cells()*27);
        assertTrue(near.peakBytes() < full.peakBytes());

        for (double x : new double[]{-16.001, -16, -.001, 0, 15.999, 16}) {
            var p = NativeFirstViewPlan.near(4, x, -337.5, x, -5000, 320, EPOCH, 884_736).orElseThrow();
            int expectedAnchor = x < -16 ? -32 : x < 0 ? -16 : x < 16 ? 0 : 16;
            assertEquals(expectedAnchor - 16, p.drawBounds().minX());
            assertEquals(expectedAnchor - 16, p.drawBounds().minZ());
        }
        assertTrue(NativeFirstViewPlan.near(4, (double) Integer.MIN_VALUE + 48, -337, 0,
                -5000, 320, EPOCH, 884_736).isPresent());
        assertTrue(NativeFirstViewPlan.near(4, (double) Integer.MAX_VALUE - 47, -337, 0,
                -5000, 320, EPOCH, 884_736).isPresent());
    }

    @Test void insufficientDomainBudgetAndSignedRoomRefuseRatherThanClipOrWrap() {
        assertTrue(NativeFirstViewPlan.near(4, 0, -19970, 0, -20000, 320, EPOCH, 884_736).isEmpty());
        assertTrue(NativeFirstViewPlan.near(4, 0, 300, 0, -5000, 320, EPOCH, 884_736).isEmpty());
        assertTrue(NativeFirstViewPlan.near(4, 0, -337, 0, -5000, 320, EPOCH, 511_999).isEmpty());
        assertTrue(NativeFirstViewPlan.near(4, 0, -337, 0, -5000, 320, EPOCH, 0).isEmpty());
        assertTrue(NativeFirstViewPlan.near(4, 0, -337, 0, -5000, 320, EPOCH, Integer.MAX_VALUE).isEmpty());
        assertTrue(NativeFirstViewPlan.near(0, 0, -337, 0, -5000, 320, EPOCH, 884_736).isEmpty());
        assertTrue(NativeFirstViewPlan.near(1, 0, -337, 0, -5000, 320, EPOCH, 884_736).isEmpty(),
                "fixed 64-cubed DRAW must not exceed the requested 16-metre target");
        assertTrue(NativeFirstViewPlan.near(129, 0, -337, 0, -5000, 320, EPOCH, 884_736).isEmpty());
        for (double x : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Integer.MIN_VALUE, Integer.MAX_VALUE, (double) Integer.MAX_VALUE + 1}) {
            assertTrue(NativeFirstViewPlan.near(4, x, -337, 0, -5000, 320, EPOCH, 884_736).isEmpty());
            assertTrue(NativeFirstViewPlan.near(4, 0, -337, x, -5000, 320, EPOCH, 884_736).isEmpty());
        }
        assertTrue(NativeFirstViewPlan.near(4, 0, Double.NaN, 0, -5000, 320, EPOCH, 884_736).isEmpty());
        assertTrue(NativeFirstViewPlan.near(4, 0, Integer.MIN_VALUE, 0,
                Integer.MIN_VALUE, Integer.MAX_VALUE, EPOCH, 884_736).isEmpty());
        assertTrue(NativeFirstViewPlan.near(4, 0, Integer.MAX_VALUE - 1, 0,
                Integer.MIN_VALUE, Integer.MAX_VALUE, EPOCH, 884_736).isEmpty());
    }

    @Test void actualMixedNearSkyAndBlockMatchLargerSolveAtEveryDrawAndFaceSample() {
        var plan = fixturePlan();
        var near = solveMixed(plan.solveBounds());
        var larger = solveMixed(new VoxelSkyLight.Bounds(-336, -416, 336, 128, 128, 128));
        var d = plan.drawBounds();
        int skyMask = 0, blockMask = 0, checked = 0;
        for (int y = d.minY() - 1; y <= d.minY() + d.sizeY(); y++) {
            for (int z = d.minZ() - 1; z <= d.minZ() + d.sizeZ(); z++) {
                for (int x = d.minX() - 1; x <= d.minX() + d.sizeX(); x++) {
                    int sky = near.sky().value(x, y, z).orElseThrow();
                    int block = near.block().value(x, y, z).orElseThrow();
                    assertEquals(larger.sky().value(x, y, z).orElseThrow(), sky,
                            "SKY at " + x + "," + y + "," + z);
                    assertEquals(larger.block().value(x, y, z).orElseThrow(), block,
                            "BLOCK at " + x + "," + y + "," + z);
                    skyMask |= 1 << sky;
                    blockMask |= 1 << block;
                    checked++;
                }
            }
        }
        assertEquals(125_000, checked);
        assertTrue((skyMask & 1) != 0 && (skyMask & (1 << 15)) != 0,
                "fixture must include obstructed and exposed global columns");
        assertTrue((blockMask & 1) != 0 && (blockMask & (1 << 15)) != 0
                && (blockMask & 0x7ffe) != 0, "fixture must include emission and attenuation");
        assertTrue(NativeFirstViewPlan.certify(plan, EPOCH, near.sky(), near.block(),
                (x, y, z) -> mixedState(x, y, z) >= 0).isPresent(),
                "a complete interior with independent larger-solve equality must be admitted");
    }

    @Test void uncertainFaceUnknownWaterSupportStaleEpochAndWrongSolveNeverCertify() {
        var p = fixturePlan();
        var b = p.solveBounds();
        int cells = VoxelSkyLight.cellCount(b, 884_736);
        byte[] lo = new byte[cells], hi = new byte[cells];
        var exact = VoxelSkyLight.restore(b, lo, hi, cells);
        var d = p.drawBounds();
        hi[index(b, d.minX() - 1, -350, 400)] = 1;
        var uncertain = VoxelSkyLight.restore(b, lo, hi, cells);
        assertTrue(NativeFirstViewPlan.certify(p, EPOCH, uncertain, exact, (x, y, z) -> true).isEmpty());
        assertTrue(NativeFirstViewPlan.certify(p, EPOCH, exact, uncertain, (x, y, z) -> true).isEmpty());
        assertTrue(NativeFirstViewPlan.certify(p, EPOCH, exact, exact,
                (x, y, z) -> !(x == d.minX() - 2 && y == -350 && z == 400)).isEmpty(),
                "water's two-cell state support cannot be inferred from exact light");
        assertTrue(NativeFirstViewPlan.certify(p, EPOCH + 1, exact, exact, (x, y, z) -> true).isEmpty());
        var otherBounds = new VoxelSkyLight.Bounds(b.minX() + 1, b.minY(), b.minZ(), 80, 80, 80);
        var other = VoxelSkyLight.restore(otherBounds, lo, lo, cells);
        assertTrue(NativeFirstViewPlan.certify(p, EPOCH, exact, other, (x, y, z) -> true).isEmpty());
        var forgeries = new NativeFirstViewPlan.Plan[]{
                new NativeFirstViewPlan.Plan(null, 64, 32, -272, -352, 400, EPOCH, b, d, 884_736, 23_003_136),
                new NativeFirstViewPlan.Plan(NativeFirstViewPlan.Stage.FULL, 64, 32, -272, -352, 400,
                        EPOCH, b, d, 884_736, 23_003_136),
                new NativeFirstViewPlan.Plan(NativeFirstViewPlan.Stage.NEAR, 65, 32, -272, -352, 400,
                        EPOCH, b, d, 884_736, 23_003_136),
                new NativeFirstViewPlan.Plan(NativeFirstViewPlan.Stage.NEAR, 64, 64, -272, -352, 400,
                        EPOCH, b, d, 884_736, 23_003_136),
                new NativeFirstViewPlan.Plan(NativeFirstViewPlan.Stage.NEAR, 64, 32, -271, -352, 400,
                        EPOCH, b, d, 884_736, 23_003_136),
                new NativeFirstViewPlan.Plan(NativeFirstViewPlan.Stage.NEAR, 64, 32, -272, -352, 400,
                        EPOCH, null, d, 884_736, 23_003_136),
                new NativeFirstViewPlan.Plan(NativeFirstViewPlan.Stage.NEAR, 64, 32, -272, -352, 400,
                        EPOCH, b, new VoxelSkyLight.Bounds(-303, -384, 368, 64, 64, 64), 884_736, 23_003_136),
                new NativeFirstViewPlan.Plan(NativeFirstViewPlan.Stage.NEAR, 64, 32, -272, -352, 400,
                        EPOCH, b, d, 884_735, 23_003_136),
                new NativeFirstViewPlan.Plan(NativeFirstViewPlan.Stage.NEAR, 64, 32, -272, -352, 400,
                        EPOCH, b, d, 884_736, 1),
                new NativeFirstViewPlan.Plan(NativeFirstViewPlan.Stage.NEAR, 64, 32, Integer.MAX_VALUE - 15,
                        -352, 400, EPOCH, b, d, 884_736, 23_003_136)
        };
        for (var forged : forgeries) {
            assertTrue(NativeFirstViewPlan.certify(forged, EPOCH, exact, exact, (x, y, z) -> {
                fail("malformed plan must refuse before consulting authority");
                return true;
            }).isEmpty());
        }
    }

    @Test void certifiedCoverageReportsDrawOnlyAndRequiresCurrentAuthority() {
        var p = fixturePlan();
        int cells = VoxelSkyLight.cellCount(p.solveBounds(), 884_736);
        var exact = VoxelSkyLight.restore(p.solveBounds(), new byte[cells], new byte[cells], cells);
        var certified = NativeFirstViewPlan.certify(p, EPOCH, exact, exact, (x, y, z) -> true);
        assertTrue(certified.isPresent(), "exact current authority must yield truthful draw coverage");
        var coverage = certified.orElseThrow();
        assertTrue(coverage.coversCamera(-269.153355, -337.365774, 405.973425, EPOCH));
        assertTrue(coverage.containsDraw(-288, -368, 384));
        assertTrue(coverage.containsDraw(-241, -321, 431));
        assertFalse(coverage.containsDraw(-305, -352, 400));
        assertFalse(coverage.containsDraw(-240, -352, 400));
        assertFalse(coverage.coversCamera(-269.153355, -337.365774, 405.973425, EPOCH + 1));
        assertFalse(coverage.coversCamera(-305, -352, 400, EPOCH));
        assertFalse(coverage.coversCamera(Double.NaN, -352, 400, EPOCH));
    }

    @Test void waterStateHaloDoesNotClaimTwoCellLightWhenBoundaryAmbientIsOmitted() {
        var p = fixturePlan();
        var b = p.solveBounds();
        int cells = VoxelSkyLight.cellCount(b, 884_736);
        byte[] air = new byte[cells], none = new byte[cells];
        var nearSky = VoxelSkyLight.solve(b, air, none, cells);
        var nearBlock = VoxelSkyLight.solveBlock(b, air, none, cells);
        var largerBounds = new VoxelSkyLight.Bounds(-336, -416, 336, 128, 128, 128);
        int largerCells = VoxelSkyLight.cellCount(largerBounds, 2_097_152);
        byte[] largerAir = new byte[largerCells], largerSeeds = new byte[largerCells];
        largerSeeds[index(largerBounds, b.minX(), -350, 400)] = 15;
        var largerSky = VoxelSkyLight.solve(largerBounds, largerAir, largerSeeds, largerCells);
        assertEquals(0, nearSky.value(-290, -350, 400).orElseThrow());
        assertEquals(1, largerSky.value(-290, -350, 400).orElseThrow(),
                "an omitted boundary ambient seed disproves the two-cell LIGHT extension");
        assertEquals(0, largerSky.value(-289, -350, 400).orElseThrow());
        byte[] upper = nearSky.upperSnapshot();
        upper[index(b, -290, -350, 400)] = 1;
        var stateOnlyUncertainty = VoxelSkyLight.restore(b, nearSky.lowerSnapshot(), upper, cells);
        assertTrue(NativeFirstViewPlan.certify(p, EPOCH, stateOnlyUncertainty, nearBlock,
                (x, y, z) -> true).isPresent(),
                "the one-cell light extension remains exact while states use two cells");
    }

    private static NativeFirstViewPlan.Plan fixturePlan() {
        return new NativeFirstViewPlan.Plan(NativeFirstViewPlan.Stage.NEAR, 64, 24, -272, -352, 400,
                EPOCH, new VoxelSkyLight.Bounds(-304, -384, 368, 80, 80, 80),
                new VoxelSkyLight.Bounds(-288, -368, 384, 48, 48, 48), 512_000, 13_312_000);
    }

    private record Lights(VoxelSkyLight.Result sky, VoxelSkyLight.Result block) { }

    private static Lights solveMixed(VoxelSkyLight.Bounds b) {
        int cells = VoxelSkyLight.cellCount(b, 2_097_152);
        byte[] opacity = new byte[cells], direct = new byte[cells], emission = new byte[cells];
        for (int z = b.minZ(); z < b.minZ() + b.sizeZ(); z++) {
            for (int x = b.minX(); x < b.minX() + b.sizeX(); x++) {
                int sky = 15;
                // Global column starts well above both volumes, never at a clipped solve boundary.
                for (int y = 0; y >= b.minY(); y--) {
                    int state = mixedState(x, y, z);
                    int op = state == 1 || state == 4 ? 15 : state == 2 ? 3 : state == 3 ? 2 : 0;
                    sky = Math.max(0, sky - op);
                    if (y >= b.minY() + b.sizeY()) continue;
                    int i = index(b, x, y, z);
                    opacity[i] = (byte) op;
                    direct[i] = (byte) sky;
                    emission[i] = (byte) (state == 4 ? 15 : 0);
                }
            }
        }
        return new Lights(VoxelSkyLight.solve(b, opacity, direct, cells),
                VoxelSkyLight.solveBlock(b, opacity, emission, cells));
    }

    /** Hand-defined full state fixture, including a saved opaque emitter at a DRAW edge. */
    private static int mixedState(int x, int y, int z) {
        if ((x == -304 && y == -350 && z == 400) || (x == -250 && y == -355 && z == 410)) return 4;
        if (y <= -373 || (y == -332 && !(x >= -278 && x <= -267 && z >= 394 && z <= 406))) return 1;
        if (y >= -362 && y <= -358 && x >= -292 && x <= -282 && z >= 382 && z <= 392) return 2;
        if (y >= -345 && y <= -342 && Math.floorMod(x, 13) == 0 && Math.floorMod(z, 11) < 3) return 3;
        return 0;
    }

    private static int index(VoxelSkyLight.Bounds b, int x, int y, int z) {
        return x - b.minX() + b.sizeX() * (z - b.minZ() + b.sizeZ() * (y - b.minY()));
    }
}
