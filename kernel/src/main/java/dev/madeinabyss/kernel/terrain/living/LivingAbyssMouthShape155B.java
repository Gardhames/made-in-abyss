package dev.madeinabyss.kernel.terrain.living;

import static dev.madeinabyss.kernel.terrain.living.LivingAbyssMath155.*;

/** Bent/noisy superellipse sections shared by B hall openings and cave-mouth adapters. */
public final class LivingAbyssMouthShape155B {
    private LivingAbyssMouthShape155B(){}
    public static double axisAngle(double theta){double phi=Math.toRadians(130),a=Math.toRadians(theta);
        double tangent=Math.tan(Math.toRadians(5.5))*(Math.cos(phi)*Math.cos(a)+Math.sin(phi)*Math.sin(a));
        tangent+=2*.35*Math.tan(Math.toRadians(2.5))*Math.cos(2*a+Math.toRadians(40));return -Math.atan(tangent);
    }
    public static double exponent(long seed,long salt){return 2.5+1.5*mixUnit(seed^salt^0x155bcaL);}
    /** Radial variation is bounded by +/-20%; no regular circle/ellipse is used. */
    private static double erosion(long seed,long salt,double t,double depth){return .16*(2*noise(seed,t,depth,9,9,salt+0x155b31L)-1)
        +.04*(2*noise(seed,t,depth,3,3,salt+0x155b32L)-1);}
    public static double sectionValue(long seed,long salt,double t,double v,double hw,double hh,double depth){
        double n=exponent(seed,salt),rho=Math.pow(Math.pow(Math.abs(t/hw),n)+Math.pow(Math.abs(v/hh),n),1/n);
        return rho/(1+erosion(seed,salt,t,depth));
    }
    public static boolean opening(long seed,LivingAbyssRevision155.Opening mouth,double theta,double depth){
        return new Section(seed,mouth).value(theta,depth)<0;
    }
    /** All per-descriptor trigonometry/exponents are compiled once for real halls. */
    public static final class Section {
        private final long seed;private final LivingAbyssRevision155.Opening mouth;
        private final double hw,hh,cos,sin,n;
        public Section(long seed,LivingAbyssRevision155.Opening mouth){this.seed=seed;this.mouth=mouth;hw=mouth.w()/2;hh=mouth.ah()/2;
            double angle=axisAngle(mouth.theta());cos=Math.cos(angle);sin=Math.sin(angle);n=exponent(seed,mouth.seed());}
        /** Signed metric constraint; its zero set is exactly the authored B section. */
        public double value(double theta,double depth){double cx=LivingAbyssRevision155.signedArc(theta,mouth.theta(),450),v=depth-(mouth.floor()-hh);
            if(Math.abs(cx)>hw*1.6+2||Math.abs(v)>hh*1.6+2)return Math.max(Math.abs(cx)-hw,Math.abs(v)-hh);
            double t=cx*cos+v*sin;v=-cx*sin+v*cos;
            if(mouth.port())return (sectionValue(seed,mouth.seed(),t,v,hw,hh,depth)-1)*Math.min(hw,hh);
            double up=hh-v,u=t/hw,un=Math.max(-1,Math.min(1,u-mouth.skew()*(1-clamp(u*u))));
            double vault=mouth.ah()*mouth.sf()+mouth.ah()*(1-mouth.sf())*Math.pow(Math.max(0,1-Math.pow(Math.abs(un),n)),1/n);
            double edge=hw*erosion(seed,mouth.seed(),t,depth),flow=.35+.65*Math.pow(clamp(up/(.4*mouth.ah())),.6);
            return Math.max(-up,Math.max(Math.abs(t)-hw*flow-edge,up-vault-edge));
        }
    }
    private static double noise(long seed,double u,double v,double px,double py,long salt){
        double x=u/px,y=v/py;long ix=(long)Math.floor(x),iy=(long)Math.floor(y);
        double fx=LivingAbyssFields.smooth(x-ix),fy=LivingAbyssFields.smooth(y-iy);long s=seed^LivingAbyssFields.mix(salt);
        double a=mixUnit(s^LivingAbyssFields.mix(ix)^Long.rotateLeft(LivingAbyssFields.mix(iy),31));
        double b=mixUnit(s^LivingAbyssFields.mix(ix+1)^Long.rotateLeft(LivingAbyssFields.mix(iy),31));
        double c=mixUnit(s^LivingAbyssFields.mix(ix)^Long.rotateLeft(LivingAbyssFields.mix(iy+1),31));
        double d=mixUnit(s^LivingAbyssFields.mix(ix+1)^Long.rotateLeft(LivingAbyssFields.mix(iy+1),31));
        return (a+(b-a)*fx)*(1-fy)+(c+(d-c)*fx)*fy;
    }
}
