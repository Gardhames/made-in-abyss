package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.terrain.TerrainParameters;

/** Conservative cube-wide bounds for the bare wall, never a substitute for shelves or reservations. */
public final class LivingAbyssCubeEnvelope {
    public enum Kind { CENTRE_AIR, DEEP_ROCK, MIXED }
    @FunctionalInterface public interface Radius { double at(int x,int y,int z); }
    public record Bounds(Kind kind,double minimumRadius,double maximumRadius,double minimumWall,double maximumWall) {}
    private LivingAbyssCubeEnvelope() {}
    public static Bounds classify(TerrainParameters p,LivingAbyssRecipe.Domain d,Radius wall,int x,int y,int z) {
        int xx=Math.addExact(x,15),yy=Math.addExact(y,15),zz=Math.addExact(z,15);
        double nearX=x>0?x:xx<0?xx:0,nearZ=z>0?z:zz<0?zz:0;
        double rmin=Math.hypot(nearX,nearZ),rmax=Math.hypot(Math.max(Math.abs((double)x),Math.abs((double)xx)),Math.max(Math.abs((double)z),Math.abs((double)zz)));
        if(y>=d.wallBottomY()&&yy<=d.emptyTopY()&&rmax<d.emptyRadius())return new Bounds(Kind.CENTRE_AIR,rmin,rmax,0,0);
        if(y<d.wallBottomY()||yy>=0||rmin<d.emptyRadius()||rmax>p.cavity().lowerRadius()+p.noise().wallAmplitude())
            return new Bounds(Kind.MIXED,rmin,rmax,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(int qx:new int[]{x,xx})for(int qz:new int[]{z,zz})for(int qy:new int[]{y,yy}){double r=wall.at(qx,qy,qz);min=Math.min(min,r);max=Math.max(max,r);}
        // Smooth lattice noise has per-axis derivative <=3/wavelength. Projection
        // onto the mouth circle is Lipschitz outside R380; rounding adds <=2 L1 metres.
        // The remaining terms bound vertical taper and the mouth envelope, including its transition.
        var c=p.cavity();var n=p.noise();double span=15,projectionL1=Math.sqrt(2)*c.mouthRadius()/rmin*Math.hypot(span,span)+2;
        double margin=n.wallAmplitude()*3/n.wavelength()*(projectionL1+.35*span)
            +1.5*Math.abs(c.lowerRadius()-c.mouthRadius())/(c.firstLayerTopY()-c.declaredLowerBoundY())*span
            +n.wallAmplitude()*1.5/n.wavelength()*span+1e-9;
        min-=margin;max+=margin;
        return new Bounds(rmin>max+8?Kind.DEEP_ROCK:Kind.MIXED,rmin,rmax,min,max);
    }
}
