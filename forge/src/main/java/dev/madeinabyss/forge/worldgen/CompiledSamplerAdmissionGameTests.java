package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.kernel.cube.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exact historical admission/codec/save contracts for the private sampler reuse. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompiledSamplerAdmissionGameTests {
    private static final String WILDERNESS_BASELINE="5e8183b96dd7700185d00129d49490e0e2db90ba92a2ed17974cb679a338a4d6";
    private static final String OPENING_BASELINE="fcb4149aecb3801354d3a4ad45e6c68b339e6295aca4415fea9e16e3d2e44a2d";
    private static final String WILDERNESS_FINGERPRINT="c88d7134ad6687444b9ffee5bdcd7952a1ab5b8c1a4f2e23b3aa0bfc7a339a4c";
    private static final String PREPATCH_CAVE="06eb446c89e51c163700accb162135fbac608f170f4e4aac873c0c59ad5a495a";
    private static final String PREPATCH_CAVE_FINGERPRINT="6cb4f66812dcc386bc2500910792c5770c379051f07ffff7780d52ec0270f44b";
    private static final String REUSED_CAVE="91f87867f1ab1ed693723175e41733f65e6f655d3e6024c32156e55ef3b42812";
    private static final String REUSED_OPENING="c217cd95b585e768a4fc65ecb9f56245c672bdbd53b3d19780f7ca957b41e422";
    private static final String REUSED_WILDERNESS="be8c037a97864bc381c4ea631c190073ccaeeefaba1ef2cb613953a14b8669d9";

    @GameTest(template="empty",timeoutTicks=1200)
    public static void prepatchCanonicalCaveRecipeKeepsItsOwnFingerprintCodecAndSavedEdits(GameTestHelper h)throws Exception {
        JsonObject original;
        try(var input=CompiledSamplerAdmissionGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json")){
            original=JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
        // This recipe was valid under the immediate prepatch constructor. Its
        // canonical identity must remain distinct from the legacy5e8 recipe.
        original.getAsJsonObject("caveExpansion").addProperty("algorithmSha256",PREPATCH_CAVE);
        String before=original.toString(),id=original.get("profileId").getAsString();
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var profile=new GeneratedPreviewProfile(original,id,biomes);
        h.assertTrue(profile.fingerprint().equals(PREPATCH_CAVE_FINGERPRINT),"valid prepatch canonical recipe keeps its independent fixed namespace");
        h.assertTrue(original.toString().equals(before),"historical actualSHA recipe is not canonicalized to the legacy5e8 field");
        var generator=new ComposedTerrainChunkGenerator(original,biomes);
        var ops=net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE,h.getLevel().registryAccess());
        var encoded=ComposedTerrainChunkGenerator.CODEC.encodeStart(ops,generator).result().orElseThrow();
        var reloaded=ComposedTerrainChunkGenerator.CODEC.parse(ops,encoded).result().orElseThrow();
        h.assertTrue(reloaded.profile().fingerprint().equals(PREPATCH_CAVE_FINGERPRINT)&&reloaded.manifestJson().equals(generator.manifestJson()),"codec retains the exact prepatch canonical input");
        var key=new CubeKey(-50,-30,-2);var states=profile.generator().generateCube(key).states();states[7]=Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        var root=Path.of("diagnostics","compiled-reuse-prepatch-cave",UUID.randomUUID().toString());
        var store=new StableCubeStore(root,profile.generatorId(),1);store.write(new CubeSnapshot(key,states));byte[] bytes=Files.readAllBytes(store.path(key));
        var restored=new StableCubeStore(root,reloaded.profile().generatorId(),1).read(key).orElseThrow();
        h.assertTrue(Arrays.equals(states,restored.states())&&Arrays.equals(bytes,Files.readAllBytes(store.path(key))),"same prepatch namespace retains full existing edited snapshot bytes");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void exactPersistedWildernessBaselineRetainsCanonicalCodecAndSavedEditBytes(GameTestHelper h)throws Exception {
        JsonObject original;
        try(var input=CompiledSamplerAdmissionGameTests.class.getResourceAsStream("/diagnostics/compiled-reuse-wilderness-baseline.json")){
            original=JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
        String id=original.get("profileId").getAsString(),before=original.toString();
        h.assertTrue(original.getAsJsonObject("wilderness").get("algorithmSha256").getAsString().equals(WILDERNESS_BASELINE),"exact formerly admitted accelerated/persistent recipe fixture");
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var oracle=new GeneratedPreviewProfile(original,id,biomes,false);
        var current=new GeneratedPreviewProfile(original,id,biomes,true);
        h.assertTrue(current.fingerprint().equals(WILDERNESS_FINGERPRINT)&&oracle.fingerprint().equals(WILDERNESS_FINGERPRINT),"canonical old recipe retains its fixed visited namespace in both strategies");
        h.assertTrue(original.toString().equals(before),"private construction copies cannot mutate caller manifest");
        for(var key:List.of(new CubeKey(13,-42,-26),new CubeKey(13,-41,-26),new CubeKey(21,-40,-26),new CubeKey(20,-38,-29),
                new CubeKey(-37,-56,5),new CubeKey(-1,-1,-1),new CubeKey(30,3,-1),new CubeKey(0,-80,0)))
            h.assertTrue(Arrays.equals(oracle.generator().generateCube(key).states(),current.generator().generateCube(key).states()),"independent Parametric parent preserves composed full cube "+key);
        var generator=new ComposedTerrainChunkGenerator(original,biomes);
        var ops=net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE,h.getLevel().registryAccess());
        var encoded=ComposedTerrainChunkGenerator.CODEC.encodeStart(ops,generator).result().orElseThrow();
        var restoredGenerator=ComposedTerrainChunkGenerator.CODEC.parse(ops,encoded).result().orElseThrow();
        h.assertTrue(generator.manifestJson().equals(restoredGenerator.manifestJson())&&restoredGenerator.profile().fingerprint().equals(WILDERNESS_FINGERPRINT),"codec persists original recipe, not actual implementation substitution");
        var key=new CubeKey(-37,-56,5);var states=current.generator().generateCube(key).states();states[5]=Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        var root=Path.of("diagnostics","compiled-reuse-admission",UUID.randomUUID().toString());
        var store=new StableCubeStore(root,current.generatorId(),1);store.write(new CubeSnapshot(key,states));byte[] bytes=Files.readAllBytes(store.path(key));
        var reloaded=new StableCubeStore(root,restoredGenerator.profile().generatorId(),1).read(key).orElseThrow();
        h.assertTrue(Arrays.equals(states,reloaded.states())&&Arrays.equals(bytes,Files.readAllBytes(store.path(key))),"visited construction bytes remain in the exact original namespace");
        for(String unsupported:List.of("b90a5df9fec5f22b1b0bf6e8b98683ab77a3dcb593b02cb078168226c778c36e",
                "21f700e99a49b5e7a4f48caf2a5e2191026836d8758f6d14ddb550053cb43466",
                "040a6d32715c1143d0521a86f28e21024e537fa6b2cc953903483fa6e50ade40","0".repeat(64))){
            var invalid=original.deepCopy();invalid.getAsJsonObject("wilderness").addProperty("algorithmSha256",unsupported);
            boolean refused=false;try{new GeneratedPreviewProfile(invalid,id,biomes);}catch(IllegalArgumentException expected){refused=true;}
            h.assertTrue(refused,"unaudited historical recipes remain refused: "+unsupported);
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void onlyFixedCaveOpeningAndWildernessPairsReceivePrivateConstructionCopies(GameTestHelper h){
        String cave=CaveExpansionGeneration.algorithmSha256(),opening=UpperShaftOpeningGeneration.actualAlgorithmSha256(),wilderness=WildernessGeneration.algorithmSha256();
        System.out.println("MIA_COMPILED_REUSE_ACTUAL_TARGETS cave="+cave+" opening="+opening+" wilderness="+wilderness);
        h.assertTrue(cave.equals(REUSED_CAVE)&&opening.equals(REUSED_OPENING)&&wilderness.equals(REUSED_WILDERNESS),"actual loaded byte closures match the fixed reviewed candidate targets");
        checkPair(h,CaveAlgorithmEquivalence.ORIGINAL,cave,CaveAlgorithmEquivalence::forConstruction);
        checkPair(h,PREPATCH_CAVE,cave,CaveAlgorithmEquivalence::forConstruction);
        checkPair(h,OPENING_BASELINE,opening,UpperShaftOpeningAlgorithmEquivalence::forConstruction);
        checkPair(h,WILDERNESS_BASELINE,wilderness,WildernessAlgorithmEquivalence::forConstruction);
        h.succeed();
    }
    private static void checkPair(GameTestHelper h,String baseline,String actual,java.util.function.BiFunction<JsonObject,String,JsonObject> bridge){
        var original=new JsonObject();original.addProperty("algorithmSha256",baseline);original.addProperty("unrelatedField","preserved");
        var before=original.deepCopy();var construction=bridge.apply(original,actual);
        h.assertTrue(construction!=original&&construction.get("algorithmSha256").getAsString().equals(actual),"exact audited pair needs a private constructor recipe");
        var expected=before.deepCopy();expected.addProperty("algorithmSha256",actual);
        h.assertTrue(original.equals(before)&&construction.equals(expected),"only constructor SHA changes; original complete recipe stays immutable");
        h.assertTrue(bridge.apply(original,"f".repeat(64))==original,"unaudited actual code cannot pass a known historical recipe");
        var unknown=original.deepCopy();unknown.addProperty("algorithmSha256","e".repeat(64));
        h.assertTrue(bridge.apply(unknown,actual)==unknown,"unaudited stored recipe cannot pass current implementation");
    }
}
