package dev.madeinabyss.kernel.light.gpu;

/** Snapshot-only transport; deliberately has no sampler callback or Level reference. */
public final class ClosedLightScene {
    private final PackedLightVolume volume;
    private final PackedHeightHalo halo;
    private final int sky,lower,parentLower,water;
    private final double knownRadiusSquared;
    private final int[] bounds;
    public ClosedLightScene(PackedLightVolume volume,PackedHeightHalo halo,int skyY,int lowerY,int parentLowerY,double knownRadiusSquared,int waterOpacity){
        if(waterOpacity<0||waterOpacity>=15||!Double.isFinite(knownRadiusSquared)||knownRadiusSquared<=0||parentLowerY>lowerY||lowerY>=skyY)throw new IllegalArgumentException("Invalid optical authority");
        this.volume=java.util.Objects.requireNonNull(volume);this.halo=java.util.Objects.requireNonNull(halo);sky=skyY;lower=lowerY;parentLower=parentLowerY;this.knownRadiusSquared=knownRadiusSquared;water=waterOpacity;bounds=volume.dimensions();
    }
    public int opacity(int x,int y,int z){
        if(y>=sky)return 0;double r2=(double)x*x+(double)z*z;
        if(y<(r2<knownRadiusSquared?lower:parentLower))return -1;
        int cx=x>>4,cy=y>>4,cz=z>>4;boolean insideXZ=cx>=bounds[0]&&cx<=bounds[3]&&cz>=bounds[2]&&cz<=bounds[5];
        if(insideXZ&&cy>=bounds[1]&&cy<=bounds[4])return volume.lightOpacity(x,y,z);
        // Proven outside-R2000 island bound: boundary correction has faded out;
        // coast<=1186, bed<=-105 and wave<=32, channel only lowers. No positive
        // terrain/plant remains. This rule is bound to the verified profile by caller.
        if(y>0&&r2>=4000000)return 0;
        int top=halo.top(x,z);if(top==Integer.MIN_VALUE)return -1;
        if(y<=top)return 15;if(y<=0)return water;
        return top<=11||y>top+8?0:-1;
    }
}
