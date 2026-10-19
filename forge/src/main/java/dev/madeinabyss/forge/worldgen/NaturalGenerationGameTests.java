package dev.madeinabyss.forge.worldgen;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import dev.madeinabyss.kernel.terrain.natural.CliffParameters;
import dev.madeinabyss.kernel.terrain.natural.NaturalBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/** Bounded native integration, not visual acceptance or full-Abyss ecology certification. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NaturalGenerationGameTests {
    private static JsonObject naturalManifest() {
        var manifest=GeneratedPreviewProfileGameTests.manifest();
        var natural=new JsonObject();natural.addProperty("schema",1);natural.addProperty("provenance","ORIGINAL");
        natural.addProperty("algorithmSha256",GeneratedPreviewProfile.actualNaturalAlgorithmSha256());
        natural.add("cliffs",new Gson().toJsonTree(CliffParameters.proposal(827)));
        manifest.add("naturalization",natural);return manifest;
    }
    private static GeneratedPreviewProfile profile(GameTestHelper h,JsonObject manifest) {
        return new GeneratedPreviewProfile(manifest,"test-generated",new FixedBiomeSource(h.getLevel().registryAccess()
                .registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)));
    }
    private static int index(BlockCoordinate p) {return (Math.floorMod(p.y(),16)<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);}
    private static Path diagnosticRoot(String name) {return Path.of("diagnostics","natural-generation",name,UUID.randomUUID().toString());}
    @GameTest(template="empty")
    public static void optionalNaturalProfileUsesSeparateStoreAndRetainsLegacyIdentity(GameTestHelper h) throws Exception {
        var oldManifest=GeneratedPreviewProfileGameTests.manifest();var original=oldManifest.deepCopy();
        var old=profile(h,oldManifest);var natural=profile(h,naturalManifest());var reloaded=profile(h,original);
        h.assertTrue(old.natural()==null&&natural.natural()!=null,"natural recipe is explicitly opt-in");
        h.assertTrue(old.fingerprint().equals(reloaded.fingerprint())&&old.generatorId().equals(reloaded.generatorId()),"legacy identity survives optional feature loading");
        h.assertTrue(oldManifest.equals(original),"profile loading cannot mutate the old manifest");
        h.assertTrue(old.generator().parameters().equals(natural.generator().parameters()),"global metre-scale baseline metrics retained");
        h.assertTrue(!old.fingerprint().equals(natural.fingerprint())&&!old.relativeStoreRoot().equals(natural.relativeStoreRoot()),"new ecology receives a distinct visited namespace");
        var key=CubeKey.fromBlock(old.feet());var snapshot=old.generator().generateCube(key);
        h.assertTrue(Arrays.equals(snapshot.states(),reloaded.generator().generateCube(key).states()),"old geological factory output is unchanged");
        Path root=diagnosticRoot("namespaces");var oldStore=new StableCubeStore(root.resolve(old.relativeStoreRoot()),old.generatorId(),1);
        oldStore.write(snapshot);byte[] bytes=Files.readAllBytes(oldStore.path(key));
        var newStore=new StableCubeStore(root.resolve(natural.relativeStoreRoot()),natural.generatorId(),1);
        h.assertTrue(newStore.read(key).isEmpty(),"new profile cannot borrow visited legacy cubes");
        newStore.write(natural.generator().generateCube(key));
        h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(oldStore.path(key))),"new generation preserves legacy file bytes");
        var stale=naturalManifest();stale.getAsJsonObject("naturalization").addProperty("algorithmSha256","0".repeat(64));
        boolean rejected=false;try{profile(h,stale);}catch(IllegalArgumentException expected){rejected=true;}
        h.assertTrue(rejected,"stale natural algorithm fingerprint refuses silently changed generation");h.succeed();
    }
    @GameTest(template="empty")
    public static void everyNativeNaturalStateHasScalarLightingStablePaletteAndCorrectAttachment(GameTestHelper h) throws Exception {
        var generator=profile(h,naturalManifest()).generator();var source=new GeneratedSkySource(generator,Map.of());
        int[] values=new int[4096];Arrays.fill(values,Block.getId(Blocks.AIR.defaultBlockState()));int n=0;
        var directions=new net.minecraft.world.level.block.state.properties.BooleanProperty[]{VineBlock.NORTH,VineBlock.SOUTH,VineBlock.EAST,VineBlock.WEST};
        for(var kind:NaturalBlock.values()) {
            var state=NaturalBlockStates.state(kind);int id=Block.getId(state);values[n++]=id;
            h.assertTrue(Block.stateById(id).equals(state),"native registry round-trip for "+kind);
            int opacity=state.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            h.assertTrue(source.opacity(id)==opacity&&source.emission(id)==state.getLightEmission(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),"generated light palette contains every natural kind: "+kind);
            h.assertTrue(!state.hasBlockEntity()&&!state.useShapeForLightOcclusion()&&opacity>=0&&opacity<=15,"supported scalar light contract: "+kind);
            if(kind.name().endsWith("_LEAVES"))h.assertTrue(state.getValue(LeavesBlock.PERSISTENT)&&opacity>0&&opacity<15,"placeholder canopy persists and attenuates sky");
            if(kind.name().startsWith("VINE_")) {
                int expected=kind==NaturalBlock.VINE_NORTH?0:kind==NaturalBlock.VINE_SOUTH?1:kind==NaturalBlock.VINE_EAST?2:3;
                for(int i=0;i<directions.length;i++)h.assertTrue(state.getValue(directions[i])==(i==expected),"exact attachment property on "+kind);
                h.assertTrue(!state.getValue(VineBlock.UP)&&opacity==0,"wall vine does not fabricate upward attachment or opaque lighting");
            }
        }
        var key=new CubeKey(-2,-7,-3);var store=new StableCubeStore(diagnosticRoot("palette"),"madeinabyss:natural-palette-test",1);
        store.write(new CubeSnapshot(key,values));
        h.assertTrue(Arrays.equals(values,store.read(key).orElseThrow().states()),"all natural properties survive stable named disk palette");h.succeed();
    }
    @GameTest(template="empty")
    public static void globalNaturalCubeAndSavedSkySourceShareSupportedSignedFeatures(GameTestHelper h) {
        var p=profile(h,naturalManifest());var generator=p.generator();
        // Actual sampler-derived vectors, away from the protected anchor and without finite habitat patches.
        BlockCoordinate[] positions={new BlockCoordinate(-809,233,39),new BlockCoordinate(-486,-100,-80),new BlockCoordinate(-485,-100,-56)};
        NaturalBlock[] kinds={NaturalBlock.SPRUCE_LOG,NaturalBlock.MOSS,NaturalBlock.VINE_WEST};
        for(int i=0;i<positions.length;i++) {
            var pos=positions[i];var expected=NaturalBlockStates.state(kinds[i]);var sampled=p.natural().sample(pos.x(),pos.y(),pos.z());
            h.assertTrue(p.natural().decoration(pos.x(),pos.y(),pos.z(),sampled).orElseThrow()==kinds[i],"actual global ecology vector: "+kinds[i]);
            h.assertTrue(generator.sampleBlock(pos.x(),pos.y(),pos.z()).equals(expected),"native source maps global ecology exactly");
            var cube=generator.generateCube(CubeKey.fromBlock(pos));
            h.assertTrue(cube.states()[index(pos)]==Block.getId(expected),"cube factory agrees at signed section boundary for "+kinds[i]);
            if(kinds[i]==NaturalBlock.SPRUCE_LOG)h.assertTrue(generator.sampleBlock(pos.x(),pos.y()-1,pos.z()).is(Blocks.GRASS_BLOCK),"actual island grass supports tree root");
            if(kinds[i]==NaturalBlock.MOSS)h.assertTrue(sampled instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.ROCK,"moss changes an actual rock surface");
            if(kinds[i]==NaturalBlock.VINE_WEST) {
                h.assertTrue(sampled instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.AIR,"vine occupies actual air");
                var support=p.natural().sample(pos.x()-1,pos.y(),pos.z());
                h.assertTrue(support instanceof TerrainSample.Known k&&(k.material()==TerrainMaterial.ROCK||k.material()==TerrainMaterial.SOIL||k.material()==TerrainMaterial.GRASS),"vine has real west support");
            }
        }
        var vine=positions[2];var key=CubeKey.fromBlock(vine);var saved=generator.generateCube(key).states();
        saved[index(vine)]=Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        var source=new GeneratedSkySource(generator,Map.of(key,saved));saved[index(vine)]=Block.getId(Blocks.AIR.defaultBlockState());
        h.assertTrue(Block.stateById(source.stateIdFast(vine.x(),vine.y(),vine.z())).is(Blocks.GOLD_BLOCK),"immutable saved edit wins over newly generated vine");
        h.assertTrue(source.opacity(source.stateIdFast(vine.x(),vine.y(),vine.z()))==15,"saved opacity remains authoritative");h.succeed();
    }
}
