package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent geometry/scalar oracles catch unsafe support seams and lost local-wall proofs. */
class LivingAbyssAirCertificateExactTest {
    private record Fixture(TerrainParameters p,LivingAbyssGeometry geometry) {}
    private static Fixture fixture;
    private static synchronized Fixture fixture(){
        if(fixture!=null)return fixture;
        var p=LivingAbyssGeometryTest.parameters(20261003);var r=LivingAbyssWallTest.recipe(p.seed());var c=p.cavity();
        var bare=new CompiledTerrainSampler(new TerrainParameters(p.seed(),p.surface(),new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),c.declaredLowerBoundY(),c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),p.noise()));
        var fields=new LivingAbyssFields(r.seed(),20,r.fields().zoneBoundaries(),25,22.5,45);
        var plan=new LivingAbyssPlan(p,r,fields,(x,y,z)->true,bare::cavityRadius,bare::cavityRadius);
        var base=new LivingAbyssGeometry(p,plan);var spires=new LivingAbyssSpirePlan(p,plan,base,(x,y,z)->true);
        return fixture=new Fixture(p,new LivingAbyssGeometry(p,plan,spires));
    }
    private static LivingAbyssAirCertificate exact(Iterable<CubeKey> footprint){
        var method=assertDoesNotThrow(()->LivingAbyssAirCertificate.class.getMethod("exact",TerrainParameters.class,LivingAbyssGeometry.class,Iterable.class,boolean.class),"explicit final-footprint proof is missing");
        return assertDoesNotThrow(()->(LivingAbyssAirCertificate)method.invoke(null,fixture().p,fixture().geometry,footprint,false));
    }
    @Test void frozenFootprintsDoNotVetoUnrelatedFacesEdgesOrSignedYWordNeighbors(){
        var dirty=new CubeKey(-2,-22,-2);var c=exact(List.of(dirty));
        assertFalse(c.uniformAir(-2,-22,-2));
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)
            if(dx!=0||dy!=0||dz!=0)assertTrue(c.uniformAir(-2+dx,-22+dy,-2+dz),"frozen output has no read-time growth into neighbors");
    }
    @Test void supportingFeaturesExpandOnlyThroughTheirActualTopVoxelAtNegativeSeams(){
        var b=new LivingAbyssVoxelPlan.Builder();b.put(-17,-34,-16,1);b.put(-16,-33,-17,2);
        var method=assertDoesNotThrow(()->LivingAbyssAirCertificate.class.getMethod("supportingFeatureCubes",LivingAbyssVoxelPlan.class));
        @SuppressWarnings("unchecked") var keys=assertDoesNotThrow(()->(Set<CubeKey>)method.invoke(null,b.freeze()));
        assertEquals(Set.of(new CubeKey(-2,-3,-1),new CubeKey(-1,-3,-2),new CubeKey(-1,-2,-2)),keys);
        assertThrows(UnsupportedOperationException.class,keys::clear);
    }
    @Test void actualWallRecoversWholeAirCubesRejectedByTheGlobalNoiseBound(){
        var f=fixture();var certificate=exact(List.of());var p=f.p;var c=p.cavity();
        var historical=new CompiledTerrainSampler(new TerrainParameters(p.seed(),p.surface(),new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),c.declaredLowerBoundY(),c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),p.noise()));
        int recovered=0;
        candidates:for(int cx=28;cx<=38;cx+=2)for(int cz=-16;cz<=16;cz+=4)for(int cy=-64;cy<=-12;cy+=4){
            int sx=cx*16,sy=cy*16,sz=cz*16;double global=Double.POSITIVE_INFINITY,rmax=0;
            for(int y=sy-1;y<sy+16;y++)global=Math.min(global,LivingAbyssAirCertificate.bareWallLowerBound(p,y)-23.700001);
            for(int z=sz;z<sz+16;z++)for(int x=sx;x<sx+16;x++)rmax=Math.max(rmax,Math.hypot(x,z));
            if(rmax<global)continue;
            boolean empty=true;
            scan:for(int z=sz;z<sz+16;z++)for(int x=sx;x<sx+16;x++)for(int y=sy-1;y<sy+16;y++)
                if(Math.hypot(x,z)>=historical.cavityRadius(x,y,z)-23.700001||f.geometry.sample(x,y,z)!=LivingAbyssGeometry.Kind.AIR){empty=false;break scan;}
            if(!empty)continue;
            assertTrue(certificate.uniformAir(cx,cy,cz),"independent full-voxel wall/geometry proof at "+cx+","+cy+","+cz);
            if(++recovered==8)break candidates;
        }
        assertEquals(8,recovered,"exercise local noise variation rather than old core AIR");
    }
    @Test void boundedColumnMinimumIsBitExactAgainstEveryHistoricalIntegerY()throws Exception {
        var method=assertDoesNotThrow(()->LivingAbyssWallRadius.class.getMethod("minimumRadius",int.class,int.class,int.class,int.class));
        var random=new Random(151005);
        for(long seed:new long[]{0,20261003,-20261003,Long.MIN_VALUE}){
            var p=LivingAbyssGeometryTest.parameters(seed);var c=p.cavity();
            var bare=new TerrainParameters(seed,p.surface(),new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),c.declaredLowerBoundY(),c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),p.noise());
            var oracle=new CompiledTerrainSampler(bare);var actual=new LivingAbyssWallRadius(bare);
            for(int n=0;n<240;n++){
                int x=n==0?Integer.MIN_VALUE:random.nextInt(1921)-960,z=n==1?Integer.MAX_VALUE:random.nextInt(1921)-960;
                int lo=n==2?Integer.MIN_VALUE:n==3?Integer.MAX_VALUE-16:-1500+random.nextInt(1901),hi=lo+16;
                double expected=Double.POSITIVE_INFINITY;for(long y=lo;y<=hi;y++)expected=Math.min(expected,oracle.cavityRadius(x,(int)y,z));
                assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits((double)method.invoke(actual,x,lo,hi,z)),"minimum includes every integer, support row and lattice/zero seams");
            }
        }
    }
    @Test void bakedSpireColumnExtentsMatchAllRetainedVoxelsIncludingEmptyCandidateColumns(){
        var min=assertDoesNotThrow(()->LivingAbyssSpireShape.class.getMethod("minimumY",int.class,int.class));
        var max=assertDoesNotThrow(()->LivingAbyssSpireShape.class.getMethod("maximumY",int.class,int.class));
        var shape=new LivingAbyssSpireShape("leaning",151005,5,40);var bounds=new HashMap<Long,int[]>();
        shape.voxels().visit((x,y,z,code)->{long key=((long)x<<32)^(z&0xffffffffL);var span=bounds.computeIfAbsent(key,k->new int[]{Integer.MAX_VALUE,Integer.MIN_VALUE});span[0]=Math.min(span[0],y);span[1]=Math.max(span[1],y);});
        assertFalse(bounds.isEmpty());int empty=0;
        for(int x=-shape.pad()-1;x<=shape.pad()+1;x++)for(int z=-shape.pad()-1;z<=shape.pad()+1;z++){
            var span=bounds.get(((long)x<<32)^(z&0xffffffffL));int xx=x,zz=z;
            assertEquals(span==null?Integer.MAX_VALUE:span[0],assertDoesNotThrow(()->(int)min.invoke(shape,xx,zz)));
            assertEquals(span==null?Integer.MIN_VALUE:span[1],assertDoesNotThrow(()->(int)max.invoke(shape,xx,zz)));if(span==null)empty++;
        }assertTrue(empty>100);
    }
}
