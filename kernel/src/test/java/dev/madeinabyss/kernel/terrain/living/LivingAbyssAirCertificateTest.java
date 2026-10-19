package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.natural.NaturalProtection.Box;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Catches a missed shape/support layer, signed bit alias, halo carry or mutable publication. */
class LivingAbyssAirCertificateTest {
    private record Fixture(TerrainParameters p, LivingAbyssGeometry geometry) {}
    private static Fixture fixture;
    private static synchronized Fixture fixture() {
        if(fixture!=null)return fixture;
        var p=LivingAbyssGeometryTest.parameters(20261003);var r=LivingAbyssWallTest.recipe(p.seed());var c=p.cavity();
        var bare=new CompiledTerrainSampler(new TerrainParameters(p.seed(),p.surface(),new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),c.declaredLowerBoundY(),c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),p.noise()));
        var fields=new LivingAbyssFields(r.seed(),20,r.fields().zoneBoundaries(),25,22.5,45);
        var plan=new LivingAbyssPlan(p,r,fields,(x,y,z)->true,bare::cavityRadius,bare::cavityRadius);
        var base=new LivingAbyssGeometry(p,plan);var spires=new LivingAbyssSpirePlan(p,plan,base,(x,y,z)->true);
        return fixture=new Fixture(p,new LivingAbyssGeometry(p,plan,spires));
    }
    // Reflection lets the initial RED run fail an assertion for the absent implementation.
    private static Class<?> type(){return assertDoesNotThrow(()->Class.forName("dev.madeinabyss.kernel.terrain.living.LivingAbyssAirCertificate"),"Exact AIR certificate is not implemented");}
    private static Object build(Iterable<CubeKey> dirty)throws Exception {
        var t=type();var f=fixture();return t.getConstructor(TerrainParameters.class,LivingAbyssGeometry.class,Iterable.class).newInstance(f.p,f.geometry,dirty);
    }
    private static boolean air(Object c,int x,int y,int z)throws Exception{return (boolean)c.getClass().getMethod("uniformAir",int.class,int.class,int.class).invoke(c,x,y,z);}
    private static long count(Object c)throws Exception{return ((Number)c.getClass().getMethod("certifiedCubeCount").invoke(c)).longValue();}

    @Test void everyVoxelAndItsSupportingLayerOfSignedShellFixturesAreGeometryAir()throws Exception {
        var c=build(List.of());var keys=new ArrayList<CubeKey>();
        c.getClass().getMethod("visitCertifiedCubes",Consumer.class).invoke(c,(Consumer<CubeKey>)k->{
            int x=k.x()*16,z=k.z()*16;double nx=x>0?x:x+15<0?x+15:0,nz=z>0?z:z+15<0?z+15:0;
            if(nx*nx+nz*nz>=380.0*380&&keys.size()<64&&k.x()<0&&k.z()<0)keys.add(k);
        });
        assertEquals(64,keys.size(),"must exercise the new open shell, not only the old core shortcut");
        for(var k:keys)for(int y=-1;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
            assertEquals(LivingAbyssGeometry.Kind.AIR,fixture().geometry.sample(k.x()*16+x,k.y()*16+y,k.z()*16+z),"no shelf, spire or sturdy moss support in "+k);
    }
    @Test void oneDirtyCubeVetoesAll26NeighborsAcrossSignedYWordBoundary()throws Exception {
        var clean=build(List.of());var dirty=new CubeKey(-2,-22,-2);var c=build(List.of(dirty));
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
            assertTrue(air(clean,dirty.x()+dx,dirty.y()+dy,dirty.z()+dz),"literal empty core fixture must be non-vacuous");
            assertFalse(air(c,dirty.x()+dx,dirty.y()+dy,dirty.z()+dz),"dirty face/edge/corner halo, including word63/64");
        }
        assertTrue(air(c,-2,-20,-2),"a two-cube separation is outside the halo");
    }
    @Test void floorSurfaceExteriorAndExtremeCubeCoordinatesNeverAliasAnAirBit()throws Exception {
        var c=build(List.of());assertTrue(air(c,0,-1,0),"upper core has no legacy -120 cutoff");
        for(var k:List.of(new CubeKey(0,-85,0),new CubeKey(0,-89,0),new CubeKey(0,0,0),new CubeKey(0,20,0),
                new CubeKey(60,-1,0),new CubeKey(-61,-1,0),new CubeKey(Integer.MAX_VALUE,-1,0),new CubeKey(0,Integer.MIN_VALUE,0),new CubeKey(0,-1,Integer.MAX_VALUE)))
            assertFalse(air(c,k.x(),k.y(),k.z()),"outside certified whole-cube domain: "+k);
    }
    @Test void inputMutationAndConcurrentReadersCannotChangePublishedBits()throws Exception {
        var input=new ArrayList<CubeKey>();var c=build(input);long before=count(c);input.add(new CubeKey(0,-1,0));
        assertTrue(air(c,0,-1,0));assertEquals(before,count(c));assertTrue(before>10000);
        var pool=Executors.newFixedThreadPool(4);
        try {var work=new ArrayList<Callable<Boolean>>();for(int i=0;i<4;i++)work.add(()->{for(int n=0;n<10000;n++)if(!air(c,-2,-22,-2)||air(c,0,0,0))return false;return true;});
            for(var result:pool.invokeAll(work))assertTrue(result.get());
        }finally{pool.shutdownNow();}
    }
    @Test void bareWallLowerBoundStaysBelowTheIndependentHistoricalSampler()throws Exception {
        var method=type().getMethod("bareWallLowerBound",TerrainParameters.class,int.class);var random=new Random(150215);
        for(long seed:new long[]{0,20261003,-20261003,Long.MIN_VALUE,Long.MAX_VALUE}){
            var p=LivingAbyssGeometryTest.parameters(seed);var c=p.cavity();var oracle=new CompiledTerrainSampler(new TerrainParameters(seed,p.surface(),new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),c.declaredLowerBoundY(),c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),p.noise()));
            for(int n=0;n<30000;n++){int x=random.nextInt(1921)-960,z=random.nextInt(1921)-960,y=-1351+random.nextInt(1416);
                double bound=(double)method.invoke(null,p,y);assertTrue(bound<=oracle.cavityRadius(x,y,z),"outward FP wall bound at signed noise/lattice/depth seam");}
        }
    }
    @Test void actualShelfSpireAndWallVoxelsCannotBeCertified()throws Exception {
        var c=build(List.of());var random=new Random(381516);int solids=0;
        for(int n=0;n<25000;n++){
            int x=random.nextInt(1441)-720,z=random.nextInt(1441)-720,y=-1344+random.nextInt(1344);
            if(fixture().geometry.sample(x,y,z)!=LivingAbyssGeometry.Kind.AIR){solids++;assertFalse(air(c,x>>4,y>>4,z>>4),"non-AIR geometry must veto its whole cube");}
        }assertTrue(solids>1000);
    }
    @Test void historicalWalllessRecipeHasNoCertificate()throws Exception {
        var t=type();var p=LivingAbyssGeometryTest.parameters(20261003);var g=new LivingAbyssGeometry(p,LivingAbyssGeometryTest.build(20261003));
        var c=t.getConstructor(TerrainParameters.class,LivingAbyssGeometry.class,Iterable.class).newInstance(p,g,List.of());assertEquals(0,count(c));assertFalse(air(c,0,-1,0));
    }
    @Test void approvedUpperOpeningStopsExactlyBeforeY64AndR500()throws Exception {
        var t=type();var f=fixture();
        var constructor=assertDoesNotThrow(()->t.getConstructor(TerrainParameters.class,LivingAbyssGeometry.class,Iterable.class,boolean.class),"explicit approved opening proof required");
        var c=constructor.newInstance(f.p,f.geometry,List.of(),true);
        assertTrue(air(c,-1,0,-1));assertTrue(air(c,-1,3,-1));
        assertFalse(air(c,-1,4,-1),"Y64 belongs to the unproved parent surface");
        assertFalse(air(c,31,0,0),"a cube crossing R500 cannot use opening ownership");
        assertFalse(air(build(List.of()),-1,0,-1),"bare geometry alone cannot certify the parent composition");
    }
    @Test void featureAndReservationCubeViewsKeepNegativeSeamsAndAreImmutable()throws Exception {
        var b=new LivingAbyssVoxelPlan.Builder();b.put(-17,-33,-16,1);b.put(-16,-32,-17,2);var voxels=b.freeze();
        @SuppressWarnings("unchecked") var keys=(Set<CubeKey>)assertDoesNotThrow(()->voxels.getClass().getMethod("cubeKeys").invoke(voxels));
        assertEquals(Set.of(new CubeKey(-2,-3,-1),new CubeKey(-1,-2,-2)),keys);assertThrows(UnsupportedOperationException.class,keys::clear);
        var reservations=new LivingAbyssReservations(List.of(new Box(-17,-33,-17,-16,-32,-16)));
        @SuppressWarnings("unchecked") var reserved=(Set<CubeKey>)assertDoesNotThrow(()->reservations.getClass().getMethod("cubeKeys").invoke(reservations));
        assertEquals(8,reserved.size());assertTrue(reserved.contains(new CubeKey(-2,-3,-2)));assertThrows(UnsupportedOperationException.class,reserved::clear);
    }
}
