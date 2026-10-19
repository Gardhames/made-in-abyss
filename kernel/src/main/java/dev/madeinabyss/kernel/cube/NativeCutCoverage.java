package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.lod.TerrainLodClipper.BoundsLong;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure requested-volume coverage, including AIR, independent of visible models and fluids. */
public final class NativeCutCoverage {
    private static final long SIGNED_MIN=Integer.MIN_VALUE,SIGNED_LIMIT=(long)Integer.MAX_VALUE+1;
    private NativeCutCoverage() {}

    /**
     * Every voxel column whose center lies on or inside the requested horizontal circle
     * must be wholly covered in XZ, with the union of its cuts covering every inclusive Y.
     * Half-open endpoints use long arithmetic; SIGNED_LIMIT is a valid exclusive endpoint.
     * Invalid/unsupported input refuses coverage. Runtime depends on radius and cut count,
     * never on vertical depth, and input order/overlap does not affect the result.
     */
    public static boolean covers(double centerX,double centerZ,int radius,int minY,int maxYInclusive,
                                 List<BoundsLong> cuts) {
        if(!Double.isFinite(centerX)||!Double.isFinite(centerZ)||centerX<SIGNED_MIN||centerX>=SIGNED_LIMIT
                ||centerZ<SIGNED_MIN||centerZ>=SIGNED_LIMIT||radius<1||radius>128||minY>maxYInclusive
                ||cuts==null||cuts.isEmpty())return false;
        var ordered=new ArrayList<BoundsLong>(cuts.size());
        for(var cut:cuts){
            if(cut==null||cut.minX()<SIGNED_MIN||cut.minY()<SIGNED_MIN||cut.minZ()<SIGNED_MIN
                    ||cut.maxXExclusive()>SIGNED_LIMIT||cut.maxYExclusive()>SIGNED_LIMIT
                    ||cut.maxZExclusive()>SIGNED_LIMIT)return false;
            ordered.add(cut);
        }
        ordered.sort(Comparator.comparingLong(BoundsLong::minY));
        long firstX=(long)Math.ceil(centerX-radius-.5),lastX=(long)Math.floor(centerX+radius-.5);
        long firstZ=(long)Math.ceil(centerZ-radius-.5),lastZ=(long)Math.floor(centerZ+radius-.5);
        long limitY=(long)maxYInclusive+1;
        double radiusSquared=(double)radius*radius;
        for(long x=firstX;x<=lastX;x++)for(long z=firstZ;z<=lastZ;z++){
            double dx=x+.5-centerX,dz=z+.5-centerZ;
            if(dx*dx+dz*dz>radiusSquared)continue;
            if(x<SIGNED_MIN||x>=SIGNED_LIMIT||z<SIGNED_MIN||z>=SIGNED_LIMIT
                    ||!coversColumn(x,z,minY,limitY,ordered))return false;
        }
        return true;
    }

    private static boolean coversColumn(long x,long z,long minY,long limitY,List<BoundsLong> ordered) {
        long coveredUntil=minY;
        for(var cut:ordered){
            if(cut.minX()>x||cut.maxXExclusive()<x+1||cut.minZ()>z||cut.maxZExclusive()<z+1
                    ||cut.maxYExclusive()<=coveredUntil)continue;
            if(cut.minY()>coveredUntil)return false; // Later starts cannot fill this gap.
            coveredUntil=cut.maxYExclusive();
            if(coveredUntil>=limitY)return true;
        }
        return false;
    }
}
