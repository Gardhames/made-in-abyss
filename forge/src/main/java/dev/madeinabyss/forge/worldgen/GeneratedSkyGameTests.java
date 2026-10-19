package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedSkyGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void denseOpacityHandoffRetainsSavedBlocksAndSourceIsolation(GameTestHelper h) {
        var original=generator(h);var calls=new AtomicInteger();
        var flat=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->{calls.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.AIR);});
        var roof=airCube();for(int z=0;z<16;z++)for(int x=0;x<16;x++)roof[(z<<4)|x]=Block.getId(Blocks.STONE.defaultBlockState());
        var source=new GeneratedSkySource(flat,Map.of(new CubeKey(-1,0,-1),roof));
        var bounds=new dev.madeinabyss.kernel.light.VoxelSkyLight.Bounds(-16,-16,-16,16,32,16);var values=new byte[8192];int index=0;
        for(int y=-16;y<16;y++)for(int z=-16;z<0;z++)for(int x=-16;x<0;x++)values[index++]=(byte)source.opacity(source.stateIdUncached(x,y,z));
        source.publishDenseOpacity(bounds,values);calls.set(0);
        for(int y=-16;y<16;y++)for(int z=-16;z<0;z++)for(int x=-16;x<0;x++)h.assertTrue(source.opacityFast(x,y,z)==(y==0?15:0),"Transferred density retains every exact signed cell and saved roof");
        h.assertTrue(calls.get()==0&&source.denseOpacityHits()==8192,"DH-style source probes reuse native density without generating terrain");
        h.assertTrue(source.denseOpacityResidentBytes()==8192,"Only one transferred density volume is retained");
        source.publishDenseOpacity(new dev.madeinabyss.kernel.light.VoxelSkyLight.Bounds(-1,0,-1,1,1,1),new byte[]{15});
        h.assertTrue(source.denseOpacityResidentBytes()==1&&source.opacityFast(-1,0,-1)==15,"A completed replacement releases the old retained volume");
        source.opacityFast(-1,-10,-1);h.assertTrue(calls.get()>0,"Outside replacement volume uses authoritative fallback instead of old density");
        var water=airCube();water[(15<<4)|15]=Block.getId(Blocks.WATER.defaultBlockState());
        var edited=new GeneratedSkySource(flat,Map.of(new CubeKey(-1,0,-1),water));
        h.assertTrue(edited.denseOpacityResidentBytes()==0,"Changed source does not inherit old density");
        h.assertTrue(edited.opacityFast(-1,0,-1)==edited.opacity(Block.getId(Blocks.WATER.defaultBlockState())),"Saved edit wins in independent source");
        boolean refused=false;try{source.opacityFast(-1,source.minY()-1,-1);}catch(TerrainUnavailableException expected){refused=true;}
        h.assertTrue(refused,"Dense memo cannot imply geometry outside known domain");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void cachedAirCertificatePreservesPartialCanopyAndSavedUpperRoof(GameTestHelper h) {
        var original=generator(h);var calls=new AtomicInteger();
        var flat=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->{calls.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.AIR);});
        var canopy=airCube();canopy[(7<<4)|7]=Block.getId(Blocks.OAK_LEAVES.defaultBlockState());
        canopy[(7<<4)|6]=Block.getId(Blocks.WATER.defaultBlockState());
        var roof=airCube();roof[(15<<4)|15]=Block.getId(Blocks.STONE.defaultBlockState());
        var source=new GeneratedSkySource(flat,Map.of(new CubeKey(-1,0,-1),canopy,new CubeKey(-1,25,-1),roof));
        source.columnExposure(-9,-9);source.columnExposure(-10,-9);source.columnExposure(-1,-1);
        calls.set(0);
        for(int y=1;y<64;y++)h.assertTrue(source.opacityFast(-9,y,-9)==0,"Exact known column above partial tree canopy is clear");
        h.assertTrue(calls.get()==0,"Published exposure certificate eliminates upper-tail terrain resampling");
        h.assertTrue(source.airCertificateHits()==63,"Every uncached certified upper cell was actually bypassed");
        h.assertTrue(source.opacityFast(-9,0,-9)==source.opacity(Block.getId(Blocks.OAK_LEAVES.defaultBlockState())),"First partially opaque tree cell must not be skipped");
        h.assertTrue(source.opacityFast(-10,0,-9)==source.opacity(Block.getId(Blocks.WATER.defaultBlockState())),"Water partial attenuation must not become air");
        h.assertTrue(source.opacityFast(-1,400,-1)==15,"Saved upper roof remains authoritative even above native320");
        for(int y=1;y<64;y++)h.assertTrue(source.opacityFast(-9,y,-9)==source.opacity(source.stateIdUncached(-9,y,-9)),"Certificate equals uncached authoritative oracle");
        boolean refused=false;try{source.opacityFast(-9,source.minY()-1,-9);}catch(TerrainUnavailableException expected){refused=true;}
        h.assertTrue(refused,"Below known domain stays UNKNOWN before certificate lookup");h.succeed();
    }
    @GameTest(template="empty")
    public static void upperSkyCubeWindowsAndSavedRoofsRetainLight(GameTestHelper h) {
        var generator=generator(h);
        var highAir=new CubeKey(-1,25,-1);
        var open=new GeneratedSkySource(generator,Map.of(highAir,airCube()));
        h.assertTrue(open.stateIdFast(-1,400,-1)==Block.getId(Blocks.AIR.defaultBlockState()),"known sky beyond native ceiling stays air");
        for(byte sky:open.directSkySlice(-1,-1,300,160))h.assertTrue(sky==15,"native light halo may cross Y320");
        h.assertTrue(open.maxY()==320,"empty sky visits do not extend diffuse ray scanning");
        var plan=dev.madeinabyss.kernel.cube.NativeRenderDistance.plan(4,-1,400,-1,open.minY(),open.knownMaxY());
        h.assertTrue(plan.lightBounds().minY()+plan.lightBounds().sizeY()>400,"actual native light allocation includes camera above320");
        var roof=airCube();roof[(Math.floorMod(340,16)<<8)|(15<<4)|15]=Block.getId(Blocks.STONE.defaultBlockState());
        var built=new GeneratedSkySource(generator,Map.of(new CubeKey(-1,21,-1),roof,highAir,airCube()));
        h.assertTrue(built.stateIdFast(-1,340,-1)==Block.getId(Blocks.STONE.defaultBlockState()),"actual upper-world edits remain authoritative");
        h.assertTrue(built.directSkyFast(-1,339,-1)==0&&built.directSkyFast(-1,341,-1)==15,"upper-world roof blocks sunlight");
        boolean refused=false;try{new GeneratedSkySource(generator,Map.of(new CubeKey(0,1500,0),airCube()));}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"technical upper domain remains bounded");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void savedNegativeCeilingReceivesActualKnownBottomDiffuse(GameTestHelper h) {
        var original=generator(h);
        var empty=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->new TerrainSample.Known(TerrainMaterial.AIR));
        var saved=airCube();int stone=Block.getId(Blocks.STONE.defaultBlockState());
        for(var pos:new int[][]{{-9,-60,-9},{-9,-59,-9},{-10,-60,-9},{-8,-60,-9},{-9,-60,-10},{-9,-60,-8}})
            saved[(Math.floorMod(pos[1],16)<<8)|(Math.floorMod(pos[2],16)<<4)|Math.floorMod(pos[0],16)]=stone;
        var source=new GeneratedSkySource(empty,Map.of(new CubeKey(-1,-4,-1),saved));
        var box=new dev.madeinabyss.kernel.lod.TerrainLodColumns.Box(-9,-60,-9,-8,-59,-8,TerrainMaterial.ROCK);
        int bottom=source.ambientSky(-9,-61,-9,32);
        h.assertTrue(bottom>0&&source.directSkyFast(-9,-61,-9)==0,"actual bottom AIR receives diffuse while saved overhead blocks direct SKY");
        int light=dev.madeinabyss.kernel.light.LodBoxSkyLight.maximum(box,
                (x,y,z)->source.opacity(source.stateIdFast(x,y,z)),source::directSkyFast,
                (x,y,z)->source.opacity(source.stateIdFast(x,y,z))>=15?0:source.ambientSky(x,y,z,32));
        h.assertTrue(light==bottom,"opaque top/sides cannot hide the only exposed bottom lighting sample");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void temporaryEightRayOverviewCannotPoisonFinalNativeDiffuseCache(GameTestHelper h) {
        var original=generator(h);
        var roof=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->new TerrainSample.Known(y==-60&&x<0?TerrainMaterial.ROCK:TerrainMaterial.AIR));
        var baseline=new GeneratedSkySource(roof,Map.of());var staged=new GeneratedSkySource(roof,Map.of());
        boolean distinct=false;
        // Exact lattice probes avoid hiding genuine8/32-ray differences in interpolation rounding.
        // Compiled ray oracle: (-32,-96) has fractions5/32 versus1/8; (-32,-128)9/32 versus3/8.
        for(var point:new int[][]{{-32,-96,-32},{-32,-128,-32},{-1,-65,-1}}) {
            int x=point[0],y=point[1],z=point[2];
            int exact=baseline.ambientSky(x,y,z,32),preview=staged.ambientSkyPreview(x,y,z,32);
            h.assertTrue(staged.ambientSky(x,y,z,32)==exact,"overview cache cannot replace final32-ray native visibility at signed overhang "+Arrays.toString(point));
            h.assertTrue(staged.directSkyFast(x,y,z)==0,"temporary diffuse lighting never changes physical direct SKY");
            if(preview!=exact)distinct=true;
        }
        h.assertTrue(distinct,"oracle actually exercises distinct8/32-ray irradiance values");h.succeed();
    }
    @GameTest(template="empty")
    public static void derivedCacheIdentityIgnoresPristineVisitsButRetainsEveryEditedVoxel(GameTestHelper h) {
        var generator=generator(h);var key=new CubeKey(-1,-40,-1);
        var pristine=generator.generateCube(key).states();
        var empty=new GeneratedSkySource(generator,Map.of());
        var visited=new GeneratedSkySource(generator,Map.of(key,pristine));
        h.assertTrue(empty.cacheGeometryIdentity().equals(visited.cacheGeometryIdentity()),"visiting unchanged negative cubes must not invalidate the entire distant disk cache");
        var edited=pristine.clone();edited[4095]=Block.getId(Blocks.STONE.defaultBlockState());
        if(edited[4095]==pristine[4095])edited[4095]=Block.getId(Blocks.AIR.defaultBlockState());
        h.assertTrue(!empty.cacheGeometryIdentity().equals(new GeneratedSkySource(generator,Map.of(key,edited)).cacheGeometryIdentity()),"one saved voxel change invalidates derived lighting and geometry");
        h.assertTrue(visited.overrideKeys().contains(key),"cache normalization never discards authoritative saved geometry");h.succeed();
    }
    @GameTest(template="empty")
    public static void cachedWeatherExposureNeverGeneratesAndRetainsExactSignedColumn(GameTestHelper h) {
        var calls=new AtomicInteger();var original=generator(h);
        var counting=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->{calls.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.AIR);});
        var roof=airCube();roof[(15<<4)|15]=Block.getId(Blocks.OAK_LEAVES.defaultBlockState());
        var source=new GeneratedSkySource(counting,Map.of(new CubeKey(-1,-4,-1),roof));
        h.assertTrue(source.cachedColumnExposure(-1,-1).isEmpty(),"cold weather metadata stays explicitly unavailable");
        h.assertTrue(calls.get()==0,"owner-thread weather query never invokes generator");
        var exposure=source.columnExposure(-1,-1);int sampled=calls.get();
        h.assertTrue(exposure.rainHeight()==-63,"rain stops above saved leaves at negative cube seam");
        for(int y:new int[]{-1286,-80,-65,-64,-63,319,320})
            h.assertTrue(exposure.directSky(y)==source.directSkyFast(-1,y,-1),"immutable column has exact direct SKY at Y="+y);
        h.assertTrue(source.cachedColumnExposure(-1,-1).orElseThrow()==exposure,"cached query returns immutable worker result");
        h.assertTrue(calls.get()==sampled,"repeated weather/direct queries do no density sampling");
        boolean refused=false;try{exposure.directSky(-1287);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"weather metadata does not invent SKY below known geometry");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void exactStateMemoMatchesUncachedNativeCanopyAndSkyRays(GameTestHelper h) {
        var sourceGenerator=generator(h);var canopy=airCube();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)canopy[(z<<4)|x]=Block.getId((x%3==0?Blocks.STONE:x%3==1?Blocks.OAK_LEAVES:Blocks.WATER).defaultBlockState());
        var source=new GeneratedSkySource(sourceGenerator,Map.of(new CubeKey(-1,-4,-1),canopy));
        for(int y:new int[]{-1286,-80,-65,-64,-1,0,319,320})for(int x:new int[]{-17,-16,-1,0,16}) {
            int expected=source.stateIdUncached(x,y,-1);
            h.assertTrue(source.stateIdFast(x,y,-1)==expected&&source.stateIdFast(x,y,-1)==expected,"exact repeated signed/native boundary state lookup");
        }
        var cached=(dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity)(x,y,z)->source.opacity(source.stateIdFast(x,y,z));
        var direct=(dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity)(x,y,z)->source.opacity(source.stateIdUncached(x,y,z));
        for(var point:new int[][]{{-8,-65,-8},{-1,-65,-1},{-17,-65,-17}}) {
            double reference=dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(point[0],point[1],point[2],source.maxY(),100000,direct);
            double actual=dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(point[0],point[1],point[2],source.maxY(),100000,cached);
            h.assertTrue(Double.doubleToLongBits(actual)==Double.doubleToLongBits(reference),"32 native SKY rays exact with partial saved leaves/water");
        }
        h.assertTrue(source.stateCacheHits()>0&&source.stateCacheMisses()>0,"exact cache hit/miss diagnostics");
        var calls=new AtomicInteger();
        var counting=new ParameterizedTerrainChunkGenerator(sourceGenerator.parameters(),(FixedBiomeSource)sourceGenerator.getBiomeSource(),-64,384,
                (x,y,z)->{calls.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.AIR);});
        var counted=new GeneratedSkySource(counting,Map.of());
        for(int i=0;i<1000;i++)counted.stateIdFast(-17,-65,-17);
        h.assertTrue(calls.get()==1,"repeated exact ray voxel invokes the actual sampler only once");
        for(int i=0;i<1000;i++)counted.stateIdUncached(-17,-65,-17);
        h.assertTrue(calls.get()==1001,"uncached oracle invokes that same sampler for each query");
        var isolated=new GeneratedSkySource(counting,Map.of(new CubeKey(-2,-5,-2),java.util.stream.IntStream.range(0,4096).map(i->Block.getId(Blocks.STONE.defaultBlockState())).toArray()));
        h.assertTrue(isolated.stateIdFast(-17,-65,-17)!=counted.stateIdFast(-17,-65,-17),"separate immutable sources never share memoized states");
        boolean refused=false;try{source.stateIdFast(-1,-1287,-1);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"unknown lower layer never memoizes air");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void pagedOpacityMatchesSavedCanopyAndIsolatesUnknownSources(GameTestHelper h) {
        var original=generator(h);var calls=new AtomicInteger();
        var counted=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->{calls.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.AIR);});
        var canopy=airCube();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)canopy[(z<<4)|x]=Block.getId((x%3==0?Blocks.STONE:x%3==1?Blocks.OAK_LEAVES:Blocks.WATER).defaultBlockState());
        var source=new GeneratedSkySource(counted,Map.of(new CubeKey(-1,-4,-1),canopy));
        for(int i=0;i<1000;i++)h.assertTrue(source.opacityFast(-17,-65,-17)==0,"exact cached air");
        h.assertTrue(calls.get()==1,"lazy pages sample only the visited cell once");
        for(var point:new int[][]{{-8,-65,-8},{-1,-65,-1},{-17,-65,-17}}){
            double reference=dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(point[0],point[1],point[2],source.maxY(),100000,
                    (x,y,z)->source.opacity(source.stateIdUncached(x,y,z)));
            double actual=dev.madeinabyss.kernel.light.SkyVisibility.visibleFraction(point[0],point[1],point[2],source.maxY(),100000,source::opacityFast);
            h.assertTrue(Double.doubleToLongBits(actual)==Double.doubleToLongBits(reference),"full32-ray native saved partial canopy exact");
        }
        h.assertTrue(source.opacityCacheHits()>0&&source.opacityCacheMisses()>0,"paged opacity diagnostics observed");
        var solid=new GeneratedSkySource(counted,Map.of(new CubeKey(-2,-5,-2),java.util.stream.IntStream.range(0,4096).map(i->Block.getId(Blocks.STONE.defaultBlockState())).toArray()));
        h.assertTrue(solid.opacityFast(-17,-65,-17)==15&&source.opacityFast(-17,-65,-17)==0,"immutable sources do not share cached opacity");
        boolean refused=false;try{source.opacityFast(0,source.minY()-1,0);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"unknown below declared floor never memoizes air");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void proceduralDigestsAvoidRegenerationButNeverHideSavedEdits(GameTestHelper h) throws java.io.IOException {
        var original=generator(h);var calls=new AtomicInteger();
        var counted=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->{calls.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.AIR);});
        var key=new CubeKey(-2,-5,-3);var pristine=airCube();
        var directory=java.nio.file.Files.createTempDirectory("mia-procedural-digest-");
        var cold=new GeneratedSkySource(counted,Map.of(key,pristine));cold.restoreLightCache(directory,"digest-test-profile");
        String expected=new GeneratedSkySource(counted,Map.of()).cacheGeometryIdentity();
        h.assertTrue(cold.cacheGeometryIdentity().equals(expected),"pristine visit keeps previous geometry identity");
        h.assertTrue(calls.get()==4096,"cold digest uses complete actual procedural cube");
        // Identity calculation is read-only; the background path explicitly flushes derived IO.
        cold.persistLightCache();calls.set(0);
        var warm=new GeneratedSkySource(counted,Map.of(key,pristine));warm.restoreLightCache(directory,"digest-test-profile");
        h.assertTrue(calls.get()==0,"warm identity must not regenerate the procedural cube");
        h.assertTrue(warm.cacheGeometryIdentity().equals(expected),"warm identity matches uncached oracle");
        for(int index:new int[]{0,4095}) {
            var edited=pristine.clone();edited[index]=Block.getId(Blocks.STONE.defaultBlockState());calls.set(0);
            var source=new GeneratedSkySource(counted,Map.of(key,edited));source.restoreLightCache(directory,"digest-test-profile");
            h.assertTrue(calls.get()==0,"same procedural baseline reused for edited authoritative cube");
            h.assertTrue(!source.cacheGeometryIdentity().equals(expected),"saved edit cannot be hidden by pristine digest");
            h.assertTrue(source.cacheGeometryIdentity().equals(new GeneratedSkySource(counted,Map.of(key,edited)).cacheGeometryIdentity()),"edited identity equals uncached bytewise oracle");
        }
        calls.set(0);var reverted=new GeneratedSkySource(counted,Map.of(key,pristine));reverted.restoreLightCache(directory,"digest-test-profile");
        h.assertTrue(calls.get()==0&&reverted.cacheGeometryIdentity().equals(expected),"saved edit cannot poison procedural baseline");
        calls.set(0);new GeneratedSkySource(counted,Map.of(key,pristine)).restoreLightCache(directory,"other-digest-profile");
        h.assertTrue(calls.get()==4096,"new profile must regenerate baseline");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void persistedSkyProbesRestoreExactLightAndRejectEditedGeometry(GameTestHelper h) throws java.io.IOException {
        var original=generator(h);var calls=new AtomicInteger();
        var counted=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->{calls.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.AIR);});
        var roof=airCube();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)roof[(z<<4)|x]=Block.getId((x%3==0?Blocks.STONE:x%3==1?Blocks.OAK_LEAVES:Blocks.WATER).defaultBlockState());
        var key=new CubeKey(-1,-4,-1);var overrides=Map.of(key,roof);
        var directory=java.nio.file.Files.createTempDirectory("mia-sky-probe-cache-");
        var cold=new GeneratedSkySource(counted,overrides);cold.restoreLightCache(directory,"test-profile");calls.set(0);
        int expected=cold.ambientSky(-8,-65,-8),coldCalls=calls.get();
        h.assertTrue(cold.cachedVisibilityProbes()>0,"real32-ray native probes were evaluated");
        cold.persistLightCache();h.assertTrue(cold.lightCacheWarning().isEmpty(),"cache write succeeded");
        var restored=new GeneratedSkySource(counted,overrides);restored.restoreLightCache(directory,"test-profile");
        h.assertTrue(restored.lightCacheStatus().equals("HIT"),"fresh immutable source restores checksum-validated probes");
        h.assertTrue(restored.cachedVisibilityProbes()==cold.cachedVisibilityProbes(),"all final/native probes restored");calls.set(0);
        int actual=restored.ambientSky(-8,-65,-8);
        h.assertTrue(actual==expected&&calls.get()<coldCalls,"identical light with fewer actual geometry samples after restart");
        var changed=roof.clone();changed[0]=Block.getId(Blocks.AIR.defaultBlockState());
        var edited=new GeneratedSkySource(counted,Map.of(key,changed));edited.restoreLightCache(directory,"test-profile");
        h.assertTrue(!edited.lightCacheStatus().equals("HIT")&&edited.cachedVisibilityProbes()==0,"one saved changed block prevents stale far-light reuse");
        var otherProfile=new GeneratedSkySource(counted,overrides);otherProfile.restoreLightCache(directory,"other-profile");
        h.assertTrue(!otherProfile.lightCacheStatus().equals("HIT"),"different profile cannot reuse probes");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void denseAmbientOracleMatchesNativeNegativeCanopyAndPartialLowerBoundary(GameTestHelper h) {
        var original=generator(h);var baselineCalls=new AtomicInteger();var acceleratedCalls=new AtomicInteger();
        var baseGenerator=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->{baselineCalls.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.AIR);});
        var fastGenerator=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->{acceleratedCalls.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.AIR);});
        var canopy=airCube();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)canopy[(z<<4)|x]=Block.getId((x%3==0?Blocks.STONE:x%3==1?Blocks.OAK_LEAVES:Blocks.WATER).defaultBlockState());
        var overrides=Map.of(new CubeKey(-1,-4,-1),canopy);
        var baseline=new GeneratedSkySource(baseGenerator,overrides);var fast=new GeneratedSkySource(fastGenerator,overrides);
        int fallbacks=0;boolean reduced=false;
        for(var point:new int[][]{{-8,-65,-8},{-1,-65,-1},{-17,-65,-17},{-8,-1286,-8}}) {
            var bounds=new dev.madeinabyss.kernel.light.VoxelSkyLight.Bounds(-24,Math.max(fast.minY(),point[1]-16),-24,32,32,32);
            var density=new byte[32*32*32];int i=0;
            for(int y=bounds.minY();y<bounds.minY()+32;y++)for(int z=-24;z<8;z++)for(int x=-24;x<8;x++)density[i++]=(byte)fast.opacity(fast.stateIdFast(x,y,z));
            var outside=new AtomicInteger();
            dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity oracle=(x,y,z)-> {
                long xx=(long)x-bounds.minX(),yy=(long)y-bounds.minY(),zz=(long)z-bounds.minZ();
                if(xx>=0&&xx<32&&yy>=0&&yy<32&&zz>=0&&zz<32)return density[(int)(xx+32*(zz+32*yy))];
                outside.incrementAndGet();return fast.opacity(fast.stateIdFast(x,y,z));
            };
            baseline.directSkyFast(point[0],point[1],point[2]);fast.directSkyFast(point[0],point[1],point[2]);
            baselineCalls.set(0);acceleratedCalls.set(0);
            int expected=baseline.ambientSky(point[0],point[1],point[2]);
            int actual=fast.ambientSky(point[0],point[1],point[2],oracle);
            h.assertTrue(fast.ambientSky(point[0],point[1],point[2],oracle,fast.directSkyFast(point[0],point[1],point[2]))==expected,"dense direct SKY seed preserves exact ambient");
            h.assertTrue(actual==expected,"exact8m native ambient at signed/source-boundary point "+Arrays.toString(point));
            if(acceleratedCalls.get()<baselineCalls.get())reduced=true;
            fallbacks+=outside.get();
        }
        h.assertTrue(reduced,"dense lookup eliminates native generator resampling without changing ambient");
        h.assertTrue(fallbacks>0,"out-of-window long visibility rays use exact generator fallback");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void parallelDenseAmbientMatchesSequentialNativePartialCanopy(GameTestHelper h) {
        var original=generator(h);
        var flat=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->new TerrainSample.Known(TerrainMaterial.AIR));
        var roof=airCube();for(int z=0;z<16;z++)for(int x=0;x<16;x++)roof[(z<<4)|x]=Block.getId((x%3==0?Blocks.STONE:x%3==1?Blocks.OAK_LEAVES:Blocks.WATER).defaultBlockState());
        var overrides=Map.of(new CubeKey(-1,-4,-1),roof);
        var serial=new GeneratedSkySource(flat,overrides);var parallel=new GeneratedSkySource(flat,overrides);
        var density=new byte[32*32*32];int index=0;
        for(int y=-80;y<-48;y++)for(int z=-24;z<8;z++)for(int x=-24;x<8;x++)density[index++]=(byte)parallel.opacity(parallel.stateIdFast(x,y,z));
        dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity oracle=(x,y,z)-> {
            long xx=(long)x+24,yy=(long)y+80,zz=(long)z+24;
            return xx>=0&&xx<32&&yy>=0&&yy<32&&zz>=0&&zz<32?density[(int)(xx+32*(zz+32*yy))]:parallel.opacity(parallel.stateIdFast(x,y,z));
        };
        var points=new java.util.ArrayList<int[]>();var expected=new java.util.ArrayList<Integer>();
        for(int z=-15;z<=-1;z+=2)for(int x=-15;x<=-1;x+=2){points.add(new int[]{x,-65,z});expected.add(serial.ambientSky(x,-65,z));}
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var actual=dev.madeinabyss.kernel.concurrent.BoundedOrderedWork.map(points,4,pool,p->parallel.ambientSky(p[0],p[1],p[2],oracle,parallel.directSkyFast(p[0],p[1],p[2])));
            h.assertTrue(actual.equals(expected),"actual64 native canopy queries parallel match serial exactly with shared immutable source/cache");
        }finally {pool.shutdownNow();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void indexedPalettePreservesNativeOpacityEmissionAndRefusesUnknown(GameTestHelper h) {
        var source=new GeneratedSkySource(generator(h),Map.of());
        var palette=new java.util.HashSet<net.minecraft.world.level.block.state.BlockState>(HabitatBlockStates.palette());
        for(var kind:dev.madeinabyss.kernel.terrain.natural.NaturalBlock.values())palette.add(NaturalBlockStates.state(kind));
        palette.add(Blocks.AIR.defaultBlockState());palette.add(Blocks.STONE.defaultBlockState());palette.add(Blocks.WATER.defaultBlockState());
        for(var state:palette){int id=Block.getId(state);h.assertTrue(source.opacity(id)==state.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),"indexed native opacity");h.assertTrue(source.emission(id)==state.getLightEmission(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),"indexed native emission");}
        for(int invalid:new int[]{-1,Integer.MAX_VALUE,Block.getId(Blocks.CHEST.defaultBlockState())}) {
            boolean opacityRefused=false,emissionRefused=false;
            try{source.opacity(invalid);}catch(IllegalArgumentException expected){opacityRefused=true;}
            try{source.emission(invalid);}catch(IllegalArgumentException expected){emissionRefused=true;}
            h.assertTrue(opacityRefused&&emissionRefused,"unknown/unsupported native IDs never become0 light");
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void directSkySlicesPreserveSavedRoofWaterLeavesAndNegativeSeams(GameTestHelper h) {
        var lower=airCube();lower[(15<<4)|15]=Block.getId(Blocks.WATER.defaultBlockState());
        lower[(15<<4)|14]=Block.getId(Blocks.OAK_LEAVES.defaultBlockState());
        lower[(15<<4)|13]=Block.getId(Blocks.STONE.defaultBlockState());
        var upper=airCube();upper[(1<<8)|(15<<4)|12]=Block.getId(Blocks.STONE.defaultBlockState());
        var source=new GeneratedSkySource(generator(h),Map.of(new CubeKey(-1,-4,-1),lower,new CubeKey(-1,5,-1),upper));
        for(int x:new int[]{-1,-2,-3,-4}) {
            var slice=source.directSkySlice(x,-1,-90,50);
            h.assertTrue(slice.length==50,"dense ascending slice length");
            for(int i=0;i<50;i++)h.assertTrue(slice[i]==source.directSkyFast(x,-90+i,-1),"exact direct SKY at signed seam XYZ="+x+","+(-90+i)+",-1");
            for(var window:new int[][]{{-90,50},{-65,2},{-1200,32}}) {
                int min=window[0],height=window[1];var opacity=new byte[height*3];var seeded=new byte[height*3];
                for(int i=0;i<height;i++)opacity[1+i*3]=(byte)source.opacity(source.stateIdFast(x,min+i,-1));
                var original=opacity.clone();var reference=source.directSkySlice(x,-1,min,height);
                dev.madeinabyss.kernel.light.DirectSkyColumnSeed.fill(opacity,seeded,1,3,height,source.directSkyFast(x,min+height-1,-1));
                for(int i=0;i<height;i++)h.assertTrue(seeded[1+i*3]==reference[i],"shared native opacity exact SKY at signed Y="+(min+i));
                h.assertTrue(Arrays.equals(original,opacity),"direct seed cannot mutate authoritative opacity");
            }
        }
        var water=source.directSkySlice(-1,-1,-65,3);
        h.assertTrue(water[2]==15 && water[1]==15-source.opacity(Block.getId(Blocks.WATER.defaultBlockState())) && water[0]==water[1]-1,"water attenuation and indirect air decay");
        var leaf=source.directSkySlice(-2,-1,-65,3);
        h.assertTrue(leaf[2]==15 && leaf[1]==15-source.opacity(Block.getId(Blocks.OAK_LEAVES.defaultBlockState())),"actual native leaf opacity");
        h.assertTrue(source.directSkySlice(-4,-1,-1200,32)[0]==0,"roof outside local slice remains authoritative");h.succeed();
    }
    @GameTest(template="empty")
    public static void directSkySlicesRemainLinearWhenSharedColumnCacheIsSaturated(GameTestHelper h) throws Exception {
        var calls=new AtomicInteger();var original=generator(h);
        var counting=new ParameterizedTerrainChunkGenerator(original.parameters(),(FixedBiomeSource)original.getBiomeSource(),-64,384,
                (x,y,z)->{calls.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.AIR);});
        var source=new GeneratedSkySource(counting,Map.of());source.directSkyFast(5,0,5);
        var field=GeneratedSkySource.class.getDeclaredField("skyColumns");field.setAccessible(true);
        @SuppressWarnings("unchecked") var cache=(Map<dev.madeinabyss.kernel.light.SkyColumnKey,Object>)field.get(source);
        // All seeded keys belong to genuinely identical all-air columns, never to the tested column.
        var known=cache.values().iterator().next();cache.clear();for(int z=0;z<GeneratedSkySource.MAX_SKY_COLUMNS;z++)cache.put(new dev.madeinabyss.kernel.light.SkyColumnKey(0,z),known);
        calls.set(0);var slice=source.directSkySlice(-17,-33,-1000,368);
        h.assertTrue(slice.length==368,"job-local slice is complete even after shared cache admission closes");
        for(byte value:slice)h.assertTrue(value==15,"known open sky never becomes unknown or dark");
        h.assertTrue(calls.get()<=source.maxY()-source.minY()+368,"one global column scan plus one slice pass, never a scan for each Y");
        h.assertTrue(cache.size()==GeneratedSkySource.MAX_SKY_COLUMNS,"saturated cache retains its declared bound");
        h.assertTrue(source.cachedColumnExposure(-17,-33).isPresent(),"newly visited columns replace old derived entries after the cache fills");
        slice[0]=0;h.assertTrue(source.directSkySlice(-17,-33,-1000,368)[0]==15,"caller-owned slice cannot mutate geometry/source");h.succeed();
    }
    @GameTest(template="empty")
    public static void directSkySlicesRejectUnknownOverflowAndHeightBudgets(GameTestHelper h) {
        var source=new GeneratedSkySource(generator(h),Map.of());
        for(int[] range:new int[][]{{-1287,1},{-100,0},{-100,-1},{-1286,25287},{Integer.MAX_VALUE,2},{23999,2}}){
            boolean refused=false;try{source.directSkySlice(-1,-1,range[0],range[1]);}catch(IllegalArgumentException expected){refused=true;}
            h.assertTrue(refused,"invalid or unknown slice bounds rejected before allocation: "+range[0]+"/"+range[1]);
        }
        h.succeed();
    }
    private static ParameterizedTerrainChunkGenerator generator(GameTestHelper h) {
        var p=new TerrainParameters(20261003,new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
                new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),new TerrainParameters.Noise(140,20,60,70));
        return new ParameterizedTerrainChunkGenerator(p,new FixedBiomeSource(h.getLevel().registryAccess()
                .registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)),-64,384);
    }
    private static int[] airCube() {
        int[] values=new int[4096];Arrays.fill(values,Block.getId(Blocks.AIR.defaultBlockState()));return values;
    }
    @GameTest(template="empty")
    public static void openMouthCarriesSkyThroughNativeBoundaryAndDeepFirstLayer(GameTestHelper h) {
        var source=new GeneratedSkySource(generator(h),Map.of());
        for(int y:new int[]{319,64,0,-63,-64,-65,-945,-1200})
            h.assertTrue(source.directSky(-1,y,-1)==15,"open global column carries sky15 at actual Y="+y);
        h.succeed();
    }
    @GameTest(template="empty")
    public static void visitedRoofOutsideLocalWindowShadowsWholeColumnAndRemovalRestoresIt(GameTestHelper h) {
        var states=airCube();states[(1<<8)|(15<<4)|15]=Block.getId(Blocks.STONE.defaultBlockState());
        var key=new CubeKey(-1,5,-1);var caller=new java.util.HashMap<CubeKey,int[]>();caller.put(key,states);
        var source=new GeneratedSkySource(generator(h),caller);caller.clear();
        var cacheIdentity=source.cacheGeometryIdentity();
        h.assertTrue(source.overrideKeys().equals(java.util.Set.of(key)),"published source owns its map after caller mutation");
        boolean immutable=false;try{source.overrideKeys().clear();}catch(UnsupportedOperationException expected){immutable=true;}
        h.assertTrue(immutable,"published override keys cannot mutate the owned map");
        h.assertTrue(source.directSky(-1,82,-1)==15,"sky above roof remains open");
        h.assertTrue(source.directSky(-1,81,-1)==0 && source.directSky(-1,-1200,-1)==0,"saved roof above current window casts a deep shadow");
        h.assertTrue(source.ambientSky(-1,-1200,-1)>0,"a thin overhead obstruction must not erase diffuse light from the visible open sky");
        h.assertTrue(source.directSky(-1,-1200,-1)==0,"render irradiance must not change physical direct-sky exposure");
        states[(1<<8)|(15<<4)|15]=Block.getId(Blocks.AIR.defaultBlockState());
        h.assertTrue(source.directSky(-1,-1200,-1)==0,"published source cannot change with caller array reuse");
        h.assertTrue(cacheIdentity.equals(source.cacheGeometryIdentity()),"cache identity owns immutable arrays");
        var removed=new GeneratedSkySource(generator(h),Map.of(key,states));
        h.assertTrue(!cacheIdentity.equals(removed.cacheGeometryIdentity()),"visited edit invalidates derived disk identity");
        h.assertTrue(removed.directSky(-1,-1200,-1)==15,"new authoritative removal restores sky");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void nativeWaterOpacityAttenuatesSkyAndUnknownLowerLayerIsRefused(GameTestHelper h) {
        var states=airCube();states[(15<<4)|15]=Block.getId(Blocks.WATER.defaultBlockState());
        var source=new GeneratedSkySource(generator(h),Map.of(new CubeKey(-1,-4,-1),states));
        int opacity=Blocks.WATER.defaultBlockState().getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
        h.assertTrue(source.directSky(-1,-63,-1)==15,"above water is lit");
        h.assertTrue(source.directSky(-1,-64,-1)==15-Math.max(1,opacity),"actual native water opacity attenuates sky");
        h.assertTrue(source.directSky(-1,-65,-1)==14-Math.max(1,opacity),"non-direct light continues attenuating through air");
        boolean refused=false;try{source.directSky(-1,-1287,-1);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"unknown next layer must not become darkness or sky15");h.succeed();
    }
}
