package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.Arrays;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WildernessGenerationGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void acceleratedComposedWorldKeepsExactCubesAndSavedIdentity(GameTestHelper h){
        var m=GeneratedPreviewProfileGameTests.manifest();
        var n=new JsonObject();n.addProperty("schema",1);n.addProperty("provenance","ORIGINAL");n.addProperty("algorithmSha256",GeneratedPreviewProfile.actualNaturalAlgorithmSha256());
        n.add("cliffs",new com.google.gson.Gson().toJsonTree(new dev.madeinabyss.kernel.terrain.natural.CliffParameters(827,12,64,144,28,4,56,10,18,48)));
        m.add("naturalization",n);m.add("wilderness",WildernessGeneration.proposal(827,net.minecraftforge.fml.ModList.get().isLoaded("alexscaves")));
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var before=new GeneratedPreviewProfile(m,"test-generated",biomes,false);
        var after=new GeneratedPreviewProfile(m,"test-generated",biomes,true);
        h.assertTrue(before.fingerprint().equals(after.fingerprint()),"runtime calculation strategy must not fork terrain identity");
        for(var key:java.util.List.of(new CubeKey(13,-42,-26),new CubeKey(13,-41,-26),new CubeKey(21,-40,-26),new CubeKey(20,-38,-29),new CubeKey(-37,-56,5),new CubeKey(-1,-1,-1),new CubeKey(30,3,-1),new CubeKey(0,-80,0)))
            h.assertTrue(java.util.Arrays.equals(before.generator().generateCube(key).states(),after.generator().generateCube(key).states()),"exact composed cave/water/author vegetation cube differs "+key);
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void broadShelfEnvelopeRetainsWholePoolAndSoilBottom(GameTestHelper h){
        var p=new dev.madeinabyss.kernel.terrain.TerrainParameters(20261003,
                new dev.madeinabyss.kernel.terrain.TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
                new dev.madeinabyss.kernel.terrain.TerrainParameters.Cavity(500,64,-1286,650,180,170,280),
                new dev.madeinabyss.kernel.terrain.TerrainParameters.Noise(140,20,60,70));
        var base=new dev.madeinabyss.kernel.terrain.ParametricTerrainSampler(p);
        var cliff=new dev.madeinabyss.kernel.terrain.natural.CliffTerrainSampler(p,
                new dev.madeinabyss.kernel.terrain.natural.CliffParameters(827,12,64,144,28,4,56,10,18,48),base::sample,(x,y,z)->false);
        var next=new WildernessGeneration(p,WildernessGeneration.proposal(827,false),cliff::sample,(x,y,z)->false);
        for(int y=-666;y<=-665;y++)h.assertTrue(next.sample(216,y,-401) instanceof dev.madeinabyss.kernel.terrain.TerrainSample.Known k
                &&k.material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER,"broad supported shelf must retain both water depths at Y="+y);
        h.assertTrue(next.sample(216,-667,-401) instanceof dev.madeinabyss.kernel.terrain.TerrainSample.Known k
                &&k.material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.SOIL,"retained pool floor must remain solid");h.succeed();
    }
    private static GeneratedPreviewProfile load(GameTestHelper h,JsonObject m){
        return new GeneratedPreviewProfile(m,"test-generated",new FixedBiomeSource(h.getLevel().registryAccess()
                .registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)));
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void wildernessIsVersionedAndCannotRewriteLegacyProfile(GameTestHelper h){
        var manifest=GeneratedPreviewProfileGameTests.manifest();var original=manifest.deepCopy();
        var legacy=load(h,manifest);var next=manifest.deepCopy();
        next.add("wilderness",WildernessGeneration.proposal(827,false));
        var wilderness=load(h,next);
        h.assertTrue(wilderness.wilderness()!=null,"new recipe must be consumed, never silently ignored");
        h.assertTrue(!legacy.relativeStoreRoot().equals(wilderness.relativeStoreRoot()),"new geometry owns separate visited namespace");
        var again=load(h,original);var cube=CubeKey.fromBlock(legacy.feet());
        h.assertTrue(legacy.fingerprint().equals(again.fingerprint())&&Arrays.equals(legacy.generator().generateCube(cube).states(),again.generator().generateCube(cube).states()),"legacy identity and exact blocks preserved");
        h.assertTrue(original.equals(manifest),"loading leaves old manifest immutable");
        next.getAsJsonObject("wilderness").addProperty("algorithmSha256","0".repeat(64));
        boolean refused=false;try{load(h,next);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"stale geometry fingerprint must refuse generation");h.succeed();
    }
    @GameTest(template="empty")
    public static void requestedExternalAssetsFailClosedWithoutDependency(GameTestHelper h){
        if(net.minecraftforge.fml.ModList.get().isLoaded("alexscaves")){h.succeed();return;}
        boolean refused=false;try{new WildernessBlockStates(true);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"explicit Alex palette must not silently produce vanilla substitutes");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void authorPaletteSurvivesStableStoreAndScalarLighting(GameTestHelper h) throws Exception {
        boolean alex=net.minecraftforge.fml.ModList.get().isLoaded("alexscaves");
        var manifest=GeneratedPreviewProfileGameTests.manifest();manifest.add("wilderness",WildernessGeneration.proposal(827,alex));
        var profile=load(h,manifest);var source=new GeneratedSkySource(profile.generator(),java.util.Map.of());
        int[] states=new int[4096];java.util.Arrays.fill(states,net.minecraft.world.level.block.Block.getId(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
        int index=0;for(var block:profile.wilderness().palette().palette()){
            int id=net.minecraft.world.level.block.Block.getId(block);states[index++]=id;
            h.assertTrue(net.minecraft.world.level.block.Block.stateById(id).equals(block),"author block state roundtrip");
            h.assertTrue(!block.hasBlockEntity(),"initial palette must not introduce unmanaged block entities");
            h.assertTrue(source.opacity(id)>=0&&source.opacity(id)<=15,"registered author lighting admitted");
        }
        var key=new CubeKey(-4,-43,2);var snapshot=new dev.madeinabyss.kernel.cube.CubeSnapshot(key,states);
        var path=java.nio.file.Path.of("diagnostics","wilderness-palette",java.util.UUID.randomUUID().toString());
        var store=new dev.madeinabyss.forge.cube.StableCubeStore(path,profile.generatorId(),1);store.write(snapshot);
        h.assertTrue(java.util.Arrays.equals(states,store.read(key).orElseThrow().states()),"stable author registry palette survives disk roundtrip at negative cubeY");h.succeed();
    }
}
