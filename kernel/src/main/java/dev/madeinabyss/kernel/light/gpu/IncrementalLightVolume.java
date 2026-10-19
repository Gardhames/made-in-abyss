package dev.madeinabyss.kernel.light.gpu;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.volume.VoxelBrick;
import java.util.*;

/** Incremental edit API. Implementation follows the edit-local RED contract tests. */
public final class IncrementalLightVolume {
    public record Authority(String fingerprint,String generationCode,String physicalRules,int schema){
        public Authority{if(Objects.requireNonNull(fingerprint).isBlank()||Objects.requireNonNull(generationCode).isBlank()||Objects.requireNonNull(physicalRules).isBlank()||schema<1)throw new IllegalArgumentException("Complete immutable global authority required");}
    }
    public record Cell(int x,int y,int z){}
    public record Parent(int level,CubeKey key){}
    @FunctionalInterface public interface Baseline {int opacity(int x,int y,int z);}
    public interface Snapshot {long revision();long brickRevision(CubeKey key);int opacity(int x,int y,int z);VoxelBrick brick(CubeKey key);}
    public interface Ticket {Set<CubeKey> bricks();Set<CubeKey> neighborhood();Set<Parent> parents();List<Cell> opacityChanges();}
    private final Authority authority;private final Baseline baseline;private final Object owner=new Object();
    private volatile State current;
    private final class State implements Snapshot {
        private final long revision;private final Map<CubeKey,VoxelBrick> bricks;private final Map<CubeKey,Long> versions;
        private State(long revision,Map<CubeKey,VoxelBrick> bricks,Map<CubeKey,Long> versions){this.revision=revision;this.bricks=Map.copyOf(bricks);this.versions=Map.copyOf(versions);}
        public long revision(){return revision;}
        public long brickRevision(CubeKey key){return versions.getOrDefault(key,0L);}
        public int opacity(int x,int y,int z){var brick=bricks.get(new CubeKey(x>>4,y>>4,z>>4));return brick==null?baseline.opacity(x,y,z):brick.opacityAt(((y&15)<<8)|((z&15)<<4)|(x&15));}
        public VoxelBrick brick(CubeKey key){var brick=bricks.get(key);if(brick!=null)return brick;
            int[] values=new int[4096];int x=Math.multiplyExact(key.x(),16),y=Math.multiplyExact(key.y(),16),z=Math.multiplyExact(key.z(),16);
            for(int i=0;i<4096;i++)values[i]=baseline.opacity(Math.addExact(x,i&15),Math.addExact(y,i>>8),Math.addExact(z,(i>>4)&15));return VoxelBrick.opacityOnly(values);}
    }
    private record Prepared(Object owner,State expected,Map<CubeKey,VoxelBrick> replacements,Set<CubeKey> bricks,
                            Set<CubeKey> neighborhood,Set<Parent> parents,List<Cell> opacityChanges) implements Ticket{}
    public IncrementalLightVolume(Authority authority,Baseline baseline){this.authority=Objects.requireNonNull(authority);this.baseline=Objects.requireNonNull(baseline);current=new State(0,Map.of(),Map.of());}
    public Snapshot snapshot(){return current;}
    public Ticket prepare(Authority authority,Map<CubeKey,VoxelBrick> replacements){
        if(!this.authority.equals(authority))throw new IllegalArgumentException("Foreign optical rules");
        var copy=Map.copyOf(replacements);var before=current;var halo=new HashSet<CubeKey>();var parents=new HashSet<Parent>();var changed=new ArrayList<Cell>();
        for(var e:copy.entrySet()){
            var key=e.getKey();var brick=e.getValue();if(!brick.allKnown())throw new IllegalArgumentException("UNKNOWN optical edit cannot be committed");
            int x=Math.multiplyExact(key.x(),16),y=Math.multiplyExact(key.y(),16),z=Math.multiplyExact(key.z(),16);
            Math.addExact(x,15);Math.addExact(y,15);Math.addExact(z,15);
            for(int i=0;i<4096;i++){int xx=x+(i&15),yy=y+(i>>8),zz=z+((i>>4)&15),old=before.opacity(xx,yy,zz);if(old<0||old>15)throw new IllegalArgumentException("Unresolved old optical frontier");if(old!=brick.opacityAt(i))changed.add(new Cell(xx,yy,zz));}
            for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)halo.add(new CubeKey(Math.addExact(key.x(),dx),Math.addExact(key.y(),dy),Math.addExact(key.z(),dz)));
            for(int level=1;level<=4;level++)parents.add(new Parent(level,new CubeKey(Math.floorDiv(key.x(),1<<level),Math.floorDiv(key.y(),1<<level),Math.floorDiv(key.z(),1<<level))));
        }
        return new Prepared(owner,before,copy,Set.copyOf(copy.keySet()),Set.copyOf(halo),Set.copyOf(parents),List.copyOf(changed));
    }
    public synchronized void commit(Ticket ticket){
        if(!(ticket instanceof Prepared p)||p.owner()!=owner||p.expected()!=current)throw new IllegalStateException("Stale or foreign optical edit ticket");
        if(p.bricks().isEmpty())return;
        var bricks=new HashMap<>(current.bricks);var versions=new HashMap<>(current.versions);bricks.putAll(p.replacements());
        for(var key:p.bricks())versions.put(key,Math.incrementExact(current.brickRevision(key)));
        current=new State(Math.incrementExact(current.revision()),bricks,versions);
    }
    public Authority authority(){return authority;}
}
