package dev.madeinabyss.kernel.lod;

import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.List;
import java.lang.ref.WeakReference;

/** Admission charge for a retained panorama and an acquiring immutable source view. */
public final class AbyssLodGridResidency {
    private AbyssLodGridResidency(){}
    /** Metadata belongs to each Grid; an immutable BOX payload shared by reference
     * is resident only once. Equal independently allocated lists remain separately charged. */
    public static long retainedBytes(AbyssLodFineLedger.Candidate acquiring,AbyssLodFineLedger.Candidate displayed){
        Objects.requireNonNull(acquiring);Objects.requireNonNull(displayed);
        var grids=new IdentityHashMap<AbyssLodFineLedger.Grid,Boolean>();
        var payloads=new IdentityHashMap<List<TerrainLodColumns.Box>,Boolean>();long bytes=0;
        for(var candidate:List.of(acquiring,displayed))for(var grid:candidate.grids()){
            if(grids.put(grid,Boolean.TRUE)!=null)continue;
            bytes=Math.addExact(bytes,128);var boxes=grid.raw().boxes();
            if(payloads.put(boxes,Boolean.TRUE)==null)bytes=Math.addExact(bytes,Math.multiplyExact(80L,boxes.size()));
        }
        return bytes;
    }
    /** Coordinator-only memo; weak keys never extend an old source view's ownership. */
    public static final class Cache {
        private WeakReference<AbyssLodFineLedger.Candidate> acquiring=new WeakReference<>(null),displayed=new WeakReference<>(null);
        private long bytes;
        public long retainedBytes(AbyssLodFineLedger.Candidate next,AbyssLodFineLedger.Candidate previous){
            Objects.requireNonNull(next);Objects.requireNonNull(previous);
            if(acquiring.get()==next&&displayed.get()==previous)return bytes;
            bytes=AbyssLodGridResidency.retainedBytes(next,previous);acquiring=new WeakReference<>(next);displayed=new WeakReference<>(previous);return bytes;
        }
    }
}
