package dev.madeinabyss.kernel.terrain.living;

/** v15 angular fields. 0=-Z, clockwise; independent from v14's internal sector phase. */
public final class LivingAbyssFields {
    private final long seed;
    private final double wet,noise,plateau,transition;
    private final double[] boundaries;
    private final double[] boundaryPhases=new double[5],boundaryLow=new double[4],boundaryHigh=new double[4];
    public LivingAbyssFields(long seed,double wet,double[] boundaries,double noise,double plateau,double transition) {
        if(boundaries.length!=5||noise<0||noise>25||!Double.isFinite(wet)||plateau<0||transition<=0
                ||Math.abs(2*plateau+transition-90)>1e-9)throw new IllegalArgumentException("Invalid v15 angular fields");
        this.seed=seed;this.wet=normalize(wet);this.boundaries=boundaries.clone();this.noise=noise;this.plateau=plateau;this.transition=transition;
        double previous=0;for(double b:boundaries){if(!Double.isFinite(b)||b-previous<=2*noise)throw new IllegalArgumentException("Overlapping zone boundaries");previous=b;}
        for(int i=0;i<5;i++)boundaryPhases[i]=(mix(seed+i*0x9e3779b97f4a7c15L)>>>11)*0x1.0p-53*2*Math.PI;
        // The sine weights sum to one. Slack conservatively covers rounding at both ends.
        for(int i=0;i<4;i++){boundaryLow[i]=boundaries[i]-noise-1e-9;boundaryHigh[i]=boundaries[i]+noise+1e-9;}
    }
    /** Bit-identical to ((d%360)+360)%360 (verified over 30M values and the whole azimuth grid) without two fmod calls. */
    public static double normalize(double degrees){
        if(degrees>=0&&degrees<360){double r=(degrees+360.0)-360.0;return r==360.0?0.0:r;}
        if(degrees<0&&degrees>-360){double r=degrees+360.0;return r==360.0?0.0:r;}
        return ((degrees%360)+360)%360;
    }
    /** sqrt of the exact sum of squares; Math.hypot is a slow software fdlibm routine on Java 17. */
    public static double hypot(double x,double z){return Math.sqrt(x*x+z*z);}
    public static double azimuth(double x,double z){return normalize(Math.toDegrees(Math.atan2(x,-z)));}
    public static double delta(double a,double b){return normalize(a-b+180)-180;}
    /** Wet, karst, sunny, forest, in clockwise order. */
    public double[] sideWeights(double theta) {
        double q=normalize(theta-wet),f=q/90;int i=(int)Math.floor(f);double offset=q-i*90;
        double[] w=new double[4];
        if(offset<=plateau)w[i]=1;
        else if(offset>=90-plateau)w[(i+1)%4]=1;
        else{double t=smooth((offset-plateau)/transition);w[i]=1-t;w[(i+1)%4]=t;}
        return w;
    }
    public double boundary(int index,double theta) {
        double phase=boundaryPhases[index];
        double a=Math.toRadians(normalize(theta));
        return boundaries[index]+noise*(.65*Math.sin(3*a+phase)+.35*Math.sin(7*a-phase));
    }
    public int zone(int x,int y,int z){return zoneAtDepth(-((double)y),azimuth(x,z));}
    public int zoneAtDepth(double depth,double theta){
        if(!Double.isFinite(theta))return 4;
        for(int i=0;i<4;i++){
            if(depth<boundaryLow[i])return i;
            if(depth>=boundaryHigh[i])continue;
            if(depth<boundary(i,theta))return i;
        }return 4;
    }
    public double wetDegrees(){return wet;}
    public static double smooth(double t){t=Math.max(0,Math.min(1,t));return t*t*(3-2*t);}
    public static long mix(long v){v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;return v^(v>>>31);}
}
