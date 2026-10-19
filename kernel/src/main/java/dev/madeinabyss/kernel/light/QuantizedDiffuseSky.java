package dev.madeinabyss.kernel.light;

import java.util.Objects;

/**
 * Exact integer diffuse response with bounded occlusion prefixes. This does not approximate or
 * replace SkyVisibility's fraction API. An unfinished ray has final transmission in [0,prefix];
 * outward-rounded interval arithmetic must prove a single rounded display level before stopping.
 * Geometry in an unneeded tail is not consulted, just as a tail beyond an opaque voxel is not.
 * UNKNOWN, invalid opacity, coordinate overflow and unresolved budgets in consulted prefixes throw.
 */
public final class QuantizedDiffuseSky {
    private static final Direction[] FINAL=directions(32),PREVIEW=directions(8);
    private static final int BATCH=8;
    private QuantizedDiffuseSky(){}
    private record Direction(int stepX,int stepZ,double deltaX,double deltaY,double deltaZ){}
    private static final class Ray {
        int x,y,z,visited;
        double nextX,nextY,nextZ,transmission=1.0;
        boolean complete;
        final Direction direction;
        Ray(int x,int y,int z,Direction d){this.x=x;this.y=y;this.z=z;direction=d;nextX=.5*d.deltaX;nextY=.5*d.deltaY;nextZ=.5*d.deltaZ;}
        void step(int sky,int budget,SkyVisibility.VoxelOpacity opacity){
            int count=Math.min(BATCH,budget-visited);
            for(int i=0;i<count&&!complete;i++){
                int blocked=opacity.opacity(x,y,z);visited++;
                if(blocked<0||blocked>15)throw new IllegalArgumentException("Voxel opacity must be 0..15");
                if(blocked==15){transmission=0;complete=true;break;}
                if(blocked>0)transmission*=(15.0-blocked)/15.0;
                double crossing=Math.min(nextX,Math.min(nextY,nextZ));
                if(nextX==crossing){x=advance(x,direction.stepX);nextX+=direction.deltaX;}
                if(nextY==crossing){y=advance(y,1);nextY+=direction.deltaY;}
                if(nextZ==crossing){z=advance(z,direction.stepZ);nextZ+=direction.deltaZ;}
                if(y>=sky)complete=true;
            }
        }
    }
    /** Same directions, accumulation order and scalar response as the original 8/32-ray oracle. */
    public static int level(int x,int y,int z,int openSkyY,int maxStepsPerRay,SkyVisibility.VoxelOpacity opacity,boolean preview){
        Objects.requireNonNull(opacity,"opacity");
        if(maxStepsPerRay<1)throw new IllegalArgumentException("Ray step budget must be positive");
        if(y>=openSkyY)return SkyVisibility.diffuseLevel(1);
        var directions=preview?PREVIEW:FINAL;var rays=new Ray[directions.length];
        for(int i=0;i<rays.length;i++)rays[i]=new Ray(x,y,z,directions[i]);
        while(true){
            for(var ray:rays)if(!ray.complete)ray.step(openSkyY,maxStepsPerRay,opacity);
            double lower=0,upper=0;boolean allComplete=true,exhausted=false;
            // IEEE addition is monotone on finite nonnegative inputs. Using the oracle's exact
            // accumulation order bounds its rounded sum, rather than summing in a new order.
            for(var ray:rays){
                lower+=ray.complete?ray.transmission:0;
                upper+=ray.transmission;
                if(!ray.complete){allComplete=false;exhausted|=ray.visited>=maxStepsPerRay;}
            }
            if(allComplete)return SkyVisibility.diffuseLevel(lower/rays.length);
            int certified=certify(lower/rays.length,upper/rays.length);
            if(certified>=0)return certified;
            if(exhausted)throw new IllegalStateException("Sky visibility ray step budget exhausted");
        }
    }
    /** -1 means uncertainty, including a rounding boundary; no arbitrary transmission epsilon. */
    static int certify(double lower,double upper){
        if(lower==0&&upper==0)return 0;
        // Bound each operation of 15*(4*f/(1+3*f)), including roundoff and a correlated
        // numerator/denominator. Wider independent bounds may defer, never falsely certify.
        double numeratorLow=nonnegativeDown(4*lower),numeratorHigh=Math.nextUp(4*upper);
        double denominatorLow=Math.nextDown(1+nonnegativeDown(3*lower));
        double denominatorHigh=Math.nextUp(1+Math.nextUp(3*upper));
        double responseLow=nonnegativeDown(15*nonnegativeDown(numeratorLow/denominatorHigh));
        double responseHigh=Math.nextUp(15*Math.nextUp(numeratorHigh/denominatorLow));
        int minimum=Math.min(14,(int)Math.round(responseLow));
        int maximum=Math.min(14,(int)Math.round(responseHigh));
        return minimum==maximum?minimum:-1;
    }
    private static double nonnegativeDown(double value){return Math.max(0,Math.nextDown(value));}
    private static Direction[] directions(int count){
        var result=new Direction[count];double goldenAngle=Math.PI*(3.0-Math.sqrt(5.0));
        for(int i=0;i<count;i++){
            double dy=Math.sqrt((i+0.5)/count),radius=Math.sqrt(1.0-dy*dy),azimuth=i*goldenAngle;
            double dx=radius*Math.cos(azimuth),dz=radius*Math.sin(azimuth);
            result[i]=new Direction(dx>0?1:dx<0?-1:0,dz>0?1:dz<0?-1:0,
                    dx==0?Double.POSITIVE_INFINITY:1.0/Math.abs(dx),1.0/dy,
                    dz==0?Double.POSITIVE_INFINITY:1.0/Math.abs(dz));
        }
        return result;
    }
    private static int advance(int coordinate,int step){
        long next=(long)coordinate+step;
        if(next<Integer.MIN_VALUE||next>Integer.MAX_VALUE)throw new IllegalStateException("Sky visibility ray exceeds signed world coordinates");
        return (int)next;
    }
}
