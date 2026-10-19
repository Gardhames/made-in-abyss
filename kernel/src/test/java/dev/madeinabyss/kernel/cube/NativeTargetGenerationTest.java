package dev.madeinabyss.kernel.cube;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeTargetGenerationTest {
    @Test void appliedFeetOriginAndFollowingEyeCameraAtNegativeYBoundaryKeepOneIntent() {
        var targets=new NativeTargetGeneration();
        var applied=applied(targets,-.01,-16.1,500,1.62);
        var frame=targets.request(-.01,-14.48,500,64,64,false);
        assertEquals(applied.generation(),frame.generation(),"Camera eye must not replace the actual teleport origin/generation");
        var ring=NativeFirstViewPlan.near(4,-.01,-16.1,500,-1424,320,1,512000).orElseThrow();
        assertEquals(-48,ring.drawBounds().minY());assertEquals(48,ring.drawBounds().sizeY());
        assertEquals(-16,frame.anchorY());
    }
    private static NativeTargetGeneration.Target applied(NativeTargetGeneration targets,double x,double feetY,double z,double eye){
        try{return (NativeTargetGeneration.Target)NativeTargetGeneration.class.getMethod("requestAppliedTeleport",double.class,double.class,double.class,double.class,int.class,int.class).invoke(targets,x,feetY,z,eye,64,64);}
        catch(NoSuchMethodException missing){return targets.request(x,feetY,z,64,64,true);}
        catch(ReflectiveOperationException failed){throw new AssertionError(failed);}
    }
    @Test void sameAnchorIsStableButTeleportAndNewSignedAnchorSupersedeOldWork() {
        var targets=new NativeTargetGeneration();
        var a=targets.request(-.01,-330,500,64,64,false);
        assertEquals(1,a.generation());assertEquals(-16,a.anchorX());assertEquals(-336,a.anchorY());
        assertEquals(a,targets.request(-1,-331,501,64,64,false));
        var b=targets.request(499.99,-330,500,64,64,true);
        assertFalse(targets.current(a.generation()));assertTrue(targets.current(b.generation()));
        var c=targets.request(-.01,-330,500,64,64,true);
        assertEquals(3,c.generation());assertFalse(targets.current(b.generation()));
        assertEquals(c.generation()+1,targets.request(-.01,-330,500,128,128,false).generation());
    }
    @Test void invalidCoordinateCannotRetireAValidTarget() {
        var targets=new NativeTargetGeneration();var a=targets.request(-16,-32,-48,64,64,false);
        assertThrows(IllegalArgumentException.class,()->targets.request(Double.NaN,0,0,64,64,true));
        assertTrue(targets.current(a.generation()));
    }
}
