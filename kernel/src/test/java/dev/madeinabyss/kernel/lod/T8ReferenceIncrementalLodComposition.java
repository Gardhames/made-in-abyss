package dev.madeinabyss.kernel.lod;

import java.util.*;

/** Persistent signed-cell composition; original FAR owners are retained independently. */
public final class T8ReferenceIncrementalLodComposition {
    public record Delta(List<TerrainLodColumns.Box> removed,List<TerrainLodColumns.Box> added,int totalBoxes,int backgroundBoxes,
            int recomputedFineCells,int recomputedFarRoots,int rootIndexBuilds,long retainedIndexBytes,
            List<TerrainLodColumns.Box> fineRemoved,List<TerrainLodColumns.Box> fineAdded,List<TerrainLodClipper.BoundsLong> fineFootprints) {
        public Delta {removed=List.copyOf(removed);added=List.copyOf(added);fineRemoved=List.copyOf(fineRemoved);fineAdded=List.copyOf(fineAdded);fineFootprints=List.copyOf(fineFootprints);}
        public Delta(List<TerrainLodColumns.Box> removed,List<TerrainLodColumns.Box> added,int totalBoxes,int backgroundBoxes,
                int recomputedFineCells,int recomputedFarRoots,int rootIndexBuilds,long retainedIndexBytes){
            this(removed,added,totalBoxes,backgroundBoxes,recomputedFineCells,recomputedFarRoots,rootIndexBuilds,retainedIndexBytes,List.of(),List.of(),List.of());
        }
    }
    private record Cell(long x,long z){}
    private record FineCell(List<AbyssLodFineLedger.Grid> grids,List<TerrainLodClipper.BoundsLong> cuts,
                            List<TerrainLodColumns.Box> output,List<Integer> prefixes){}
    private record Root(TerrainLodColumns.Box original,List<TerrainLodColumns.Box> nativeOnly,List<TerrainLodColumns.Box> output){}
    private record Index(Map<Cell,List<Integer>> cells,long bytes){}
    /** Opaque one-update transaction marker; no scene payload is copied. */
    public static final class Checkpoint {
        private final T8ReferenceIncrementalLodComposition owner;private final long version;
        private Checkpoint(T8ReferenceIncrementalLodComposition owner,long version){this.owner=owner;this.version=version;}
    }
    private record Undo(long version,List<TerrainLodColumns.Box> backdrop,List<TerrainLodClipper.BoundsLong> cuts,
            List<Root> roots,Index index,Map<Cell,FineCell> cells,Map<Integer,Root> changedRoots,
            int total,int background,int nativeCount,int detail,boolean initialized){}
    private final int maxBoxes,maxFragments;
    private final long maxIndexBytes;
    private List<TerrainLodColumns.Box> backdrop=List.of();
    private List<TerrainLodClipper.BoundsLong> nativeCuts=List.of();
    private final Map<Cell,FineCell> fineCells=new HashMap<>();
    private List<Root> roots=List.of();
    private Index index=new Index(Map.of(),0);
    private int totalBoxes,backgroundBoxes,nativeBoxes,detailBoxes;
    private boolean initialized;
    private long version;
    private Undo undo;
    public T8ReferenceIncrementalLodComposition(int maxBoxes,int maxFragments,long maxIndexBytes){
        if(maxBoxes<1||maxFragments<maxBoxes||maxIndexBytes<1)throw new IllegalArgumentException("Positive bounded incremental composition required");
        this.maxBoxes=maxBoxes;this.maxFragments=maxFragments;this.maxIndexBytes=maxIndexBytes;
    }
    public Checkpoint checkpoint(){return new Checkpoint(this,version);}
    public void restore(Checkpoint checkpoint){
        Objects.requireNonNull(checkpoint);
        if(checkpoint.owner!=this)throw new IllegalArgumentException("Foreign composition checkpoint");
        if(checkpoint.version==version)return; // The attempted update itself may have refused.
        var previous=undo;
        if(previous==null||previous.version()!=checkpoint.version||version!=checkpoint.version+1)
            throw new IllegalArgumentException("Stale composition checkpoint");
        for(var entry:previous.cells().entrySet()){if(entry.getValue()==null)fineCells.remove(entry.getKey());else fineCells.put(entry.getKey(),entry.getValue());}
        roots=previous.roots();for(var entry:previous.changedRoots().entrySet())roots.set(entry.getKey(),entry.getValue());
        backdrop=previous.backdrop();nativeCuts=previous.cuts();index=previous.index();totalBoxes=previous.total();backgroundBoxes=previous.background();
        nativeBoxes=previous.nativeCount();detailBoxes=previous.detail();initialized=previous.initialized();version=previous.version();undo=null;
    }
    public Delta update(List<TerrainLodColumns.Box> originalBackdrop,AbyssLodFineLedger.Candidate fine,List<TerrainLodClipper.BoundsLong> requestedCuts){
        Objects.requireNonNull(fine);var nextBackdrop=List.copyOf(originalBackdrop);var cuts=List.copyOf(requestedCuts);
        boolean rebuild=!initialized||!backdrop.equals(nextBackdrop),nativeChanged=!nativeCuts.equals(cuts);
        var nextIndex=rebuild?buildIndex(nextBackdrop):index;
        var ordered=new ArrayList<>(fine.grids());Collections.reverse(ordered);ordered.sort(Comparator.comparingInt(AbyssLodFineLedger.Grid::step).reversed());
        var groups=new HashMap<Cell,List<AbyssLodFineLedger.Grid>>();
        for(var grid:ordered){check();groups.computeIfAbsent(cell(grid),key->new ArrayList<>()).add(grid);}
        long bytes=nextIndex.bytes();for(var group:groups.values())bytes=addBytes(bytes,128L+96L*group.size());
        var changedCells=new HashSet<Cell>();changedCells.addAll(fineCells.keySet());changedCells.addAll(groups.keySet());
        changedCells.removeIf(key->{var old=fineCells.get(key);var next=groups.getOrDefault(key,List.of());
            return old!=null&&old.grids().equals(next)&&(!nativeChanged||!touches(old.grids(),nativeCuts,cuts));});
        var stagedCells=new HashMap<Cell,FineCell>();
        for(var key:changedCells)stagedCells.put(key,buildCell(groups.getOrDefault(key,List.of()),cuts));
        // Preserve the untouched operator's global intermediate fine-fragment cap,
        // including prefixes that may subsequently shrink under authoritative AIR.
        var prefixAt=new HashMap<Cell,Integer>();var prefixSize=new HashMap<Cell,Integer>();long prefixTotal=0;
        for(var grid:ordered){var key=cell(grid);var state=stagedCells.getOrDefault(key,fineCells.get(key));int at=prefixAt.getOrDefault(key,0);
            int size=state.prefixes().get(at);prefixTotal+=size-prefixSize.getOrDefault(key,0);prefixAt.put(key,at+1);prefixSize.put(key,size);
            if(prefixTotal>maxFragments)throw cap("Fine fragment cap exceeded");}
        var dirtyRoots=new HashSet<Integer>();
        if(rebuild)for(int id=0;id<nextBackdrop.size();id++)dirtyRoots.add(id);
        else{
            for(var key:changedCells)dirtyRoots.addAll(nextIndex.cells().getOrDefault(key,List.of()));
            if(nativeChanged){for(var cut:nativeCuts)addRoots(nextIndex,nextBackdrop,cut,dirtyRoots);for(var cut:cuts)addRoots(nextIndex,nextBackdrop,cut,dirtyRoots);}
        }
        var stagedRoots=new HashMap<Integer,Root>();
        int nextNative=rebuild?0:nativeBoxes,nextBackground=rebuild?0:backgroundBoxes,nextDetail=detailBoxes;
        for(var key:changedCells){var old=fineCells.get(key);nextDetail-=old==null?0:old.output().size();nextDetail+=stagedCells.get(key).output().size();}
        for(int id:dirtyRoots){check();var box=nextBackdrop.get(id);var old=rebuild?null:roots.get(id);
            var nativeOnly=old!=null&&!nativeChanged?old.nativeOnly():TerrainLodClipper.subtract(List.of(box),cuts,maxFragments);
            var output=new ArrayList<TerrainLodColumns.Box>();
            for(var fragment:nativeOnly){var local=new ArrayList<TerrainLodClipper.BoundsLong>();
                for(long x=Math.floorDiv(fragment.minX(),16);x<=Math.floorDiv(fragment.maxXExclusive()-1,16);x++)
                    for(long z=Math.floorDiv(fragment.minZ(),16);z<=Math.floorDiv(fragment.maxZExclusive()-1,16);z++){
                        var key=new Cell(x,z);var state=stagedCells.getOrDefault(key,fineCells.get(key));if(state!=null)local.addAll(state.cuts());}
                output.addAll(TerrainLodClipper.subtract(List.of(fragment),local,maxFragments));if(output.size()>maxFragments)throw cap("Backdrop fragment cap exceeded");}
            var next=new Root(box,nativeOnly,List.copyOf(output));stagedRoots.put(id,next);
            nextNative+=nativeOnly.size()-(old==null?0:old.nativeOnly().size());nextBackground+=output.size()-(old==null?0:old.output().size());
        }
        if(nextNative>maxFragments)throw cap("Native backdrop fragment cap exceeded");
        long nextTotal=(long)nextBackground+nextDetail;
        if(nextDetail>maxFragments||nextBackground>maxFragments||nextTotal>maxFragments||nextTotal>maxBoxes)throw cap("Scene cannot preserve fine within actual box cap");
        var differences=new LinkedHashMap<TerrainLodColumns.Box,Integer>();
        for(var key:changedCells){var old=fineCells.get(key);if(old!=null)count(differences,old.output(),-1);count(differences,stagedCells.get(key).output(),1);}
        if(rebuild){for(var old:roots)count(differences,old.output(),-1);for(var next:stagedRoots.values())count(differences,next.output(),1);}
        else for(int id:dirtyRoots){count(differences,roots.get(id).output(),-1);count(differences,stagedRoots.get(id).output(),1);}
        var removed=new ArrayList<TerrainLodColumns.Box>();var added=new ArrayList<TerrainLodColumns.Box>();
        for(var entry:differences.entrySet()){int n=entry.getValue();for(int i=0;i<Math.abs(n);i++)(n<0?removed:added).add(entry.getKey());}
        // Local metadata describes changed fine cells independently of simultaneous FAR
        // acquisition. It includes sampled AIR's old/new cut footprints even when both
        // fine BOX lists are empty; no unchanged cell or global scene is flattened here.
        var fineRemoved=new ArrayList<TerrainLodColumns.Box>();var fineAdded=new ArrayList<TerrainLodColumns.Box>();
        var fineFootprints=new LinkedHashSet<TerrainLodClipper.BoundsLong>();var metadataCells=new ArrayList<>(changedCells);
        metadataCells.sort(Comparator.comparingLong(Cell::x).thenComparingLong(Cell::z));
        for(var key:metadataCells){var old=fineCells.get(key);if(old!=null){fineRemoved.addAll(old.output());fineFootprints.addAll(old.cuts());}
            var next=stagedCells.get(key);fineAdded.addAll(next.output());fineFootprints.addAll(next.cuts());}
        var result=new Delta(removed,added,(int)nextTotal,nextBackground,changedCells.size(),dirtyRoots.size(),rebuild?1:0,bytes,
            fineRemoved,fineAdded,new ArrayList<>(fineFootprints));
        var oldCells=new HashMap<Cell,FineCell>();for(var key:changedCells)oldCells.put(key,fineCells.get(key));
        var oldRoots=new HashMap<Integer,Root>();if(!rebuild)for(int id:dirtyRoots)oldRoots.put(id,roots.get(id));
        var previous=new Undo(version,backdrop,nativeCuts,roots,index,oldCells,oldRoots,totalBoxes,backgroundBoxes,nativeBoxes,detailBoxes,initialized);
        long nextVersion=Math.addExact(version,1);
        // All clipping, cap validation and immutable Delta construction precede commit.
        for(var key:changedCells){var value=stagedCells.get(key);if(value.grids().isEmpty())fineCells.remove(key);else fineCells.put(key,value);}
        if(rebuild){var nextRoots=new ArrayList<Root>(nextBackdrop.size());for(int id=0;id<nextBackdrop.size();id++)nextRoots.add(stagedRoots.get(id));roots=nextRoots;}
        else for(var entry:stagedRoots.entrySet())roots.set(entry.getKey(),entry.getValue());
        backdrop=nextBackdrop;nativeCuts=cuts;index=nextIndex;totalBoxes=(int)nextTotal;backgroundBoxes=nextBackground;nativeBoxes=nextNative;detailBoxes=nextDetail;initialized=true;
        undo=previous;version=nextVersion;
        return result;
    }
    private FineCell buildCell(List<AbyssLodFineLedger.Grid> grids,List<TerrainLodClipper.BoundsLong> cuts){
        var volumes=new ArrayList<TerrainLodClipper.BoundsLong>();var prefixes=new ArrayList<Integer>();List<TerrainLodColumns.Box> output=List.of();
        for(var grid:grids){check();volumes.add(grid.volume());var next=new ArrayList<>(TerrainLodClipper.subtract(output,List.of(grid.volume()),maxFragments));
            next.addAll(TerrainLodClipper.subtract(grid.raw().boxes(),cuts,maxFragments));if(next.size()>maxFragments)throw cap("Fine fragment cap exceeded");
            output=List.copyOf(next);prefixes.add(next.size());}
        return new FineCell(List.copyOf(grids),List.copyOf(volumes),output,List.copyOf(prefixes));
    }
    private Index buildIndex(List<TerrainLodColumns.Box> boxes){
        var cells=new HashMap<Cell,List<Integer>>();long bytes=addBytes(0,96L*boxes.size());
        for(int id=0;id<boxes.size();id++){check();var b=boxes.get(id);
            long x0=Math.floorDiv(b.minX(),16),x1=Math.floorDiv(b.maxXExclusive()-1,16),z0=Math.floorDiv(b.minZ(),16),z1=Math.floorDiv(b.maxZExclusive()-1,16);
            long refs;try{refs=Math.multiplyExact(Math.addExact(Math.subtractExact(x1,x0),1),Math.addExact(Math.subtractExact(z1,z0),1));}
            catch(ArithmeticException huge){throw cap("Root incidence coordinate overflow");}
            if(refs>(maxIndexBytes-bytes)/16)throw cap("Root incidence exceeds bounded index");
            for(long x=x0;x<=x1;x++)for(long z=z0;z<=z1;z++){check();var key=new Cell(x,z);var list=cells.get(key);
                if(list==null){bytes=addBytes(bytes,128);list=new ArrayList<>();cells.put(key,list);}bytes=addBytes(bytes,16);list.add(id);}
        }
        return new Index(cells,bytes);
    }
    private long addBytes(long used,long extra){if(extra<0||extra>maxIndexBytes-used)throw cap("Composition retained index budget exceeded");return used+extra;}
    private static Cell cell(AbyssLodFineLedger.Grid grid){return new Cell(Math.floorDiv(grid.bounds().minX(),16),Math.floorDiv(grid.bounds().minZ(),16));}
    private static boolean touches(List<AbyssLodFineLedger.Grid> grids,List<TerrainLodClipper.BoundsLong> old,List<TerrainLodClipper.BoundsLong> next){
        for(var grid:grids){for(var cut:old)if(overlaps(grid.volume(),cut))return true;for(var cut:next)if(overlaps(grid.volume(),cut))return true;}return false;
    }
    private static boolean overlaps(TerrainLodClipper.BoundsLong a,TerrainLodClipper.BoundsLong b){return a.minX()<b.maxXExclusive()&&a.maxXExclusive()>b.minX()
        &&a.minY()<b.maxYExclusive()&&a.maxYExclusive()>b.minY()&&a.minZ()<b.maxZExclusive()&&a.maxZExclusive()>b.minZ();}
    private static void addRoots(Index index,List<TerrainLodColumns.Box> roots,TerrainLodClipper.BoundsLong cut,Set<Integer> out){
        long x0=Math.floorDiv(cut.minX(),16),x1=Math.floorDiv(cut.maxXExclusive()-1,16),z0=Math.floorDiv(cut.minZ(),16),z1=Math.floorDiv(cut.maxZExclusive()-1,16);
        // Scan the bounded index for enormous cuts; never enumerate an unbounded cut volume.
        for(var entry:index.cells().entrySet()){var key=entry.getKey();if(key.x()<x0||key.x()>x1||key.z()<z0||key.z()>z1)continue;
            for(int id:entry.getValue()){var b=roots.get(id);if(b.minY()<cut.maxYExclusive()&&b.maxYExclusive()>cut.minY())out.add(id);}}
    }
    private static void count(Map<TerrainLodColumns.Box,Integer> counts,List<TerrainLodColumns.Box> boxes,int sign){for(var b:boxes)counts.merge(b,sign,Integer::sum);}
    private static TerrainLodColumns.BudgetExceededException cap(String message){return new TerrainLodColumns.BudgetExceededException(message);}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Composition source lease canceled");}
}
