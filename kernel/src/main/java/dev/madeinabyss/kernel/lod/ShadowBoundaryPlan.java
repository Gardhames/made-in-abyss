package dev.madeinabyss.kernel.lod;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
/** Immutable worker-prepared whole opaque boundary. No camera-dependent reconstruction.
 * Lighting remains indexed by the original opaque owner, including split faces. */
public final class ShadowBoundaryPlan {
    public static final int MAX_INPUT_BOXES=400000,MAX_FACES=524288,CHUNK_FACES=4096,MAX_VERTEX_BYTES=64;
    public static final long MAX_GPU_BYTES=128L*1024*1024;
    private ShadowBoundaryPlan(){}
    /** Global bounds of the actual emitted vertices, including zero-width face planes. */
    public record ChunkBounds(long minX,long minY,long minZ,long maxX,long maxY,long maxZ) {}
    public record Prepared(List<TerrainLodColumns.Box> boxes,List<Integer> skies,OpaqueBoxFaces.Result surface,List<ChunkBounds> chunkBounds) {
        public Prepared {boxes=List.copyOf(boxes);skies=List.copyOf(skies);Objects.requireNonNull(surface);chunkBounds=List.copyOf(chunkBounds);
            if(chunkBounds.size()!=(surface.faces().size()+CHUNK_FACES-1)/CHUNK_FACES)throw new IllegalArgumentException("Chunk bounds/face mismatch");}
        public int sky(int owner){return skies.get(owner);}
        public int chunkCount(){return (surface.faces().size()+CHUNK_FACES-1)/CHUNK_FACES;}
        public int chunkStart(int chunk){if(chunk<0||chunk>=chunkCount())throw new IndexOutOfBoundsException();return chunk*CHUNK_FACES;}
        public int chunkEnd(int chunk){return Math.min(surface.faces().size(),chunkStart(chunk)+CHUNK_FACES);}
        public long maximumVertexBytes(){return (long)surface.faces().size()*4*MAX_VERTEX_BYTES;}
    }
    public static Prepared prepare(List<TerrainLodColumns.Box> input,Map<TerrainLodColumns.Box,Integer> light) {
        Objects.requireNonNull(input);Objects.requireNonNull(light);
        if(input.size()>MAX_INPUT_BOXES)throw new IllegalArgumentException("Far shadow input exceeds400000 boxes");
        var boxes=new ArrayList<TerrainLodColumns.Box>();var skies=new ArrayList<Integer>();var bounds=new ArrayList<OpaqueBoxFaces.Bounds>();
        for(var box:input) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Shadow preparation cancelled");
            if(box.material()==TerrainMaterial.WATER)continue;
            Integer sky=light.get(box);if(sky==null||sky<0||sky>15)throw new IllegalArgumentException("Far shadow worker SKY unavailable or invalid");
            boxes.add(box);skies.add(sky);bounds.add(new OpaqueBoxFaces.Bounds(box.minX(),box.minY(),box.minZ(),box.maxXExclusive(),box.maxYExclusive(),box.maxZExclusive()));
        }
        var surface=OpaqueBoxFaces.extract(bounds,MAX_FACES);
        var chunks=new ArrayList<ChunkBounds>();
        for(int start=0;start<surface.faces().size();start+=CHUNK_FACES) {
            long x0=Long.MAX_VALUE,y0=Long.MAX_VALUE,z0=Long.MAX_VALUE,x1=Long.MIN_VALUE,y1=Long.MIN_VALUE,z1=Long.MIN_VALUE;
            for(int i=start;i<Math.min(surface.faces().size(),start+CHUNK_FACES);i++) {
                var f=surface.faces().get(i);
                long fx0=f.axis()==0?f.plane():f.u0(),fx1=f.axis()==0?f.plane():f.u1();
                long fy0=f.axis()==1?f.plane():f.v0(),fy1=f.axis()==1?f.plane():f.v1();
                long fz0=f.axis()==0?f.u0():f.axis()==1?f.v0():f.plane();
                long fz1=f.axis()==0?f.u1():f.axis()==1?f.v1():f.plane();
                x0=Math.min(x0,fx0);x1=Math.max(x1,fx1);y0=Math.min(y0,fy0);y1=Math.max(y1,fy1);z0=Math.min(z0,fz0);z1=Math.max(z1,fz1);
            }
            chunks.add(new ChunkBounds(x0,y0,z0,x1,y1,z1));
        }
        return new Prepared(boxes,skies,surface,chunks);
    }
}
