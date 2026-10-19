package dev.madeinabyss.kernel.terrain;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ParametricTerrainSamplerTest {
    private TerrainParameters proposal() {
        return new TerrainParameters(20261003,
                new TerrainParameters.Surface(0, -64, 1000, 1000, 80, 64, 650, 800, 100, 160, 3),
                new TerrainParameters.Cavity(500, 64, -1286, 650, 180, 32, 45),
                new TerrainParameters.Noise(140, 20, 60, 70));
    }
    private TerrainParameters habitatProposal() {
        var old = proposal();
        return new TerrainParameters(old.seed(), old.surface(),
                new TerrainParameters.Cavity(500, 64, -1286, 650, 180, 170, 280), old.noise());
    }
    @Test void broadTerracesContainSeveralConnectedEightyMetreNaturalHabitatsWithWoodlandHeadroom() {
        var s = new ParametricTerrainSampler(habitatProposal());
        int habitats = 0, fertile = 0;
        for (int depth = 80; depth < 1300 && (habitats < 3 || fertile < 2); depth++) {
            int referenceY = 64-depth;
            boolean found = false;
            for (int shift=-120; shift<=120 && !found; shift+=20) for (int x=180; x<=650 && !found; x+=5) {
                if (!solid(material(s,x,referenceY,shift)) || material(s,x,referenceY+1,shift)!=TerrainMaterial.AIR) continue;
                int[][] groundYs = new int[80][80];
                int minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE,grass=0,soil=0;
                boolean square=true;
                for (int dx=0;dx<80 && square;dx++) for (int dz=0;dz<80;dz++) {
                    int z=shift-40+dz;
                    int floor=findGroundNear(s,x+dx,z,referenceY);
                    if (floor==Integer.MIN_VALUE || (dx>0 && Math.abs(floor-groundYs[dx-1][dz])>1)
                            || (dz>0 && Math.abs(floor-groundYs[dx][dz-1])>1)) { square=false;break; }
                    groundYs[dx][dz]=floor;
                    minY=Math.min(minY,floor); maxY=Math.max(maxY,floor);
                    for (int dy=1;dy<=24;dy++) if (material(s,x+dx,floor+dy,z)!=TerrainMaterial.AIR) {square=false;break;}
                    if (!square) break;
                    if (material(s,x+dx,floor,z)==TerrainMaterial.GRASS)grass++;
                    if (material(s,x+dx,floor-1,z)==TerrainMaterial.SOIL)soil++;
                }
                if (square && maxY-minY>=3) {
                    habitats++;found=true;
                    if(grass>=4800 && soil>=4800)fertile++;
                }
            }
            if(found)depth+=40; // Distinct height neighbourhoods, rather than repeated overlapping rectangles.
        }
        assertTrue(habitats>=3,"several actual connected80x80 habitats need varying ground and24m diagnostic headroom");
        assertTrue(fertile>=2,"at least two natural habitats need mostly grass and soil cover");
    }
    private int findGroundNear(ParametricTerrainSampler sampler,int x,int z,int referenceY) {
        for(int y=referenceY+40;y>=referenceY-40;y--)
            if(solid(material(sampler,x,y,z)) && material(sampler,x,y+1,z)==TerrainMaterial.AIR)return y;
        return Integer.MIN_VALUE;
    }
    @Test void deepShelfHasSeededGrassAndSoilCoverAlongsideExposedRock() {
        var s = new ParametricTerrainSampler(habitatProposal());
        int grass = 0, exposed = 0, soils = 0;
        for (int depth = 80; depth < 1300; depth++) {
            int y = 64 - depth;
            for (int x = 300; x <= 700; x += 5) {
                TerrainMaterial m = material(s, x, y, 0);
                if (!solid(m) || material(s, x, y + 1, 0) != TerrainMaterial.AIR
                        || material(s, x, y + 2, 0) != TerrainMaterial.AIR) continue;
                if (m == TerrainMaterial.GRASS) {
                    grass++;
                    if (material(s, x, y - 1, 0) == TerrainMaterial.SOIL
                            && material(s, x, y - 3, 0) == TerrainMaterial.ROCK) soils++;
                }
                if (m == TerrainMaterial.ROCK) exposed++;
            }
        }
        assertTrue(grass > 0 && soils > 0, "some real deep ledge tops need a soil horizon");
        assertTrue(exposed > 0, "seeded cover must retain exposed rocky ledges");
    }
    private boolean solid(TerrainMaterial m) {
        return m == TerrainMaterial.ROCK || m == TerrainMaterial.SOIL || m == TerrainMaterial.GRASS;
    }
    @Test void localShelfCapsAndRoundedButtressesRemainRealSolidGeometry() {
        var capParameters = proposal();
        var s = new ParametricTerrainSampler(new TerrainParameters(capParameters.seed(),capParameters.surface(),
                capParameters.cavity(),new TerrainParameters.Noise(140,0,60,70)));
        int shelves = 0;
        for (int depth = 80; depth < 1300; depth++) {
            int y = 64 - depth;
            for (int x = 400; x <= 700; x++) {
                if (material(s, x, y, 0) == TerrainMaterial.ROCK
                        && material(s, x, y + 1, 0) == TerrainMaterial.AIR
                        && material(s, x + 14, y, 0) == TerrainMaterial.ROCK
                        && material(s, x + 14, y + 1, 0) == TerrainMaterial.AIR) {
                    shelves++; break;
                }
            }
        }
        assertTrue(shelves >= 3, "several local ledges need real flat tops at different heights");
        var p = habitatProposal();
        var sparse = new ParametricTerrainSampler(new TerrainParameters(p.seed(), p.surface(),
                new TerrainParameters.Cavity(500,64,-1286,650,800,p.cavity().ledgeWidth(),280),
                new TerrainParameters.Noise(140,0,0,0)));
        var bare = new ParametricTerrainSampler(new TerrainParameters(p.seed(), p.surface(),
                new TerrainParameters.Cavity(500,64,-1286,650,800,p.cavity().ledgeWidth(),0),
                new TerrainParameters.Noise(140,0,0,0)));
        int topY = Integer.MIN_VALUE;
        for (int y = -1; y > -1050; y--) if (sparse.cavityRadius(500,y+1,0)-sparse.cavityRadius(500,y,0)>120) {
            topY=y; break;
        }
        assertNotEquals(Integer.MIN_VALUE, topY, "the controlled sparse profile needs a real shelf top");
        int massAt120 = firstSolidOnEastRay(bare,topY-120)-firstSolidOnEastRay(sparse,topY-120);
        assertTrue(massAt120 >= 20, "rounded buttress must keep substantial actual rock mass 120m below the cap");
        int projection20 = firstSolidOnEastRay(bare,topY-20)-firstSolidOnEastRay(sparse,topY-20);
        int projection40 = firstSolidOnEastRay(bare,topY-40)-firstSolidOnEastRay(sparse,topY-40);
        int projection140 = firstSolidOnEastRay(bare,topY-140)-firstSolidOnEastRay(sparse,topY-140);
        assertTrue(projection20-projection40 > massAt120-projection140,
                "actual underside must progressively turn towards the cliff rather than remain a straight thin wing");
    }
    private int firstSolidOnEastRay(ParametricTerrainSampler sampler, int y) {
        for (int x = 100; x < 900; x++) if (solid(material(sampler, x, y, 0))) return x;
        throw new AssertionError("missing known east cliff");
    }
    @Test void ledgeHeightsAndWidthsDoNotRepeatAsGlobalPeriodicRings() {
        var s = new ParametricTerrainSampler(proposal());
        var mirrored = new ArrayList<Integer>();
        var eastern = new ArrayList<Integer>();
        for (int depth = 80; depth < 1300; depth++) {
            int y = 64 - depth;
            if (s.cavityRadius(500, y + 1, 0) - s.cavityRadius(500, y, 0) > 15) eastern.add(depth);
            if (s.cavityRadius(-500, y + 1, 0) - s.cavityRadius(-500, y, 0) > 15) mirrored.add(depth);
            assertEquals(TerrainMaterial.AIR, material(s, 0, y, 0));
        }
        assertTrue(eastern.size() >= 3);
        assertNotEquals(eastern, mirrored);
        assertTrue(eastern.stream().map(d -> d % 180).distinct().count() >= 3);
        assertInstanceOf(TerrainSample.Unavailable.class, s.sample(0, -1287, 0));
    }
    // Deliberately small ORIGINAL mathematical fixture, not canon geography.
    private TerrainParameters parameters(long seed, boolean noise) {
        return new TerrainParameters(seed,
                new TerrainParameters.Surface(0, -20, 200, 200, 40, 10, 60, 120, 30, 50, 3),
                new TerrainParameters.Cavity(20, 10, -1000, 40, 70, 12, 4),
                new TerrainParameters.Noise(37, noise ? 4 : 0, noise ? 3 : 0, noise ? 9 : 0));
    }
    private TerrainMaterial material(ParametricTerrainSampler sampler, int x, int y, int z) {
        return assertInstanceOf(TerrainSample.Known.class, sampler.sample(x, y, z)).material();
    }
    @Test void oceanHasExplicitBedWaterAndAir() {
        var s = new ParametricTerrainSampler(parameters(1, false));
        assertEquals(TerrainMaterial.ROCK, material(s, 300, -20, 0));
        assertEquals(TerrainMaterial.WATER, material(s, 300, -19, 0));
        assertEquals(TerrainMaterial.WATER, material(s, -300, 0, 0));
        assertEquals(TerrainMaterial.AIR, material(s, -300, 1, 0));
    }
    @Test void terraceMountainAndMouthAreOneContinuousSpace() {
        var s = new ParametricTerrainSampler(parameters(1, false));
        assertEquals(TerrainMaterial.GRASS, material(s, 50, 10, 0));
        assertEquals(TerrainMaterial.SOIL, material(s, 50, 8, 0));
        assertEquals(TerrainMaterial.ROCK, material(s, 50, 6, 0));
        assertEquals(60, s.surfaceHeight(120, 0), 1e-9);
        assertEquals(TerrainMaterial.AIR, material(s, 0, 10, 0));
        assertEquals(TerrainMaterial.AIR, material(s, 0, -999, 0));
        assertEquals(TerrainMaterial.ROCK, material(s, 100, -999, 0));
    }
    @Test void declaredBottomIsKnownButBelowItIsTypedUnavailable() {
        var s = new ParametricTerrainSampler(parameters(1, false));
        assertInstanceOf(TerrainSample.Known.class, s.sample(0, -1000, 0));
        var unknown = assertInstanceOf(TerrainSample.Unavailable.class, s.sample(0, -1001, 0));
        assertEquals(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER, unknown.reason());
        assertInstanceOf(TerrainSample.Unavailable.class, s.sample(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE));
    }
    @Test void negativeCubeAndNativeColumnAgreeAcrossMinus64Seam() {
        var s = new ParametricTerrainSampler(parameters(7, true));
        var column = new ArrayList<TerrainSample>();
        for (int y = -80; y < -48; y++) column.add(s.sample(-23, y, -11));
        var cubes = new ArrayList<TerrainSample>();
        for (int origin : new int[]{-80, -64})
            for (int local = 0; local < 16; local++) cubes.add(s.sample(-23, origin + local, -11));
        assertEquals(column, cubes);
        assertEquals(TerrainMaterial.ROCK, material(s, -100, -64, -11));
        assertEquals(TerrainMaterial.ROCK, material(s, -100, -65, -11));
        assertEquals(TerrainMaterial.AIR, material(s, 0, -64, 0));
        assertEquals(TerrainMaterial.AIR, material(s, 0, -65, 0));
    }
    @Test void seedAndOrderAreStableWhileDifferentSeedsAlterGeometry() {
        var a = new ParametricTerrainSampler(parameters(7, true));
        var b = new ParametricTerrainSampler(parameters(7, true));
        var c = new ParametricTerrainSampler(parameters(8, true));
        var ys = new ArrayList<Integer>();
        for (int y = -200; y <= 10; y++) ys.add(y);
        var expected = ys.stream().map(y -> a.sample(-23, y, 0)).toList();
        Collections.reverse(ys);
        for (int y : ys) assertEquals(expected.get(y + 200), b.sample(-23, y, 0));
        assertNotEquals(a.surfaceHeight(120, 7), c.surfaceHeight(120, 7));
        assertNotEquals(a.cavityRadius(-23, -125, 0), c.cavityRadius(-23, -125, 0));
    }
    @Test void ledgesHaveVerticalAndAngularReliefRatherThanCubeAlignedPlanes() {
        var s = new ParametricTerrainSampler(parameters(7, true));
        assertNotEquals(s.cavityRadius(25, -100, 0), s.cavityRadius(0, -100, 25));
        var p = parameters(7, true);
        var withoutLedges = new ParametricTerrainSampler(new TerrainParameters(p.seed(), p.surface(),
                new TerrainParameters.Cavity(20, 10, -1000, 40, 70, 12, 0), p.noise()));
        double maximumActualProjection = 0;
        for (int y = -240; y < -100; y++) {
            double projection = withoutLedges.cavityRadius(25, y, 0) - s.cavityRadius(25, y, 0);
            assertTrue(projection >= 0, "ledges project inward from the same cliff baseline");
            maximumActualProjection = Math.max(maximumActualProjection, projection);
        }
        assertTrue(maximumActualProjection > 1, "ledge relief must be real radial geometry");
    }
    @Test void extremeHorizontalCoordinatesDoNotOverflowOrAliasToTheIsland() {
        var s = new ParametricTerrainSampler(parameters(7, true));
        assertEquals(-20, s.surfaceHeight(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(TerrainMaterial.WATER, material(s, Integer.MIN_VALUE, -1, Integer.MAX_VALUE));
        assertEquals(TerrainMaterial.AIR, material(s, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE));
    }
    @Test void rejectsNonfiniteAndPhysicallyInconsistentParameters() {
        assertThrows(IllegalArgumentException.class, () -> new TerrainParameters.Noise(Double.NaN, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TerrainParameters.Noise(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TerrainParameters.Surface(0, 1, 200, 200, 40, 10, 60, 120, 30, 50, 3));
        assertThrows(IllegalArgumentException.class, () -> new TerrainParameters.Cavity(20, 10, 11, 40, 70, 12, 4));
        assertThrows(IllegalArgumentException.class, () -> new TerrainParameters.Cavity(20, 10, -1000, 40, 70, 12, 20));
        assertThrows(IllegalArgumentException.class, () -> new TerrainParameters.Surface(0, -20, 200, Double.MIN_VALUE,
                40, 10, 60, 120, 30, 50, 3));
        assertThrows(IllegalArgumentException.class, () -> new TerrainParameters.Cavity(20, 10, -1000, 40,
                1e-310, 1e-311, 4));
    }
    @Test void rejectsNoiseThatCouldSealTheCavityAndDifferentRimDatums() {
        var base = parameters(7, false);
        assertThrows(IllegalArgumentException.class, () -> new TerrainParameters(7, base.surface(), base.cavity(),
                new TerrainParameters.Noise(37, 0, 17, 0)));
        assertThrows(IllegalArgumentException.class, () -> new TerrainParameters(7, base.surface(),
                new TerrainParameters.Cavity(20, 9, -1000, 40, 70, 12, 4), base.noise()));
    }
    @Test void elongatedIslandUsesExplicitAxesWithoutMovingTheMouth() {
        var base = parameters(7, false);
        var s = new ParametricTerrainSampler(new TerrainParameters(7,
                new TerrainParameters.Surface(0, -20, 200, 400, 40, 10, 60, 120, 30, 50, 3),
                base.cavity(), base.noise()));
        assertEquals(60, s.surfaceHeight(0, 240), 1e-9);
        assertEquals(-20, s.surfaceHeight(0, 400), 1e-9);
        assertEquals(TerrainMaterial.AIR, material(s, 0, 0, 19));
        assertEquals(TerrainMaterial.ROCK, material(s, 0, 0, 30));
    }
}







