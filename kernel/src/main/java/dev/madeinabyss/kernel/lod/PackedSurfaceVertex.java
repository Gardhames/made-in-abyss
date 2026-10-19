package dev.madeinabyss.kernel.lod;

import java.nio.ByteBuffer;

/** Official DH/Oculus terrain layout with explicit ARGB alpha, independent of GL. */
public final class PackedSurfaceVertex {
    public static final int STRIDE=16;
    private PackedSurfaceVertex(){}
    public static int normalIndex(int axis,boolean positive){return PackedShadowVertex.normalIndex(axis,positive);}
    public static void put(ByteBuffer buffer,long ax,long ay,long az,long x,long y,long z,
                           int argb,int sky,int block,int material,int normal){
        long dx,dy,dz;try{dx=Math.subtractExact(x,ax);dy=Math.subtractExact(y,ay);dz=Math.subtractExact(z,az);}
        catch(ArithmeticException overflow){throw new IllegalArgumentException("Global surface anchor overflow",overflow);}
        if(dx<0||dx>65535||dy<0||dy>65535||dz<0||dz>65535||sky<0||sky>15||block<0||block>15
            ||material<0||material>255||normal<0||normal>5)throw new IllegalArgumentException("Packed surface vertex out of range");
        if(buffer.remaining()<STRIDE)throw new java.nio.BufferOverflowException();
        buffer.putShort((short)dx).putShort((short)dy).putShort((short)dz).putShort((short)(sky|(block<<4)));
        buffer.put((byte)(argb>>16)).put((byte)(argb>>8)).put((byte)argb).put((byte)(argb>>>24));
        buffer.put((byte)material).put((byte)normal).putShort((short)0);
    }
}
