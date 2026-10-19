package dev.madeinabyss.kernel.lod;
import java.nio.ByteBuffer;
/** Official DH/Oculus 16-byte terrain vertex layout, independent of GL and world state. */
public final class PackedShadowVertex {
    public static final int STRIDE=16;
    private PackedShadowVertex() {}
    public static int normalIndex(int axis,boolean positive) {
        if(axis<0||axis>2)throw new IllegalArgumentException("Axis 0..2 required");
        return (axis==0?4:axis==1?0:2)+(positive?1:0);
    }
    public static void put(ByteBuffer buffer,long ax,long ay,long az,long x,long y,long z,int rgb,int sky,int block,int material,int normal) {
        long dx=x-ax,dy=y-ay,dz=z-az;
        if(dx<0||dx>65535||dy<0||dy>65535||dz<0||dz>65535||sky<0||sky>15||block<0||block>15
                ||material<0||material>255||normal<0||normal>5)throw new IllegalArgumentException("Packed shadow vertex out of range");
        buffer.putShort((short)dx).putShort((short)dy).putShort((short)dz).putShort((short)(sky|(block<<4)));
        buffer.put((byte)(rgb>>16)).put((byte)(rgb>>8)).put((byte)rgb).put((byte)255).put((byte)material).put((byte)normal).putShort((short)0);
    }
}
