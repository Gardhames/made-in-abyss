package dev.madeinabyss.kernel.volume;

import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;

/** Stable names and captured semantics, independent of session registry numeric IDs. */
public final class VoxelPalette {
    public record Entry(String state,int opacity,int emission,boolean air,boolean fluid,boolean collisionEmpty,TerrainMaterial material) {
        public Entry {
            if(Objects.requireNonNull(state).isBlank()||state.length()>4096||opacity<0||opacity>15||emission<0||emission>15)
                throw new IllegalArgumentException("Invalid canonical voxel state");
        }
    }
    private final List<Entry> entries;
    private final Map<String,Integer> byName;
    private final String identity;
    public VoxelPalette(Collection<Entry> source) {
        var sorted=new ArrayList<>(source);sorted.sort(Comparator.comparing(Entry::state));
        if(sorted.isEmpty()||sorted.size()>65536)throw new IllegalArgumentException("Bounded nonempty palette required");
        var map=new HashMap<String,Integer>();var identities=new ArrayList<String>();identities.add("voxel-palette-v1");
        for(int i=0;i<sorted.size();i++){var entry=sorted.get(i);if(map.put(entry.state(),i)!=null)throw new IllegalArgumentException("Duplicate state name");identities.add(entry.toString());}
        entries=List.copyOf(sorted);byName=Map.copyOf(map);identity=TerrainLodDiskCache.identity(identities.toArray(String[]::new));
    }
    public int size(){return entries.size();}
    public Entry entry(int index){return entries.get(index);}
    public List<Entry> entries(){return entries;}
    public int index(String name){var value=byName.get(name);if(value==null)throw new IllegalArgumentException("Uncaptured state "+name);return value;}
    public String identity(){return identity;}
    public VoxelBrick pack(int[] states) {
        if(states.length!=4096)throw new IllegalArgumentException("Native16^3 states required");
        int[] opacity=new int[4096];for(int i=0;i<4096;i++)opacity[i]=states[i]<0?-1:entry(states[i]).opacity();
        return VoxelBrick.copyOf(states,opacity);
    }
}
