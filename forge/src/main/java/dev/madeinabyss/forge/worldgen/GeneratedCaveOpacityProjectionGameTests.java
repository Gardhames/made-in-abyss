package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.*;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.natural.*;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Scalar projection admission uses the untouched generator/full-state oracle.
 * Numeric equality alone cannot prove activation or exactly-once composition. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedCaveOpacityProjectionGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void actualProductionSolidMissesUsePaletteProjectionAndKeepFullStates(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var source=new GeneratedSkySource(p,Map.of());
        int rocks=0;
        for(int y:new int[]{-1286,-1285,-1201,-1009,-945,-901,-705,-609,-433,-432,-431,-400})
            for(int x=-820;x<=820;x+=41)for(int z:new int[]{-257,-17,-1,0,15,16,255}) {
                int expected=source.stateIdUncached(x,y,z);
                h.assertTrue(source.opacityFast(x,y,z)==source.opacity(expected),"Actual scalar equals the independent full decorated state at signed seams");
                h.assertTrue(source.stateIdUncached(x,y,z)==expected,"Projection never seeds a representative state into the full oracle");
                if(p.generator().caveExpansion().sample(x,y,z) instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.ROCK)rocks++;
            }
        h.assertTrue(rocks>100,"The real approved composition contains substantial solid material");
        h.assertTrue(source.caveOpacityProjectionEnabled()&&source.caveOpacityProjectionMode().equals("COMPOSED_CAVE_PALETTE")
            &&source.caveOpacityProjectionRockHits()>100,"Actual source miss path admits real palette-equivalent ROCK rather than an inactive standalone helper");
        h.assertTrue(source.caveOpacityProjectionCoreSamples()>=source.caveOpacityProjectionHits()+source.caveOpacityProjectionContinued(),"Only actual composed entries contribute to execution counters");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void exact32RayBitsQuantizationSegmentsAndSavedCanopyExerciseProductionHook(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var roofKey=key(-24,-392,-40);int[] roof=air();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)roof[(8<<8)|(z<<4)|x]=Block.getId((x%3==0?Blocks.STONE:x%3==1?Blocks.WATER:Blocks.OAK_LEAVES).defaultBlockState());
        long hits=0;double[] fractions=new double[2];int mode=0;
        for(var saved:List.of(Map.<CubeKey,int[]>of(),Map.of(roofKey,roof))) {
            var source=new GeneratedSkySource(p,saved);
            SkyVisibility.VoxelOpacity full=(x,y,z)->source.opacity(source.stateIdUncached(x,y,z));
            for(int[] q:new int[][]{{-24,-431,-40},{0,-900,0},{299,-700,0},{377,-609,-221},{0,-1200,0},{-1,-1286,-1}}) {
                double expected=SkyVisibility.visibleFraction(q[0],q[1],q[2],source.maxY(),100000,full);
                double actual=SkyVisibility.visibleFraction(q[0],q[1],q[2],source.maxY(),100000,source::opacityFast);
                h.assertTrue(Double.doubleToLongBits(expected)==Double.doubleToLongBits(actual),"All actual32-ray binary64 bits agree with full states and saved canopy");
                h.assertTrue(QuantizedDiffuseSky.level(q[0],q[1],q[2],source.maxY(),100000,full,false)==QuantizedDiffuseSky.level(q[0],q[1],q[2],source.maxY(),100000,source::opacityFast,false),"Actual quantized32-ray result is exact");
            }
            for(int[] segment:new int[][]{{-17,-900,-33,16,-900,15},{400,-700,-17,420,-697,16}})
                h.assertTrue(SkyVisibility.isOpenSegment(segment[0],segment[1],segment[2],segment[3],segment[4],segment[5],200,full)
                    ==SkyVisibility.isOpenSegment(segment[0],segment[1],segment[2],segment[3],segment[4],segment[5],200,source::opacityFast),"Signed/tied segment classification stays exact");
            fractions[mode++]=SkyVisibility.visibleFraction(-24,-431,-40,source.maxY(),100000,full);
            hits+=source.caveOpacityProjectionHits();
        }
        h.assertTrue(fractions[0]!=fractions[1],"Saved partial canopy genuinely changes the real ray result");
        concurrentAndCancelledProductionQueries(h,p);
        h.assertTrue(hits>100,"Actual32-ray miss path traverses the new projection substantially");h.succeed();
    }

    private static void concurrentAndCancelledProductionQueries(GameTestHelper h,GeneratedPreviewProfile p)throws Exception {
        // Small per-worker pages force genuine collisions; shared scalar pages and
        // identical irradiance keys exercise the production source, not a fake callback.
        var parallel=sharedSmallPageSource(p);var cancelled=sharedSmallPageSource(p);
        var original=new GeneratedSkySource(p.generator(),Map.of());
        SkyVisibility.VoxelOpacity full=(x,y,z)->original.opacity(original.stateIdUncached(x,y,z));
        int[][] probes={{-17,-431,-33},{378,-610,-222},{377,-609,-221},{-17,-431,-33}};
        int[] direct=new int[probes.length],ambient=new int[probes.length];
        for(int i=0;i<probes.length;i++){var q=probes[i];direct[i]=original.directSkyFast(q[0],q[1],q[2]);ambient[i]=original.ambientSky(q[0],q[1],q[2],full,direct[i]);}
        int[][] solid=new int[16][3];int[] expected=new int[solid.length];
        for(int i=0;i<solid.length;i++){
            solid[i]=new int[]{740+(i%8)*8,-920+(i%4)*8,-32+(i%8)*8};var q=solid[i];expected[i]=full.opacity(q[0],q[1],q[2]);
        }
        var pool=Executors.newFixedThreadPool(4);CountDownLatch ownerExited=new CountDownLatch(1),entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var cancellation=new AtomicReference<Throwable>();var paused=new AtomicBoolean();
        try {
            var work=new ArrayList<Future<Boolean>>();
            for(int job=0;job<16;job++){int index=job;work.add(pool.submit(()->{
                for(int i=0;i<solid.length;i++){int s=(index+i)%solid.length;var q=solid[s];if(parallel.opacityFast(q[0],q[1],q[2])!=expected[s])return false;}
                int a=index%probes.length;var q=probes[a];return parallel.ambientSky(q[0],q[1],q[2],parallel::opacityFast,direct[a])==ambient[a];
            }));}
            for(var future:work)h.assertTrue(future.get(15,TimeUnit.SECONDS),"Actual4-worker colliding/identical source misses and irradiance agree with independent full states");
            h.assertTrue(parallel.caveOpacityProjectionHits()>0&&parallel.sharedOpacityMisses()>0,"Concurrent test enters the real approved projection and shared memo");

            // Pause only AFTER an actual new composed miss INSIDE a source sky flight.
            // Cancelling that owner must remove its failed flight and preserve valid
            // scalar cells; retry cannot observe a fabricated partial SKY result.
            var q=probes[1];var pending=pool.submit(()->{
                try {
                    return cancelled.ambientSky(q[0],q[1],q[2],(x,y,z)->{
                        if(Thread.currentThread().isInterrupted())throw new CancellationException("Controlled ray cancellation");
                        long before=cancelled.caveOpacityProjectionCoreSamples();int value=cancelled.opacityFast(x,y,z);
                        if(cancelled.caveOpacityProjectionCoreSamples()>before&&cancelled.probeFlightEvaluations()>0&&paused.compareAndSet(false,true)){
                            entered.countDown();try{release.await();}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new CancellationException("Controlled approved-source miss cancellation");}
                        }
                        return value;
                    },direct[1]);
                }catch(Throwable failure){cancellation.set(failure);throw failure;}finally{ownerExited.countDown();}
            });
            h.assertTrue(entered.await(10,TimeUnit.SECONDS),"Cancellation reached a real approved composed miss during an actual sky flight");
            pending.cancel(true);h.assertTrue(ownerExited.await(3,TimeUnit.SECONDS),"Cancelled source flight terminates and completes cleanup");
            h.assertTrue(cancellation.get() instanceof CancellationException,"Actual wrapper ray propagates cancellation instead of publishing default SKY");
            long beforeRetry=cancelled.probeFlightEvaluations();
            int retry=cancelled.ambientSky(q[0],q[1],q[2],cancelled::opacityFast,direct[1]);
            h.assertTrue(retry==ambient[1]&&cancelled.probeFlightEvaluations()>beforeRetry,"Fresh actual source retry recomputes the failed flight and matches the full-state oracle");
        }finally{release.countDown();pool.shutdownNow();h.assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS),"Bounded production workers terminate");}
    }
    private static GeneratedSkySource sharedSmallPageSource(GeneratedPreviewProfile p){
        String pages=System.getProperty("mia.generated.opacityPageCapacity"),shared=System.getProperty("mia.generated.sharedOpacity"),flight=System.getProperty("mia.generated.singleFlight");
        try{System.setProperty("mia.generated.opacityPageCapacity","4");System.setProperty("mia.generated.sharedOpacity","true");System.setProperty("mia.generated.singleFlight","true");return new GeneratedSkySource(p,Map.of());}
        finally{restore("mia.generated.opacityPageCapacity",pages);restore("mia.generated.sharedOpacity",shared);restore("mia.generated.singleFlight",flight);}
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void savedWholeCubeDenseAndUnknownPrecedeEveryProjection(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var key=new CubeKey(25,-44,-2);int[] saved=air();
        var lichen=Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.NORTH),true);
        var states=List.of(Blocks.STONE.defaultBlockState(),Blocks.WATER.defaultBlockState(),Blocks.OAK_LEAVES.defaultBlockState(),Blocks.GRASS.defaultBlockState(),lichen,Blocks.OAK_LOG.defaultBlockState());
        for(int i=0;i<saved.length;i++)saved[i]=Block.getId(states.get(i%states.size()));
        var source=new GeneratedSkySource(p,Map.of(key,saved));
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            int px=key.x()*16+x,py=key.y()*16+y,pz=key.z()*16+z;
            h.assertTrue(source.opacityFast(px,py,pz)==source.opacity(source.stateIdUncached(px,py,pz)),"All4096 authoritative saved values win, including transparent/emissive non-AIR states");
            h.assertTrue(source.stateIdUncached(px,py,pz)==saved[(y<<8)|(z<<4)|x],"Saved state identity remains exact");
        }
        h.assertTrue(source.caveOpacityProjectionCoreSamples()==0&&source.caveOpacityProjectionHits()==0,"Whole saved cube veto occurs before the extra composed sampler");
        h.assertTrue(source.opacity(Block.getId(lichen))==0&&source.emission(Block.getId(lichen))>0,"Opacity0 is neither AIR identity nor zero emission");
        source.publishDenseOpacity(new VoxelSkyLight.Bounds(400,-700,-17,1,1,1),new byte[]{7});
        h.assertTrue(source.opacityFast(400,-700,-17)==7&&source.caveOpacityProjectionCoreSamples()==0,"Completed native density remains prior authority");
        source.publishDenseOpacity(new VoxelSkyLight.Bounds(0,-1287,0,1,1,1),new byte[]{0});
        boolean full=false,fast=false;
        try{source.stateIdUncached(0,-1287,0);}catch(TerrainUnavailableException expected){full=true;}
        try{source.opacityFast(0,-1287,0);}catch(TerrainUnavailableException expected){fast=true;}
        h.assertTrue(full&&fast&&source.caveOpacityProjectionCoreSamples()==0,"UNKNOWN refuses before native density or proof");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void rawCustomChangedRecipeAndCapturedOffSwitchRefuseBeforeCore(GameTestHelper h)throws Exception {
        var original=manifest();String before=original.toString();var p=profile(h,original);
        long boundSeed=p.derivedAlexEcologySeed();original.getAsJsonObject("alexEcology").addProperty("seed",Long.MIN_VALUE);
        h.assertTrue(p.derivedAlexEcologySeed()==boundSeed,"Actual Alex seed binding is immutable, not reread from caller-owned JSON");
        var changed=manifest();changed.getAsJsonObject("alexEcology").addProperty("seed",boundSeed+1);
        var other=profile(h,changed);var entries=new AtomicInteger();
        var custom=new ParameterizedTerrainChunkGenerator(p.generator().parameters(),biomes(h),-64,384,(x,y,z)->{entries.incrementAndGet();return new TerrainSample.Known(TerrainMaterial.ROCK);});
        var raw=new GeneratedSkySource(custom,Map.of());
        h.assertTrue(raw.opacityFast(400,-700,-17)==raw.opacity(Block.getId(Blocks.STONE.defaultBlockState()))&&entries.get()==1,"Raw custom source calls its original composition exactly once");
        for(var source:List.of(raw,new GeneratedSkySource(p.generator(),Map.of()),new GeneratedSkySource(other,Map.of()))) {
            for(int[] q:new int[][]{{0,-900,0},{400,-700,-17},{378,-610,-222}})
                h.assertTrue(source.opacityFast(q[0],q[1],q[2])==source.opacity(source.stateIdUncached(q[0],q[1],q[2])),"Unbound/different recipe retains the full oracle");
            h.assertTrue(!source.caveOpacityProjectionEnabled()&&source.caveOpacityProjectionCoreSamples()==0&&source.caveOpacityProjectionHits()==0,"Constructor/fingerprint rejection precedes new-core work");
        }
        String old=System.getProperty("mia.generated.caveOpacityProjection");GeneratedSkySource disabled;
        try {
            System.setProperty("mia.generated.caveOpacityProjection","false");disabled=new GeneratedSkySource(p,Map.of());
            System.setProperty("mia.generated.caveOpacityProjection","true");
            h.assertTrue(!disabled.caveOpacityProjectionEnabled()&&disabled.caveOpacityProjectionMode().equals("DISABLED"),"Switch is captured by each immutable source constructor");
            disabled.opacityFast(400,-700,-17);
            h.assertTrue(disabled.caveOpacityProjectionCoreSamples()==0&&disabled.caveOpacityProjectionHits()==0,"Same-build off source executes the original miss path");
        }finally{restore("mia.generated.caveOpacityProjection",old);}
        h.assertTrue(p.fingerprint().equals(GeneratedDeepAirCellCertificate.APPROVED_FINGERPRINT)&&before.equals(manifest().toString()),"Canonical persisted manifest/profile are unaffected by derived control");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void islandAndUpperModifiersRefuseBeforeDiscardedCaveSample(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());var source=new GeneratedSkySource(p,Map.of());var proof=new GeneratedCaveOpacityProjection(p,source::opacity);
        for(int[] q:new int[][]{{960,-700,0},{-960,-700,0},{0,-399,0},{0,-250,0},{0,63,0}}) {
            h.assertTrue(!proof.mayEnter(q[0],q[1],q[2],Set.of()),"Owned island and final upper cleanup reject before Cave entry");
            h.assertTrue(source.opacityFast(q[0],q[1],q[2])==source.opacity(source.stateIdUncached(q[0],q[1],q[2])),"Final modifier priority matches the unchanged full generator");
        }
        h.assertTrue(source.caveOpacityProjectionCoreSamples()==0,"No discarded extra composition call under final modifiers");
        // A delegated solid point proves this is a region decision, not global disablement.
        h.assertTrue(proof.mayEnter(400,-700,-17,Set.of()),"Actual approved delegated territory remains eligible");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void continuationConsumesSameComposedSampleWithoutTopLevelResample(GameTestHelper h)throws Exception {
        var j=manifest();var p=profile(h,j);var cave=p.generator().caveExpansion();
        var target=new AtomicReference<BlockCoordinate>();var entries=new AtomicInteger();
        var g=new ParameterizedTerrainChunkGenerator(p.generator().parameters(),biomes(h),-64,384,(x,y,z)->{
            var q=target.get();if(q!=null&&q.x()==x&&q.y()==y&&q.z()==z)entries.incrementAndGet();return cave.sample(x,y,z);
        },null,null,null,null,cave,j.getAsJsonObject("alexEcology"),new NaturalProtection(List.of()));
        boolean actualWater=false;
        for(int[] q:new int[][]{{0,-900,0},{400,-700,-17},{378,-610,-222},{379,-611,-222},{-17,-431,-33}}) {
            target.set(new BlockCoordinate(q[0],q[1],q[2]));var composed=cave.sample(q[0],q[1],q[2]);
            if(composed instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.WATER)actualWater=true;
            var expected=g.sampleBlock(q[0],q[1],q[2]);entries.set(0);
            var actual=g.continueCaveSampleForDerivedOpacity(q[0],q[1],q[2],composed);
            h.assertTrue(actual.equals(expected),"Already composed AIR/WATER/solid continues through the actual Cave and Alex instances");
            h.assertTrue(entries.get()==0,"Refused projection must not repeat the top-level composed sample; neighbor support callbacks remain allowed");
            h.assertTrue(g.alexEcology().baselineBlock(q[0],q[1],q[2]).equals(cave.decoration(q[0],q[1],q[2],composed).orElseGet(()->vanilla(composed))),"Actual pre-Alex decorated state, never representative opacity ROCK, enters baseline reuse");
        }
        h.assertTrue(actualWater,"The unchanged approved graph supplies a real post-composed retained basin");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void paletteUniformityIsConditionalAndAirWaterAlwaysContinue(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());int cop=Block.getId(new AlexEcologyBlockStates(true).state(AlexEcologyOverlay.Decoration.COPROLITH));
        var uniform=new GeneratedCaveOpacityProjection(p,id->5);
        h.assertTrue(uniform.opacityFor(TerrainMaterial.SOIL,400,-700,-17)==5&&uniform.opacityFor(TerrainMaterial.GRASS,400,-700,-17)==5
            &&uniform.opacityFor(TerrainMaterial.ROCK,400,-700,-17)==5,"Actual reachable closures return the compiled common scalar, not hardcoded opaque15");
        var partial=new GeneratedCaveOpacityProjection(p,id->id==cop?3:5);int candidates=0,excluded=0;
        var overlay=oreOverlay(p.derivedAlexEcologySeed());
        for(int x=-32;x<32;x++)for(int z=-8;z<8;z++) {
            boolean actualCop=overlay.sampleOverride(x,-701,z,AlexEcologyOverlay.Voxel.ROCK).orElse(null)==AlexEcologyOverlay.Decoration.COPROLITH;
            int actual=partial.opacityFor(TerrainMaterial.ROCK,x,-701,z);
            h.assertTrue(actual==(actualCop?-1:5),"Unequal COP scalar forces same-sample continuation only where COP is still reachable");
            if(actualCop)candidates++;else excluded++;
        }
        h.assertTrue(candidates>0&&excluded>100,"Actual original ore decisions exercise both COP refusal and certified exclusion");
        for(var proof:List.of(uniform,partial))for(var material:List.of(TerrainMaterial.AIR,TerrainMaterial.WATER))
            h.assertTrue(proof.opacityFor(material,400,-700,-17)==-1,"Broad AIR and WATER retain their full continuation even with an artificially uniform table");
        var mismatch=new GeneratedCaveOpacityProjection(p,id->id==Block.getId(Blocks.MOSS_BLOCK.defaultBlockState())?3:5);
        h.assertTrue(mismatch.opacityFor(TerrainMaterial.SOIL,400,-700,-17)==-1&&mismatch.opacityFor(TerrainMaterial.GRASS,400,-700,-17)==-1,"Partial reachable MOSS invalidates each affected closure independently");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void copNecessaryPredicateMatchesOriginalOreBranchAcrossSeedsAndSignedEdges(GameTestHelper h)throws Exception {
        var p=profile(h,manifest());int candidates=0,excluded=0;
        int[] edges={Integer.MIN_VALUE,Integer.MIN_VALUE+1,-17,-16,-5,-4,-1,0,1,3,4,15,16,17,Integer.MAX_VALUE-1,Integer.MAX_VALUE};
        for(long seed:new long[]{p.derivedAlexEcologySeed(),Long.MIN_VALUE,-1,Long.MAX_VALUE}) {
            var overlay=oreOverlay(seed);
            for(int x:edges)for(int y:edges)for(int z:edges) {
                boolean cop=overlay.sampleOverride(x,y,z,AlexEcologyOverlay.Voxel.ROCK).orElse(null)==AlexEcologyOverlay.Decoration.COPROLITH;
                h.assertTrue(GeneratedCaveOpacityProjection.coprolithCandidate(seed,x,y,z)==cop,"Necessary predicate agrees with independent original ore branch, including signed floorDiv and overflow-safe neighbor edges");
                if(cop)candidates++;else excluded++;
            }
        }
        h.assertTrue(candidates>10&&excluded>1000,"Real original branch yields both partial COP and excluded coordinates for non-default seeds");h.succeed();
    }

    private static AlexEcologyOverlay oreOverlay(long seed){
        // Each solid entry has an exposed X face. Vertical support is either blocked
        // above or absent below, so MOSS/MUD cannot mask the original ore decision.
        return new AlexEcologyOverlay(seed,(x,y,z)->(x&1)==0?AlexEcologyOverlay.Voxel.AIR:AlexEcologyOverlay.Voxel.ROCK,(x,y,z)->false,(x,y,z)->new AlexEcologyOverlay.Feature(true,100));
    }
    private static net.minecraft.world.level.block.state.BlockState vanilla(TerrainSample sample){return switch(((TerrainSample.Known)sample).material()){case AIR->Blocks.AIR.defaultBlockState();case ROCK->Blocks.STONE.defaultBlockState();case SOIL->Blocks.DIRT.defaultBlockState();case GRASS->Blocks.GRASS_BLOCK.defaultBlockState();case WATER->Blocks.WATER.defaultBlockState();};}
    private static void restore(String key,String old){if(old==null)System.clearProperty(key);else System.setProperty(key,old);}
    private static JsonObject manifest()throws Exception {try(var in=GeneratedCaveOpacityProjectionGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json")){return JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}}
    private static FixedBiomeSource biomes(GameTestHelper h){return new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));}
    private static GeneratedPreviewProfile profile(GameTestHelper h,JsonObject j){return new GeneratedPreviewProfile(j,j.get("profileId").getAsString(),biomes(h));}
    private static CubeKey key(int x,int y,int z){return new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16));}
    private static int[] air(){int[] a=new int[4096];Arrays.fill(a,Block.getId(Blocks.AIR.defaultBlockState()));return a;}
}
