package dev.madeinabyss.kernel.terrain;

import java.util.Objects;

/** Pure global XYZ geology sampler. Contours are RECONSTRUCTION, never verified canon. */
public final class ParametricTerrainSampler {
    private final TerrainParameters p;
    private static final TerrainSample.Unavailable BELOW = new TerrainSample.Unavailable(
            TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER);
    private static final TerrainSample.Known[] MATERIALS = java.util.Arrays.stream(TerrainMaterial.values())
            .map(TerrainSample.Known::new).toArray(TerrainSample.Known[]::new);

    public ParametricTerrainSampler(TerrainParameters parameters) { p = Objects.requireNonNull(parameters); }

    public TerrainSample sample(int x, int y, int z) {
        if (y < p.cavity().declaredLowerBoundY()) return BELOW;
        if (Math.hypot((double)x, (double)z) < cavityRadius(x, y, z)) return known(TerrainMaterial.AIR);
        double surface = Math.floor(surfaceHeight(x, z));
        if (y > surface) return known(y <= p.surface().oceanLevelY() ? TerrainMaterial.WATER : TerrainMaterial.AIR);
        if (surface > p.surface().oceanLevelY()) {
            if (y == surface) return known(TerrainMaterial.GRASS);
            if ((double)y > surface - p.surface().soilDepth()) return known(TerrainMaterial.SOIL);
        }
        // Soil horizon on real exposed deep ground, never an ecological feature or recursive sample call.
        // Patch noise leaves bare rock between covered terraces; depth is the explicit surface soilDepth.
        if (y < p.cavity().firstLayerTopY() && noise(x, y, z, 31) > 0) {
            if (cavityAirAbove(x, y, z, 1) && cavityAirAbove(x, y, z, 2)) return known(TerrainMaterial.GRASS);
            long horizon = (long)Math.ceil(p.surface().soilDepth());
            if (cavityAirAbove(x, y, z, horizon) && cavityAirAbove(x, y, z, horizon + 1))
                return known(TerrainMaterial.SOIL);
        }
        return known(TerrainMaterial.ROCK);
    }

    private boolean cavityAirAbove(int x, int y, int z, long offset) {
        long above = (long)y + offset;
        return above <= Integer.MAX_VALUE && above >= p.cavity().declaredLowerBoundY()
                && Math.hypot((double)x, (double)z) < cavityRadius(x, (int)above, z);
    }

    /** Continuous height of the upper solid block. Its voxel Y is floor(height), air starts one above. */
    public double surfaceHeight(int x, int z) {
        var s = p.surface();
        double radius = Math.hypot((double)x, (double)z * (s.islandRadiusX() / s.islandRadiusZ()));
        if (radius >= s.islandRadiusX()) return s.oceanBedY();
        double coast = smooth(clamp((s.islandRadiusX() - radius) / s.coastWidth()));
        double ring = Math.max(0, 1 - Math.abs(radius - s.mountainPeakRadius()) / s.mountainHalfWidth());
        double relief = smooth(clamp((radius - s.terraceOuterRadius()) / p.noise().wavelength()));
        return s.oceanBedY() + (s.terraceY() - s.oceanBedY()) * coast
                + s.mountainHeight() * smooth(ring)
                + noise(x, 0, z, 1) * p.noise().surfaceAmplitude() * relief * coast;
    }

    /** Local cliff profile at this azimuth/altitude; the upper mouth preserves its declared radius. */
    public double cavityRadius(int x, int y, int z) {
        var c = p.cavity(); var n = p.noise();
        if (y >= c.firstLayerTopY()) return c.mouthRadius();
        double depth = c.firstLayerTopY() - (double)y;
        double fraction = clamp(depth / (c.firstLayerTopY() - c.declaredLowerBoundY()));
        double envelope = smooth(clamp(depth / n.wavelength()));
        double length = Math.hypot((double)x, (double)z);
        // Project onto the rim: cliff features follow azimuth, not arbitrary radial noise coordinates.
        int rimX = (int)Math.round(length == 0 ? c.mouthRadius() : x / length * c.mouthRadius());
        int rimZ = (int)Math.round(length == 0 ? 0 : z / length * c.mouthRadius());
        double ribs = noise(rimX, 0, rimZ, 11);
        double cliff = noise(rimX, y, rimZ, 2);
        double wall = n.wallAmplitude() * (0.65 * ribs + 0.35 * cliff);
        return c.mouthRadius() + (c.lowerRadius() - c.mouthRadius()) * smooth(fraction)
                + envelope * (wall - localLedge(depth, Math.atan2(z, x), x, z));
    }

    // Reconstruction of local shelves, not rings: independent top, width and projection per patch.
    // Dimensionless shape coefficients are algorithm choices; all metre scales come from parameters.
    private double localLedge(double depth, double angle, int x, int z) {
        var c = p.cavity();
        long sectors = Math.max(1L, (long)Math.ceil(2 * Math.PI * c.mouthRadius() / p.noise().wavelength()));
        double azimuth = (angle / (2 * Math.PI) + 1) % 1 * sectors;
        long sector = (long)Math.floor(azimuth);
        long band = (long)Math.floor(depth / c.ledgePeriod());
        double extrusion = 0;
        for (int offset = -1; offset <= 1; offset++) {
            long localSector = sector + offset;
            double angularWeight = smooth(clamp(1 - Math.abs(azimuth - localSector)));
            if (angularWeight == 0) continue;
            long key = Math.floorMod(localSector, sectors);
            for (int vertical = -2; vertical <= 2; vertical++) {
                long localBand = band + vertical;
                if (localBand < 0) continue;
                double top = (localBand + 0.2 + 0.6 * randomUnit(key, localBand, 21)) * c.ledgePeriod()
                        + (2 * randomUnit(key, localBand, 22) - 1) * p.noise().ledgeVerticalAmplitude()
                        + noise(x, 0, z, 41) * p.noise().surfaceAmplitude();
                double width = c.ledgeWidth() * (0.6 + 0.8 * randomUnit(key, localBand, 23));
                double belowTop = depth - top;
                if (top <= 0 || belowTop < 0 || belowTop >= width) continue;
                double projection = c.ledgeProjection() * (0.4 + 0.6 * randomUnit(key, localBand, 24));
                double position = belowTop / width;
                // User-drawn rounded buttress: substantial downward rock body, progressively turning into the cliff.
                // Individual additional extrusion reaches zero with zero derivative at its lower connection.
                // Overlapping patches and voxel stair steps still need visual review; no global smoothness claim.
                double remaining = 1 - position;
                double support = remaining * remaining;
                extrusion = Math.max(extrusion, projection * angularWeight * support);
            }
        }
        return extrusion;
    }

    private double randomUnit(long sector, long band, long salt) {
        return (mix(p.seed() ^ mix(sector) ^ Long.rotateLeft(mix(band), 29) ^ mix(salt)) >>> 11) * 0x1.0p-53;
    }

    private TerrainSample.Known known(TerrainMaterial material) { return MATERIALS[material.ordinal()]; }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static double smooth(double value) { return value * value * (3 - 2 * value); }

    // Stateless interpolated lattice noise: signed coordinates, no packed keys or mutable RNG order.
    private double noise(int x, int y, int z, long salt) {
        double sx = x / p.noise().wavelength(), sy = y / p.noise().wavelength(), sz = z / p.noise().wavelength();
        long ix = (long)Math.floor(sx), iy = (long)Math.floor(sy), iz = (long)Math.floor(sz);
        double fx = smooth(sx - ix), fy = smooth(sy - iy), fz = smooth(sz - iz), result = 0;
        for (int dx = 0; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = 0; dz <= 1; dz++) {
            double weight = (dx == 0 ? 1-fx : fx) * (dy == 0 ? 1-fy : fy) * (dz == 0 ? 1-fz : fz);
            long hash = mix(p.seed() ^ mix(ix + dx) ^ Long.rotateLeft(mix(iy + dy), 21)
                    ^ Long.rotateLeft(mix(iz + dz), 42) ^ mix(salt));
            result += weight * ((hash >>> 11) * 0x1.0p-53 * 2 - 1);
        }
        return result;
    }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
