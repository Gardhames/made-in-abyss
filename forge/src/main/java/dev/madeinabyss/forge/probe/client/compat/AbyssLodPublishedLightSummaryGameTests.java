package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodPublishedLightSummaryGameTests {
    @GameTest(template="empty",timeoutTicks=100)
    public static void publishedOwnerSummaryKeepsExactOrderLastValueAndPageLifecycle(GameTestHelper h){
        var summary=new AbyssLodPublishedLightSummary(128,32,1000);
        var interior=box(-120,-20,-9,-119,-18,-7);
        var positiveBoundary=box(126,3,3,128,7,7);
        var negativeBoundary=box(-2,-5,-3,0,-1,-1);
        var foreign=box(4,4,4,5,5,5);
        var p0=page(new SurfaceQuadPlan.PageKey(-1,-1,-1),owner(interior,3),owner(negativeBoundary,2),owner(interior,4));
        var p1=page(new SurfaceQuadPlan.PageKey(0,0,0),owner(positiveBoundary,5),owner(foreign,6));
        var p2=page(new SurfaceQuadPlan.PageKey(1,0,0),owner(positiveBoundary,7),owner(foreign,8));
        var p3=page(new SurfaceQuadPlan.PageKey(0,-1,-1),owner(negativeBoundary,9));
        var first=set(p0,p1,p2,p3);var expected=reference(first);var actual=summary.build(first);
        h.assertTrue(actual.equals(expected),"Negative pages, duplicate owners, maxExclusive128 and foreign noncontributing owners retain first position / last value");
        var repeated=summary.build(set(new SurfaceQuadPlan.Page(p0.key(),p0.prepared()),p1,p2,p3));
        h.assertTrue(repeated.equals(expected),"New Page wrapper with identical Prepared reuses the exact ordered summary");
        for(int i=0;i<actual.size();i++)h.assertTrue(repeated.get(i)==actual.get(i),"Unchanged pages reuse each LitBox and Light object");
        h.assertTrue(summary.stats().builtPages()==4&&summary.stats().reusedPages()==4,"Prepared identity avoids rebuilding unchanged pages");
        var changed=set(p3,p0,page(p1.key(),owner(foreign,11),owner(positiveBoundary,12)));
        h.assertTrue(summary.build(changed).equals(reference(changed)),"Reorder, replacement/resource page and removal match the independent complete reduction");
        h.assertTrue(summary.stats().retainedPages()==3,"Removed pages are not retained by the cache");
        h.assertTrue(summary.build(set()).isEmpty()&&summary.stats().retainedOwnerReferences()==0,"Empty set releases every retained owner summary");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void publishedOwnerSummaryMatchesActualCanonicalExtractionAndKeepsFailedCache(GameTestHelper h){
        var boxes=List.of(box(-129,-2,-2,-127,2,2),box(-128,5,5,-127,7,7),box(-2,-5,-3,0,-1,-1),
                box(126,3,3,128,7,7),box(127,16,16,129,18,18),box(7,7,7,8,8,8));
        var packing=new SurfaceQuadPages(SurfaceQuadPages.Limits.defaults(),128);var geometry=packing.geometry(boxes);
        var pages=packing.prepare(geometry,index->owner(boxes.get(index),index+1));
        var summary=new AbyssLodPublishedLightSummary(128,32,1000);
        var first=summary.build(pages);h.assertTrue(first.equals(reference(pages)),"Actual clipped production geometry agrees on signed canonical planes");
        var before=summary.stats();Thread.currentThread().interrupt();boolean canceled=false;
        try{summary.build(set());}catch(java.util.concurrent.CancellationException expected){canceled=true;}finally{Thread.interrupted();}
        h.assertTrue(canceled&&summary.stats().equals(before),"Cancellation cannot commit a partial replacement cache");
        h.assertTrue(summary.build(pages).equals(first),"Retry after cancellation retains every exact scalar");
        var bounded=new AbyssLodPublishedLightSummary(128,1,1);var single=set(page(new SurfaceQuadPlan.PageKey(0,0,0),owner(boxes.get(5),2)));
        var prior=bounded.build(single);boolean refused=false;
        try{bounded.build(set(page(new SurfaceQuadPlan.PageKey(0,0,0),owner(boxes.get(5),2),owner(boxes.get(3),3))));}
        catch(TerrainLodColumns.BudgetExceededException expected){refused=true;}
        h.assertTrue(refused&&bounded.stats().retainedOwnerReferences()==1&&bounded.build(single).get(0)==prior.get(0),
                "Resident reference refusal preserves the previously committed page summary");
        h.succeed();
    }
    private static TerrainLodColumns.Box box(long x,long y,long z,long xx,long yy,long zz){return new TerrainLodColumns.Box(x,y,z,xx,yy,zz,TerrainMaterial.ROCK);}
    private static SurfaceQuadPlan.Owner owner(TerrainLodColumns.Box box,int sky){return new SurfaceQuadPlan.Owner(box,sky,sky%4,0xff887766);}
    private static SurfaceQuadPlan.Page page(SurfaceQuadPlan.PageKey key,SurfaceQuadPlan.Owner... owners){
        return new SurfaceQuadPlan.Page(key,new SurfaceQuadPlan.Prepared(List.of(owners),List.of(),List.of(),0));
    }
    private static SurfaceQuadPlan.PageSet set(SurfaceQuadPlan.Page... pages){
        int faces=0,opaque=0,water=0;long bytes=0;
        for(var page:pages){faces+=page.prepared().faces().size();opaque+=page.opaqueFaces();water+=page.waterFaces();bytes+=page.prepared().vertexBytes();}
        return new SurfaceQuadPlan.PageSet(List.of(pages),bytes,(long)faces*SurfaceQuadPlan.GPU_BYTES_PER_FACE,faces,opaque,water);
    }
    private static List<AbyssLodPipeline.LitBox> reference(SurfaceQuadPlan.PageSet pages){
        var values=new LinkedHashMap<TerrainLodColumns.Box,AbyssLodPipeline.Light>();
        for(var page:pages.pages())for(var owner:page.prepared().owners())values.put(owner.box(),new AbyssLodPipeline.Light(owner.sky(),owner.block()));
        var result=new ArrayList<AbyssLodPipeline.LitBox>();values.forEach((box,light)->result.add(new AbyssLodPipeline.LitBox(box,light)));return List.copyOf(result);
    }
}
