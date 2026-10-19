package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeBlockView;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.RemotePlayerAvailability;
import dev.madeinabyss.forge.probe.deep.GeneratedLightAccess;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Native Level-backed policy tests. RemotePlayer itself is client-only and is exercised by the four-client runtime. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RemotePlayerAvailabilityGameTests {
    private RemotePlayerAvailabilityGameTests() {}
    private static final double X = -23.5, Z = -23.5;
    private static AABB body(double y) { return new AABB(X - .3, y, Z - .3, X + .3, y + 1.8, Z + .3); }
    private static SparseCubeSpace known() {
        var space = new SparseCubeSpace();
        for (int x = -3; x <= -1; x++) for (int y = -251; y <= -249; y++) for (int z = -3; z <= -1; z++)
            space.loadEmpty(new CubeKey(x, y, z));
        return space;
    }
    @GameTest(template = "empty")
    public static void coldGeneratedRainDefersEnvironmentUntilIndependentExposurePublication(GameTestHelper helper) {
        var level=helper.getLevel();DeepCubeDomain.attach(level,known());
        var ready=new java.util.concurrent.atomic.AtomicBoolean();var queries=new java.util.concurrent.atomic.AtomicInteger();
        GeneratedLightAccess.install((current,pos)->current==level,(current,pos)->java.util.OptionalInt.empty());
        GeneratedLightAccess.geometry((current,pos)->java.util.OptionalInt.empty(),(current,pos)->{
            queries.incrementAndGet();return ready.get()?java.util.OptionalInt.of(-4000):java.util.OptionalInt.empty();
        });
        float previous=level.getRainLevel(1);
        try {
            level.setRainLevel(1);
            helper.assertTrue(!GeneratedLightAccess.rainReady(level,new BlockPos(-24,-3992,-24)),"cold generated rain metadata stays unavailable");
            helper.assertTrue(!RemotePlayerAvailability.canTickEnvironment(level,body(-3992),Vec3.ZERO,Vec3.ZERO),"resident blocks alone cannot admit the rain-dependent entity tick");
            // Independent background publication proceeds while no entity environment tick ran.
            ready.set(true);
            helper.assertTrue(RemotePlayerAvailability.canTickEnvironment(level,body(-3992),Vec3.ZERO,Vec3.ZERO),"published exposure resumes the original environmental tick");
            ready.set(false);level.setRainLevel(0);int before=queries.get();
            helper.assertTrue(RemotePlayerAvailability.canTickEnvironment(level,body(-3992),Vec3.ZERO,Vec3.ZERO),"clear weather does not require unknown rain metadata");
            helper.assertTrue(queries.get()==before,"clear weather short circuits before metadata lookup");
        } finally {
            level.setRainLevel(previous);DeepCubeDomain.detach(level);
            GeneratedLightAccess.install((current,pos)->false,(current,pos)->java.util.OptionalInt.empty());
            GeneratedLightAccess.geometry((current,pos)->java.util.OptionalInt.empty(),(current,pos)->java.util.OptionalInt.empty());
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void nativePushabilityPredicateReadsFeetBeforeCandidateFiltering(GameTestHelper helper) {
        var level = helper.getLevel(); DeepCubeDomain.attach(level, known());
        try {
            var candidate = EntityType.PIG.create(level);
            helper.assertTrue(candidate != null, "native living candidate is available");
            candidate.setPos(X, -3992, Z);
            helper.assertTrue(RemotePlayerAvailability.canTickEnvironment(level, candidate.getBoundingBox(), Vec3.ZERO, Vec3.ZERO),
                    "known post-interpolation neighborhood permits original optional push query");
            helper.assertTrue(EntitySelector.pushableBy(candidate).test(candidate),
                    "real native living pushability predicate retains its known AIR/feet result");
            candidate.setPos(X, -12000, Z); // Clears native feetBlockState cache, as remote packet interpolation does.
            boolean missing = false;
            try { EntitySelector.pushableBy(candidate).test(candidate); }
            catch (CubeBlockView.MissingCubeException expected) { missing = true; }
            helper.assertTrue(missing, "native selector's isPushable/onClimbable transitively reads unavailable feet data");
            helper.assertTrue(!RemotePlayerAvailability.canTickEnvironment(level, candidate.getBoundingBox(), Vec3.ZERO, Vec3.ZERO),
                    "optional push callsite refuses this post-lerp unknown neighborhood without changing the required read contract");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void remoteUnknownEnvironmentalTickRefusedWithoutAirFallback(GameTestHelper helper) {
        var level = helper.getLevel(); DeepCubeDomain.attach(level, known());
        try {
            helper.assertTrue(!RemotePlayerAvailability.canTickEnvironment(level, body(-12000), Vec3.ZERO, Vec3.ZERO),
                    "tracked remote body at a different deep Y must not run environmental reads outside this client's mirror");
            boolean missing = false;
            try { level.getFluidState(new BlockPos(-24, -12000, -24)); }
            catch (CubeBlockView.MissingCubeException expected) { missing = true; }
            helper.assertTrue(missing, "required native fluid read remains strict; remote policy must not publish unknown AIR");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void nearbyKnownEnvironmentalTickKeepsRealNativeState(GameTestHelper helper) {
        var level = helper.getLevel(); var space = known();
        space.setBlock(new BlockCoordinate(-24, -3993, -24), Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState()));
        DeepCubeDomain.attach(level, space);
        try {
            helper.assertTrue(RemotePlayerAvailability.canTickEnvironment(level, body(-3992), new Vec3(.2, .5, 0), new Vec3(0, .1, 0)),
                    "nearby known remote keeps original environmental tick and packet interpolation");
            helper.assertTrue(level.getBlockState(new BlockPos(-24, -3993, -24)).is(Blocks.DIAMOND_BLOCK),
                    "policy does not alter real native cube state");
            helper.assertTrue(space.loadedCubeCount() == 27 && level.getMinBuildHeight() == -64 && level.getHeight() == 384,
                    "remote decision does not enlarge mirrors or native height");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void interpolationAndVelocityCannotReachUnknownEnvironment(GameTestHelper helper) {
        var level = helper.getLevel(); DeepCubeDomain.attach(level, known());
        try {
            helper.assertTrue(RemotePlayerAvailability.canTickEnvironment(level, body(-3992), Vec3.ZERO, Vec3.ZERO), "starting body is known");
            helper.assertTrue(!RemotePlayerAvailability.canTickEnvironment(level, body(-3992), new Vec3(0, -40, 0), Vec3.ZERO),
                    "the next actual native interpolation step can leave the known mirror");
            helper.assertTrue(!RemotePlayerAvailability.canTickEnvironment(level, body(-3992), Vec3.ZERO, new Vec3(0, 40, 0)),
                    "velocity sweep requires resident environmental data as well");
            helper.assertTrue(!RemotePlayerAvailability.canTickEnvironment(level, body(-59), Vec3.ZERO, Vec3.ZERO),
                    "ordinary body with an unknown deep eight-block halo at the -64 seam cannot run environmental reads");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void detachedDeepRemoteRetainsInterpolationButOrdinaryKeepsVanilla(GameTestHelper helper) {
        var level = helper.getLevel(); DeepCubeDomain.detach(level);
        try {
            helper.assertTrue(RemotePlayerAvailability.canTickEnvironment(level, body(80), Vec3.ZERO, Vec3.ZERO),
                    "detached ordinary remote retains original vanilla environmental tick");
            helper.assertTrue(!RemotePlayerAvailability.canTickEnvironment(level, body(-12000), Vec3.ZERO, Vec3.ZERO),
                    "after local owner return, a still-tracked deep remote has no environmental mirror");
            helper.assertTrue(!RemotePlayerAvailability.canTickEnvironment(level, body(-64.01), Vec3.ZERO, Vec3.ZERO),
                    "negative AABB crossing below the exact native min is a deep read candidate");
            helper.assertTrue(RemotePlayerAvailability.canTickEnvironment(level, body(-64), Vec3.ZERO, Vec3.ZERO),
                    "exact native min body stays ordinary when detached");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }
}
