package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeometryPatchChangesTest {
    @Test void verifiedSameWorldIgnoresPristineArrivalAndDepartureAtNegativeDepth() {
        var leaving=new CubeKey(-2,-257,-1);var arriving=new CubeKey(-1,-256,-1);
        assertEquals(Set.of(),GeometryPatchChanges.between(14,14,Set.of(arriving),Set.of(leaving)));
    }
    @Test void actualGeometryChangeConservativelyInvalidatesAllAuthorityIncludingDistantUnloadedOverrides() {
        var edited=new CubeKey(-1,-257,2);var remoteSaved=new CubeKey(100,-400,-120);
        assertEquals(Set.of(edited,remoteSaved),GeometryPatchChanges.between(15,14,Set.of(edited),Set.of(edited,remoteSaved)));
        assertEquals(Set.of(edited),GeometryPatchChanges.between(16,15,Set.of(),Set.of(edited)));
    }
    @Test void geometryIdentityIsIndependentOfWhetherResidentKeySetsLookEqual() {
        var key=new CubeKey(Integer.MIN_VALUE,-4096,Integer.MAX_VALUE);
        assertEquals(Set.of(key),GeometryPatchChanges.between(2,1,Set.of(key),Set.of(key)));
        assertEquals(Set.of(),GeometryPatchChanges.between(2,2,Set.of(key),Set.of(key)));
        var result=GeometryPatchChanges.between(3,2,Set.of(key),Set.of());
        assertThrows(UnsupportedOperationException.class,()->result.clear());
    }
    @Test void exactBaseNeedsNoResidentPatchWhileEditsAndReversionUseCanonicalIdentity() {
        var oldEdited=new CubeKey(-3,-257,1);var currentEdited=new CubeKey(9,-300,-2);
        var affected=Set.of(oldEdited,currentEdited);
        assertEquals(Set.of(),GeometryPatchChanges.againstBase("saved-world-A","saved-world-A",affected));
        assertEquals(affected,GeometryPatchChanges.againstBase("saved-world-A","edited-world-B",affected));
        // Returning to the exact base restores its terrain, including the now-removed edit.
        assertEquals(Set.of(),GeometryPatchChanges.againstBase("saved-world-A","saved-world-A",affected));
        // A base captured while edited must patch when those edits are reverted.
        assertEquals(affected,GeometryPatchChanges.againstBase("edited-world-B","saved-world-A",affected));
        assertEquals(affected,GeometryPatchChanges.againstBase(null,"saved-world-A",affected));
    }
}
