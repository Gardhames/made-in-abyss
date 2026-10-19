package dev.madeinabyss.kernel.lod;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.List;

/** Surface-only LOD for the approved island's analytically open outer ocean.
 * Not a collision volume or seabed. Caller validates the island recipe and clips
 * all native/saved overrides; the underlying generator supplies actual depth. */
public final class OceanHorizon {
    private OceanHorizon() {}
    public static List<TerrainLodColumns.Box> plan(TerrainLodColumns.Bounds island,int extent,int seaY) {
        if(extent<2048||extent>32768||seaY<Integer.MIN_VALUE+1||seaY>=Integer.MAX_VALUE
                ||island.minX()>-1280||island.minZ()>-1280||island.maxXInclusive()<1279||island.maxZInclusive()<1279
                ||island.minX()<=-extent||island.minZ()<=-extent||island.maxXInclusive()>=extent||island.maxZInclusive()>=extent)
            throw new IllegalArgumentException("Ocean horizon needs a complete island envelope and extent2048..32768");
        long x0=island.minX(),z0=island.minZ(),x1=(long)island.maxXInclusive()+1,z1=(long)island.maxZInclusive()+1;
        return List.of(box(-extent,-extent,x0,extent,seaY),box(x1,-extent,extent,extent,seaY),
                box(x0,-extent,x1,z0,seaY),box(x0,z1,x1,extent,seaY));
    }
    private static TerrainLodColumns.Box box(long x0,long z0,long x1,long z1,int y) {
        return new TerrainLodColumns.Box(x0,y,z0,x1,(long)y+1,z1,TerrainMaterial.WATER);
    }
}
