package dev.madeinabyss.kernel.lod;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;

/** Partial acquisition keeps coarse backdrop ownership separate from completed detail. */
public final class ProgressiveRefinementOverlay {
    private ProgressiveRefinementOverlay() {}
    private record Cell(long x,long z) {}
    public record Result(List<TerrainLodColumns.Box> background,List<TerrainLodColumns.Box> detail,
                         int acceptedCells,int rejectedCells,int acceptedOneMetre,int acceptedTwoMetre,int acceptedFourMetre,
                         boolean preservedBackdrop,List<TerrainLodBudgetRefinement.Tile> rawAcceptedReplacements) {
        public Result(List<TerrainLodColumns.Box> background,List<TerrainLodColumns.Box> detail,
                      int acceptedCells,int rejectedCells,int acceptedOneMetre,int acceptedTwoMetre,int acceptedFourMetre,
                      boolean preservedBackdrop) {
            this(background,detail,acceptedCells,rejectedCells,acceptedOneMetre,acceptedTwoMetre,acceptedFourMetre,preservedBackdrop,List.of());
        }
        public Result { background=List.copyOf(background);detail=List.copyOf(detail);rawAcceptedReplacements=List.copyOf(rawAcceptedReplacements); }
        public List<TerrainLodColumns.Box> boxes() {
            var result=new ArrayList<TerrainLodColumns.Box>(background.size()+detail.size());
            result.addAll(background);result.addAll(detail);return List.copyOf(result);
        }
    }
    /** Rebase displayed raw detail against current ownership; refusal keeps the displayed scene. */
    public static boolean preserves(List<TerrainLodBudgetRefinement.Tile> pins,List<TerrainLodBudgetRefinement.Tile> accepted,
            List<TerrainLodClipper.BoundsLong> nativeExclusions,int cap) {
        if(cap<1)throw new IllegalArgumentException("Positive certificate budget required");
        int fragmentCap=(int)Math.min(Integer.MAX_VALUE,4L*cap);
        var index=new LinkedHashMap<Cell,List<TerrainLodBudgetRefinement.Tile>>();
        for(var tile:accepted)index.computeIfAbsent(cell(tile.bounds()),ignored->new ArrayList<>()).add(tile);
        try {
            for(var pin:pins) {
                var cuts=new ArrayList<>(nativeExclusions);
                for(var tile:index.getOrDefault(cell(pin.bounds()),List.of()))
                    if(tile.horizontalStep()<=pin.horizontalStep()&&overlaps(pin.bounds(),tile.bounds()))cuts.add(tile.bounds());
                var p=pin.bounds();var volume=new TerrainLodColumns.Box(p.minX(),p.minY(),p.minZ(),p.maxXExclusive(),p.maxYExclusive(),p.maxZExclusive(),dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK);
                if(!TerrainLodClipper.subtract(List.of(volume),cuts,fragmentCap).isEmpty())return false;
            }
            return true;
        }catch(TerrainLodColumns.BudgetExceededException overflow){return false;}
    }
    public static Optional<Result> rebase(List<TerrainLodColumns.Box> rawBackdrop,
            List<TerrainLodBudgetRefinement.Tile> pinnedRaw,List<TerrainLodBudgetRefinement.Tile> detail,
            List<TerrainLodClipper.BoundsLong> currentExclusions,double cameraX,double cameraY,double cameraZ,int cap) {
        if(cap<1||!Double.isFinite(cameraX)||!Double.isFinite(cameraY)||!Double.isFinite(cameraZ))
            throw new IllegalArgumentException("Invalid budget/camera");
        int fragmentCap=(int)Math.min(Integer.MAX_VALUE,4L*cap);
        try {
            var pins=normalize(pinnedRaw,fragmentCap);
            var pinIndex=new LinkedHashMap<Cell,List<TerrainLodBudgetRefinement.Tile>>();
            for(var pin:pins)pinIndex.computeIfAbsent(cell(pin.bounds()),ignored->new ArrayList<>()).add(pin);
            var backdrop=TerrainLodClipper.subtract(rawBackdrop,currentExclusions,fragmentCap);
            var forced=compose(backdrop,visible(pins,currentExclusions,fragmentCap),pins,0,0,cap);
            var candidates=new ArrayList<TerrainLodBudgetRefinement.Tile>();
            for(var tile:detail)if(finerThanPins(tile,pinIndex))candidates.add(new TerrainLodBudgetRefinement.Tile(
                    tile.bounds(),tile.boxes(),tile.horizontalStep(),currentExclusions));
            var admitted=TerrainLodBudgetRefinement.select(forced.boxes(),candidates,cameraX,cameraY,cameraZ,cap);
            var raw=new ArrayList<>(pins);
            // A nominal1m candidate can be admitted at2m/4m. Recheck the actual
            // admitted grid before it is allowed to replace any displayed finer tile.
            for(var tile:admitted.rawAcceptedReplacements())if(finerThanPins(tile,pinIndex))raw.add(tile);
            var retained=normalize(raw,fragmentCap);
            return Optional.of(compose(backdrop,visible(retained,currentExclusions,fragmentCap),retained,
                    admitted.rejectedCells(),0,cap));
        }catch(TerrainLodColumns.BudgetExceededException overflow) {
            return Optional.empty();
        }
    }
    public static Result select(List<TerrainLodColumns.Box> backdrop,List<TerrainLodBudgetRefinement.Tile> detail,
                                double cameraX,double cameraY,double cameraZ,int cap) {
        var selected=TerrainLodBudgetRefinement.select(backdrop,detail,cameraX,cameraY,cameraZ,cap);
        try {
            return compose(backdrop,selected.acceptedReplacements(),selected.rawAcceptedReplacements(),
                    selected.rejectedCells(),selected.acceptedCells(),cap);
        }catch(TerrainLodColumns.BudgetExceededException overflow) {
            // Exact ordinary selection is already bounded. Giving its whole scene requested
            // lighting costs more, but never creates a hole or silently drops admitted detail.
            return new Result(List.of(),selected.boxes(),selected.acceptedCells(),selected.rejectedCells(),
                    selected.acceptedOneMetre(),selected.acceptedTwoMetre(),selected.acceptedFourMetre(),false,selected.rawAcceptedReplacements());
        }
    }
    private static Result compose(List<TerrainLodColumns.Box> backdrop,List<TerrainLodBudgetRefinement.Tile> replacements,
            List<TerrainLodBudgetRefinement.Tile> raw,int rejected,int accepted,int cap) {
        int fragmentCap=(int)Math.min(Integer.MAX_VALUE,4L*cap);
        var cuts=new LinkedHashMap<Cell,List<TerrainLodClipper.BoundsLong>>();
        var detailByCell=new LinkedHashMap<Cell,List<TerrainLodColumns.Box>>();
            for(var replacement:replacements) {
                var b=replacement.bounds();var cell=new Cell(Math.floorDiv(b.minX(),16),Math.floorDiv(b.minZ(),16));
                cuts.computeIfAbsent(cell,ignored->new ArrayList<>()).add(b);
                // Overlapping replacement volumes obey the selector's near-first admission order.
                // Adjacent tiles stay separate, retaining exact box/light keys through prefixes.
                var prior=detailByCell.getOrDefault(cell,List.of());
                var visible=new ArrayList<>(TerrainLodClipper.subtract(prior,List.of(b),fragmentCap));
                visible.addAll(replacement.boxes());
                if(visible.size()>fragmentCap)throw new TerrainLodColumns.BudgetExceededException("Overlay detail fragments exceeded budget");
                detailByCell.put(cell,visible);
            }
            var background=new ArrayList<TerrainLodColumns.Box>();long scannedCells=0;
            for(var box:backdrop) {
                var localCuts=new ArrayList<TerrainLodClipper.BoundsLong>();
                for(long x=Math.floorDiv(box.minX(),16);x<=Math.floorDiv(box.maxXExclusive()-1,16);x++)
                    for(long z=Math.floorDiv(box.minZ(),16);z<=Math.floorDiv(box.maxZExclusive()-1,16);z++) {
                        if(++scannedCells>4L*cap)throw new TerrainLodColumns.BudgetExceededException("Overlay partition scan exceeded budget");
                        var nearby=cuts.get(new Cell(x,z));if(nearby!=null)localCuts.addAll(nearby);
                    }
                background.addAll(TerrainLodClipper.subtract(List.of(box),localCuts,fragmentCap));
                if(background.size()>cap)throw new TerrainLodColumns.BudgetExceededException("Overlay backdrop exceeded budget");
            }
            var visibleDetail=new ArrayList<TerrainLodColumns.Box>();
            for(var cell:detailByCell.values()) {
                visibleDetail.addAll(cell);
                if((long)background.size()+visibleDetail.size()>cap)throw new TerrainLodColumns.BudgetExceededException("Overlay final scene exceeded budget");
            }
            int one=0,two=0,four=0;for(var tile:raw)if(tile.horizontalStep()==1)one++;else if(tile.horizontalStep()==2)two++;else if(tile.horizontalStep()==4)four++;
            return new Result(background,visibleDetail,accepted==0?raw.size():accepted,rejected,one,two,four,true,raw);
    }
    private static List<TerrainLodBudgetRefinement.Tile> visible(List<TerrainLodBudgetRefinement.Tile> raw,
            List<TerrainLodClipper.BoundsLong> exclusions,int fragmentCap) {
        var out=new ArrayList<TerrainLodBudgetRefinement.Tile>();long count=0;
        for(var tile:raw) {
            var boxes=TerrainLodClipper.subtract(tile.boxes(),exclusions,fragmentCap);
            if((count+=boxes.size())>fragmentCap)throw new TerrainLodColumns.BudgetExceededException("Pinned raw fragments exceeded budget");
            out.add(new TerrainLodBudgetRefinement.Tile(tile.bounds(),boxes,tile.horizontalStep(),List.of()));
        }
        return List.copyOf(out);
    }
    private static Cell cell(TerrainLodClipper.BoundsLong b){return new Cell(Math.floorDiv(b.minX(),16),Math.floorDiv(b.minZ(),16));}
    private static boolean overlaps(TerrainLodClipper.BoundsLong a,TerrainLodClipper.BoundsLong b) {
        return a.minX()<b.maxXExclusive()&&a.maxXExclusive()>b.minX()&&a.minY()<b.maxYExclusive()&&a.maxYExclusive()>b.minY()
                &&a.minZ()<b.maxZExclusive()&&a.maxZExclusive()>b.minZ();
    }
    private static boolean finerThanPins(TerrainLodBudgetRefinement.Tile tile,LinkedHashMap<Cell,List<TerrainLodBudgetRefinement.Tile>> index) {
        for(var pin:index.getOrDefault(cell(tile.bounds()),List.of()))
            if(overlaps(tile.bounds(),pin.bounds())&&tile.horizontalStep()>=pin.horizontalStep())return false;
        return true;
    }
    /** Last replacement owns its complete sampled volume, including AIR. */
    private static List<TerrainLodBudgetRefinement.Tile> normalize(List<TerrainLodBudgetRefinement.Tile> raw,int fragmentCap) {
        var cells=new LinkedHashMap<Cell,List<TerrainLodBudgetRefinement.Tile>>();long count=0;
        for(var tile:raw) {
            var key=cell(tile.bounds());var prior=cells.getOrDefault(key,List.of());var next=new ArrayList<TerrainLodBudgetRefinement.Tile>();
            for(var previous:prior) {
                if(!overlaps(previous.bounds(),tile.bounds())){next.add(previous);continue;}
                var p=previous.bounds();
                var volume=new TerrainLodColumns.Box(p.minX(),p.minY(),p.minZ(),p.maxXExclusive(),p.maxYExclusive(),p.maxZExclusive(),dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK);
                for(var part:TerrainLodClipper.subtract(List.of(volume),List.of(tile.bounds()),fragmentCap)) {
                    var b=new TerrainLodClipper.BoundsLong(part.minX(),part.minY(),part.minZ(),part.maxXExclusive(),part.maxYExclusive(),part.maxZExclusive());
                    var boxes=new ArrayList<TerrainLodColumns.Box>();
                    for(var box:previous.boxes())if(box.minX()<b.maxXExclusive()&&box.maxXExclusive()>b.minX()&&box.minY()<b.maxYExclusive()&&box.maxYExclusive()>b.minY()&&box.minZ()<b.maxZExclusive()&&box.maxZExclusive()>b.minZ())
                        boxes.add(new TerrainLodColumns.Box(Math.max(box.minX(),b.minX()),Math.max(box.minY(),b.minY()),Math.max(box.minZ(),b.minZ()),Math.min(box.maxXExclusive(),b.maxXExclusive()),Math.min(box.maxYExclusive(),b.maxYExclusive()),Math.min(box.maxZExclusive(),b.maxZExclusive()),box.material()));
                    next.add(new TerrainLodBudgetRefinement.Tile(b,boxes,previous.horizontalStep(),List.of()));
                }
            }
            next.add(new TerrainLodBudgetRefinement.Tile(tile.bounds(),tile.boxes(),tile.horizontalStep(),List.of()));
            count+=next.size()-prior.size();if(count>fragmentCap)throw new TerrainLodColumns.BudgetExceededException("Pinned raw volumes exceeded budget");
            cells.put(key,next);
        }
        var out=new ArrayList<TerrainLodBudgetRefinement.Tile>();for(var tiles:cells.values())out.addAll(tiles);return List.copyOf(out);
    }
}
