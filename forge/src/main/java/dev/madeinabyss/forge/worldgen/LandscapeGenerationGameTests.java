package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.BlockCoordinate;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.gametest.*;
import java.nio.file.*;
import java.util.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LandscapeGenerationGameTests {
    private static JsonObject manifest() throws Exception {
        String configured=System.getProperty("mia.generator.previewProfile");
        if(configured!=null) {
            var m=JsonParser.parseString(Files.readString(Path.of(configured))).getAsJsonObject();
            if(m.has("landscape"))return m;
        }
        var m=GeneratedPreviewProfileGameTests.manifest();
        var params=TerrainParameterCodecs.PARAMETERS.parse(JsonOps.INSTANCE,m.get("parameters")).result().orElseThrow();
        var sampler=new ParametricTerrainSampler(params);int floor=0;
        for(int y=-860;y>=-908;y--)if(sampler.sample(-600,y,90) instanceof TerrainSample.Known k && k.material()==TerrainMaterial.GRASS){floor=y;break;}
        if(floor==0)throw new AssertionError("measured fixture floor missing");
        var data=JsonParser.parseString("{\"schema\":1,\"provenance\":\"ORIGINAL\",\"toolkitCommit\":\"262850795f317a8e4340987bbbe4a8d0ecb68afe\",\"patches\":[]}").getAsJsonObject();
        var patch=new JsonObject();patch.addProperty("id","fixture");patch.addProperty("minX",-608);patch.addProperty("minZ",80);
        patch.addProperty("width",32);patch.addProperty("depth",32);patch.addProperty("referenceY",floor);
        var c=new JsonObject();c.addProperty("x",-600);c.addProperty("z",90);c.addProperty("baseY",floor);c.addProperty("surfaceY",floor+1);
        var cs=new JsonArray();cs.add(c);patch.add("columns",cs);
        var ds=new JsonArray();var d=new JsonArray();d.add(-600);d.add(floor+2);d.add(90);d.add("TALL_GRASS");ds.add(d);patch.add("placements",ds);
        data.getAsJsonArray("patches").add(patch);var wrapper=new JsonObject();wrapper.add("data",data);
        wrapper.addProperty("algorithmSha256",GeneratedPreviewProfile.actualLandscapeAlgorithmSha256());m.add("landscape",wrapper);return m;
    }
    private static GeneratedPreviewProfile profile(GameTestHelper h,JsonObject m) {
        return new GeneratedPreviewProfile(m,m.get("profileId").getAsString(),new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)));
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void offlineRecipeReachesActualNativeCubePalette(GameTestHelper h)throws Exception {
        var m=manifest();var p=profile(h,m);var cubes=new HashMap<CubeKey,int[]>();
        for(var v:p.landscape().placements()) {
            var expected=HabitatBlockStates.state(v.block());
            h.assertTrue(p.generator().sampleBlock(v.x(),v.y(),v.z()).equals(expected),"native sampler must honor every compiled write");
            var key=CubeKey.fromBlock(new BlockCoordinate(v.x(),v.y(),v.z()));
            var cube=cubes.computeIfAbsent(key,k->p.generator().generateCube(k).states());
            int i=(Math.floorMod(v.y(),16)<<8)|(Math.floorMod(v.z(),16)<<4)|Math.floorMod(v.x(),16);
            h.assertTrue(Block.stateById(cube[i]).equals(expected),"actual signed cube palette matches authored terrain/ecology");
        }
        var report=p.status();report.addProperty("validatedCubes",cubes.size());report.addProperty("passed",true);
        var path=Path.of("diagnostics","landscape-native-generation.json");Files.createDirectories(path.getParent());Files.writeString(path,report.toString());
        h.assertTrue(p.status().toString().length()<8192,"large recipe never enters state packet");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void recipeIdentityCannotRewritePreviousStore(GameTestHelper h)throws Exception {
        var m=manifest();var p=profile(h,m);var altered=m.deepCopy();
        altered.getAsJsonObject("landscape").getAsJsonObject("data").addProperty("revisionNote","changed authored recipe");
        var q=profile(h,altered);h.assertTrue(!p.relativeStoreRoot().equals(q.relativeStoreRoot()),"changed recipe needs its own visited store");
        altered=m.deepCopy();altered.getAsJsonObject("landscape").addProperty("algorithmSha256","0".repeat(64));
        try{profile(h,altered);h.fail("stale bytecode must refuse");}catch(IllegalArgumentException expected){}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void fractionalRecipeCoordinatesAreNotSilentlyTruncated(GameTestHelper h)throws Exception {
        var data=manifest().getAsJsonObject("landscape").getAsJsonObject("data").deepCopy();
        data.getAsJsonArray("patches").get(0).getAsJsonObject().getAsJsonArray("columns").get(0).getAsJsonObject().addProperty("x",-1.5);
        try{LandscapeRecipeCodec.parse(data);h.fail("fractional coordinates must refuse");}catch(IllegalArgumentException expected){}
        h.succeed();
    }
}
