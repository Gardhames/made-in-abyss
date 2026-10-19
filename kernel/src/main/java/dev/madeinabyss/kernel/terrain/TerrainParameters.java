package dev.madeinabyss.kernel.terrain;

import java.util.Objects;

/** Explicit metre-scale reconstruction inputs. No canon dimensions or implicit defaults. */
public record TerrainParameters(long seed, Surface surface, Cavity cavity, Noise noise) {
    public TerrainParameters {
        Objects.requireNonNull(surface); Objects.requireNonNull(cavity); Objects.requireNonNull(noise);
        require(surface.terraceY == cavity.firstLayerTopY, "terrace and mouth must share a datum");
        require(cavity.mouthRadius + noise.wallAmplitude < surface.terraceOuterRadius,
                "mouth must fit inside the terrace");
        require(cavity.lowerRadius + noise.wallAmplitude < surface.islandRadiusX - surface.coastWidth,
                "first layer must fit beneath the island");
        require(noise.wallAmplitude + cavity.ledgeProjection < Math.min(cavity.mouthRadius, cavity.lowerRadius),
                "noise and ledges must leave the cavity open");
        require(surface.mountainHeight + noise.surfaceAmplitude + surface.terraceY <= Integer.MAX_VALUE,
                "surface exceeds signed block range");
        require(noise.ledgeVerticalAmplitude < cavity.ledgePeriod / 2, "ledge displacement must be bounded");
    }
    /** Radial features use X-axis metres; Z is stretched by islandRadiusZ / islandRadiusX. */
    public record Surface(double oceanLevelY, double oceanBedY, double islandRadiusX,
            double islandRadiusZ, double coastWidth, double terraceY, double terraceOuterRadius,
            double mountainPeakRadius, double mountainHalfWidth, double mountainHeight, double soilDepth) {
        public Surface {
            finite(oceanLevelY, oceanBedY, terraceY);
            positive(islandRadiusX, islandRadiusZ, coastWidth, terraceOuterRadius, mountainPeakRadius,
                    mountainHalfWidth, soilDepth);
            nonnegative(mountainHeight);
            require(Double.isFinite((islandRadiusX / islandRadiusZ) * 2147483648.0),
                    "ellipse scaling must stay finite across signed coordinates");
            require(oceanBedY < oceanLevelY && oceanLevelY < terraceY, "bed < water < terrace required");
            require(terraceOuterRadius < mountainPeakRadius - mountainHalfWidth,
                    "mountain ring must leave an empty terrace");
            require(mountainPeakRadius + mountainHalfWidth < islandRadiusX - coastWidth,
                    "mountain ring must fit inside coast");
        }
    }
    public record Cavity(double mouthRadius, double firstLayerTopY, int declaredLowerBoundY,
            double lowerRadius, double ledgePeriod, double ledgeWidth, double ledgeProjection) {
        public Cavity {
            finite(firstLayerTopY);
            positive(mouthRadius, lowerRadius, ledgePeriod, ledgeWidth);
            nonnegative(ledgeProjection);
            require(Double.isFinite((firstLayerTopY - Integer.MIN_VALUE) / ledgePeriod),
                    "ledge phase must stay finite across signed coordinates");
            require(declaredLowerBoundY < firstLayerTopY, "declared first layer must have depth");
            require(ledgeWidth < ledgePeriod, "ledge width must be less than spacing");
            require(ledgeProjection < Math.min(mouthRadius, lowerRadius), "ledges must not seal the cavity");
        }
    }
    /** surfaceAmplitude explicitly scales both island ground and natural local shelf-cap relief. */
    public record Noise(double wavelength, double surfaceAmplitude, double wallAmplitude,
            double ledgeVerticalAmplitude) {
        public Noise {
            positive(wavelength); nonnegative(surfaceAmplitude, wallAmplitude, ledgeVerticalAmplitude);
            require(wavelength >= 1, "noise wavelength must resolve at block scale");
        }
    }
    private static void positive(double... values) {
        finite(values); for (double v : values) require(v > 0, "distance must be positive");
    }
    private static void nonnegative(double... values) {
        finite(values); for (double v : values) require(v >= 0, "amplitude must be nonnegative");
    }
    private static void finite(double... values) {
        for (double v : values) require(Double.isFinite(v) && v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE,
                "metres must be finite and within signed block range");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
