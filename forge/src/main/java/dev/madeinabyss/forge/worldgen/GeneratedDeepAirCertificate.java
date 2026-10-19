package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.Objects;
import java.util.Set;

/** Derived exact AIR proof for the unchanged approved v14 composed world.
 * The primitive table excludes the complete geometry/vegetation support halo;
 * a different valid recipe and every authoritative saved cube stay on the oracle. */
public final class GeneratedDeepAirCertificate {
    public static final String APPROVED_FINGERPRINT="148e3d835d034f0a195516c95d20cb415d5a275ac5e92ee8a29ef28389642bb0";
    private final ParameterizedTerrainChunkGenerator generator;
    private final dev.madeinabyss.kernel.light.DeepCavityAirBounds bounds;
    public GeneratedDeepAirCertificate(GeneratedPreviewProfile verifiedProfile) {
        Objects.requireNonNull(verifiedProfile);generator=verifiedProfile.generator();
        bounds=APPROVED_FINGERPRINT.equals(verifiedProfile.fingerprint())
            ?new dev.madeinabyss.kernel.light.DeepCavityAirBounds(generator.parameters()):null;
    }
    public double radiusAtY(int y) { return bounds==null?0:bounds.radiusAtY(y); }
    public boolean eligible() { return bounds!=null&&bounds.radiusAtY(-430)>0; }
    public boolean guaranteedAir(int x,int y,int z,Set<CubeKey> savedOverrideKeys) {
        Objects.requireNonNull(savedOverrideKeys);
        return bounds!=null&&bounds.guaranteedAir(x,y,z)&&y>=generator.knownLowerY(x,z)
            &&!savedOverrideKeys.contains(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)));
    }
}
