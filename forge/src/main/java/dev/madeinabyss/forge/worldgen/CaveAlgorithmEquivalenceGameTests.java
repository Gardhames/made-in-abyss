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

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CaveAlgorithmEquivalenceGameTests {
    private static final String FINGERPRINT="148e3d835d034f0a195516c95d20cb415d5a275ac5e92ee8a29ef28389642bb0";
    @GameTest(template="empty",timeoutTicks=1200)
    public static void exactPersistedV14PairKeepsCodecFingerprintAndSavedBlocks(GameTestHelper h)throws Exception {
        String json;
        try(var input=CaveAlgorithmEquivalenceGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json")){json=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);}
        var original=JsonParser.parseString(json).getAsJsonObject();String id=original.get("profileId").getAsString(),before=original.toString();
        h.assertTrue(original.getAsJsonObject("caveExpansion").get("algorithmSha256").getAsString().equals(CaveAlgorithmEquivalence.ORIGINAL),"fixture is exact historical aggregate");
        String actual=CaveExpansionGeneration.algorithmSha256();
        if(!actual.equals(CaveAlgorithmEquivalence.INDEXED))System.out.println("MIA_CAVE_ACTUAL_CLASSES "+actualClassDiagnostics());
        h.assertTrue(actual.equals(CaveAlgorithmEquivalence.INDEXED),"only fixed audited current closure is approved: actual="+actual+", expected="+CaveAlgorithmEquivalence.INDEXED);
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var profile=new GeneratedPreviewProfile(original,id,biomes);
        h.assertTrue(profile.fingerprint().equals(FINGERPRINT),"unchanged original canonical fingerprint and visited namespace");
        h.assertTrue(original.toString().equals(before),"construction substitution never mutates caller manifest");
        var nativeGenerator=new ComposedTerrainChunkGenerator(original,biomes);var ops=net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE,h.getLevel().registryAccess());
        var encoded=ComposedTerrainChunkGenerator.CODEC.encodeStart(ops,nativeGenerator).result().orElseThrow();
        var reloaded=ComposedTerrainChunkGenerator.CODEC.parse(ops,encoded).result().orElseThrow();
        h.assertTrue(reloaded.profile().fingerprint().equals(FINGERPRINT)&&reloaded.manifestJson().equals(nativeGenerator.manifestJson()),"codec retains original old recipe and fingerprint");
        var root=Path.of("diagnostics","cave-equivalence-stores",UUID.randomUUID().toString());var key=new CubeKey(-50,-30,-2);
        int[] states=profile.generator().generateCube(key).states();states[5]=Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        var store=new StableCubeStore(root,profile.generatorId(),1);store.write(new CubeSnapshot(key,states));byte[] bytes=Files.readAllBytes(store.path(key));
        var restored=new StableCubeStore(root,reloaded.profile().generatorId(),1).read(key).orElseThrow();
        h.assertTrue(Arrays.equals(states,restored.states())&&Arrays.equals(bytes,Files.readAllBytes(store.path(key))),"same immutable save provenance retains complete existing edit bytes");
        var invalid=original.deepCopy();invalid.getAsJsonObject("caveExpansion").addProperty("algorithmSha256","0".repeat(64));
        boolean refused=false;try{new GeneratedPreviewProfile(invalid,id,biomes);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"unknown old hash remains refusal");h.succeed();
    }
    @GameTest(template="empty")
    public static void constructionCopyRequiresBothExactHashSides(GameTestHelper h) {
        var original=new JsonObject();original.addProperty("algorithmSha256",CaveAlgorithmEquivalence.ORIGINAL);original.addProperty("provenance","ORIGINAL");
        var copied=CaveAlgorithmEquivalence.forConstruction(original,CaveAlgorithmEquivalence.INDEXED);
        h.assertTrue(copied!=original&&copied.get("algorithmSha256").getAsString().equals(CaveAlgorithmEquivalence.INDEXED),"only approved pair receives private construction copy");
        h.assertTrue(original.get("algorithmSha256").getAsString().equals(CaveAlgorithmEquivalence.ORIGINAL),"original persisted field untouched");
        h.assertTrue(CaveAlgorithmEquivalence.forConstruction(original,"f".repeat(64))==original,"unknown actual code cannot pass old hash");
        var unknown=original.deepCopy();unknown.addProperty("algorithmSha256","e".repeat(64));
        h.assertTrue(CaveAlgorithmEquivalence.forConstruction(unknown,CaveAlgorithmEquivalence.INDEXED)==unknown,"unknown stored code cannot pass current hash");h.succeed();
    }
    private static JsonArray actualClassDiagnostics()throws Exception{
        var classes=new TreeMap<String,Class<?>>();
        for(var root:List.of(dev.madeinabyss.kernel.terrain.natural.CaveNetworkParameters.class,
                dev.madeinabyss.kernel.terrain.natural.CaveNetworkTerrainSampler.class,
                dev.madeinabyss.kernel.terrain.natural.SupportedCaveTerrainSampler.class,
                dev.madeinabyss.kernel.terrain.natural.RouteVoxelIndex.class,
                dev.madeinabyss.kernel.terrain.natural.CaveInteriorOverlay.class,CaveInteriorBlockStates.class,
                dev.madeinabyss.kernel.terrain.natural.RetainedWaterSampler.class,
                dev.madeinabyss.kernel.terrain.natural.DenseEcologyOverlay.class,
                dev.madeinabyss.kernel.terrain.natural.EcologyMaskFields.class,CaveExpansionGeneration.class,
                WildernessBlockStates.class,dev.madeinabyss.kernel.terrain.CompiledTerrainSampler.class,
                dev.madeinabyss.kernel.terrain.trail.IndexedTrailOverlay.class))collectActualClass(root,classes);
        var out=new JsonArray();
        for(var entry:classes.entrySet()){
            String resource="/"+entry.getKey();var item=new JsonObject();item.addProperty("resource",entry.getKey());
            try(var input=entry.getValue().getResourceAsStream(resource)){
                byte[] bytes=Objects.requireNonNull(input).readAllBytes();item.addProperty("bytes",bytes.length);
                item.addProperty("sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
            item.addProperty("resourceURL",String.valueOf(entry.getValue().getResource(resource)));
            var source=entry.getValue().getProtectionDomain().getCodeSource();
            item.addProperty("classLocation",source==null?"none":String.valueOf(source.getLocation()));out.add(item);
        }
        return out;
    }
    private static void collectActualClass(Class<?> type,Map<String,Class<?>> classes){
        classes.put(type.getName().replace('.','/')+".class",type);
        for(var child:type.getDeclaredClasses())collectActualClass(child,classes);
    }
}
