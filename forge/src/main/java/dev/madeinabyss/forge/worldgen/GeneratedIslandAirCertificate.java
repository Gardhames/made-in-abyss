package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.Objects;
import java.util.Set;

/** Derived exact AIR certificate; does not participate in persisted generator identity.
 * Only constructed from a profile whose immutable recipes already passed their actual SHA guards.
 * This deliberately makes no assertion about the deep cavity or arbitrary composed callbacks. */
public final class GeneratedIslandAirCertificate {
    private final ParameterizedTerrainChunkGenerator generator;
    private final IslandSurfaceGeneration island;
    private final boolean eligible;
    private final double upperOpeningRadiusSquared;
    public GeneratedIslandAirCertificate(GeneratedPreviewProfile verifiedProfile) {
        Objects.requireNonNull(verifiedProfile);generator=verifiedProfile.generator();island=generator.islandSurface();
        // The v14 opening constructor validates exact approved -250/-400 and R500/Y64 rules;
        // the island constructor validates every fixed proposal field and the actual asset/code SHA.
        eligible=generator.livingAbyss()==null&&island!=null&&island.minimumKnownY()==-5000&&generator.upperShaftOpening()!=null&&verifiedProfile.caveExpansion()!=null
                &&verifiedProfile.habitats()==null&&verifiedProfile.landscape()==null&&verifiedProfile.wilderness()==null;
        double radius=0;var p=generator.parameters();var c=p.cavity();
        if(eligible&&c.mouthRadius()==500&&c.firstLayerTopY()==64&&c.lowerRadius()>=500){
            // At -250<=Y<64, shelf retention is exactly0. The no-shelf base radius is
            // at least500; the wall/envelope noise is bounded by1 plus roundoff. 2^-40
            // is8192 binary64 unit-roundoff units, exceeding the bounded polynomial,
            // trilinear and weighted-wall operation count. Final ULP covers base+wall.
            double wall=Math.nextUp(p.noise().wallAmplitude()*(1+0x1.0p-40));
            radius=Math.nextDown(500-wall-Math.ulp(2*Math.max(500,c.lowerRadius())));
        }
        upperOpeningRadiusSquared=radius>0?Math.nextDown(radius*radius):0;
    }
    public boolean eligible(){return eligible;}
    public boolean guaranteedAir(int x,int y,int z,Set<CubeKey> savedOverrideKeys) {
        Objects.requireNonNull(savedOverrideKeys);
        if(!eligible)return false;
        boolean upper=y>=-250&&y<64,positive=y>0,exterior=positive&&y>island.maximumSurfaceY();
        // Negative deep-ray cells outside the opening remain on authoritative geometry.
        if(!upper&&!positive)return false;
        double radiusSquared=(double)x*x+(double)z*z;
        if(positive&&radiusSquared>=4000000){
            // At integer R>=2000, max(|x|,|z|)>=1415: the fixed128m correction
            // fade beyond1280 clamps to exactly1. coast<=1080+106=1186, so d>=814.
            // The fixed PCHIP's derivative Bernstein controls are nonpositive in
            // all8 intervals; d>=320 implies bed<=-105. Channels only lower it,
            // and waves add at most32. Binary64 error is far below the73m margin:
            // finite clamped inputs/intermediates keep the computed height below0.
            // Owned terrain is therefore negative, water ends at0, and plants
            // require a positive top>12. Avoid all seabed/slope/column sampling.
            if(y<generator.knownLowerY(x,z))return false;
            return !savedOverrideKeys.contains(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)));
        }
        if(upper&&radiusSquared<upperOpeningRadiusSquared){
            if(y<generator.knownLowerY(x,z))return false;
            // InsideR500 island delegates. Verified v14 carving returnsAIR before evaluating
            // any parent cave/trail/ecology material. Saved overrides veto the entire cube.
            return !savedOverrideKeys.contains(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)));
        }
        // Exact IslandSurfaceGeneration ownership. The negative-Y exception is already excluded.
        if(!exterior||radiusSquared<250000||y<generator.knownLowerY(x,z))return false;
        // Independent terrain guard: do not assume that the exterior analytic seabed is bounded
        // by the finite asset's maximum. This is precisely the owned column's c.top calculation.
        if(y<=Math.floor(island.surfaceHeight(x,z)))return false;
        // Owned terrain ends at c.top; water ends at0; the complete plant branch requires
        // y<=maximum. Island is the generator's final authority after the upper opening.
        return !savedOverrideKeys.contains(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)));
    }
}
