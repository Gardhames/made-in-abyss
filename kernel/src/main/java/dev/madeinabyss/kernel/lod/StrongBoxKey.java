package dev.madeinabyss.kernel.lod;

import it.unimi.dsi.fastutil.Hash;
import java.util.Objects;

/** Lookup-only key. The historical BOX hash, ordering maps and persistent bytes stay unchanged. */
public record StrongBoxKey(TerrainLodColumns.Box box) {
    public StrongBoxKey { Objects.requireNonNull(box); }
    @Override public int hashCode(){return hash(box);}
    public static int hash(TerrainLodColumns.Box b){
        long h=b.minX()*0x9e3779b97f4a7c15L
            ^Long.rotateLeft(b.minY()*0xc2b2ae3d27d4eb4fL,11)
            ^Long.rotateLeft(b.minZ()*0x165667b19e3779f9L,22)
            ^Long.rotateLeft(b.maxXExclusive()*0x85ebca77c2b2ae63L,33)
            ^Long.rotateLeft(b.maxYExclusive()*0x27d4eb2f165667c5L,44)
            ^Long.rotateLeft(b.maxZExclusive()*0x94d049bb133111ebL,55)
            ^((long)b.material().ordinal()*0xd6e8feb86659fd93L);
        h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;
        h=(h^(h>>>27))*0x94d049bb133111ebL;
        h^=h>>>31;return (int)(h^(h>>>32));
    }
    public static final Hash.Strategy<TerrainLodColumns.Box> STRATEGY=new Strategy();
    private static final class Strategy implements Hash.Strategy<TerrainLodColumns.Box> {
        @Override public int hashCode(TerrainLodColumns.Box box){return box==null?0:hash(box);}
        @Override public boolean equals(TerrainLodColumns.Box a,TerrainLodColumns.Box b){return Objects.equals(a,b);}
    }
}
