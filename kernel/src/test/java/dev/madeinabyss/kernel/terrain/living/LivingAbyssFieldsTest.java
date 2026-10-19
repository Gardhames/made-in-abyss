package dev.madeinabyss.kernel.terrain.living;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LivingAbyssFieldsTest {
    @Test void sidePlateausSmoothstepSeamAndPartition() {
        var f=new LivingAbyssFields(827,20,new double[]{120,450,800,1150,1350},25,22.5,45);
        for(int side=0;side<4;side++)for(double off:new double[]{-22.5,-10,0,10,22.5})
            assertEquals(1,f.sideWeights(20+side*90+off)[side],1e-12);
        for(double a=-720;a<720;a+=.125){double sum=0;for(double w:f.sideWeights(a)){assertTrue(w>=0&&w<=1);sum+=w;}assertEquals(1,sum,1e-12);}
        assertArrayEquals(f.sideWeights(0),f.sideWeights(360),1e-12);
        assertEquals(.5,f.sideWeights(65)[0],1e-12);assertEquals(.5,f.sideWeights(65)[1],1e-12);
        assertEquals(0,LivingAbyssFields.azimuth(0,-1));assertEquals(90,LivingAbyssFields.azimuth(1,0));
        assertEquals(180,LivingAbyssFields.azimuth(0,1));assertEquals(270,LivingAbyssFields.azimuth(-1,0));
    }
    @Test void noisyBoundariesArePeriodicOrderedAndBoundedOnNegativeCoordinates() {
        var f=new LivingAbyssFields(827,20,new double[]{120,450,800,1150,1350},25,22.5,45);
        for(int i=0;i<5;i++)for(double a=-360;a<720;a+=.2){double b=f.boundary(i,a);assertTrue(Math.abs(b-new double[]{120,450,800,1150,1350}[i])<=25);assertEquals(b,f.boundary(i,a+360),1e-10);if(i>0)assertTrue(b>f.boundary(i-1,a));}
        assertEquals(f.zone(-550,-500,-220),f.zone(-550,-500,-220));
        assertEquals(0,f.zone(500,-50,0));assertEquals(4,f.zone(-500,-1300,0));
    }
}
