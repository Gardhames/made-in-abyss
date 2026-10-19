package dev.madeinabyss.kernel.concurrent;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.Map;

/** Immutable open-addressing table keyed by three ints. Lookups allocate nothing and use a mixed hash:
 * the record hash of a {@link CubeKey} is a base-31 polynomial that clusters badly for neighbouring cubes. */
public final class FrozenCubeTable<V> {
    private final int mask;
    private final int[] xs,ys,zs;
    private final Object[] values;
    private final int size;

    public FrozenCubeTable(Map<CubeKey,V> source) {
        size=source.size();
        int capacity=16;while(capacity<size*2)capacity<<=1;
        mask=capacity-1;xs=new int[capacity];ys=new int[capacity];zs=new int[capacity];values=new Object[capacity];
        for(var e:source.entrySet()){
            var k=e.getKey();int i=hash(k.x(),k.y(),k.z())&mask;
            while(values[i]!=null)i=(i+1)&mask;
            xs[i]=k.x();ys[i]=k.y();zs[i]=k.z();values[i]=java.util.Objects.requireNonNull(e.getValue());
        }
    }
    public static int hash(int x,int y,int z){
        int h=x*0x9E3779B1+y*0x85EBCA77+z*0xC2B2AE3D;h^=h>>>15;h*=0x2C1B3C6D;h^=h>>>13;return h;
    }
    @SuppressWarnings("unchecked")
    public V get(int x,int y,int z){
        int i=hash(x,y,z)&mask;
        for(Object v;(v=values[i])!=null;i=(i+1)&mask)if(xs[i]==x&&ys[i]==y&&zs[i]==z)return (V)v;
        return null;
    }
    public int size(){return size;}
}
