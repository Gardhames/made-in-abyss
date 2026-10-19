package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.madeinabyss.forge.cube.CubeBlockView;
import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.forge.probe.CubeProbeNetwork;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Client main-thread diagnostics over received native sections, separate from ClientLevel. */
final class ClientCubeProbe {
    private static JsonObject report = new JsonObject();
    private static UUID pending;
    private static SparseCubeSpace accepted;
    private static boolean playerSetup;
    private static UUID pendingQuery;
    private static JsonObject playerReport = new JsonObject();
    private ClientCubeProbe() {}

    static JsonObject begin(Minecraft client, int phase) {
        if (client.level == null || client.getConnection() == null) throw new IllegalStateException("No client connection");
        var id = UUID.randomUUID();
        pending = id;
        playerSetup = false;
        report = new JsonObject();
        report.addProperty("request", id.toString()); report.addProperty("completed", false);
        CubeProbeNetwork.sendRequest(id, phase, client.getConnection().getConnection(), ClientCubeProbe::receive);
        var result = new JsonObject(); result.addProperty("request", id.toString()); result.addProperty("sent", true);
        return result;
    }

    static JsonObject status() { return report.deepCopy(); }
    static void disconnect() {
        if (Minecraft.getInstance().level != null) { ClientDeepItems.clear(Minecraft.getInstance().level); DeepCubeDomain.detach(Minecraft.getInstance().level); }
        pending = null; accepted = null; report = new JsonObject();
        pendingQuery = null; playerReport = new JsonObject(); playerSetup = false;
    }

    static JsonObject beginPlayer(Minecraft client) {
        if (client.level == null || client.getConnection() == null) throw new IllegalStateException("No client connection");
        pending = UUID.randomUUID(); playerSetup = true;
        report = new JsonObject(); report.addProperty("request", pending.toString()); report.addProperty("completed", false);
        CubeProbeNetwork.sendPlayerSetup(pending, client.getConnection().getConnection(), ClientCubeProbe::receive);
        var result = new JsonObject(); result.addProperty("request", pending.toString()); result.addProperty("sent", true); return result;
    }
    static JsonObject queryPlayer(Minecraft client, boolean returnHome) {
        if (client.level == null || client.getConnection() == null) throw new IllegalStateException("No client connection");
        pendingQuery = UUID.randomUUID(); playerReport = new JsonObject();
        playerReport.addProperty("request", pendingQuery.toString()); playerReport.addProperty("completed", false);
        CubeProbeNetwork.queryPlayer(pendingQuery, returnHome, client.getConnection().getConnection(), state -> {
            if (!state.id().equals(pendingQuery)) return;
            var response = new JsonObject(); response.addProperty("request", state.id().toString()); response.addProperty("completed", true);
            var xyz = new JsonArray(); xyz.add(state.x()); xyz.add(state.y()); xyz.add(state.z()); response.add("position", xyz);
            response.addProperty("onGround", state.onGround()); response.addProperty("floorBlock", state.floorBlock());
            response.addProperty("returned", state.returned()); response.addProperty("serverPid", state.serverPid());
            response.addProperty("gameMode", state.gameMode()); response.addProperty("flying", state.flying());
            response.addProperty("flightSpeed", state.flightSpeed());
            playerReport = response;
            if (state.returned() && client.level != null) { ClientDeepRuntime.disconnect(); DeepCubeDomain.detach(client.level); }
        });
        var result = new JsonObject(); result.addProperty("request", pendingQuery.toString()); result.addProperty("sent", true); return result;
    }
    static JsonObject playerStatus() { return playerReport.deepCopy(); }

    private static void receive(CubeProbeNetwork.Reply reply) {
        if (!reply.id().equals(pending)) return;
        Minecraft client = Minecraft.getInstance();
        try {
            if (client.level == null) throw new IllegalStateException("Disconnected while receiving cube batch");
            var biomes = client.level.registryAccess().registryOrThrow(Registries.BIOME);
            var next = new SparseCubeSpace();
            int bytes = 0;
            for (byte[] frame : reply.frames()) {
                bytes += frame.length;
                var input = new FriendlyByteBuf(Unpooled.wrappedBuffer(frame));
                try {
                    var decoded = CubeSectionPacketCodec.decode(input, biomes);
                    int[] states = new int[CubeSnapshot.BLOCK_COUNT];
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                        states[(y << 8) | (z << 4) | x] = Block.getId(decoded.section().getBlockState(x, y, z));
                    next.load(new CubeSnapshot(decoded.key(), states)); // Duplicate keys are rejected.
                } finally { input.release(); }
            }
            var view = new CubeBlockView(next, -24000, 24576);
            var result = new JsonObject();
            result.addProperty("request", reply.id().toString()); result.addProperty("phase", reply.phase());
            result.addProperty("completed", true); result.addProperty("cubeCount", next.loadedCubeCount());
            result.addProperty("serverDedicated", reply.serverDedicated());
            result.addProperty("serverPid", reply.serverPid()); result.addProperty("clientPid", ProcessHandle.current().pid());
            result.addProperty("packetBytes", bytes);
            var markers = new JsonArray();
            for (int y : CubeProbeNetwork.DEPTHS) {
                var position = new BlockPos(5, y, 5);
                var marker = new JsonObject(); marker.add("position", xyz(position));
                marker.addProperty("block", BuiltInRegistries.BLOCK.getKey(view.getBlockState(position).getBlock()).toString());
                var hit = view.clip(new ClipContext(new Vec3(5.5, y + 3.5, 5.5), new Vec3(5.5, y - 2, 5.5),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
                marker.addProperty("rayType", hit.getType().name());
                if (hit.getType() == HitResult.Type.BLOCK) marker.add("hitPosition", xyz(hit.getBlockPos()));
                marker.addProperty("obstructed", !view.noCollision(new AABB(5.2, y + .2, 5.2, 5.8, y + .8, 5.8)));
                markers.add(marker);
            }
            result.add("markers", markers);
            boolean unloadedRejected = false;
            try { view.getBlockState(new BlockPos(5, -8000, 5)); }
            catch (CubeBlockView.MissingCubeException expected) { unloadedRejected = true; }
            result.addProperty("unloadedRejected", unloadedRejected);
            // Publish only after every frame and every native query succeeded.
            if (playerSetup) DeepCubeDomain.attach(client.level, next);
            accepted = next; report = result;
        } catch (RuntimeException invalid) {
            report.addProperty("completed", true); report.addProperty("error", invalid.toString());
            report.addProperty("previousCubeCount", accepted == null ? 0 : accepted.loadedCubeCount());
        }
    }

    private static JsonArray xyz(BlockPos position) {
        var values = new JsonArray(); values.add(position.getX()); values.add(position.getY()); values.add(position.getZ());
        return values;
    }
}
