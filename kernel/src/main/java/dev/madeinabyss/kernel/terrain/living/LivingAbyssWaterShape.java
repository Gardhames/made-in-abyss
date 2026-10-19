package dev.madeinabyss.kernel.terrain.living;

/** Metre-space equations transferred from Claude's section17/22 prototypes. */
public final class LivingAbyssWaterShape {
    private LivingAbyssWaterShape(){}
    public static double depth(double rr,double radius,double depth,double shelfWidth,double bottomNoise){
        if(rr>=1)return 0;double shelf=shelfWidth/radius;
        double value=rr>1-shelf?1.2*(1-rr)/shelf:1.2+(depth-1.2)*Math.pow(Math.max(0,Math.min(1,(1-shelf-rr)/.28)),.8)*(1-.2*rr*rr);
        return Math.max(0,value+(bottomNoise-.5)*1.6);
    }
    /** Local X grows from the wall into the shaft; signed coordinates obey the same rule. */
    public static double outwardWater(double previous,double face){return Math.max(previous,face+1);}
}
