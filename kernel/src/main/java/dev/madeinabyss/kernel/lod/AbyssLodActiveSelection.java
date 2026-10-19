package dev.madeinabyss.kernel.lod;

import java.util.*;
import java.util.function.Predicate;

/** Selects a display window independently of the caller's reusable source-grid storage. */
public final class AbyssLodActiveSelection {
    private AbyssLodActiveSelection() { }

    public record Selection(String sourceIdentity, double x, double y, double z,
            List<TerrainLodRefinement.Band> bands, List<AbyssLodFineLedger.Grid> witnessPins,
            AbyssLodFineLedger.Candidate candidate,List<AbyssLodFineLedger.Grid> protectedGrids,long maximumBytes) {
        public Selection {
            Objects.requireNonNull(sourceIdentity); Objects.requireNonNull(candidate);
            bands=List.copyOf(bands); witnessPins=List.copyOf(witnessPins);protectedGrids=List.copyOf(protectedGrids);
            if(witnessPins.size()>8 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Bounded witness island and finite camera required");
        }
    }
    public record Admission(AbyssLodFineLedger.Candidate candidate,
            AbyssLodFineLedger.Composition composition, int deferredGrids,AbyssLodFineLedger.Composition rawComposition) {
        public Admission(AbyssLodFineLedger.Candidate candidate,AbyssLodFineLedger.Composition composition,int deferredGrids){this(candidate,composition,deferredGrids,composition);}
        public Admission {
            Objects.requireNonNull(candidate); Objects.requireNonNull(composition);Objects.requireNonNull(rawComposition);
            if(deferredGrids<0) throw new IllegalArgumentException("Negative deferred grids");
        }
    }

    public static Selection select(AbyssLodFineLedger ledger, String sourceIdentity,
            List<AbyssLodFineLedger.Grid> available, List<AbyssLodFineLedger.Grid> witnessPins,
            double x, double y, double z, List<TerrainLodRefinement.Band> bands, double legacyOffset) {
        return select(ledger,sourceIdentity,available,witnessPins,x,y,z,bands,legacyOffset,grid->true);
    }
    /** Current-plan eligibility applies equally to reusable RAW and acknowledged history. */
    public static Selection select(AbyssLodFineLedger ledger, String sourceIdentity,
            List<AbyssLodFineLedger.Grid> available, List<AbyssLodFineLedger.Grid> witnessPins,
            double x, double y, double z, List<TerrainLodRefinement.Band> bands, double legacyOffset,
            Predicate<AbyssLodFineLedger.Grid> eligible) {
        return select(ledger,sourceIdentity,available,witnessPins,x,y,z,bands,legacyOffset,eligible,grid->false);
    }
    /** Exact initial acquisition fragments retain their BOX ownership even if full FAR arrived first. */
    public static Selection select(AbyssLodFineLedger ledger, String sourceIdentity,
            List<AbyssLodFineLedger.Grid> available, List<AbyssLodFineLedger.Grid> witnessPins,
            double x, double y, double z, List<TerrainLodRefinement.Band> bands, double legacyOffset,
            Predicate<AbyssLodFineLedger.Grid> eligible,Predicate<AbyssLodFineLedger.Grid> exactOwnership) {
        Objects.requireNonNull(eligible);
        Objects.requireNonNull(exactOwnership);
        // A witness is at most eight explicit source grids, never an expansion of all tiers.
        var additions=new ArrayList<AbyssLodFineLedger.Grid>();
        for(var grid:available) if(eligible.test(grid)&&distance(grid.bounds(),x,y,z)<=keepRadius(grid.step(),bands)+16) additions.add(grid);
        additions.addAll(witnessPins);
        var chosen=ledger.prepareForAdmission(sourceIdentity,additions,x,y,z,bands,eligible,exactOwnership);
        var originalProtected=new ArrayList<AbyssLodFineLedger.Grid>(witnessPins);
        for(var grid:ledger.displayed()) if(grid.sourceIdentity().equals(sourceIdentity)&&grid.step()<=2
                &&distance(grid.bounds(),x,y,z)<=keepRadius(grid.step(),bands)+16) originalProtected.add(grid);
        var cells=new HashMap<Cell,List<AbyssLodFineLedger.Grid>>();
        for(var grid:chosen.grids()) cells.computeIfAbsent(cell(grid),ignored->new ArrayList<>()).add(grid);
        var selected=new ArrayList<AbyssLodFineLedger.Grid>();
        var protectedKeys=new HashSet<Key>();for(var grid:originalProtected)protectedKeys.add(key(grid));
        var protectedGrids=new ArrayList<AbyssLodFineLedger.Grid>();
        for(var grid:chosen.grids()) {
            boolean dominated=false;
            for(var other:cells.get(cell(grid))) if(other.step()<grid.step()&&contains(other.bounds(),grid.bounds())) {
                dominated=true;if(protectedKeys.contains(key(grid)))protectedKeys.add(key(other));break;
            }
            if(!dominated)selected.add(grid);
        }
        // Composition gives the earlier same-step grid precedence. Match initial local
        // acquisition even when a warm view already acknowledged the complete FAR cell.
        selected.sort(Comparator.comparingInt(grid->exactOwnership.test(grid)?0:1));
        for(var grid:selected)if(protectedKeys.contains(key(grid)))protectedGrids.add(grid);
        return new Selection(sourceIdentity,x,y,z,bands,witnessPins,candidate(selected),protectedGrids,ledger.maximumBytes());
    }

    public static Admission composeParts(Selection selection, List<TerrainLodColumns.Box> rawBackdrop,
            List<TerrainLodClipper.BoundsLong> nativeCuts, int maxBoxes, int maxFragments) {
        return composeRenderedParts(selection,rawBackdrop,nativeCuts,maxBoxes,maxFragments,java.util.function.UnaryOperator.identity());
    }
    /** Source ownership is complete before the caller removes only certified buried render boxes. */
    public static Admission composeRenderedParts(Selection selection,List<TerrainLodColumns.Box> rawBackdrop,
            List<TerrainLodClipper.BoundsLong> nativeCuts,int maxBoxes,int maxFragments,
            java.util.function.UnaryOperator<AbyssLodFineLedger.Composition> renderSurface) {
        Objects.requireNonNull(renderSurface);
        if(maxBoxes<1||maxFragments<maxBoxes)throw new IllegalArgumentException("Fragment cap must cover positive scene cap");
        try{return compose(selection,selection.candidate(),rawBackdrop,nativeCuts,maxBoxes,maxFragments,0,renderSurface);}
        catch(TerrainLodColumns.BudgetExceededException fullCap) {
            var protectedKeys=new HashSet<Key>();for(var grid:selection.protectedGrids())protectedKeys.add(key(grid));
            var optional=new ArrayList<AbyssLodFineLedger.Grid>();
            for(var grid:selection.candidate().grids())if(!protectedKeys.contains(key(grid)))optional.add(grid);
            optional.sort(Comparator.comparingInt(AbyssLodFineLedger.Grid::step)
                    .thenComparingDouble(grid->distance(grid.bounds(),selection.x(),selection.y(),selection.z())));
            int retain=optional.size();
            while(retain>0) {
                // Remove a bounded chunk of lowest-priority history. BOX counts are not monotone
                // because complete sampled AIR can remove coarse boxes; no binary-fit assumption.
                retain-=Math.max(1,(retain+3)/4);
                var grids=new ArrayList<>(selection.protectedGrids());grids.addAll(optional.subList(0,retain));
                try{return compose(selection,candidate(grids),rawBackdrop,nativeCuts,maxBoxes,maxFragments,
                        selection.candidate().grids().size()-grids.size(),renderSurface);}
                catch(TerrainLodColumns.BudgetExceededException fewerCap) { }
            }
            return compose(selection,candidate(selection.protectedGrids()),rawBackdrop,nativeCuts,maxBoxes,maxFragments,
                    selection.candidate().grids().size()-selection.protectedGrids().size(),renderSurface);
        }
    }
    private static Admission compose(Selection selection,AbyssLodFineLedger.Candidate candidate,List<TerrainLodColumns.Box> raw,
            List<TerrainLodClipper.BoundsLong> cuts,int maxBoxes,int maxFragments,int deferred,
            java.util.function.UnaryOperator<AbyssLodFineLedger.Composition> renderSurface) {
        if(candidate.bytes()>selection.maximumBytes())throw new TerrainLodColumns.BudgetExceededException("Fine ledger exceeds retained geometry cap");
        // The fragment bound still applies to exact source composition. The BOX bound applies
        // to submitted render boxes after conservative complete-face enclosure certification.
        var source=AbyssLodFineLedger.composeParts(raw,candidate,cuts,maxFragments,maxFragments);
        var render=Objects.requireNonNull(renderSurface.apply(source));
        if((long)render.background().size()+render.fine().size()>maxBoxes)
            throw new TerrainLodColumns.BudgetExceededException("Scene cannot preserve fine within actual box cap");
        return new Admission(candidate,render,deferred,source);
    }
    private static AbyssLodFineLedger.Candidate candidate(List<AbyssLodFineLedger.Grid> grids) {
        long bytes=0;for(var grid:grids)bytes=Math.addExact(bytes,Math.addExact(128,Math.multiplyExact(80L,grid.raw().boxes().size())));
        return new AbyssLodFineLedger.Candidate(grids,bytes);
    }
    private record Key(TerrainLodColumns.Bounds bounds,int step) { }
    private record Cell(long x,long y,long z) { }
    private static Key key(AbyssLodFineLedger.Grid grid){return new Key(grid.bounds(),grid.step());}
    private static Cell cell(AbyssLodFineLedger.Grid grid){var b=grid.bounds();return new Cell(Math.floorDiv(b.minX(),16),Math.floorDiv(b.minY(),64),Math.floorDiv(b.minZ(),16));}
    private static boolean contains(TerrainLodColumns.Bounds outer,TerrainLodColumns.Bounds inner) {
        return outer.minX()<=inner.minX()&&outer.maxXInclusive()>=inner.maxXInclusive()&&outer.minY()<=inner.minY()
                &&outer.maxYInclusive()>=inner.maxYInclusive()&&outer.minZ()<=inner.minZ()&&outer.maxZInclusive()>=inner.maxZInclusive();
    }
    private static double keepRadius(int step,List<TerrainLodRefinement.Band> bands) {
        double radius=0;for(var band:bands) if(band.horizontalStep()<=step) radius=Math.max(radius,band.radius());return radius;
    }
    private static double distance(TerrainLodColumns.Bounds b,double x,double y,double z) {
        double dx=Math.max(0,Math.max(b.minX()-x,x-((long)b.maxXInclusive()+1)));
        double dy=Math.max(0,Math.max(b.minY()-y,y-((long)b.maxYInclusive()+1)));
        double dz=Math.max(0,Math.max(b.minZ()-z,z-((long)b.maxZInclusive()+1)));
        return Math.hypot(Math.hypot(dx,dy),dz);
    }
}
