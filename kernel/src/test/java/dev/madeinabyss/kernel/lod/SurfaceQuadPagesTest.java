package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.light.LodBoxSkyLight;
import dev.madeinabyss.kernel.light.SkyVisibility;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceQuadPagesTest {
    private static final Comparator<SurfaceQuadPlan.PageKey> KEY_ORDER=Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);
    private static SurfaceQuadPlan.Owner light(TerrainLodColumns.Box box,int sky){return new SurfaceQuadPlan.Owner(box,sky,2,0xff7e7b77);}
    @Test void partialRetainsUnlitSignedPageWhilePublishingReadyExactPageAndThenRemovesEmpty(){
        var pages=new SurfaceQuadPages(SurfaceQuadPages.Limits.defaults(),128);var oldBoxes=List.of(rock(-127,1,1,-126,2,2),rock(1,1,1,2,2,2));
        var old=pages.prepare(pages.geometry(oldBoxes),i->light(oldBoxes.get(i),7));var left=new SurfaceQuadPlan.PageKey(-1,0,0);var right=new SurfaceQuadPlan.PageKey(0,0,0);
        var nextBoxes=List.of(rock(-127,1,1,-125,2,2),oldBoxes.get(1));var geometry=pages.geometry(nextBoxes);
        var partial=pages.preparePartial(geometry,i->i==0?null:light(nextBoxes.get(i),9),old,1,KEY_ORDER);
        assertEquals(Set.of(right),partial.currentKeys());assertEquals(Set.of(right),partial.changedKeys());assertEquals(1,partial.pendingPages());
        assertSame(page(old,left),page(partial.published(),left));assertEquals(9,page(partial.published(),right).owners().get(0).sky());
        var completed=pages.preparePartial(geometry,i->light(nextBoxes.get(i),9),partial.published(),1,KEY_ORDER);
        assertEquals(Set.of(left,right),completed.currentKeys());assertEquals(0,completed.pendingPages());assertEquals(Set.of(left),completed.changedKeys());
        assertSame(page(partial.published(),right),page(completed.published(),right));
        var empty=pages.preparePartial(pages.geometry(List.of()),i->{throw new AssertionError("Empty source needs no light");},completed.published(),2,KEY_ORDER);
        assertTrue(empty.published().pages().isEmpty());assertEquals(Set.of(left,right),empty.changedKeys());assertEquals(0,empty.pendingPages());
    }
    @Test void partialSharedOriginalOwnerAcrossNegative128SeamIsExactAndSelectedOnce(){
        var box=rock(-129,1,1,-127,2,2);var pages=new SurfaceQuadPages(SurfaceQuadPages.Limits.defaults(),128);var geometry=pages.geometry(List.of(box));
        assertEquals(List.of(0),pages.pendingPageOwners(geometry,i->null,KEY_ORDER,1));
        var empty=new SurfaceQuadPlan.PageSet(List.of(),0,0,0,0,0);var first=pages.preparePartial(geometry,i->light(box,13),empty,1,KEY_ORDER);
        assertEquals(1,first.published().pages().size());assertEquals(1,first.currentKeys().size());assertEquals(1,first.pendingPages());
        var both=pages.preparePartial(geometry,i->light(box,13),first.published(),1,KEY_ORDER);assertEquals(0,both.pendingPages());assertEquals(2,both.currentKeys().size());
        var actual=new ArrayList<SurfaceQuadPlan.Face>();for(var page:both.published().pages()){for(var owner:page.prepared().owners()){assertEquals(box,owner.box());assertEquals(13,owner.sky());}actual.addAll(page.prepared().faces());}
        assertEquals(pixels(SurfaceQuadPlan.extract(List.of(box),1000,76000).faces()),pixels(actual));
        var reused=pages.preparePartial(geometry,i->light(box,13),both.published(),1,KEY_ORDER);assertTrue(reused.changedKeys().isEmpty());
        for(var page:both.published().pages())assertSame(page.prepared(),page(reused.published(),page.key()));
    }
    @Test void partialAggregateCapFailureCannotSilentlyDropRetainedPendingPage(){
        var limits=new SurfaceQuadPages.Limits(1,1000,1_000_000,1000,1_000_000,1000,76000);var pages=new SurfaceQuadPages(limits,128);
        var left=rock(-127,1,1,-126,2,2);var right=rock(1,1,1,2,2,2);var old=pages.prepare(pages.geometry(List.of(left)),i->light(left,7));
        var geometry=pages.geometry(List.of(left,right));var oldPrepared=old.pages().get(0).prepared();
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->pages.preparePartial(geometry,i->i==0?null:light(right,9),old,1,KEY_ORDER));
        assertEquals(1,old.pages().size());assertSame(oldPrepared,old.pages().get(0).prepared());assertEquals(7,oldPrepared.owners().get(0).sky());
    }
    private static TerrainLodColumns.Box rock(long x0,long y0,long z0,long x1,long y1,long z1){return new TerrainLodColumns.Box(x0,y0,z0,x1,y1,z1,TerrainMaterial.ROCK);}
    private static int originalSky(TerrainLodColumns.Box box){return LodBoxSkyLight.maximum(box,(x,y,z)->0,(x,y,z)->0,
        (x,y,z)->(int)Math.round(14*SkyVisibility.visibleFraction(x,y,z,8,512,(a,b,c)->b==2&&Math.floorMod(a,5)==0?15:0)));}
    @Test void signedPageBorderAndActualHoleKeepWholeOriginal32LightOwners(){
        var original=List.of(rock(-65,-2,-2,-1,0,2),rock(-1,-2,-2,65,0,2));
        var input=new ArrayList<>(TerrainLodClipper.subtract(original,List.of(new TerrainLodClipper.BoundsLong(-1,-2,-1,1,0,1)),100));
        input.add(new TerrainLodColumns.Box(64,0,2,65,1,3,TerrainMaterial.WATER));
        var expected=SurfaceQuadPlan.extract(input,1000,76000);var oracle=new HashMap<TerrainLodColumns.Box,Integer>();for(var box:input)oracle.put(box,originalSky(box));
        for(int packing:new int[]{64,128}){
        var pages=new SurfaceQuadPages(SurfaceQuadPages.Limits.defaults(),packing);var geometry=pages.geometry(input);var calls=new AtomicInteger();
        var result=pages.prepare(geometry,i->{calls.incrementAndGet();var box=input.get(i);return new SurfaceQuadPlan.Owner(box,originalSky(box),2,box.material()==TerrainMaterial.WATER?0xb4355ea3:0xff7e7b77);});
        assertEquals(expected.visibleOwners().size(),calls.get(),"A crossing original owner is lit once, not once per page fragment");
        var output=new ArrayList<SurfaceQuadPlan.Face>();for(var page:result.pages()){
            for(var owner:page.prepared().owners()){assertTrue(input.contains(owner.box()));assertEquals(oracle.get(owner.box()),owner.sky());}
            for(var face:page.prepared().faces()){output.add(face);var p=page.key();long x=face.axis()==0?face.plane():face.u0(),y=face.axis()==1?face.plane():face.v0(),z=face.axis()==0?face.u0():face.axis()==1?face.v0():face.plane();
                assertEquals(Math.floorDiv(x,packing),p.x(),"Normal-plane signs and signed page borders share the canonical page");
                assertEquals(Math.floorDiv(y,packing),p.y());assertEquals(Math.floorDiv(z,packing),p.z());
                long uBase=(face.axis()==0?p.z():p.x())*packing,vBase=(face.axis()==1?p.z():p.y())*packing;
                assertTrue(face.u0()>=uBase&&face.u1()<=uBase+packing&&face.v0()>=vBase&&face.v1()<=vBase+packing);}}
        assertEquals(pixels(expected.faces()),pixels(output),"Face-only page clipping cannot add the removed native hole or lose a visible pixel");
        assertTrue(result.waterFaces()>0);}
    }
    @Test void runtimePackingPreservesSurfaceAndRefusesExactPageCountBeforeOwnerLight(){
        var limits=new SurfaceQuadPages.Limits(2,1000,1_000_000,1000,1_000_000,1000,76000);
        var input=List.of(rock(0,0,0,128,1,1));var old=new SurfaceQuadPages(limits);var calls=new AtomicInteger();
        var refusal=assertThrows(TerrainLodColumns.BudgetExceededException.class,()->old.prepare(old.geometry(input),i->{calls.incrementAndGet();return new SurfaceQuadPlan.Owner(input.get(i),9,2,0xff7e7b77);}));
        assertTrue(refusal.getMessage().contains("PAGECOUNT"));assertEquals(0,calls.get());assertEquals(3,old.status().get("attemptedPages"));
        var packed=new SurfaceQuadPages(limits,128);var result=packed.prepare(packed.geometry(input),i->{calls.incrementAndGet();return new SurfaceQuadPlan.Owner(input.get(i),9,2,0xff7e7b77);});
        assertEquals(2,result.pages().size());assertEquals(1,calls.get());
        var actual=new ArrayList<SurfaceQuadPlan.Face>();for(var page:result.pages()){for(var owner:page.prepared().owners()){assertEquals(input.get(0),owner.box());assertEquals(9,owner.sky());assertEquals(2,owner.block());}actual.addAll(page.prepared().faces());}
        assertEquals(pixels(SurfaceQuadPlan.extract(input,1000,76000).faces()),pixels(actual));
    }
    @Test void unchangedPageKeepsPreparedIdentityAndTouchingOppositeNeighbourDirtiesItsBorder(){
        var left=rock(48,0,0,64,16,16);var distant=rock(192,0,0,208,16,16);var touching=rock(64,0,0,80,16,16);
        var pages=new SurfaceQuadPages(SurfaceQuadPages.Limits.defaults());var first=List.of(left,distant);
        var a=pages.prepare(pages.geometry(first),i->new SurfaceQuadPlan.Owner(first.get(i),7,0,0xff7e7b77));
        var again=pages.prepare(pages.geometry(first),i->new SurfaceQuadPlan.Owner(first.get(i),7,0,0xff7e7b77));
        assertSame(page(a,new SurfaceQuadPlan.PageKey(0,0,0)),page(again,new SurfaceQuadPlan.PageKey(0,0,0)),"An unchanged exact source/light page is the same immutable GPU reuse key");
        var changed=List.of(left,distant,touching);var b=pages.prepare(pages.geometry(changed),i->new SurfaceQuadPlan.Owner(changed.get(i),7,0,0xff7e7b77));
        assertSame(page(a,new SurfaceQuadPlan.PageKey(3,0,0)),page(b,new SurfaceQuadPlan.PageKey(3,0,0)),"A remote unchanged page survives a local seam update");
        assertFalse(b.pages().stream().flatMap(p->p.prepared().faces().stream()).anyMatch(f->f.axis()==0&&f.plane()==64&&f.u0()<16&&f.v0()<16),"Both opposite signs at page64 are hidden by actual whole neighbours");
    }
    private static SurfaceQuadPlan.Prepared page(SurfaceQuadPlan.PageSet set,SurfaceQuadPlan.PageKey key){return set.pages().stream().filter(p->p.key().equals(key)).findFirst().orElseThrow().prepared();}
    private static Set<String> pixels(List<SurfaceQuadPlan.Face> faces){var out=new HashSet<String>();for(var f:faces)for(long u=f.u0();u<f.u1();u++)for(long v=f.v0();v<f.v1();v++)out.add(f.axis()+":"+f.positive()+":"+f.plane()+":"+u+":"+v);return out;}
}
