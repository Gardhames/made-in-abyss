package dev.madeinabyss.kernel.light;

import java.util.Objects;

/** Render-only sky irradiance approximation; physical direct SKY and canSeeSky remain separate. */
public final class SkyVisibility {
    private static final int RAY_COUNT=32;
    private static final double[][] DIRECTIONS=directions();
    private static final int PREVIEW_RAY_COUNT=8;
    private static final double[][] PREVIEW_DIRECTIONS=previewDirections();
    private SkyVisibility() { }
    /** Unknown geometry must throw; it must never be supplied as an air or opaque default. */
    @FunctionalInterface public interface VoxelOpacity {
        int opacity(int x,int y,int z);
        /** True proves all4096 voxels known with opacity0, including saved override authority.
         * Called at least once per crossed cube; implementations retain cancellation/retirement checks. */
        default boolean uniformAirCube(int cx,int cy,int cz){return false;}
    }

    /**
     * Exact center-to-center segment traversal for irradiance reuse. Every voxel with positive
     * path length, including both endpoint voxels, must have opacity zero. Unknown callbacks
     * and exhausted budgets throw. Rational crossing comparisons avoid floating-point false
     * edge/corner contacts, including descending and extreme signed-coordinate segments.
     */
    public static boolean isOpenSegment(int x,int y,int z,int endX,int endY,int endZ,int maxSteps,VoxelOpacity opacity) {
        Objects.requireNonNull(opacity,"opacity");
        if(maxSteps<1)throw new IllegalArgumentException("Segment step budget must be positive");
        long dx=(long)endX-x,dy=(long)endY-y,dz=(long)endZ-z;
        int stepX=Long.compare(dx,0),stepY=Long.compare(dy,0),stepZ=Long.compare(dz,0);
        long lengthX=Math.abs(dx),lengthY=Math.abs(dy),lengthZ=Math.abs(dz);
        // Crossing times are oddNumerator/(2*axisLength), starting half a voxel from the center.
        long nextX=1,nextY=1,nextZ=1;
        for(int visited=0;visited<maxSteps;visited++) {
            int blocked=opacity.opacity(x,y,z);
            if(blocked<0 || blocked>15)throw new IllegalArgumentException("Voxel opacity must be 0..15");
            if(blocked>0)return false;
            if(x==endX && y==endY && z==endZ)return true;
            long firstNumerator=nextX,firstLength=lengthX;
            if(compareCrossing(nextY,lengthY,firstNumerator,firstLength)<0){firstNumerator=nextY;firstLength=lengthY;}
            if(compareCrossing(nextZ,lengthZ,firstNumerator,firstLength)<0){firstNumerator=nextZ;firstLength=lengthZ;}
            boolean crossX=compareCrossing(nextX,lengthX,firstNumerator,firstLength)==0;
            boolean crossY=compareCrossing(nextY,lengthY,firstNumerator,firstLength)==0;
            boolean crossZ=compareCrossing(nextZ,lengthZ,firstNumerator,firstLength)==0;
            if(crossX){x=advance(x,stepX);nextX+=2;}
            if(crossY){y=advance(y,stepY);nextY+=2;}
            if(crossZ){z=advance(z,stepZ);nextZ+=2;}
        }
        throw new IllegalStateException("Sky visibility segment step budget exhausted");
    }

    private static int compareCrossing(long numerator,long length,long otherNumerator,long otherLength) {
        if(length==0)return otherLength==0?0:1;
        if(otherLength==0)return -1;
        if(numerator<=Long.MAX_VALUE/otherLength && otherNumerator<=Long.MAX_VALUE/length)
            return Long.compare(numerator*otherLength,otherNumerator*length);
        // Normal 8/32m irradiance segments use the allocation-free long branch above.
        return java.math.BigInteger.valueOf(numerator).multiply(java.math.BigInteger.valueOf(otherLength))
                .compareTo(java.math.BigInteger.valueOf(otherNumerator).multiply(java.math.BigInteger.valueOf(length)));
    }

    /**
     * Traces exact visited voxels from the center of an air/surface-neighbor voxel towards a
     * globally verified open-sky Y boundary. Directions sample the cosine-weighted hemisphere,
     * so each ray contributes an equal share of diffuse irradiance. The finite 32-ray sampling
     * approximates irradiance, while its DDA traversal cannot skip thin opaque voxel geometry.
     * Partial opacity multiplies transmission by (15-opacity)/15 at each visited voxel.
     * Exhausted budgets and signed-coordinate overflow throw instead of implying darkness.
     */
    public static double visibleFraction(int x,int y,int z,int openSkyY,int maxStepsPerRay,VoxelOpacity opacity) {
        Objects.requireNonNull(opacity,"opacity");
        if(maxStepsPerRay<1)throw new IllegalArgumentException("Ray step budget must be positive");
        if(y>=openSkyY)return 1.0;
        double irradiance=0.0;
        for(var direction:DIRECTIONS)irradiance+=trace(x,y,z,openSkyY,maxStepsPerRay,opacity,direction);
        return irradiance/RAY_COUNT;
    }

    /** Temporary panorama only: fewer irradiance directions, the same exact voxel traversal. */
    public static double visibleFractionPreview(int x,int y,int z,int openSkyY,int maxStepsPerRay,VoxelOpacity opacity) {
        Objects.requireNonNull(opacity,"opacity");
        if(maxStepsPerRay<1)throw new IllegalArgumentException("Ray step budget must be positive");
        if(y>=openSkyY)return 1.0;
        double irradiance=0.0;
        for(var direction:PREVIEW_DIRECTIONS)irradiance+=trace(x,y,z,openSkyY,maxStepsPerRay,opacity,direction);
        return irradiance/PREVIEW_RAY_COUNT;
    }

    private static double[][] previewDirections() {
        var directions=new double[PREVIEW_RAY_COUNT][3];
        double goldenAngle=Math.PI*(3.0-Math.sqrt(5.0));
        for(int i=0;i<PREVIEW_RAY_COUNT;i++) {
            double dy=Math.sqrt((i+0.5)/PREVIEW_RAY_COUNT);
            double radius=Math.sqrt(1.0-dy*dy),azimuth=i*goldenAngle;
            directions[i][0]=radius*Math.cos(azimuth);directions[i][1]=dy;directions[i][2]=radius*Math.sin(azimuth);
        }
        return directions;
    }

    /** Scalar display response; only the separate direct-SKY path may produce level 15. */
    public static int diffuseLevel(double visibleFraction) {
        if(!Double.isFinite(visibleFraction) || visibleFraction<0.0 || visibleFraction>1.0)
            throw new IllegalArgumentException("Visible irradiance must be finite and 0..1");
        return Math.min(14,(int)Math.round(15.0*(4.0*visibleFraction/(1.0+3.0*visibleFraction))));
    }

    private static double[][] directions() {
        double[][] directions=new double[RAY_COUNT][3];
        double goldenAngle=Math.PI*(3.0-Math.sqrt(5.0));
        for(int i=0;i<RAY_COUNT;i++) {
            double dy=Math.sqrt((i+0.5)/RAY_COUNT);
            double radius=Math.sqrt(1.0-dy*dy),azimuth=i*goldenAngle;
            directions[i][0]=radius*Math.cos(azimuth);
            directions[i][1]=dy;
            directions[i][2]=radius*Math.sin(azimuth);
        }
        return directions;
    }

    private static double trace(int x,int y,int z,int openSkyY,int budget,VoxelOpacity opacity,double[] direction) {
        int stepX=direction[0]>0?1:direction[0]<0?-1:0;
        int stepZ=direction[2]>0?1:direction[2]<0?-1:0;
        double deltaX=stepX==0?Double.POSITIVE_INFINITY:1.0/Math.abs(direction[0]);
        double deltaY=1.0/direction[1];
        double deltaZ=stepZ==0?Double.POSITIVE_INFINITY:1.0/Math.abs(direction[2]);
        // Local center-origin distances keep deep/global integer translations numerically identical.
        double nextX=0.5*deltaX,nextY=0.5*deltaY,nextZ=0.5*deltaZ;
        double transmission=1.0;
        int cubeX=0,cubeY=0,cubeZ=0;boolean checkedCube=false,cubeAir=false;
        for(int visited=0;visited<budget;visited++) {
            int cx=x>>4,cy=y>>4,cz=z>>4;
            if(!checkedCube||cx!=cubeX||cy!=cubeY||cz!=cubeZ){
                cubeAir=opacity.uniformAirCube(cx,cy,cz);cubeX=cx;cubeY=cy;cubeZ=cz;checkedCube=true;
            }
            // Retain EVERY DDA addition, crossing, overflow check and budget step. A floating
            // jump/reassociation could change thin-voxel visits at distant tied boundaries.
            int blocked=cubeAir?0:opacity.opacity(x,y,z);
            if(blocked<0 || blocked>15)throw new IllegalArgumentException("Voxel opacity must be 0..15");
            if(blocked==15)return 0.0;
            if(blocked>0)transmission*=(15.0-blocked)/15.0;
            double crossing=Math.min(nextX,Math.min(nextY,nextZ));
            // Advance all tied axes together: edge/corner contacts have no occupied path length.
            if(nextX==crossing){x=advance(x,stepX);nextX+=deltaX;}
            if(nextY==crossing){y=advance(y,1);nextY+=deltaY;}
            if(nextZ==crossing){z=advance(z,stepZ);nextZ+=deltaZ;}
            if(y>=openSkyY)return transmission;
        }
        throw new IllegalStateException("Sky visibility ray step budget exhausted");
    }

    private static int advance(int coordinate,int step) {
        long next=(long)coordinate+step;
        if(next<Integer.MIN_VALUE || next>Integer.MAX_VALUE)
            throw new IllegalStateException("Sky visibility ray exceeds signed world coordinates");
        return (int)next;
    }
}
