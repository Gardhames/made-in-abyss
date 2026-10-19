package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.worldgen.GeneratedSkySource;
import dev.madeinabyss.forge.worldgen.ParameterizedTerrainChunkGenerator;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraftforge.gametest.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodScalarPipelineGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void realPhysicalPipelineFreshReopenHasZeroLightCalculationsAndSameTerminalOracle(GameTestHelper h)throws Exception {
        var directory=directory("warm-");var generator=generator(h);com.google.gson.JsonObject expected;
        try(var cold=new AbyssLodPipeline(true,directory)){
            cold.request(input(generator,()->"fixture-resource-A"));var terminal=AbyssLodStreamingGameTests.complete(cold,20);
            expected=LodSpeedGameTests.oracle(terminal);
            h.assertTrue(number(cold,"actualLightJobs")>0,"Cold path really evaluates physical original32");
            var archive=(Map<?,?>)cold.status().get("scalarArchive");
            h.assertTrue(((Number)archive.get("writes")).longValue()>0&&((Number)archive.get("dirtyPages")).longValue()==0,"Terminal proposal force-flushes every computed scalar before ACK");
        }
        // close is intentionally asynchronous: a second Pipeline must fence that previous writer.
        try(var warm=new AbyssLodPipeline(true,directory)){
            warm.request(input(generator,()->"fixture-resource-A"));var terminal=AbyssLodStreamingGameTests.complete(warm,20);
            h.assertTrue(number(warm,"actualLightJobs")==0,"Fresh physical source and Pipeline reuse every exact scalar from disk");
            h.assertTrue(Boolean.TRUE.equals(warm.status().get("scalarSealHit")),"Warm source recognizes the exact previously completed view");
            h.assertTrue(expected.equals(LodSpeedGameTests.oracle(terminal)),"Cold/warm terminal geometry, fine grids and exact light oracle match");
            var archive=(Map<?,?>)warm.status().get("scalarArchive");h.assertTrue(((Number)archive.get("diskHits")).longValue()>0,"Warm success is a real disk restore");
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void completedSealWithMissingArchivePagesFallsBackToExactTerminalLight(GameTestHelper h)throws Exception {
        var coldDirectory=directory("sealed-cold-");var damagedDirectory=directory("sealed-missing-pages-");var generator=generator(h);
        com.google.gson.JsonObject expected;
        try(var cold=new AbyssLodPipeline(true,coldDirectory)){
            cold.request(input(generator,()->"fixture-resource-A"));expected=LodSpeedGameTests.oracle(AbyssLodStreamingGameTests.complete(cold,20));
        }
        // Copy only the durable seal into this test's fresh directory. This models
        // every owner page being lost, without touching any source/world save data.
        var seals=coldDirectory.resolve("abyss-physical-scalars").resolve("completed-views");int copied=0;
        try(var files=Files.walk(seals)){for(var source:files.filter(Files::isRegularFile).toList()){
            var destination=damagedDirectory.resolve(coldDirectory.relativize(source));Files.createDirectories(destination.getParent());Files.copy(source,destination);copied++;
        }}
        h.assertTrue(copied>0,"Cold completion writes a durable seal after scalar checkpoint");
        try(var damaged=new AbyssLodPipeline(true,damagedDirectory)){
            damaged.request(input(generator,()->"fixture-resource-A"));var terminal=AbyssLodStreamingGameTests.complete(damaged,20);
            h.assertTrue(Boolean.TRUE.equals(damaged.status().get("scalarSealHit")),"A valid completion seal is present even though owner pages were lost");
            h.assertTrue(number(damaged,"actualLightJobs")>0,"Missing terminal owners are recomputed with exact original32");
            h.assertTrue(!Boolean.TRUE.equals(damaged.status().get("scalarMissDeferral")),"Source convergence disables deferral before terminal completion");
            h.assertTrue(expected.equals(LodSpeedGameTests.oracle(terminal)),"Missing archive pages cannot weaken terminal geometry or exact light");
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=900)
    public static void unavailableResourceAuthorityDisablesDiskAndStillCompletesExactPhysicalLight(GameTestHelper h)throws Exception {
        try(var pipeline=new AbyssLodPipeline(true,directory("unverified-resource-"))){
            pipeline.request(input(generator(h),()->{throw new AbyssLodResourceIdentity.UnavailableException(new IOException("fixture unsupported pack"));}));
            var terminal=AbyssLodStreamingGameTests.complete(pipeline,20);
            h.assertTrue(terminal.requestedComplete()&&number(pipeline,"actualLightJobs")>0,"Unknown resource identity still completes exact cold evaluation");
            h.assertTrue(((Map<?,?>)pipeline.status().get("scalarArchive")).isEmpty(),"No unverified scalar archive is opened");
            h.assertTrue(String.valueOf(pipeline.status().get("scalarArchiveError")).startsWith("DISABLED_UNVERIFIED_RESOURCE"),"Explicit unverified resource diagnostic is preserved");
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=900)
    public static void failedTerminalScalarFlushPreservesPartialSceneButRefusesCompletion(GameTestHelper h)throws Exception {
        var directory=directory("flush-refused-");var scalar=directory.resolve("abyss-physical-scalars");Files.createDirectories(scalar);
        var blocker=scalar.resolve("owner-pages-v1");Files.writeString(blocker,"owned test obstruction");
        var pipeline=new AbyssLodPipeline(true,directory);boolean acknowledged=false;
        try{
            pipeline.request(input(generator(h),()->"fixture-resource-A"));long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<until){var proposal=pipeline.poll();
                if(proposal.isPresent()){h.assertTrue(!proposal.get().requestedComplete(),"Failed durable flush must never offer a terminal proposal");
                    acknowledged|=pipeline.acknowledge(proposal.get(),true);}
                if(!String.valueOf(pipeline.status().get("error")).isEmpty())break;Thread.sleep(2);
            }
            var status=pipeline.status();h.assertTrue(acknowledged,"Earlier exact partial geometry remains acknowledgeable");
            h.assertTrue(!Boolean.TRUE.equals(status.get("completed"))&&String.valueOf(status.get("error")).contains("durable completion refused"),"Forced checkpoint IO failure refuses terminal completion explicitly");
            h.assertTrue(String.valueOf(status.get("scalarArchiveError")).startsWith("CHECKPOINT_REFUSED"),"Cache IO diagnostic remains separate from the exact partial scene");
            h.assertTrue(((Number)((Map<?,?>)status.get("scalarArchive")).get("dirtyPages")).longValue()>0,"Failed publication retains dirty scalar pages for retry");
        }finally{Files.deleteIfExists(blocker);pipeline.close();}
        h.succeed();
    }
    private static long number(AbyssLodPipeline pipeline,String key){return ((Number)pipeline.status().get(key)).longValue();}
    private static Path directory(String prefix)throws IOException {
        var parent=Path.of(System.getProperty("mia.storageRoot", ".local"),"codex","20261013-lod-speed","stage-d","scalar-pipeline-tests");
        Files.createDirectories(parent);return Files.createTempDirectory(parent,prefix);
    }
    private static AbyssLodPipeline.Input input(ParameterizedTerrainChunkGenerator generator,Supplier<String> resources){
        var base=AbyssLodStreamingGameTests.input(1_000_000);var source=new GeneratedSkySource(generator,Map.of());
        var target=new AbyssLodPipeline.Target("scalar-pipeline",1,TerrainLodDiskCache.identity("scalar-pipeline-fixture"),0,0,0,1);
        return new AbyssLodPipeline.Input(target,base.camera(),base.nearBounds(),base.worldBounds(),base.bands(),List.of(),base.terrain(),
            new AbyssLodPhysicalLighting(source,"scalar-pipeline-fixture-world",resources),base.limits());
    }
    private static ParameterizedTerrainChunkGenerator generator(GameTestHelper h){
        var parameters=new TerrainParameters(20261003,new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
            new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),new TerrainParameters.Noise(140,20,60,70));
        return new ParameterizedTerrainChunkGenerator(parameters,new FixedBiomeSource(h.getLevel().registryAccess()
            .registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)),-64,384,
            (x,y,z)->new TerrainSample.Known(y==-8?TerrainMaterial.ROCK:TerrainMaterial.AIR));
    }
}
