package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class LivingAbyssCubeEnvelopeTest {
    @Test void signedCubeBoundsNeverClassifySurfaceOrUnknownAsDeepRock() {
        var p=LivingAbyssGeometryTest.parameters(20261003);var c=p.cavity();
        var bare=new TerrainParameters(p.seed(),p.surface(),new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),c.declaredLowerBoundY(),c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),p.noise());
        var oracle=new ParametricTerrainSampler(bare);var d=LivingAbyssWallTest.recipe(20261003).domain();var random=new Random(1503);int deep=0,air=0;
        for(int i=0;i<1600;i++){
            int x=(random.nextInt(89)-44)*16,z=(random.nextInt(89)-44)*16,y=-(random.nextInt(95)+1)*16;
            var b=LivingAbyssCubeEnvelope.classify(p,d,oracle::cavityRadius,x,y,z);
            if(b.kind()==LivingAbyssCubeEnvelope.Kind.CENTRE_AIR){air++;assertTrue(y>=d.wallBottomY()&&y+15<=d.emptyTopY());}
            if(b.kind()==LivingAbyssCubeEnvelope.Kind.DEEP_ROCK){deep++;assertTrue(y>=d.wallBottomY()&&y+15<0);
                for(int n=0;n<64;n++){int xx=x+random.nextInt(16),yy=y+random.nextInt(16),zz=z+random.nextInt(16);double r=oracle.cavityRadius(xx,yy,zz);
                    assertTrue(r>=b.minimumWall()&&r<=b.maximumWall(),"interior radius must remain inside conservative corner bounds");assertTrue(Math.hypot(xx,zz)>r+8);}}
        }
        assertTrue(deep>0,"non-vacuous deep cubes");assertTrue(air>100,"non-vacuous centre cubes");
        assertEquals(LivingAbyssCubeEnvelope.Kind.MIXED,LivingAbyssCubeEnvelope.classify(p,d,oracle::cavityRadius,0,-1424,0).kind());
        assertEquals(LivingAbyssCubeEnvelope.Kind.MIXED,LivingAbyssCubeEnvelope.classify(p,d,oracle::cavityRadius,-16,-112,-16).kind());
    }
}
