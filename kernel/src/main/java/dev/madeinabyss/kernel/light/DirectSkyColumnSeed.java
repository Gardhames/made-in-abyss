package dev.madeinabyss.kernel.light;

/** Direct SKY from one globally verified top seed and caller-owned ascending opacity data. */
public final class DirectSkyColumnSeed {
    private DirectSkyColumnSeed() {}
    public static void fill(byte[] opacity,byte[] sky,int start,int stride,int height,int topSky) {
        java.util.Objects.requireNonNull(opacity);java.util.Objects.requireNonNull(sky);
        if(opacity==sky||opacity.length!=sky.length||start<0||stride<1||height<1||topSky<0||topSky>15
                ||(long)start+(long)(height-1)*stride>=opacity.length)
            throw new IllegalArgumentException("Distinct bounded column arrays and scalar top SKY required");
        for(int i=0;i<height;i++)if((opacity[start+i*stride]&255)>15)throw new IllegalArgumentException("Opacity outside0..15");
        int light=topSky;sky[start+(height-1)*stride]=(byte)light;
        // The global top seed already includes top-voxel attenuation. Apply opacity only
        // after descending into the next voxel; open direct SKY15 does not decay in air.
        for(int i=height-2;i>=0;i--) {
            int at=start+i*stride,o=opacity[at];
            if(!(light==15&&o==0))light=Math.max(0,light-Math.max(1,o));
            sky[at]=(byte)light;
        }
    }
}
