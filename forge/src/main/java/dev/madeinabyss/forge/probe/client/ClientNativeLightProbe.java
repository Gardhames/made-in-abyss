package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/** Client-only native seam observation; never changes native/server blocks or player geometry. */
public final class ClientNativeLightProbe {
    private static WeakReference<ClientLevel> fixtureLevel = new WeakReference<>(null);
    private static SparseCubeSpace fixtureSpace;
    private static BlockPos target;
    private ClientNativeLightProbe() {}

    public static JsonObject begin(Minecraft client, JsonObject params) {
        if (!Boolean.getBoolean("mia.client.automation") || !Boolean.getBoolean("mia.cube.playerProbe"))
            throw new IllegalStateException("Native light fixture requires both development flags");
        if (client.player == null || client.level == null || client.getConnection() == null
                || !client.player.onGround() || client.player.getAbilities().flying
                || client.player.getY() < client.level.getMinBuildHeight())
            throw new IllegalStateException("Native light fixture requires a connected ordinary grounded player");
        ClientLevel level = client.level;
        if (level.dimension() != Level.OVERWORLD || level.getMinBuildHeight() != -64)
            throw new IllegalStateException("Native light fixture requires the ordinary overworld min Y=-64");
        discardStale(client);
        if (fixtureSpace != null || DeepCubeDomain.lookup(level).isPresent())
            throw new IllegalStateException("An existing deep domain cannot be replaced by the client-only light fixture");
        BlockPos position = position(params);
        if (position.getY() != level.getMinBuildHeight() - 1)
            throw new IllegalArgumentException("Fixture position must be directly below the native minimum height");
        CubeKey center = CubeKey.fromBlock(coordinate(position));
        if (center.x() < -134217727 || center.x() > 134217726
                || center.z() < -134217727 || center.z() > 134217726)
            throw new IllegalArgumentException("Complete 3x3x3 fixture must fit signed int block coordinates");
        // Inspect the source column without loading, creating or modifying native terrain.
        if (level.getChunkSource().getChunk(center.x(), center.z(), ChunkStatus.FULL, false) == null)
            throw new IllegalStateException("The native source column is not in the client cache");
        var candidate = new SparseCubeSpace();
        int stone = Block.getId(Blocks.STONE.defaultBlockState());
        for (int y = center.y() - 1; y <= center.y() + 1; y++)
            for (int z = center.z() - 1; z <= center.z() + 1; z++)
                for (int x = center.x() - 1; x <= center.x() + 1; x++) {
                    int[] states = new int[4096];
                    Arrays.fill(states, stone);
                    candidate.load(new CubeSnapshot(new CubeKey(x, y, z), states));
                }
        int air = Block.getId(Blocks.AIR.defaultBlockState());
        candidate.setBlock(coordinate(position), air);
        candidate.setBlock(coordinate(position.below()), air);
        // The ordinary snapshot remains STONE even if real native packets turn its source into GLOWSTONE.
        DeepCubeDomain.attach(level, candidate, true);
        fixtureLevel = new WeakReference<>(level); fixtureSpace = candidate; target = position;
        JsonObject result = status(client);
        result.addProperty("completed", true);
        return result;
    }

    public static JsonObject status(Minecraft client) {
        discardStale(client);
        JsonObject report = new JsonObject();
        ClientLevel level = fixtureLevel.get();
        boolean active = level != null && level == client.level && fixtureSpace != null
                && DeepCubeDomain.lookup(level).map(value -> value.space() == fixtureSpace).orElse(false);
        report.addProperty("active", active);
        report.addProperty("fixture", "client-only-native-light-seam");
        report.addProperty("scope", "Local scalar-light fixture; no server geometry, native block mutation or player teleport");
        if (!active) return report;
        report.add("position", coordinates(target));
        BlockPos source = target.above();
        report.add("nativeSourcePosition", coordinates(source));
        var chunk = level.getChunkSource().getChunk(Math.floorDiv(source.getX(), 16),
                Math.floorDiv(source.getZ(), 16), ChunkStatus.FULL, false);
        report.addProperty("nativeSourceLoaded", chunk != null);
        if (chunk != null) {
            var state = chunk.getBlockState(source);
            report.addProperty("nativeSourceBlock", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            report.addProperty("nativeSourceState", state.toString());
        }
        var light = DeepCubeDomain.lookup(level).orElseThrow().blockLight();
        report.addProperty("revision", light.revision(level));
        report.addProperty("computedGeneration", light.computedGeneration());
        report.addProperty("recomputations", light.recomputations());
        report.addProperty("computedCubes", light.computedCubes());
        report.addProperty("nativeHaloCubes", light.nativeHaloCubes());
        report.addProperty("cubeCount", fixtureSpace.loadedCubeCount());
        report.addProperty("contentGeneration", fixtureSpace.contentGeneration());
        return report;
    }

    public static JsonObject end(Minecraft client) {
        ClientLevel level = fixtureLevel.get();
        boolean detached = level != null && fixtureSpace != null
                && DeepCubeDomain.lookup(level).map(value -> value.space() == fixtureSpace).orElse(false);
        if (detached) DeepCubeDomain.detach(level);
        clear();
        JsonObject report = new JsonObject();
        report.addProperty("active", false); report.addProperty("detached", detached);
        report.addProperty("otherDomainPresent", DeepCubeDomain.lookup(client.level).isPresent());
        return report;
    }

    private static void discardStale(Minecraft client) {
        ClientLevel level = fixtureLevel.get();
        if (fixtureSpace != null && (level == null || level != client.level
                || DeepCubeDomain.lookup(level).map(value -> value.space() != fixtureSpace).orElse(true)))
            end(client);
    }
    private static void clear() {
        fixtureLevel = new WeakReference<>(null); fixtureSpace = null; target = null;
    }
    private static BlockPos position(JsonObject params) {
        if (!params.has("position") || !params.get("position").isJsonArray())
            throw new IllegalArgumentException("Expected position:[signed int X,Y,Z]");
        JsonArray values = params.getAsJsonArray("position");
        if (values.size() != 3) throw new IllegalArgumentException("Expected exactly three coordinates");
        int[] axes = new int[3];
        for (int i = 0; i < axes.length; i++) {
            if (!values.get(i).isJsonPrimitive() || !values.get(i).getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Coordinates must be signed integers");
            try { axes[i] = values.get(i).getAsBigDecimal().intValueExact(); }
            catch (ArithmeticException | NumberFormatException invalid) {
                throw new IllegalArgumentException("Coordinate is not a signed integer", invalid);
            }
        }
        return new BlockPos(axes[0], axes[1], axes[2]);
    }
    private static BlockCoordinate coordinate(BlockPos pos) { return new BlockCoordinate(pos.getX(), pos.getY(), pos.getZ()); }
    private static JsonArray coordinates(BlockPos position) {
        JsonArray values = new JsonArray(); values.add(position.getX()); values.add(position.getY()); values.add(position.getZ()); return values;
    }
}
