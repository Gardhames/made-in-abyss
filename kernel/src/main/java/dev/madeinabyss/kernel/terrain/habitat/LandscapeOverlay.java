package dev.madeinabyss.kernel.terrain.habitat;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import java.util.*;

/** Bounded ORIGINAL offline recipe. Validation is atomic and never assumes UNKNOWN is AIR. */
public final class LandscapeOverlay {
    public static final int MAX_PATCHES=32, MAX_WRITES=262144;
    public record Column(int x,int z,int baseY,int surfaceY,Integer waterY) {}
    public record Patch(String id,int minX,int minZ,int width,int depth,int referenceY,
                        List<Column> columns,List<HabitatOverlay.Placement> placements) {
        public Patch { columns=List.copyOf(columns);placements=List.copyOf(placements); }
    }
    private record Pos(int x,int y,int z) {}
    private record Xz(int x,int z) {}
    private static final int[][] DIRECTIONS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private final Map<Pos,HabitatBlock> writes;
    private final List<HabitatOverlay.Placement> placements;
    private LandscapeOverlay(Map<Pos,HabitatOverlay.Placement> staged) {
        var values=new HashMap<Pos,HabitatBlock>();staged.forEach((p,v)->values.put(p,v.block()));
        // Keep HashMap collision treeification for large dense patches; never expose its mutable owner.
        writes=Collections.unmodifiableMap(values);
        var ordered=new ArrayList<>(staged.values());
        ordered.sort(Comparator.comparingInt(HabitatOverlay.Placement::x).thenComparingInt(HabitatOverlay.Placement::y)
                .thenComparingInt(HabitatOverlay.Placement::z));
        placements=List.copyOf(ordered);
    }
    public Optional<HabitatBlock> sample(int x,int y,int z){return Optional.ofNullable(writes.get(new Pos(x,y,z)));}
    public List<HabitatOverlay.Placement> placements(){return placements;}
    public static LandscapeOverlay plan(TrailNetwork.SampleSource base,List<Patch> patches,HabitatOverlay.ProtectedCells protectedCells) {
        Objects.requireNonNull(base);Objects.requireNonNull(protectedCells);patches=List.copyOf(patches);
        if(patches.size()>MAX_PATCHES)throw invalid("patch budget");
        var ids=new HashSet<String>();var ordered=new ArrayList<>(patches);
        for(var p:ordered){
            if(p.id()==null||p.id().isBlank()||!ids.add(p.id())||p.width()<16||p.width()>80||p.depth()<16||p.depth()>80)
                throw invalid("unique patch ID and 16..80 metre dimensions required");
            checked((long)p.minX()+p.width()-1);checked((long)p.minZ()+p.depth()-1);
            checked((long)p.referenceY()-32);checked((long)p.referenceY()+40);
            if(p.columns().size()>(long)p.width()*p.depth()||p.placements().size()>MAX_WRITES)throw invalid("input budget");
        }
        ordered.sort(Comparator.comparing(Patch::id));
        var builder=new Builder(base,protectedCells);
        for(var p:ordered)builder.columns(p);
        for(var p:ordered)builder.decorations(p);
        builder.validateSupport();builder.validateWater();
        return new LandscapeOverlay(builder.staged);
    }
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException("Invalid landscape recipe: "+reason);}
    private static int checked(long n){if(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw invalid("signed XYZ overflow");return (int)n;}
    private static boolean solid(HabitatBlock b){return b!=null&&b!=HabitatBlock.AIR&&b!=HabitatBlock.WATER&&b!=HabitatBlock.FLOWER&&b!=HabitatBlock.TALL_GRASS;}
    private static boolean fertile(HabitatBlock b){return b==HabitatBlock.GRASS||b==HabitatBlock.SOIL;}
    private static Pos neighbour(Pos p,int[] d){return new Pos(checked((long)p.x()+d[0]),checked((long)p.y()+d[1]),checked((long)p.z()+d[2]));}
    private static final class Builder {
        final TrailNetwork.SampleSource base;final HabitatOverlay.ProtectedCells protectedCells;
        final Map<Pos,HabitatOverlay.Placement> staged=new HashMap<>();
        final Map<String,Map<Xz,Column>> columns=new HashMap<>();
        final Map<Pos,HabitatOverlay.Placement> decorations=new HashMap<>();
        Builder(TrailNetwork.SampleSource base,HabitatOverlay.ProtectedCells protectedCells){this.base=base;this.protectedCells=protectedCells;}
        HabitatBlock original(Pos p){
            var sample=base.sample(p.x(),p.y(),p.z());
            if(!(sample instanceof TerrainSample.Known known)||known.material()==null)throw invalid("UNKNOWN source at "+p);
            return switch(known.material()){case AIR->HabitatBlock.AIR;case ROCK->HabitatBlock.ROCK;case SOIL->HabitatBlock.SOIL;case GRASS->HabitatBlock.GRASS;case WATER->HabitatBlock.WATER;};
        }
        HabitatBlock composed(Pos p){var v=staged.get(p);return v==null?original(p):v.block();}
        void unprotected(Pos p){if(protectedCells.contains(p.x(),p.y(),p.z()))throw invalid("protected footprint at "+p);}
        void put(HabitatOverlay.Placement p){
            var pos=new Pos(p.x(),p.y(),p.z());unprotected(pos);
            if(staged.putIfAbsent(pos,p)!=null)throw invalid("overlapping XYZ writes at "+pos);
            if(staged.size()>MAX_WRITES)throw invalid("write budget");
        }
        void inside(Patch p,int x,int z){
            if((long)x-p.minX()<0||(long)x-p.minX()>=p.width()||(long)z-p.minZ()<0||(long)z-p.minZ()>=p.depth())throw invalid("placement outside patch");
        }
        void columns(Patch p){
            var byXz=new HashMap<Xz,Column>();columns.put(p.id(),byXz);
            for(var c:p.columns()){
                inside(p,c.x(),c.z());
                if(byXz.putIfAbsent(new Xz(c.x(),c.z()),c)!=null)throw invalid("duplicate column");
                if(Math.abs((long)c.baseY()-p.referenceY())>24||Math.abs((long)c.surfaceY()-p.referenceY())>24
                        ||Math.abs((long)c.surfaceY()-c.baseY())>4)throw invalid("column height bounds");
                if(c.waterY()!=null&&(c.waterY()<=c.surfaceY()||(long)c.waterY()>c.baseY()+4L||Math.abs((long)c.waterY()-p.referenceY())>24))throw invalid("water height bounds");
                // Verify eight solid cells including the actual old floor and sixteen air cells above.
                for(int d=-7;d<=16;d++){
                    var pos=new Pos(c.x(),checked((long)c.baseY()+d),c.z());unprotected(pos);var b=original(pos);
                    if(d<=0?!solid(b):b!=HabitatBlock.AIR)throw invalid("unsupported old floor or blocked headroom");
                    if(d==0&&!fertile(b))throw invalid("old floor must be soil or grass");
                }
                int bottom=checked(Math.max((long)c.baseY()-7,(long)c.surfaceY()-4));
                int top=Math.max(c.baseY(),Math.max(c.surfaceY(),c.waterY()==null?c.surfaceY():c.waterY()));
                for(long y=bottom;y<=top;y++){
                    HabitatBlock b=y==c.surfaceY()?HabitatBlock.GRASS:y<c.surfaceY()?(y>=c.surfaceY()-3L?HabitatBlock.SOIL:HabitatBlock.ROCK)
                            :c.waterY()!=null&&y<=c.waterY()?HabitatBlock.WATER:HabitatBlock.AIR;
                    var pos=new Pos(c.x(),checked(y),c.z());original(pos);
                    put(new HabitatOverlay.Placement(c.x(),(int)y,c.z(),b,p.id()+"/column"));
                }
            }
        }
        void decorations(Patch p){
            for(var d:p.placements()){
                inside(p,d.x(),d.z());checked((long)d.y()-1);checked((long)d.y()+1);
                checked((long)d.x()-1);checked((long)d.x()+1);checked((long)d.z()-1);checked((long)d.z()+1);
                var column=columns.get(p.id()).get(new Xz(d.x(),d.z()));
                if(column==null||d.y()<=column.surfaceY()||Math.abs((long)d.y()-p.referenceY())>24)throw invalid("decoration requires authored surface below it");
                if(d.block()==null||d.block()==HabitatBlock.AIR||d.block()==HabitatBlock.WATER||d.featureId()==null||d.featureId().isBlank())throw invalid("decoration type or feature ID");
                var pos=new Pos(d.x(),d.y(),d.z());
                if(composed(pos)!=HabitatBlock.AIR)throw invalid("decoration requires prior AIR");
                // A cut explicitly writes AIR; the later decoration pass may consume that AIR.
                // All other duplicates remain invalid, including duplicate input decorations.
                var old=staged.get(pos);
                if(old!=null&&old.block()==HabitatBlock.AIR){unprotected(pos);staged.put(pos,d);}
                else put(d);
                decorations.put(pos,d);
            }
        }
        void validateSupport(){
            var remaining=new HashSet<Pos>();var queue=new ArrayDeque<Pos>();
            for(var entry:decorations.entrySet()){
                var p=entry.getKey();var b=entry.getValue().block();var below=neighbour(p,new int[]{0,-1,0});
                if(b==HabitatBlock.FLOWER||b==HabitatBlock.TALL_GRASS){if(!fertile(composed(below)))throw invalid("plant lacks fertile direct support");}
                else if(solid(b)){
                    remaining.add(p);
                    if(!decorations.containsKey(below)&&solid(composed(below))){queue.add(p);}
                }
            }
            remaining.removeAll(queue);
            while(!queue.isEmpty()){
                var current=queue.remove();
                for(var d:DIRECTIONS){var next=neighbour(current,d);if(remaining.remove(next))queue.add(next);}
            }
            if(!remaining.isEmpty())throw invalid("floating decoration component");
        }
        void validateWater(){
            for(var entry:staged.entrySet())if(entry.getValue().block()==HabitatBlock.WATER){
                var p=entry.getKey();var below=composed(neighbour(p,new int[]{0,-1,0}));
                if(!solid(below)&&below!=HabitatBlock.WATER)throw invalid("water lacks solid bed");
                for(var d:new int[][]{{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}}){var b=composed(neighbour(p,d));if(!solid(b)&&b!=HabitatBlock.WATER)throw invalid("water spills laterally");}
            }
        }
    }
}
