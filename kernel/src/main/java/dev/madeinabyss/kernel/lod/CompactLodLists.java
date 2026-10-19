package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;

/** Immutable ordered value storage. Public records, equality, hash and codec order stay exact.
 * Full signed-long coordinates use a wide representation; no coordinate is quantized. */
public final class CompactLodLists {
    private static final int MIN_PACKED=32;
    private static final TerrainMaterial[] MATERIALS=TerrainMaterial.values();
    private CompactLodLists() {}

    /** General Result construction preserves the caller's original owner objects. Only an
     * explicitly packed, privately immutable list opts into value-based reconstruction. */
    public static List<TerrainLodColumns.Box> immutableBoxes(List<TerrainLodColumns.Box> values){
        return values instanceof Boxes?values:List.copyOf(values);
    }
    /** Use at a private cache ownership boundary; the general Result API keeps owner identity. */
    public static TerrainLodColumns.Result result(TerrainLodColumns.Result value){
        var packed=boxes(value.boxes());return packed==value.boxes()?value:new TerrainLodColumns.Result(packed,value.sampledVoxels());
    }

    public static List<TerrainLodColumns.Box> boxes(List<TerrainLodColumns.Box> values) {
        Objects.requireNonNull(values);
        return values instanceof Boxes?values:values.size()<MIN_PACKED?List.copyOf(values):new Boxes(values);
    }
    public static List<SurfaceQuadPlan.Face> faces(List<SurfaceQuadPlan.Face> values) {
        Objects.requireNonNull(values);
        return values instanceof Faces?values:values.size()<MIN_PACKED?List.copyOf(values):new Faces(values);
    }
    public static List<SurfaceQuadPlan.Owner> owners(List<SurfaceQuadPlan.Owner> values) {
        Objects.requireNonNull(values);
        return values instanceof Owners?values:values.size()<MIN_PACKED?List.copyOf(values):new Owners(values);
    }
    public static List<Integer> integers(List<Integer> values) {
        Objects.requireNonNull(values);
        return values instanceof Ints?values:values.size()<MIN_PACKED?List.copyOf(values):new Ints(values);
    }
    public static List<List<Integer>> contributors(List<List<Integer>> values) {
        Objects.requireNonNull(values);
        if(values instanceof Contributors)return values;
        if(values.size()<MIN_PACKED)return values.stream().map(List::copyOf).toList();
        return new Contributors(values);
    }
    private abstract static class ImmutableList<T> extends AbstractList<T> implements RandomAccess {
        // AbstractList defaults are read-only; even empty no-op mutations must refuse.
        @Override public final T set(int i,T v){throw new UnsupportedOperationException();}
        @Override public final void add(int i,T v){throw new UnsupportedOperationException();}
        @Override public final T remove(int i){throw new UnsupportedOperationException();}
        @Override public final void clear(){throw new UnsupportedOperationException();}
    }
    private static final class Coordinates {
        final int[] narrow;final long[] wide;
        Coordinates(long[] values) {
            boolean small=true;for(long v:values)if(v<Integer.MIN_VALUE||v>Integer.MAX_VALUE){small=false;break;}
            if(small){narrow=new int[values.length];for(int i=0;i<values.length;i++)narrow[i]=(int)values[i];wide=null;}
            else {narrow=null;wide=values;}
        }
        long get(int i){return narrow==null?wide[i]:narrow[i];}
    }
    private static final class Boxes extends ImmutableList<TerrainLodColumns.Box> {
        final Coordinates coordinates;final byte[] materials;
        Boxes(List<TerrainLodColumns.Box> values) {
            int n=values.size();var coords=new long[Math.multiplyExact(n,6)];materials=new byte[n];int i=0;
            for(var box:values){Objects.requireNonNull(box);int at=i*6;
                coords[at]=box.minX();coords[at+1]=box.minY();coords[at+2]=box.minZ();
                coords[at+3]=box.maxXExclusive();coords[at+4]=box.maxYExclusive();coords[at+5]=box.maxZExclusive();materials[i++]=(byte)box.material().ordinal();}
            coordinates=new Coordinates(coords);
        }
        @Override public int size(){return materials.length;}
        @Override public TerrainLodColumns.Box get(int i){Objects.checkIndex(i,size());int at=i*6;var c=coordinates;
            return new TerrainLodColumns.Box(c.get(at),c.get(at+1),c.get(at+2),c.get(at+3),c.get(at+4),c.get(at+5),MATERIALS[Byte.toUnsignedInt(materials[i])]);}
    }
    private static final class Faces extends ImmutableList<SurfaceQuadPlan.Face> {
        final Coordinates coordinates;final int[] owners;final byte[] directions;
        Faces(List<SurfaceQuadPlan.Face> values) {
            int n=values.size();var coords=new long[Math.multiplyExact(n,5)];owners=new int[n];directions=new byte[n];int i=0;
            for(var f:values){Objects.requireNonNull(f);int at=i*5;owners[i]=f.owner();directions[i]=(byte)(f.axis()*2+(f.positive()?1:0));
                coords[at]=f.plane();coords[at+1]=f.u0();coords[at+2]=f.v0();coords[at+3]=f.u1();coords[at+4]=f.v1();i++;}
            coordinates=new Coordinates(coords);
        }
        @Override public int size(){return owners.length;}
        @Override public SurfaceQuadPlan.Face get(int i){Objects.checkIndex(i,size());int at=i*5;var c=coordinates;
            return new SurfaceQuadPlan.Face(owners[i],directions[i]>>>1,(directions[i]&1)!=0,c.get(at),c.get(at+1),c.get(at+2),c.get(at+3),c.get(at+4));}
    }
    private static final class Owners extends ImmutableList<SurfaceQuadPlan.Owner> {
        final List<TerrainLodColumns.Box> boxes;final int[] rgba;final byte[] lights;
        Owners(List<SurfaceQuadPlan.Owner> values) {
            int n=values.size();var raw=new ArrayList<TerrainLodColumns.Box>(n);rgba=new int[n];lights=new byte[n];int i=0;
            for(var v:values){Objects.requireNonNull(v);raw.add(v.box());rgba[i]=v.rgba();lights[i++]=(byte)(v.sky()<<4|v.block());}
            boxes=boxes(raw);
        }
        @Override public int size(){return rgba.length;}
        @Override public SurfaceQuadPlan.Owner get(int i){Objects.checkIndex(i,size());int light=Byte.toUnsignedInt(lights[i]);
            return new SurfaceQuadPlan.Owner(boxes.get(i),light>>>4,light&15,rgba[i]);}
    }
    private static final class Ints extends ImmutableList<Integer> {
        final int[] values;
        Ints(List<Integer> input){values=new int[input.size()];for(int i=0;i<values.length;i++)values[i]=input.get(i);}
        @Override public int size(){return values.length;}
        @Override public Integer get(int i){return values[Objects.checkIndex(i,size())];}
    }
    private static final class Contributors extends ImmutableList<List<Integer>> {
        final int[] offsets,ids;final int size;
        Contributors(List<List<Integer>> input) {
            size=input.size();int count=0;boolean singleton=true;
            for(var row:input){count=Math.addExact(count,Objects.requireNonNull(row).size());singleton&=row.size()==1;}
            offsets=singleton?null:new int[Math.addExact(size,1)];ids=new int[count];int at=0,i=0;
            for(var row:input){if(offsets!=null)offsets[i]=at;for(int id:row)ids[at++]=id;i++;}
            if(offsets!=null)offsets[size]=at;
        }
        @Override public int size(){return size;}
        @Override public List<Integer> get(int i){Objects.checkIndex(i,size);return offsets==null?new IntSlice(ids,i,i+1):new IntSlice(ids,offsets[i],offsets[i+1]);}
    }
    private static final class IntSlice extends ImmutableList<Integer> {
        final int[] values;final int from,to;
        IntSlice(int[] values,int from,int to){this.values=values;this.from=from;this.to=to;}
        @Override public int size(){return to-from;}
        @Override public Integer get(int i){return values[from+Objects.checkIndex(i,size())];}
    }
}
