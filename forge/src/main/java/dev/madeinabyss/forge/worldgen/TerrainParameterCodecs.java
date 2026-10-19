package dev.madeinabyss.forge.worldgen;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.madeinabyss.kernel.terrain.TerrainParameters;

/** Explicit reconstruction metrics; no implicit artistic defaults. */
public final class TerrainParameterCodecs {
    private TerrainParameterCodecs() { }
    private static final Codec<TerrainParameters.Surface> SURFACE = guarded(RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("ocean_level_y").forGetter(TerrainParameters.Surface::oceanLevelY),
            Codec.DOUBLE.fieldOf("ocean_bed_y").forGetter(TerrainParameters.Surface::oceanBedY),
            Codec.DOUBLE.fieldOf("island_radius_x").forGetter(TerrainParameters.Surface::islandRadiusX),
            Codec.DOUBLE.fieldOf("island_radius_z").forGetter(TerrainParameters.Surface::islandRadiusZ),
            Codec.DOUBLE.fieldOf("coast_width").forGetter(TerrainParameters.Surface::coastWidth),
            Codec.DOUBLE.fieldOf("terrace_y").forGetter(TerrainParameters.Surface::terraceY),
            Codec.DOUBLE.fieldOf("terrace_outer_radius").forGetter(TerrainParameters.Surface::terraceOuterRadius),
            Codec.DOUBLE.fieldOf("mountain_peak_radius").forGetter(TerrainParameters.Surface::mountainPeakRadius),
            Codec.DOUBLE.fieldOf("mountain_half_width").forGetter(TerrainParameters.Surface::mountainHalfWidth),
            Codec.DOUBLE.fieldOf("mountain_height").forGetter(TerrainParameters.Surface::mountainHeight),
            Codec.DOUBLE.fieldOf("soil_depth").forGetter(TerrainParameters.Surface::soilDepth)
    ).apply(i, TerrainParameters.Surface::new)));
    private static final Codec<TerrainParameters.Cavity> CAVITY = guarded(RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("mouth_radius").forGetter(TerrainParameters.Cavity::mouthRadius),
            Codec.DOUBLE.fieldOf("first_layer_top_y").forGetter(TerrainParameters.Cavity::firstLayerTopY),
            Codec.INT.fieldOf("declared_lower_bound_y").forGetter(TerrainParameters.Cavity::declaredLowerBoundY),
            Codec.DOUBLE.fieldOf("lower_radius").forGetter(TerrainParameters.Cavity::lowerRadius),
            Codec.DOUBLE.fieldOf("ledge_period").forGetter(TerrainParameters.Cavity::ledgePeriod),
            Codec.DOUBLE.fieldOf("ledge_width").forGetter(TerrainParameters.Cavity::ledgeWidth),
            Codec.DOUBLE.fieldOf("ledge_projection").forGetter(TerrainParameters.Cavity::ledgeProjection)
    ).apply(i, TerrainParameters.Cavity::new)));
    private static final Codec<TerrainParameters.Noise> NOISE = guarded(RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("wavelength").forGetter(TerrainParameters.Noise::wavelength),
            Codec.DOUBLE.fieldOf("surface_amplitude").forGetter(TerrainParameters.Noise::surfaceAmplitude),
            Codec.DOUBLE.fieldOf("wall_amplitude").forGetter(TerrainParameters.Noise::wallAmplitude),
            Codec.DOUBLE.fieldOf("ledge_vertical_amplitude").forGetter(TerrainParameters.Noise::ledgeVerticalAmplitude)
    ).apply(i, TerrainParameters.Noise::new)));
    public static final Codec<TerrainParameters> PARAMETERS = guarded(RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("seed").forGetter(TerrainParameters::seed),
            SURFACE.fieldOf("surface").forGetter(TerrainParameters::surface),
            CAVITY.fieldOf("cavity").forGetter(TerrainParameters::cavity),
            NOISE.fieldOf("noise").forGetter(TerrainParameters::noise)
    ).apply(i, TerrainParameters::new)));

    /** Turn metric validation failures into normal decode errors, preserving supplied data. */
    static <A> Codec<A> guarded(Codec<A> delegate) {
        return new Codec<>() {
            @Override public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
                try { return delegate.decode(ops, input); }
                catch (IllegalArgumentException invalid) { return DataResult.error(() -> invalid.getMessage()); }
            }
            @Override public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
                return delegate.encode(input, ops, prefix);
            }
        };
    }
}
