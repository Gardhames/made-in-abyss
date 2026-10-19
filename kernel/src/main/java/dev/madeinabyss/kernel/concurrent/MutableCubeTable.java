package dev.madeinabyss.kernel.concurrent;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.function.Consumer;

/** Single-writer open-addressing table keyed by three ints (plan builders). Allocation-free lookups; entries are never removed. */
public final class MutableCubeTable<V> {
    private int mask,size;
    private int[] xs,ys,zs;
    private Object[] values;

    public MutableCubeTable(){allocate(64);}
    private void allocate(int capacity){mask=capacity-1;xs=new int[capacity];ys=new int[capacity];zs=new int[capacity];values=new Object[capacity];}
    @SuppressWarnings("unchecked")
    public V get(int x,int y,int z){
        int i=FrozenCubeTable.hash(x,y,z)&mask;
        for(Object v;(v=values[i])!=null;i=(i+1)&mask)if(xs[i]==x&&ys[i]==y&&zs[i]==z)return (V)v;
        return null;
    }
    public void put(int x,int y,int z,V value){
        java.util.Objects.requireNonNull(value);
        int i=FrozenCubeTable.hash(x,y,z)&mask;
        for(;values[i]!=null;i=(i+1)&mask)if(xs[i]==x&&ys[i]==y&&zs[i]==z){values[i]=value;return;}
        xs[i]=x;ys[i]=y;zs[i]=z;values[i]=value;
        if(++size*2>mask+1)grow();
    }
    private void grow(){
        var ox=xs;var oy=ys;var oz=zs;var ov=values;allocate((mask+1)*2);size=0;
        for(int i=0;i<ov.length;i++)if(ov[i]!=null){
            int j=FrozenCubeTable.hash(ox[i],oy[i],oz[i])&mask;while(values[j]!=null)j=(j+1)&mask;
            xs[j]=ox[i];ys[j]=oy[i];zs[j]=oz[i];values[j]=ov[i];size++;
        }
    }
    public int size(){return size;}
    public void clear(){allocate(64);size=0;}
    @SuppressWarnings("unchecked")
    public void forEach(java.util.function.BiConsumer<CubeKey,V> visitor){for(int i=0;i<values.length;i++)if(values[i]!=null)visitor.accept(new CubeKey(xs[i],ys[i],zs[i]),(V)values[i]);}
}
