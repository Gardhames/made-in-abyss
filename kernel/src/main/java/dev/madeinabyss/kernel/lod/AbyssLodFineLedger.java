package dev.madeinabyss.kernel.lod;

import java.util.*;
import java.util.function.Predicate;

/** Raw source grids are promised only by an actual successful scene promotion. */
public final class AbyssLodFineLedger {
    public record Grid(String sourceIdentity,TerrainLodColumns.Bounds bounds,int step,TerrainLodColumns.Result raw) {
        public Grid {
            Objects.requireNonNull(sourceIdentity);Objects.requireNonNull(bounds);Objects.requireNonNull(raw);
            var region=new TerrainLodRefinement.Region(bounds,step);
            if((long)bounds.maxXInclusive()-bounds.minX()>=16||(long)bounds.maxYInclusive()-bounds.minY()>=64||(long)bounds.maxZInclusive()-bounds.minZ()>=16)
                throw new IllegalArgumentException("Fine source grid exceeds16x64x16");
            if(Math.floorDiv(bounds.minX(),16)!=Math.floorDiv(bounds.maxXInclusive(),16)||Math.floorDiv(bounds.minY(),64)!=Math.floorDiv(bounds.maxYInclusive(),64)
                    ||Math.floorDiv(bounds.minZ(),16)!=Math.floorDiv(bounds.maxZInclusive(),16))throw new IllegalArgumentException("Fine source grid crosses its original cell");
            if(sourceIdentity.isEmpty()||raw.sampledVoxels()!=region.sampledVoxels())throw new IllegalArgumentException("Incomplete source grid");
            for(var b:raw.boxes())if(b.minX()<bounds.minX()||b.maxXExclusive()>(long)bounds.maxXInclusive()+1
                    ||b.minY()<bounds.minY()||b.maxYExclusive()>(long)bounds.maxYInclusive()+1
                    ||b.minZ()<bounds.minZ()||b.maxZExclusive()>(long)bounds.maxZInclusive()+1)
                throw new IllegalArgumentException("Raw box outside its original sampled grid");
        }
        public TerrainLodClipper.BoundsLong volume(){return new TerrainLodClipper.BoundsLong(bounds.minX(),bounds.minY(),bounds.minZ(),
                (long)bounds.maxXInclusive()+1,(long)bounds.maxYInclusive()+1,(long)bounds.maxZInclusive()+1);}
        public String fingerprint(){return TerrainLodDiskCache.identity("abyss-source-grid-v1",sourceIdentity,bounds.toString(),Integer.toString(step));}
    }
    public record Candidate(List<Grid> grids,long bytes) {
        public Candidate {grids=List.copyOf(grids);if(bytes<0)throw new IllegalArgumentException("Negative ledger weight");}
    }
    /** Same exact composition, separated so requested fine light precedes background work. */
    public record Composition(List<TerrainLodColumns.Box> background,List<TerrainLodColumns.Box> fine) {
        public Composition {background=List.copyOf(background);fine=List.copyOf(fine);}
        public List<TerrainLodColumns.Box> boxes(){var all=new ArrayList<TerrainLodColumns.Box>(background.size()+fine.size());
            all.addAll(background);all.addAll(fine);return List.copyOf(all);}
    }
    private final long maxBytes;
    private volatile List<Grid> displayed=List.of();
    public AbyssLodFineLedger(long maxBytes){if(maxBytes<1)throw new IllegalArgumentException("Positive ledger cap required");this.maxBytes=maxBytes;}
    public List<Grid> displayed(){return displayed;}
    public long maximumBytes(){return maxBytes;}
    public Candidate prepare(String sourceIdentity,List<Grid> additions,double x,double y,double z,
                                         List<TerrainLodRefinement.Band> bands) {
        var candidate=prepareForAdmission(sourceIdentity,additions,x,y,z,bands);
        if(candidate.bytes()>maxBytes)throw new TerrainLodColumns.BudgetExceededException("Fine ledger exceeds retained geometry cap");
        return candidate;
    }
    /** Private-scene selection may defer optional grids before enforcing the promotion cap. */
    public Candidate prepareForAdmission(String sourceIdentity,List<Grid> additions,double x,double y,double z,
                                         List<TerrainLodRefinement.Band> bands) {
        return prepareForAdmission(sourceIdentity,additions,x,y,z,bands,grid->true);
    }
    /** Restricts a new view without mutating the WORLD-acknowledged history. */
    public Candidate prepareForAdmission(String sourceIdentity,List<Grid> additions,double x,double y,double z,
                                         List<TerrainLodRefinement.Band> bands,Predicate<Grid> eligible) {
        return prepareForAdmission(sourceIdentity,additions,x,y,z,bands,eligible,grid->false);
    }
    /** Canonical initial fragments preserve their exact BOX partition beside same-step history. */
    public Candidate prepareForAdmission(String sourceIdentity,List<Grid> additions,double x,double y,double z,
                                         List<TerrainLodRefinement.Band> bands,Predicate<Grid> eligible,Predicate<Grid> exactOwnership) {
        Objects.requireNonNull(bands);
        Objects.requireNonNull(eligible);
        Objects.requireNonNull(exactOwnership);
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Finite camera required");
        var grids=new ArrayList<Grid>();var lookup=new HashMap<VolumeCell,List<Grid>>();
        for(var grid:displayed)if(eligible.test(grid)&&grid.sourceIdentity().equals(sourceIdentity)&&distance(grid.bounds(),x,y,z)<=keepRadius(grid.step(),bands)+16){grids.add(grid);index(lookup,grid);}
        for(var grid:additions){if(!grid.sourceIdentity().equals(sourceIdentity))throw new IllegalArgumentException("Foreign raw source grid");
            if(!eligible.test(grid))continue;
            boolean identical=false,covered=false;var b=grid.bounds();
            for(var previous:lookup.getOrDefault(new VolumeCell(Math.floorDiv(b.minX(),16),Math.floorDiv(b.minY(),64),Math.floorDiv(b.minZ(),16)),List.of())){
                if(previous.bounds().equals(b)&&previous.step()==grid.step()){identical=true;break;}
                if((previous.step()<grid.step()||previous.step()==grid.step()&&!exactOwnership.test(grid))&&contains(previous.bounds(),b))covered=true;}
            if(!identical&&!covered){grids.add(grid);index(lookup,grid);}
        }
        long bytes=0;
        for(var grid:grids){
            bytes=Math.addExact(bytes,Math.addExact(128,Math.multiplyExact(80L,grid.raw().boxes().size())));}
        return new Candidate(grids,bytes);
    }
    public void promote(Candidate candidate){if(candidate.bytes()>maxBytes)throw new IllegalArgumentException("Foreign over-budget ledger candidate");displayed=candidate.grids();}
    public void clear(){displayed=List.of();}
    public static List<TerrainLodColumns.Box> compose(List<TerrainLodColumns.Box> rawBackdrop,Candidate fine,
            List<TerrainLodClipper.BoundsLong> nativeCuts,int maxBoxes,int maxFragments) {
        return composeParts(rawBackdrop,fine,nativeCuts,maxBoxes,maxFragments).boxes();
    }
    public static Composition composeParts(List<TerrainLodColumns.Box> rawBackdrop,Candidate fine,
            List<TerrainLodClipper.BoundsLong> nativeCuts,int maxBoxes,int maxFragments) {
        if(maxFragments<maxBoxes)throw new IllegalArgumentException("Fragment cap must cover scene cap");
        var cuts=new HashMap<Cell,List<TerrainLodClipper.BoundsLong>>();var detail=new HashMap<Cell,List<TerrainLodColumns.Box>>();
        var ordered=new ArrayList<Grid>(fine.grids());
        // Earlier displayed grids win equal-step overlap. Finer grids always win coarser ones.
        Collections.reverse(ordered);ordered.sort(Comparator.comparingInt(Grid::step).reversed());long detailCount=0;
        for(var grid:ordered){var b=grid.bounds();var cell=new Cell(Math.floorDiv(b.minX(),16),Math.floorDiv(b.minZ(),16));
            cuts.computeIfAbsent(cell,k->new ArrayList<>()).add(grid.volume());
            var owned=new ArrayList<>(TerrainLodClipper.subtract(detail.getOrDefault(cell,List.of()),List.of(grid.volume()),maxFragments));
            owned.addAll(TerrainLodClipper.subtract(grid.raw().boxes(),nativeCuts,maxFragments));
            detailCount+=owned.size()-detail.getOrDefault(cell,List.of()).size();if(detailCount>maxFragments)throw new TerrainLodColumns.BudgetExceededException("Fine fragment cap exceeded");detail.put(cell,owned);}
        var background=TerrainLodClipper.subtract(rawBackdrop,nativeCuts,maxFragments);var result=new ArrayList<TerrainLodColumns.Box>();
        for(var box:background){var local=new ArrayList<TerrainLodClipper.BoundsLong>();
            for(long x=Math.floorDiv(box.minX(),16);x<=Math.floorDiv(box.maxXExclusive()-1,16);x++)for(long z=Math.floorDiv(box.minZ(),16);z<=Math.floorDiv(box.maxZExclusive()-1,16);z++){
                var found=cuts.get(new Cell(x,z));if(found!=null)local.addAll(found);}
            result.addAll(TerrainLodClipper.subtract(List.of(box),local,maxFragments));if(result.size()>maxFragments)throw new TerrainLodColumns.BudgetExceededException("Backdrop fragment cap exceeded");}
        var cells=new ArrayList<>(detail.keySet());cells.sort(Comparator.comparingLong(Cell::x).thenComparingLong(Cell::z));
        var fineBoxes=new ArrayList<TerrainLodColumns.Box>();
        for(var cell:cells){fineBoxes.addAll(detail.get(cell));if((long)result.size()+fineBoxes.size()>maxFragments)throw new TerrainLodColumns.BudgetExceededException("Scene fragment cap exceeded");}
        if((long)result.size()+fineBoxes.size()>maxBoxes)throw new TerrainLodColumns.BudgetExceededException("Scene cannot preserve fine within actual box cap");
        return new Composition(result,fineBoxes);
    }
    private record Cell(long x,long z) { }
    private record VolumeCell(long x,long y,long z) { }
    private static void index(Map<VolumeCell,List<Grid>> lookup,Grid grid){var b=grid.bounds();for(long x=Math.floorDiv(b.minX(),16);x<=Math.floorDiv(b.maxXInclusive(),16);x++)
        for(long y=Math.floorDiv(b.minY(),64);y<=Math.floorDiv(b.maxYInclusive(),64);y++)for(long z=Math.floorDiv(b.minZ(),16);z<=Math.floorDiv(b.maxZInclusive(),16);z++)
            lookup.computeIfAbsent(new VolumeCell(x,y,z),k->new ArrayList<>()).add(grid);}
    private static boolean contains(TerrainLodColumns.Bounds a,TerrainLodColumns.Bounds b){return a.minX()<=b.minX()&&a.maxXInclusive()>=b.maxXInclusive()&&a.minY()<=b.minY()&&a.maxYInclusive()>=b.maxYInclusive()&&a.minZ()<=b.minZ()&&a.maxZInclusive()>=b.maxZInclusive();}
    private static double keepRadius(int step,List<TerrainLodRefinement.Band> bands){double radius=0;for(var b:bands)if(b.horizontalStep()<=step)radius=Math.max(radius,b.radius());return radius;}
    private static double distance(TerrainLodColumns.Bounds b,double x,double y,double z){double dx=Math.max(0,Math.max(b.minX()-x,x-((long)b.maxXInclusive()+1))),dy=Math.max(0,Math.max(b.minY()-y,y-((long)b.maxYInclusive()+1))),dz=Math.max(0,Math.max(b.minZ()-z,z-((long)b.maxZInclusive()+1)));return Math.hypot(Math.hypot(dx,dy),dz);}
}
