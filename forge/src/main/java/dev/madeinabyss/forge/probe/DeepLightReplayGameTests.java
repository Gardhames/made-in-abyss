package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.client.ClientDeepLight;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepLightNetwork;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.UUID;

/** Actual client scalar-cache state machine on its generic Level API; no native client/transport claim. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepLightReplayGameTests {
    @GameTest(template = "empty")
    public static void invalidScalarRevisionCannotReactivateAnOlderSnapshot(GameTestHelper helper) {
        var level = helper.getLevel();
        var space = window();
        var session = UUID.randomUUID();
        DeepCubeDomain.attach(level, space);
        try {
            var inputs = DeepCubeDomain.lookup(level).orElseThrow().blockLight().deepInputs();
            ClientDeepLight.begin(session, true);
            ClientDeepLight.geometry(level, 1, space.residentKeys());
            ClientDeepLight.receive(new DeepLightNetwork.Snapshot(session, 1, 8, true, "", inputs));
            helper.assertTrue(ClientDeepLight.status().get("available").getAsBoolean(), "valid8 accepted");
            // Structurally decoded but invalid for this exact geometry: every core input is missing.
            ClientDeepLight.receive(new DeepLightNetwork.Snapshot(session, 1, 10, true, "", List.of()));
            var invalid = ClientDeepLight.status();
            helper.assertTrue(!invalid.get("available").getAsBoolean(), "invalid10 discards active scalar inputs");
            ClientDeepLight.receive(new DeepLightNetwork.Snapshot(session, 1, 9, true, "", inputs));
            var replay = ClientDeepLight.status();
            helper.assertTrue(!replay.get("available").getAsBoolean() && replay.get("revision").getAsLong() >= 10,
                    "valid9 must not reactivate old data after invalid10: invalid=" + invalid + ", replay=" + replay);
            ClientDeepLight.receive(new DeepLightNetwork.Snapshot(session, 1, 11, true, "", inputs));
            helper.assertTrue(ClientDeepLight.status().get("available").getAsBoolean()
                    && ClientDeepLight.status().get("revision").getAsLong() == 11, "newer valid11 recovers");
        } finally { ClientDeepLight.clear(); DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void futureScalarGeometryWaitsAndCannotBeReplacedByOldSession(GameTestHelper helper) {
        var level = helper.getLevel();
        var space = window();
        var session = UUID.randomUUID();
        DeepCubeDomain.attach(level, space);
        try {
            var inputs = DeepCubeDomain.lookup(level).orElseThrow().blockLight().deepInputs();
            ClientDeepLight.begin(session, true);
            ClientDeepLight.geometry(level, 1, space.residentKeys());
            ClientDeepLight.receive(new DeepLightNetwork.Snapshot(session, 1, 1, true, "", inputs));
            ClientDeepLight.receive(new DeepLightNetwork.Snapshot(session, 2, 3, true, "", inputs));
            helper.assertTrue(!ClientDeepLight.status().get("available").getAsBoolean(), "future geometry scalar stays inactive");
            ClientDeepLight.receive(new DeepLightNetwork.Snapshot(UUID.randomUUID(), 1, 99, true, "", inputs));
            ClientDeepLight.receive(new DeepLightNetwork.Snapshot(session, 1, 2, true, "", inputs));
            helper.assertTrue(!ClientDeepLight.status().get("available").getAsBoolean(), "old session/current geometry cannot supersede pending future scalar");
            ClientDeepLight.geometry(level, 2, space.residentKeys());
            var status = ClientDeepLight.status();
            helper.assertTrue(status.get("available").getAsBoolean() && status.get("revision").getAsLong() == 3
                    && status.get("geometryRevision").getAsLong() == 2, "matching geometry commits the pending packet exactly once");
            ClientDeepLight.receive(new DeepLightNetwork.Snapshot(session, 2, 4, false, "fixture unavailable", List.of()));
            ClientDeepLight.receive(new DeepLightNetwork.Snapshot(session, 2, 3, true, "", inputs));
            helper.assertTrue(!ClientDeepLight.status().get("available").getAsBoolean(), "server unavailable4 cannot be undone by replay3");
        } finally { ClientDeepLight.clear(); DeepCubeDomain.detach(level); }
        helper.succeed();
    }

    private static SparseCubeSpace window() {
        var space = new SparseCubeSpace();
        for (int x = -1; x <= 1; x++) for (int y = -1251; y <= -1249; y++) for (int z = -1; z <= 1; z++)
            space.loadEmpty(new CubeKey(x,y,z));
        return space;
    }
}
