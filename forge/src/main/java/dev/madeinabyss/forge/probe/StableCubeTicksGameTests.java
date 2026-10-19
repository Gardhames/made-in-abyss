package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.PersistentCubeSpace;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.tick.SavedCubeTick;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StableCubeTicksGameTests {
    private static final CubeKey KEY = new CubeKey(-2, -1251, -2);
    private static final BlockCoordinate POS = new BlockCoordinate(-17, -20001, -17);
    private static final String GENERATOR = "madeinabyss:tick_store_test";

    // Break caught: a format upgrade reads legacy as missing, drops properties, or loses pending queues.
    @GameTest(template = "empty")
    public static void legacyReadIsInertAndExplicitWritePreservesStatesAndTicks(GameTestHelper helper) throws Exception {
        var store = store(); byte[] legacy = fixture(1, List.of());
        Files.createDirectories(store.path(KEY).getParent()); Files.write(store.path(KEY), legacy);
        var old = store.read(KEY).orElseThrow();
        helper.assertTrue(old.ticks().isEmpty() && Arrays.equals(legacy, Files.readAllBytes(store.path(KEY))), "legacy read never rewrites or manufactures ticks");
        int[] states = old.states(); states[4095] = Block.getId(Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
        var ticks = List.of(tick(SavedCubeTick.Kind.BLOCK, "minecraft:oak_stairs", -7, -3, Long.MIN_VALUE),
                tick(SavedCubeTick.Kind.FLUID, "minecraft:water", 40, 3, Long.MAX_VALUE),
                new SavedCubeTick(SavedCubeTick.Kind.FLUID, "minecraft:flowing_water", new BlockCoordinate(-32, -20016, -32), 5000000000L, 0, -2));
        store.write(new CubeSnapshot(KEY, states, ticks));
        var restored = new StableCubeStore(store.path(KEY).getParent().getParent(), GENERATOR, 1).read(KEY).orElseThrow();
        helper.assertTrue(Arrays.equals(states, restored.states()) && ticks.equals(restored.ticks()), "properties, source and flowing fluid identities, signed long delay/order survive fresh decode");
        helper.succeed();
    }

    // Break caught: encoder and decoder agree on a wrong layout, or decoded tuples expose mutable state.
    @GameTest(template = "empty")
    public static void independentV2DecodeRetainsImmutableTuple(GameTestHelper helper) throws Exception {
        var store = store(); var ticks = List.of(tick(SavedCubeTick.Kind.FLUID, "minecraft:water", -1, 2, -99));
        Files.createDirectories(store.path(KEY).getParent()); Files.write(store.path(KEY), fixture(2, ticks));
        var first = store.read(KEY).orElseThrow(); var second = store.read(KEY).orElseThrow();
        helper.assertTrue(first.ticks().equals(ticks) && second.ticks().equals(ticks), "independent v2 frame decodes exact queue");
        int[] exposed = first.states(); exposed[0] = Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        helper.assertTrue(second.states()[0] == Block.getId(Blocks.AIR.defaultBlockState()) && first.states()[0] == second.states()[0], "decoded states are defensive copies");
        try { first.ticks().clear(); helper.fail("decoded queue must be immutable"); } catch (UnsupportedOperationException expected) { }
        helper.succeed();
    }

    // Break caught: count arithmetic rejects the supported boundary or conflates block/fluid identity.
    @GameTest(template = "empty")
    public static void supportedTickBudgetAndKindIdentitySurvive(GameTestHelper helper) throws Exception {
        var store=store(); var ticks=new ArrayList<SavedCubeTick>();
        for(int i=0;i<511;i++)ticks.add(new SavedCubeTick(SavedCubeTick.Kind.FLUID,"minecraft:water",new BlockCoordinate(-32+(i&15),-20016+(i>>8),-32+((i>>4)&15)),i,0,i));
        ticks.add(new SavedCubeTick(SavedCubeTick.Kind.BLOCK,"minecraft:water",new BlockCoordinate(-32,-20016,-32),0,0,-1));
        store.write(new CubeSnapshot(KEY,states(),ticks));
        helper.assertTrue(store.read(KEY).orElseThrow().ticks().equals(ticks),"512 records accepted; block and fluid with identical registry text/XYZ are independent");
        helper.succeed();
    }

    // Break caught: registry default fallback or partial corrupt frames become usable snapshots.
    @GameTest(template = "empty")
    public static void malformedTickRecordsNeverPublishOrOverwrite(GameTestHelper helper) throws Exception {
        var store = store(); Files.createDirectories(store.path(KEY).getParent());
        var valid = tick(SavedCubeTick.Kind.FLUID, "minecraft:water", 5, 0, 1);
        var duplicates = List.of(valid, tick(SavedCubeTick.Kind.FLUID, "minecraft:water", 1, -3, 2));
        var overCap = new ArrayList<SavedCubeTick>();
        for(int i=0;i<513;i++) overCap.add(new SavedCubeTick(SavedCubeTick.Kind.FLUID,"minecraft:water",new BlockCoordinate(-32+(i&15),-20016+(i>>8),-32+((i>>4)&15)),1,0,i));
        byte[] badCrc = fixture(2, List.of(valid)); badCrc[badCrc.length-1] ^= 1;
        byte[] invalidKind = fixture(2,List.of(valid)); invalidKind[tickOffset()] = 2; checksum(invalidKind);
        byte[] invalidPriority = fixture(2,List.of(valid)); ByteBuffer.wrap(invalidPriority).putInt(tickOffset()+1+2+"minecraft:water".length()+12+8,4); checksum(invalidPriority);
        for(byte[] bad : new byte[][]{fixture(2,duplicates),fixture(2,overCap),fixture(2,List.of(tick(SavedCubeTick.Kind.FLUID,"minecraft:empty",1,0,0))),
                fixture(2,List.of(tick(SavedCubeTick.Kind.FLUID,"minecraft:missing_fluid",1,0,0))),fixture(2,List.of(tick(SavedCubeTick.Kind.BLOCK,"minecraft:water_missing",1,0,0))),
                rawOutside(),badCrc,invalidKind,invalidPriority,Arrays.copyOf(fixture(2,List.of(valid)),fixture(2,List.of(valid)).length-1)}) {
            Files.write(store.path(KEY),bad);
            try { store.read(KEY); helper.fail("invalid tick tuple must fail rather than publish"); } catch(IOException expected) { }
            try { store.write(new CubeSnapshot(KEY,states(),List.of(valid))); helper.fail("invalid old file must not be overwritten"); } catch(IOException expected) { }
            helper.assertTrue(Arrays.equals(bad,Files.readAllBytes(store.path(KEY))),"malformed visited file remains byte-for-byte intact");
        }
        helper.succeed();
    }

    // Break caught: unknown fluid encoding wipes prior states/queues or unloads before successful commit.
    @GameTest(template = "empty")
    public static void failedTickSaveKeepsPreviousFileAndResidentTuple(GameTestHelper helper) throws Exception {
        var store = store(); var valid = tick(SavedCubeTick.Kind.FLUID,"minecraft:water",17,0,7);
        store.write(new CubeSnapshot(KEY,states(),List.of(valid))); byte[] before=Files.readAllBytes(store.path(KEY));
        var invalid = tick(SavedCubeTick.Kind.FLUID,"minecraft:unregistered",17,0,7);
        var resident = new SparseCubeSpace(); resident.load(new CubeSnapshot(KEY,states(),List.of(invalid)));
        try { new PersistentCubeSpace(resident,store).saveAndUnload(KEY); helper.fail("unknown scheduled type must refuse save"); } catch(IOException expected) { }
        helper.assertTrue(resident.isLoaded(KEY) && resident.snapshot(KEY).orElseThrow().ticks().equals(List.of(invalid)),"failed save retains resident states and queue");
        helper.assertTrue(Arrays.equals(before,Files.readAllBytes(store.path(KEY))) && store.read(KEY).orElseThrow().ticks().equals(List.of(valid)),"failed encoding preserves earlier complete frame");
        helper.succeed();
    }

    private static SavedCubeTick tick(SavedCubeTick.Kind kind,String type,long delay,int priority,long order) { return new SavedCubeTick(kind,type,POS,delay,priority,order); }
    private static StableCubeStore store() { return new StableCubeStore(Path.of("diagnostics","stable-tick-stores",UUID.randomUUID().toString()),GENERATOR,1); }
    private static int[] states() { int[] states=new int[4096]; Arrays.fill(states,Block.getId(Blocks.AIR.defaultBlockState())); return states; }
    // Independent wire fixture: existing palette/cells, then int count and byte kind/name/full XYZ/long delay/int priority/long order.
    private static byte[] fixture(int version,List<SavedCubeTick> ticks) throws Exception {
        var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
        out.writeInt(0x4d495350);out.writeInt(version);out.writeInt(KEY.x());out.writeInt(KEY.y());out.writeInt(KEY.z());
        utf(out,GENERATOR);out.writeInt(1);out.writeInt(1);utf(out,"minecraft:air");out.writeByte(0);out.writeInt(4096);
        for(int i=0;i<4096;i++)out.writeShort(0);
        if(version==2){out.writeInt(ticks.size());for(var tick:ticks){out.writeByte(tick.kind().ordinal());utf(out,tick.type());out.writeInt(tick.position().x());out.writeInt(tick.position().y());out.writeInt(tick.position().z());out.writeLong(tick.delay());out.writeInt(tick.priority());out.writeLong(tick.order());}}
        out.writeInt(0);byte[] result=bytes.toByteArray();checksum(result);return result;
    }
    private static int tickOffset() throws Exception { return fixture(2,List.of()).length-4; }
    private static byte[] rawOutside() throws Exception { byte[] bytes=fixture(2,List.of(tick(SavedCubeTick.Kind.FLUID,"minecraft:water",1,0,0)));ByteBuffer.wrap(bytes).putInt(tickOffset()+1+2+"minecraft:water".length(),-16);checksum(bytes);return bytes; }
    private static void utf(DataOutputStream out,String text)throws IOException {byte[] bytes=text.getBytes(StandardCharsets.UTF_8);out.writeShort(bytes.length);out.write(bytes);}
    private static void checksum(byte[] bytes){var crc=new CRC32();crc.update(bytes,0,bytes.length-4);ByteBuffer.wrap(bytes).putInt(bytes.length-4,(int)crc.getValue());}
}
