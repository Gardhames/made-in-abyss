package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProbeHomeGameTests {
    private static final String HOME = "madeinabyssDeepProbeHomeV1";

    @GameTest(template = "empty")
    public static void logoutRestoresCapturedFlightSpeedAndClearsRecovery(GameTestHelper h) {
        var player = player(h);
        try {
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.getAbilities().setFlyingSpeed(.13f);
            CubeProbeNetwork.capturePlayerHome(player);
            var saved = player.getPersistentData().getCompound(HOME);
            h.assertTrue(saved.contains("flightSpeed", 99) && Math.abs(saved.getFloat("flightSpeed") - .13f) < 1e-7,
                    "crash recovery must persist the original native flight speed before the diagnostic overrides it");
            player.getAbilities().setFlyingSpeed(.6f);
            MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
            h.assertTrue(Math.abs(player.getAbilities().getFlyingSpeed() - .13f) < 1e-7,
                    "logout restores the original speed instead of saving diagnostic acceleration");
            h.assertTrue(player.getAbilities().flying, "original flight state is restored");
            h.assertTrue(!player.getPersistentData().contains(HOME), "successful recovery clears the persistent marker");
            h.succeed();
        } finally { MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player)); player.discard(); }
    }

    @GameTest(template = "empty")
    public static void legacyRecoveryWithoutSpeedUsesNormalFlightRatherThanDiagnosticSpeed(GameTestHelper h) {
        var player = player(h);
        try {
            var home = new CompoundTag();
            home.putDouble("x", player.getX()); home.putDouble("y", player.getY()); home.putDouble("z", player.getZ());
            home.putString("mode", "creative"); home.putBoolean("flying", true);
            player.getPersistentData().put(HOME, home);
            player.getAbilities().setFlyingSpeed(.6f);
            MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
            h.assertTrue(Math.abs(player.getAbilities().getFlyingSpeed() - .05f) < 1e-7,
                    "legacy marker cannot leave diagnostic flight speed in persisted player state");
            h.assertTrue(!player.getPersistentData().contains(HOME), "legacy recovery still completes");
            h.succeed();
        } finally { MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player)); player.discard(); }
    }

    // Forge's makeMockServerPlayerInLevel enters its login pipeline with a null Channel.
    // Recovery does not require login or a wire: use the actual player/listener, whose sends
    // are queued by native Connection until connected, and do not add it to the player list.
    private static ServerPlayer player(GameTestHelper h) {
        var level = h.getLevel();
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "Dev"));
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player);
        var origin = h.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
        player.setPos(origin.getX(), origin.getY(), origin.getZ());
        return player;
    }
}
