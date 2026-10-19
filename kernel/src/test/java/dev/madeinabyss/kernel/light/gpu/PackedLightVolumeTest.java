package dev.madeinabyss.kernel.light.gpu;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.volume.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackedLightVolumeTest {
    static void put(Map<VoxelVolumeArchive.PageKey,VoxelVolumeArchive.Page> pages,CubeKey key,VoxelBrick brick){var p=VoxelVolumeArchive.PageKey.ofBrick(key.x(),key.y(),key.z());var old=pages.get(p);var slots=old==null?new VoxelBrick[512]:old.bricks();slots[VoxelVolumeArchive.PageKey.slot(key.x(),key.y(),key.z())]=brick;pages.put(p,new VoxelVolumeArchive.Page(slots,new long[512]));}
    @Test void signedDenseWordsAndPageEdgesAreExact(){var pages=new HashMap<VoxelVolumeArchive.PageKey,VoxelVolumeArchive.Page>();int[] op=new int[4096];for(int i=0;i<4096;i++)op[i]=i%16;
        put(pages,new CubeKey(-1,-1,-9),VoxelBrick.opacityOnly(op));var packed=PackedLightVolume.compile(new CubeKey(-2,-2,-10),new CubeKey(0,0,0),pages,false);
        for(int i=0;i<4096;i++)assertEquals(i%16,packed.lightOpacity(-16+(i&15),-16+(i>>>8),-144+((i>>>4)&15)));
        assertEquals(-1,packed.lightOpacity(0,0,0));assertEquals(-1,packed.lightOpacity(1000,0,0));assertTrue(packed.directory().isReadOnly());}
    @Test void closureRequiresAllBoundaryBricks(){assertThrows(IllegalArgumentException.class,()->PackedLightVolume.compile(new CubeKey(-1,-1,-1),new CubeKey(1,1,1),Map.of(),true));}
    @Test void sealedComponentIsNotCanonicalOpaqueGeometry(){var pages=new HashMap<VoxelVolumeArchive.PageKey,VoxelVolumeArchive.Page>();
        for(int y=-2;y<=2;y++)for(int z=-2;z<=2;z++)for(int x=-2;x<=2;x++)if(Math.abs(x)==2||Math.abs(y)==2||Math.abs(z)==2)put(pages,new CubeKey(x,y,z),VoxelBrick.uniform(-1,15));
        var packed=PackedLightVolume.compile(new CubeKey(-2,-2,-2),new CubeKey(2,2,2),pages,true);
        assertEquals(15,packed.lightOpacity(0,0,0));assertEquals(-1,packed.canonicalOpacity(0,0,0));assertEquals(-1,packed.lightOpacity(1000,0,0));}
    @Test void missingDiagonalFrontierCannotMasqueradeAsSealed(){var pages=new HashMap<VoxelVolumeArchive.PageKey,VoxelVolumeArchive.Page>();
        for(int y=-2;y<=2;y++)for(int z=-2;z<=2;z++)for(int x=-2;x<=2;x++)put(pages,new CubeKey(x,y,z),VoxelBrick.uniform(-1,15));
        put(pages,new CubeKey(0,0,0),VoxelBrick.uniform(-1,0));put(pages,new CubeKey(1,0,1),null);
        assertThrows(IllegalArgumentException.class,()->PackedLightVolume.compile(new CubeKey(-2,-2,-2),new CubeKey(2,2,2),pages,true));}
}
