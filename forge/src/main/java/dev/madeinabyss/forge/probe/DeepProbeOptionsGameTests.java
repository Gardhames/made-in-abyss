package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepProbeStart;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepProbeOptionsGameTests {
    @GameTest(template = "empty")
    public static void ascentStartsAboveRealFloorWithBoundedFlight(GameTestHelper h) {
        var start = DeepProbeStart.vertical("ascent", null, false);
        h.assertTrue(start.startY() == -21999 && start.flying(), "ascent starts above the real floor with flight enabled");
        h.assertTrue(Math.abs(start.flightSpeed() - .6f) < 1e-7, "diagnostic ascent uses bounded native flight speed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void fourDepthStartsRetainSignedYAndNormalFlight(GameTestHelper h) {
        for (int y : new int[]{0, -4000, -12000, -21000}) {
            var start = DeepProbeStart.vertical("descent", y, true);
            h.assertTrue(start.startY() == y && start.flying(), "explicit signed depth cannot alias or fall by default");
            h.assertTrue(Math.abs(start.flightSpeed() - .05f) < 1e-7, "multiplayer starts retain normal creative speed");
        }
        var legacy = DeepProbeStart.vertical("descent", null, false);
        h.assertTrue(legacy.startY() == 80 && !legacy.flying(), "legacy descent remains a gravity test from the surface");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidStartsAndConflictingAscentCannotEnterResidency(GameTestHelper h) {
        for (int y : new int[]{Integer.MIN_VALUE, -22000, 81, Integer.MAX_VALUE})
            refused(h, () -> DeepProbeStart.vertical("descent", y, true));
        refused(h, () -> DeepProbeStart.vertical("sideways", null, false));
        refused(h, () -> DeepProbeStart.vertical("ascent", -4000, true));
        for (float speed : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0, -.05f, .61f})
            refused(h, () -> new DeepProbeStart(-21000, true, speed));
        h.assertTrue(DeepProbeStart.vertical("descent", -21999, true).startY() == -21999, "lowest safe feet boundary is accepted");
        h.assertTrue(DeepProbeStart.vertical("descent", 80, true).startY() == 80, "highest diagnostic boundary is accepted");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void developerRosterRequiresOptInAndExactIdentity(GameTestHelper h) {
        for (String name : new String[]{"Dev", "MiaNative02", "MiaNative03", "MiaNative04"}) {
            h.assertTrue(DevProbeAccess.allowed(name, true), "authorized native identity must use the shared probe");
            h.assertTrue(!DevProbeAccess.allowed(name, false), "production defaults cannot enable diagnostic teleports");
        }
        for (String name : new String[]{"dev", "MiaNative01", "MiaNative05", "Dev ", "", "Visitor"})
            h.assertTrue(!DevProbeAccess.allowed(name, true), "unlisted identity must not enter development fixtures");
        h.assertTrue(!DevProbeAccess.allowed(null, true), "missing identity must not be authorized");
        h.succeed();
    }

    private static void refused(GameTestHelper h, Runnable operation) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "invalid setup must be refused before any player/store mutation");
    }
}
