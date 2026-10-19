package dev.madeinabyss.kernel.terrain;

public sealed interface TerrainSample permits TerrainSample.Known, TerrainSample.Unavailable {
    record Known(TerrainMaterial material) implements TerrainSample {}
    record Unavailable(Reason reason) implements TerrainSample {}
    enum Reason { BELOW_DECLARED_FIRST_LAYER }
}
