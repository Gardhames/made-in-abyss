package dev.madeinabyss.forge.worldgen;
import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.Map;
import java.util.function.Supplier;

/** Captured retained authority must describe the same immutable source epoch. */
public final class GeneratedObservationExpectations {
    private GeneratedObservationExpectations(){}
    public static int[] expected(CubeKey key,Map<CubeKey,int[]> retained,Map<CubeKey,int[]> publishedDensity,
            GeneratedSkySource source,Supplier<int[]> procedural,Runnable checkpoint) {
        checkpoint.run();
        if(!retained.containsKey(key)) {
            var known=publishedDensity.get(key);if(known!=null)return known;
            if(source!=null)return source.captureDerivedCube(key,checkpoint);
        }
        // Saved/modified source tiles and published density may contain the edit itself.
        // Only a fresh independent procedural expectation can certify its restoration.
        return procedural.get();
    }
}
