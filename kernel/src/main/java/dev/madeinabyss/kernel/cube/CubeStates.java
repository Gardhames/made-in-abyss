package dev.madeinabyss.kernel.cube;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.*;

/** Owner-confined storage with immutable palette payloads. First edit expands only that cube;
 * snapshots repack and never share a writable array. Native y/z/x indices are unchanged. */
final class CubeStates {
    private int[] palette;
    private long[] indices;
    private final int bits;
    private int[] direct;
    private CubeStates(int[] palette,long[] indices,int bits,int[] direct){this.palette=palette;this.indices=indices;this.bits=bits;this.direct=direct;}
    static CubeStates copyOf(int[] source) {
        if(source.length!=4096)throw new IllegalArgumentException("expected 4096 states");
        source=source.clone();boolean uniform=true;
        for(int state:source){if(state<0)throw new IllegalArgumentException("state IDs must be nonnegative");uniform&=state==source[0];}
        if(uniform)return new CubeStates(new int[]{source[0]},new long[0],0,null);
        var ids=new Int2IntOpenHashMap(16);ids.defaultReturnValue(-1);var palette=new int[16];int count=0;
        for(int state:source){if(state<0)throw new IllegalArgumentException("state IDs must be nonnegative");
            if(ids.get(state)<0){ids.put(state,count);if(count==palette.length)palette=Arrays.copyOf(palette,palette.length*2);palette[count++]=state;}}
        int bits=count==1?0:32-Integer.numberOfLeadingZeros(count-1);
        int words=(4096*bits+63)/64;
        if((long)count*4+(long)words*8>=4096L*4)return new CubeStates(null,null,0,source);
        var packed=new long[words];
        if(bits!=0)for(int i=0;i<4096;i++){
            long value=ids.get(source[i]);int bit=i*bits,at=bit>>>6,shift=bit&63;
            packed[at]|=value<<shift;if(shift+bits>64)packed[at+1]|=value>>>(64-shift);
        }
        return new CubeStates(Arrays.copyOf(palette,count),packed,bits,null);
    }
    CubeStates snapshotCopy(){return direct==null?new CubeStates(palette,indices,bits,null):copyOf(direct);}
    int get(int i){
        if(direct!=null)return direct[i];if(bits==0)return palette[0];
        int bit=i*bits,at=bit>>>6,shift=bit&63;long value=indices[at]>>>shift;
        if(shift+bits>64)value|=indices[at+1]<<(64-shift);
        return palette[(int)(value&((1L<<bits)-1))];
    }
    void set(int i,int value){if(get(i)==value)return;if(direct==null){direct=toArray();palette=null;indices=null;}direct[i]=value;}
    int[] toArray(){if(direct!=null)return direct.clone();var out=new int[4096];for(int i=0;i<4096;i++)out[i]=get(i);return out;}
}
