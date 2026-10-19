package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.terrain.TerrainSample;

/** Refusal carries full XYZ and the kernel availability reason; never an air substitute. */
public final class TerrainUnavailableException extends IllegalArgumentException {
    private final TerrainSample.Reason reason;
    private final BlockCoordinate position;
    public TerrainUnavailableException(TerrainSample.Reason reason, BlockCoordinate position) {
        super("Unavailable terrain " + reason + " at " + position);
        this.reason = reason;
        this.position = position;
    }
    public TerrainSample.Reason reason() { return reason; }
    public BlockCoordinate position() { return position; }
}
