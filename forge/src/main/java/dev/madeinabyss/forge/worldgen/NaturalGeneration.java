package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.natural.*;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import java.util.Optional;

/** One optional global recipe, shared by native columns, cubic chunks, lighting and LOD sampling.
 * The wrapper is opt-in per immutable profile. Old profile sources and stores remain unchanged. */
public final class NaturalGeneration {
    private final TerrainParameters parameters;
    private final ParametricTerrainSampler baseline;
    private final CliffTerrainSampler cliffs;
    private final GlobalEcologyOverlay ecology;
    private final double minimumRadius,maximumRadius;
    public NaturalGeneration(TerrainParameters p,CliffParameters recipe,TrailNetwork.SampleSource source,
                             CliffTerrainSampler.Protection protectedArea) {
        parameters=p;baseline=new ParametricTerrainSampler(p);
        cliffs=new CliffTerrainSampler(p,recipe,source::sample,protectedArea);
        ecology=new GlobalEcologyOverlay(recipe.seed(),cliffs::sample,protectedArea::protectedAt,this::surfaceBand);
        var c=p.cavity();
        minimumRadius=Math.max(0,Math.min(c.mouthRadius(),c.lowerRadius())-p.noise().wallAmplitude()-c.ledgeProjection()-32);
        maximumRadius=Math.max(c.mouthRadius(),c.lowerRadius())+p.noise().wallAmplitude()+36;
    }
    public TerrainSample sample(int x,int y,int z){return cliffs.sample(x,y,z);}
    public Optional<NaturalBlock> decoration(int x,int y,int z,TerrainSample sample){return ecology.sampleOverride(x,y,z,sample);}
    public CliffTerrainSampler cliffs(){return cliffs;}
    public GlobalEcologyOverlay ecology(){return ecology;}
    /** Prune deep rock/open shaft before plant masks or local candidate planning. The
     * lower probe retains trees above a projecting shelf at the same XZ as upper air. */
    private boolean surfaceBand(int x,int y,int z) {
        var c=parameters.cavity();var s=parameters.surface();
        if(y<c.declaredLowerBoundY()||y>=320)return false;
        double radial=Math.hypot((double)x,(double)z);
        if(y>=s.oceanLevelY()-2&&Math.abs(y-baseline.surfaceHeight(x,z))<=12&&radial>=c.mouthRadius()-16)return true;
        if(y>=c.firstLayerTopY()||radial<minimumRadius||radial>maximumRadius)return false;
        double wall=baseline.cavityRadius(x,y,z);
        if(Math.abs(radial-wall)<=16)return true;
        long lower=(long)y-12;
        return lower>=c.declaredLowerBoundY()&&radial<wall&&radial>=baseline.cavityRadius(x,(int)lower,z)-4;
    }
}
