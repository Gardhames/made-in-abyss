package dev.madeinabyss.kernel.terrain.living;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class LivingAbyssFieldsOptimizationTest {
    private static final double[] BOUNDARIES={120,450,800,1150,1350};
    private static double boundary(long seed,int index,double theta,double noise) {
        double phase=(LivingAbyssFields.mix(seed+index*0x9e3779b97f4a7c15L)>>>11)*0x1.0p-53*2*Math.PI;
        double a=Math.toRadians(LivingAbyssFields.normalize(theta));
        return BOUNDARIES[index]+noise*(.65*Math.sin(3*a+phase)+.35*Math.sin(7*a-phase));
    }
    private static int zone(long seed,double depth,double theta,double noise) {
        for(int i=0;i<4;i++)if(depth<boundary(seed,i,theta,noise))return i;return 4;
    }
    @Test void phasesAndConservativeDepthGatesPreserveBoundaryAndZoneBits() {
        var random=new Random(1515);
        for(long seed:new long[]{0,20261003,-20261003,Long.MAX_VALUE})for(double noise:new double[]{0,1,25}) {
            var fields=new LivingAbyssFields(seed,20,BOUNDARIES,noise,22.5,45);
            for(int sample=0;sample<40_000;sample++) {
                double theta=random.nextDouble()*1440-720,depth=random.nextDouble()*1900-400;int index=sample%5;
                double edge=boundary(seed,index,theta,noise);
                assertEquals(Double.doubleToRawLongBits(edge),Double.doubleToRawLongBits(fields.boundary(index,theta)));
                assertEquals(zone(seed,depth,theta,noise),fields.zoneAtDepth(depth,theta));
                for(double d:new double[]{Math.nextDown(edge),edge,Math.nextUp(edge),BOUNDARIES[index]-noise,BOUNDARIES[index]+noise})
                    assertEquals(zone(seed,d,theta,noise),fields.zoneAtDepth(d,theta));
            }
            for(double depth:new double[]{Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NaN,-0.0,0})
                for(double theta:new double[]{-360,0,360,Double.NaN,Double.POSITIVE_INFINITY})
                    assertEquals(zone(seed,depth,theta,noise),fields.zoneAtDepth(depth,theta));
        }
    }
}
