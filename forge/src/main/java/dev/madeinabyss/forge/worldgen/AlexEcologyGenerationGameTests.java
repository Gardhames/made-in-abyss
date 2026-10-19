package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraftforge.gametest.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AlexEcologyGenerationGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void ecologyOwnsNamespaceAndPreservesCaveRoutes(GameTestHelper h) throws Exception {
        var manifest=GeneratedPreviewProfileGameTests.manifest();
        boolean assets=net.minecraftforge.fml.ModList.get().isLoaded("alexscaves");
        manifest.add("caveExpansion",CaveExpansionGeneration.proposal(827,assets));
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var before=new GeneratedPreviewProfile(manifest,"test-generated",biomes);
        manifest.add("alexEcology",AlexEcologyGeneration.proposal(927,assets));
        var after=new GeneratedPreviewProfile(manifest,"test-generated",biomes);
        h.assertTrue(after.generator().alexEcology()!=null && !before.fingerprint().equals(after.fingerprint()),"explicit new ecology must get its own visited namespace");
        var sky=new GeneratedSkySource(after.generator(),java.util.Map.of());
        for(var state:after.generator().alexEcology().palette()){
            int id=net.minecraft.world.level.block.Block.getId(state);
            h.assertTrue(sky.opacity(id)>=0&&sky.emission(id)>=0,"all actual ecology states admitted by scalar source");
        }
        int checked=0;
        outer:for(var route:after.caveExpansion().caves().routes())for(var foot:route.feet()) {
            for(int dy=-1;dy<=2;dy++)h.assertTrue(before.generator().sampleBlock(foot.x(),foot.y()+dy,foot.z())==after.generator().sampleBlock(foot.x(),foot.y()+dy,foot.z()),"entire admitted floor/clearance remains exactly unchanged");
            if(++checked>=1500)break outer;
        }
        h.assertTrue(checked>100,"actual supported cave route points tested");
        var again=new GeneratedPreviewProfile(manifest,"test-generated",biomes);
        h.assertTrue(again.fingerprint().equals(after.fingerprint()),"stable deterministic manifest identity");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=800)
    public static void staleOrMissingParentEcologyIsRefused(GameTestHelper h) {
        var manifest=GeneratedPreviewProfileGameTests.manifest();
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var recipe=AlexEcologyGeneration.proposal(927,false);manifest.add("alexEcology",recipe);
        boolean refused=false;try{new GeneratedPreviewProfile(manifest,"test-generated",biomes);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"ecology cannot silently ignore its required cave parent");
        manifest.add("caveExpansion",CaveExpansionGeneration.proposal(827,false));recipe.addProperty("algorithmSha256","0".repeat(64));
        refused=false;try{new GeneratedPreviewProfile(manifest,"test-generated",biomes);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"stale ecology algorithm cannot mutate visited geometry");h.succeed();
    }
}
