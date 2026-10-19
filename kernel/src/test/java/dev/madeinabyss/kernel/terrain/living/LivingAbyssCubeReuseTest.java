package dev.madeinabyss.kernel.terrain.living;

import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detects signed index aliasing, stale epochs and mutable feature-view publication. */
class LivingAbyssCubeReuseTest {
    private static Object invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception {
        var method=assertDoesNotThrow(()->target.getClass().getMethod(name,types),"Missing immutable cube-reuse behavior: "+name);
        return method.invoke(target,args);
    }
    @Test void immutableFeatureViewReusesTheSignedCubeWithoutLeakingBuilderEdits()throws Exception{
        var b=new LivingAbyssVoxelPlan.Builder();b.put(-17,-33,-1,255);b.put(-32,-48,-16,7);
        var frozen=b.freeze();
        var view=invoke(frozen,"cubeView",new Class<?>[]{int.class,int.class,int.class},-2,-3,-1);
        assertNotNull(view);b.put(-17,-33,-1,1);
        for(int i=0;i<4096;i++)assertEquals(i==4095?255:i==0?7:0,invoke(view,"atLocal",new Class<?>[]{int.class},i));
        assertNull(invoke(frozen,"cubeView",new Class<?>[]{int.class,int.class,int.class},-1,-3,-1));
        assertEquals(255,frozen.at(-17,-33,-1));
    }
    @Test void predicateMemoDoesNotAliasSignedCoordinatesOrRetainThePreviousCube()throws Exception{
        var type=assertDoesNotThrow(()->Class.forName("dev.madeinabyss.kernel.terrain.living.LivingAbyssCubePredicates"),"Missing per-cube exact predicate memo");
        var memo=type.getConstructor().newInstance();var xyz=new Class<?>[]{int.class,int.class,int.class};
        var xyzw=new Class<?>[]{int.class,int.class,int.class,int.class};
        invoke(memo,"begin",xyz,-32,-48,-16);
        for(int i=0;i<4096;i++)invoke(memo,"put",xyzw,-32+(i&15),-48+(i>>8),-16+((i>>4)&15),4+(i&3));
        for(int i=0;i<4096;i++)assertEquals(4+(i&3),invoke(memo,"get",xyz,-32+(i&15),-48+(i>>8),-16+((i>>4)&15)));
        assertEquals(0,invoke(memo,"get",xyz,-16,-48,-16));
        assertEquals(0,invoke(memo,"get",xyz,Integer.MAX_VALUE,-48,-16));
        invoke(memo,"end",new Class<?>[0]);assertEquals(0,invoke(memo,"get",xyz,-32,-48,-16));
        invoke(memo,"begin",xyz,-16,-32,0);assertEquals(0,invoke(memo,"get",xyz,-16,-32,0));
        invoke(memo,"begin",xyz,-32,-48,-16);assertEquals(0,invoke(memo,"get",xyz,-32,-48,-16));
    }
}
