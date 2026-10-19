package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceQuadPlanTest {
    private static TerrainLodColumns.Box box(long x0,long y0,long z0,long x1,long y1,long z1,TerrainMaterial m){return new TerrainLodColumns.Box(x0,y0,z0,x1,y1,z1,m);}
    @Test void adjacentSignedBoxesSubmitTenOuterFacesWithOriginalOwnerLight(){
        var a=box(-32,-80,-16,-16,-64,0,TerrainMaterial.ROCK);var b=box(-16,-80,-16,0,-64,0,TerrainMaterial.SOIL);
        var geometry=SurfaceQuadPlan.extract(List.of(a,b),100,10000);
        assertEquals(10,geometry.faces().size(),"The shared two faces are internal, despite different opaque materials");
        assertFalse(geometry.faces().stream().anyMatch(f->f.axis()==0&&f.plane()==-16));
        var calls=new AtomicInteger();var prepared=SurfaceQuadPlan.prepare(geometry,i->{calls.incrementAndGet();return new SurfaceQuadPlan.Owner(i==0?a:b,3+i,2,0xff7e7b77);});
        assertEquals(2,calls.get());assertEquals(3,prepared.owners().get(0).sky());assertEquals(4,prepared.owners().get(1).sky());
        assertEquals(a,prepared.owners().get(0).box());assertEquals(b,prepared.owners().get(1).box());
    }
    @Test void partialOppositeCoverLeavesExactNativeHoleAndWaterNeverOccludes(){
        var a=box(-16,-16,-16,0,0,0,TerrainMaterial.ROCK);var partial=box(0,-16,-16,16,-8,0,TerrainMaterial.GRASS);
        var geometry=SurfaceQuadPlan.extract(List.of(a,partial),100,10000);
        var visible=geometry.faces().stream().filter(f->f.owner()==0&&f.axis()==0&&f.positive()).toList();
        assertEquals(1,visible.size());assertEquals(-8,visible.get(0).v0());assertEquals(0,visible.get(0).v1());
        var water=box(0,-16,-16,16,0,0,TerrainMaterial.WATER);
        var withWater=SurfaceQuadPlan.extract(List.of(a,water),100,10000);
        assertTrue(withWater.faces().stream().anyMatch(f->f.owner()==0&&f.axis()==0&&f.positive()&&f.v0()==-16));
        assertEquals(6,withWater.faces().stream().filter(f->f.owner()==1).count());
    }
    @Test void enclosedOwnerIsNotRelitAndExactByteBudgetCannotBeBypassed(){
        var boxes=new ArrayList<TerrainLodColumns.Box>();for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)boxes.add(box(x,y,z,x+1,y+1,z+1,TerrainMaterial.ROCK));
        var geometry=SurfaceQuadPlan.extract(boxes,200,200*76);
        assertEquals(54,geometry.faces().size());assertFalse(geometry.visibleOwners().contains(13));
        var calls=new AtomicInteger();SurfaceQuadPlan.prepare(geometry,i->{calls.incrementAndGet();assertNotEquals(13,i);return new SurfaceQuadPlan.Owner(boxes.get(i),7,0,0xff7e7b77);});
        assertEquals(26,calls.get());assertThrows(TerrainLodColumns.BudgetExceededException.class,()->SurfaceQuadPlan.extract(boxes,200,53*76));
    }
    @Test void equalLitGreedyKeepsSourceContributorsDistinctLightAndActualHolePixels(){
        var boxes=new ArrayList<TerrainLodColumns.Box>();for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)boxes.add(box(x,y,z,x+1,y+1,z+1,TerrainMaterial.ROCK));
        var raw=SurfaceQuadPlan.extract(boxes,200,200*76);var calls=new AtomicInteger();
        var merged=SurfaceQuadPlan.prepare(raw,i->{calls.incrementAndGet();return new SurfaceQuadPlan.Owner(boxes.get(i),7,1,0xff7e7b77);});
        assertEquals(6,merged.faces().size(),"54 true outer unit faces become six rectangles only after original scalar lighting");
        assertEquals(26,calls.get());assertEquals(26,merged.owners().size(),"All original lit owner bounds remain available for provenance, not merged bounds");
        assertEquals(pixels(raw.faces()),pixels(merged.faces()));
        var contributors=new HashSet<Integer>();for(var group:merged.contributors()){assertEquals(9,group.size());contributors.addAll(group);}
        assertEquals(26,contributors.size());
        var differentLight=SurfaceQuadPlan.prepare(raw,i->new SurfaceQuadPlan.Owner(boxes.get(i),i==26?8:7,1,0xff7e7b77));
        assertTrue(differentLight.faces().size()>6,"A different original SKY is never absorbed by an equal-color neighbour");
        assertEquals(pixels(raw.faces()),pixels(differentLight.faces()));
        var differentMaterial=new ArrayList<>(boxes);var old=boxes.get(26);
        differentMaterial.set(26,box(old.minX(),old.minY(),old.minZ(),old.maxXExclusive(),old.maxYExclusive(),old.maxZExclusive(),TerrainMaterial.SOIL));
        var materialRaw=SurfaceQuadPlan.extract(differentMaterial,200,200*76);
        var materialPlan=SurfaceQuadPlan.prepare(materialRaw,i->new SurfaceQuadPlan.Owner(differentMaterial.get(i),7,1,0xff7e7b77));
        assertTrue(materialPlan.faces().size()>6,"Different opaque material stays separate even if ARGB and scalar light match");
        var aroundHole=new ArrayList<TerrainLodColumns.Box>();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(x!=0||z!=0)aroundHole.add(box(x,-1,z,x+1,0,z+1,TerrainMaterial.ROCK));
        var holeRaw=SurfaceQuadPlan.extract(aroundHole,200,200*76);
        var holePlan=SurfaceQuadPlan.prepare(holeRaw,i->new SurfaceQuadPlan.Owner(aroundHole.get(i),7,1,0xff7e7b77));
        assertEquals(pixels(holeRaw.faces()),pixels(holePlan.faces()),"Greedy adjacency preserves actual surface union; it cannot span the native hole");
        assertFalse(pixels(holePlan.faces()).contains("1:true:0:0:0"));
    }
    private static Set<String> pixels(List<SurfaceQuadPlan.Face> faces){var out=new HashSet<String>();
        for(var face:faces)for(long u=face.u0();u<face.u1();u++)for(long v=face.v0();v<face.v1();v++)out.add(face.axis()+":"+face.positive()+":"+face.plane()+":"+u+":"+v);return out;}
}
