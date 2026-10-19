package dev.madeinabyss.kernel.terrain.living;

import java.util.*;
import static dev.madeinabyss.kernel.terrain.living.LivingAbyssMath155.*;

/** Section24.1: immutable depth column, facies and interior rock use the SAME lookup. */
public final class LivingAbyssStrata155 {
    public record Band(double top,double bottom,int zone,String primary,String secondary,boolean replace,
                       List<Double> thresholds,List<String> substitutes) {
        public Band {thresholds=List.copyOf(thresholds);substitutes=List.copyOf(substitutes);}
    }
    private static final String[][] NAMES={
        {"stone","andesite","calcite","white_terracotta","diorite"},
        {"calcite","white_terracotta","diorite","terracotta","stone","andesite","yellow_terracotta"},
        {"tuff","stone","calcite","dripstone_block","andesite","granite","white_terracotta"},
        {"stone","tuff","deepslate","andesite"},
        {"deepslate","cobbled_deepslate","tuff","blackstone"}};
    private static final int[][] WEIGHTS={{30,25,25,12,8},{24,18,14,14,14,10,6},{24,20,16,14,14,6,6},{30,26,26,18},{50,22,18,10}};
    private static final String[][][] SUB={
        {{"tuff","stone"},{"calcite","white_terracotta"},{"tuff","andesite"},{"andesite","stone"}},
        {{"tuff","stone"},{"calcite","white_terracotta","terracotta"},{"tuff","dripstone_block"},{"andesite","stone"}},
        {{"tuff","stone"},{"calcite","white_terracotta"},{"tuff","dripstone_block"},{"andesite","stone"}},
        {{"deepslate","tuff"},{"stone","andesite"},{"tuff","stone"},{"stone","deepslate"}},
        {{"deepslate","tuff"},{"tuff","stone"},{"tuff","cobbled_deepslate"},{"cobbled_deepslate","deepslate"}}};
    private final long seed;
    private final double wet;
    private final List<Band> bands;
    private final double[] tops;
    private record Angular(long bits,double tilt,double wet,double sunny,double karst,double forest,double sum) {}
    private final Angular[] angular=new Angular[1<<15];
    private static final int QUANTILE_STEPS=32;
    private static final double[][] SECONDARY_QUANTILES=secondaryQuantiles();
    public LivingAbyssStrata155(long seed,double wet){
        this.seed=seed;this.wet=wet;var rng=new Rng(seed^0x15557a7aL);var out=new ArrayList<Band>();
        double d=-120;String previous="";
        while(d<1480){
            double thickness=rng.unit()<.2?rng.range(2,5):rng.range(5,26);int z=zone(d);
            String primary;do{primary=weighted(z,rng.unit());}while(primary.equals(previous));
            String secondary;do{secondary=weighted(z,rng.unit());}while(secondary.equals(primary));
            boolean replace=rng.unit()<.55;var thresholds=new ArrayList<Double>();var substitutes=new ArrayList<String>();
            for(int s=0;s<4;s++){thresholds.add(rng.range(.35,.95));substitutes.add(SUB[z][s][rng.pick(SUB[z][s].length)]);}
            out.add(new Band(d,d+thickness,z,primary,secondary,replace,thresholds,substitutes));previous=primary;d+=thickness;
        }
        bands=List.copyOf(out);tops=bands.stream().mapToDouble(Band::top).toArray();
    }
    private static String weighted(int zone,double unit){double draw=unit*100;for(int i=0;i<WEIGHTS[zone].length;i++){draw-=WEIGHTS[zone][i];if(draw<0)return NAMES[zone][i];}return NAMES[zone][NAMES[zone].length-1];}
    public int bandIndex(double d){int i=Arrays.binarySearch(tops,d);return Math.max(0,i>=0?i:-i-2);}
    public static double tilt(double theta){return .35*520*Math.tan(Math.toRadians(5.5+2.5*Math.sin(Math.toRadians(2*theta+40))))*Math.sin(Math.toRadians(theta-130));}
    private Angular angular(double theta){long bits=Double.doubleToLongBits(theta);int slot=(int)LivingAbyssFields.mix(bits)&(angular.length-1);var c=angular[slot];
        if(c!=null&&c.bits==bits)return c;
        double a=side(theta,wet),s=side(theta,wet+180),k=side(theta,wet+90),f=side(theta,wet+270);
        c=new Angular(bits,tilt(theta),a,s,k,f,Math.max(1e-15,a+s+k+f));angular[slot]=c;return c;}
    public String material(double theta,int x,int y,int z){
        var c=angular(theta);double depth=-((double)y),dd=depth+c.tilt+(noise(seed,theta,depth,9,3,0x15501L,520)-.5)*4;
        int i=bandIndex(dd);var b=bands.get(i);String primary=facies(i,theta,depth,c);
        double block=mixUnit(seed^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),21)^Long.rotateLeft(LivingAbyssFields.mix(z),42)^0x15b10cL);
        return .7*noise(seed,theta,depth,3,8,0x15503L,520)+.3*block<secondaryThreshold(theta,depth)?b.secondary:primary;
    }
    private String facies(int i,double theta,double depth,Angular c){var b=bands.get(i);String primary=b.primary;
        if(!b.replace)return primary;
        double e=(noise(seed,theta,depth,40,6,0x15502L+i,520)-.5)*.25;
        if(c.wet/c.sum+e>b.thresholds.get(0))primary=b.substitutes.get(0);
        if(c.sunny/c.sum+e>b.thresholds.get(1))primary=b.substitutes.get(1);
        if(c.karst/c.sum+e>b.thresholds.get(2))primary=b.substitutes.get(2);
        if(c.forest/c.sum+e>b.thresholds.get(3))primary=b.substitutes.get(3);
        return primary;
    }
    /** The section21 70/30 selector is NOT uniform (threshold .25 gave only8.2%).
     * Its exact independent-lattice CDF restores the authored75/25 expectation,
     * retaining the same coherent ordering. Bilinear interpolation of33² immutable
     * quantiles avoids a per-block numerical solve. No palette weight is adjusted. */
    public static double secondaryThreshold(double theta,double depth){
        int count=Math.max(2,(int)Math.floor(2*Math.PI*520/3+.5));double gx=LivingAbyssFields.normalize(theta)/360*count,gy=depth/8;
        double x=LivingAbyssFields.smooth(gx-Math.floor(gx))*QUANTILE_STEPS,y=LivingAbyssFields.smooth(gy-Math.floor(gy))*QUANTILE_STEPS;
        int ix=Math.min(QUANTILE_STEPS-1,(int)x),iy=Math.min(QUANTILE_STEPS-1,(int)y);double fx=x-ix,fy=y-iy;
        return (SECONDARY_QUANTILES[ix][iy]*(1-fx)+SECONDARY_QUANTILES[ix+1][iy]*fx)*(1-fy)
            +(SECONDARY_QUANTILES[ix][iy+1]*(1-fx)+SECONDARY_QUANTILES[ix+1][iy+1]*fx)*fy;
    }
    private static double[][] secondaryQuantiles(){var out=new double[QUANTILE_STEPS+1][QUANTILE_STEPS+1];
        for(int ix=0;ix<=QUANTILE_STEPS;ix++)for(int iy=0;iy<=QUANTILE_STEPS;iy++){
            double x=ix/(double)QUANTILE_STEPS,y=iy/(double)QUANTILE_STEPS;
            double[] all={.7*(1-x)*(1-y),.7*x*(1-y),.7*(1-x)*y,.7*x*y,.3};
            double[] weights=Arrays.stream(all).filter(v->v>1e-12).toArray();int n=weights.length;
            double denominator=1;for(int i=1;i<=n;i++)denominator*=i;for(double w:weights)denominator*=w;
            double low=0,high=1;
            for(int iteration=0;iteration<40;iteration++){double q=(low+high)/2,sum=0;
                for(int mask=0;mask<(1<<n);mask++){double v=q;for(int i=0;i<n;i++)if((mask&(1<<i))!=0)v-=weights[i];
                    if(v>0){double term=1;for(int i=0;i<n;i++)term*=v;sum+=(Integer.bitCount(mask)&1)==0?term:-term;}}
                if(sum/denominator<.25)low=q;else high=q;
            }out[ix][iy]=(low+high)/2;
        }return out;
    }
    /** Hardness belongs to the band, not a per-voxel speckle on the surface. */
    public double hardness(double theta,double depth){var c=angular(theta);double dd=depth+c.tilt+(noise(seed,theta,depth,9,3,0x15501L,520)-.5)*4;
        return hardness(facies(bandIndex(dd),theta,depth,c));}
    public static double hardness(String name){return switch(name){case "calcite"->.9;case "diorite"->.7;case "white_terracotta"->.6;
        case "terracotta","yellow_terracotta"->.5;case "granite"->.4;case "deepslate"->.3;case "andesite"->.2;case "dripstone_block"->-.6;case "tuff"->-.8;default->0;};}
    public List<Band> bands(){return bands;}
}
