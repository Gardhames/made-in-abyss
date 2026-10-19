package dev.madeinabyss.kernel.terrain.natural;

import dev.madeinabyss.kernel.terrain.*;
import java.util.Objects;

/**
 * Opt-in ORIGINAL rock weathering. Cartesian rim fields avoid an azimuth seam; no section keys,
 * mutable RNG, recursive source lookups, new material categories or giant drilled tunnels.
 * NPS Yosemite joints and Mesa Verde layered alcoves inform the morphology, not Abyss canon.
 * This shallow subtractive pass retains the source's broad silhouette and all fertile ground.
 */
public final class CliffTerrainSampler {
    @FunctionalInterface public interface Source {TerrainSample sample(int x,int y,int z);}
    @FunctionalInterface public interface Protection {boolean protectedAt(int x,int y,int z);}
    public record Feature(double recessMetres,double fracture,double pocket,double dampness){}
    private static final Feature NONE=new Feature(0,0,0,0);
    private static final TerrainSample.Known AIR=new TerrainSample.Known(TerrainMaterial.AIR);
    private final TerrainParameters p;
    private final CliffParameters recipe;
    private final ParametricTerrainSampler geometry;
    private final Source source;
    private final Protection mask;
    private final double innerShell,outerShell;
    public CliffTerrainSampler(TerrainParameters p,CliffParameters recipe,Source source,Protection mask){
        this.p=Objects.requireNonNull(p);this.recipe=Objects.requireNonNull(recipe);
        this.source=Objects.requireNonNull(source);this.mask=Objects.requireNonNull(mask);
        geometry=new ParametricTerrainSampler(p);
        var c=p.cavity();double amplitude=p.noise().wallAmplitude();
        // The geology pass deliberately leaves strongly projecting broad shelves intact.
        innerShell=Math.max(1,Math.min(c.mouthRadius(),c.lowerRadius())-amplitude-24);
        outerShell=Math.max(c.mouthRadius(),c.lowerRadius())+amplitude+recipe.maximumRecessMetres()+2;
    }
    /** Cheap conservative prefilter, useful to LOD/ecology; no source/radius/noise evaluation. */
    public boolean mayAffectShell(int x,int y,int z){
        if(y<p.cavity().declaredLowerBoundY() || y>=p.cavity().firstLayerTopY()-recipe.mouthBlendMetres())return false;
        double r2=(double)x*x+(double)z*z;
        return r2>=innerShell*innerShell && r2<=outerShell*outerShell;
    }
    public TerrainSample sample(int x,int y,int z){
        TerrainSample original=source.sample(x,y,z);
        if(!(original instanceof TerrainSample.Known known) || known.material()!=TerrainMaterial.ROCK
                || !mayAffectShell(x,y,z) || mask.protectedAt(x,y,z))return original;
        double radial=Math.hypot((double)x,(double)z);
        double wall=geometry.cavityRadius(x,y,z),distance=radial-wall;
        // A wrapper must never substitute geometry when an upstream source supplied a different solid.
        if(distance<0 || distance>recipe.maximumRecessMetres())return original;
        double weight=shelfWeight(wall,y);
        if(weight==0)return original;
        return distance<featureAt(x,y,z).recessMetres()*weight ? AIR:original;
    }
    /** Continuous diagnostics. Values are dimensionless except the bounded inward recess. */
    public Feature featureAt(int x,int y,int z){
        if(!mayAffectShell(x,y,z))return NONE;
        double radius=Math.hypot((double)x,(double)z);
        if(radius==0)return NONE;
        double rimX=x/radius*p.cavity().mouthRadius(),rimZ=z/radius*p.cavity().mouthRadius();
        double depth=p.cavity().firstLayerTopY()-y;
        double envelope=smooth(clamp((depth-recipe.mouthBlendMetres())/recipe.mouthBlendMetres()));
        // Long, gently tilted ribs with sheet joints; frequency is anisotropic, never per voxel RNG.
        double ribs=noise2(rimX/recipe.ribSpacingMetres()+y/recipe.verticalRibScaleMetres()*0.18,
                rimZ/recipe.ribSpacingMetres()-y/recipe.verticalRibScaleMetres()*0.11,11);
        double finer=noise2(rimX/(recipe.ribSpacingMetres()*0.42)+y/recipe.verticalRibScaleMetres()*0.23,
                rimZ/(recipe.ribSpacingMetres()*0.42),12);
        double fracture=smooth(clamp(1-Math.abs(ribs)/(recipe.fractureWidthMetres()/recipe.ribSpacingMetres())));
        double strataWarp=noise2(rimX/160,rimZ/160,21);
        double phase=(y/recipe.strataSpacingMetres()+strataWarp*0.35)*Math.PI*2;
        double bedding=smooth(clamp(1-Math.abs(Math.sin(phase))/0.42));
        double chamber=noise3(rimX/recipe.pocketWidthMetres(),y/recipe.pocketHeightMetres(),
                rimZ/recipe.pocketWidthMetres(),31);
        double pocketClusters=smooth(clamp((noise2(rimX/recipe.pocketSpacingMetres(),
                rimZ/recipe.pocketSpacingMetres(),32)+0.35)/0.7));
        double pocket=smooth(clamp((chamber-0.06)/0.44))*bedding*pocketClusters;
        double smallRelief=clamp((finer+1)*0.5);
        double recess=recipe.maximumRecessMetres()*envelope*
                (0.08+0.18*(ribs+1)*0.5+0.13*smallRelief+0.24*fracture+0.37*pocket);
        double dampness=clamp(0.25+0.35*bedding+0.35*pocket+0.05*strataWarp);
        return new Feature(recess,fracture*envelope,pocket*envelope,dampness*envelope);
    }
    private double shelfWeight(double wall,int y){
        var c=p.cavity();
        double fraction=clamp((c.firstLayerTopY()-y)/(c.firstLayerTopY()-c.declaredLowerBoundY()));
        double main=c.mouthRadius()+(c.lowerRadius()-c.mouthRadius())*smooth(fraction);
        return smooth(clamp((wall-(main-p.noise().wallAmplitude()-24))/24));
    }
    private double noise2(double x,double z,long salt){
        long ix=(long)Math.floor(x),iz=(long)Math.floor(z);double fx=smooth(x-ix),fz=smooth(z-iz);
        double a=unit(ix,0,iz,salt),b=unit(ix+1,0,iz,salt),c=unit(ix,0,iz+1,salt),d=unit(ix+1,0,iz+1,salt);
        return lerp(lerp(a,b,fx),lerp(c,d,fx),fz);
    }
    private double noise3(double x,double y,double z,long salt){
        long ix=(long)Math.floor(x),iy=(long)Math.floor(y),iz=(long)Math.floor(z);
        double fx=smooth(x-ix),fy=smooth(y-iy),fz=smooth(z-iz),sum=0;
        for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++)
            sum+=unit(ix+dx,iy+dy,iz+dz,salt)*(dx==0?1-fx:fx)*(dy==0?1-fy:fy)*(dz==0?1-fz:fz);
        return sum;
    }
    private double unit(long x,long y,long z,long salt){
        return (mix(recipe.seed()^mix(x)^Long.rotateLeft(mix(y),21)^Long.rotateLeft(mix(z),42)^mix(salt))>>>11)*0x1.0p-53*2-1;
    }
    private static long mix(long v){v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;return v^(v>>>31);}
    private static double clamp(double v){return Math.max(0,Math.min(1,v));}
    private static double smooth(double v){return v*v*(3-2*v);}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
}
