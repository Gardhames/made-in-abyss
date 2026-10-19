package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.util.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedSceneInventoryGameTests {
    private static StableCubeStore store() throws Exception { var root=java.nio.file.Path.of("diagnostics","generated-scene-stores",UUID.randomUUID().toString());Files.createDirectories(root);return new StableCubeStore(root,"madeinabyss:inventory_test",1); }
    private static CubeSnapshot cube(CubeKey key) { int[] ids=new int[4096]; Arrays.fill(ids,Block.getId(Blocks.AIR.defaultBlockState()));ids[0]=Block.getId(Blocks.STONE.defaultBlockState());return new CubeSnapshot(key,ids); }
    @GameTest(template="empty")
    public static void actualLoadedGenerationDependencyIdentityIncludesForgeAndKernel(GameTestHelper h) {
        String identity=GeneratedInventoryCodeIdentity.get();
        h.assertTrue(identity.matches("[0-9a-f]{64}")&&identity.equals(GeneratedInventoryCodeIdentity.get()),"actual loaded project dependency resources provide stable cache identity in Forge runtime");h.succeed();
    }
    @GameTest(template="empty")
    public static void savedNegativeOutsideResidentSceneRemainsExact(GameTestHelper h) throws Exception {
        var store=store();var keys=List.of(new CubeKey(-400,-80,-120),new CubeKey(0,-1,0));
        var bytes=new ArrayList<byte[]>(); for(var key:keys) {store.write(cube(key));bytes.add(Files.readAllBytes(store.path(key)));}
        var biomes=h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var inventory=GeneratedSceneInventory.capture(biomes,store);
        h.assertTrue(inventory.cubes().size()==2,"all saved XYZ, including outside current windows");
        for(int i=0;i<keys.size();i++) {
            h.assertTrue(Arrays.equals(bytes.get(i),Files.readAllBytes(store.path(keys.get(i)))),"capture never rewrites saves");
            var frame=inventory.frames().get(i);var buf=new FriendlyByteBuf(Unpooled.wrappedBuffer(frame.bytes()));
            try {var decoded=CubeSectionPacketCodec.decode(buf,biomes);h.assertTrue(decoded.key().equals(keys.get(i)),"signed XYZ preserved");h.assertTrue(decoded.section().getBlockState(0,0,0).is(Blocks.STONE),"saved build geometry preserved");} finally {buf.release();}
        }
        byte[] frame=inventory.frames().get(0).bytes();frame[0]^=1;
        h.assertTrue(frame[0]!=inventory.frames().get(0).bytes()[0],"frame is immutable");h.succeed();
    }
    @GameTest(template="empty")
    public static void corruptVisitedCubeRefusesWholeSceneWithoutRewrite(GameTestHelper h) throws Exception {
        var store=store();var key=new CubeKey(-3,-80,-4);store.write(cube(key));var bad=Files.readAllBytes(store.path(key));bad[bad.length-1]^=1;Files.write(store.path(key),bad);
        try {GeneratedSceneInventory.capture(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME),store);h.fail("corrupt saved cube must refuse inventory");}catch(java.io.IOException expected) {h.assertTrue(Arrays.equals(bad,Files.readAllBytes(store.path(key))),"no regeneration or rewrite");}h.succeed();
    }
    @GameTest(template="empty")
    public static void sceneSequencePublishesOnlyAfterExactEnd(GameTestHelper h) throws Exception {
        var store=store();store.write(cube(new CubeKey(-1,-2,-3)));var inventory=GeneratedSceneInventory.capture(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME),store);
        var id=UUID.randomUUID();var receiver=new GeneratedSceneNetwork.Receiver();var begin=new GeneratedSceneNetwork.Begin(id,"a".repeat(64),1,inventory.encodedBytes());
        h.assertTrue(receiver.accept(begin).isEmpty(),"begin does not publish");h.assertTrue(receiver.accept(new GeneratedSceneNetwork.Batch(id,0,inventory.frames())).isEmpty(),"batch does not publish");
        var result=receiver.accept(new GeneratedSceneNetwork.End(id,1,inventory.encodedBytes()));h.assertTrue(result.orElseThrow().frames().size()==1,"only complete scene publishes");h.succeed();
    }
    @GameTest(template="empty")
    public static void sceneBoundsAndSequenceReject(GameTestHelper h) throws Exception {
        var id=UUID.randomUUID();var receiver=new GeneratedSceneNetwork.Receiver();
        try {receiver.accept(new GeneratedSceneNetwork.Begin(id,"b".repeat(64),8193,0));h.fail("oversized count rejected");}catch(IllegalArgumentException expected) { }
        receiver.accept(new GeneratedSceneNetwork.Begin(id,"b".repeat(64),1,25));
        try {receiver.accept(new GeneratedSceneNetwork.End(id,1,25));h.fail("missing batch rejected");}catch(IllegalArgumentException expected) { }
        var buf=new FriendlyByteBuf(Unpooled.buffer());try {buf.writeByte(0);buf.writeUUID(id);buf.writeUtf("b".repeat(64));buf.writeInt(-1);buf.writeLong(0);try {GeneratedSceneNetwork.decode(buf);h.fail("negative wire count rejected");}catch(IllegalArgumentException expected) { }}finally {buf.release();}h.succeed();
    }
    @GameTest(template="empty")
    public static void namespaceProvenanceAndCanonicalNamesRefuse(GameTestHelper h) throws Exception {
        var store=store();var key=new CubeKey(-3,-80,-4);store.write(cube(key));
        var wrong=new StableCubeStore(store.path(key).getParent().getParent(),"madeinabyss:other_inventory",1);
        try {GeneratedSceneInventory.capture(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME),wrong);h.fail("wrong provenance refused");}catch(java.io.IOException expected) { }
        Files.write(store.path(key).resolveSibling("c.-03.-80.-4.miacube"),Files.readAllBytes(store.path(key)));
        try {GeneratedSceneInventory.capture(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME),store);h.fail("noncanonical alias cannot be silently omitted");}catch(java.io.IOException expected) { }h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void inventoryCubeLimitRefusesBeforeReadingFiles(GameTestHelper h) throws Exception {
        var store=store();Files.createDirectories(store.path(new CubeKey(0,0,0)).getParent());
        for(int i=0;i<8193;i++)Files.createFile(store.path(new CubeKey(i,-80,-1)));
        try {GeneratedSceneInventory.capture(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME),store);h.fail("oversized inventory refused atomically");}catch(java.io.IOException expected) {h.assertTrue(expected.getMessage().contains("cube count"),"preflight count before payload decode");}h.succeed();
    }
    @GameTest(template="empty")
    public static void wireRoundtripAndDuplicateOutOfOrderRefuse(GameTestHelper h) throws Exception {
        var store=store();store.write(cube(new CubeKey(-2,-3,-4)));var inventory=GeneratedSceneInventory.capture(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME),store);
        var id=UUID.randomUUID();var begin=new GeneratedSceneNetwork.Begin(id,"c".repeat(64),2,inventory.encodedBytes()*2);var frame=inventory.frames().get(0);
        var receiver=new GeneratedSceneNetwork.Receiver();receiver.accept(begin);
        var batch=new GeneratedSceneNetwork.Batch(id,0,List.of(frame));var buf=new FriendlyByteBuf(Unpooled.buffer());
        try {GeneratedSceneNetwork.encode(batch,buf);var decoded=(GeneratedSceneNetwork.Batch)GeneratedSceneNetwork.decode(buf);h.assertTrue(decoded.firstIndex()==0&&decoded.frames().get(0).key().equals(frame.key())&&Arrays.equals(decoded.frames().get(0).bytes(),frame.bytes()),"actual signed frame roundtrip");}finally {buf.release();}
        receiver.accept(batch);
        try {receiver.accept(new GeneratedSceneNetwork.Batch(id,1,List.of(frame)));h.fail("duplicate XYZ across batches refused");}catch(IllegalArgumentException expected) { }
        receiver.accept(begin);
        try {receiver.accept(new GeneratedSceneNetwork.Batch(id,1,List.of(frame)));h.fail("out of order index refused");}catch(IllegalArgumentException expected) { }
        receiver.accept(begin);
        try {receiver.accept(new GeneratedSceneNetwork.Batch(UUID.randomUUID(),0,List.of(frame)));h.fail("foreign session refused");}catch(IllegalArgumentException expected) { }h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void proceduralInventoryBeyondWireLimitKeepsDistantEditWithoutRewrite(GameTestHelper h) throws Exception {
        var store=store();
        for(int i=0;i<8193;i++)store.write(cube(new CubeKey(i,-80,-1)));
        var editKey=new CubeKey(-400,-80,-120);var edited=cube(editKey).states();edited[1]=Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState());
        store.write(new CubeSnapshot(editKey,edited));
        var pristinePath=store.path(new CubeKey(8192,-80,-1));var pristineBytes=Files.readAllBytes(pristinePath);var editBytes=Files.readAllBytes(store.path(editKey));
        var biomes=h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        long start=System.nanoTime();
        var inventory=GeneratedSceneInventory.capture(biomes,store,GeneratedSceneInventoryGameTests::cube,"d".repeat(64));
        long coldMillis=(System.nanoTime()-start)/1_000_000;start=System.nanoTime();
        var warm=GeneratedSceneInventory.capture(biomes,store,key->{throw new AssertionError("Warm expected digest must not regenerate: "+key);},"d".repeat(64));
        long warmMillis=(System.nanoTime()-start)/1_000_000;
        h.assertTrue(inventory.cacheStats().expectedGenerated()==8194&&warm.cacheStats().expectedHits()==8194,"cold then warm inventory includes all saved cubes beyond wire limit");
        h.assertTrue(warm.cacheStats().validatedCubes()==8194&&warm.cubes().size()==1&&Arrays.equals(edited,warm.cubes().get(0).states()),"warm validation and distant edit exact");
        System.out.println("MIA_INVENTORY_DIGEST_BENCH cubes=8194 coldMs="+coldMillis+" warmMs="+warmMillis+" warmGenerated="+warm.cacheStats().expectedGenerated());
        h.assertTrue(inventory.cubes().size()==1&&inventory.cubes().get(0).key().equals(editKey),"only distant nonprocedural geometry enters bounded transfer");
        h.assertTrue(Arrays.equals(edited,inventory.cubes().get(0).states()),"edit remains exact");
        h.assertTrue(Arrays.equals(pristineBytes,Files.readAllBytes(pristinePath))&&Arrays.equals(editBytes,Files.readAllBytes(store.path(editKey))),"filter never rewrites or removes saved cubes");
        var receiver=new GeneratedSceneNetwork.Receiver();int completed=0;
        for(var message:GeneratedSceneNetwork.prepare(UUID.randomUUID(),"d".repeat(64),inventory))if(receiver.accept(message).isPresent())completed++;
        h.assertTrue(completed==1,"filtered inventory remains valid atomic wire scene");
        try {
            GeneratedSceneInventory.capture(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME),store,key->{
                int[] air=new int[4096];Arrays.fill(air,Block.getId(Blocks.AIR.defaultBlockState()));return new CubeSnapshot(key,air);
            });h.fail("nonprocedural inventory still enforces retained cube bound");
        }catch(java.io.IOException expected) {h.assertTrue(expected.getMessage().contains("cube count"),"retained geometry overflow refused");}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void warmProceduralInventoryValidatesSavedEditsAndCorruption(GameTestHelper h)throws Exception {
        var store=store();var key=new CubeKey(-3,-80,-4);store.write(cube(key));
        var biomes=h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);String identity="f".repeat(64);
        h.assertTrue(GeneratedSceneInventory.capture(biomes,store,GeneratedSceneInventoryGameTests::cube,identity).cubes().isEmpty(),"cold pristine omitted");
        var edited=cube(key).states();edited[1]=Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState());store.write(new CubeSnapshot(key,edited));
        var saved=Files.readAllBytes(store.path(key));
        var warm=GeneratedSceneInventory.capture(biomes,store,k->{throw new AssertionError("warm generation");},identity);
        h.assertTrue(warm.cubes().size()==1&&Arrays.equals(warm.cubes().get(0).states(),edited),"edited saved bytes use original expected baseline");
        h.assertTrue(Arrays.equals(saved,Files.readAllBytes(store.path(key))),"warm classification cannot rewrite saved edits");
        byte[] bad=saved.clone();bad[bad.length-1]^=1;Files.write(store.path(key),bad);
        try{GeneratedSceneInventory.capture(biomes,store,k->{throw new AssertionError("corrupt save must reject before generator");},identity);h.fail("warm digest cannot hide corruption");}
        catch(java.io.IOException expected){h.assertTrue(Arrays.equals(bad,Files.readAllBytes(store.path(key))),"invalid save stays untouched");}
        Files.write(store.path(key),saved);Files.write(store.path(key).resolveSibling("c.-03.-80.-4.miacube"),saved);
        try{GeneratedSceneInventory.capture(biomes,store,GeneratedSceneInventoryGameTests::cube,identity);h.fail("warm digest cannot hide noncanonical alias");}catch(java.io.IOException expected){ }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void coldSavedEditCannotSeedExpectedGeometry(GameTestHelper h)throws Exception {
        var store=store();var key=new CubeKey(-3,-80,-4);var edited=cube(key).states();edited[1]=Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState());
        store.write(new CubeSnapshot(key,edited));var bytes=Files.readAllBytes(store.path(key));var biomes=h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var cold=GeneratedSceneInventory.capture(biomes,store,GeneratedSceneInventoryGameTests::cube,"e".repeat(64));
        var warm=GeneratedSceneInventory.capture(biomes,store,k->{throw new AssertionError("baseline should persist even when first saved geometry is edited");},"e".repeat(64));
        h.assertTrue(cold.cubes().size()==1&&warm.cubes().size()==1&&Arrays.equals(edited,warm.cubes().get(0).states()),"baseline derives only from generated expected states");
        h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(store.path(key))),"cold and warm preserve edited file");h.succeed();
    }
    @GameTest(template="empty")
    public static void proceduralGeometryOmissionPreservesChestInventoryAndPendingTicks(GameTestHelper h)throws Exception {
        var store=store();var key=new CubeKey(-3,-80,-4);var position=new dev.madeinabyss.kernel.BlockCoordinate(-48,-1280,-64);
        int[] geometry=cube(key).states();geometry[0]=Block.getId(Blocks.CHEST.defaultBlockState());
        var chest=new net.minecraft.world.level.block.entity.ChestBlockEntity(new net.minecraft.core.BlockPos(position.x(),position.y(),position.z()),Blocks.CHEST.defaultBlockState());
        chest.setItem(4,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,23));
        var output=new java.io.ByteArrayOutputStream();net.minecraft.nbt.NbtIo.write(chest.saveWithFullMetadata(),new java.io.DataOutputStream(output));
        var entity=new dev.madeinabyss.kernel.cube.SavedCubeBlockEntity(position,"minecraft:chest",output.toByteArray());
        var tick=new dev.madeinabyss.kernel.tick.SavedCubeTick(dev.madeinabyss.kernel.tick.SavedCubeTick.Kind.BLOCK,"minecraft:chest",position,500,0,1);
        store.write(new CubeSnapshot(key,geometry,List.of(tick),List.of(entity)));byte[] before=Files.readAllBytes(store.path(key));
        var biomes=h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var cold=GeneratedSceneInventory.capture(biomes,store,k->new CubeSnapshot(k,geometry),"1".repeat(64));
        var warm=GeneratedSceneInventory.capture(biomes,store,k->{throw new AssertionError("warm expected geometry");},"1".repeat(64));
        var restored=store.read(key).orElseThrow();
        h.assertTrue(cold.cubes().isEmpty()&&warm.cubes().isEmpty(),"only observer geometry is omitted");
        h.assertTrue(Arrays.equals(before,Files.readAllBytes(store.path(key)))&&restored.ticks().equals(List.of(tick))&&restored.blockEntities().equals(List.of(entity)),"saved native chest payload and pending ticks survive byte-exact");h.succeed();
    }
    @GameTest(template="empty")
    public static void proceduralFilterValidatesCorruptionAndAliasesBeforeOmission(GameTestHelper h) throws Exception {
        var store=store();var key=new CubeKey(-3,-80,-4);store.write(cube(key));
        var bytes=Files.readAllBytes(store.path(key));var bad=bytes.clone();bad[bad.length-1]^=1;Files.write(store.path(key),bad);
        var biomes=h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        try {GeneratedSceneInventory.capture(biomes,store,GeneratedSceneInventoryGameTests::cube);h.fail("corrupt procedural cube must refuse before omission");}catch(java.io.IOException expected) { }
        h.assertTrue(Arrays.equals(bad,Files.readAllBytes(store.path(key))),"corruption remains untouched");
        Files.write(store.path(key),bytes);Files.write(store.path(key).resolveSibling("c.-03.-80.-4.miacube"),bytes);
        try {GeneratedSceneInventory.capture(biomes,store,GeneratedSceneInventoryGameTests::cube);h.fail("alias cannot be hidden by procedural filter");}catch(java.io.IOException expected) { }
        var wrong=new StableCubeStore(store.path(key).getParent().getParent(),"madeinabyss:other_inventory",1);
        Files.delete(store.path(key).resolveSibling("c.-03.-80.-4.miacube"));
        try {GeneratedSceneInventory.capture(biomes,wrong,GeneratedSceneInventoryGameTests::cube);h.fail("wrong provenance cannot be hidden by procedural filter");}catch(java.io.IOException expected) { }h.succeed();
    }
}
