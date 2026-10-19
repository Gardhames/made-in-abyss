package dev.madeinabyss.kernel.volume;

import java.io.*;
import java.util.*;

/** Immutable native-order16^3 tile. Each channel has explicit availability:
 * state=-1 may accompany a known opacity (lazy colour/meshing), never inferred AIR. */
public final class VoxelBrick {
    public static final int VOXELS=4096;
    private final int[] dictionary;
    private final int bits;
    private final long[] states,known;
    private final byte[] opacity;
    private final int uniformOpacity;
    private final boolean allKnown;
    private VoxelBrick(int[] dictionary,int bits,long[] states,long[] known,byte[] opacity,int uniformOpacity,boolean allKnown) {
        this.dictionary=dictionary;this.bits=bits;this.states=states;this.known=known;
        this.opacity=opacity;this.uniformOpacity=uniformOpacity;this.allKnown=allKnown;
    }
    private static void validate(int state,int opacity) {
        if(state< -1 || opacity< -1 || opacity>15 || state>=0&&opacity<0)
            throw new IllegalArgumentException("Known state requires opacity0..15; absent state may accompany known opacity");
    }
    public static VoxelBrick uniform(int state,int opacity) {
        validate(state,opacity);return new VoxelBrick(new int[]{state},0,new long[0],null,null,opacity,opacity>=0);
    }
    /** Full opacity, lazy state. No per-voxel state dictionary is needed. */
    public static VoxelBrick opacityOnly(int[] source) {
        if(source.length!=4096)throw new IllegalArgumentException("16^3 opacity required");int[] op=source.clone();
        int count=0,first=-1;boolean same=true;long[] known=new long[64];
        for(int i=0;i<4096;i++){if(op[i]< -1||op[i]>15)throw new IllegalArgumentException("Opacity0..15 or UNKNOWN required");if(op[i]>=0){known[i>>>6]|=1L<<(i&63);count++;if(first<0)first=op[i];else same&=first==op[i];}}
        if(same&&(count==0||count==4096))return uniform(-1,first);
        byte[] packed=same?null:new byte[2048];if(packed!=null)for(int i=0;i<4096;i++)if(op[i]>=0)packed[i>>>1]|=(byte)(op[i]<<((i&1)*4));
        return new VoxelBrick(new int[]{-1},0,new long[0],count==4096?null:known,packed,first,count==4096);
    }
    public static VoxelBrick copyOf(int[] sourceStates,int[] sourceOpacity) {
        Objects.requireNonNull(sourceStates);Objects.requireNonNull(sourceOpacity);
        if(sourceStates.length!=VOXELS||sourceOpacity.length!=VOXELS)throw new IllegalArgumentException("16^3 channels required");
        // Own both arrays before checking; publication never retains caller-owned storage.
        int[] s=sourceStates.clone(),o=sourceOpacity.clone();var dict=new LinkedHashMap<Integer,Integer>();
        int firstOpacity=-1,knownCount=0;boolean sameOpacity=true;
        long[] known=new long[64];
        for(int i=0;i<VOXELS;i++) {
            validate(s[i],o[i]);dict.computeIfAbsent(s[i],ignored->dict.size());
            if(o[i]>=0){known[i>>>6]|=1L<<(i&63);knownCount++;if(firstOpacity<0)firstOpacity=o[i];else sameOpacity&=firstOpacity==o[i];}
        }
        if(dict.size()==1&&sameOpacity&&(knownCount==0||knownCount==VOXELS))return uniform(s[0],o[0]);
        int bits=dict.size()==1?0:32-Integer.numberOfLeadingZeros(dict.size()-1);long[] packed=new long[(VOXELS*bits+63)/64];
        if(bits>0)for(int i=0;i<VOXELS;i++)set(packed,i,bits,dict.get(s[i]));
        byte[] opacity=sameOpacity?null:new byte[VOXELS/2];
        if(opacity!=null)for(int i=0;i<VOXELS;i++)if(o[i]>=0)opacity[i>>>1]|=(byte)(o[i]<<((i&1)*4));
        int[] dictionary=new int[dict.size()];dict.forEach((value,index)->dictionary[index]=value);
        return new VoxelBrick(dictionary,bits,packed,knownCount==VOXELS?null:known,opacity,firstOpacity,knownCount==VOXELS);
    }
    private static void set(long[] packed,int i,int bits,int value) {
        int bit=i*bits,word=bit>>>6,shift=bit&63;packed[word]|=(long)value<<shift;
        if(shift+bits>64)packed[word+1]|=(long)value>>>(64-shift);
    }
    public int stateAt(int index) {
        Objects.checkIndex(index,VOXELS);if(bits==0)return dictionary[0];
        int bit=index*bits,word=bit>>>6,shift=bit&63;long value=states[word]>>>shift;
        if(shift+bits>64)value|=states[word+1]<<(64-shift);
        return dictionary[(int)value&((1<<bits)-1)];
    }
    public boolean knownAt(int index) {
        Objects.checkIndex(index,VOXELS);return allKnown||(known!=null&&(known[index>>>6]&(1L<<(index&63)))!=0);
    }
    public int opacityAt(int index) {
        if(!knownAt(index))return -1;
        return opacity==null?uniformOpacity:(opacity[index>>>1]>>>((index&1)*4))&15;
    }
    public boolean allKnown() { return allKnown; }
    public boolean allUnknown() { return !allKnown&&known==null; }
    public boolean uniformOpacity() { return opacity==null; }
    public boolean uniformState() { return bits==0; }
    public long payloadBytes() { return 24L+4L*dictionary.length+8L*states.length+(known==null?0:8L*known.length)+(opacity==null?0:opacity.length); }
    public long gpuBytes() { return 24L+4L*dictionary.length+8L*states.length+(known==null?0:8L*known.length)+(opacity==null?0:opacity.length); }
    public void write(DataOutput out)throws IOException {
        out.writeShort(dictionary.length);for(int id:dictionary)out.writeInt(id);
        out.writeByte(bits);for(long word:states)out.writeLong(word);
        out.writeByte(allKnown?1:known==null?0:2);if(known!=null)for(long word:known)out.writeLong(word);
        out.writeByte(uniformOpacity);out.writeBoolean(opacity!=null);if(opacity!=null)out.write(opacity);
    }
    public static VoxelBrick read(DataInput in,int paletteSize)throws IOException {
        int size=in.readUnsignedShort();if(size<1||size>4096)throw new IOException("Invalid brick dictionary");
        int[] dictionary=new int[size];var unique=new HashSet<Integer>();
        for(int i=0;i<size;i++){int id=in.readInt();if(id< -1||id>=paletteSize||!unique.add(id))throw new IOException("Invalid stable palette reference");dictionary[i]=id;}
        int bits=in.readUnsignedByte(),expected=size==1?0:32-Integer.numberOfLeadingZeros(size-1);
        if(bits!=expected)throw new IOException("Invalid state bit width");
        long[] packed=new long[(VOXELS*bits+63)/64];for(int i=0;i<packed.length;i++)packed[i]=in.readLong();
        int flag=in.readUnsignedByte();if(flag>2)throw new IOException("Invalid known mask flag");
        long[] known=flag==2?new long[64]:null;if(known!=null)for(int i=0;i<known.length;i++)known[i]=in.readLong();
        int uniform=in.readByte();boolean dense=in.readBoolean();byte[] opacity=dense?new byte[2048]:null;if(dense)in.readFully(opacity);
        if(uniform< -1||uniform>15)throw new IOException("Invalid uniform opacity");
        var made=new VoxelBrick(dictionary,bits,packed,known,opacity,uniform,flag==1);
        try {for(int i=0;i<4096;i++){validate(made.stateAt(i),made.opacityAt(i));if(made.knownAt(i)!=(made.opacityAt(i)>=0))throw new IllegalArgumentException("Inconsistent opacity mask");}}
        catch(IllegalArgumentException|IndexOutOfBoundsException invalid){throw new IOException("Invalid brick channels",invalid);}
        return made;
    }
    /** SSBO words: descriptor flags/opacity pointer/state pointer/known pointer.
     * Offsets are relative to this array; state IDs reference the stable volume palette.
     * Page/root revisions are carried by the upload ticket, not truncated into a voxel. */
    public int[] gpuWords() {
        var words=new ArrayList<Integer>();for(int i=0;i<4;i++)words.add(0);
        int flags=(allKnown?1:known==null?0:2)|(opacity!=null?4:0)|((Math.max(0,uniformOpacity)&15)<<8);
        words.set(0,flags);
        if(opacity!=null){words.set(1,words.size());for(int i=0;i<opacity.length;i+=4)words.add((opacity[i]&255)|((opacity[i+1]&255)<<8)|((opacity[i+2]&255)<<16)|((opacity[i+3]&255)<<24));}
        words.set(2,words.size());words.add(dictionary.length);words.add(bits);for(int id:dictionary)words.add(id);
        for(long word:states){words.add((int)word);words.add((int)(word>>>32));}
        if(known!=null){words.set(3,words.size());for(long word:known){words.add((int)word);words.add((int)(word>>>32));}}
        return words.stream().mapToInt(Integer::intValue).toArray();
    }
    public static int gpuStateAt(int[] words,int index) {
        Objects.checkIndex(index,4096);int p=words[2],count=words[p],bits=words[p+1];if(bits==0)return words[p+2];
        int bit=index*bits,offset=p+2+count+(bit>>>5),shift=bit&31;long value=Integer.toUnsignedLong(words[offset])>>>shift;
        if(shift+bits>32)value|=Integer.toUnsignedLong(words[offset+1])<<(32-shift);
        return words[p+2+((int)value&((1<<bits)-1))];
    }
    public static int gpuOpacityAt(int[] words,int index) {
        Objects.checkIndex(index,4096);int known=words[0]&3;
        if(known==0||(known==2&&(words[words[3]+(index>>>5)]&(1<<(index&31)))==0))return -1;
        return (words[0]&4)==0?(words[0]>>>8)&15:(words[words[1]+(index>>>3)]>>>((index&7)*4))&15;
    }
}
