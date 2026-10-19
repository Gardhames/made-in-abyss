package dev.madeinabyss.kernel.terrain.natural;
/** Shared continuous XYZ wet-community mask; no global heightmap or sampled-world cache. */
public final class EcologyMaskFields {
    private EcologyMaskFields() {}
    public static double moisture(long seed,int x,int y,int z) {
        double fx=(double)x/96,fy=(double)y/192,fz=(double)z/96;
        long ix=(long)Math.floor(fx),iy=(long)Math.floor(fy),iz=(long)Math.floor(fz);
        fx=smooth(fx-ix);fy=smooth(fy-iy);fz=smooth(fz-iz);double value=0;
        for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++) {
            long bits=mix(seed^mix(ix+dx)^Long.rotateLeft(mix(iy+dy),21)^Long.rotateLeft(mix(iz+dz),42)^mix(41));
            value+=(dx==0?1-fx:fx)*(dy==0?1-fy:fy)*(dz==0?1-fz:fz)*(bits>>>11)*0x1.0p-53;
        }
        return value;
    }
    private static double smooth(double v){return v*v*(3-2*v);}
    private static long mix(long n){n=(n^(n>>>30))*0xbf58476d1ce4e5b9L;n=(n^(n>>>27))*0x94d049bb133111ebL;return n^(n>>>31);}
}
