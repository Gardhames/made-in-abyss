package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedDeepAirCellCertificateGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void exactRecipeAdmissionAndFinalCarveAreNonVacuous(GameTestHelper h)throws Exception {
        var original=manifest();String before=original.toString();var p=profile(h,original);
        h.assertTrue(p.fingerprint().equals(GeneratedDeepAirCellCertificate.APPROVED_FINGERPRINT),"Verbatim historical profile, not a new proposal");
        var proof=new GeneratedDeepAirCellCertificate(p);
        var source=new GeneratedSkySource(p,Map.of());String identity=source.cacheGeometryIdentity();
        h.assertTrue(source.opacityFast(0,-900,0)==source.opacity(source.stateIdUncached(0,-900,0)),"Real source remains equal to independent full-state oracle");
        h.assertTrue(source.deepAirCellCertificateEnabled()&&source.deepAirCellCertificateHits()>0,"Approved profile actually uses production cell shortcut");
        h.assertTrue(proof.guaranteedAir(0,-900,0,Set.of()),"Approved deep cell proof active; false scaffold fails RED here");
        h.assertTrue(proof.guaranteedAir(380,-300,0,Set.of())&&p.generator().upperShaftOpening().carves(380,-300,0),"Exact final carve adds transition volume");
        h.assertTrue(proof.guaranteedAir(470,0,0,Set.of())&&p.generator().sampleBlock(470,0,0).is(Blocks.AIR),"Upper annulus is real final AIR");
        var changed=original.deepCopy();changed.getAsJsonObject("alexEcology").addProperty("seed",928);
        var other=profile(h,changed);h.assertTrue(other.generator().parameters().equals(p.generator().parameters()),"Different valid recipe retains same geology");
        h.assertTrue(!new GeneratedDeepAirCellCertificate(other).guaranteedAir(0,-900,0,Set.of()),"Exact fingerprint gate refuses changed ecology");
        var otherSource=new GeneratedSkySource(other,Map.of());otherSource.opacityFast(0,-900,0);
        h.assertTrue(!otherSource.deepAirCellCertificateEnabled()&&otherSource.deepAirCellCertificateHits()==0,"Different valid profile does not acquire cell proof");
        var legacy=original.deepCopy();legacy.remove("upperShaftOpening");
        var legacyProfile=profile(h,legacy);
        h.assertTrue(!new GeneratedDeepAirCellCertificate(legacyProfile).guaranteedAir(0,-900,0,Set.of()),"Legacy profile refuses both proofs");
        var legacySource=new GeneratedSkySource(legacyProfile,Map.of());legacySource.opacityFast(0,-900,0);
        h.assertTrue(!legacySource.deepAirCellCertificateEnabled()&&legacySource.deepAirCellCertificateHits()==0,"Legacy source retains exact fallback");
        h.assertTrue(identity.equals(source.cacheGeometryIdentity()),"AIR optimization does not change immutable geometry identity");
        h.assertTrue(original.toString().equals(before)&&p.fingerprint().equals(GeneratedDeepAirCellCertificate.APPROVED_FINGERPRINT),"No persisted identity mutation");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void productionCellProofKeepsGeneratorOnlyDenseAndUnknownAuthority(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var generatorOnly=new GeneratedSkySource(p.generator(),Map.of());
        h.assertTrue(generatorOnly.opacityFast(-1,-900,-1)==generatorOnly.opacity(generatorOnly.stateIdUncached(-1,-900,-1)),"Unbound generator opacity still matches full-state oracle");
        h.assertTrue(!generatorOnly.deepAirCellCertificateEnabled()&&generatorOnly.deepAirCellCertificateHits()==0,"Generator-only constructor has no profile-specific cell certificate");
        var source=new GeneratedSkySource(p,Map.of());
        source.publishDenseOpacity(new VoxelSkyLight.Bounds(0,-900,0,1,1,1),new byte[]{7});
        h.assertTrue(source.opacityFast(0,-900,0)==7&&source.denseOpacityHits()==1,"Completed native density precedes every AIR shortcut");
        h.assertTrue(source.deepAirCellCertificateHits()==0,"Native density does not count as a procedural shortcut");
        source.publishDenseOpacity(new VoxelSkyLight.Bounds(0,-1287,0,1,1,1),new byte[]{0});
        boolean unknown=false;try{source.opacityFast(0,-1287,0);}catch(TerrainUnavailableException expected){unknown=true;}
        h.assertTrue(unknown,"Known-domain refusal precedes density and cell proofs");
        h.assertTrue(source.opacityFast(-1,-900,-1)==source.opacity(source.stateIdUncached(-1,-900,-1)),"Outside density the actual hook remains equal to full-state oracle");
        h.assertTrue(source.deepAirCellCertificateHits()>0,"Actual production cell hook is exercised outside dense authority");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void everyYEverySectorSignedCellSeamsAndRandomMatchFullComposedOracle(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var proof=new GeneratedDeepAirCellCertificate(p);int count=0;var random=new Random(141014);
        h.assertTrue(proof.guaranteedAir(0,-900,0,Set.of()),"Non-vacuous proof before oracle sweep");
        for(int y=-1286;y<=63;y++) {
            for(int sector=0;sector<23;sector++)for(double fraction:new double[]{-.001,0,.499,.999,1.001}) {
                double angle=2*Math.PI*(sector+fraction)/23;
                for(int r:new int[]{149,225,299,375,449,525,589}) {
                    int x=(int)Math.round(r*Math.cos(angle)),z=(int)Math.round(r*Math.sin(angle));
                    if(proof.guaranteedAir(x,y,z,Set.of())){oracle(h,p,x,y,z);count++;}
                }
            }
            for(int i=0;i<4;i++) {
                int x=random.nextInt(1280)-640,z=random.nextInt(1280)-640;
                if(proof.guaranteedAir(x,y,z,Set.of())){oracle(h,p,x,y,z);count++;}
            }
        }
        for(int y:new int[]{-1286,-1285,-1254,-432,-431,-430,-400,-399,-283,-251,-250,-1,0,63})
            for(int x:new int[]{-641,-640,-513,-512,-497,-496,-257,-256,-17,-16,-1,0,15,16,255,256,495,496,511,512,639,640})
                for(int z:new int[]{-513,-512,-17,-16,-1,0,15,16,511,512})
                    if(proof.guaranteedAir(x,y,z,Set.of())){oracle(h,p,x,y,z);count++;}
        h.assertTrue(count>100000,"Meaningful full composed state coverage, including angular wraps and signed seams");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void wholeSavedCubeVetoAndUnknownTargetsRemainAuthoritative(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var proof=new GeneratedDeepAirCellCertificate(p);
        var pristine=new GeneratedSkySource(p,Map.of());
        for(int y:new int[]{Integer.MIN_VALUE,-1287,64,Integer.MAX_VALUE})h.assertTrue(!proof.guaranteedAir(0,y,0,Set.of()),"Unknown/outside proof scope is refused");
        for(int x:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE})h.assertTrue(!proof.guaranteedAir(x,-900,x,Set.of()),"Signed lookup cannot overflow into admission");
        boolean unknown=false;try{pristine.opacityFast(0,-1287,0);}catch(IllegalArgumentException expected){unknown=true;}
        h.assertTrue(unknown,"Known-lower source refusal survives candidate union");
        for(int[] q:new int[][]{{-17,-431,-33},{380,-300,0},{470,0,0}}) {
            h.assertTrue(proof.guaranteedAir(q[0],q[1],q[2],Set.of()),"Exercise each admitted branch before edit");
            var key=key(q[0],q[1],q[2]);
            for(var block:List.of(Blocks.STONE,Blocks.WATER)) {
                int[] states=air();states[index(q[0],q[1],q[2])]=Block.getId(block.defaultBlockState());
                var saved=Map.of(key,states);var source=new GeneratedSkySource(p,saved);
                for(int dy=0;dy<16;dy++)for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
                    h.assertTrue(!proof.guaranteedAir(key.x()*16+dx,key.y()*16+dy,key.z()*16+dz,saved.keySet()),"Entire authoritative cube veto, not only edited cell");
                    int x=key.x()*16+dx,y=key.y()*16+dy,z=key.z()*16+dz;
                    h.assertTrue(source.opacityFast(x,y,z)==source.opacity(source.stateIdUncached(x,y,z)),"Every saved-cube opacity agrees with full-state oracle including AIR");
                }
                h.assertTrue(source.deepAirCellCertificateHits()==0,"Actual cell hook vetoes the whole saved cube");
                h.assertTrue(Block.stateById(source.stateIdUncached(q[0],q[1],q[2])).is(block),"Saved state intact");
                h.assertTrue(source.opacityFast(q[0],q[1],q[2])==source.opacity(Block.getId(block.defaultBlockState())),"Saved full or partial attenuation intact");
            }
        }
        pristine.opacityFast(0,-900,0);
        h.assertTrue(pristine.deepAirCellCertificateHits()>0,"Saved-cube authority does not globally disable the production proof");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void actual32RaysAndQuantizationWithIntersectingSavedCanopy(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());
        var roofKey=key(-24,-392,-40);int[] roof=air();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)roof[(8<<8)|(z<<4)|x]=Block.getId((x%3==0?Blocks.STONE:x%3==1?Blocks.WATER:Blocks.OAK_LEAVES).defaultBlockState());
        var saved=Map.of(roofKey,roof);long hits=0;double[] canopyFraction=new double[2];int mode=0;
        for(var overrides:List.of(Map.<CubeKey,int[]>of(),saved)) {
            var source=new GeneratedSkySource(p,overrides);
            SkyVisibility.VoxelOpacity full=(x,y,z)->source.opacity(source.stateIdUncached(x,y,z));
            SkyVisibility.VoxelOpacity accelerated=source::opacityFast;
            for(int[] q:new int[][]{{-24,-431,-40},{0,-1286,0},{-1,-900,-1},{299,-700,0},{380,-300,0},{470,0,0}}) {
                double expected=SkyVisibility.visibleFraction(q[0],q[1],q[2],source.maxY(),100000,full);
                double actual=SkyVisibility.visibleFraction(q[0],q[1],q[2],source.maxY(),100000,accelerated);
                h.assertTrue(Double.doubleToLongBits(expected)==Double.doubleToLongBits(actual),"Actual32-ray fraction is bit-identical to saved-aware full state");
                h.assertTrue(QuantizedDiffuseSky.level(q[0],q[1],q[2],source.maxY(),100000,full,false)==QuantizedDiffuseSky.level(q[0],q[1],q[2],source.maxY(),100000,accelerated,false),"Actual32-ray quantized level stays exact");
            }
            hits+=source.deepAirCellCertificateHits();
            canopyFraction[mode++]=SkyVisibility.visibleFraction(-24,-431,-40,source.maxY(),100000,full);
        }
        h.assertTrue(hits>100&&canopyFraction[0]!=canopyFraction[1],"Production cell shortcut actually traversed and saved canopy actually occludes rays");h.succeed();
    }
    private static void oracle(GameTestHelper h,GeneratedPreviewProfile p,int x,int y,int z){h.assertTrue(p.generator().sampleBlock(x,y,z).is(Blocks.AIR),"Full immutable composed oracle "+x+","+y+","+z);}
    private static JsonObject manifest()throws Exception {
        try(var in=GeneratedDeepAirCellCertificateGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json")){return JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}
    }
    private static GeneratedPreviewProfile profile(GameTestHelper h,JsonObject j){var b=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));return new GeneratedPreviewProfile(j,j.get("profileId").getAsString(),b);}
    private static CubeKey key(int x,int y,int z){return new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16));}
    private static int index(int x,int y,int z){return (Math.floorMod(y,16)<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16);}
    private static int[] air(){int[] a=new int[4096];Arrays.fill(a,Block.getId(Blocks.AIR.defaultBlockState()));return a;}
}
