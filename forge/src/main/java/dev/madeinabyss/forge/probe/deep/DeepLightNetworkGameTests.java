package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.CubeLightInput;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Actual optional scalar-channel wire codec. No running connection/client cache is implied. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepLightNetworkGameTests {
    private static final UUID TOKEN = UUID.fromString("0b48de39-d5fd-4605-a003-ef4ee6feab82");

    @GameTest(template = "empty")
    public static void signedKeysAliasAndAllScalarBytesRoundTrip(GameTestHelper helper) {
        var first = input(new CubeKey(-17, -1250, -1));
        var alias = input(new CubeKey(-17, -994, -1));
        var extreme = input(new CubeKey(-134217728, 134217727, -134217728));
        var original = new DeepLightNetwork.Snapshot(TOKEN, 37, 91, true, "", List.of(first, alias, extreme));
        var buffer = buffer();
        try {
            DeepLightNetwork.encode(original, buffer);
            var decoded = DeepLightNetwork.decode(buffer);
            helper.assertTrue(decoded.session().equals(TOKEN), "session UUID must roundtrip");
            helper.assertTrue(decoded.geometryRevision() == 37 && decoded.revision() == 91, "independent revisions must roundtrip");
            helper.assertTrue(decoded.available() && decoded.error().isEmpty(), "availability must roundtrip");
            helper.assertTrue(decoded.inputs().size() == 3 && !buffer.isReadable(), "whole payload must be consumed");
            for (int i = 0; i < original.inputs().size(); i++) {
                var expected = original.inputs().get(i); var actual = decoded.inputs().get(i);
                helper.assertTrue(actual.key().equals(expected.key()), "signed independent XYZ at input " + i);
                helper.assertTrue(Arrays.equals(actual.emission(), expected.emission()), "all emission bytes at input " + i);
                helper.assertTrue(Arrays.equals(actual.opacity(), expected.opacity()), "all opacity bytes at input " + i);
            }
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void maximum108InputsFitActualNativePayloadCap(GameTestHelper helper) {
        var inputs = new ArrayList<CubeLightInput>();
        for (int x = 0; x < 108; x++) inputs.add(input(new CubeKey(x, -1250, -1)));
        var buffer = buffer();
        try {
            DeepLightNetwork.encode(new DeepLightNetwork.Snapshot(TOKEN, 1, 2, true, "", inputs), buffer);
            helper.assertTrue(buffer.readableBytes() <= 1048576, "actual maximal valid payload must fit vanilla1MiB");
            var decoded = DeepLightNetwork.decode(buffer);
            helper.assertTrue(decoded.inputs().size() == 108, "max budget must decode completely");
            helper.assertTrue(decoded.inputs().get(107).key().x() == 107, "last input must not be silently truncated");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unavailableSnapshotCarriesNoActiveInputs(GameTestHelper helper) {
        var buffer = buffer();
        try {
            DeepLightNetwork.encode(new DeepLightNetwork.Snapshot(TOKEN, 8, 10, false, "staging failed", List.of()), buffer);
            var decoded = DeepLightNetwork.decode(buffer);
            helper.assertTrue(!decoded.available() && decoded.inputs().isEmpty(), "failed snapshot cannot activate old scalar inputs");
            helper.assertTrue(decoded.error().equals("staging failed") && decoded.revision() == 10, "failure revision and error must survive");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidCountIsRejectedBeforeAnyInputAllocation(GameTestHelper helper) {
        for (int count : new int[]{-1, 109, Integer.MAX_VALUE}) {
            var buffer = header(true, 1, 2, count);
            reject(helper, buffer, IllegalArgumentException.class, "count");
        }
        reject(helper, header(false, 1, 2, 1), IllegalArgumentException.class, "count");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void truncationAtHeaderKeyAndScalarBoundariesIsRejected(GameTestHelper helper) {
        var complete = buffer();
        byte[] bytes;
        try {
            DeepLightNetwork.encode(new DeepLightNetwork.Snapshot(TOKEN, 1, 2, true, "",
                    List.of(input(new CubeKey(-1, -1250, -1)))), complete);
            bytes = new byte[complete.readableBytes()]; complete.readBytes(bytes);
        } finally { complete.release(); }
        for (int length : new int[]{0, 15, 32, 37, 45, bytes.length - 1}) {
            var truncated = new FriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(bytes, length)));
            reject(helper, truncated, IndexOutOfBoundsException.class, null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void duplicateKeysAndTrailingBytesAreRejected(GameTestHelper helper) {
        var duplicate = header(true, 1, 2, 2);
        var input = input(new CubeKey(-1, -1250, -1));
        frame(duplicate, input.key(), input.emission(), input.opacity());
        frame(duplicate, input.key(), input.emission(), input.opacity());
        reject(helper, duplicate, IllegalArgumentException.class, "Duplicate");
        var trailing = header(true, 1, 2, 0); trailing.writeByte(1);
        reject(helper, trailing, IllegalArgumentException.class, "Trailing");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidNibbleCoordinateAndRevisionRangesAreRejected(GameTestHelper helper) {
        for (boolean badEmission : new boolean[]{true, false}) {
            var emission = new byte[4096]; var opacity = new byte[4096];
            if (badEmission) emission[4095] = 16; else opacity[4095] = -1;
            var buffer = header(true, 1, 2, 1);
            frame(buffer, new CubeKey(0, -1250, 0), emission, opacity);
            reject(helper, buffer, IllegalArgumentException.class, "0..15");
        }
        var overflow = header(true, 1, 2, 1);
        frame(overflow, new CubeKey(134217728, -1250, 0), new byte[4096], new byte[4096]);
        reject(helper, overflow, IllegalArgumentException.class, "signed int");
        reject(helper, header(true, -1, 2, 0), IllegalArgumentException.class, "snapshot");
        reject(helper, header(true, 1, -1, 0), IllegalArgumentException.class, "snapshot");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oversizedWireIsRejectedByCapBeforeParsing(GameTestHelper helper) {
        var buffer = buffer();
        buffer.writeZero(1048577);
        // Without the cap this buffer would instead fail as a trailing payload; require the actual cap branch.
        reject(helper, buffer, IllegalArgumentException.class, "Oversized");
        helper.succeed();
    }

    private static FriendlyByteBuf buffer() { return new FriendlyByteBuf(Unpooled.buffer()); }
    private static FriendlyByteBuf header(boolean available, long geometry, long revision, int count) {
        var buffer = buffer();
        buffer.writeUUID(TOKEN); buffer.writeLong(geometry); buffer.writeLong(revision);
        buffer.writeBoolean(available); buffer.writeUtf("", 512); buffer.writeVarInt(count);
        return buffer;
    }
    private static void frame(FriendlyByteBuf buffer, CubeKey key, byte[] emission, byte[] opacity) {
        buffer.writeInt(key.x()); buffer.writeInt(key.y()); buffer.writeInt(key.z());
        buffer.writeBytes(emission); buffer.writeBytes(opacity);
    }
    private static CubeLightInput input(CubeKey key) {
        var emission = new byte[4096]; var opacity = new byte[4096];
        for (int i = 0; i < 4096; i++) { emission[i] = (byte) (i & 15); opacity[i] = (byte) ((i >>> 4) & 15); }
        return new CubeLightInput(key, emission, opacity);
    }
    private static void reject(GameTestHelper helper, FriendlyByteBuf buffer,
                               Class<? extends RuntimeException> type, String messagePart) {
        RuntimeException observed = null;
        try { DeepLightNetwork.decode(buffer); }
        catch (RuntimeException failure) { observed = failure; }
        finally { buffer.release(); }
        helper.assertTrue(observed != null && type.isInstance(observed), "expected " + type.getSimpleName() + ", actual=" + observed);
        helper.assertTrue(messagePart == null || (observed.getMessage() != null && observed.getMessage().contains(messagePart)),
                "expected rejection reason " + messagePart + ", actual=" + observed);
    }
}
