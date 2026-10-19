package dev.madeinabyss.kernel.terrain.natural;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.madeinabyss.kernel.terrain.*;
import java.util.*;

class SupportedCaveTerrainSamplerTest {
    private static final TerrainSample ROCK=new TerrainSample.Known(TerrainMaterial.ROCK);
    private static final TerrainSample AIR=new TerrainSample.Known(TerrainMaterial.AIR);
    private static TerrainParameters parameters(){return new TerrainParameters(20261003,
        new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
        new TerrainParameters.Cavity(500,64,-1286,500,180,170,0),new TerrainParameters.Noise(140,0,0,0));}
    private static TerrainSample source(int x,int y,int z){return Math.hypot(x,z)>=500?ROCK:AIR;}
    private static SupportedCaveTerrainSampler sampler(){return new SupportedCaveTerrainSampler(parameters(),CaveNetworkParameters.proposal(827),SupportedCaveTerrainSamplerTest::source,(x,y,z)->false);}
    @Test void everyPublishedFootHasRockSupportTwoBlockHeadroomAndUnitSteps(){
        var s=sampler();assertTrue(s.routes().size()>100,"supported routes must exist");
        assertTrue(s.routes().stream().anyMatch(SupportedCaveTerrainSampler.Route::vertical),"some inter-band stair routes must exist");
        for(var r:s.routes()){
            SupportedCaveTerrainSampler.Foot previous=null;
            for(var f:r.feet()){
                assertEquals(ROCK,s.sample(f.x(),f.y()-1,f.z()));
                assertEquals(AIR,s.sample(f.x(),f.y(),f.z()));
                assertEquals(AIR,s.sample(f.x(),f.y()+1,f.z()));
                assertTrue(s.routeProtectedAt(f.x(),f.y(),f.z()));
                if(previous!=null){assertEquals(1,Math.abs(f.x()-previous.x())+Math.abs(f.z()-previous.z()));assertTrue(Math.abs(f.y()-previous.y())<=1);}
                previous=f;
            }
        }
    }
    @Test void supportedRoutesAreAvailableAsSeparateImmutableAlgorithm() throws Exception {
        assertDoesNotThrow(()->Class.forName("dev.madeinabyss.kernel.terrain.natural.SupportedCaveTerrainSampler"),
                "v11 must provide a separate supported route sampler without rewriting v10");
    }
    @Test void additionalSupportedPassagesAreReportedByCheapPrefilterAndFeature(){
        var s=sampler();int checked=0;
        for(var r:s.routes())if(r.vertical())for(var f:r.feet()){
            assertTrue(s.mayAffectShell(f.x(),f.y(),f.z()));
            assertTrue(s.featureAt(f.x(),f.y(),f.z()).cave());checked++;
        }
        assertTrue(checked>100);
    }
    @Test void protectsMasksKnownWaterUnknownAndExistingUnchangedV10Planner(){
        var s=sampler();var old=new CaveNetworkTerrainSampler(parameters(),CaveNetworkParameters.proposal(827),SupportedCaveTerrainSamplerTest::source,(x,y,z)->false);
        assertEquals(old.portals(),s.portals());assertTrue(s.segments().containsAll(old.segments()));
        var blocked=new SupportedCaveTerrainSampler(parameters(),CaveNetworkParameters.proposal(827),SupportedCaveTerrainSamplerTest::source,(x,y,z)->true);
        assertTrue(blocked.routes().isEmpty());assertEquals(ROCK,blocked.sample(520,-240,0));
        var unavailable=new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER);
        var unknown=new SupportedCaveTerrainSampler(parameters(),CaveNetworkParameters.proposal(827),(x,y,z)->unavailable,(x,y,z)->false);
        assertTrue(unknown.routes().isEmpty());assertSame(unavailable,unknown.sample(-520,-240,-1));
        var f=s.routes().get(0).feet().get(0);var water=new TerrainSample.Known(TerrainMaterial.WATER);
        var wet=new SupportedCaveTerrainSampler(parameters(),CaveNetworkParameters.proposal(827),(x,y,z)->x==f.x()+1&&y==f.y()&&z==f.z()?water:source(x,y,z),(x,y,z)->false);
        assertEquals(ROCK,wet.sample(f.x(),f.y(),f.z()));assertEquals(water,wet.sample(f.x()+1,f.y(),f.z()));
        for(int y:new int[]{Integer.MIN_VALUE,-1287,-1286,0,64,Integer.MAX_VALUE})assertEquals(source(-501,y,0),s.sample(-501,y,0));
    }
    @Test void deterministicNegativeCoordinateRoutesPreserveSweptJumpClearance(){
        var a=sampler();var b=sampler();assertEquals(a.routes(),b.routes());
        assertTrue(a.routes().stream().flatMap(r->r.feet().stream()).anyMatch(f->f.x()<0&&f.z()<0));
        for(var r:a.routes())for(int i=1;i<r.feet().size();i++){
            var p=r.feet().get(i-1);var q=r.feet().get(i);
            // A one-block jump needs clearance over the LOW position before moving sideways.
            var low=p.y()<q.y()?p:q;int head=Math.max(p.y(),q.y())+1;
            assertEquals(AIR,a.sample(low.x(),head,low.z()));
        }
        assertThrows(UnsupportedOperationException.class,()->a.routes().clear());
        assertThrows(UnsupportedOperationException.class,()->a.routes().get(0).feet().clear());
    }
    @Test void retainedFloorsAnchorThroughFinalRockToUncarvedGeology(){
        var s=sampler();var legacy=new CaveNetworkTerrainSampler(parameters(),CaveNetworkParameters.proposal(827),SupportedCaveTerrainSamplerTest::source,(x,y,z)->false);
        for(var r:s.routes())for(var f:r.feet()){
            boolean anchored=false;
            for(int depth=1;depth<=12;depth++){
                assertEquals(ROCK,s.sample(f.x(),f.y()-depth,f.z()),"a cave stair must retain continuous physical rock beneath its floor");
                if(depth>=2&&legacy.sample(f.x(),f.y()-depth,f.z()).equals(ROCK)){anchored=true;break;}
            }
            assertTrue(anchored,"retained support must meet unchanged geology within bounded depth");
        }
    }
    @Test void signedCoordinateKeysDoNotCollapseAlongCommonLinearHashDiagonal(){
        var hashes=new HashSet<Integer>();
        for(int x=0;x<32;x++)hashes.add(new SupportedCaveTerrainSampler.Foot(x,-31*x,-512).hashCode());
        assertTrue(hashes.size()>24,"first-layer signed-coordinate keys need mixed hash distribution");
    }
    @Test void exactNetworkClassificationApiExists() throws Exception {
        assertDoesNotThrow(()->SupportedCaveTerrainSampler.class.getMethod("caveNetworkAt",int.class,int.class,int.class));
    }
    @Test void everyGeneratedRouteMaskMatchesOriginalFootMapOracle() throws Exception {
        var s=sampler();var legacyField=SupportedCaveTerrainSampler.class.getDeclaredField("legacy");legacyField.setAccessible(true);
        var legacy=(CaveNetworkTerrainSampler)legacyField.get(s);
        var original=new HashMap<SupportedCaveTerrainSampler.Foot,Integer>();
        for(var route:s.routes())for(var foot:route.feet())for(int depth=1;depth<=12;depth++) {
            var q=new SupportedCaveTerrainSampler.Foot(foot.x(),foot.y()-depth,foot.z());original.put(q,0);
            if(depth>=2&&legacy.sample(q.x(),q.y(),q.z()).equals(ROCK))break;
        }
        for(var route:s.routes())for(var foot:route.feet())for(int dy=0;dy<3;dy++)original.put(new SupportedCaveTerrainSampler.Foot(foot.x(),foot.y()+dy,foot.z()),route.network()+1);
        var field=SupportedCaveTerrainSampler.class.getDeclaredField("routeVoxels");field.setAccessible(true);var index=(RouteVoxelIndex)field.get(s);
        assertTrue(original.size()>400000,"entire generated floor/clearance masks are checked");
        for(var e:original.entrySet()){var q=e.getKey();assertEquals((int)e.getValue(),index.get(q.x(),q.y(),q.z()));assertTrue(s.routeProtectedAt(q.x(),q.y(),q.z()));
            if(e.getValue()>0){assertEquals(e.getValue()-1,s.caveNetworkAt(q.x(),q.y(),q.z()));assertEquals(AIR,s.sample(q.x(),q.y(),q.z()));}else assertEquals(ROCK,s.sample(q.x(),q.y(),q.z()));}
    }
    @Test void fastNetworkClassificationExactlyMatchesDiagnosticIdentity(){
        var s=sampler();var random=new Random(701);
        for(int i=0;i<50000;i++)checkClassifier(s,random.nextInt(1601)-800,random.nextInt(1601)-1450,random.nextInt(1601)-800);
        for(var e:s.segments()){
            int x=(int)Math.round((e.a().x()+e.b().x())*.5),y=(int)Math.round((e.a().y()+e.b().y())*.5),z=(int)Math.round((e.a().z()+e.b().z())*.5);
            for(int offset:new int[]{-7,-3,-1,0,1,3,7})checkClassifier(s,x+offset,y,z-offset);
        }
        for(var r:s.routes())for(var f:r.feet())for(int dy=-2;dy<3;dy++)checkClassifier(s,f.x(),f.y()+dy,f.z());
        for(int x:new int[]{Integer.MIN_VALUE,-769,-768,-33,-32,-1,0,31,32,767,768,Integer.MAX_VALUE})
            for(int y:new int[]{Integer.MIN_VALUE,-1287,-1286,-1278,-65,-64,-1,0,64,Integer.MAX_VALUE})checkClassifier(s,x,y,-32);
        var blocked=new SupportedCaveTerrainSampler(parameters(),CaveNetworkParameters.proposal(827),SupportedCaveTerrainSamplerTest::source,(x,y,z)->true);
        for(int x=-600;x<=600;x+=17)for(int z=-600;z<=600;z+=19)checkClassifier(blocked,x,-240,z);
    }
    private static void checkClassifier(SupportedCaveTerrainSampler s,int x,int y,int z){
        var f=s.featureAt(x,y,z);assertEquals(f.cave()?f.network():-1,s.caveNetworkAt(x,y,z),"classification mismatch at "+x+","+y+","+z);
    }
    public static void main(String[] args) throws Exception {
        new SupportedCaveTerrainSamplerTest().supportedRoutesAreAvailableAsSeparateImmutableAlgorithm();
        new SupportedCaveTerrainSamplerTest().everyPublishedFootHasRockSupportTwoBlockHeadroomAndUnitSteps();
        new SupportedCaveTerrainSamplerTest().additionalSupportedPassagesAreReportedByCheapPrefilterAndFeature();
        new SupportedCaveTerrainSamplerTest().protectsMasksKnownWaterUnknownAndExistingUnchangedV10Planner();
        new SupportedCaveTerrainSamplerTest().deterministicNegativeCoordinateRoutesPreserveSweptJumpClearance();
        new SupportedCaveTerrainSamplerTest().retainedFloorsAnchorThroughFinalRockToUncarvedGeology();
        new SupportedCaveTerrainSamplerTest().signedCoordinateKeysDoNotCollapseAlongCommonLinearHashDiagonal();
        new SupportedCaveTerrainSamplerTest().exactNetworkClassificationApiExists();
        new SupportedCaveTerrainSamplerTest().fastNetworkClassificationExactlyMatchesDiagnosticIdentity();
        var s=sampler();System.out.println("9 SupportedCaveTerrainSampler tests passed; routes="+s.routes().size()+" vertical="+s.routes().stream().filter(SupportedCaveTerrainSampler.Route::vertical).count()+" portals="+s.portals().size());
    }
}
