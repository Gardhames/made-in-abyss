package dev.madeinabyss.kernel.light.gpu;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.volume.VoxelBrick;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Edit transactions are local, coherent, and independent of view/source instance identity. */
final class IncrementalLightVolumeTest {
    private static final IncrementalLightVolume.Authority RULES=new IncrementalLightVolume.Authority("world-fp","generator-code","physical-rules",1);
    private static VoxelBrick changed(int index,int opacity){int[] values=new int[4096];values[index]=opacity;return VoxelBrick.opacityOnly(values);}
    @Test void oneNegativeCubeReadsOnlyThatBrickAndPreservesOtherLight(){
        var reads=new AtomicInteger();var v=new IncrementalLightVolume(RULES,(x,y,z)->{reads.incrementAndGet();return 0;});
        var old=v.snapshot();var key=new CubeKey(-1,-1,-1);var ticket=v.prepare(RULES,Map.of(key,changed(4095,15)));
        assertEquals(4096,reads.get());assertEquals(0,v.snapshot().opacity(-1,-1,-1));
        assertEquals(Set.of(key),ticket.bricks());assertEquals(1,ticket.opacityChanges().size());
        assertEquals(new IncrementalLightVolume.Cell(-1,-1,-1),ticket.opacityChanges().get(0));
        assertEquals(27,ticket.neighborhood().size());assertTrue(ticket.neighborhood().contains(new CubeKey(-2,-2,-2)));
        v.commit(ticket);assertEquals(15,v.snapshot().opacity(-1,-1,-1));assertEquals(0,old.opacity(-1,-1,-1));
        assertEquals(0,v.snapshot().opacity(200,-40,300));assertEquals(1,v.snapshot().brickRevision(key));assertEquals(0,v.snapshot().brickRevision(new CubeKey(10,-1,10)));
    }
    @Test void stateOnlyEditDirtiesParentsWithoutInvalidatingSky(){
        var v=new IncrementalLightVolume(RULES,(x,y,z)->0);int[] states=new int[4096],opacity=new int[4096];states[0]=3;
        var key=new CubeKey(8,-1,7);var ticket=v.prepare(RULES,Map.of(key,VoxelBrick.copyOf(states,opacity)));
        assertTrue(ticket.opacityChanges().isEmpty());assertEquals(4,ticket.parents().size());
        assertTrue(ticket.parents().contains(new IncrementalLightVolume.Parent(3,new CubeKey(1,-1,0))));
        v.commit(ticket);assertEquals(3,v.snapshot().brick(key).stateAt(0));
    }
    @Test void twoEditsCoalesceDuplicateHaloAndParents(){
        var v=new IncrementalLightVolume(RULES,(x,y,z)->0);
        var ticket=v.prepare(RULES,Map.of(new CubeKey(-1,-1,-1),changed(0,15),new CubeKey(0,-1,-1),changed(0,15)));
        assertEquals(2,ticket.opacityChanges().size());assertEquals(36,ticket.neighborhood().size());assertEquals(8,ticket.parents().size());
    }
    @Test void staleTicketCannotOverwriteANewerCommittedEdit(){
        var v=new IncrementalLightVolume(RULES,(x,y,z)->0);var key=new CubeKey(0,0,0);
        var stale=v.prepare(RULES,Map.of(key,changed(0,15)));var current=v.prepare(RULES,Map.of(key,changed(1,15)));
        v.commit(current);assertThrows(IllegalStateException.class,()->v.commit(stale));
        assertEquals(0,v.snapshot().opacity(0,0,0));assertEquals(15,v.snapshot().opacity(1,0,0));
    }
    @Test void unknownInputRefusesTheEntirePatch(){
        var v=new IncrementalLightVolume(RULES,(x,y,z)->0);
        assertThrows(IllegalArgumentException.class,()->v.prepare(RULES,Map.of(new CubeKey(0,0,0),VoxelBrick.uniform(-1,-1))));
        assertEquals(0,v.snapshot().revision());
    }
    @Test void anAbandonedPatchDoesNotMutateEitherSnapshot(){
        var v=new IncrementalLightVolume(RULES,(x,y,z)->0);var old=v.snapshot();v.prepare(RULES,Map.of(new CubeKey(0,0,0),changed(1,15)));
        assertEquals(0,v.snapshot().opacity(1,0,0));assertEquals(0,old.revision());assertEquals(0,v.snapshot().revision());
    }
    @Test void globalRulesRejectButEditsDoNotChangeTheGlobalKey(){
        var v=new IncrementalLightVolume(RULES,(x,y,z)->0);var key=new CubeKey(0,0,0);
        assertThrows(IllegalArgumentException.class,()->v.prepare(new IncrementalLightVolume.Authority("other","generator-code","physical-rules",1),Map.of(key,changed(0,15))));
        v.commit(v.prepare(RULES,Map.of(key,changed(0,15))));assertEquals(RULES,v.authority());
        v.commit(v.prepare(RULES,Map.of(key,changed(0,0))));assertEquals(RULES,v.authority());assertEquals(2,v.snapshot().brickRevision(key));assertEquals(0,v.snapshot().opacity(0,0,0));
    }
    @Test void preparedMapsAndDirtySetsDoNotExposeMutableStorage(){
        var v=new IncrementalLightVolume(RULES,(x,y,z)->0);var changes=new HashMap<CubeKey,VoxelBrick>();var key=new CubeKey(-1,0,0);changes.put(key,changed(0,15));
        var ticket=v.prepare(RULES,changes);changes.clear();assertEquals(1,ticket.bricks().size());
        assertThrows(UnsupportedOperationException.class,()->ticket.bricks().clear());assertThrows(UnsupportedOperationException.class,()->ticket.opacityChanges().clear());
        v.commit(ticket);assertEquals(15,v.snapshot().opacity(-16,0,0));
    }
}
