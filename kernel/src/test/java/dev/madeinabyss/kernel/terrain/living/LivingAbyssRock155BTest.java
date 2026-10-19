package dev.madeinabyss.kernel.terrain.living;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Fails if B still samples A's azimuth-only tilt / per-voxel camouflage. */
class LivingAbyssRock155BTest {
    static LivingAbyssRevision155 shape(long seed){
        var r=LivingAbyssGeometryTest.recipe(seed);
        var b=new LivingAbyssRecipe(r.schema(),"15.5-stage-b-1","RECONSTRUCTION",seed,r.domain(),r.fields(),r.archetypes(),r.newLedges(),r.cascade());
        return new LivingAbyssRevision155(b,new LivingAbyssFields(seed,7.3,r.fields().zoneBoundaries(),25,22.5,45));
    }
    @Test void signedWholeRockSamplesMatchIndependentPlanarAnisotropicPythonOracle()throws Exception{
        var shapes=new HashMap<Long,LivingAbyssRevision155>();int checked=0,different=0;String first="";
        try(var in=new BufferedReader(new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/diagnostics/rev155b/rock.tsv")),StandardCharsets.UTF_8))){
            in.readLine();String line;
            while((line=in.readLine())!=null){var a=line.split("\t");long seed=Long.parseLong(a[0]);int x=Integer.parseInt(a[1]),y=Integer.parseInt(a[2]),z=Integer.parseInt(a[3]);
                var s=shapes.computeIfAbsent(seed,LivingAbyssRock155BTest::shape);String actual=s.material(x,y,z);
                if(!a[5].equals(actual)){different++;if(first.isEmpty())first=line+" actual="+actual;}checked++;
            }
        }
        assertEquals(224,checked);assertEquals(0,different,"B plane+patch failures="+different+" first="+first);
    }
    @Test void caveOpeningsUseNoisyBeddingAlignedSuperellipsesInsteadOfCircularPortholes()throws Exception{
        var s=shape(20261003);int checked=0,different=0;String first="";
        try(var in=new BufferedReader(new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/diagnostics/rev155b/mouths.tsv")),StandardCharsets.UTF_8))){
            in.readLine();String line;
            while((line=in.readLine())!=null){var a=line.split("\t");double center=Double.parseDouble(a[1]),theta=Double.parseDouble(a[2]),d=Double.parseDouble(a[3]);boolean port=Boolean.parseBoolean(a[4]);
                var mouth=new LivingAbyssRevision155.Opening(center,40,640,48,.35,2,.15,0,port,false,99);
                boolean actual=s.openingCut(mouth,theta,d),expected=Boolean.parseBoolean(a[5]);
                if(actual!=expected){different++;if(first.isEmpty())first=line+" actual="+actual;}checked++;
            }
        }
        assertEquals(3078,checked);assertEquals(0,different,"B mouth shape failures="+different+" first="+first);
    }
    @Test void rockFinishCannotConsumeDifferentLayoutRngAndMoveTheAcceptedHalls(){
        var b=shape(20261003);var a=LivingAbyssRevision155Test.build(20261003).revision155();
        assertEquals(a.ledges(),b.ledges(),"B finishes the accepted macro layout");
        assertEquals(a.halls(),b.halls(),"B aperture sampling must not reroll later hall dimensions/positions");
    }
    @Test void openingJambsVaryThroughTheFalseWallInsteadOfExtrudingAFlatSection(){
        var s=shape(20261003);int checked=0,changed=0,openings=0;
        outer:for(var h:s.halls())for(var a:h.arches()){
            if(a.w()<30||a.ah()<30)continue;
            var one=new LivingAbyssRevision155.Hall(h.id(),h.zone(),h.theta(),h.top(),h.h(),h.G(),h.A(),h.T(),h.seed(),
                List.of(a),null,List.of(),List.of(),List.of(),List.of(),true,h.openingFraction());
            for(double d=a.floor()-.8*a.ah();d<a.floor()-.2*a.ah();d+=3)for(double arc=-.65*a.w();arc<=.65*a.w();arc+=1.5){
                double theta=a.theta()+Math.toDegrees(arc/450),ri=LivingAbyssMath155.inner(d);
                double t=Math.max(8,h.T()*(.4+1.2*LivingAbyssMath155.noise(20261003,theta,d,30,55,h.seed()+1,450)));
                boolean front=LivingAbyssRevision155.isSolid(s.hallShape(one,theta,ri+.32*t,d,LivingAbyssRevision155.ROCK));
                boolean back=LivingAbyssRevision155.isSolid(s.hallShape(one,theta,ri+.68*t,d,LivingAbyssRevision155.ROCK));
                if(front!=back)changed++;checked++;
            }
            if(++openings==12)break outer;
        }
        assertTrue(checked>1000,"nonvacuous jamb samples "+checked);assertTrue(changed>15,"flat extruded jambs: "+changed+"/"+checked);
    }
    @Test void collapseEdgesAlsoCarryDepthVaryingNormalRelief(){
        var s=shape(20261003);int changed=0,checked=0;
        for(var h:s.halls().subList(1,9)){
            var collapse=new LivingAbyssRevision155.Collapse(h.theta(),h.A()*.35,h.top()+h.h()*.25,h.top()+h.h()*.85);
            var one=new LivingAbyssRevision155.Hall(h.id(),h.zone(),h.theta(),h.top(),h.h(),h.G(),h.A(),h.T(),h.seed(),
                List.of(),collapse,List.of(),List.of(),List.of(),List.of(),false,h.openingFraction());
            for(double d=collapse.d0()-5;d<=collapse.d1()+5;d+=3)for(double arc=-collapse.w()*1.25;arc<=collapse.w()*1.25;arc+=2){
                double theta=h.theta()+Math.toDegrees(arc/450),ri=LivingAbyssMath155.inner(d);
                double t=Math.max(8,h.T()*(.4+1.2*LivingAbyssMath155.noise(20261003,theta,d,30,55,h.seed()+1,450)));
                boolean front=LivingAbyssRevision155.isSolid(s.hallShape(one,theta,ri+.32*t,d,LivingAbyssRevision155.ROCK));
                boolean back=LivingAbyssRevision155.isSolid(s.hallShape(one,theta,ri+.68*t,d,LivingAbyssRevision155.ROCK));
                if(front!=back)changed++;checked++;
            }
        }
        assertTrue(checked>1000);assertTrue(changed>10,"flat collapse boundaries: "+changed+"/"+checked);
    }
    @Test void hardBandReliefUsesTheSameXyzBeddingAsItsVisibleRock()throws Exception{
        var s=shape(20261003);int checked=0;
        try(var in=new BufferedReader(new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/diagnostics/rev155b/relief.tsv")),StandardCharsets.UTF_8))){
            in.readLine();String line;
            while((line=in.readLine())!=null){var a=line.split("\t");
                assertEquals(Double.parseDouble(a[4]),s.relief(Double.parseDouble(a[0]),Double.parseDouble(a[1]),Double.parseDouble(a[2]),Long.parseLong(a[3])),2e-9,line);checked++;
            }
        }
        assertEquals(495,checked);
    }
}
