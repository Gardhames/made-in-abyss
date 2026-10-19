package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.PersistentCubeSpace;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.Direction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StableCubeStoreGameTests {
    private static final CubeKey KEY = new CubeKey(-17, -1250, -994);
    private static final String GENERATOR = "madeinabyss:stable_test";

    // Break caught: persisting numeric registry IDs or dropping properties changes actual blocks after reload.
    @GameTest(template = "empty")
    public static void namedPropertiesAndSignedCoordinatesReload(GameTestHelper helper) throws Exception {
        Path root = directory();
        var store = new StableCubeStore(root, GENERATOR, 7);
        int[] states = states();
        states[15] = Block.getId(Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        states[4095] = Block.getId(Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.WATERLOGGED, true));
        for (var key : new CubeKey[]{KEY, new CubeKey(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE)}) {
            store.write(new CubeSnapshot(key, states));
            var restored = new StableCubeStore(root, GENERATOR, 7).read(key).orElseThrow();
            helper.assertTrue(restored.key().equals(key) && Arrays.equals(states, restored.states()), "native properties and all signed XYZ survive a fresh store");
        }
        String encoded = new String(Files.readAllBytes(store.path(KEY)), StandardCharsets.ISO_8859_1);
        helper.assertTrue(encoded.contains("minecraft:oak_log") && encoded.contains("axis") && encoded.contains("waterlogged"), "disk contains named states and properties");
        helper.assertTrue(!Files.exists(root.resolve("c.-17.-1250.-994.mia3")), "stable storage stays separate from diagnostic format");
        helper.assertTrue(store.read(new CubeKey(4, -1250, 4)).isEmpty(), "only a missing file means absent data");
        helper.succeed();
    }

    // Break caught: native registry fallback silently turns unavailable block/property/provenance into valid data.
    @GameTest(template = "empty")
    public static void unknownRegistryPropertiesAndProvenanceReject(GameTestHelper helper) throws Exception {
        var store = new StableCubeStore(directory(), GENERATOR, 7);
        store.write(new CubeSnapshot(KEY, states()));
        byte[] original = Files.readAllBytes(store.path(KEY));
        reject(helper, new StableCubeStore(store.path(KEY).getParent().getParent(), GENERATOR, 8), "generator version");
        reject(helper, new StableCubeStore(store.path(KEY).getParent().getParent(), "madeinabyss:other", 7), "generator identity");
        try {
            new StableCubeStore(store.path(KEY).getParent().getParent(), GENERATOR, 8).write(new CubeSnapshot(KEY, states()));
            helper.fail("different generator provenance must not overwrite visited cube");
        } catch (IOException expected) {
            helper.assertTrue(Arrays.equals(original, Files.readAllBytes(store.path(KEY))), "provenance mismatch preserves old file");
        }
        for (byte[] invalid : new byte[][]{
                fixture(KEY, "minecraft:missing_block", new String[0][0]),
                fixture(KEY, "minecraft:oak_log", new String[][]{{"unknown", "x"}}),
                fixture(KEY, "minecraft:oak_log", new String[][]{{"axis", "invalid"}}),
                fixture(KEY, "minecraft:oak_log", new String[0][0]),
                fixture(KEY, "minecraft:oak_log", new String[][]{{"axis", "x"}, {"axis", "y"}}),
                fixture(new CubeKey(-17, -994, -994), "minecraft:air", new String[0][0])}) {
            Files.write(store.path(KEY), invalid);
            reject(helper, store, "unknown state/property/coordinate");
            helper.assertTrue(Arrays.equals(invalid, Files.readAllBytes(store.path(KEY))), "rejecting a file never rewrites it");
        }
        helper.succeed();
    }

    // Break caught: corruption or unbounded count/length creates partial data or excessive allocations.
    @GameTest(template = "empty")
    public static void boundedCorruptFilesNeverPublishSnapshots(GameTestHelper helper) throws Exception {
        var store = new StableCubeStore(directory(), GENERATOR, 7);
        store.write(new CubeSnapshot(KEY, states()));
        byte[] valid = fixture(KEY, "minecraft:air", new String[0][0]);
        byte[] version = valid.clone(); ByteBuffer.wrap(version).putInt(4, 2); checksum(version);
        byte[] palette = valid.clone(); ByteBuffer.wrap(palette).putInt(26 + GENERATOR.length(), Integer.MAX_VALUE); checksum(palette);
        byte[] index = valid.clone(); index[index.length - 5] = 2; checksum(index);
        byte[] crc = valid.clone(); crc[crc.length - 1] ^= 1;
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        byte[] oversized = new byte[StableCubeStore.MAX_FILE_BYTES + 1];
        for (byte[] invalid : new byte[][]{version, palette, index, crc, trailing, oversized, Arrays.copyOf(valid, valid.length - 1), new byte[0]}) {
            Files.write(store.path(KEY), invalid);
            reject(helper, store, "malformed bounded frame");
        }
        helper.succeed();
    }

    // Break caught: failed encoding destroys an earlier save or unloads resident data before atomic replacement.
    @GameTest(template = "empty")
    public static void failedSavePreservesFileAndResidentCube(GameTestHelper helper) throws Exception {
        var store = new StableCubeStore(directory(), GENERATOR, 7);
        store.write(new CubeSnapshot(KEY, states()));
        byte[] original = Files.readAllBytes(store.path(KEY));
        int[] invalid = states(); invalid[13] = Integer.MAX_VALUE;
        var resident = new SparseCubeSpace(); resident.load(new CubeSnapshot(KEY, invalid));
        try {
            new PersistentCubeSpace(resident, store).saveAndUnload(KEY);
            helper.fail("invalid native state must fail saving");
        } catch (IOException expected) {
            helper.assertTrue(resident.isLoaded(KEY), "failed save keeps resident cube");
            helper.assertTrue(Arrays.equals(original, Files.readAllBytes(store.path(KEY))), "failed save keeps old file byte-for-byte");
        }
        var healthy = new SparseCubeSpace(); healthy.load(new CubeSnapshot(KEY, states()));
        helper.assertTrue(new PersistentCubeSpace(healthy, store).saveAndUnload(KEY) && !healthy.isLoaded(KEY), "successful save precedes unload");
        helper.assertTrue(store.read(KEY).isPresent(), "saved cube remains reloadable");
        helper.succeed();
    }

    private static Path directory() { return Path.of("diagnostics", "stable-cube-stores", UUID.randomUUID().toString()); }
    private static int[] states() { int[] states = new int[4096]; Arrays.fill(states, Block.getId(Blocks.AIR.defaultBlockState())); return states; }
    private static void reject(GameTestHelper helper, StableCubeStore store, String reason) throws Exception {
        try { store.read(KEY); helper.fail("must reject " + reason); } catch (IOException expected) { /* strict error, never absent/air */ }
    }
    // Hand-written v1 wire fixture independent of the store encoder: MISP, version, XYZ, provenance, palette, cells, CRC.
    private static byte[] fixture(CubeKey key, String block, String[][] properties) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x4d495350); out.writeInt(1); out.writeInt(key.x()); out.writeInt(key.y()); out.writeInt(key.z());
        utf(out, GENERATOR); out.writeInt(7); out.writeInt(1); utf(out, block); out.writeByte(properties.length);
        for (String[] property : properties) { utf(out, property[0]); utf(out, property[1]); }
        out.writeInt(4096); for (int i = 0; i < 4096; i++) out.writeShort(0);
        out.writeInt(0); byte[] result = bytes.toByteArray(); checksum(result); return result;
    }
    private static void utf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeShort(bytes.length); out.write(bytes);
    }
    private static void checksum(byte[] bytes) {
        var crc = new CRC32(); crc.update(bytes, 0, bytes.length - 4); ByteBuffer.wrap(bytes).putInt(bytes.length - 4, (int) crc.getValue());
    }
}
