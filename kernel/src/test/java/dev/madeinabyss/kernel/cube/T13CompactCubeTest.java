package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.lod.T13RetainedGraph;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class T13CompactCubeTest {
    @Test void keyIsValidatedBeforeMalformedStatePayload(){assertThrows(NullPointerException.class,()->new CubeSnapshot(null,new int[1]));}
    @Test void snapshotRetainsPackedPaletteInsteadOfFullStateArray() {
        int[] states=new int[4096];for(int i=0;i<states.length;i++)states[i]=(i&3)*100003;
        var snapshot=new CubeSnapshot(new CubeKey(-1,-2048,0),states);assertArrayEquals(states,snapshot.states());
        assertTrue(T13RetainedGraph.bytes(snapshot)<1800,"snapshot resident graph="+T13RetainedGraph.bytes(snapshot));
    }
    @Test void residentUniformCubesDoNotKeepFullArrays() {
        var space=new SparseCubeSpace();for(int x=-20;x<20;x++)space.loadEmpty(new CubeKey(x,-10,0));
        assertTrue(T13RetainedGraph.bytes(space)<100_000,"resident graph="+T13RetainedGraph.bytes(space));
    }
    @Test void exactSamplingTilesRetainPackedStatePaletteAndExactOpacity() {
        int[] states=new int[4096];byte[] opacity=new byte[4096];for(int i=0;i<4096;i++){states[i]=(i%5)*100003;opacity[i]=15;}
        var tile=ExactCubeTileCache.Tile.copyOf(states,opacity);
        assertArrayEquals(states,tile.states());for(int i=0;i<4096;i++){assertEquals(states[i],tile.stateAt(i));assertEquals(15,tile.opacityAt(i));}
        assertTrue(T13RetainedGraph.bytes(tile)<2200,"exact tile resident graph="+T13RetainedGraph.bytes(tile));
        assertEquals(16385,tile.residentBytes(),"Preserve existing conservative FIFO admission weights");
    }
    @Test void editingDropsResidentPaletteWhileFrozenSnapshotKeepsItsValues() {
        int[] values=new int[4096];for(int i=0;i<4096;i++)values[i]=i%128;
        var states=CubeStates.copyOf(values);var snapshot=states.snapshotCopy();states.set(27,999999);
        assertEquals(999999,states.get(27));assertArrayEquals(values,snapshot.toArray());
        assertTrue(T13RetainedGraph.bytes(states)<16500,"Edited cube must not keep both direct and palette payloads: "+T13RetainedGraph.bytes(states));
    }
    @Test void signedEditsPaletteGrowthAndOldSnapshotsRemainExact() {
        var random=new Random(0x13c0be);var space=new SparseCubeSpace();var key=new CubeKey(-2,-2048,-1);space.loadEmpty(key);
        var original=space.snapshot(key).orElseThrow();int[] expected=new int[4096];
        for(int i=0;i<9000;i++) {
            int at=random.nextInt(4096),value=i%5==0?Integer.MAX_VALUE:random.nextInt(5000);expected[at]=value;
            var pos=new BlockCoordinate(key.x()*16+(at&15),key.y()*16+(at>>>8),key.z()*16+((at>>>4)&15));
            space.setBlock(pos,value);assertEquals(value,space.block(pos).orElseThrow());
            if(i%127==0)assertArrayEquals(expected,space.snapshot(key).orElseThrow().states());
        }
        assertArrayEquals(new int[4096],original.states());var frozen=space.unload(key).orElseThrow();assertArrayEquals(expected,frozen.states());
        space.load(frozen);assertArrayEquals(expected,space.snapshot(key).orElseThrow().states());
        int[] escaping=frozen.states();Arrays.fill(escaping,42);assertArrayEquals(expected,frozen.states());
    }
}
