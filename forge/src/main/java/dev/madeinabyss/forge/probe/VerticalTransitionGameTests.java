package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeBlockView;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Synchronous native method probes at the surface/deep seam, not full mob/server ticking acceptance. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VerticalTransitionGameTests {
    // Characterization candidate: native Level read paths must split by actual Y, without taller chunk arrays.
    @GameTest(template = "empty")
    public static void boundaryLevelReadsKeepNativeHeightAndColumnAllocation(GameTestHelper helper) {
        try (var fixture = new Boundary(helper)) {
            var p = fixture.anchor;
            fixture.nativeSet(new BlockPos(p.getX(), -64, p.getZ()), Blocks.DIRT.defaultBlockState());
            fixture.nativeSet(new BlockPos(p.getX(), -63, p.getZ()), Blocks.STONE.defaultBlockState());
            fixture.space.setBlock(new BlockCoordinate(p.getX(), -64, p.getZ()), Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()));
            fixture.space.setBlock(new BlockCoordinate(p.getX(), -65, p.getZ()), Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState()));
            helper.assertTrue(fixture.level.getBlockState(new BlockPos(p.getX(), -64, p.getZ())).is(Blocks.DIRT), "Y=-64 uses actual native column, not cube shadow");
            helper.assertTrue(fixture.level.getBlockState(new BlockPos(p.getX(), -65, p.getZ())).is(Blocks.DIAMOND_BLOCK), "Y=-65 uses attached cube state");
            helper.assertTrue(fixture.level.hasChunksAt(new BlockPos(p.getX(), -65, p.getZ()), new BlockPos(p.getX(), -60, p.getZ())), "mixed vertical bounds preserve deep and ordinary availability");
            helper.assertTrue(fixture.level.getMinBuildHeight() == -64 && fixture.level.getHeight() == 384, "global vanilla height accessor remains -64/384");
            helper.assertTrue(fixture.level.getChunk(p).getSections().length == 24, "native LevelChunk still allocates 24 sections, not a 1536-section column");
            helper.assertTrue(fixture.space.loadedCubeCount() == 27, "surface seam fixture is bounded to 27 independent cubes");
            var item = new ItemEntity(fixture.level, p.getX() + .5, -60, p.getZ() + .5, new ItemStack(Items.STONE));
            try {
                item.move(MoverType.SELF, new Vec3(0, -8, 0));
                helper.assertTrue(Math.abs(item.getY() + 62) < 1.0e-7 && item.onGround(), "ordinary native floor above seam keeps original collision semantics");
            } finally { item.discard(); }
        }
        helper.succeed();
    }

    // Characterization candidate: a real collision move through -64 must not encounter fabricated void or aliases.
    @GameTest(template = "empty")
    public static void nativeItemAndPigMoveContinuouslyAcrossSurfaceSeam(GameTestHelper helper) {
        try (var fixture = new Boundary(helper)) {
            var p = fixture.anchor;
            fixture.deepFloor(-66);
            ItemEntity item = new ItemEntity(fixture.level, p.getX() + .5, -60, p.getZ() + .5, new ItemStack(Items.STONE));
            Pig pig = EntityType.PIG.create(fixture.level);
            if (pig == null) throw new IllegalStateException("Pig factory returned null");
            pig.setNoAi(true); pig.setNoGravity(true); pig.setPos(p.getX() + .5, -60, p.getZ() + .5);
            item.setNoGravity(true);
            try {
                for (Entity body : new Entity[]{item, pig}) {
                    body.move(MoverType.SELF, new Vec3(0, -8, 0));
                    helper.assertTrue(Math.abs(body.getY() + 65) < 1.0e-7 && body.onGround(), "native body crosses -64 and clamps on global deep floor -66");
                    helper.assertTrue(Math.abs(body.getX() - (p.getX() + .5)) < 1.0e-7 && Math.abs(body.getZ() - (p.getZ() + .5)) < 1.0e-7, "continuous move retains actual X/Z");
                }
                pig.tick();
                helper.assertTrue(!pig.isRemoved() && Math.abs(pig.getY() + 65) < 1.0e-7, "known-residency no-AI/no-gravity native Pig tick remains supported after seam move");
                // Return upward by a real collision move; no reposition is used for this transition.
                pig.move(MoverType.SELF, new Vec3(0, 6, 0));
                helper.assertTrue(Math.abs(pig.getY() + 59) < 1.0e-7, "native body can move continuously back above -64");
                item.move(MoverType.SELF, new Vec3(0, 6, 0));
                DeepCubeDomain.attach(fixture.level, new SparseCubeSpace());
                for (Entity body : new Entity[]{item, pig}) {
                    Vec3 before = body.position();
                    body.setDeltaMovement(0, -12, 0);
                    helper.assertTrue(!DeepCubeDomain.allowsMove(fixture.level, body.getBoundingBox().expandTowards(body.getDeltaMovement())), "crossing into unknown deep halo is unavailable");
                    body.move(MoverType.SELF, new Vec3(0, -12, 0));
                    helper.assertTrue(body.position().equals(before) && body.getDeltaMovement().equals(Vec3.ZERO), "unknown crossing freezes actual movement and clears velocity");
                }
            } finally { item.discard(); pig.discard(); }
        }
        helper.succeed();
    }

    // Characterization candidate: actual Level.clip must traverse ordinary and deep Y in one global ray.
    @GameTest(template = "empty")
    public static void actualLevelRayCrossesSeamAndHitsGlobalDeepBlock(GameTestHelper helper) {
        try (var fixture = new Boundary(helper)) {
            var p = fixture.anchor;
            var target = new BlockPos(p.getX(), -65, p.getZ());
            fixture.space.setBlock(new BlockCoordinate(target.getX(), target.getY(), target.getZ()), Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState()));
            var hit = fixture.level.clip(new ClipContext(new Vec3(p.getX() + .5, -60, p.getZ() + .5),
                    new Vec3(p.getX() + .5, -68, p.getZ() + .5), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
            helper.assertTrue(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target), "actual Level ray retains global deep XYZ across seam");
            helper.assertTrue(hit.getDirection() == Direction.UP && Math.abs(hit.getLocation().y + 64) < 1.0e-7, "native ray hits top face at Y=-64");
        }
        helper.succeed();
    }

    // Break caught: rejecting a harmless halo below technical bottom, or clipping the actual physical body too.
    @GameTest(template = "empty")
    public static void technicalBottomClipsOnlyQueryHaloAndRejectsPhysicalPenetration(GameTestHelper helper) {
        var level = helper.getLevel();
        var anchor = helper.absolutePos(new BlockPos(2, 0, 2));
        int sx = Math.floorDiv(anchor.getX(), 16), sz = Math.floorDiv(anchor.getZ(), 16);
        var space = new SparseCubeSpace();
        for (int x = sx - 1; x <= sx + 1; x++) for (int y = -1501; y <= -1499; y++) for (int z = sz - 1; z <= sz + 1; z++) space.loadEmpty(new CubeKey(x, y, z));
        var body = new ItemEntity(level, anchor.getX() + .5, -24000, anchor.getZ() + .5, new ItemStack(Items.STONE));
        body.setNoGravity(true);
        DeepCubeDomain.attach(level, space);
        try {
            helper.assertTrue(body.getBoundingBox().minY == -24000, "actual native physical AABB begins exactly at technical bottom");
            helper.assertTrue(space.loadedCubeCount() == 27 && DeepCubeDomain.allowsMove(level, body.getBoundingBox()), "known 8-block query halo is clipped at bottom without rejecting a valid body");
            helper.assertTrue(!DeepCubeDomain.loadedRange(level, anchor.getX(), -24001, anchor.getZ(), anchor.getX(), -24000, anchor.getZ()), "direct loadedRange still rejects any request below technical bottom");
            Vec3 before = body.position();
            body.setDeltaMovement(0, -.01, 0);
            body.move(MoverType.SELF, new Vec3(0, -.01, 0));
            helper.assertTrue(body.position().equals(before) && body.getDeltaMovement().equals(Vec3.ZERO), "actual attempted movement below bottom freezes without physical penetration");
            body.setPos(anchor.getX() + .5, -24000.01, anchor.getZ() + .5);
            helper.assertTrue(!DeepCubeDomain.allowsMove(level, body.getBoundingBox()), "physical AABB below bottom is rejected rather than clipped into domain");
            helper.assertTrue(level.getMinBuildHeight() == -64 && level.getHeight() == 384, "bottom query leaves native height unchanged");
        } finally { DeepCubeDomain.detach(level); body.discard(); }
        helper.succeed();
    }

    // Break caught: early ordinary-height allow or a forced native chunk load conceals missing residency.
    @GameTest(template = "empty")
    public static void attachedDomainRejectsAbsentNativeColumnsWithoutAllocatingThem(GameTestHelper helper) {
        var level = helper.getLevel();
        var cache = level.getChunkSource();
        int farSection = 625000; // Exact block origin 10,000,000, unrelated to the GameTest region.
        helper.assertTrue(cache.getChunk(farSection, farSection, ChunkStatus.FULL, false) == null, "far native column must actually be absent before test");
        var local = helper.absolutePos(new BlockPos(2, 0, 2));
        int nativeSections = level.getChunk(local).getSections().length;
        int count = cache.getLoadedChunksCount();
        DeepCubeDomain.attach(level, new SparseCubeSpace());
        try {
            var ordinary = new AABB(10000000.25, 80, 10000000.25, 10000000.75, 81.8, 10000000.75);
            helper.assertTrue(!DeepCubeDomain.allowsMove(level, ordinary), "wholly ordinary body must reject unavailable native cache columns");
            var aboveSeam = new AABB(local.getX() + .25, -59, local.getZ() + .25, local.getX() + .75, -57.2, local.getZ() + .75);
            helper.assertTrue(!DeepCubeDomain.allowsMove(level, aboveSeam), "body above -64 still requires its 8-block halo's unknown deep portion");
            helper.assertTrue(cache.getChunk(farSection, farSection, ChunkStatus.FULL, false) == null && cache.getLoadedChunksCount() == count, "availability checks never allocate a missing native chunk");
            helper.assertTrue(level.getMinBuildHeight() == -64 && level.getHeight() == 384 && nativeSections == 24
                    && level.getChunk(local).getSections().length == 24, "native height and existing section allocation remain unchanged");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    // Break caught: optional native debug pick reaches outside a resident player window and throws before rendering.
    @GameTest(template = "empty")
    public static void nativeLongPickOutsideDeepWindowMissesButKnownShortPickStillHits(GameTestHelper helper) {
        var level = helper.getLevel();
        var anchor = helper.absolutePos(new BlockPos(2, 0, 2));
        int sx = Math.floorDiv(anchor.getX(), 16), sz = Math.floorDiv(anchor.getZ(), 16);
        var space = new SparseCubeSpace();
        for (int x = sx - 1; x <= sx + 1; x++) for (int y = -1252; y <= -1250; y++) for (int z = sz - 1; z <= sz + 1; z++) space.loadEmpty(new CubeKey(x, y, z));
        var body = new ItemEntity(level, anchor.getX() + .5, -20014, anchor.getZ() + .5, new ItemStack(Items.STONE));
        body.setNoGravity(true); body.setXRot(90); body.setYRot(0);
        DeepCubeDomain.attach(level, space);
        try {
            Vec3 end = body.getEyePosition(1).add(body.getViewVector(1).scale(20));
            helper.assertTrue(end.y < -20032 && space.loadedCubeCount() == 27, "actual 20-block native ray exits the bounded known window");
            var longHit = body.pick(20, 1, false);
            helper.assertTrue(longHit.getType() == HitResult.Type.MISS && longHit.getLocation().distanceToSqr(end) < 1.0e-12, "unavailable native diagnostic pick returns MISS at its actual endpoint without fabricating AIR");
            var floor = new BlockPos(anchor.getX(), -20018, anchor.getZ());
            space.setBlock(new BlockCoordinate(floor.getX(), floor.getY(), floor.getZ()), Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState()));
            var nearLongHit = body.pick(20, 1, false);
            helper.assertTrue(nearLongHit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
                    && block.getBlockPos().equals(floor), "long native pick retains a known nearby hit before its unavailable distant endpoint");
            var shortHit = body.pick(5, 1, false);
            helper.assertTrue(shortHit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
                    && block.getBlockPos().equals(floor) && block.getDirection() == Direction.UP, "known native short pick retains actual deep block and face");
            helper.assertTrue(level.getBlockState(floor).is(Blocks.DIAMOND_BLOCK), "known block read remains real native state after guarded diagnostic pick");
        } finally { DeepCubeDomain.detach(level); body.discard(); }
        helper.succeed();
    }

    // Break caught: native CatSpawner ground predicate reads unknown deep cubes and crashes the server tick.
    @GameTest(template = "empty")
    public static void nativeCatGroundSpawnRejectsUnknownButPreservesKnownPredicate(GameTestHelper helper) {
        var level = helper.getLevel();
        var anchor = helper.absolutePos(new BlockPos(2, 0, 2));
        var candidate = new BlockPos(anchor.getX(), -12429, anchor.getZ());
        DeepCubeDomain.attach(level, new SparseCubeSpace());
        try {
            helper.assertTrue(!SpawnPlacements.Type.ON_GROUND.canSpawnAt(level, candidate, EntityType.CAT), "native ground spawn predicate rejects unknown cube data without throwing");
            boolean directReadRejected = false;
            try { level.getBlockState(candidate); }
            catch (CubeBlockView.MissingCubeException unknown) {
                helper.assertTrue(unknown.key().equals(CubeKey.fromBlock(new BlockCoordinate(candidate.getX(), candidate.getY(), candidate.getZ()))), "direct unknown read reports actual requested cube");
                directReadRejected = true;
            }
            helper.assertTrue(directReadRejected, "optional spawn refusal never converts unknown Level reads to AIR");
            int sx = Math.floorDiv(candidate.getX(), 16), sy = -777, sz = Math.floorDiv(candidate.getZ(), 16);
            var known = new SparseCubeSpace();
            for (int x = sx - 1; x <= sx + 1; x++) for (int y = sy - 1; y <= sy + 1; y++) for (int z = sz - 1; z <= sz + 1; z++) known.loadEmpty(new CubeKey(x, y, z));
            var floor = candidate.below();
            known.setBlock(new BlockCoordinate(floor.getX(), floor.getY(), floor.getZ()), Block.getId(Blocks.STONE.defaultBlockState()));
            DeepCubeDomain.attach(level, known);
            helper.assertTrue(known.loadedCubeCount() == 27 && level.getBlockState(candidate).isAir()
                    && level.getBlockState(candidate.above()).isAir() && level.getBlockState(floor).is(Blocks.STONE), "known native CAT candidate has stone support and empty body/head cells");
            helper.assertTrue(SpawnPlacements.Type.ON_GROUND.canSpawnAt(level, candidate, EntityType.CAT), "known native ground predicate retains its original true result");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    private static final class Boundary implements AutoCloseable {
        final ServerLevel level;
        final BlockPos anchor;
        final SparseCubeSpace space = new SparseCubeSpace();
        final Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
        Boundary(GameTestHelper helper) {
            level = helper.getLevel();
            BlockPos test = helper.absolutePos(new BlockPos(2, 0, 2));
            anchor = new BlockPos(test.getX(), -64, test.getZ());
            int sx = Math.floorDiv(anchor.getX(), 16), sz = Math.floorDiv(anchor.getZ(), 16);
            for (int x = sx - 1; x <= sx + 1; x++) for (int y = -5; y <= -3; y++) for (int z = sz - 1; z <= sz + 1; z++) space.loadEmpty(new CubeKey(x, y, z));
            // Remove pre-existing native bedrock/terrain only in a narrow test-owned seam shaft, then restore it.
            for (int x = anchor.getX() - 1; x <= anchor.getX() + 1; x++) for (int z = anchor.getZ() - 1; z <= anchor.getZ() + 1; z++) {
                for (int y = -64; y <= -54; y++) nativeSet(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
            DeepCubeDomain.attach(level, space);
        }
        void nativeSet(BlockPos pos, BlockState state) {
            originals.putIfAbsent(pos, level.getBlockState(pos));
            level.setBlock(pos, state, 2);
        }
        void deepFloor(int y) {
            for (int x = anchor.getX() - 1; x <= anchor.getX() + 1; x++) for (int z = anchor.getZ() - 1; z <= anchor.getZ() + 1; z++)
                space.setBlock(new BlockCoordinate(x, y, z), Block.getId(Blocks.STONE.defaultBlockState()));
        }
        @Override public void close() {
            DeepCubeDomain.detach(level);
            originals.forEach((position, state) -> level.setBlock(position, state, 2));
        }
    }
}
