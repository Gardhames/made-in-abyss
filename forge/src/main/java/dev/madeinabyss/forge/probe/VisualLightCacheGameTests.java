package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.forge.probe.client.ClientDeepVisual;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepVisualNetwork;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Actual native sections/client cache state machine on ServerLevel; not transport/native framebuffer proof. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VisualLightCacheGameTests {
    private static final CubeKey CENTER = new CubeKey(0, -1250, 0);
    private static final CubeKey SOURCE = new CubeKey(0, -1260, 0);
    private static final BlockPos NEIGHBOR = new BlockPos(9, -20152, 8);

    // Break caught: limiting the render solver to physics27 loses far visible emission and source edits.
    @GameTest(template = "empty")
    public static void visualOnlySourceEditsReachBeyondPhysicalWindow(GameTestHelper helper) {
        var level = helper.getLevel(); var physical = physical(); var session = UUID.randomUUID();
        try {
            ClientDeepVisual.begin(session, level);
            ClientDeepVisual.receive(level, full(level, session, SOURCE, Blocks.GLOWSTONE.defaultBlockState()));
            helper.assertTrue(!physical.isLoaded(SOURCE), "source is outside authoritative physical27");
            var light = ClientDeepVisual.light(level, physical);
            helper.assertTrue(light.value(level, NEIGHBOR) == 14, "visual-only glowstone emits neighbor14");
            helper.assertTrue(light.computedCubes() == 243 && physical.loadedCubeCount() == 27,
                    "visual solve must not change physical residency");
            var before = light.recomputations();
            light.value(level, NEIGHBOR);
            helper.assertTrue(light.recomputations() == before, "unchanged render queries reuse derived cache");
            ClientDeepVisual.receive(level, delta(level, session, 2, SOURCE, Blocks.STONE.defaultBlockState()));
            helper.assertTrue(ClientDeepVisual.light(level, physical).value(level, NEIGHBOR) == 0, "source removal is confirmed dark");
            ClientDeepVisual.receive(level, delta(level, session, 3, SOURCE, Blocks.GLOWSTONE.defaultBlockState()));
            helper.assertTrue(ClientDeepVisual.light(level, physical).value(level, NEIGHBOR) == 14, "restored source restores14");
            helper.assertTrue(DeepCubeDomain.lookup(level).isEmpty(), "visual lighting never attaches a Level domain");
        } finally { ClientDeepVisual.clear(); }
        helper.succeed();
    }

    // Break caught: old visual snapshots override current physical geometry, or arbitrary physical unions exceed the cap.
    @GameTest(template = "empty")
    public static void physicalOverridesStayInsideTheFixedVisualWindow(GameTestHelper helper) {
        var level = helper.getLevel(); var physical = physical(); var session = UUID.randomUUID();
        var sample = new BlockPos(9, -19992, 8);
        try {
            ClientDeepVisual.begin(session, level);
            ClientDeepVisual.receive(level, full(level, session, CENTER, Blocks.GLOWSTONE.defaultBlockState()));
            helper.assertTrue(ClientDeepVisual.light(level, physical).value(level, sample) == 0,
                    "current physical AIR wins over older visual glowstone");
            physical.setBlock(new BlockCoordinate(8, -19992, 8), Block.getId(Blocks.GLOWSTONE.defaultBlockState()));
            helper.assertTrue(ClientDeepVisual.light(level, physical).value(level, sample) == 14,
                    "same-identity physical content change invalidates cached light");
            ClientDeepVisual.receive(level, delta(level, session, 2, CENTER, Blocks.STONE.defaultBlockState()));
            helper.assertTrue(ClientDeepVisual.light(level, physical).value(level, sample) == 14,
                    "visual packet delivered after physical change cannot regress the authoritative cell");
            var next = physical();
            var outside = new CubeKey(20, -1250, 20); next.loadEmpty(outside);
            next.setBlock(new BlockCoordinate(328, -19992, 328), Block.getId(Blocks.GLOWSTONE.defaultBlockState()));
            var light = ClientDeepVisual.light(level, next);
            helper.assertTrue(light.value(level, sample) == 0, "new physical identity invalidates the old merged source");
            helper.assertTrue(light.computedCubes() == 243, "out-of-window physical cube never expands the visual solve");
            boolean unavailable = false;
            try { light.value(level, new BlockPos(328, -19992, 328)); }
            catch (IllegalStateException expected) { unavailable = true; }
            helper.assertTrue(unavailable, "unrelated physical source remains outside visual query contract");
        } finally { ClientDeepVisual.clear(); }
        helper.succeed();
    }

    // Break caught: a malformed newer observation keeps previously lit output falsely current, or prevents valid retry recovery.
    @GameTest(template = "empty")
    public static void invalidVisualGeometryRevokesLightUntilValidContiguousRetry(GameTestHelper helper) {
        var level = helper.getLevel(); var physical = physical(); var session = UUID.randomUUID();
        try {
            ClientDeepVisual.begin(session, level);
            ClientDeepVisual.receive(level, full(level, session, SOURCE, Blocks.GLOWSTONE.defaultBlockState()));
            helper.assertTrue(ClientDeepVisual.light(level, physical).value(level, NEIGHBOR) == 14, "initial published light14");
            ClientDeepVisual.receive(level, new DeepVisualNetwork.Window(session, 2, false, CENTER, List.of(), List.of(new byte[]{0})));
            helper.assertTrue(ClientDeepVisual.space(level).orElseThrow().loadedCubeCount() == 243
                    && ClientDeepVisual.revision() == 1, "failed decode preserves last valid geometry");
            unavailable(helper, level, physical, "failed newer decode invalidates light immediately");
            ClientDeepVisual.receive(level, full(level, session, SOURCE, Blocks.GLOWSTONE.defaultBlockState()));
            unavailable(helper, level, physical, "replay of committed geometry does not reactivate invalidated light");
            ClientDeepVisual.receive(level, delta(level, session, 2, SOURCE, Blocks.GLOWSTONE.defaultBlockState()));
            helper.assertTrue(ClientDeepVisual.light(level, physical).value(level, NEIGHBOR) == 14,
                    "valid contiguous revision retry recovers without fake geometry edit");
        } finally { ClientDeepVisual.clear(); }
        helper.succeed();
    }

    // Break caught: stale sessions/foreign Levels/clear expose retained visual results to the next world.
    @GameTest(template = "empty")
    public static void releaseAndSessionReplacementDiscardDerivedLight(GameTestHelper helper) {
        var level = helper.getLevel(); var physical = physical(); var session = UUID.randomUUID();
        try {
            ClientDeepVisual.begin(session, level);
            ClientDeepVisual.receive(level, full(level, session, SOURCE, Blocks.GLOWSTONE.defaultBlockState()));
            helper.assertTrue(ClientDeepVisual.light(level, physical).value(level, NEIGHBOR) == 14, "first session source14");
            var replacement = UUID.randomUUID(); ClientDeepVisual.begin(replacement, level);
            ClientDeepVisual.receive(level, delta(level, session, 99, SOURCE, Blocks.GLOWSTONE.defaultBlockState()));
            unavailable(helper, level, physical, "old session cannot light replacement scene");
            ClientDeepVisual.receive(level, full(level, replacement, SOURCE, Blocks.STONE.defaultBlockState()));
            helper.assertTrue(ClientDeepVisual.light(level, physical).value(level, NEIGHBOR) == 0, "replacement scene has no retained emitter");
            ClientDeepVisual.clear(); unavailable(helper, level, physical, "clear releases current light cache");
        } finally { ClientDeepVisual.clear(); }
        helper.succeed();
    }

    private static void unavailable(GameTestHelper helper, Level level, SparseCubeSpace physical, String reason) {
        boolean rejected = false;
        try { ClientDeepVisual.light(level, physical).value(level, NEIGHBOR); }
        catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, reason);
    }

    private static SparseCubeSpace physical() {
        var space = new SparseCubeSpace();
        for (int x = -1; x <= 1; x++) for (int y = -1251; y <= -1249; y++) for (int z = -1; z <= 1; z++)
            space.loadEmpty(new CubeKey(x, y, z));
        return space;
    }

    private static DeepVisualNetwork.Window full(Level level, UUID session, CubeKey source, BlockState state) {
        var frames = new ArrayList<byte[]>();
        for (int x = -1; x <= 1; x++) for (int y = -1271; y <= -1245; y++) for (int z = -1; z <= 1; z++) {
            var key = new CubeKey(x, y, z); frames.add(frame(level, key, key.equals(source) ? state : null));
        }
        return new DeepVisualNetwork.Window(session, 1, true, CENTER, List.of(), List.copyOf(frames));
    }

    private static DeepVisualNetwork.Window delta(Level level, UUID session, long revision, CubeKey key, BlockState state) {
        return new DeepVisualNetwork.Window(session, revision, false, CENTER, List.of(), List.of(frame(level, key, state)));
    }

    private static byte[] frame(Level level, CubeKey key, BlockState source) {
        var section = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
        if (source != null) section.setBlockState(8, 8, 8, source);
        var out = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CubeSectionPacketCodec.encode(out, key, section);
            var bytes = new byte[out.readableBytes()]; out.readBytes(bytes); return bytes;
        } finally { out.release(); }
    }
}
