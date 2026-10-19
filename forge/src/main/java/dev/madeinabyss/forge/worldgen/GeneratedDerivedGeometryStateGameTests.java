package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.*;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.terrain.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact full-state admission through the production derived-geometry API.
 * The independent oracle remains stateIdUncached; transparent states must never
 * become AIR merely because their opacity is zero. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedDerivedGeometryStateGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void approvedProductionStateShortcutReturnsActualAirWithoutOpacityInference(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var source=new GeneratedSkySource(p,Map.of());
        String identity=source.cacheGeometryIdentity();
        for(int[] q:new int[][]{{0,-900,0},{-17,-431,-33},{380,-300,0},{470,0,0}}) {
            h.assertTrue(p.generator().sampleBlock(q[0],q[1],q[2]).is(Blocks.AIR),"Full composed generator independently says AIR");
            int expected=source.stateIdUncached(q[0],q[1],q[2]);
            h.assertTrue(source.stateIdForDerivedGeometry(q[0],q[1],q[2])==expected,"Actual derived-state API returns the exact registry state");
        }
        h.assertTrue(source.derivedGeometryAirCertificateEnabled()&&source.derivedGeometryAirCertificateHits()>0,"Real production state API exercises the certificate");
        h.assertTrue(source.deepAirCellCertificateHits()==0,"State shortcut has its own counter and does not invoke the opacity shortcut");
        // Opacity density can never stand in for a full block identity.
        source.publishDenseOpacity(new dev.madeinabyss.kernel.light.VoxelSkyLight.Bounds(0,-900,0,1,1,1),new byte[]{7});
        h.assertTrue(source.opacityFast(0,-900,0)==7,"Existing dense opacity authority remains intact");
        h.assertTrue(source.stateIdForDerivedGeometry(0,-900,0)==source.stateIdUncached(0,-900,0),"Full state does not become density7 or a fabricated opaque block");
        h.assertTrue(identity.equals(source.cacheGeometryIdentity()),"Exact geometry identity bytes remain unchanged");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void savedWholeCubePreservesTransparentAndEmissiveStates(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var key=new CubeKey(-2,-27,-3);int[] values=air();
        var blocks=List.of(Blocks.STONE,Blocks.WATER,Blocks.OAK_LEAVES,Blocks.GRASS,Blocks.TORCH);
        for(int i=0;i<blocks.size();i++)values[i]=Block.getId(blocks.get(i).defaultBlockState());
        var source=new GeneratedSkySource(p,Map.of(key,values));
        source.publishDenseOpacity(new dev.madeinabyss.kernel.light.VoxelSkyLight.Bounds(-32,-432,-48,16,16,16),new byte[4096]);
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            int px=key.x()*16+x,py=key.y()*16+y,pz=key.z()*16+z;
            h.assertTrue(source.stateIdForDerivedGeometry(px,py,pz)==source.stateIdUncached(px,py,pz),"Every saved voxel keeps exact state, including unchanged AIR");
        }
        h.assertTrue(source.derivedGeometryAirCertificateHits()==0,"The whole saved cube vetoes admission rather than only non-AIR voxels");
        for(int i=0;i<blocks.size();i++)h.assertTrue(source.stateIdForDerivedGeometry(-32+i,-432,-48)==Block.getId(blocks.get(i).defaultBlockState()),"Zero-opacity native density cannot erase saved transparent or emissive state");
        h.assertTrue(source.opacity(Block.getId(Blocks.GRASS.defaultBlockState()))==0,"The fixture contains a non-AIR state with zero opacity");
        h.assertTrue(source.emission(Block.getId(Blocks.TORCH.defaultBlockState()))>0,"The fixture contains real emission despite zero opacity");
        source.stateIdForDerivedGeometry(0,-900,0);
        h.assertTrue(source.derivedGeometryAirCertificateHits()>0,"Saved authority does not disable pristine certificates globally");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void generatorOnlyChangedFingerprintAndUnknownStayAuthoritative(GameTestHelper h)throws Exception {
        var original=manifest();String before=original.toString();var p=profile(h,original);
        var changed=original.deepCopy();changed.getAsJsonObject("alexEcology").addProperty("seed",928);
        var other=profile(h,changed);var legacy=original.deepCopy();legacy.remove("upperShaftOpening");
        for(var source:List.of(new GeneratedSkySource(p.generator(),Map.of()),new GeneratedSkySource(other,Map.of()),new GeneratedSkySource(profile(h,legacy),Map.of()))) {
            for(int[] q:new int[][]{{0,-900,0},{-17,-431,-33},{380,-300,0},{470,0,0},{608,-320,0}})
                h.assertTrue(source.stateIdForDerivedGeometry(q[0],q[1],q[2])==source.stateIdUncached(q[0],q[1],q[2]),"Unbound or different recipe uses the exact full-state oracle");
            h.assertTrue(!source.derivedGeometryAirCertificateEnabled()&&source.derivedGeometryAirCertificateHits()==0,"Strict constructor/fingerprint gate remains non-vacuous");
        }
        var source=new GeneratedSkySource(p,Map.of());
        source.publishDenseOpacity(new dev.madeinabyss.kernel.light.VoxelSkyLight.Bounds(0,-1287,0,1,1,1),new byte[]{0});
        boolean derived=false,oracle=false;
        try{source.stateIdForDerivedGeometry(0,-1287,0);}catch(TerrainUnavailableException expected){derived=true;}
        try{source.stateIdUncached(0,-1287,0);}catch(TerrainUnavailableException expected){oracle=true;}
        h.assertTrue(derived&&oracle&&source.derivedGeometryAirCertificateHits()==0,"UNKNOWN refuses before density/certificate and cannot count as AIR");
        h.assertTrue(before.equals(original.toString())&&p.fingerprint().equals(GeneratedDeepAirCellCertificate.APPROVED_FINGERPRINT),"Manifest and persisted profile remain immutable");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void nativeDensityCaptureKeepsFullStateArraysAndEpochReuse(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var source=new GeneratedSkySource(p,Map.of());
        for(var key:List.of(new CubeKey(-2,-57,-3),new CubeKey(-2,-27,-3),new CubeKey(23,-20,0),new CubeKey(38,-20,0))) {
            var full=CubeDensityReuse.capture(key,17,Long.MIN_VALUE,Map.of(),source::stateIdUncached);
            var actual=CubeDensityReuse.capture(key,17,Long.MIN_VALUE,Map.of(),source::stateIdForDerivedGeometry);
            h.assertTrue(!actual.reused()&&Arrays.equals(full.states(),actual.states()),"Actual native-density callback preserves all4096 exact block IDs");
            long prior=source.derivedGeometryAirCertificateHits();
            var reused=CubeDensityReuse.capture(key,17,17,Map.of(key,actual.states()),source::stateIdForDerivedGeometry);
            h.assertTrue(reused.reused()&&reused.states()==actual.states()&&source.derivedGeometryAirCertificateHits()==prior,"Same-epoch complete density is reused without another procedural lookup");
        }
        h.assertTrue(source.derivedGeometryAirCertificateHits()>100,"Real native capture exercises certified state admission substantially");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void lodSamplerGridsKeepExactStatesAndRawIntervalsAtSignedBoundaries(GameTestHelper h)throws Exception {
        var source=new GeneratedSkySource(profile(h,manifest()),Map.of());
        // Independent full-state comparison happens at every coordinate the real LOD
        // builder requests; its AIR/ROCK classifier is deliberately simple, not copied
        // from DH's material mapper. Existing kernel tests own the grid contract.
        for(var b:List.of(new TerrainLodColumns.Bounds(-33,-432,-49,14,-401,-2),new TerrainLodColumns.Bounds(350,-336,-17,397,-287,30)))
            for(int step:new int[]{1,2,4,8,16}) {
                var parameters=new TerrainLodColumns.Parameters(step,2_000_000,100_000);
                var expected=TerrainLodColumns.build((x,y,z)->known(source.stateIdUncached(x,y,z)),b,parameters);
                var actual=TerrainLodColumns.build((x,y,z)->{
                    int id=source.stateIdForDerivedGeometry(x,y,z);
                    h.assertTrue(id==source.stateIdUncached(x,y,z),"Every actual RAW grid coordinate has the exact full state");
                    return known(id);
                },b,parameters);
                h.assertTrue(expected.equals(actual),"Exact states retain RAW intervals/sample counts at1/2/4/8/16m grids");
            }
        h.assertTrue(source.derivedGeometryAirCertificateHits()>100,"Real LOD builder callback exercises AIR-state admission");h.succeed();
    }

    private static TerrainSample known(int state){return new TerrainSample.Known(Block.stateById(state).isAir()?TerrainMaterial.AIR:TerrainMaterial.ROCK);}
    private static int[] air(){int[] values=new int[4096];Arrays.fill(values,Block.getId(Blocks.AIR.defaultBlockState()));return values;}
    private static JsonObject manifest()throws Exception {
        try(var in=GeneratedDerivedGeometryStateGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json")){return JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}
    }
    private static GeneratedPreviewProfile profile(GameTestHelper h,JsonObject manifest){var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));return new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),biomes);}
}
