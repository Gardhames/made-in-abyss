package dev.madeinabyss.kernel.volume;

import java.util.List;

/** Append-only state IDs over the admitted base palette. Global rules are independent of edits. */
public final class VoxelPaletteCatalog {
    private final String rules;
    private final java.util.ArrayList<VoxelPalette.Entry> values=new java.util.ArrayList<>();
    private final java.util.Map<String,Integer> names=new java.util.HashMap<>();
    public VoxelPaletteCatalog(VoxelPalette baseline,String globalRules){
        rules=java.util.Objects.requireNonNull(globalRules);if(rules.isBlank())throw new IllegalArgumentException("Physical rules required");
        for(var entry:java.util.Objects.requireNonNull(baseline).entries())intern(entry);
    }
    public synchronized int intern(VoxelPalette.Entry entry){
        java.util.Objects.requireNonNull(entry);var old=names.get(entry.state());
        if(old!=null){if(!values.get(old).equals(entry))throw new IllegalStateException("Existing state physical semantics changed: "+entry.state());return old;}
        if(values.size()==65536)throw new IllegalArgumentException("Stable voxel palette capacity exhausted");
        int index=values.size();values.add(entry);names.put(entry.state(),index);return index;
    }
    public synchronized int index(String name){var index=names.get(name);if(index==null)throw new IllegalArgumentException("Uncaptured state "+name);return index;}
    public synchronized List<VoxelPalette.Entry> entries(){return List.copyOf(values);}
    public String globalRules(){return rules;}
}
