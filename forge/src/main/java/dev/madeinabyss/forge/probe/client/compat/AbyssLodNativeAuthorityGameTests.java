package dev.madeinabyss.forge.probe.client.compat;

import com.google.gson.*;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.worldgen.*;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraftforge.gametest.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodNativeAuthorityGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void actualForgeResourcesCannotDisableResolvedPhysicalWarmAuthority(GameTestHelper h)throws Exception {
        var root=Path.of(System.getProperty("mia.storageRoot", ".local"),"codex","20261013-lod-speed","stage-d2","native-authority-tests");
        Files.createDirectories(root);var directory=Files.createTempDirectory(root,"warm-");var report=new JsonObject();
        // Actual ModLauncher-loaded descriptors, not the Stage D IModFile proxy fixture.
        var descriptors=new JsonArray();
        for(var info:net.minecraftforge.fml.ModList.get().getModFiles()){
            var item=new JsonObject();item.addProperty("file",info.getFile().getFileName());
            var descriptor=new AbyssLodResourceIdentity.Captured(info.getFile().getFileName(),()->net.minecraftforge.resource.ResourcePackLoader.createPackForMod(info));
            try(var pack=descriptor.open().get()){item.addProperty("class",pack.getClass().getName());}
            try{item.addProperty("root",info.getFile().findResource().toUri().toString());}
            catch(RuntimeException unavailable){item.addProperty("zeroSegmentRootFailure",unavailable.toString());}
            try {item.addProperty("oldRawHash",new AbyssLodResourceIdentity.Snapshot(List.of(descriptor)).get());}
            catch(RuntimeException unavailable){var causes=new JsonArray();for(Throwable cause=unavailable;cause!=null;cause=cause.getCause())causes.add(cause.toString());item.add("oldRawFailure",causes);}
            descriptors.add(item);
        }
        report.add("actualModDescriptors",descriptors);
        JsonObject expected;
        var coldInput=input(h);report.addProperty("physicalIdentity",((AbyssLodPhysicalLighting)coldInput.lighting()).archiveAuthority("fixture").familyIdentity());
        long started=System.nanoTime();
        try(var cold=new AbyssLodPipeline(true,directory)){
            cold.request(coldInput);expected=LodSpeedGameTests.oracle(AbyssLodStreamingGameTests.complete(cold,30));
            h.assertTrue(number(cold,"actualLightJobs")>0,"Cold actual registry physical source computes original32");
            report.addProperty("coldMillis",(System.nanoTime()-started)/1e6);report.add("coldStatus",new Gson().toJsonTree(cold.status()));report.add("coldOracle",expected);
        }
        // A visual-only unknown wrapper cannot grant or withhold physical authority. Its bytes
        // are deliberately impossible to read. The native adapter uses the same two-arg factory.
        var unsupported=new AbyssLodResourceIdentity.Snapshot(List.of(new AbyssLodResourceIdentity.Captured("unknown-visual-shader-pack",()->{throw new UnsupportedOperationException("unreadable visual descriptor");})));
        boolean rejected=false;try{unsupported.get();}catch(AbyssLodResourceIdentity.UnavailableException expectedFailure){rejected=true;}
        h.assertTrue(rejected,"Fixture reproduces raw visual descriptor refusal");
        started=System.nanoTime();
        try(var warm=new AbyssLodPipeline(true,directory)){
            warm.request(input(h));var terminal=AbyssLodStreamingGameTests.complete(warm,30);
            h.assertTrue(number(warm,"actualLightJobs")==0,"Fresh actual physical source reopens all scalars despite unrelated visual descriptor failure");
            h.assertTrue(Boolean.TRUE.equals(warm.status().get("scalarSealHit")),"Same view restores the durable completion seal");
            h.assertTrue(expected.equals(LodSpeedGameTests.oracle(terminal)),"Four terminal geometry/page/grid/light digests equal cold");
            report.addProperty("warmMillis",(System.nanoTime()-started)/1e6);report.add("warmStatus",new Gson().toJsonTree(warm.status()));report.add("warmOracle",LodSpeedGameTests.oracle(terminal));
        }finally{Files.writeString(directory.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}
        h.succeed();
    }
    private static long number(AbyssLodPipeline pipeline,String key){return ((Number)pipeline.status().get(key)).longValue();}
    private static AbyssLodPipeline.Input input(GameTestHelper h)throws Exception {
        JsonObject manifest;
        try(var stream=AbyssLodNativeAuthorityGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json")){
            manifest=JsonParser.parseString(new String(Objects.requireNonNull(stream).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var profile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),biomes);
        var source=new GeneratedSkySource(profile,Map.of());var base=AbyssLodStreamingGameTests.input(1_000_000);
        var identity=TerrainLodDiskCache.identity("native-resource-fixture",profile.fingerprint(),source.cacheGeometryIdentity());
        var target=new AbyssLodPipeline.Target("native-resource-fixture",1,identity,0,0,0,1);
        return new AbyssLodPipeline.Input(target,base.camera(),base.nearBounds(),base.worldBounds(),base.bands(),List.of(),base.terrain(),
                new AbyssLodPhysicalLighting(source,profile.fingerprint()),base.limits());
    }
}
