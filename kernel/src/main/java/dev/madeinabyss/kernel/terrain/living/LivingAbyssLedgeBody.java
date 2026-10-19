package dev.madeinabyss.kernel.terrain.living;

/** Claude's corrected section19 profile. Inputs are precomputed once per XZ column. */
public final class LivingAbyssLedgeBody {
    private LivingAbyssLedgeBody(){}
    public static double thickness(double u,double zf,double noise,double wall,double edge,double tier){
        u=clamp(u);zf=Math.min(1,Math.abs(zf));
        double value=(edge+(wall-edge)*Math.pow(1-u,1.6))*(1+.35*Math.sin(Math.PI*u)*(1-zf*zf));
        return Math.max(edge,Math.floor(value/tier+noise*.8)*tier);
    }
    public static double filletDepth(double distance,double zf,double radius){
        double fx=Math.max(0,Math.min(radius,radius-distance));
        return (radius-Math.sqrt(Math.max(0,radius*radius-fx*fx)))*1.6*(1-Math.pow(Math.min(1,Math.abs(zf)),3));
    }
    public static double ribDepth(double distance,double reach,double base){
        return base*Math.pow(1-clamp(distance/reach),1.5);
    }
    public static double clamp(double x){return Math.max(0,Math.min(1,x));}
}
