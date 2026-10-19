package dev.madeinabyss.kernel.light;

/** Presentation conversion for the unmodified IterationT 3.2.0 SKY response.
 * Authoritative SKY uses vanilla V(s)=(s/15)/(4-3*s/15). Oculus lightmap UV is
 * (16*s+8)/256; this pack normalizes it with clamp(UV*1.103449-0.0689656), then
 * applies clamp(1.95*(1-(1-0.9*n)^0.7)^3). Each LUT output minimizes absolute
 * irradiance error across the 16 available levels. This is not a gameplay solver. */
public final class IterationSkyResponse {
    private static final int[] SKY = {0,5,6,7,8,9,9,10,10,11,12,12,13,13,14,15};
    private IterationSkyResponse() {}

    public static int presentSky(int sky) {
        if (sky < 0 || sky > 15) throw new IllegalArgumentException("SKY must be 0..15");
        return SKY[sky];
    }

    /** Preserve the complete BLOCK half, replacing only the submitted SKY half. */
    public static int presentPacked(int packed) {
        return (packed & 0xffff) | (presentSky((packed >>> 20) & 15) << 20);
    }
}
