package dev.madeinabyss.kernel.lod;
import java.util.*;
import dev.madeinabyss.kernel.lod.SurfaceQuadPlan.*;
import static dev.madeinabyss.kernel.lod.SurfaceQuadPlan.*;
/** Pre-T3 extraction and helpers; the T1 OpaqueBoxFaces implementation is shared unchanged. */
final class T3ReferenceSurfaceExtraction {
    static Geometry extract(List<TerrainLodColumns.Box> input,int limit){
        var owners=List.copyOf(input);var faces=new ArrayList<Face>();
        var opaque=new ArrayList<OpaqueBoxFaces.Bounds>();var original=new ArrayList<Integer>();
        for(int owner=0;owner<owners.size();owner++){check();var b=owners.get(owner);
            if(b.material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER)continue;
            opaque.add(new OpaqueBoxFaces.Bounds(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive()));original.add(owner);}
        // The existing extractor removes only proven opposite cover. Mismatched rectangles
        // are conservatively retained, so cracks, omitted native cuts and water never hide
        // an exposed source face. Source occupancy and original owner bounds stay unchanged.
        OpaqueBoxFaces.Result surface;
        try{surface=OpaqueBoxFaces.extract(opaque,limit);}
        catch(OpaqueBoxFaces.BudgetExceededException exceeded){throw new TerrainLodColumns.BudgetExceededException("Intermediate surface rectangle budget exceeded");}
        for(var f:surface.faces())add(faces,new Face(original.get(f.owner()),f.axis(),f.positive(),f.plane(),f.u0(),f.v0(),f.u1(),f.v1()),limit);
        for(int owner=0;owner<owners.size();owner++){check();var b=owners.get(owner);
            if(b.material()!=dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER)continue;
            for(int axis=0;axis<3;axis++)for(int sign=0;sign<2;sign++)add(faces,face(b,owner,axis,sign!=0),limit);}
        return geometry(owners,faces);
    }
    private static Geometry geometry(List<TerrainLodColumns.Box> owners,List<Face> faces){var visible=new BitSet(owners.size());
        for(var f:faces)visible.set(f.owner());var indices=new ArrayList<Integer>();for(int i=visible.nextSetBit(0);i>=0;i=visible.nextSetBit(i+1))indices.add(i);
        return new Geometry(owners,faces,indices,(long)faces.size()*VERTEX_BYTES_PER_FACE);}
    private static Face face(TerrainLodColumns.Box b,int owner,int axis,boolean positive){return switch(axis){
        case 0->new Face(owner,axis,positive,positive?b.maxXExclusive():b.minX(),b.minZ(),b.minY(),b.maxZExclusive(),b.maxYExclusive());
        case 1->new Face(owner,axis,positive,positive?b.maxYExclusive():b.minY(),b.minX(),b.minZ(),b.maxXExclusive(),b.maxZExclusive());
        default->new Face(owner,axis,positive,positive?b.maxZExclusive():b.minZ(),b.minX(),b.minY(),b.maxXExclusive(),b.maxYExclusive());};}
    private static void add(List<Face> faces,Face face,int maxFaces){check();
        if(faces.size()>=maxFaces)throw new TerrainLodColumns.BudgetExceededException("Surface rectangle budget exceeded");faces.add(face);}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Surface preparation cancelled");}
}
