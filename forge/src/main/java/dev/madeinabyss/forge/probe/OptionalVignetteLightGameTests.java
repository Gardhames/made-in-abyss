package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeBlockLightAccess;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.OptionalLightWork;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OptionalVignetteLightGameTests {
    private static final BlockPos FRINGE = new BlockPos(192, -21843, 192);
    private static final BlockPos INTERIOR = new BlockPos(192, -21832, 192);

    // Break caught: native GUI preflight throws at a resident geometry point whose 14m light boundary is uncertain.
    @GameTest(template = "empty")
    public static void residentFringeRawLightSkipsOptionalVignetteWithoutChangingQueries(GameTestHelper helper) {
        var level = helper.getLevel(); var space = physical();
        DeepCubeDomain.attach(level, space, true);
        try {
            helper.assertTrue(space.isLoaded(new CubeKey(12, -1366, 12)), "crash point is geometrically resident");
            long before = OptionalLightWork.skipped(OptionalLightWork.Kind.VIGNETTE);
            boolean[] used = {false};
            boolean completed = OptionalLightWork.run(level, OptionalLightWork.Kind.VIGNETTE, () -> {
                level.getMaxLocalRawBrightness(FRINGE);
                used[0] = true;
            });
            helper.assertTrue(!completed && !used[0], "unknown native eye brightness aborts optional GUI work");
            helper.assertTrue(OptionalLightWork.skipped(OptionalLightWork.Kind.VIGNETTE) == before + 1,
                    "vignette skip is recorded once");
            boolean unavailable = false;
            try { level.getRawBrightness(FRINGE, 15); }
            catch (CubeBlockLightAccess.UnavailableLightException expected) { unavailable = true; }
            helper.assertTrue(unavailable, "explicit raw light query stays unavailable, with no fake dark value");
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    // Break caught: a skip disables later known rendering, or counts unrelated optional consumer kinds.
    @GameTest(template = "empty")
    public static void knownInteriorWorkRecoversAndConsumerCountersStayIndependent(GameTestHelper helper) {
        var level = helper.getLevel(); DeepCubeDomain.attach(level, physical(), true);
        try {
            for (var kind : new OptionalLightWork.Kind[]{OptionalLightWork.Kind.VIGNETTE,
                    OptionalLightWork.Kind.HANDS, OptionalLightWork.Kind.DEBUG, OptionalLightWork.Kind.ENTITY,
                    OptionalLightWork.Kind.PARTICLE,OptionalLightWork.Kind.ANIMATION}) {
                long[] before = counters();
                boolean complete = OptionalLightWork.run(level, kind, () -> level.getRawBrightness(FRINGE, 15));
                helper.assertTrue(!complete, "unknown preflight must skip " + kind);
                for (var other : OptionalLightWork.Kind.values()) {
                    long expected = before[other.ordinal()] + (other == kind ? 1 : 0);
                    helper.assertTrue(OptionalLightWork.skipped(other) == expected, "independent counter for " + other);
                }
                boolean[] used = {false};
                complete = OptionalLightWork.run(level, kind, () -> {
                    helper.assertTrue(level.getRawBrightness(INTERIOR, 15) == 0,
                            "interior is beyond the unknown boundary's 14m reach; SKY is explicitly subtracted");
                    used[0] = true;
                });
                helper.assertTrue(complete && used[0], "confirmed preflight resumes " + kind);
                helper.assertTrue(OptionalLightWork.skipped(kind) == before[kind.ordinal()] + 1,
                        "successful operation does not count another skip");
            }
        } finally { DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    // Break caught: an optional guard hides arbitrary bugs or suppresses unavailable values without an enabled domain.
    @GameTest(template = "empty")
    public static void unrelatedDisabledAndDetachedFailuresPropagateWithoutCountingSkips(GameTestHelper helper) {
        var level = helper.getLevel();
        var bug = new IllegalStateException("unrelated optional consumer failure");
        var unknown = new CubeBlockLightAccess.UnavailableLightException(FRINGE);
        long before = OptionalLightWork.skipped(OptionalLightWork.Kind.VIGNETTE);
        DeepCubeDomain.attach(level, physical(), true);
        try {
            boolean propagated = false;
            try { OptionalLightWork.run(level, OptionalLightWork.Kind.VIGNETTE, () -> { throw bug; }); }
            catch (IllegalStateException actual) { propagated = actual == bug; }
            helper.assertTrue(propagated, "enabled guard propagates unrelated failures unchanged");
            DeepCubeDomain.attach(level, physical(), false);
            propagated = false;
            try { OptionalLightWork.run(level, OptionalLightWork.Kind.VIGNETTE, () -> { throw unknown; }); }
            catch (CubeBlockLightAccess.UnavailableLightException actual) { propagated = actual == unknown; }
            helper.assertTrue(propagated, "disabled BLOCK domain does not authorize suppression");
        } finally { DeepCubeDomain.detach(level); }
        boolean propagated = false;
        try { OptionalLightWork.run(level, OptionalLightWork.Kind.VIGNETTE, () -> { throw unknown; }); }
        catch (CubeBlockLightAccess.UnavailableLightException actual) { propagated = actual == unknown; }
        helper.assertTrue(propagated, "detached native work cannot suppress unavailable light");
        helper.assertTrue(OptionalLightWork.skipped(OptionalLightWork.Kind.VIGNETTE) == before,
                "propagated exceptions do not become recorded skips");
        helper.succeed();
    }

    private static long[] counters() {
        var counts = new long[OptionalLightWork.Kind.values().length];
        for (var kind : OptionalLightWork.Kind.values()) counts[kind.ordinal()] = OptionalLightWork.skipped(kind);
        return counts;
    }

    private static SparseCubeSpace physical() {
        var space = new SparseCubeSpace();
        for (int x = 11; x <= 13; x++) for (int y = -1366; y <= -1364; y++) for (int z = 11; z <= 13; z++)
            space.loadEmpty(new CubeKey(x, y, z));
        return space;
    }
}
