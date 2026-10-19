package dev.madeinabyss.kernel.terrain.living;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Breaks on the old expanding wall/R380 shortcut, before any new implementation. */
class LivingAbyssRevision155Test {
    private static double metricRelief(LivingAbyssRevision155 s,double face,double global,double d,double t,long salt,double radius){
        double drift=(LivingAbyssMath155.noise(20261003,0,d,60,60,salt+31,radius)-.5)*16,theta=face+Math.toDegrees(drift/radius);
        double slab=Math.floor(LivingAbyssMath155.noise(20261003,theta,d,34,340,salt+32,radius)*5)/5*9;
        double bulge=LivingAbyssMath155.noise(20261003,theta,d,90,180,salt+33,radius)*10;
        double pipe=(1-Math.abs(2*LivingAbyssMath155.noise(20261003,theta,d,18,144,salt+34,radius)-1))*3.5;
        double flute=(1-Math.abs(2*LivingAbyssMath155.noise(20261003,theta,d,6,120,salt+35,radius)-1))*1.2;
        return Math.max(-.25*t,Math.min(.25*t,slab+bulge+pipe+flute-10+s.strata().hardness(global,d)));
    }
    private static final java.util.concurrent.ConcurrentHashMap<Long,LivingAbyssPlan> FIXTURES=new java.util.concurrent.ConcurrentHashMap<>();
    static LivingAbyssPlan build(long seed) {
        return FIXTURES.computeIfAbsent(seed,LivingAbyssRevision155Test::fresh);
    }
    private static LivingAbyssPlan fresh(long seed) {
        var old=LivingAbyssGeometryTest.recipe(seed);
        var recipe=new LivingAbyssRecipe(old.schema(),"15.5-stage-a","RECONSTRUCTION",seed,
            old.domain(),old.fields(),old.archetypes(),old.newLedges(),old.cascade());
        var fields=new LivingAbyssFields(seed,7.3,recipe.fields().zoneBoundaries(),25,22.5,45);
        return new LivingAbyssPlan(LivingAbyssGeometryTest.parameters(seed),recipe,fields,
            (x,y,z)->Math.hypot(x,z)>=490,(x,y,z)->490,(x,y,z)->490);
    }
    @Test void funnelReplacesExpandingParentAtBothSignedAxes() {
        var g=new LivingAbyssGeometry(LivingAbyssGeometryTest.parameters(1552),build(1552));
        assertEquals(490,g.wallRadius(0,0,-500),1e-12,"rim");
        assertEquals(320,g.wallRadius(0,-1350,-500),1e-12,"bottom");
        assertEquals(320,g.wallRadius(-500,-1350,0),1e-12,"negative quadrant");
    }
    @Test void fullDepthRockInsideOldR380IsNoLongerErased() {
        var g=new LivingAbyssGeometry(LivingAbyssGeometryTest.parameters(1552),build(1552));
        assertEquals(LivingAbyssGeometry.Kind.ROCK,g.sample(370,-1349,0));
        assertEquals(LivingAbyssGeometry.Kind.ROCK,g.sample(-370,-1349,0));
        for(int y:new int[]{-120,-600,-950,-1350})
            assertEquals(LivingAbyssGeometry.Kind.AIR,g.sample(-219,y,0));
        assertThrows(IllegalArgumentException.class,()->g.sample(-1,-1425,-1));
    }
    @Test void newCatalogueUsesSpecifiedShaftCountsAndLandmarks() {
        var p=build(1552);
        assertEquals(121,p.ledges().size(),"116 shaft ledges plus five landmarks");
        assertEquals(5,p.ledges().stream().filter(q->q.id().startsWith("landmark-")).count());
        assertEquals(60,p.ledges().stream().filter(q->q.id().startsWith("shaft-1-")).count());
        assertEquals(30,p.ledges().stream().filter(q->q.id().startsWith("shaft-2-")).count());
        assertEquals(12,p.ledges().stream().filter(q->q.id().startsWith("shaft-3-")).count());
        assertEquals(14,p.ledges().stream().filter(q->q.id().startsWith("shaft-4-")).count());
    }
    @Test void everyHallDescriptorCanBeExportedAsFiniteJson() {
        for(var h:build(20261003).revision155().halls())assertTrue(Double.isFinite(h.openingFraction()),h.id());
    }
    @Test void actualHallCountsArePackedWithoutOverwritingGateOrNeighbours() {
        var s=build(20261003).revision155();assertEquals(40,s.halls().size());
        for(int i=0;i<s.halls().size();i++)for(int j=i+1;j<s.halls().size();j++){
            var a=s.halls().get(i);var b=s.halls().get(j);
            boolean vertical=a.top()+a.h()+10<=b.top()+1e-8||b.top()+b.h()+10<=a.top()+1e-8;
            boolean angular=Math.abs(LivingAbyssRevision155.signedArc(a.theta(),b.theta(),450))+1e-8>=a.A()+b.A()+10;
            assertTrue(vertical||angular,a.id()+" / "+b.id());
        }
    }
    @Test void localReliefNeverConsumesTheStructuralCore() {
        var s=build(20261003).revision155();
        for(double t:new double[]{.5,2,8,18,55,100})for(int d=0;d<=1350;d+=13)for(int a=-360;a<=720;a+=47)
            assertTrue(Math.abs(s.relief(a,d,t,19))<=.25*t+1e-12);
    }
    @Test void ruinArchetypeCarriesTheAuthoredRisingSurfaceIntoTheWall() {
        var s=build(20261003).revision155();var l=s.ledges().stream().filter(q->q.id().equals("landmark-abode")).findFirst().orElseThrow();
        assertEquals(LivingAbyssRevision155.LEDGE,s.shape(l.theta(),LivingAbyssMath155.inner(l.top())-.1*l.P(),l.top()-10));
    }
    @Test void archCutsTheEntireFrontReliefInsteadOfLeavingAHiddenRockCurtain() {
        var s=build(20261003).revision155();int checked=0;
        for(var h:s.halls())for(var a:h.arches()){
            double theta=a.theta(),d=a.floor()-a.ah()*.5;
            if(!s.openingCut(a,theta,d)||LivingAbyssRevision155.envelope(LivingAbyssRevision155.signedArc(theta,h.theta(),450)/h.A(),(d-h.top()-h.h()/2)/(h.h()/2))<=.12)continue;
            double ri=LivingAbyssMath155.inner(d),T=Math.max(8,h.T()*(.4+1.2*LivingAbyssMath155.noise(20261003,theta,d,30,55,h.seed()+1,450)));
            double base=ri-s.relief(theta,d,80,0x155faceL),front=ri-s.relief(theta,d,T,h.seed()+103);
            if(base+.01>=front)continue;checked++;
            var frontOnly=new LivingAbyssRevision155.Hall(h.id(),h.zone(),h.theta(),h.top(),h.h(),h.G(),h.A(),h.T(),h.seed(),h.arches(),h.collapse(),
                java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),h.gate(),h.openingFraction());
            assertFalse(LivingAbyssRevision155.isSolid(s.hallShape(frontOnly,theta,(base+front)/2,d,LivingAbyssRevision155.ROCK)),h.id());
        }
        assertTrue(checked>20,"nonvacuous independently located front-relief curtains");
    }
    @Test void secondaryLithologyOccupiesAQuarterOfUnreplacedBands() {
        for(long seed:new long[]{20261003,-20261003}){
            var strata=new LivingAbyssStrata155(seed,7.3);int count=0,secondary=0;
            for(int theta=0;theta<360;theta+=5)for(int d=0;d<=1350;d++){
                double dd=d+LivingAbyssStrata155.tilt(theta)+(LivingAbyssMath155.noise(seed,theta,d,9,3,0x15501L,520)-.5)*4;
                var band=strata.bands().get(strata.bandIndex(dd));if(band.replace())continue;
                double a=Math.toRadians(theta);int x=(int)Math.round(520*Math.sin(a)),z=(int)Math.round(-520*Math.cos(a));
                count++;if(strata.material(theta,x,-d,z).equals(band.secondary()))secondary++;
            }
            double share=secondary/(double)count;assertTrue(share>=.22&&share<=.28,"75/25 lithology seed="+seed+" secondary="+share);
        }
    }
    @Test void hallSpeleothemsReachTheirSupportedPlacementQuotaIncludingTheGate() {
        var s=build(20261003).revision155();
        for(var h:s.halls()){
            long elements=s.speleothems().stream().filter(q->q.owner().equals(h.id())).count();
            long fused=s.speleothems().stream().filter(q->q.owner().equals(h.id())&&q.fused()).count();
            assertEquals((int)(2*h.A()*h.G()/2500)+2,elements-fused/2,h.id());
        }
    }
    @Test void pointedTipsKeepAnActualDripstoneBodyBeforeTheirEndpoint() {
        var s=build(20261003).revision155();
        for(var q:s.speleothems())if(!q.fused())assertTrue(q.L()>=q.tips()+1,q.owner()+" L="+q.L()+" tips="+q.tips());
    }
    @Test void throughBodiesAndColumnsPreserveTheRequiredFacadeOpeningFraction() {
        var s=build(20261003).revision155();double[] low={0,.30,.50,.45,.30},high={0,.45,.65,.60,.45};
        for(var h:s.halls())if(!h.gate()){
            int nx=(int)Math.ceil(2*h.A()/4),ny=(int)Math.ceil(h.h()/4),open=0,total=0;
            for(int y=0;y<ny;y++)for(int x=0;x<nx;x++){
                double d=h.top()+(y+.5)*h.h()/ny,theta=h.theta()+Math.toDegrees((-h.A()+(x+.5)*2*h.A()/nx)/450);
                double env=LivingAbyssRevision155.envelope(LivingAbyssRevision155.signedArc(theta,h.theta(),450)/h.A(),(d-h.top()-h.h()/2)/(h.h()/2));
                if(env<=.12)continue;total++;
                double T=Math.max(8,h.T()*(.4+1.2*LivingAbyssMath155.noise(20261003,theta,d,30,55,h.seed()+1,450)));
                int code=s.hallShape(h,theta,LivingAbyssMath155.inner(d)+T*.5,d,LivingAbyssRevision155.ROCK);
                if(!LivingAbyssRevision155.isSolid(code))open++;
            }
            double fraction=open/(double)total;assertTrue(fraction>=low[h.zone()]-.005&&fraction<=high[h.zone()]+.005,h.id()+" final openness="+fraction);
        }
    }
    @Test void threeBlockPointedEndsStartWithBaseAndKeepFrustumBeforeTip() {
        var s=build(20261003).revision155();int checked=0;
        for(var q:s.speleothems())if(q.tips()>0)for(int t=(int)Math.floor(q.L())-q.tips()+1;t<=(int)Math.floor(q.L());t++){
            int y=-q.depth()-q.direction()*t,code=s.sample(q.x(),y,q.z());if(code<7)continue;checked++;
            int direction=code<11?1:-1,next=s.sample(q.x(),y-direction,q.z()),behind=s.sample(q.x(),y+direction,q.z());
            boolean sameNext=next>=7&&(next<11)==(direction==1),sameBehind=behind>=7&&(behind<11)==(direction==1);
            // Actual1.20.1 calculateDripstoneThickness: next TIP -> FRUSTUM,
            // otherwise absent pointed backing -> BASE, pointed backing -> MIDDLE.
            int expected=!sameNext?3:((next-7)&3)==3?2:!sameBehind?0:1;
            assertEquals((direction==1?7:11)+expected,code,q.owner()+" y="+y);
        }
        assertTrue(checked>30);
    }
    @Test void fusedPairsEachSpanTheAuthoredHalfHallHeight() {
        var s=build(20261003).revision155();var heights=new java.util.HashMap<String,Double>();for(var h:s.halls())heights.put(h.id(),h.h());
        for(var q:s.speleothems())if(q.fused())assertEquals(.55*heights.get(q.owner()),q.L(),1e-12,q.owner());
    }
    @Test void bridgeReliefMovesAlongItsCornerNormalWithinOneAmplitude() {
        var s=build(20261003).revision155();int checked=0;
        for(var h:s.halls())for(double fraction:new double[]{.35,.5,.65}){
            double theta=h.theta(),d=h.top()+fraction*h.h(),shift=metricRelief(s,theta+90,theta,d,10,h.seed()+104,450);
            if(shift<.5)continue;
            double in=LivingAbyssMath155.inner(d)+Math.max(8,h.T()*(.4+1.2*LivingAbyssMath155.noise(20261003,theta,d,30,55,h.seed()+1,450)));
            double env=LivingAbyssRevision155.envelope(0,(d-h.top()-h.h()/2)/(h.h()/2));
            double gap=h.G()*(.7+.6*LivingAbyssMath155.noise(20261003,theta,d,70,45,h.seed(),450))*env,r=in+gap*.5;
            // Use a small negative radial-field offset only to recover the exact sag
            // coordinate: this point remains deep inside the lobe under any face relief.
            double inShift=metricRelief(s,theta+180,theta,d,in-LivingAbyssMath155.inner(d),h.seed()+102,450);
            double v=(d-h.top()-h.h()/2)/(h.h()/2),ss=0;
            double dv=-Math.copySign(v*v,v)*Math.pow(Math.max(1e-9,1-Math.pow(Math.abs(v),3)),-2.0/3)/(h.h()/2);
            double G=h.G()*(.7+.6*LivingAbyssMath155.noise(20261003,theta,d,70,45,h.seed(),450));
            double norm=Math.min(16,Math.sqrt(1+G*G*dv*dv));
            double local=Math.min(gap/norm,Math.min(d-h.top(),h.top()+h.h()-d)*2);
            double out=in+gap-s.relief(theta,d,Math.max(0,local),h.seed()+101)*norm;
            double shiftedIn=in+inShift,sag=8*Math.sin(Math.PI*LivingAbyssMath155.clamp((r-shiftedIn)/Math.max(1,out-shiftedIn)));
            var b=new LivingAbyssRevision155.Bridge(theta-Math.toDegrees((7+.9*shift)/450),d-sag-5-.9*shift,14,10);
            var isolated=new LivingAbyssRevision155.Hall(h.id(),h.zone(),h.theta(),h.top(),h.h(),h.G(),h.A(),h.T(),h.seed(),java.util.List.of(),null,
                java.util.List.of(),java.util.List.of(b),java.util.List.of(),java.util.List.of(),h.gate(),0);
            checked++;assertEquals(LivingAbyssRevision155.HALL,s.hallShape(isolated,theta,r,d,LivingAbyssRevision155.ROCK),h.id());break;
        }
        assertTrue(checked>5);
    }
    @Test void airCertificateRefusesOverflowingAndUnknownBlockOrigins() {
        var s=build(20261003).revision155();
        for(int y:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE,-1425,-1408,0,16})assertFalse(s.uniformCentreAir(0,y,0),"unknownY="+y);
    }
}
