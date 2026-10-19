package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.kernel.cube.*;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;
import java.nio.file.*;

/** ORIGINAL numerical preview fixtures; does not accept reconstruction metrics as canon. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedPreviewProfileGameTests {
    @GameTest(template="empty")
    public static void knownFirstLayerBelowOldVisualGuardRemainsReachable(GameTestHelper helper) {
        var p=profile(helper,manifest());
        p.requireVisualDomain(new dev.madeinabyss.kernel.BlockCoordinate(0,-1200,0));
        helper.assertTrue(p.generator().generateCube(new CubeKey(0,-75,0)).states().length==4096,
                "known complete first-layer cube remains generated below -945");
        helper.succeed();
    }
    static JsonObject manifest() {
        var p = new TerrainParameters(20261003,new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
                new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),new TerrainParameters.Noise(140,20,60,70));
        var out = new JsonObject();out.addProperty("profileId","test-generated");out.addProperty("algorithmRevision","v5-test");
        out.addProperty("algorithmSha256",GeneratedPreviewProfile.actualAlgorithmSha256());
        out.add("parameters",TerrainParameterCodecs.PARAMETERS.encodeStart(JsonOps.INSTANCE,p).result().orElseThrow());
        var ground = new JsonObject();ground.addProperty("x",-590);ground.addProperty("y",-888);ground.addProperty("z",80);out.add("groundAnchor",ground);return out;
    }
    private static GeneratedPreviewProfile profile(GameTestHelper helper,JsonObject manifest) {
        return new GeneratedPreviewProfile(manifest,"test-generated",new FixedBiomeSource(helper.getLevel().registryAccess()
                .registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)));
    }
    // Catches stale algorithm declarations, unsafe cutoff anchors and missing real grass collision source.
    @GameTest(template="empty") public static void actualGrassAnchorAndAlgorithmIdentity(GameTestHelper helper) {
        var m=manifest();var p=profile(helper,m);var feet=p.feet();
        helper.assertTrue(p.generator().sampleBlock(feet.x(),feet.y()-1,feet.z()).is(Blocks.GRASS_BLOCK),"actual sampled grass beneath native feet");
        helper.assertTrue(p.generator().sampleBlock(feet.x(),feet.y(),feet.z()).isAir(),"native feet are in actual air");
        var changed=m.deepCopy();changed.addProperty("algorithmRevision","v5-new-revision");
        helper.assertTrue(!p.fingerprint().equals(profile(helper,changed).fingerprint()),"new algorithm identity gets independent visited store");
        var stale=m.deepCopy();stale.addProperty("algorithmSha256","0".repeat(64));
        try{profile(helper,stale);helper.fail("stale compiled algorithm identity must refuse");}catch(IllegalArgumentException expected){}
        var invalid=m.deepCopy();invalid.getAsJsonObject("groundAnchor").addProperty("y",-1250);
        try{profile(helper,invalid);helper.fail("unsupported or nongrass anchor must refuse");}catch(IllegalArgumentException expected){}
        helper.succeed();
    }
    // Catches replacing a saved build with newly sampled ground, or reusing another profile's bytes.
    @GameTest(template="empty") public static void savedGeneratedBuildWinsOverFactory(GameTestHelper helper) throws Exception {
        var p=profile(helper,manifest());var key=CubeKey.fromBlock(p.feet());
        Path root=Path.of("diagnostics","generated-preview-stores",java.util.UUID.randomUUID().toString());
        var store=new StableCubeStore(root,p.generatorId(),1);var generated=p.generator().generateCube(key);int[] states=generated.states();
        int index=(Math.floorMod(p.feet().y(),16)<<8)|(Math.floorMod(p.feet().z(),16)<<4)|Math.floorMod(p.feet().x(),16);
        states[index]=Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());store.write(new CubeSnapshot(key,states));
        byte[] before=Files.readAllBytes(store.path(key));
        var residency=new CubeResidencyService(store,k->{throw new AssertionError("saved terrain must not invoke missing factory");},1,1,1);
        residency.move("preview",p.feet(),0,0);
        helper.assertTrue(Block.stateById(residency.space().block(p.feet()).orElseThrow()).is(Blocks.GOLD_BLOCK),"visited build remains authoritative");
        helper.assertTrue(java.util.Arrays.equals(before,Files.readAllBytes(store.path(key))),"read admission does not rewrite existing MISP");
        var changed=manifest();changed.addProperty("algorithmRevision","other-profile");
        var wrong=new StableCubeStore(root,profile(helper,changed).generatorId(),1);
        try{wrong.read(key);helper.fail("different profile must not silently regenerate old bytes");}catch(java.io.IOException expected){}
        helper.assertTrue(java.util.Arrays.equals(before,Files.readAllBytes(store.path(key))),"provenance refusal preserves old bytes");
        residency.release("preview");helper.succeed();
    }
}
