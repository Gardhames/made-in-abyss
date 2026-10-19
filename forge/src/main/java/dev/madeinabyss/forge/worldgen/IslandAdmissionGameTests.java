package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepVisualNetwork;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraftforge.gametest.*;

/** Domain integration invariants; neither gameplay nor visual acceptance. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IslandAdmissionGameTests {
    @GameTest(template="empty")
    public static void frozenV12FingerprintAndSignedRouteOracle(GameTestHelper helper) throws Exception {
        String configured=System.getenv("MIA_V12_LEGACY_PROFILE");
        if(configured==null||configured.isBlank()){helper.succeed();return;}
        var m=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(configured))).getAsJsonObject();
        var biomes=new FixedBiomeSource(helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var legacy=new GeneratedPreviewProfile(m,m.get("profileId").getAsString(),biomes);
        helper.assertTrue(legacy.fingerprint().equals("e2efcfba3e6a716d859bcc97716824851eb6b890692444c0b161cd89c63cc401"),"frozen v12 exact fingerprint/store preserved");
        var changed=m.deepCopy();changed.add("islandSurface",IslandSurfaceGeneration.proposal());
        var island=new GeneratedPreviewProfile(changed,changed.get("profileId").getAsString(),biomes);
        for(var key:java.util.List.of(CubeKey.fromBlock(legacy.feet()),new CubeKey(-30,-60,-1),new CubeKey(29,-50,0)))
            helper.assertTrue(java.util.Arrays.equals(legacy.generator().generateCube(key).states(),island.generator().generateCube(key).states()),"v12 signed deep oracle byte-identical");
        helper.succeed();
    }
    @GameTest(template="empty")
    public static void legacyDomainAndVisualFootprintAreUnchanged(GameTestHelper helper) {
        var manifest=GeneratedPreviewProfileGameTests.manifest();
        var profile=new GeneratedPreviewProfile(manifest,"test-generated",new FixedBiomeSource(helper.getLevel()
                .registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)));
        helper.assertTrue(profile.generator().islandSurface()==null,"absent optional island retains legacy branch");
        helper.assertTrue(profile.generator().minimumKnownY()==-1286,"legacy exact geological lower bound");
        helper.assertTrue(profile.minimumKnownCubeY()==-80,"only complete legacy cubes admitted");
        helper.assertTrue(profile.visualMinimumCubeY(new CubeKey(-37,-75,5))==-80,"legacy visual rectangle unchanged");
        helper.assertTrue(profile.generator().supportsCube(new CubeKey(-37,-80,5)),"negative complete known cube");
        helper.assertTrue(!profile.generator().supportsCube(new CubeKey(-37,-81,5)),"partial unknown bottom cube refused");
        try { profile.requireVisualDomain(new BlockCoordinate(-590,-1280,80));
            helper.fail("complete physics halo must reject before residency mutation");
        } catch(IllegalArgumentException expected) { }
        helper.succeed();
    }
    @GameTest(template="empty")
    public static void islandOceanDomainRetainsProtectedGeologyAndAtomicHalo(GameTestHelper helper) {
        var m=GeneratedPreviewProfileGameTests.manifest();
        var biomes=new FixedBiomeSource(helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var legacy=new GeneratedPreviewProfile(m,"test-generated",biomes);
        m.add("islandSurface",IslandSurfaceGeneration.proposal());
        var island=new GeneratedPreviewProfile(m,"test-generated",biomes);
        helper.assertTrue(!island.fingerprint().equals(legacy.fingerprint()),"optional island creates new immutable store identity");
        helper.assertTrue(island.generator().parameters().equals(legacy.generator().parameters()),"legacy cavity metrics unchanged");
        for(var key:java.util.List.of(new CubeKey(0,-75,0),new CubeKey(-30,-60,-1),new CubeKey(29,-50,0)))
            helper.assertTrue(java.util.Arrays.equals(legacy.generator().generateCube(key).states(),island.generator().generateCube(key).states()),
                    "complete protected deep geology remains byte-identical at signed coordinates");
        helper.assertTrue(island.generator().knownLowerY(959,0)==-1286 && island.generator().knownLowerY(960,0)==-5000,
                "exact local lower-bound boundary");
        helper.assertTrue(island.generator().minKnownYForBox(-976,-15,-961,15)==-5000,"negative exterior complete box supported");
        helper.assertTrue(island.generator().minKnownYForBox(-976,-15,-959,15)==-1286,"closest edge clips mixed box");
        var ocean=new CubeKey(-100,-250,0);
        helper.assertTrue(island.generator().generateCube(ocean).states().length==4096,"actual deep ocean full cube available");
        island.requireVisualDomain(new BlockCoordinate(-1600,-4000,0));
        helper.assertTrue(island.visualMinimumCubeY(ocean)==-312,"ocean visual rectangular window uses complete -5000 envelope");
        try { island.requireVisualDomain(new BlockCoordinate(-960,-4000,0));helper.fail("halo crosses central unknown at signed edge"); }
        catch(IllegalArgumentException expected) { }
        try { island.generator().sampleBlock(959,-1287,0);helper.fail("unknown never AIR"); }
        catch(TerrainUnavailableException expected) { }
        var keys=DeepVisualNetwork.windowKeys(ocean,island.visualMinimumCubeY(ocean));
        for(var key:keys)helper.assertTrue(island.generator().supportsCube(key),"every transmitted ocean cube supported");
        helper.succeed();
    }
    @GameTest(template="empty")
    public static void visualProtocolRefusesMissingKnownFramesAndUnadmittedDomainChange(GameTestHelper helper) {
        var center=new CubeKey(0,-75,0);var token=java.util.UUID.randomUUID();int floor=-80;
        var expected=DeepVisualNetwork.windowKeys(center,floor);
        var frames=new java.util.ArrayList<byte[]>();
        var biomes=helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        for(var key:expected){
            var section=new net.minecraft.world.level.chunk.LevelChunkSection(biomes);
            var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{dev.madeinabyss.forge.cube.CubeSectionPacketCodec.encode(buffer,key,section);
                var bytes=new byte[buffer.readableBytes()];buffer.readBytes(bytes);frames.add(bytes);
            }finally{buffer.release();}
        }
        try{
            var receiver=helper.getLevel();
            dev.madeinabyss.forge.probe.client.ClientDeepVisual.begin(token,receiver);
            dev.madeinabyss.forge.probe.client.ClientDeepVisual.receive(receiver,new DeepVisualNetwork.Window(token,1,true,center,java.util.List.of(),frames,floor));
            helper.assertTrue(dev.madeinabyss.forge.probe.client.ClientDeepVisual.revision()==1,"initial complete rectangle accepted");
            dev.madeinabyss.forge.probe.client.ClientDeepVisual.receive(receiver,new DeepVisualNetwork.Window(token,2,false,center,java.util.List.of(),java.util.List.of(),-81));
            helper.assertTrue(dev.madeinabyss.forge.probe.client.ClientDeepVisual.revision()==1,"unadmitted bound alteration rejected transactionally");
            helper.assertTrue(dev.madeinabyss.forge.probe.client.ClientDeepVisual.space(receiver).orElseThrow().loadedCubeCount()==expected.size(),"rejected domain retains prior geometry");
            try{DeepVisualNetwork.validate(new DeepVisualNetwork.Window(token,1,true,center,java.util.List.of(),frames.subList(1,frames.size()),floor));
                helper.fail("missing supported cube rejected");
            }catch(IllegalArgumentException rejection){}
        }finally{dev.madeinabyss.forge.probe.client.ClientDeepVisual.clear();}
        helper.succeed();
    }
    @GameTest(template="empty")
    public static void visualScalarIncludesExactlyMandatoryPhysicsFootprint(GameTestHelper helper) {
        var center=new CubeKey(-61,-250,0);
        var full=DeepVisualNetwork.windowKeys(center);
        helper.assertTrue(full.size()==243,"protocol bounded at 243");
        for(var key:full) helper.assertTrue(Math.abs(key.x()-center.x())<=1 && Math.abs(key.z()-center.z())<=1,
                "visual and physics share XZ halo, conservative rectangle cannot deny a physics-admissible ocean location");
        var clipped=DeepVisualNetwork.windowKeys(center,-252);
        helper.assertTrue(clipped.size()==72,"clip removes excluded UNKNOWN without replacement air");
        for(var key:clipped) helper.assertTrue(key.y()>=-252,"only complete supported Y cubes transmitted");
        helper.succeed();
    }
}
