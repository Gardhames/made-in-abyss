package dev.madeinabyss.kernel.cube;
import dev.madeinabyss.kernel.light.VoxelSkyLight;

/** Explicit bounded native draw and dense light allocation plan. Independent of physics residency. */
public final class NativeRenderDistance {
    public static final int MAX_CHUNKS=8,MAX_CELLS=40_000_000;
    private NativeRenderDistance() {}
    public record Plan(int requestedRadius,int radius,int anchorX,int anchorY,int anchorZ,
                       VoxelSkyLight.Bounds lightBounds,long cells,long peakBytes,boolean capped) {}
    public static Plan plan(int chunks,double x,double y,double z,int minimumY,int maximumY) {
        if(chunks<1||chunks>128||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||y<minimumY||y>=maximumY)
            throw new IllegalArgumentException("Invalid native distance/camera domain");
        int ax=anchor(x),ay=anchor(y),az=anchor(z),radius=Math.min(chunks,MAX_CHUNKS)*16,size=radius*2+64;
        long minX=(long)ax-radius-24,minZ=(long)az-radius-24;
        long minY=Math.max((long)minimumY,(long)ay-112),maxY=Math.min((long)maximumY,(long)ay+72);
        if(minX<Integer.MIN_VALUE||minZ<Integer.MIN_VALUE||minX+size-1>Integer.MAX_VALUE||minZ+size-1>Integer.MAX_VALUE||maxY<=minY)
            throw new IllegalArgumentException("Native light halo exceeds signed coordinates");
        var bounds=new VoxelSkyLight.Bounds((int)minX,(int)minY,(int)minZ,size,(int)(maxY-minY),size);
        long cells=(long)size*size*bounds.sizeY();if(cells>MAX_CELLS)throw new IllegalArgumentException("Native light cell budget exceeded");
        // Density ids, inputs/results/queue, retained old snapshot and conservative mesh overhead.
        return new Plan(chunks*16,radius,ax,ay,az,bounds,cells,cells*26, chunks>MAX_CHUNKS);
    }
    public static boolean retain(boolean authorityAvailable,long builtEpoch,long currentEpoch,boolean resourcesMatch) {
        return authorityAvailable && builtEpoch==currentEpoch && resourcesMatch;
    }
    public static int completeCubeMinimum(int minimumY) {
        long result=-Math.floorDiv(-(long)minimumY,16)*16;
        if(result>Integer.MAX_VALUE)throw new IllegalArgumentException("No complete cube above signed minimum");
        return (int)result;
    }
    /** Exclusive end of the last complete cube. */
    public static int completeCubeMaximum(int maximumY) {return Math.floorDiv(maximumY,16)*16;}
    private static int anchor(double value) {
        double floor=Math.floor(value);if(floor<Integer.MIN_VALUE||floor>Integer.MAX_VALUE)throw new IllegalArgumentException("Native camera outside signed coordinates");
        return Math.multiplyExact(Math.floorDiv((int)floor,16),16);
    }
}
