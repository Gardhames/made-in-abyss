package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.terrain.*;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeepCavityAirBoundsTest {
    private static TerrainParameters approved() {
        return new TerrainParameters(20261003,
            new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
            new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),
            new TerrainParameters.Noise(140,20,60,70));
    }
    @Test void usefulDeepRegionsMatchIndependentUncompiledGeologyAtEveryY() {
        var p=approved();var proof=new DeepCavityAirBounds(p);var oracle=new ParametricTerrainSampler(p);
        var random=new Random(135013);int checked=0;
        for(int y=-1286;y<=-251;y++) {
            int radius=proof.radiusAtY(y);
            assertTrue(radius>=150&&radius<500,"Useful bound at Y="+y);
            for(int angle=0;angle<32;angle++) {
                double a=2*Math.PI*angle/32;
                for(int offset:new int[]{-2,-1,0,1}) {
                    int x=(int)Math.round((radius+offset)*Math.cos(a)),z=(int)Math.round((radius+offset)*Math.sin(a));
                    if(proof.guaranteedAir(x,y,z)) {
                        assertEquals(new TerrainSample.Known(TerrainMaterial.AIR),oracle.sample(x,y,z),"Full geological oracle "+x+","+y+","+z);checked++;
                    }
                    if((double)x*x+(double)z*z>=(double)radius*radius)assertFalse(proof.guaranteedAir(x,y,z));
                }
            }
            for(int i=0;i<4;i++) {
                int x=random.nextInt(2*radius-3)-radius+2,z=random.nextInt(2*radius-3)-radius+2;
                if(proof.guaranteedAir(x,y,z)){assertEquals(new TerrainSample.Known(TerrainMaterial.AIR),oracle.sample(x,y,z));checked++;}
            }
        }
        assertTrue(checked>50000);assertTrue(proof.radiusAtY(-900)>=250);assertTrue(proof.radiusAtY(-1200)>=300);
    }
    @Test void fullSupportHaloIsGeologicalAirIncludingSignedAndLowerDomainEdges() {
        var p=approved();var proof=new DeepCavityAirBounds(p);var oracle=new ParametricTerrainSampler(p);
        for(int y:new int[]{-1286,-1254,-900,-700,-432,-431,-430,-400,-283,-251}) {
            int radius=proof.radiusAtY(y);assertTrue(radius>=150);
            for(int[] point:new int[][]{{radius-1,0},{1-radius,0},{0,radius-1},{0,1-radius}})
                for(int dy=-32;dy<=32;dy++)if(y+dy>=-1286&&y+dy<64)
                    for(int[] halo:new int[][]{{0,0},{10,0},{-10,0},{0,10},{0,-10},{7,7},{-7,-7}})
                        assertEquals(new TerrainSample.Known(TerrainMaterial.AIR),oracle.sample(point[0]+halo[0],y+dy,point[1]+halo[1]),"Original geology excludes the whole support halo");
        }
    }
    @Test void differentGeometryAndUnknownOrUpperDomainNeverAcquireTheDeepProof() {
        var p=approved();var good=new DeepCavityAirBounds(p);
        for(int y:new int[]{Integer.MIN_VALUE,-1287,-250,-249,0,64,Integer.MAX_VALUE}) {
            assertEquals(0,good.radiusAtY(y));assertFalse(good.guaranteedAir(0,y,0));
        }
        var altered=new DeepCavityAirBounds(new TerrainParameters(p.seed()+1,p.surface(),p.cavity(),p.noise()));
        assertEquals(0,altered.radiusAtY(-900));assertFalse(altered.guaranteedAir(0,-900,0));
        for(int x:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE,-500,500})assertFalse(good.guaranteedAir(x,-900,x));
    }
}
