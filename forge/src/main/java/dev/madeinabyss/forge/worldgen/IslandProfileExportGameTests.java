package dev.madeinabyss.forge.worldgen;
import com.google.gson.*;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.*;
import net.minecraftforge.gametest.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Optional exact production-recipe audit/export, without changing old manifests. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IslandProfileExportGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void exportApprovedIslandFromFrozenV12(GameTestHelper h) throws Exception {
        String input=System.getenv("MIA_ISLAND_PARENT_PROFILE"),output=System.getenv("MIA_ISLAND_PROFILE_OUTPUT");
        if(input==null||output==null){h.succeed();return;}
        var original=JsonParser.parseString(Files.readString(Path.of(input),StandardCharsets.UTF_8)).getAsJsonObject();
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var before=new GeneratedPreviewProfile(original,original.get("profileId").getAsString(),biomes);
        h.assertTrue(before.fingerprint().equals("e2efcfba3e6a716d859bcc97716824851eb6b890692444c0b161cd89c63cc401"),"frozen v12 still resolves identically");
        var recipe=original.deepCopy();recipe.addProperty("profileId","first-layer-v13-island");
        recipe.addProperty("algorithmRevision",original.get("algorithmRevision").getAsString()+"+approved-island-v13");
        recipe.add("islandSurface",IslandSurfaceGeneration.proposal());
        recipe.add("nativeWorldView",JsonParser.parseString("{\"bounds\":[-1280,-1286,-1280,1279,319,1279],\"quality\":\"island-high\",\"step\":16,\"maxSamples\":120000000,\"maxBoxes\":400000,\"nearDistance\":32,\"refinement\":true,\"oceanHorizonRadius\":12000}").getAsJsonObject());
        var after=new GeneratedPreviewProfile(recipe,"first-layer-v13-island",biomes);
        h.assertTrue(!before.fingerprint().equals(after.fingerprint()),"new independent namespace");
        // All old cave routes retain exact states; upper island is an explicit new branch.
        long checked=0;
        for(int x=-880;x<=880;x+=40)for(int z=-880;z<=880;z+=40)if((double)x*x+(double)z*z<960.0*960)
            for(int y=-1280;y<0;y+=31){h.assertTrue(before.generator().sampleBlock(x,y,z).equals(after.generator().sampleBlock(x,y,z)),"frozen cave voxel changed");checked++;}
        var nativeGenerator=new ComposedTerrainChunkGenerator(recipe,biomes);
        var ops=net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE,h.getLevel().registryAccess());
        var encoded=ComposedTerrainChunkGenerator.CODEC.encodeStart(ops,nativeGenerator).result().orElseThrow();
        var decoded=ComposedTerrainChunkGenerator.CODEC.parse(ops,encoded).result().orElseThrow();
        h.assertTrue(decoded.profile().fingerprint().equals(after.fingerprint()),"native NBT identity reload");
        h.assertTrue(decoded.profile().generator().sampleBlock(11000,-4000,0).is(net.minecraft.world.level.block.Blocks.WATER),"actual deep water survives native codec");
        Path directory=Path.of(output);Files.createDirectories(directory);
        Files.writeString(directory.resolve("profile.json"),recipe.toString(),StandardCharsets.UTF_8);
        var receipt=new JsonObject();receipt.addProperty("fingerprint",after.fingerprint());receipt.addProperty("parentFingerprint",before.fingerprint());
        receipt.addProperty("preservedCaveSamples",checked);receipt.addProperty("manifestSha256",nativeGenerator.manifestSha256());
        receipt.addProperty("embeddedManifestBytes",recipe.toString().getBytes(StandardCharsets.UTF_8).length);
        receipt.add("island",after.generator().islandSurface().status());
        Files.writeString(directory.resolve("receipt.json"),receipt.toString(),StandardCharsets.UTF_8);
        String upperOutput=System.getenv("MIA_ISLAND_UPPER_PROFILE_OUTPUT");
        if(upperOutput!=null){
            h.assertTrue(after.fingerprint().equals("f5e6e6a31cb78d96aa151b68f52558d002b26fbed1a8466a4435abcd453ddf66"),"existing island v13 recipe remains valid");
            var deeper=recipe.deepCopy();deeper.addProperty("profileId","first-layer-v14-island-deeper-shelves");
            deeper.addProperty("algorithmRevision",recipe.get("algorithmRevision").getAsString()+"+upper-shelves-y-minus250");
            deeper.add("upperShaftOpening",UpperShaftOpeningGeneration.proposal());
            var newest=new GeneratedPreviewProfile(deeper,deeper.get("profileId").getAsString(),biomes);
            long preserved=0;
            for(int x=-880;x<=880;x+=40)for(int z=-880;z<=880;z+=40)
                for(int y=-1280;y<=-400;y+=40){h.assertTrue(after.generator().sampleBlock(x,y,z).equals(newest.generator().sampleBlock(x,y,z)),"deep ledge revision changed preserved lower world");preserved++;}
            var nativeNew=new ComposedTerrainChunkGenerator(deeper,biomes);
            var reload=ComposedTerrainChunkGenerator.CODEC.parse(ops,ComposedTerrainChunkGenerator.CODEC.encodeStart(ops,nativeNew).result().orElseThrow()).result().orElseThrow();
            h.assertTrue(reload.profile().fingerprint().equals(newest.fingerprint()),"deeper shelf native recipe reload");
            Path upperDir=Path.of(upperOutput);Files.createDirectories(upperDir);
            Files.writeString(upperDir.resolve("profile.json"),deeper.toString(),StandardCharsets.UTF_8);
            var upperReceipt=new JsonObject();upperReceipt.addProperty("fingerprint",newest.fingerprint());upperReceipt.addProperty("parentFingerprint",after.fingerprint());
            upperReceipt.addProperty("preservedLowerSamples",preserved);upperReceipt.addProperty("manifestSha256",nativeNew.manifestSha256());upperReceipt.add("upperShaftOpening",UpperShaftOpeningGeneration.proposal());
            Files.writeString(upperDir.resolve("receipt.json"),upperReceipt.toString(),StandardCharsets.UTF_8);
        }
        h.succeed();
    }
}
