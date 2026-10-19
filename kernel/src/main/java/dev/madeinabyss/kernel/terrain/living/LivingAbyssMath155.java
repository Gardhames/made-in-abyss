package dev.madeinabyss.kernel.terrain.living;

/** Stateless metric formulae shared by the compiled 15.5 descriptors. No historical sampler changes. */
public final class LivingAbyssMath155 {
    private LivingAbyssMath155() {}
    public static double clamp(double x){return Math.max(0,Math.min(1,x));}
    public static double mixUnit(long x){return (LivingAbyssFields.mix(x)>>>11)*0x1.0p-53;}
    public static final class Rng {
        private long state;
        public Rng(long seed){state=seed;}
        public double unit(){state+=0x9e3779b97f4a7c15L;return mixUnit(state);}
        public double range(double a,double b){return a+(b-a)*unit();}
        public int pick(int n){return Math.min(n-1,(int)(unit()*n));}
        public long salt(){return (long)(unit()*0x1.0p52);}
    }
    public static double noise(long seed,double theta,double depth,double px,double py,long salt,double radius){
        int count=Math.max(2,(int)Math.floor(2*Math.PI*radius/px+.5));
        double x=LivingAbyssFields.normalize(theta)/360*count,y=depth/py;
        long ix=(long)Math.floor(x),iy=(long)Math.floor(y);
        double fx=LivingAbyssFields.smooth(x-ix),fy=LivingAbyssFields.smooth(y-iy);
        long s=seed^LivingAbyssFields.mix(salt),x0=LivingAbyssFields.mix(Math.floorMod(ix,count)),x1=LivingAbyssFields.mix(Math.floorMod(ix+1,count));
        long y0=Long.rotateLeft(LivingAbyssFields.mix(iy),31),y1=Long.rotateLeft(LivingAbyssFields.mix(iy+1),31);
        double a=mixUnit(s^x0^y0),b=mixUnit(s^x1^y0),c=mixUnit(s^x0^y1),d=mixUnit(s^x1^y1);
        return (a+(b-a)*fx)*(1-fy)+(c+(d-c)*fx)*fy;
    }
    public static double profile(int kind,double tau,int steps){
        if(tau<0||tau>1)return 0;
        double g;
        if(kind==0)g=Math.pow(Math.max(0,1-Math.pow(tau,2.2)),1/2.2);
        else if(kind==1)g=tau<.14?1:Math.pow(Math.max(0,1-(tau-.14)/.86),1.8);
        else {double k=Math.floor(tau*steps),f=tau*steps-k;
            g=(1-.78*k/steps)*(1-.18*Math.pow(clamp((f-.75)/.25),2))*Math.sqrt(Math.max(0,1-Math.pow(tau,6)));}
        return g*Math.sqrt(clamp(tau/.035+.55));
    }
    public static double plan(double p,double s,double lob,double bay){
        return Math.min(1.15*p,p*Math.pow(clamp(1-Math.pow(Math.abs(s),2.4)),.55)*(1+.45*lob+.18*bay));
    }
    private static final double[] INTEGER_INNER=integerInner();
    private static double[] integerInner(){var out=new double[1425];for(int i=0;i<out.length;i++)out[i]=490-170*Math.pow(clamp(i/1350.0),1.15);return out;}
    public static double inner(double depth){int d=(int)depth;return d>=0&&d<INTEGER_INNER.length&&depth==d?INTEGER_INNER[d]:490-170*Math.pow(clamp(depth/1350),1.15);}
    public static double centre(double depth){return Math.min(385,inner(depth)-100);}
    public static int zone(double depth){return depth<120?0:depth<450?1:depth<800?2:depth<1150?3:4;}
    /** Cosine side influence, half width 90 degrees; same normalized convention as the layout oracle. */
    public static double side(double theta,double centre){return Math.max(0,Math.cos(Math.toRadians(Math.min(90,Math.abs(LivingAbyssFields.delta(theta,centre))))));}
}
