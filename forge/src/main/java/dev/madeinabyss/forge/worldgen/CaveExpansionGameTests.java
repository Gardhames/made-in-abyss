package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraftforge.gametest.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CaveExpansionGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void optionalExactProfileAudit(GameTestHelper h) throws Exception {
        String configured=System.getenv("MIA_CAVE_AUDIT_PROFILE");
        if(configured==null||configured.isBlank()){h.succeed();return;}
        var manifest=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(configured))).getAsJsonObject();
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var profile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),biomes);
        CaveExpansionDiagnostics.export(profile,java.nio.file.Path.of(configured).getParent().resolve("audit"));
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=800)
    public static void composedCavesKeepSupportedRoutesAndNativePalette(GameTestHelper h) throws Exception {
        var manifest=GeneratedPreviewProfileGameTests.manifest();
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var legacy=new GeneratedPreviewProfile(manifest,"test-generated",biomes);
        manifest.add("caveExpansion",CaveExpansionGeneration.proposal(827,net.minecraftforge.fml.ModList.get().isLoaded("alexscaves")));
        var profile=new GeneratedPreviewProfile(manifest,"test-generated",biomes);
        h.assertTrue(profile.caveExpansion()!=null&&!legacy.relativeStoreRoot().equals(profile.relativeStoreRoot()),"opt-in caves own a separate visited namespace");
        var sky=new GeneratedSkySource(profile.generator(),java.util.Map.of());
        for(var state:profile.caveExpansion().palette()){
            int id=net.minecraft.world.level.block.Block.getId(state);
            h.assertTrue(!state.hasBlockEntity()&&sky.opacity(id)>=0&&sky.emission(id)>=0,"registered scalar cave palette");
        }
        var routes=profile.caveExpansion().caves().routes();
        h.assertTrue(!routes.isEmpty(),"actual first-layer terrain must admit supported passages");
        int checked=0;
        for(var route:routes)for(var foot:route.feet()){
            var floor=profile.generator().sampleBlock(foot.x(),foot.y()-1,foot.z());
            h.assertTrue(floor.isCollisionShapeFullBlock(h.getLevel(),net.minecraft.core.BlockPos.ZERO),"composed floor must support player");
            h.assertTrue(profile.generator().sampleBlock(foot.x(),foot.y(),foot.z()).isAir()&&profile.generator().sampleBlock(foot.x(),foot.y()+1,foot.z()).isAir(),"ecology/minerals/water must not obstruct admitted passages");
            if(++checked>=1500)break;
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void caveRecipeCannotBeSilentlyIgnoredOrUseStaleIdentity(GameTestHelper h){
        var manifest=GeneratedPreviewProfileGameTests.manifest();
        var recipe=new JsonObject();recipe.addProperty("schema",1);recipe.addProperty("provenance","ORIGINAL");
        recipe.addProperty("algorithmSha256","0".repeat(64));manifest.add("caveExpansion",recipe);
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        boolean refused=false;
        try{new GeneratedPreviewProfile(manifest,"test-generated",biomes);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"Explicit cave recipe must be validated, never silently ignored or loaded with stale code");
        h.succeed();
    }
}
