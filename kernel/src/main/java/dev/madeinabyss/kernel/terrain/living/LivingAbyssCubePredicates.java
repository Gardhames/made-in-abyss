package dev.madeinabyss.kernel.terrain.living;

import java.util.Arrays;

/** Worker-owned exact predicates for one prepared cube. Zero means unevaluated.
 * Bits: 4=known, 1=isAir, 2=sturdy UP. Never store final moss/mouth dressing here. */
public final class LivingAbyssCubePredicates {
    private final byte[] values=new byte[4096];
    private int x,y,z;
    private boolean active;
    public void begin(int x,int y,int z){this.x=x;this.y=y;this.z=z;Arrays.fill(values,(byte)0);active=true;}
    public void end(){active=false;}
    private int index(int x,int y,int z){
        long dx=(long)x-this.x,dy=(long)y-this.y,dz=(long)z-this.z;
        return active&&dx>=0&&dx<16&&dy>=0&&dy<16&&dz>=0&&dz<16?(int)((dy<<8)|(dz<<4)|dx):-1;
    }
    public int get(int x,int y,int z){int i=index(x,y,z);return i<0?0:values[i];}
    public void put(int x,int y,int z,int flags){int i=index(x,y,z);if(i>=0)values[i]=(byte)flags;}
}
