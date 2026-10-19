package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.kernel.lod.SurfaceQuadPlan;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import java.util.*;

/** Single-worker reduction of a complete immutable PageSet; no page publication protocol or ACK state. */
public final class AbyssLodPublishedLightSummary {
    private record Cached(SurfaceQuadPlan.Prepared prepared,List<AbyssLodPipeline.LitBox> values,
                          BitSet shared,List<TerrainLodColumns.Box> foreign) { }
    public record Stats(int retainedPages,long retainedOwnerReferences,long builtPages,long reusedPages,
                        long boundaryOccurrences,int deduplicatedOwners,int foreignContainedOwners) { }
    private final int pageMetres,maximumPages;
    private final long maximumOwnerReferences;
    private Map<SurfaceQuadPlan.PageKey,Cached> retained=Map.of();
    private volatile Stats stats=new Stats(0,0,0,0,0,0,0);

    public AbyssLodPublishedLightSummary(int pageMetres,int maximumPages,long maximumOwnerReferences){
        if((pageMetres!=64&&pageMetres!=128)||maximumPages<1||maximumOwnerReferences<1)
            throw new IllegalArgumentException("Positive bounded canonical page summary required");
        this.pageMetres=pageMetres;this.maximumPages=maximumPages;this.maximumOwnerReferences=maximumOwnerReferences;
    }
    /** First BOX encounter determines position; its last encountered light determines the value. */
    public List<AbyssLodPipeline.LitBox> build(SurfaceQuadPlan.PageSet pages){
        Objects.requireNonNull(pages);check();
        if(pages.pages().size()>maximumPages)throw cap();
        var next=new HashMap<SurfaceQuadPlan.PageKey,Cached>();
        var ordered=new ArrayList<Cached>(pages.pages().size());
        var foreign=new HashSet<TerrainLodColumns.Box>();
        long references=0,built=0,reused=0;
        for(var page:pages.pages()){
            check();var cached=retained.get(page.key());
            // Prepared records contain large lists: deliberately compare identity, not equals/hashCode.
            if(cached==null||cached.prepared()!=page.prepared()){cached=summarize(page);built++;}else reused++;
            references=Math.addExact(references,cached.values().size());
            if(references>maximumOwnerReferences||references>Integer.MAX_VALUE)throw cap();
            next.put(page.key(),cached);ordered.add(cached);foreign.addAll(cached.foreign());
        }
        var result=new ArrayList<AbyssLodPipeline.LitBox>((int)references);
        var sharedPositions=new HashMap<TerrainLodColumns.Box,Integer>();long boundary=0;int duplicates=0;
        boolean hasForeign=!foreign.isEmpty();
        for(var page:ordered)for(int i=0;i<page.values().size();i++){
            if((i&1023)==0)check();var value=page.values().get(i);
            if(page.shared().get(i)||(hasForeign&&foreign.contains(value.box()))){
                boundary++;var position=sharedPositions.putIfAbsent(value.box(),result.size());
                if(position!=null){result.set(position,value);duplicates++;continue;}
            }
            result.add(value);
        }
        var published=List.copyOf(result);check();
        // Failure/cancellation leaves the previous cache intact. Only the current set is retained.
        retained=next;stats=new Stats(next.size(),references,stats.builtPages()+built,stats.reusedPages()+reused,
                boundary,duplicates,foreign.size());return published;
    }
    private Cached summarize(SurfaceQuadPlan.Page page){
        if(page.prepared().owners().size()>maximumOwnerReferences)throw cap();
        // Also supports duplicate/noncontributing owners in independently constructed Prepared records.
        var values=new LinkedHashMap<TerrainLodColumns.Box,AbyssLodPipeline.LitBox>();int scanned=0;
        for(var owner:page.prepared().owners()){
            if((scanned++&1023)==0)check();
            values.put(owner.box(),new AbyssLodPipeline.LitBox(owner.box(),new AbyssLodPipeline.Light(owner.sky(),owner.block())));
        }
        var unique=List.copyOf(values.values());var shared=new BitSet(unique.size());var foreign=new ArrayList<TerrainLodColumns.Box>();
        for(int i=0;i<unique.size();i++){
            if((i&1023)==0)check();var box=unique.get(i).box();
            long x=Math.floorDiv(box.minX(),pageMetres),y=Math.floorDiv(box.minY(),pageMetres),z=Math.floorDiv(box.minZ(),pageMetres);
            // Positive face planes belong to floorDiv(maxExclusive,pageMetres), not maxExclusive-1.
            // If all six closed bounds share a canonical cell, no extracted face can escape it.
            if(x!=Math.floorDiv(box.maxXExclusive(),pageMetres)||y!=Math.floorDiv(box.maxYExclusive(),pageMetres)
                    ||z!=Math.floorDiv(box.maxZExclusive(),pageMetres))shared.set(i);
            else if(x!=page.key().x()||y!=page.key().y()||z!=page.key().z()){
                shared.set(i);foreign.add(box);
            }
        }
        return new Cached(page.prepared(),unique,shared,List.copyOf(foreign));
    }
    public Stats stats(){return stats;}
    private static TerrainLodColumns.BudgetExceededException cap(){
        return new TerrainLodColumns.BudgetExceededException("Published owner summary exceeds resident reference budget");
    }
    private static void check(){if(Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException("Published owner summary canceled");}
}
