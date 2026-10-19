package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedIslandAirCertificateGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void farOceanPositiveAirMatchesFullStateOracleAndSignedBoundaries(GameTestHelper h) {
        var profile=fixture(h,true);var generator=profile.generator();var island=generator.islandSurface();
        var certificate=new GeneratedIslandAirCertificate(profile);String fingerprint=profile.fingerprint();
        int checked=0;
        for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1})
            for(int[] p:new int[][]{{2000,0},{2000,17},{1200,1600},{1201,1600},{1415,1415},
                    {1407,1450},{1408,1450},{1409,1450},{12000,12000}})
                for(int y:new int[]{1,2,15,16,63,64,island.maximumSurfaceY(),319}){
                    int x=sx*p[0],z=sz*p[1];
                    h.assertTrue(certificate.guaranteedAir(x,y,z,Set.of()),"Far positive ocean is certified at "+x+","+y+","+z);
                    h.assertTrue(generator.sampleBlock(x,y,z).is(Blocks.AIR),"Certified ocean equals full composed state");checked++;
                }
        var random=new Random(20001415);
        for(int i=0;i<8192;i++){
            int x=random.nextInt(24001)-12000,z=random.nextInt(24001)-12000;
            if((double)x*x+(double)z*z<4000000)continue;
            int y=1+random.nextInt(300);
            h.assertTrue(certificate.guaranteedAir(x,y,z,Set.of()),"Every verified far positive cell is admitted");
            h.assertTrue(generator.sampleBlock(x,y,z).is(Blocks.AIR),"Full state oracle remains AIR across quadrants");checked++;
        }
        for(int x:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE})for(int z:new int[]{Integer.MIN_VALUE,-1,0,1,Integer.MAX_VALUE}){
            h.assertTrue(certificate.guaranteedAir(x,1,z,Set.of()),"Extreme signed far coordinate is safe");
            h.assertTrue(generator.sampleBlock(x,1,z).is(Blocks.AIR),"Extreme full state is AIR");checked++;
        }
        for(int x:new int[]{-1999,-1409,-1408,-1407,1407,1408,1409,1999})
            h.assertTrue(!certificate.guaranteedAir(x,1,0,Set.of()),"Inside conservative R2000 band stays on fallback");
        for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1})
            h.assertTrue(!certificate.guaranteedAir(sx*1199,1,sz*1600,Set.of()),"Signed inside radial boundary stays on fallback");
        for(int y:new int[]{-5001,-5000,-1,0})
            h.assertTrue(!certificate.guaranteedAir(2000,y,0,Set.of()),"Unknown/terrain/water band never becomes inferred AIR");
        h.assertTrue(generator.sampleBlock(2000,0,0).is(Blocks.WATER),"Ocean Y0 is actual water");
        long builds=island.status().get("columnBuilds").getAsLong();
        for(int i=0;i<1024;i++)h.assertTrue(certificate.guaranteedAir(2000+i,1,-2000-i,Set.of()),"Unique far cells stay certified");
        h.assertTrue(island.status().get("columnBuilds").getAsLong()==builds,"Certificate never builds an island column");
        h.assertTrue(checked>8000&&profile.fingerprint().equals(fingerprint),"At least8k states checked without changing persisted identity");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void farOceanSavedCubeAndFull32RayOracleRemainAuthoritative(GameTestHelper h) {
        var profile=fixture(h,true);var generator=profile.generator();var certificate=new GeneratedIslandAirCertificate(profile);
        int x=-2000,y=1,z=-17;var key=new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16));
        h.assertTrue(certificate.guaranteedAir(x,y,z,Set.of()),"Pristine positive far ocean exercises new proof");
        int[] saved=new int[4096];Arrays.fill(saved,Block.getId(Blocks.AIR.defaultBlockState()));
        saved[(Math.floorMod(y,16)<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16)]=Block.getId(Blocks.STONE.defaultBlockState());
        var overrides=Map.of(key,saved);var source=new GeneratedSkySource(profile,overrides);
        h.assertTrue(!certificate.guaranteedAir(x,y,z,overrides.keySet())&&!certificate.guaranteedAir(x,y+1,z,overrides.keySet()),"Saved cube veto includes adjacent procedural AIR");
        h.assertTrue(source.opacityFast(x,y,z)==15&&Block.stateById(source.stateIdUncached(x,y,z)).is(Blocks.STONE),"Saved stone stays authoritative in accelerated and full sources");
        boolean unknown=false;try{source.opacityFast(2000,-5001,0);}catch(IllegalArgumentException unavailable){unknown=true;}
        h.assertTrue(unknown,"Far certificate keeps known-lower refusal");
        var pristine=new GeneratedSkySource(profile,Map.of());
        for(var tested:List.of(pristine,source)){
            dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity oracle=(xx,yy,zz)->tested.opacity(tested.stateIdUncached(xx,yy,zz));
            for(int[] p:new int[][]{{-2000,1,-17},{2000,1,17},{-1415,16,1415},{1200,1,-1600},{12000,1,-12000}}){
                double expected=dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(p[0],p[1],p[2],tested.maxY(),100000,oracle);
                double actual=dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(p[0],p[1],p[2],tested.maxY(),100000,tested::opacityFast);
                h.assertTrue(Double.doubleToLongBits(expected)==Double.doubleToLongBits(actual),"Actual full32-ray fraction is bit-identical to composed-state oracle");
                int expectedLevel=dev.madeinabyss.kernel.light.QuantizedDiffuseSky.level(p[0],p[1],p[2],tested.maxY(),100000,oracle,false);
                h.assertTrue(expectedLevel==dev.madeinabyss.kernel.light.QuantizedDiffuseSky.level(p[0],p[1],p[2],tested.maxY(),100000,tested::opacityFast,false),"Quantized32-ray level remains exact with and without saved stone");
            }
        }
        h.assertTrue(pristine.islandAirCertificateHits()>0,"Ray oracle actually traverses certificate cells");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void certifiedUpperOpeningMatchesFullComposedStateOracle120000Points(GameTestHelper h) {
        var profile=fixture(h,true);var certificate=new GeneratedIslandAirCertificate(profile);var generator=profile.generator();
        String fingerprint=profile.fingerprint();var random=new Random(844011);int certified=0;
        for(int i=0;i<120000;i++){
            int x=random.nextInt(961)-480,z=random.nextInt(961)-480,y=-250+random.nextInt(314);
            if(certificate.guaranteedAir(x,y,z,Set.of())){
                h.assertTrue(generator.sampleBlock(x,y,z).is(Blocks.AIR),"Full composed state is AIR at "+x+","+y+","+z);certified++;
            }
        }
        h.assertTrue(certified>60000,"At least60k certified actual upper-shaft states checked");
        for(int coordinate:new int[]{-500,-440,-439,-1,0,1,439,440,500})
            for(int y:new int[]{-400,-251,-250,-249,-1,0,1,63,64,65}){
                boolean admitted=certificate.guaranteedAir(coordinate,y,0,Set.of());
                if(admitted)h.assertTrue(generator.sampleBlock(coordinate,y,0).is(Blocks.AIR),"Signed radial/Y endpoint remains exact AIR");
            }
        // Test upper-band refusal on inner points: exterior sky has its independent positive rule.
        for(int y:new int[]{-400,-251,64,65})h.assertTrue(!certificate.guaranteedAir(0,y,0,Set.of()),"Inner point outside supported upper band remains fallback");
        h.assertTrue(certificate.guaranteedAir(439,-250,0,Set.of())&&certificate.guaranteedAir(-439,63,0,Set.of()),"Current60m wall bound admits signed439m cardinals");
        h.assertTrue(!certificate.guaranteedAir(440,-250,0,Set.of())&&!certificate.guaranteedAir(-440,63,0,Set.of()),"Outward-rounded440m boundary remains fallback");
        h.assertTrue(profile.fingerprint().equals(fingerprint),"Derived optimization does not change persisted profile identity");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void upperOpeningSavedOverrideAndSkyRaysRemainAuthoritative(GameTestHelper h) {
        var profile=fixture(h,true);var generator=profile.generator();var certificate=new GeneratedIslandAirCertificate(profile);
        int x=-17,y=-100,z=-33;var key=new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16));
        int[] saved=new int[4096];Arrays.fill(saved,Block.getId(Blocks.AIR.defaultBlockState()));
        saved[(Math.floorMod(y,16)<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16)]=Block.getId(Blocks.STONE.defaultBlockState());
        var overrides=Map.of(key,saved);var source=new GeneratedSkySource(profile,overrides);
        h.assertTrue(!certificate.guaranteedAir(x,y,z,overrides.keySet())&&source.opacityFast(x,y,z)==15,"Saved stone vetoes otherwise guaranteed opening AIR");
        h.assertTrue(!certificate.guaranteedAir(x,y-1,z,overrides.keySet()),"Entire saved cube remains conservatively authoritative");
        var pristine=new GeneratedSkySource(profile,Map.of());
        dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity oracle=(xx,yy,zz)->pristine.opacity(pristine.stateIdUncached(xx,yy,zz));
        for(int[] p:new int[][]{{-17,-250,-33},{17,-249,-33},{-33,63,17},{0,-251,0}}){
            double expected=dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(p[0],p[1],p[2],pristine.maxY(),100000,oracle);
            h.assertTrue(expected==dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(p[0],p[1],p[2],pristine.maxY(),100000,pristine::opacityFast),"Actual32-ray fraction preserves certificate traversal");
            int quantized=dev.madeinabyss.kernel.light.QuantizedDiffuseSky.level(p[0],p[1],p[2],pristine.maxY(),100000,oracle,false);
            h.assertTrue(quantized==dev.madeinabyss.kernel.light.QuantizedDiffuseSky.level(p[0],p[1],p[2],pristine.maxY(),100000,pristine::opacityFast,false),"Quantized API agrees with its unchanged-opacity oracle");
        }
        h.assertTrue(pristine.islandAirCertificateHits()>0,"Ray tests actually exercise certificate fast path");h.succeed();
    }
    private static GeneratedPreviewProfile fixture(GameTestHelper h,boolean v14) {
        var manifest=GeneratedPreviewProfileGameTests.manifest();
        manifest.add("caveExpansion",CaveExpansionGeneration.proposal(827,net.minecraftforge.fml.ModList.get().isLoaded("alexscaves")));
        manifest.add("islandSurface",IslandSurfaceGeneration.proposal());
        if(v14)manifest.add("upperShaftOpening",UpperShaftOpeningGeneration.proposal());
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        return new GeneratedPreviewProfile(manifest,"test-generated",biomes);
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void certifiedIslandSkyMatchesFullStateOracle120000Points(GameTestHelper h) {
        var profile=fixture(h,true);String fingerprint=profile.fingerprint();var generator=profile.generator();
        var certificate=new GeneratedIslandAirCertificate(profile);h.assertTrue(certificate.eligible(),"validated v14 cave profile admits only island sky proof");
        var random=new Random(101011);int certified=0,checked=0;int ceiling=generator.islandSurface().maximumSurfaceY();
        for(int i=0;i<120000;i++) {
            int x=random.nextInt(24001)-12000,z=random.nextInt(24001)-12000,y=ceiling+1+random.nextInt(64);
            var oracle=generator.sampleBlock(x,y,z);
            if(certificate.guaranteedAir(x,y,z,Set.of())){h.assertTrue(oracle.is(Blocks.AIR),"certificate is exact AIR at "+x+","+y+","+z);certified++;}
            checked++;
        }
        for(int x:new int[]{Integer.MIN_VALUE,-1281,-1280,-961,-960,-541,-540,-501,-500,-499,-1,0,1,499,500,501,540,541,959,960,961,1280,1281,Integer.MAX_VALUE})
            for(int z:new int[]{-1281,-960,-500,-1,0,1,500,960,1281})for(int y:new int[]{-5001,-1287,-400,-250,-1,0,1,ceiling-1,ceiling,ceiling+1,319,320,Integer.MAX_VALUE}) {
                boolean admitted=certificate.guaranteedAir(x,y,z,Set.of());
                if(admitted)h.assertTrue(generator.sampleBlock(x,y,z).is(Blocks.AIR),"signed ownership/ceiling endpoint remains exact AIR");
                double radiusSquared=(double)x*x+(double)z*z;
                boolean exterior=radiusSquared>=250000,farOcean=y>0&&radiusSquared>=4000000;
                if((exterior&&y<=ceiling&&!farOcean)||y<generator.knownLowerY(x,z))h.assertTrue(!admitted,"unknown and unproven exterior below-ceiling points never become inferred AIR");
            }
        h.assertTrue(checked>=100000&&certified>100000,"full-state oracle has at least100k certified points");
        h.assertTrue(profile.fingerprint().equals(fingerprint),"derived certificate does not change persisted world identity");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void overridesUnknownAndIneligibleRecipesCannotBeCertified(GameTestHelper h) {
        var profile=fixture(h,true);var certificate=new GeneratedIslandAirCertificate(profile);var generator=profile.generator();
        int y=generator.islandSurface().maximumSurfaceY()+1,x=-1000,z=-1000;
        var key=new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16));
        h.assertTrue(certificate.guaranteedAir(x,y,z,Set.of()),"pristine owned sky has certificate");
        int[] saved=new int[4096];Arrays.fill(saved,Block.getId(Blocks.AIR.defaultBlockState()));
        int index=(Math.floorMod(y,16)<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16);saved[index]=Block.getId(Blocks.STONE.defaultBlockState());
        var overrides=Map.of(key,saved);
        h.assertTrue(!certificate.guaranteedAir(x,y,z,overrides.keySet()),"protected saved stone wins over otherwise certain procedural AIR");
        h.assertTrue(Block.stateById(overrides.get(key)[index]).is(Blocks.STONE),"exact saved state retained without mutation");
        var source=new GeneratedSkySource(generator,overrides);
        h.assertTrue(Block.stateById(source.stateIdUncached(x,y,z)).is(Blocks.STONE),"actual saved source oracle preserves protected override");
        h.assertTrue(!certificate.guaranteedAir(0,-1287,0,Set.of())&&!certificate.guaranteedAir(12000,-5001,0,Set.of()),"both spatial unknown lower domains refuse proof");
        boolean unknown=false;try{source.stateIdUncached(12000,-5001,0);}catch(IllegalArgumentException unavailable){unknown=true;}
        h.assertTrue(unknown,"unknown fallback retains source refusal instead of inferred AIR");
        h.assertTrue(!certificate.guaranteedAir(499,y,0,Set.of())&&!certificate.guaranteedAir(-499,y,0,Set.of()),"inner shaft delegates to exact fallback");
        var old=new GeneratedIslandAirCertificate(fixture(h,false));
        h.assertTrue(!old.eligible()&&!old.guaranteedAir(x,y,z,Set.of()),"profile without approved v14 opening stays on fallback");h.succeed();
    }
}
