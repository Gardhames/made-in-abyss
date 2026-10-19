package dev.madeinabyss.kernel.lod;

import java.util.*;
import java.util.function.IntFunction;

/** Render-only faces retain the exact source box which owns their physical light. */
public final class SurfaceQuadPlan {
    public static final int CHUNK_FACES=4096,VERTEX_BYTES_PER_FACE=64,GPU_BYTES_PER_FACE=76;
    /** Retained pre-light rectangle metadata estimate; extractor working indexes are separate. */
    public static final int CPU_FACE_BYTES=96;
    private SurfaceQuadPlan(){}
    /** Same axis/u/v convention as OpaqueBoxFaces; rectangles are half-open. */
    public record Face(int owner,int axis,boolean positive,long plane,long u0,long v0,long u1,long v1){
        public Face{if(owner<0||axis<0||axis>2||u0>=u1||v0>=v1)throw new IllegalArgumentException("Invalid owned surface rectangle");}}
    /** rgba uses Java ARGB0xAARRGGBB, including the unchanged translucent water alpha. */
    public record Owner(TerrainLodColumns.Box box,int sky,int block,int rgba){
        public Owner{Objects.requireNonNull(box);if(sky<0||sky>15||block<0||block>15)throw new IllegalArgumentException("Exact scalar light required");}}
    public record ChunkBounds(long minX,long minY,long minZ,long maxX,long maxY,long maxZ){}
    /** Canonical64m page indices, independent of camera/target/native publication. */
    public record PageKey(long x,long y,long z){}
    /** Material totals are validated once per immutable page, then reused by full-scene publications. */
    public record Page(PageKey key,Prepared prepared,int opaqueFaces,int waterFaces){
        public Page(PageKey key,Prepared prepared){this(key,prepared,-1,-1);}
        public Page{Objects.requireNonNull(key);Objects.requireNonNull(prepared);int opaque=0,water=0;
            for(var face:prepared.faces())if(prepared.owners().get(face.owner()).box().material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER)water++;else opaque++;
            if((opaqueFaces!=-1||waterFaces!=-1)&&(opaqueFaces!=opaque||waterFaces!=water))throw new IllegalArgumentException("Surface page material count mismatch");
            opaqueFaces=opaque;waterFaces=water;}}
    public record PageSet(List<Page> pages,long vertexBytes,long gpuBytes,int faces,int opaqueFaces,int waterFaces){
        public PageSet{pages=List.copyOf(pages);var keys=new HashSet<PageKey>();long vertices=0,gpu=0;int count=0,opaque=0,water=0;
            for(var page:pages){if(!keys.add(page.key()))throw new IllegalArgumentException("Duplicate canonical surface page");
                vertices=Math.addExact(vertices,page.prepared().vertexBytes());count=Math.addExact(count,page.prepared().faces().size());
                opaque=Math.addExact(opaque,page.opaqueFaces());water=Math.addExact(water,page.waterFaces());}
            gpu=Math.multiplyExact((long)count,GPU_BYTES_PER_FACE);
            if(vertices!=vertexBytes||gpu!=gpuBytes||count!=faces||opaque!=opaqueFaces||water!=waterFaces)throw new IllegalArgumentException("Surface page aggregate mismatch");}}
    public record Geometry(List<TerrainLodColumns.Box> owners,List<Face> faces,List<Integer> visibleOwners,long vertexBytes){
        public Geometry{owners=CompactLodLists.boxes(owners);faces=CompactLodLists.faces(faces);visibleOwners=CompactLodLists.integers(visibleOwners);
            if(vertexBytes!=(long)faces.size()*VERTEX_BYTES_PER_FACE)throw new IllegalArgumentException("Vertex budget mismatch");}}
    /** Faces now reference compact, completely lit owners; no unlit placeholders exist. */
    public record Prepared(List<Owner> owners,List<Face> faces,List<ChunkBounds> chunkBounds,long vertexBytes,List<List<Integer>> contributors){
        public Prepared(List<Owner> owners,List<Face> faces,List<ChunkBounds> chunkBounds,long vertexBytes){
            this(owners,faces,chunkBounds,vertexBytes,faces.stream().map(face->List.of(face.owner())).toList());}
        public Prepared{owners=CompactLodLists.owners(owners);faces=CompactLodLists.faces(faces);chunkBounds=List.copyOf(chunkBounds);contributors=CompactLodLists.contributors(contributors);
            if(vertexBytes!=(long)faces.size()*VERTEX_BYTES_PER_FACE||chunkBounds.size()!=(faces.size()+CHUNK_FACES-1)/CHUNK_FACES)
                throw new IllegalArgumentException("Prepared surface budget mismatch");
            if(contributors.size()!=faces.size())throw new IllegalArgumentException("Surface provenance count differs from faces");
            for(int i=0;i<faces.size();i++){var face=faces.get(i);if(face.owner()>=owners.size())throw new IllegalArgumentException("Unknown representative owner");
                var ids=contributors.get(i);if(ids.isEmpty())throw new IllegalArgumentException("Empty source provenance");int previous=-1;boolean representative=false;
                for(int id:ids){if(id<0||id>=owners.size()||id<=previous)throw new IllegalArgumentException("Invalid source contributor");
                    if(!sameShade(owners.get(face.owner()),owners.get(id)))throw new IllegalArgumentException("Merged contributors differ in physical shading");
                    representative|=id==face.owner();previous=id;}
                if(!representative)throw new IllegalArgumentException("Representative absent from source provenance");}}}

    public static Geometry extract(List<TerrainLodColumns.Box> input,int maxFaces,long maxBytes){
        if(maxFaces<1||maxBytes<GPU_BYTES_PER_FACE)throw new IllegalArgumentException("Positive face and GPU budgets required");
        int limit=(int)Math.min(maxFaces,maxBytes/GPU_BYTES_PER_FACE);
        return extractBounded(input,limit);
    }
    /** A bounded intermediate is not GPU admission: equal lit faces can shrink afterwards. */
    public static Geometry extractForMerge(List<TerrainLodColumns.Box> input,int maxFaces,long maxRetainedBytes){
        if(maxFaces<1||maxRetainedBytes<CPU_FACE_BYTES)throw new IllegalArgumentException("Positive retained CPU face budget required");
        return extractBounded(input,(int)Math.min(maxFaces,maxRetainedBytes/CPU_FACE_BYTES));
    }
    private static Geometry extractBounded(List<TerrainLodColumns.Box> input,int limit){
        var owners=List.copyOf(input);var faces=new ArrayList<Face>();
        var opaque=new ArrayList<OpaqueBoxFaces.Bounds>();
        for(int owner=0;owner<owners.size();owner++){check();var b=owners.get(owner);
            if(b.material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER)continue;
            opaque.add(new OpaqueBoxFaces.Bounds(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive()));}
        // All-opaque input already has identical owner IDs; all-WATER has no opaque faces.
        // Only a mixed input needs a remap, sized to its actual opaque owners.
        int[] original=null;
        if(!opaque.isEmpty()&&opaque.size()!=owners.size()){
            original=new int[opaque.size()];int at=0;
            for(int owner=0;owner<owners.size();owner++)if(owners.get(owner).material()!=dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER)original[at++]=owner;
        }
        // The existing extractor removes only proven opposite cover. Mismatched rectangles
        // are conservatively retained, so cracks, omitted native cuts and water never hide
        // an exposed source face. Source occupancy and original owner bounds stay unchanged.
        OpaqueBoxFaces.Result surface;
        try{surface=OpaqueBoxFaces.extract(opaque,limit);}
        catch(OpaqueBoxFaces.BudgetExceededException exceeded){throw new TerrainLodColumns.BudgetExceededException("Intermediate surface rectangle budget exceeded");}
        for(var f:surface.faces())add(faces,new Face(original==null?f.owner():original[f.owner()],f.axis(),f.positive(),f.plane(),f.u0(),f.v0(),f.u1(),f.v1()),limit);
        for(int owner=0;owner<owners.size();owner++){check();var b=owners.get(owner);
            if(b.material()!=dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER)continue;
            for(int axis=0;axis<3;axis++)for(int sign=0;sign<2;sign++)add(faces,face(b,owner,axis,sign!=0),limit);}
        return geometry(owners,faces);
    }
    public static Prepared prepare(Geometry geometry,IntFunction<Owner> light){
        Objects.requireNonNull(light);var owners=new ArrayList<Owner>();int[] remap=new int[geometry.owners().size()];Arrays.fill(remap,-1);
        for(int index:geometry.visibleOwners()){check();var owner=Objects.requireNonNull(light.apply(index));
            if(!owner.box().equals(geometry.owners().get(index)))throw new IllegalArgumentException("Physical light owner bounds changed");
            remap[index]=owners.size();owners.add(owner);}
        var faces=new ArrayList<Face>(geometry.faces().size());
        for(var f:geometry.faces()){check();if(f.owner()>=remap.length||remap[f.owner()]<0)throw new IllegalArgumentException("Unlit surface owner");
            faces.add(new Face(remap[f.owner()],f.axis(),f.positive(),f.plane(),f.u0(),f.v0(),f.u1(),f.v1()));}
        var merged=greedy(faces,owners);faces=merged.faces();var chunks=new ArrayList<ChunkBounds>();
        for(int start=0;start<faces.size();start+=CHUNK_FACES){long x0=Long.MAX_VALUE,y0=Long.MAX_VALUE,z0=Long.MAX_VALUE,x1=Long.MIN_VALUE,y1=Long.MIN_VALUE,z1=Long.MIN_VALUE;
            for(int i=start;i<Math.min(faces.size(),start+CHUNK_FACES);i++){var f=faces.get(i);
                long a=f.axis()==0?f.plane():f.u0(),b=f.axis()==0?f.plane():f.u1(),c=f.axis()==1?f.plane():f.v0(),d=f.axis()==1?f.plane():f.v1(),
                     e=f.axis()==0?f.u0():f.axis()==1?f.v0():f.plane(),g=f.axis()==0?f.u1():f.axis()==1?f.v1():f.plane();
                x0=Math.min(x0,a);x1=Math.max(x1,b);y0=Math.min(y0,c);y1=Math.max(y1,d);z0=Math.min(z0,e);z1=Math.max(z1,g);}
            chunks.add(new ChunkBounds(x0,y0,z0,x1,y1,z1));}
        return new Prepared(owners,faces,chunks,(long)faces.size()*VERTEX_BYTES_PER_FACE,merged.contributors());
    }
    private record WorkFace(Face face,int provenance){}
    private record Merged(ArrayList<Face> faces,List<List<Integer>> contributors){}
    private static boolean sameShade(Owner a,Owner b){return a.rgba()==b.rgba()&&a.sky()==b.sky()&&a.block()==b.block()&&a.box().material()==b.box().material();}
    private static boolean opaque(Owner owner){return owner.box().material()!=dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER&&(owner.rgba()>>>24)==255;}
    /** Two exact adjacency sweeps never span a gap, overlap or unequal scalar/material. */
    private static Merged greedy(List<Face> faces,List<Owner> owners){
        var work=new ArrayList<WorkFace>(faces.size());for(var face:faces)work.add(new WorkFace(face,-face.owner()-1));
        // Joins form a bounded provenance tree, avoiding quadratic contributor-list copying.
        int[] left=new int[faces.size()],right=new int[faces.size()];int[] joins={0};
        work=sweep(work,owners,true,left,right,joins);work=sweep(work,owners,false,left,right,joins);
        var output=new ArrayList<Face>(work.size());var provenance=new ArrayList<List<Integer>>(work.size());
        int[] stack=new int[32],ids=new int[32];
        for(var item:work){check();output.add(item.face());int root=item.provenance();
            if(root<0){provenance.add(List.of(-root-1));continue;}
            int top=0,count=0;stack[top++]=root;
            while(top>0){int node=stack[--top];if(node<0){if(count==ids.length)ids=Arrays.copyOf(ids,ids.length*2);ids[count++]=-node-1;}
                else{if(top+2>stack.length)stack=Arrays.copyOf(stack,stack.length*2);stack[top++]=left[node];stack[top++]=right[node];}}
            Arrays.sort(ids,0,count);var group=new ArrayList<Integer>();for(int i=0;i<count;i++)if(i==0||ids[i]!=ids[i-1])group.add(ids[i]);provenance.add(List.copyOf(group));}
        return new Merged(output,List.copyOf(provenance));
    }
    private static ArrayList<WorkFace> sweep(ArrayList<WorkFace> work,List<Owner> owners,boolean alongU,int[] left,int[] right,int[] joins){
        work.sort((a,b)->{int key=compareGroup(a.face(),b.face(),owners);if(key!=0)return key;var x=a.face();var y=b.face();
            int c=Long.compare(alongU?x.v0():x.u0(),alongU?y.v0():y.u0());if(c!=0)return c;
            c=Long.compare(alongU?x.v1():x.u1(),alongU?y.v1():y.u1());if(c!=0)return c;
            c=Long.compare(alongU?x.u0():x.v0(),alongU?y.u0():y.v0());if(c!=0)return c;
            c=Long.compare(alongU?x.u1():x.v1(),alongU?y.u1():y.v1());return c!=0?c:Integer.compare(x.owner(),y.owner());});
        var result=new ArrayList<WorkFace>(work.size());
        for(var item:work){check();var b=item.face();if(!result.isEmpty()){var previous=result.get(result.size()-1);var a=previous.face();
                boolean touching=alongU?a.v0()==b.v0()&&a.v1()==b.v1()&&a.u1()==b.u0():a.u0()==b.u0()&&a.u1()==b.u1()&&a.v1()==b.v0();
                if(touching&&opaque(owners.get(a.owner()))&&opaque(owners.get(b.owner()))&&compareGroup(a,b,owners)==0){
                    int join=joins[0]++;left[join]=previous.provenance();right[join]=item.provenance();
                    result.set(result.size()-1,new WorkFace(new Face(Math.min(a.owner(),b.owner()),a.axis(),a.positive(),a.plane(),a.u0(),a.v0(),alongU?b.u1():a.u1(),alongU?a.v1():b.v1()),join));continue;}}
            result.add(item);}
        return result;
    }
    private static int compareGroup(Face a,Face b,List<Owner> owners){int c=Integer.compare(a.axis(),b.axis());if(c!=0)return c;
        c=Boolean.compare(a.positive(),b.positive());if(c!=0)return c;c=Long.compare(a.plane(),b.plane());if(c!=0)return c;
        var x=owners.get(a.owner());var y=owners.get(b.owner());c=Integer.compare(x.box().material().ordinal(),y.box().material().ordinal());if(c!=0)return c;
        c=Integer.compare(x.rgba(),y.rgba());if(c!=0)return c;c=Integer.compare(x.sky(),y.sky());return c!=0?c:Integer.compare(x.block(),y.block());}
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
