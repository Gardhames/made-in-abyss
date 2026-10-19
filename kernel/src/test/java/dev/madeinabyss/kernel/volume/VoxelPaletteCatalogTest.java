package dev.madeinabyss.kernel.volume;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class VoxelPaletteCatalogTest {
    private static VoxelPalette.Entry state(String name,int opacity){return new VoxelPalette.Entry(name,opacity,0,false,false,true,TerrainMaterial.ROCK);}
    @Test void addingAPlayerStateNeverReindexesExistingLazyStateBricks(){
        var base=new VoxelPalette(List.of(state("minecraft:stone",15),state("minecraft:tuff",15)));var catalog=new VoxelPaletteCatalog(base,"rules");
        int stone=catalog.index("minecraft:stone"),tuff=catalog.index("minecraft:tuff");var before=catalog.entries();
        int newId=catalog.intern(state("minecraft:andesite",15));
        assertEquals(2,newId);assertEquals(stone,catalog.index("minecraft:stone"));assertEquals(tuff,catalog.index("minecraft:tuff"));
        assertEquals(2,before.size());assertEquals(3,catalog.entries().size());assertEquals("rules",catalog.globalRules());
    }
    @Test void anExistingNameWithDifferentPhysicalRulesCannotBeSilentlyRebound(){
        var catalog=new VoxelPaletteCatalog(new VoxelPalette(List.of(state("minecraft:stone",15))),"rules");
        assertEquals(0,catalog.intern(state("minecraft:stone",15)));
        assertThrows(IllegalStateException.class,()->catalog.intern(state("minecraft:stone",0)));
        assertEquals(1,catalog.entries().size());
    }
    @Test void zeroOpacityDoesNotTurnANonAirStateIntoAir(){
        var catalog=new VoxelPaletteCatalog(new VoxelPalette(List.of(state("minecraft:stone",15))),"rules");
        int id=catalog.intern(state("minecraft:glass",0));assertFalse(catalog.entries().get(id).air());assertEquals(0,catalog.entries().get(id).opacity());
        assertEquals("minecraft:glass",catalog.entries().get(id).state());
    }
}
