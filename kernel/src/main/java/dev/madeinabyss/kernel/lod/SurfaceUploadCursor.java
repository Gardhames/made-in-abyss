package dev.madeinabyss.kernel.lod;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** Resumable CPU encoding of one existing 4096-face GPU chunk. No GL or publication. */
public final class SurfaceUploadCursor {
    private final int first,end,quantum;
    private final IntPredicate water;
    private int position,phase,opaqueFaces,waterFaces;

    public SurfaceUploadCursor(int first,int end,IntPredicate water) {
        this(first,end,water,1);
    }
    public SurfaceUploadCursor(int first,int end,IntPredicate water,int quantum) {
        if(first<0||end<first||(long)end-first>4096)throw new IllegalArgumentException("Invalid surface upload chunk");
        if(quantum<1||quantum>64)throw new IllegalArgumentException("Surface CPU quantum must be 1..64 faces");
        this.first=first;this.end=end;this.position=first;this.water=Objects.requireNonNull(water);
        this.quantum=quantum;
    }
    /** Vertices retain original face order; indices retain opaque then WATER order and winding. */
    public boolean advance(BooleanSupplier available,IntConsumer vertex,IntConsumer index) {
        while(phase<3) {
            if(position==end){phase++;position=first;continue;}
            if(!available.getAsBoolean())return false;
            int limit=position+Math.min(quantum,end-position);
            if(phase==0) {
                for(;position<limit;position++){
                    vertex.accept(position);
                    if(water.test(position))waterFaces++;else opaqueFaces++;
                }
            } else {
                for(;position<limit;position++)if(water.test(position)==(phase==2)) {
                    int v=(position-first)*4;
                    index.accept(v);index.accept(v+1);index.accept(v+2);index.accept(v+2);index.accept(v+3);index.accept(v);
                }
            }
        }
        return true;
    }
    public boolean done(){return phase==3;}
    public int opaque(){return opaqueFaces;}
    public int water(){return waterFaces;}
}
