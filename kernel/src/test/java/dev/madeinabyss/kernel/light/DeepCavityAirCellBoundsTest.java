package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent unchanged-geology checks; full composed ecology/save truth is tested in Forge. */
class DeepCavityAirCellBoundsTest {
    @Test void everyYSectorWrapAndHaloMatchUnchangedOracle(){DeepCavityAirCellBoundsProbe.main(new String[0]);}
    @Test void finalCarveAddsRealAirBeyondOldGlobalOpeningCertificate() {
        var p=DeepCavityAirCellBoundsProbe.approved();var proof=new DeepCavityAirCellBounds(p);var original=new CompiledTerrainSampler(p);
        var wall=new CompiledTerrainSampler(new TerrainParameters(p.seed(),p.surface(),new TerrainParameters.Cavity(500,64,-1286,650,180,170,0),p.noise()));
        for(int y:new int[]{-399,-375,-350,-325,-300,-275,-251,-250,-100,-1,0,63}) {
            int r=proof.finalCarveRadiusAtY(y);assertTrue(r>0&&r<500);
            for(int a=0;a<64;a++) {
                int x=(int)Math.round((r-2)*Math.cos(a*2*Math.PI/64)),z=(int)Math.round((r-2)*Math.sin(a*2*Math.PI/64));
                assertTrue(proof.finalCarveAir(x,y,z));
                double retention=y>=-250?0:Math.pow((-250-y)/150.0,2)*(3-2*((-250-y)/150.0));
                double without=wall.cavityRadius(x,y,z),radius=without-retention*Math.max(0,without-original.cavityRadius(x,y,z));
                assertTrue((double)x*x+(double)z*z<radius*radius);
            }
        }
        assertTrue(proof.finalCarveAir(380,-300,0));assertTrue(proof.finalCarveAir(470,0,0));
        assertFalse(proof.finalCarveAir(0,-400,0));assertFalse(proof.finalCarveAir(0,64,0));
    }
    @Test void unknownTargetsSignedOverflowAndDistinctGeometryRefuseProof() {
        var p=DeepCavityAirCellBoundsProbe.approved();var proof=new DeepCavityAirCellBounds(p);
        for(int y:new int[]{Integer.MIN_VALUE,-1287,64,Integer.MAX_VALUE})assertFalse(proof.guaranteedAir(0,y,0));
        for(int x:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE,-641,640})for(int z:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE,-641,640})assertFalse(proof.guaranteedAir(x,-900,z));
        var other=new DeepCavityAirCellBounds(new TerrainParameters(p.seed()+1,p.surface(),p.cavity(),p.noise()));
        assertFalse(other.guaranteedAir(0,-900,0));assertEquals(0,other.residentArrayBytes());
    }
    @Test void maskIsBoundedSharedReadOnlyAndAzimuthCoverageIsUseful() {
        var p=DeepCavityAirCellBoundsProbe.approved();var a=new DeepCavityAirCellBounds(p);var b=new DeepCavityAirCellBounds(p);
        assertTrue(a.residentArrayBytes()<=55*1024);assertTrue(a.admittedCellCount()>90000);
        assertEquals(a.constructionNanos(),b.constructionNanos());
        int cells=0;for(int x=-640;x<640;x+=16)for(int z=-640;z<640;z+=16)if(a.deepCellAir(x,-430,z))cells++;
        assertTrue(cells*256>200000,"Useful certified area beyond the global R192 disk");
    }
}
