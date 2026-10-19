package dev.madeinabyss.kernel.terrain.living;

import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Exact155MathTest {
    private static Class<?> implementation(){try{return Class.forName("dev.madeinabyss.kernel.terrain.living.Exact155Math");}catch(ClassNotFoundException missing){return null;}}
    @SuppressWarnings("unchecked") private static Map<String,Long> stats()throws Exception{
        var type=implementation();return type==null?Map.of(): (Map<String,Long>)type.getMethod("threadStats").invoke(null);
    }
    private static void begin()throws Exception{var type=implementation();if(type!=null)type.getMethod("begin").invoke(null);}
    private static double noise(long seed,double theta,double depth,double px,double py,long salt,double radius)throws Exception{
        var type=implementation();if(type==null)return LivingAbyssMath155.noise(seed,theta,depth,px,py,salt,radius);
        return (double)type.getMethod("noise",long.class,double.class,double.class,double.class,double.class,long.class,double.class)
            .invoke(null,seed,theta,depth,px,py,salt,radius);
    }
    @Test void repeatedSignedPointNoiseReusesWorkAndRetainsEveryInputBit()throws Exception{
        long before=stats().getOrDefault("noiseEvaluations",0L);begin();
        double[][] keys={{-720.5,-1424,9,3,450},{-0.,-0.,6,120,450},{0.,0.,6,120,450},{359.999,-1,34,340,520}};
        for(int repeat=0;repeat<200;repeat++)for(var k:keys){
            double expected=LivingAbyssMath155.noise(20261003,k[0],k[1],k[2],k[3],991,k[4]);
            assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(noise(20261003,k[0],k[1],k[2],k[3],991,k[4])));
        }
        long evaluations=stats().getOrDefault("noiseEvaluations",800L)-before;
        assertTrue(evaluations<=4,"The same four exact scalar inputs must not execute the noise function800 times: "+evaluations);
        // Every key dimension is independent: a salt/seed/scale change must never alias a cached result.
        for(long seed:new long[]{Long.MIN_VALUE,0,Long.MAX_VALUE})for(long salt:new long[]{-1,0,991})
            assertEquals(Double.doubleToRawLongBits(LivingAbyssMath155.noise(seed,-12.25,-513,3,8,salt,520)),Double.doubleToRawLongBits(noise(seed,-12.25,-513,3,8,salt,520)));
    }
    @Test void independentStrataObjectsAndDepthsNeverShareAStaleHardness()throws Exception{
        var a=new LivingAbyssStrata155(20261003,7.294196308540847);var b=new LivingAbyssStrata155(20261004,14);
        var type=implementation();long before=stats().getOrDefault("hardnessEvaluations",0L);begin();
        for(int repeat=0;repeat<100;repeat++)for(var strata:List.of(a,b))for(double theta:new double[]{-360,7.25}){
            double expected=strata.hardness(theta,580),actual=type==null?expected:(double)type.getMethod("hardness",LivingAbyssStrata155.class,double.class,double.class).invoke(null,strata,theta,580.);
            assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(actual));
        }
        assertTrue(stats().getOrDefault("hardnessEvaluations",400L)-before<=4,"Repeated exact band hardness must be reused within a point");
    }
    @Test void columnBoundOnlyRejectsPointsOutsideEveryAdmissibleReliefRadius()throws Exception{
        var type=implementation();int rejected=0;
        for(double radius:new double[]{.01,1,5,24,53})for(double factor:new double[]{-4,-1.5000001,-1.5,-1.49,0,1.49,1.5,1.5000001,4}){
            double x=factor*radius,z=.01*radius;
            boolean mayReach=type==null|| (boolean)type.getMethod("mayReachColumn",double.class,double.class,double.class).invoke(null,x,z,radius);
            if(!mayReach){rejected++;for(int i=0;i<=100;i++){double actual=radius+radius*(i/100.-.5);assertFalse(x*x+z*z<actual*actual,"Certified outer rejection cannot delete any allowed displaced column");}}
        }
        assertTrue(rejected>=20,"Distant columns must be rejected before expensive surface relief");
    }
}
