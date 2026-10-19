package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.VoxelSkyLight;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Uses the actual native export boundary without loading client lighting or renderer classes. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssNativeDensityGameTests {
    private static AbyssNativeDensity.Authority authority(){return new AbyssNativeDensity.Authority("recipe-palette-saved",12,41,6);}
    private static TerrainMaterial material(int state){return state==0?TerrainMaterial.AIR:TerrainMaterial.ROCK;}
    @GameTest(template="empty")
    public static void completeAirAndEverySolidStateTransferWithoutResampling(GameTestHelper h){
        var air=new int[4096];var solid=new int[4096];for(int i=0;i<4096;i++)solid[i]=7+i;
        var cubes=Map.of(new CubeKey(-1,-3,2),solid,new CubeKey(0,-3,2),air);
        var data=AbyssNativeDensity.Data.fromOwned(new VoxelSkyLight.Bounds(-16,-48,32,32,16,16),cubes);
        var snapshot=new AbyssNativeDensity.Snapshot(data,authority(),18,AbyssNativeDensityGameTests::material);
        h.assertTrue(snapshot.wholeCubeKeys(authority()).size()==2,"Complete native AIR and solid cubes must both be present; AIR is not a missing cache entry");
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            int index=(y<<8)|(z<<4)|x;
            h.assertTrue(snapshot.state(authority(),x-16,y-48,z+32).orElse(-1)==solid[index],"Exact full state index survives signed native→LOD transfer");
            h.assertTrue(snapshot.state(authority(),x,y-48,z+32).orElse(-1)==0,"Certified whole AIR remains exact known AIR");
        }
        h.assertTrue(snapshot.material(authority(),-16,-48,32).orElseThrow()==TerrainMaterial.ROCK&&snapshot.material(authority(),15,-33,47).orElseThrow()==TerrainMaterial.AIR,"Worker mapper preserves native exact state/material provenance");
        var solidBounds=new dev.madeinabyss.kernel.lod.TerrainLodColumns.Bounds(-16,-48,32,-1,-33,47);
        for(int step:new int[]{1,2,4,8,16}){
            var oracle=dev.madeinabyss.kernel.lod.TerrainLodColumns.build((x,y,z)->new dev.madeinabyss.kernel.terrain.TerrainSample.Known(material(solid[((int)y&15)<<8|((int)z&15)<<4|((int)x&15)])),solidBounds,
                new dev.madeinabyss.kernel.lod.TerrainLodColumns.Parameters(step,4096,4096));
            h.assertTrue(snapshot.rawRegion(authority(),solidBounds,step,4096).orElseThrow().equals(oracle),"Bulk conversion preserves original column midpoint/order/sample count at every supported XZ step");
        }
        var airRaw=snapshot.rawRegion(authority(),new dev.madeinabyss.kernel.lod.TerrainLodColumns.Bounds(0,-48,32,15,-33,47),1,4096).orElseThrow();
        h.assertTrue(airRaw.boxes().isEmpty()&&airRaw.sampledVoxels()==4096,"Certified AIR is a valid empty RAW artifact with exact samples");
        var four=Map.of(new CubeKey(0,-4,2),air,new CubeKey(0,-3,2),air,new CubeKey(0,-2,2),air,new CubeKey(0,-1,2),air);
        var tile=new AbyssNativeDensity.Snapshot(AbyssNativeDensity.Data.fromOwned(new VoxelSkyLight.Bounds(0,-64,32,16,64,16),four),authority(),18,AbyssNativeDensityGameTests::material);
        var tileBounds=new dev.madeinabyss.kernel.lod.TerrainLodColumns.Bounds(0,-64,32,15,-1,47);
        var tileRaw=tile.rawRegion(authority(),tileBounds,1,16384).orElseThrow();
        h.assertTrue(tileRaw.boxes().isEmpty()&&tileRaw.sampledVoxels()==16384,"Four complete native cubes coalesce to exact empty16x64 tile");
        var missing=new java.util.HashMap<>(four);missing.remove(new CubeKey(0,-2,2));
        var incomplete=new AbyssNativeDensity.Snapshot(AbyssNativeDensity.Data.fromOwned(new VoxelSkyLight.Bounds(0,-64,32,16,64,16),missing),authority(),18,AbyssNativeDensityGameTests::material);
        h.assertTrue(incomplete.rawRegion(authority(),tileBounds,1,16384).isEmpty(),"Missing native quarter never certifies coalesced AIR");
        boolean immutable=false;try{snapshot.wholeCubeKeys(authority()).clear();}catch(UnsupportedOperationException expected){immutable=true;}
        h.assertTrue(immutable,"Exported key collection cannot mutate native state ownership");h.succeed();
    }
    @GameTest(template="empty")
    public static void partialMissingAndUncertifiedCellsStayUnknown(GameTestHelper h){
        var full=new int[4096];Arrays.fill(full,7);var shortCube=new int[4095];var unknown=new int[4096];unknown[55]=-1;
        var data=AbyssNativeDensity.Data.fromOwned(new VoxelSkyLight.Bounds(1,0,0,47,16,16),Map.of(new CubeKey(0,0,0),full,new CubeKey(1,0,0),shortCube,new CubeKey(2,0,0),unknown));
        var snapshot=new AbyssNativeDensity.Snapshot(data,authority(),18,AbyssNativeDensityGameTests::material);
        h.assertTrue(snapshot.wholeCubeKeys(authority()).isEmpty(),"Partially covered edge, malformed and UNKNOWN-containing arrays are not whole-cube certificates");
        for(int x:new int[]{0,1,16,32,48,-1})h.assertTrue(snapshot.state(authority(),x,0,0).isEmpty()&&snapshot.material(authority(),x,0,0).isEmpty(),"Missing/partial must never be substituted by AIR or synthetic ROCK");h.succeed();
    }
    @GameTest(template="empty")
    public static void foreignSourceRevisionEpochAndNativeStampRefuseBeforeMaterialMapping(GameTestHelper h){
        var calls=new AtomicInteger();var data=AbyssNativeDensity.Data.fromOwned(new VoxelSkyLight.Bounds(0,0,0,16,16,16),Map.of(new CubeKey(0,0,0),new int[4096]));
        var snapshot=new AbyssNativeDensity.Snapshot(data,authority(),18,id->{calls.incrementAndGet();return material(id);});
        for(var stale:List.of(new AbyssNativeDensity.Authority("changed-palette-saved",12,41,6),new AbyssNativeDensity.Authority("recipe-palette-saved",13,41,6),
            new AbyssNativeDensity.Authority("recipe-palette-saved",12,42,6),new AbyssNativeDensity.Authority("recipe-palette-saved",12,41,7))){
            h.assertTrue(snapshot.state(stale,0,0,0).isEmpty()&&snapshot.material(stale,0,0,0).isEmpty()&&snapshot.wholeCubeKeys(stale).isEmpty(),"Native array cannot cross any source/epoch/native authority fence");}
        h.assertTrue(calls.get()==0&&snapshot.publicationRevision()==18,"Rejected authority never touches session registry mapper; publication and committed GPU revision are distinct");h.succeed();
    }
}
