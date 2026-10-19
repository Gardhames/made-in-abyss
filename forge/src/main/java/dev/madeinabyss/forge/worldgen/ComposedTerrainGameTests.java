package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import net.minecraftforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Uses registry dispatch so a missing native composed generator is a behavioral RED. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ComposedTerrainGameTests {
    private static final ResourceLocation ID = new ResourceLocation(MadeInAbyssProbe.MOD_ID,"composed_first_layer");
    private static final LevelHeightAccessor COLUMN = LevelHeightAccessor.create(-64,384);
    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static JsonObject specification(GameTestHelper h) throws Exception {
        h.assertTrue(BuiltInRegistries.CHUNK_GENERATOR.getOptional(ID).isPresent(),"native composed generator codec must be registered");
        var manifest=GeneratedPreviewProfileGameTests.manifest();
        manifest.add("caveExpansion",CaveExpansionGeneration.proposal(827,false));
        manifest.add("alexEcology",AlexEcologyGeneration.proposal(927,false));
        var biome=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var profile=new GeneratedPreviewProfile(manifest,"test-generated",biome);
        var ops=RegistryOps.create(JsonOps.INSTANCE,h.getLevel().registryAccess());
        var spec=new JsonObject();spec.addProperty("type",ID.toString());
        spec.add("biome_source",FixedBiomeSource.CODEC.encodeStart(ops,biome).result().orElseThrow());
        String json=manifest.toString();spec.addProperty("manifest_json",json);
        spec.addProperty("profile_fingerprint",profile.fingerprint());spec.addProperty("manifest_sha256",digest(json));
        return spec;
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void persistedNativeCodecKeepsExactComposedSource(GameTestHelper h) throws Exception {
        var spec=specification(h);var ops=RegistryOps.create(JsonOps.INSTANCE,h.getLevel().registryAccess());
        var parsed=ChunkGenerator.CODEC.parse(ops,spec);
        h.assertTrue(parsed.error().isEmpty(),"native composed dispatch decode: "+parsed.error());
        var original=parsed.result().orElseThrow();
        var saved=ChunkGenerator.CODEC.encodeStart(ops,original);
        h.assertTrue(saved.error().isEmpty(),"native composed dispatch encode: "+saved.error());
        var encoded=saved.result().orElseThrow();
        var reloaded=ChunkGenerator.CODEC.parse(ops,encoded);
        h.assertTrue(reloaded.error().isEmpty(),"native composed saved decode: "+reloaded.error());
        var restored=reloaded.result().orElseThrow();
        h.assertTrue(encoded.getAsJsonObject().get("manifest_json").equals(spec.get("manifest_json")),"complete inline recipe survives native dispatch serialization");
        var manifest=com.google.gson.JsonParser.parseString(spec.get("manifest_json").getAsString()).getAsJsonObject();
        var expected=new GeneratedPreviewProfile(manifest,"test-generated",(FixedBiomeSource)restored.getBiomeSource()).generator();
        var biomes=h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var chunk=new ProtoChunk(new ChunkPos(-1,-1),UpgradeData.EMPTY,COLUMN,biomes,null);
        restored.fillFromNoise(Runnable::run,null,null,null,chunk).join();
        var pos=new BlockPos.MutableBlockPos();
        for(int y=-64;y<320;y++)for(int z=-16;z<0;z++)for(int x=-16;x<0;x++)
            h.assertTrue(chunk.getBlockState(pos.set(x,y,z)).equals(expected.sampleBlock(x,y,z)),"actual native negative chunk agrees with composed cube source at "+pos);
        for(int y:new int[]{-65,-64,319,320}) {
            var cube=expected.generateCube(new dev.madeinabyss.kernel.cube.CubeKey(-1,Math.floorDiv(y,16),-1));
            int index=(Math.floorMod(y,16)<<8)|(15<<4)|15;
            h.assertTrue(net.minecraft.world.level.block.Block.stateById(cube.states()[index]).equals(expected.sampleBlock(-1,y,-1)),"cube seam sample uses exact XYZ "+y);
            if(y>=-64&&y<320)h.assertTrue(restored.getBaseColumn(-1,-1,COLUMN,null).getBlock(y).equals(expected.sampleBlock(-1,y,-1)),"native seam column matches cube "+y);
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void persistedNativeCodecRefusesChangedRecipeAndUnknownAlgorithms(GameTestHelper h) throws Exception {
        var spec=specification(h);var ops=RegistryOps.create(JsonOps.INSTANCE,h.getLevel().registryAccess());
        h.assertTrue(ChunkGenerator.CODEC.parse(ops,spec).error().isEmpty(),"refusal fixture must first accept the valid composed recipe");
        for(String field:new String[]{"manifest_json","manifest_sha256","profile_fingerprint","biome_source"}) {
            var missing=spec.deepCopy();missing.remove(field);
            h.assertTrue(ChunkGenerator.CODEC.parse(ops,missing).error().isPresent(),"missing persisted recipe field fails closed: "+field);
        }
        var corrupt=spec.deepCopy();corrupt.addProperty("manifest_sha256","0".repeat(64));
        h.assertTrue(ChunkGenerator.CODEC.parse(ops,corrupt).error().isPresent(),"changed complete manifest must refuse");
        corrupt=spec.deepCopy();corrupt.addProperty("profile_fingerprint","0".repeat(64));
        h.assertTrue(ChunkGenerator.CODEC.parse(ops,corrupt).error().isPresent(),"changed store identity must refuse");
        var manifest=com.google.gson.JsonParser.parseString(spec.get("manifest_json").getAsString()).getAsJsonObject();
        manifest.getAsJsonObject("alexEcology").addProperty("algorithmSha256","0".repeat(64));
        corrupt=spec.deepCopy();String json=manifest.toString();corrupt.addProperty("manifest_json",json);corrupt.addProperty("manifest_sha256",digest(json));
        h.assertTrue(ChunkGenerator.CODEC.parse(ops,corrupt).error().isPresent(),"unknown compiled ecology cannot fall back to flat/base terrain");
        corrupt=spec.deepCopy();corrupt.addProperty("manifest_json","[]");corrupt.addProperty("manifest_sha256",digest("[]"));
        h.assertTrue(ChunkGenerator.CODEC.parse(ops,corrupt).error().isPresent(),"non-object manifest becomes a codec error");
        String tooLarge=" ".repeat(65536)+spec.get("manifest_json").getAsString();
        corrupt=spec.deepCopy();corrupt.addProperty("manifest_json",tooLarge);corrupt.addProperty("manifest_sha256",digest(tooLarge));
        h.assertTrue(ChunkGenerator.CODEC.parse(ops,corrupt).error().isPresent(),"manifest too large for native NBT UTF string must refuse before saving");
        h.succeed();
    }
}
