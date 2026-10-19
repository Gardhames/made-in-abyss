package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit development-only offline loopback transport, not vanilla authenticated LAN publication. */
public final class ProbeLanBridge {
    public static final int PORT = 25576;
    private static final Set<String> ROSTER = Set.of("Dev", "MiaNative02", "MiaNative03", "MiaNative04");
    // Values do not retain their server keys; native world shutdown owns TCP channel closure.
    private static final Map<IntegratedServer, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile JsonObject last = inactive();

    private static final class State {
        final String request = UUID.randomUUID().toString();
        final boolean previousPauseOnLostFocus;
        final AtomicBoolean refreshQueued = new AtomicBoolean();
        volatile boolean opened;
        volatile boolean closed;
        volatile JsonObject report;
        State(boolean pause) {
            previousPauseOnLostFocus = pause;
            report = inactive();
            report.addProperty("request", request);
            report.addProperty("completed", false);
        }
    }

    static { MinecraftForge.EVENT_BUS.addListener(ProbeLanBridge::stopped); }
    private ProbeLanBridge() {}
    private static boolean enabled() {
        return Boolean.getBoolean("mia.client.automation") && Boolean.getBoolean("mia.cube.playerProbe");
    }

    public static JsonObject open(Minecraft client, JsonObject params) {
        if (!enabled()) throw new IllegalStateException("Loopback probe requires both development flags");
        if (!params.has("enable") || !params.get("enable").isJsonPrimitive()
                || !params.getAsJsonPrimitive("enable").isBoolean() || !params.get("enable").getAsBoolean())
            throw new IllegalArgumentException("Explicit boolean enable:true is required");
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null || !client.hasSingleplayerServer()
                || !server.isSingleplayerOwner(client.player.getGameProfile()))
            throw new IllegalStateException("Only the connected integrated host can open the loopback probe");
        if (client.level.dimension() != Level.OVERWORLD || client.player.getY() < client.level.getMinBuildHeight()
                || !client.player.getGameProfile().getName().equals("Dev"))
            throw new IllegalStateException("Loopback probe requires the ordinary overworld development host Dev");
        if (server.isPublished()) throw new IllegalStateException("Vanilla published LAN cannot become a loopback-only probe");
        if (client.getOverlay() != null && client.getOverlay().isPauseScreen())
            throw new IllegalStateException("Close the pausing overlay before opening the loopback probe");
        State state;
        synchronized (STATES) {
            State existing = STATES.get(server);
            if (existing != null && !existing.closed) return existing.report.deepCopy();
            state = new State(client.options.pauseOnLostFocus);
            STATES.put(server, state);
        }
        client.options.pauseOnLostFocus = false;
        client.setScreen(null);
        server.execute(() -> {
            boolean priorAuthentication = server.usesAuthentication();
            try {
                if (server.isPublished() || server.getPlayerList().getPlayerCount() != 1
                        || !server.getPlayerList().getPlayers().get(0).getGameProfile().getName().equals("Dev"))
                    throw new IllegalStateException("Probe opening requires one ordinary integrated host and no published listener");
                var host = server.getPlayerList().getPlayers().get(0);
                if (!server.isSingleplayerOwner(host.getGameProfile()) || host.level().dimension() != Level.OVERWORLD
                        || host.getY() < host.level().getMinBuildHeight())
                    throw new IllegalStateException("The authoritative host must remain in the ordinary overworld before binding");
                server.setUsesAuthentication(false);
                server.getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"), PORT);
                state.opened = true;
                publish(server, state);
            } catch (Exception error) {
                server.setUsesAuthentication(priorAuthentication);
                state.closed = true;
                JsonObject report = inactive();
                report.addProperty("request", state.request);
                report.addProperty("completed", true);
                report.addProperty("error", error.toString());
                state.report = report; last = report;
                client.execute(() -> client.options.pauseOnLostFocus = state.previousPauseOnLostFocus);
            }
        });
        return state.report.deepCopy();
    }

    /** A refresh runs on the actual server thread; callers wait for successive gameTime values. */
    public static JsonObject status(Minecraft client) {
        IntegratedServer server = client.getSingleplayerServer();
        State state = server == null ? null : STATES.get(server);
        if (state == null) {
            // A report from a previous world must never advertise an active bridge in this one.
            JsonObject report = server == null ? last.deepCopy() : inactive();
            report.addProperty("active", false);
            return report;
        }
        JsonObject observed = state.report.deepCopy();
        if (state.opened && !state.closed && state.refreshQueued.compareAndSet(false, true)) {
            server.execute(() -> {
                try { if (!state.closed) publish(server, state); }
                finally { state.refreshQueued.set(false); }
            });
        }
        return observed;
    }

    /** Null permits native bans/name-taken/limit checks; no replacement for authenticated admission. */
    public static Component rejection(IntegratedServer server, SocketAddress address, GameProfile profile) {
        State state = STATES.get(server);
        // Once explicitly opened, keep admission restricted until native world shutdown,
        // even if a development property is subsequently cleared in this JVM.
        if (state == null || !state.opened || state.closed) return null;
        boolean loopback = address instanceof InetSocketAddress socket && socket.getAddress() != null
                && socket.getAddress().isLoopbackAddress();
        if (!loopback || profile == null || !ROSTER.contains(profile.getName()))
            return Component.literal("Only the four loopback development profiles are permitted");
        return null;
    }

    private static void publish(IntegratedServer server, State state) {
        JsonObject report = inactive();
        report.addProperty("request", state.request);
        report.addProperty("completed", true);
        report.addProperty("active", state.opened && !state.closed);
        report.addProperty("serverPid", ProcessHandle.current().pid());
        report.addProperty("serverTick", server.getTickCount());
        report.addProperty("gameTime", server.overworld().getGameTime());
        report.addProperty("authentication", server.usesAuthentication());
        report.addProperty("published", server.isPublished());
        report.addProperty("nativeReportedPort", server.getPort());
        report.addProperty("players", server.getPlayerList().getPlayerCount());
        report.addProperty("rosterSize", ROSTER.size());
        state.report = report; last = report;
    }

    private static void stopped(ServerStoppedEvent event) {
        if (!(event.getServer() instanceof IntegratedServer server)) return;
        State state = STATES.remove(server);
        if (state == null) return;
        state.closed = true; state.opened = false;
        JsonObject report = state.report.deepCopy();
        report.addProperty("active", false); report.addProperty("closed", true);
        state.report = report; last = report;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            // Do not overwrite settings for a newer bridge/world started during shutdown.
            if (STATES.isEmpty()) client.options.pauseOnLostFocus = state.previousPauseOnLostFocus;
        });
    }

    private static JsonObject inactive() {
        JsonObject report = new JsonObject();
        report.addProperty("active", false);
        report.addProperty("boundAddress", "127.0.0.1");
        report.addProperty("port", PORT);
        report.addProperty("scope", "Offline loopback diagnostic integrated transport; no multicast or vanilla LAN publication");
        return report;
    }
}
