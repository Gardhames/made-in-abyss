package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.light.LodBoxSkyLight;
import dev.madeinabyss.kernel.light.SkyVisibility;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceOwnerLightPlanTest {
    @Test void selectedPageOwnersFinishExactlyOnceAndLeaveLegacyFarCursorUsable(){
        var boxes=List.of(new TerrainLodColumns.Box(-129,1,1,-127,2,2,TerrainMaterial.ROCK),
            new TerrainLodColumns.Box(1,1,1,2,2,2,TerrainMaterial.ROCK),new TerrainLodColumns.Box(129,1,1,130,2,2,TerrainMaterial.ROCK));
        var scope=new SurfaceOwnerLightPlan.Scope("lease",1,"source",1);var plan=new SurfaceOwnerLightPlan<Integer>(scope,boxes,List.of(0,1,2),3,3,null,b->null);
        var selected=plan.pendingOwners(List.of(2,0,2,1),2);assertEquals(List.of(2,0),selected);
        var overlap=plan.pendingOwners(List.of(0,1),2);
        assertThrows(NullPointerException.class,()->plan.complete(selected,Arrays.asList(12,null)));assertEquals(3,plan.remaining());assertNull(plan.value(2));
        plan.complete(selected,List.of(12,10));assertEquals(1,plan.remaining());assertEquals(12,plan.value(2));assertEquals(10,plan.value(0));
        assertThrows(IllegalArgumentException.class,()->plan.complete(selected,List.of(12,10)));
        assertThrows(IllegalArgumentException.class,()->plan.complete(overlap,List.of(10,11)));assertNull(plan.value(1));
        assertEquals(List.of(1),plan.pendingOwners(List.of(2,0,1),3));
        var far=plan.pendingFar(1024);assertEquals(List.of(1),far);plan.complete(far,List.of(11));assertEquals(0,plan.remaining());
        assertThrows(IllegalArgumentException.class,()->plan.pendingOwners(List.of(0),0));
    }
    private static int original32(TerrainLodColumns.Box box){return LodBoxSkyLight.maximum(box,(x,y,z)->0,(x,y,z)->0,
        (x,y,z)->SkyVisibility.diffuseLevel(SkyVisibility.visibleFraction(x,y,z,8,128,(a,b,c)->b==2&&Math.floorMod(a,7)<2?15:0)));}
    @Test void original32SignedOwnersAdvanceBoundedFarOnceAndReuseOnlySameSource(){
        var boxes=new ArrayList<TerrainLodColumns.Box>();for(int x=-1030;x<=1030;x++)boxes.add(new TerrainLodColumns.Box(x,-4,-3,x+1,-3,-2,TerrainMaterial.ROCK));
        var visible=new ArrayList<Integer>();for(int i=0;i<boxes.size();i++)visible.add(i);
        var scope=new SurfaceOwnerLightPlan.Scope("lease",3,"actual-source",7);var probes=new AtomicInteger();
        var plan=new SurfaceOwnerLightPlan<Integer>(scope,boxes,visible,2060,3000,null,box->{probes.incrementAndGet();return null;});
        assertEquals(boxes.size(),probes.get());var fine=plan.pendingFine();assertEquals(1,fine.size());
        plan.complete(fine,List.of(original32(plan.box(fine.get(0)))));
        assertEquals(2060,plan.remaining(),"Successful fine completion must advance its cursor without rescanning all owners");
        assertTrue(plan.pendingFine().isEmpty());assertThrows(IllegalArgumentException.class,()->plan.complete(fine,List.of(7)));
        var invalid=plan.pendingFar(1024);var incomplete=new ArrayList<Integer>(Collections.nCopies(invalid.size(),7));incomplete.set(incomplete.size()-1,null);
        assertThrows(NullPointerException.class,()->plan.complete(invalid,incomplete));assertEquals(2060,plan.remaining());assertNull(plan.value(invalid.get(0)));
        int farCompleted=0;for(int turn=0;turn<3;turn++){var batch=plan.pendingFar(1024);assertTrue(batch.size()<=1024);var values=new ArrayList<Integer>();
            for(int owner:batch)values.add(original32(plan.box(owner)));plan.complete(batch,values);farCompleted+=batch.size();assertEquals(2060-farCompleted,plan.remaining());}
        assertEquals(2060,farCompleted);assertEquals(0,plan.remaining());assertTrue(plan.pendingFar(1024).isEmpty());
        for(int owner:visible)assertEquals(original32(boxes.get(owner)),plan.value(owner));
        probes.set(0);var same=new SurfaceOwnerLightPlan<Integer>(scope,boxes,visible,2060,3000,plan,box->{probes.incrementAndGet();return null;});
        assertEquals(0,probes.get());assertEquals(0,same.remaining());for(int owner:visible)assertSame(plan.value(owner),same.value(owner));
        var changed=new SurfaceOwnerLightPlan<Integer>(new SurfaceOwnerLightPlan.Scope("lease",3,"actual-source",8),boxes,visible,2060,3000,plan,box->{probes.incrementAndGet();return null;});
        assertEquals(boxes.size(),probes.get());assertEquals(boxes.size(),changed.remaining());
        assertThrows(IllegalArgumentException.class,()->new SurfaceOwnerLightPlan<>(scope,boxes,visible,2060,10,null,box->7));
    }
}
