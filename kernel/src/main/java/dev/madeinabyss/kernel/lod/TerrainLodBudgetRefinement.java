package dev.madeinabyss.kernel.lod;

import java.util.*;

/** Exact, bounded near-first replacement. Rejected cells keep their original far terrain. */
public final class TerrainLodBudgetRefinement {
    private TerrainLodBudgetRefinement() {}
    public record Tile(TerrainLodClipper.BoundsLong bounds,List<TerrainLodColumns.Box> boxes,int horizontalStep,List<TerrainLodClipper.BoundsLong> exclusions) {
        /** Legacy immutable geometry without a declared sampling grid: no resampling fallback. */
        public Tile(TerrainLodClipper.BoundsLong bounds,List<TerrainLodColumns.Box> boxes){this(bounds,boxes,64,List.of());}
        public Tile { Objects.requireNonNull(bounds);boxes=List.copyOf(boxes);
            exclusions=List.copyOf(exclusions);
            if(horizontalStep<1||horizontalStep>64||(horizontalStep&(horizontalStep-1))!=0)
                throw new IllegalArgumentException("Source step must be a power of two in1..64");
            if(Math.floorDiv(bounds.minX(),16)!=Math.floorDiv(bounds.maxXExclusive()-1,16)
                    ||Math.floorDiv(bounds.minZ(),16)!=Math.floorDiv(bounds.maxZExclusive()-1,16))
                throw new IllegalArgumentException("Detail tile must occupy one 16m XZ cell");
            for(var b:boxes)if(b.minX()<bounds.minX()||b.maxXExclusive()>bounds.maxXExclusive()
                ||b.minY()<bounds.minY()||b.maxYExclusive()>bounds.maxYExclusive()
                ||b.minZ()<bounds.minZ()||b.maxZExclusive()>bounds.maxZExclusive())
                throw new IllegalArgumentException("Detail outside replacement tile");
        }
    }
    public record Result(List<TerrainLodColumns.Box> boxes,int acceptedCells,int rejectedCells,int acceptedOneMetre,int acceptedTwoMetre,int acceptedFourMetre,List<Tile> acceptedReplacements,List<Tile> rawAcceptedReplacements) {
        public Result(List<TerrainLodColumns.Box> boxes,int acceptedCells,int rejectedCells){this(boxes,acceptedCells,rejectedCells,0,0,0,List.of());}
        public Result(List<TerrainLodColumns.Box> boxes,int acceptedCells,int rejectedCells,int acceptedOneMetre,int acceptedTwoMetre,int acceptedFourMetre){this(boxes,acceptedCells,rejectedCells,acceptedOneMetre,acceptedTwoMetre,acceptedFourMetre,List.of());}
        public Result(List<TerrainLodColumns.Box> boxes,int acceptedCells,int rejectedCells,int acceptedOneMetre,int acceptedTwoMetre,int acceptedFourMetre,List<Tile> acceptedReplacements){this(boxes,acceptedCells,rejectedCells,acceptedOneMetre,acceptedTwoMetre,acceptedFourMetre,acceptedReplacements,List.of());}
        public Result { boxes=List.copyOf(boxes);acceptedReplacements=List.copyOf(acceptedReplacements);rawAcceptedReplacements=List.copyOf(rawAcceptedReplacements); }
    }
    private record Key(long x,long z) {}
    private static final class Cell {
        final List<TerrainLodColumns.Box> base=new ArrayList<>();
        List<TerrainLodColumns.Box> selected;
    }
    private record Candidate(Key key,Tile tile) {}
    public static Result select(List<TerrainLodColumns.Box> base,List<Tile> detail,double cameraX,double cameraY,double cameraZ,int cap) {
        if(cap<1||!Double.isFinite(cameraX)||!Double.isFinite(cameraY)||!Double.isFinite(cameraZ))throw new IllegalArgumentException("Invalid budget/camera");
        int fragmentCap=(int)Math.min(Integer.MAX_VALUE,4L*cap);
        var original=TerrainLodVerticalMerge.merge(base,cap);
        var cells=new LinkedHashMap<Key,Cell>();long fragments=0;
        for(var b:original)for(long x=Math.floorDiv(b.minX(),16);x<=Math.floorDiv(b.maxXExclusive()-1,16);x++)
            for(long z=Math.floorDiv(b.minZ(),16);z<=Math.floorDiv(b.maxZExclusive()-1,16);z++) {
                if(++fragments>4L*cap)throw new TerrainLodColumns.BudgetExceededException("Refinement partition budget exceeded");
                cells.computeIfAbsent(new Key(x,z),k->new Cell()).base.add(new TerrainLodColumns.Box(
                    Math.max(b.minX(),x*16),b.minY(),Math.max(b.minZ(),z*16),Math.min(b.maxXExclusive(),x*16+16),b.maxYExclusive(),Math.min(b.maxZExclusive(),z*16+16),b.material()));
            }
        var candidates=new ArrayList<Candidate>();
        for(var tile:detail) {
            if(cells.size()>=4L*cap)throw new TerrainLodColumns.BudgetExceededException("Refinement cell budget exceeded");
            var key=new Key(Math.floorDiv(tile.bounds.minX(),16),Math.floorDiv(tile.bounds.minZ(),16));
            cells.computeIfAbsent(key,k->new Cell());candidates.add(new Candidate(key,tile));
        }
        int count=0;
        for(var cell:cells.values()){cell.selected=TerrainLodVerticalMerge.merge(cell.base,cap);count=Math.addExact(count,cell.selected.size());}
        if(count>cap)return new Result(original,0,candidates.size());
        candidates.sort(Comparator.<Candidate>comparingDouble(e->distance(e.tile.bounds,cameraX,cameraY,cameraZ))
            .thenComparingLong(e->e.key.x).thenComparingLong(e->e.key.z)
            .thenComparingLong(e->e.tile.bounds.minY()).thenComparingLong(e->e.tile.bounds.maxYExclusive())
            .thenComparingInt(e->e.tile.horizontalStep));
        int accepted=0,rejected=0,acceptedOne=0,acceptedTwo=0,acceptedFour=0;
        var replacements=new ArrayList<Tile>();
        var rawReplacements=new ArrayList<Tile>();
        for(var candidate:candidates) {
            var cell=cells.get(candidate.key);var tile=candidate.tile;boolean admitted=false;
            for(int step=tile.horizontalStep;;step=step==1?2:4) {
                try {
                    var geometry=step==tile.horizontalStep?tile.boxes:coarsen(tile,step,cap);
                    var visible=TerrainLodClipper.subtract(geometry,tile.exclusions,fragmentCap);
                    var replacement=new ArrayList<>(TerrainLodClipper.subtract(cell.selected,List.of(tile.bounds),fragmentCap));
                    if((long)replacement.size()+visible.size()>4L*cap)throw new TerrainLodColumns.BudgetExceededException("Candidate fragment budget exceeded");
                    replacement.addAll(visible);
                    var compact=TerrainLodVerticalMerge.merge(replacement,cap);
                    long next=(long)count-cell.selected.size()+compact.size();
                    if(next<=cap){count=(int)next;cell.selected=compact;accepted++;if(step==1)acceptedOne++;else if(step==2)acceptedTwo++;else if(step==4)acceptedFour++;replacements.add(new Tile(tile.bounds,visible,step,List.of()));rawReplacements.add(new Tile(tile.bounds,geometry,step,List.of()));admitted=true;break;}
                }catch(TerrainLodColumns.BudgetExceededException overflow){/* Try a coarser sampled alternative; original remains displayed. */}
                if(step>=4)break;
            }
            if(!admitted)rejected++;
        }
        var out=new ArrayList<TerrainLodColumns.Box>(count);
        for(var cell:cells.values())out.addAll(cell.selected);
        return new Result(TerrainLodVerticalMerge.merge(out,cap),accepted,rejected,acceptedOne,acceptedTwo,acceptedFour,replacements,rawReplacements);
    }
    private static double distance(TerrainLodClipper.BoundsLong b,double x,double y,double z) {
        double dx=Math.max(0,Math.max(b.minX()-x,x-b.maxXExclusive()));
        double dy=Math.max(0,Math.max(b.minY()-y,y-b.maxYExclusive()));double dz=Math.max(0,Math.max(b.minZ()-z,z-b.maxZExclusive()));
        return dx*dx+dy*dy+dz*dz;
    }
    /** Resample the complete raw tile, including sampled AIR, before ownership exclusions. */
    private static List<TerrainLodColumns.Box> coarsen(Tile tile,int step,int cap) {
        var b=tile.bounds;int sx=Math.toIntExact(b.maxXExclusive()-b.minX()),sy=Math.toIntExact(b.maxYExclusive()-b.minY()),sz=Math.toIntExact(b.maxZExclusive()-b.minZ());
        if(sx>16||sz>16||sy>64)throw new IllegalArgumentException("Fallback needs a bounded16x64x16 sampled tile");
        var density=new dev.madeinabyss.kernel.terrain.TerrainMaterial[sx*sy*sz];Arrays.fill(density,dev.madeinabyss.kernel.terrain.TerrainMaterial.AIR);
        for(var box:tile.boxes)for(long y=box.minY();y<box.maxYExclusive();y++)for(long z=box.minZ();z<box.maxZExclusive();z++)for(long x=box.minX();x<box.maxXExclusive();x++) {
            int index=Math.toIntExact(x-b.minX()+sx*(z-b.minZ()+sz*(y-b.minY())));
            if(density[index]!=dev.madeinabyss.kernel.terrain.TerrainMaterial.AIR&&density[index]!=box.material())throw new IllegalArgumentException("Overlapping contradictory sampled geometry");
            density[index]=box.material();
        }
        var bounds=new TerrainLodColumns.Bounds(Math.toIntExact(b.minX()),Math.toIntExact(b.minY()),Math.toIntExact(b.minZ()),
                Math.toIntExact(b.maxXExclusive()-1),Math.toIntExact(b.maxYExclusive()-1),Math.toIntExact(b.maxZExclusive()-1));
        return TerrainLodColumns.build((x,y,z)->new dev.madeinabyss.kernel.terrain.TerrainSample.Known(density[Math.toIntExact(x-b.minX()+sx*(z-b.minZ()+sz*(y-b.minY())))]),
                bounds,new TerrainLodColumns.Parameters(step,(long)sx*sy*sz,(int)Math.min(Integer.MAX_VALUE,4L*cap))).boxes();
    }
}
