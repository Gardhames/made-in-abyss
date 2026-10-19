package dev.madeinabyss.kernel.terrain.living;
import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class LivingAbyssSpireFlutingTest {
    static LivingAbyssSpirePlan plan(){long seed=20261003;var p=LivingAbyssGeometryTest.parameters(seed);var r=LivingAbyssWallTest.recipe(seed);var c=p.cavity();
        var bare=new CompiledTerrainSampler(new TerrainParameters(seed,p.surface(),new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),c.declaredLowerBoundY(),c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),p.noise()));
        var fields=new LivingAbyssFields(seed,20,r.fields().zoneBoundaries(),25,22.5,45);var ledges=new LivingAbyssPlan(p,r,fields,(x,y,z)->true,bare::cavityRadius,bare::cavityRadius);
        return new LivingAbyssSpirePlan(p,ledges,new LivingAbyssGeometry(p,ledges),(x,y,z)->true);
    }
    @Test void widePillarKeepsAllVoxelsInsideOnePointFiveMetreFlutingEnvelope(){
        var plan=plan();var q=new LivingAbyssSpirePlan.Spire("unit-wide",0,1,-500,-33,-600,-400,22.5,22.5,270,30,12,"SADDLE",List.of());
        for(int y=-600;y<=-400;y+=11)for(int x=-22;x<=22;x++)for(int z=-22;z<=22;z++)if(Math.hypot(x,z)<=21)
            assertTrue(plan.solid(q,q.x()+x,y,q.z()+z),"local ribs remove at most1.5m even on the widest pillar: "+x+","+y+","+z);
    }
    @Test void actualPillarPerimetersMeetLocalPeriodAndSeamBounds(){var plan=plan();assertTrue(plan.spires().size()>10);
        for(var q:plan.spires()){double period=plan.flutePeriod(q),depth=plan.fluteMaximumDepth(q);assertTrue(period>=3&&period<=6);assertTrue(period*q.topRadius()/q.radius()>=3);assertTrue(depth>=.6&&depth<=1.5);
            for(int y=q.baseY();y<=q.topY();y+=13){assertEquals(plan.fluting(q,0,y),plan.fluting(q,360,y),1e-12);assertEquals(plan.fluting(q,-.001,y),plan.fluting(q,359.999,y),1e-12);}}
    }
}
