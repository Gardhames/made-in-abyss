package dev.madeinabyss.kernel.lod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Conservative exact boundary extraction for opaque column boxes. X/Z faces only
 * subtract opposite faces with identical horizontal spans; Y faces require an exact
 * opposite rectangle. Partial horizontal matches remain, so no exposed face is lost.
 * Boxes are flattened into primitive arrays and matched through open-addressing tables: the
 * result (faces, order, hidden and split counts) is identical to the record/HashMap formulation. */
public final class OpaqueBoxFaces {
    private OpaqueBoxFaces() {}
    public record Bounds(long minX,long minY,long minZ,long maxX,long maxY,long maxZ) {
        public Bounds {if(minX>=maxX||minY>=maxY||minZ>=maxZ)throw new IllegalArgumentException("Positive box spans required");}
    }
    /** axis 0:X (u:Z,v:Y), 1:Y (u:X,v:Z), 2:Z (u:X,v:Y).
     * owner is the unchanged index in the input list, for lighting/material lookup. */
    public record Face(int owner,int axis,boolean positive,long plane,long u0,long v0,long u1,long v1) {}
    public record Result(List<Face> faces,int hiddenFaces,int extraSplitFaces) {
        public Result {faces=List.copyOf(faces);}
    }
    public static final class BudgetExceededException extends IllegalArgumentException {
        public BudgetExceededException(int limit){super("Opaque face budget exceeded: "+limit);}
    }
    private static void add(ArrayList<Face> output,Face face,int limit) {
        if(output.size()==limit)throw new BudgetExceededException(limit);output.add(face);
    }
    private static int slotsFor(int entries){int capacity=Integer.highestOneBit(Math.max(4,entries)*2-1)<<1;return capacity;}
    private static int hash(int tag,long plane,long a,long b,long c,long d){
        long h=plane*0x9E3779B97F4A7C15L+tag;
        h=(h^(h>>>32))*0xC2B2AE3D27D4EB4FL+a;
        h=(h^(h>>>29))*0x165667B19E3779F9L+b;
        h=(h^(h>>>32))*0x9E3779B97F4A7C15L+c;
        h=(h^(h>>>29))*0xC2B2AE3D27D4EB4FL+d;
        h^=h>>>32;h*=0x9E3779B97F4A7C15L;
        return (int)(h>>>32);
    }
    /** Box layout: minX,minY,minZ,maxX,maxY,maxZ. */
    private static long sidePlane(long[] box,int i,int axisIdx,int sign){int o=i*6;return axisIdx==0?box[o+3*sign]:box[o+2+3*sign];}
    public static Result extract(List<Bounds> input,int maxFaces) {
        if(maxFaces<1)throw new IllegalArgumentException("Positive face budget required");
        final int n=input.size();
        if(n==0)return new Result(List.of(),0,0);
        final long[] box=new long[n*6];
        {int i=0;for(Bounds b:input){Objects.requireNonNull(b);int o=i++*6;box[o]=b.minX;box[o+1]=b.minY;box[o+2]=b.minZ;box[o+3]=b.maxX;box[o+4]=b.maxY;box[o+5]=b.maxZ;}}
        // ---- X/Z faces: groups of identical (axis, sign, plane, u0, u1); each group's Y intervals are merged once.
        final int entries=n*4,slotCount=slotsFor(entries),mask=slotCount-1;
        final int[] slot=new int[slotCount]; // group id + 1
        final int[] entryGroup=new int[entries];
        final long[] gPlane=new long[entries],gU0=new long[entries],gU1=new long[entries];final byte[] gTag=new byte[entries];
        int groups=0;
        for(int e=0;e<entries;e++){
            int i=e>>2,axisIdx=(e>>1)&1,sign=e&1,o=i*6;
            long plane=sidePlane(box,i,axisIdx,sign),u0=axisIdx==0?box[o+2]:box[o],u1=axisIdx==0?box[o+5]:box[o+3];
            int tag=axisIdx*2+sign,pos=hash(tag,plane,u0,u1,0,0)&mask,g;
            while(true){
                int s=slot[pos];
                if(s==0){g=groups++;slot[pos]=g+1;gPlane[g]=plane;gU0[g]=u0;gU1[g]=u1;gTag[g]=(byte)tag;break;}
                g=s-1;if(gTag[g]==tag&&gPlane[g]==plane&&gU0[g]==u0&&gU1[g]==u1)break;
                pos=(pos+1)&mask;
            }
            entryGroup[e]=g;
        }
        final int[] offset=new int[groups+1];
        for(int e=0;e<entries;e++)offset[entryGroup[e]+1]++;
        for(int g=0;g<groups;g++)offset[g+1]+=offset[g];
        final int[] fill=Arrays.copyOf(offset,groups);
        final long[] iv0=new long[entries],iv1=new long[entries];
        for(int e=0;e<entries;e++){int o=(e>>2)*6,at=fill[entryGroup[e]]++;iv0[at]=box[o+1];iv1[at]=box[o+4];}
        // Premerge each bucket once; touching intervals merge so exact integer boundaries survive.
        final int[] merged=new int[groups];
        for(int g=0;g<groups;g++){
            int from=offset[g],to=offset[g+1];sortIntervals(iv0,iv1,from,to);
            int size=0;
            for(int k=from;k<to;k++){
                if(size>0&&iv1[from+size-1]>=iv0[k])iv1[from+size-1]=Math.max(iv1[from+size-1],iv1[k]);
                else{iv0[from+size]=iv0[k];iv1[from+size]=iv1[k];size++;}
            }
            merged[g]=size;
        }
        // ---- Y faces: exact opposite rectangle match.
        final int flatEntries=n*2,flatSlots=slotsFor(flatEntries),flatMask=flatSlots-1;
        final int[] flat=new int[flatSlots]; // entry + 1, entry = box*2 + sign
        for(int f=0;f<flatEntries;f++){
            int i=f>>1,sign=f&1,o=i*6;int pos=hash(sign,box[o+1+3*sign],box[o],box[o+2],box[o+3],box[o+5])&flatMask;
            while(true){
                int s=flat[pos];
                if(s==0){flat[pos]=f+1;break;}
                int j=(s-1)>>1,js=(s-1)&1,p=j*6;
                if(js==sign&&box[p+1+3*js]==box[o+1+3*sign]&&box[p]==box[o]&&box[p+2]==box[o+2]&&box[p+3]==box[o+3]&&box[p+5]==box[o+5])break;
                pos=(pos+1)&flatMask;
            }
        }
        final var output=new ArrayList<Face>(Math.min(maxFaces,n*6));int hidden=0,split=0;
        for(int i=0;i<n;i++)for(int axis=0;axis<3;axis++)for(int sign=0;sign<2;sign++){
            int before=output.size(),o=i*6;boolean positive=sign==1;
            if(axis==1){
                long plane=box[o+1+3*sign];int opposite=sign^1;
                int pos=hash(opposite,plane,box[o],box[o+2],box[o+3],box[o+5])&flatMask;boolean found=false;
                while(true){
                    int s=flat[pos];if(s==0)break;
                    int j=(s-1)>>1,js=(s-1)&1,p=j*6;
                    if(js==opposite&&box[p+1+3*js]==plane&&box[p]==box[o]&&box[p+2]==box[o+2]&&box[p+3]==box[o+3]&&box[p+5]==box[o+5]){found=true;break;}
                    pos=(pos+1)&flatMask;
                }
                if(!found)add(output,new Face(i,1,positive,plane,box[o],box[o+2],box[o+3],box[o+5]),maxFaces);
            }else{
                int axisIdx=axis>>1;
                long plane=sidePlane(box,i,axisIdx,sign),u0=axisIdx==0?box[o+2]:box[o],u1=axisIdx==0?box[o+5]:box[o+3],v0=box[o+1],v1=box[o+4];
                int tag=axisIdx*2+(sign^1),pos=hash(tag,plane,u0,u1,0,0)&mask,g=-1;
                while(true){
                    int s=slot[pos];if(s==0)break;
                    int c=s-1;if(gTag[c]==tag&&gPlane[c]==plane&&gU0[c]==u0&&gU1[c]==u1){g=c;break;}
                    pos=(pos+1)&mask;
                }
                long start=v0;
                if(g>=0){
                    for(int k=offset[g],end=k+merged[g];k<end;k++){
                        long is=iv0[k],ie=iv1[k];
                        if(ie<=start)continue;if(is>=v1)break;
                        if(is>start)add(output,new Face(i,axis,positive,plane,u0,start,u1,is),maxFaces);
                        start=Math.max(start,ie);if(start>=v1)break;
                    }
                }
                if(start<v1)add(output,new Face(i,axis,positive,plane,u0,start,u1,v1),maxFaces);
            }
            int emitted=output.size()-before;if(emitted==0)hidden++;else split+=emitted-1;
        }
        return new Result(output,hidden,split);
    }
    /** Ascending (start,end) over [from,to) of two parallel arrays; groups are almost always a handful of intervals. */
    private static void sortIntervals(long[] a,long[] b,int from,int to){
        int length=to-from;
        if(length<2)return;
        if(length<=24){
            for(int i=from+1;i<to;i++){
                long ka=a[i],kb=b[i];int j=i-1;
                while(j>=from&&(a[j]>ka||(a[j]==ka&&b[j]>kb))){a[j+1]=a[j];b[j+1]=b[j];j--;}
                a[j+1]=ka;b[j+1]=kb;
            }
            return;
        }
        // Large degenerate groups (very tall stacks of identical footprints): sort an index permutation.
        var order=new long[length][];
        for(int i=0;i<length;i++)order[i]=new long[]{a[from+i],b[from+i]};
        Arrays.sort(order,(p,q)->p[0]!=q[0]?Long.compare(p[0],q[0]):Long.compare(p[1],q[1]));
        for(int i=0;i<length;i++){a[from+i]=order[i][0];b[from+i]=order[i][1];}
    }
}
