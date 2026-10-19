package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class T13CompactLodTest {
    private static TerrainLodColumns.Box box(long x){return new TerrainLodColumns.Box(x,-700,-16,x+1,-699,-15,TerrainMaterial.ROCK);}
    @Test void rawResultRetainsLessThanThirtySixBytesPerOrderedBox() {
        var input=new ArrayList<TerrainLodColumns.Box>();for(int i=0;i<20000;i++)input.add(box(i-10000));
        var original=new TerrainLodColumns.Result(input,20000);
        assertSame(input.get(10000),original.boxes().get(10000),"General constructor retains original owner identity");
        var result=CompactLodLists.result(original);
        assertEquals(input,result.boxes());assertEquals(input.hashCode(),result.boxes().hashCode());
        assertTrue(T13RetainedGraph.bytes(result)<36L*input.size(),"raw BOX resident graph="+T13RetainedGraph.bytes(result));
    }
    @Test void geometryRetainsLessThanFortyBytesPerFace() {
        var faces=new ArrayList<SurfaceQuadPlan.Face>();for(int i=0;i<20000;i++)faces.add(new SurfaceQuadPlan.Face(0,i%3,i%2==0,i-10000,-16,-700,-15,-699));
        var geometry=new SurfaceQuadPlan.Geometry(List.of(box(-1)),faces,List.of(0),64L*faces.size());
        assertEquals(faces,geometry.faces());assertEquals(faces.hashCode(),geometry.faces().hashCode());
        assertTrue(T13RetainedGraph.bytes(geometry)<40L*faces.size(),"face resident graph="+T13RetainedGraph.bytes(geometry));
    }
    @Test void preparedRetainsPrimitiveOwnersAndProvenance() {
        var owners=new ArrayList<SurfaceQuadPlan.Owner>();var faces=new ArrayList<SurfaceQuadPlan.Face>();var provenance=new ArrayList<List<Integer>>();
        for(int i=0;i<10000;i++)owners.add(new SurfaceQuadPlan.Owner(box(i),i&15,(i>>>4)&15,0xff123456));
        for(int i=0;i<20000;i++){int owner=i%10000;faces.add(new SurfaceQuadPlan.Face(owner,0,true,1,0,0,1,1));provenance.add(List.of(owner));}
        var chunks=new ArrayList<SurfaceQuadPlan.ChunkBounds>();for(int i=0;i<5;i++)chunks.add(new SurfaceQuadPlan.ChunkBounds(0,0,0,1,1,1));
        var prepared=new SurfaceQuadPlan.Prepared(owners,faces,chunks,faces.size()*64L,provenance);
        assertEquals(owners,prepared.owners());assertEquals(faces,prepared.faces());assertEquals(provenance,prepared.contributors());
        long held=T13RetainedGraph.bytes(prepared);
        assertTrue(held<1_400_000,"prepared resident graph="+held);
    }
    @Test void fullLongBoundsDuplicatesOrderAndInputOwnershipSurvive() {
        var input=new ArrayList<>(List.of(box(Long.MIN_VALUE),box(-1),box(Long.MAX_VALUE-1),box(-1)));
        var result=CompactLodLists.result(new TerrainLodColumns.Result(input,5));var frozen=List.copyOf(input);input.clear();
        assertEquals(frozen,result.boxes());assertEquals(frozen.hashCode(),result.boxes().hashCode());
        assertThrows(UnsupportedOperationException.class,()->result.boxes().set(0,box(0)));
        assertEquals(frozen.subList(1,4),result.boxes().subList(1,4));
        assertThrows(IndexOutOfBoundsException.class,()->result.boxes().get(-1));
    }
    @Test void compositionIncidenceRetainsPrimitiveRootIds()throws Exception {
        var roots=new ArrayList<TerrainLodColumns.Box>();for(int y=0;y<4096;y++)roots.add(new TerrainLodColumns.Box(-16,y,-16,48,y+1,48,TerrainMaterial.ROCK));
        var composition=new IncrementalLodComposition(10000,10000,16_000_000);
        composition.update(roots,new AbyssLodFineLedger.Candidate(List.of(),0),List.of());
        var field=IncrementalLodComposition.class.getDeclaredField("index");field.setAccessible(true);
        long retained=T13RetainedGraph.bytes(field.get(composition));
        assertTrue(retained<350000,"root incidence resident graph="+retained);
    }
    @Test void packedListsPreserveFullLongEndpointsAndMergedContributorRows() {
        var random=new Random(0x13c0ffee);var boxes=new ArrayList<TerrainLodColumns.Box>();var owners=new ArrayList<SurfaceQuadPlan.Owner>();var faces=new ArrayList<SurfaceQuadPlan.Face>();var provenance=new ArrayList<List<Integer>>();
        for(int i=0;i<96;i++){
            long min=i%3==0?Long.MIN_VALUE:i%3==1?Integer.MAX_VALUE:random.nextLong()>>>2,max=i%3==0?Long.MAX_VALUE:min+1;
            var b=new TerrainLodColumns.Box(min,-900,Integer.MIN_VALUE,max,0,(long)Integer.MAX_VALUE+1,TerrainMaterial.ROCK);boxes.add(b);owners.add(new SurfaceQuadPlan.Owner(b,15,9,0xffabcdef));
            faces.add(new SurfaceQuadPlan.Face(i,i%3,i%2==0,min,Long.MIN_VALUE,-900,Long.MAX_VALUE,0));provenance.add(i==0?List.of(0):List.of(i-1,i));
        }
        assertEquals(boxes,CompactLodLists.result(new TerrainLodColumns.Result(boxes,96)).boxes());
        var geometry=new SurfaceQuadPlan.Geometry(boxes,faces,java.util.stream.IntStream.range(0,96).boxed().toList(),96*64L);assertEquals(faces,geometry.faces());
        var prepared=new SurfaceQuadPlan.Prepared(owners,faces,List.of(new SurfaceQuadPlan.ChunkBounds(Long.MIN_VALUE,-900,Integer.MIN_VALUE,Long.MAX_VALUE,0,(long)Integer.MAX_VALUE+1)),96*64L,provenance);
        assertEquals(owners,prepared.owners());assertEquals(faces,prepared.faces());assertEquals(provenance,prepared.contributors());
        var frozen=List.copyOf(provenance);provenance.clear();assertEquals(frozen,prepared.contributors());
        assertThrows(UnsupportedOperationException.class,()->prepared.contributors().get(20).set(0,12));
    }
}
