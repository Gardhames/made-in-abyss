package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.terrain.TerrainParameters;
import java.util.Objects;

/** Analytic AIR bound for the fixed approved geology, including its complete flora support halo.
 * Derived rendering data only. This never changes a sampled material or a world recipe. */
public final class DeepCavityAirBounds {
    private static final int MIN_Y=-1286,MAX_Y=-251,GEOMETRY_TOP=63,Y_HALO=32;
    // A metre is deliberately much larger than binary64 error throughout this fixed domain.
    // Ten more metres cover all composed tree/interior/Alex support reaches.
    private static final double SUPPORT_AND_NUMERIC_MARGIN=11,INTERVAL_MARGIN=1e-8;
    private static final TerrainParameters.Cavity CAVITY=new TerrainParameters.Cavity(500,64,-1286,650,180,170,280);
    private static final TerrainParameters.Noise NOISE=new TerrainParameters.Noise(140,20,60,70);
    private final int[] radii;
    private record Patch(double top,double width,double projection) {}

    public DeepCavityAirBounds(TerrainParameters parameters) {
        Objects.requireNonNull(parameters);
        if(parameters.seed()!=20261003||!parameters.cavity().equals(CAVITY)||!parameters.noise().equals(NOISE)) {
            radii=new int[0];return;
        }
        int sectors=(int)Math.ceil(2*Math.PI*CAVITY.mouthRadius()/NOISE.wavelength());
        int bands=(int)Math.floor((CAVITY.firstLayerTopY()-MIN_Y)/CAVITY.ledgePeriod())+3;
        var patches=new Patch[sectors][bands];
        for(int sector=0;sector<sectors;sector++)for(int band=0;band<bands;band++) {
            patches[sector][band]=new Patch(
                (band+.2+.6*random(parameters.seed(),sector,band,21))*CAVITY.ledgePeriod()
                    +(2*random(parameters.seed(),sector,band,22)-1)*NOISE.ledgeVerticalAmplitude(),
                CAVITY.ledgeWidth()*(.6+.8*random(parameters.seed(),sector,band,23)),
                CAVITY.ledgeProjection()*(.4+.6*random(parameters.seed(),sector,band,24)));
        }
        var lower=new double[GEOMETRY_TOP-MIN_Y+1];
        for(int y=MIN_Y;y<=GEOMETRY_TOP;y++) {
            double depth=CAVITY.firstLayerTopY()-y,maximumExtrusion=0;
            int band=(int)Math.floor(depth/CAVITY.ledgePeriod());
            for(var sector:patches)for(int b=Math.max(0,band-2);b<=band+2;b++) {
                var patch=sector[b];
                double relief=NOISE.surfaceAmplitude()+INTERVAL_MARGIN;
                // Outward-rounded admission includes every possible column-specific top.
                if(depth<patch.top()-relief||depth>patch.top()+relief+patch.width()+INTERVAL_MARGIN)continue;
                double below=Math.max(0,depth-(patch.top()+relief));
                double remaining=Math.max(0,1-below/(patch.width()+INTERVAL_MARGIN));
                double extrusion=(patch.projection()+INTERVAL_MARGIN)*remaining*remaining+INTERVAL_MARGIN;
                maximumExtrusion=Math.max(maximumExtrusion,extrusion);
            }
            double fraction=Math.max(0,Math.min(1,depth/(CAVITY.firstLayerTopY()-MIN_Y)));
            double growth=fraction*fraction*(3-2*fraction);
            // Both wall noise and the shelf envelope are bounded by one. Bounding the
            // envelope by one also conservatively covers the upper end of the Y halo.
            lower[y-MIN_Y]=CAVITY.mouthRadius()+(CAVITY.lowerRadius()-CAVITY.mouthRadius())*growth
                -NOISE.wallAmplitude()-maximumExtrusion-INTERVAL_MARGIN;
        }
        radii=new int[MAX_Y-MIN_Y+1];
        for(int y=MIN_Y;y<=MAX_Y;y++) {
            double minimum=Double.POSITIVE_INFINITY;
            for(int yy=Math.max(MIN_Y,y-Y_HALO);yy<=Math.min(GEOMETRY_TOP,y+Y_HALO);yy++)minimum=Math.min(minimum,lower[yy-MIN_Y]);
            radii[y-MIN_Y]=Math.max(0,(int)Math.floor(minimum-SUPPORT_AND_NUMERIC_MARGIN));
        }
    }
    public int radiusAtY(int y) { return radii.length==0||y<MIN_Y||y>MAX_Y?0:radii[y-MIN_Y]; }
    public boolean guaranteedAir(int x,int y,int z) {
        int radius=radiusAtY(y);return radius>0&&(double)x*x+(double)z*z<(double)radius*radius;
    }
    private static double random(long seed,long sector,long band,long salt) {
        return (mix(seed^mix(sector)^Long.rotateLeft(mix(band),29)^mix(salt))>>>11)*0x1.0p-53;
    }
    private static long mix(long value) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return value^(value>>>31);
    }
}
