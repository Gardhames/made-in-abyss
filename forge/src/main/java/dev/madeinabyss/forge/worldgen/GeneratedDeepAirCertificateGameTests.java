package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.SkyVisibility;
import dev.madeinabyss.kernel.light.QuantizedDiffuseSky;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedDeepAirCertificateGameTests {
    private static final String FINGERPRINT="148e3d835d034f0a195516c95d20cb415d5a275ac5e92ee8a29ef28389642bb0";

    @GameTest(template="empty",timeoutTicks=1200)
    public static void exactOriginalV14OnlyAndUsefulDeepCoverage(GameTestHelper h)throws Exception {
        var original=manifest();String before=original.toString();
        var accepted=profile(h,original);var proof=new GeneratedDeepAirCertificate(accepted);
        h.assertTrue(accepted.fingerprint().equals(FINGERPRINT),"Fixture keeps exact historical fingerprint");
        h.assertTrue(proof.radiusAtY(-430)>=150&&proof.radiusAtY(-900)>=250,"Strong proof covers useful deep radii; false scaffold must fail RED");
        h.assertTrue(proof.guaranteedAir(0,-430,0,Set.of()),"Exact approved profile admits pristine deep central AIR");
        var changed=original.deepCopy();changed.getAsJsonObject("alexEcology").addProperty("seed",928);
        var other=profile(h,changed);
        h.assertTrue(other.generator().parameters().equals(accepted.generator().parameters())&&!other.fingerprint().equals(FINGERPRINT),"Same geology with different valid ecology remains a distinct profile");
        var refused=new GeneratedDeepAirCertificate(other);
        h.assertTrue(refused.radiusAtY(-430)==0&&!refused.guaranteedAir(0,-430,0,Set.of()),"Same metrics cannot admit a different recipe");
        var legacy=original.deepCopy();legacy.remove("upperShaftOpening");
        var old=new GeneratedDeepAirCertificate(profile(h,legacy));
        h.assertTrue(old.radiusAtY(-430)==0&&!old.guaranteedAir(0,-430,0,Set.of()),"Pre-v14 recipe refuses the deep certificate");
        for(int y:new int[]{Integer.MIN_VALUE,-1287,-250,-249,0,Integer.MAX_VALUE})
            h.assertTrue(proof.radiusAtY(y)==0&&!proof.guaranteedAir(0,y,0,Set.of()),"Outside exact deep proof domain stays fallback");
        var otherSource=new GeneratedSkySource(other,Map.of());otherSource.opacityFast(0,-430,0);
        h.assertTrue(!otherSource.deepAirCertificateEnabled()&&otherSource.deepAirCertificateHits()==0,"Different valid profile never enables production shortcut");
        var legacySource=new GeneratedSkySource(profile(h,legacy),Map.of());legacySource.opacityFast(0,-430,0);
        h.assertTrue(!legacySource.deepAirCertificateEnabled()&&legacySource.deepAirCertificateHits()==0,"Pre-v14 source keeps authoritative fallback");
        var source=new GeneratedSkySource(accepted,Map.of());String identity=source.cacheGeometryIdentity();
        h.assertTrue(source.opacityFast(0,-430,0)==source.opacity(source.stateIdUncached(0,-430,0)),"Actual source shortcut agrees with full-state opacity");
        h.assertTrue(source.deepAirCertificateEnabled()&&source.deepAirCertificateHits()+source.deepAirCellCertificateHits()>0,"Approved profile enables the global proof and uses the production AIR hierarchy");
        h.assertTrue(identity.equals(source.cacheGeometryIdentity()),"Derived proof never changes immutable geometry identity");
        h.assertTrue(original.toString().equals(before)&&accepted.fingerprint().equals(FINGERPRINT),"Derived proof never mutates manifest identity");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void productionSourceKeepsGeneratorOnlyFallbackDenseAuthorityAndUnknownRefusal(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var proof=new GeneratedDeepAirCertificate(p);
        var generatorOnly=new GeneratedSkySource(p.generator(),Map.of());
        h.assertTrue(generatorOnly.opacityFast(-1,-430,-1)==generatorOnly.opacity(generatorOnly.stateIdUncached(-1,-430,-1)),"Generator-only source still uses exact full-state fallback");
        h.assertTrue(!generatorOnly.deepAirCertificateEnabled()&&generatorOnly.deepAirCertificateHits()==0,"An unbound generator cannot acquire a profile-specific proof");
        var source=new GeneratedSkySource(p,Map.of());
        h.assertTrue(proof.guaranteedAir(0,-430,0,Set.of()),"Dense priority fixture actually lies inside the deep proof");
        source.publishDenseOpacity(new dev.madeinabyss.kernel.light.VoxelSkyLight.Bounds(0,-430,0,1,1,1),new byte[]{7});
        h.assertTrue(source.opacityFast(0,-430,0)==7&&source.denseOpacityHits()==1,"Completed density is read before any procedural AIR proof");
        h.assertTrue(source.deepAirCertificateHits()==0&&source.deepAirCellCertificateHits()==0,"Dense authority does not also count as a procedural shortcut");
        source.publishDenseOpacity(new dev.madeinabyss.kernel.light.VoxelSkyLight.Bounds(0,-1287,0,1,1,1),new byte[]{0});
        boolean unknown=false;try{source.opacityFast(0,-1287,0);}catch(TerrainUnavailableException expected){unknown=true;}
        h.assertTrue(unknown,"Known-domain guard precedes dense values and all certificates");
        h.assertTrue(source.opacityFast(-1,-430,-1)==source.opacity(source.stateIdUncached(-1,-430,-1)),"Outside completed density the real source still agrees with full-state oracle");
        h.assertTrue(source.deepAirCertificateHits()+source.deepAirCellCertificateHits()>0,"Actual production AIR hierarchy is exercised outside dense authority");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void everyDeepYAngularSignedBoundaryAndRandomUsesFullStateOracle(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var proof=new GeneratedDeepAirCertificate(p);var sky=new GeneratedSkySource(p,Map.of());
        var random=new Random(131486);int checked=0;
        for(int y=-1286;y<=-251;y++) {
            double radius=proof.radiusAtY(y);
            h.assertTrue(Double.isFinite(radius)&&radius>=150&&radius<500,"Every deep Y has bounded useful proof");
            for(int a=0;a<32;a++) {
                double angle=2*Math.PI*a/32;
                for(int offset:new int[]{-2,-1,0,1,2}) {
                    int x=(int)Math.round((radius+offset)*Math.cos(angle)),z=(int)Math.round((radius+offset)*Math.sin(angle));
                    if(proof.guaranteedAir(x,y,z,Set.of())){assertOracle(h,p,sky,x,y,z);checked++;}
                    if((double)x*x+(double)z*z>=radius*radius)
                        h.assertTrue(!proof.guaranteedAir(x,y,z,Set.of()),"Strict squared-radius boundary falls back across quadrants");
                }
            }
            int inner=(int)Math.floor(radius)-2;
            for(int i=0;i<4;i++) {
                int x=random.nextInt(inner*2+1)-inner,z=random.nextInt(inner*2+1)-inner;
                if((double)x*x+(double)z*z<radius*radius){
                    h.assertTrue(proof.guaranteedAir(x,y,z,Set.of()),"Interior randomized cell is admitted");assertOracle(h,p,sky,x,y,z);checked++;
                }
            }
            h.assertTrue(proof.guaranteedAir(0,y,0,Set.of()),"Every-Y centre is admitted");assertOracle(h,p,sky,0,y,0);checked++;
        }
        h.assertTrue(checked>50000,"Substantial independently sampled full-state oracle coverage");
        h.assertTrue(p.fingerprint().equals(FINGERPRINT),"Exact persisted fingerprint survives oracle checks");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void supportHaloEdgesUnknownDomainAndWholeSavedCubeVeto(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var proof=new GeneratedDeepAirCertificate(p);var pristine=new GeneratedSkySource(p,Map.of());
        for(int y:new int[]{-1286,-1285,-1254,-432,-431,-430,-429,-400,-399,-283,-252,-251}) {
            int r=(int)Math.floor(proof.radiusAtY(y))-2;
            h.assertTrue(r>=148,"Halo endpoint exercises a positive strong certificate");
            for(int[] q:new int[][]{{r,0},{-r,0},{0,r},{0,-r},{-17,-33},{17,-33}}) {
                h.assertTrue(proof.guaranteedAir(q[0],y,q[1],Set.of()),"Halo/domain endpoint is actually certified");
                assertOracle(h,p,pristine,q[0],y,q[1]);
            }
        }
        int x=-17,y=-431,z=-33;var key=key(x,y,z);
        for(var block:List.of(Blocks.STONE,Blocks.WATER)) {
            int[] saved=airCube();saved[index(x,y,z)]=Block.getId(block.defaultBlockState());
            var overrides=Map.of(key,saved);var sky=new GeneratedSkySource(p,overrides);
            for(int yy=0;yy<16;yy++)for(int zz=0;zz<16;zz++)for(int xx=0;xx<16;xx++)
                h.assertTrue(!proof.guaranteedAir(key.x()*16+xx,key.y()*16+yy,key.z()*16+zz,overrides.keySet()),"Every cell of saved cube vetoed including procedural AIR");
            for(int yy=0;yy<16;yy++)for(int zz=0;zz<16;zz++)for(int xx=0;xx<16;xx++) {
                int px=key.x()*16+xx,py=key.y()*16+yy,pz=key.z()*16+zz;
                h.assertTrue(sky.opacityFast(px,py,pz)==sky.opacity(sky.stateIdUncached(px,py,pz)),"Every saved-cube cell retains full-state opacity including procedural AIR");
            }
            h.assertTrue(sky.deepAirCertificateHits()==0&&sky.deepAirCellCertificateHits()==0,"Both production shortcuts veto the whole saved cube, not only its changed voxel");
            h.assertTrue(Block.stateById(sky.stateIdUncached(x,y,z)).is(block),"Authoritative saved state preserved");
            h.assertTrue(sky.opacityFast(x,y,z)==sky.opacity(Block.getId(block.defaultBlockState())),"Saved full or partial opacity preserved");
            h.assertTrue(sky.opacityFast(-16,y,z)==sky.opacity(sky.stateIdUncached(-16,y,z)),"Neighboring pristine cube keeps exact opacity");
            h.assertTrue(sky.deepAirCertificateHits()+sky.deepAirCellCertificateHits()>0,"Saved veto does not disable the production AIR hierarchy in neighboring pristine cube");
        }
        h.assertTrue(!proof.guaranteedAir(0,-1287,0,Set.of()),"Unknown lower region is never certified");
        boolean state=false,opacity=false;
        try{pristine.stateIdUncached(0,-1287,0);}catch(IllegalArgumentException expected){state=true;}
        try{pristine.opacityFast(0,-1287,0);}catch(IllegalArgumentException expected){opacity=true;}
        h.assertTrue(state&&opacity,"Both authoritative APIs throw below known deep domain");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void actual32RayFractionAndQuantizedLevelWithSavedCanopy(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());
        var roofKey=key(-17,-400,-33);int[] roof=airCube();
        for(int zz=0;zz<16;zz++)for(int xx=0;xx<16;xx++)
            roof[(8<<8)|(zz<<4)|xx]=Block.getId((xx%3==0?Blocks.STONE:xx%3==1?Blocks.WATER:Blocks.OAK_LEAVES).defaultBlockState());
        var saved=Map.of(roofKey,roof);long hits=0;boolean changed=false;
        for(var overrides:List.of(Map.<CubeKey,int[]>of(),saved)) {
            var source=new GeneratedSkySource(p,overrides);
            SkyVisibility.VoxelOpacity oracle=(x,y,z)->source.opacity(source.stateIdUncached(x,y,z));
            SkyVisibility.VoxelOpacity accelerated=source::opacityFast;
            for(int[] q:new int[][]{{-24,-431,-40},{17,-431,-33},{0,-1286,0},{-1,-900,-1},{149,-700,0}}) {
                double expected=SkyVisibility.visibleFraction(q[0],q[1],q[2],source.maxY(),100000,oracle);
                double actual=SkyVisibility.visibleFraction(q[0],q[1],q[2],source.maxY(),100000,accelerated);
                h.assertTrue(Double.doubleToLongBits(expected)==Double.doubleToLongBits(actual),"Exact32-ray fraction equals saved-aware full-state oracle");
                h.assertTrue(QuantizedDiffuseSky.level(q[0],q[1],q[2],source.maxY(),100000,oracle,false)==QuantizedDiffuseSky.level(q[0],q[1],q[2],source.maxY(),100000,accelerated,false),"Exact quantized32-ray level agrees");
            }
            hits+=source.deepAirCertificateHits()+source.deepAirCellCertificateHits();
            if(!overrides.isEmpty()) {
                var pristine=new GeneratedSkySource(p,Map.of());
                double clear=SkyVisibility.visibleFraction(-24,-431,-40,source.maxY(),100000,(x,y,z)->pristine.opacity(pristine.stateIdUncached(x,y,z)));
                double blocked=SkyVisibility.visibleFraction(-24,-431,-40,source.maxY(),100000,oracle);
                changed=clear!=blocked;
            }
        }
        h.assertTrue(hits>100,"Actual32-ray and quantized production paths really traverse the deep shortcut");
        h.assertTrue(changed,"Saved canopy actually changes ray visibility rather than merely existing off-route");h.succeed();
    }
    private static void assertOracle(GameTestHelper h,GeneratedPreviewProfile p,GeneratedSkySource sky,int x,int y,int z) {
        var state=p.generator().sampleBlock(x,y,z);
        h.assertTrue(state.is(Blocks.AIR),"Unchanged complete generator says AIR at "+x+","+y+","+z);
        h.assertTrue(sky.opacity(Block.getId(state))==0&&sky.opacityFast(x,y,z)==0,"Full-state and current source opacity are zero");
    }
    private static JsonObject manifest()throws Exception {
        try(var in=GeneratedDeepAirCertificateGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json")) {
            return JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static GeneratedPreviewProfile profile(GameTestHelper h,JsonObject j) {
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        return new GeneratedPreviewProfile(j,j.get("profileId").getAsString(),biomes);
    }
    private static CubeKey key(int x,int y,int z){return new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16));}
    private static int index(int x,int y,int z){return (Math.floorMod(y,16)<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16);}
    private static int[] airCube(){int[] a=new int[4096];Arrays.fill(a,Block.getId(Blocks.AIR.defaultBlockState()));return a;}
}
