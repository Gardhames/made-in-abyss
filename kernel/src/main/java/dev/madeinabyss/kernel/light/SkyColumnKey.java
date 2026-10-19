package dev.madeinabyss.kernel.light;

/** Full signed XZ identity; packed form stays compatible with existing derived sky cache codecs. */
public record SkyColumnKey(int x,int z) {
    @Override public int hashCode(){return SkyProbeSingleFlight.hashCoordinates(x,0,z);}
    public long packed(){return ((long)x<<32)|(z&0xffffffffL);}
    public static SkyColumnKey fromPacked(long packed){return new SkyColumnKey((int)(packed>>32),(int)packed);}
}
