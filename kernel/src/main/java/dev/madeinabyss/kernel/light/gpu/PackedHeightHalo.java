package dev.madeinabyss.kernel.light.gpu;

import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;

/** Exact one-metre integer top values, compressed independently per16x16 tile. */
public final class PackedHeightHalo {
    private record Tile(int min,int max,int bits,int[] words){}
    private final int minX,minZ,side,tiles,minimum,maximum;
    private final int[] descriptors,data,maxima;
    private PackedHeightHalo(int x,int z,int side,int[] descriptors,int[] data,int[] maxima,int min,int max){minX=x;minZ=z;this.side=side;tiles=side/16;this.descriptors=descriptors;this.data=data;this.maxima=maxima;minimum=min;maximum=max;}
    public static PackedHeightHalo build(int minX,int minZ,int side,IntBinaryOperator top,int workers){
        if(side<=0||((minX|minZ|side)&15)!=0||workers<1||workers>64)throw new IllegalArgumentException("Aligned finite halo required");
        try{Math.addExact(minX,side-1);Math.addExact(minZ,side-1);}catch(ArithmeticException invalid){throw new IllegalArgumentException("Halo overflow",invalid);}
        int n=side/16,count=Math.multiplyExact(n,n);if(count>4194304)throw new IllegalArgumentException("Halo directory exceeds budget");var captured=new Tile[count];var cursor=new AtomicInteger();var pool=Executors.newFixedThreadPool(workers);var jobs=new ArrayList<Future<?>>();
        try{for(int w=0;w<workers;w++)jobs.add(pool.submit(()->{int[] values=new int[256];for(int k;(k=cursor.getAndIncrement())<count;){if(Thread.currentThread().isInterrupted())throw new CancellationException();int x0=minX+(k%n)*16,z0=minZ+(k/n)*16,lo=Integer.MAX_VALUE,hi=Integer.MIN_VALUE;
                for(int z=0;z<16;z++)for(int x=0;x<16;x++){int v=top.applyAsInt(x0+x,z0+z);values[z*16+x]=v;lo=Math.min(lo,v);hi=Math.max(hi,v);}
                int delta=Math.subtractExact(hi,lo),bits=delta==0?0:32-Integer.numberOfLeadingZeros(delta);if(bits>15)throw new IllegalArgumentException("Unbounded height tile");int[] words=new int[256*bits/32];
                if(bits>0)for(int i=0;i<256;i++){int b=i*bits,word=b>>>5,shift=b&31,v=values[i]-lo;words[word]|=v<<shift;if(shift+bits>32)words[word+1]|=v>>>(32-shift);}
                captured[k]=new Tile(lo,hi,bits,words);
            }}));for(var f:jobs)f.get();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("Halo capture interrupted");}
         catch(ExecutionException failed){if(failed.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException(failed.getCause());}
        finally{pool.shutdownNow();}
        long words=0;int minimum=Integer.MAX_VALUE,maximum=Integer.MIN_VALUE;for(var tile:captured){words+=tile.words.length;minimum=Math.min(minimum,tile.min);maximum=Math.max(maximum,tile.max);}if(words>=(1L<<28))throw new IllegalArgumentException("Halo pool address overflow");
        int[] data=new int[Math.toIntExact(words)],descriptors=new int[count*2],maxima=new int[count];int offset=0;
        for(int k=0;k<count;k++){var tile=captured[k];descriptors[k*2]=tile.min;descriptors[k*2+1]=(offset<<4)|tile.bits;maxima[k]=tile.max;System.arraycopy(tile.words,0,data,offset,tile.words.length);offset+=tile.words.length;}
        return new PackedHeightHalo(minX,minZ,side,descriptors,data,maxima,minimum,maximum);
    }
    public int top(int x,int z){long xx=(long)x-minX,zz=(long)z-minZ;if(xx<0||zz<0||xx>=side||zz>=side)return Integer.MIN_VALUE;int k=((int)zz>>4)*tiles+((int)xx>>4),meta=descriptors[k*2+1],bits=meta&15;
        if(bits==0)return descriptors[k*2];int b=(((int)zz&15)*16+((int)xx&15))*bits,word=(meta>>>4)+(b>>>5),shift=b&31;long value=Integer.toUnsignedLong(data[word])>>>shift;if(shift+bits>32)value|=Integer.toUnsignedLong(data[word+1])<<(32-shift);
        return descriptors[k*2]+((int)value&((1<<bits)-1));
    }
    public int maximumOutside(int x0,int z0,int x1,int z1){int max=Integer.MIN_VALUE;for(int z=0;z<tiles;z++)for(int x=0;x<tiles;x++){int xx=minX+x*16,zz=minZ+z*16;if(xx<x0||xx+15>x1||zz<z0||zz+15>z1)max=Math.max(max,maxima[z*tiles+x]);}return max;}
    public IntBuffer descriptors(){return IntBuffer.wrap(descriptors).asReadOnlyBuffer();}
    public IntBuffer data(){return IntBuffer.wrap(data).asReadOnlyBuffer();}
    public int minimumTop(){return minimum;}
    public int maximumTop(){return maximum;}
}
