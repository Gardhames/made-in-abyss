package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import java.util.Objects;

/** Immutable material results for derived LOD sampling; no generator identity or state is changed. */
public final class LodTerrainSamples {
    private static final TerrainSample.Known[] KNOWN=create();
    private LodTerrainSamples() { }
    private static TerrainSample.Known[] create() {
        var materials=TerrainMaterial.values();var values=new TerrainSample.Known[materials.length];
        for(var material:materials)values[material.ordinal()]=new TerrainSample.Known(material);
        return values;
    }
    public static TerrainSample.Known known(TerrainMaterial material) {
        return KNOWN[Objects.requireNonNull(material,"material").ordinal()];
    }
}
