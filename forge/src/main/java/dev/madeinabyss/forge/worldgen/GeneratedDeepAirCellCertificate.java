package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.Objects;
import java.util.Set;

/** Derived AIR proof for the exact approved composed world, including its final shaft carve.
 * The immutable cell table bounds the entire vegetation/support halo. Saved cubes and
 * different valid recipes retain the full-state sampler. */
public final class GeneratedDeepAirCellCertificate {
    public static final String APPROVED_FINGERPRINT="148e3d835d034f0a195516c95d20cb415d5a275ac5e92ee8a29ef28389642bb0";
    private final ParameterizedTerrainChunkGenerator generator;
    private final dev.madeinabyss.kernel.light.DeepCavityAirCellBounds bounds;
    public GeneratedDeepAirCellCertificate(GeneratedPreviewProfile profile) {
        Objects.requireNonNull(profile);generator=profile.generator();
        bounds=APPROVED_FINGERPRINT.equals(profile.fingerprint())
            ?new dev.madeinabyss.kernel.light.DeepCavityAirCellBounds(generator.parameters()):null;
    }
    public boolean eligible(){return bounds!=null&&bounds.residentArrayBytes()>0;}
    public int residentArrayBytes(){return bounds==null?0:bounds.residentArrayBytes();}
    public long constructionNanos(){return bounds==null?0:bounds.constructionNanos();}
    public int admittedCellCount(){return bounds==null?0:bounds.admittedCellCount();}
    public boolean guaranteedAir(int x,int y,int z,Set<CubeKey> savedKeys) {
        Objects.requireNonNull(savedKeys);
        return bounds!=null&&bounds.guaranteedAir(x,y,z)&&y>=generator.knownLowerY(x,z)
            &&(savedKeys.isEmpty()||!savedKeys.contains(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16))));
    }
}
