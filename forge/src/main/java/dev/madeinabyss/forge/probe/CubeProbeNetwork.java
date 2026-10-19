package dev.madeinabyss.forge.probe;

import com.mojang.logging.LogUtils;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.ticket.CubeTickets;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.GameType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Development-only native network fixture. Does not replace the player's Level/chunks. */
public final class CubeProbeNetwork {
    public static final int[] DEPTHS = {64, -4096, -15904, -20000};
    private static final int MAX_CUBES = 128;
    private static final int MAX_FRAME = CubeSectionPacketCodec.MAX_SECTION_BYTES + 24;
    private static final int MAX_BATCH = 1048576;
    private static final String VERSION = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MadeInAbyssProbe.MOD_ID, "cube_probe"), () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));
    private static final Map<UUID, Fixture> FIXTURES = new HashMap<>();
    private static Consumer<Reply> clientReceiver = reply -> {};
    private static Consumer<PlayerState> playerReceiver = reply -> {};
    private static final Map<UUID, Home> PLAYER_HOMES = new HashMap<>();
    private static final String HOME_TAG = "madeinabyssDeepProbeHomeV1";
    private CubeProbeNetwork() {}

    public static void capturePlayerHome(ServerPlayer player) {
        PLAYER_HOMES.putIfAbsent(player.getUUID(), new Home(player.position(),player.getYRot(),player.getXRot(),
                player.gameMode.getGameModeForPlayer(),player.getAbilities().flying,player.getAbilities().getFlyingSpeed()));
        rememberHome(player,PLAYER_HOMES.get(player.getUUID()));
        player.getServer().getPlayerList().saveAll();
    }

    public static void register() {
        CHANNEL.registerMessage(0, Request.class, (message, out) -> {
            out.writeUUID(message.id()); out.writeInt(message.phase());
        }, in -> {
            var request = new Request(in.readUUID(), in.readInt());
            if (in.isReadable()) throw new IllegalArgumentException("trailing cube request");
            return request;
        }, CubeProbeNetwork::handleRequest, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Reply.class, CubeProbeNetwork::encodeReply,
                CubeProbeNetwork::decodeReply, (message, context) -> {
                    var ctx = context.get();
                    ctx.enqueueWork(() -> clientReceiver.accept(message));
                    ctx.setPacketHandled(true);
                }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, PlayerSetup.class, (message, out) -> out.writeUUID(message.id()),
                in -> {
                    var request = new PlayerSetup(in.readUUID());
                    if (in.isReadable()) throw new IllegalArgumentException("Trailing player setup");
                    return request;
                }, CubeProbeNetwork::handlePlayerSetup, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, PlayerQuery.class, (message, out) -> {
            out.writeUUID(message.id()); out.writeBoolean(message.returnHome());
        }, in -> {
            var request = new PlayerQuery(in.readUUID(), in.readBoolean());
            if (in.isReadable()) throw new IllegalArgumentException("Trailing player query");
            return request;
        }, CubeProbeNetwork::handlePlayerQuery, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(4, PlayerState.class, (message, out) -> {
            out.writeUUID(message.id()); out.writeDouble(message.x()); out.writeDouble(message.y()); out.writeDouble(message.z());
            out.writeBoolean(message.onGround()); out.writeUtf(message.floorBlock(), 128);
            out.writeBoolean(message.returned()); out.writeLong(message.serverPid());
            out.writeUtf(message.gameMode(), 32); out.writeBoolean(message.flying());
            out.writeFloat(message.flightSpeed());
        }, in -> {
            var state = new PlayerState(in.readUUID(), in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readBoolean(), in.readUtf(128), in.readBoolean(), in.readLong(), in.readUtf(32), in.readBoolean(), in.readFloat());
            if (in.isReadable()) throw new IllegalArgumentException("Trailing player state");
            return state;
        }, (message, context) -> {
            var ctx = context.get(); ctx.enqueueWork(() -> playerReceiver.accept(message)); ctx.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(CubeProbeNetwork::onLogout);
        MinecraftForge.EVENT_BUS.addListener(CubeProbeNetwork::onLogin);
        MinecraftForge.EVENT_BUS.addListener(CubeProbeNetwork::onServerStop);
    }

    public static void sendRequest(UUID id, int phase, net.minecraft.network.Connection connection,
                                   Consumer<Reply> receiver) {
        if (!Boolean.getBoolean("mia.cube.networkProbe")) throw new IllegalStateException("Cube network fixture disabled");
        if (!CHANNEL.isRemotePresent(connection)) throw new IllegalStateException("Remote cube channel absent");
        clientReceiver = java.util.Objects.requireNonNull(receiver);
        CHANNEL.sendToServer(new Request(id, phase));
    }

    public static void sendPlayerSetup(UUID id, net.minecraft.network.Connection connection, Consumer<Reply> receiver) {
        requirePlayerChannel(connection);
        clientReceiver = java.util.Objects.requireNonNull(receiver);
        CHANNEL.sendToServer(new PlayerSetup(id));
    }
    public static void queryPlayer(UUID id, boolean returnHome, net.minecraft.network.Connection connection, Consumer<PlayerState> receiver) {
        requirePlayerChannel(connection);
        playerReceiver = java.util.Objects.requireNonNull(receiver);
        CHANNEL.sendToServer(new PlayerQuery(id, returnHome));
    }
    private static void requirePlayerChannel(net.minecraft.network.Connection connection) {
        if (!Boolean.getBoolean("mia.cube.playerProbe") || !CHANNEL.isRemotePresent(connection))
            throw new IllegalStateException("Deep player probe unavailable");
    }

    private static void handlePlayerSetup(PlayerSetup message, Supplier<NetworkEvent.Context> context) {
        var ctx = context.get();
        ctx.enqueueWork(() -> {
            var sender = ctx.getSender();
            if (sender == null || !DevProbeAccess.allowed(sender.getGameProfile().getName(),Boolean.getBoolean("mia.cube.playerProbe"))) return;
            var level = sender.level();
            if (sender.getY() < level.getMinBuildHeight()) {
                LogUtils.getLogger().warn("Deep setup rejected: origin must be in the ordinary domain"); return;
            }
            PLAYER_HOMES.putIfAbsent(sender.getUUID(), new Home(sender.position(), sender.getYRot(), sender.getXRot(),
                    sender.gameMode.getGameModeForPlayer(), sender.getAbilities().flying, sender.getAbilities().getFlyingSpeed()));
            rememberHome(sender, PLAYER_HOMES.get(sender.getUUID()));
            sender.getServer().getPlayerList().saveAll(); // Persist recovery before creative positioning.
            var fixture = new Fixture(level.registryAccess().registryOrThrow(Registries.BIOME));
            for (int x = 1; x <= 9; x++) for (int z = 1; z <= 9; z++)
                setFixtureBlock(fixture, x, -20000, z, Blocks.STONE);
            setFixtureBlock(fixture, 5, -20000, 5, Blocks.DIAMOND_BLOCK);
            for (int y = -19999; y <= -19997; y++) setFixtureBlock(fixture, 8, y, 8, Blocks.GLOWSTONE);
            setFixtureBlock(fixture, 2, -19999, 8, Blocks.GOLD_BLOCK);
            var space = new SparseCubeSpace();
            for (var entry : fixture.sections.entrySet()) {
                int[] states = new int[CubeSnapshot.BLOCK_COUNT];
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                    states[(y << 8) | (z << 4) | x] = Block.getId(entry.getValue().getBlockState(x, y, z));
                space.load(new CubeSnapshot(entry.getKey(), states));
            }
            DeepCubeDomain.attach(level, space);
            var frames = new ArrayList<byte[]>();
            for (var entry : fixture.sections.entrySet()) {
                var buffer = new FriendlyByteBuf(Unpooled.buffer(4096, MAX_FRAME));
                try {
                    CubeSectionPacketCodec.encode(buffer, entry.getKey(), entry.getValue());
                    byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); frames.add(bytes);
                } finally { buffer.release(); }
            }
            // The mirror packet precedes the ordinary double-XYZ teleport packet on this same connection.
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender),
                    new Reply(message.id(), 0, sender.getServer().isDedicatedServer(), ProcessHandle.current().pid(), frames));
            sender.setGameMode(GameType.CREATIVE); // Diagnostic positioning is creative-only.
            sender.getAbilities().flying = false; sender.onUpdateAbilities();
            sender.setDeltaMovement(Vec3.ZERO);
            sender.connection.teleport(5.5, -19996, 5.5, 90, 24);
            LogUtils.getLogger().info("MIA_DEEP_PLAYER_SETUP player={} cubes={} globalY=-19996", sender.getGameProfile().getName(), space.loadedCubeCount());
        });
        ctx.setPacketHandled(true);
    }
    private static void setFixtureBlock(Fixture fixture, int x, int y, int z, Block block) {
        fixture.sections.get(CubeKey.fromBlock(new BlockCoordinate(x, y, z)))
                .setBlockState(Math.floorMod(x, 16), Math.floorMod(y, 16), Math.floorMod(z, 16), block.defaultBlockState());
    }

    private static void handlePlayerQuery(PlayerQuery message, Supplier<NetworkEvent.Context> context) {
        var ctx = context.get();
        ctx.enqueueWork(() -> {
            var sender = ctx.getSender();
            if (sender == null || !DevProbeAccess.allowed(sender.getGameProfile().getName(),Boolean.getBoolean("mia.cube.playerProbe"))) return;
            boolean returned = false;
            if (message.returnHome()) {
                Home home = PLAYER_HOMES.get(sender.getUUID());
                if (home != null) {
                    restoreHome(sender, home, true); PLAYER_HOMES.remove(sender.getUUID()); returned = true;
                }
            }
            var below = BlockPos.containing(sender.getX(), sender.getY() - .2, sender.getZ());
            String block = BuiltInRegistries.BLOCK.getKey(sender.level().getBlockState(below).getBlock()).toString();
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), new PlayerState(message.id(), sender.getX(), sender.getY(), sender.getZ(),
                    sender.onGround(), block, returned, ProcessHandle.current().pid(),
                    sender.gameMode.getGameModeForPlayer().getName(), sender.getAbilities().flying, sender.getAbilities().getFlyingSpeed()));
        });
        ctx.setPacketHandled(true);
    }

    private static void handleRequest(Request message, Supplier<NetworkEvent.Context> context) {
        var ctx = context.get();
        ctx.enqueueWork(() -> {
            var sender = ctx.getSender();
            if (sender == null || !Boolean.getBoolean("mia.cube.networkProbe")) return;
            if (!CHANNEL.isRemotePresent(ctx.getNetworkManager())) return;
            try {
                Fixture fixture = FIXTURES.get(sender.getUUID());
                if (message.phase() == 0) {
                    if (fixture == null && FIXTURES.size() >= 4) throw new IllegalStateException("Probe fixture owner limit");
                    fixture = new Fixture(sender.level().registryAccess().registryOrThrow(Registries.BIOME));
                    FIXTURES.put(sender.getUUID(), fixture);
                }
                if (fixture == null || message.phase() != fixture.phase + 1)
                    throw new IllegalArgumentException("Probe phases must be requested in order");
                fixture.advance(message.phase());
                var frames = new ArrayList<byte[]>();
                for (var entry : fixture.sections.entrySet()) {
                    var buffer = new FriendlyByteBuf(Unpooled.buffer(4096, MAX_FRAME));
                    try {
                        CubeSectionPacketCodec.encode(buffer, entry.getKey(), entry.getValue());
                        byte[] bytes = new byte[buffer.readableBytes()];
                        buffer.readBytes(bytes); frames.add(bytes);
                    } finally { buffer.release(); }
                }
                var reply = new Reply(message.id(), message.phase(), sender.getServer().isDedicatedServer(),
                        ProcessHandle.current().pid(), frames);
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), reply);
                LogUtils.getLogger().info("MIA_CUBE_REPLY request={} phase={} cubes={} thread={}",
                        message.id(), message.phase(), frames.size(), Thread.currentThread().getName());
            } catch (RuntimeException invalid) {
                LogUtils.getLogger().warn("Cube probe request rejected: {}", invalid.toString());
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!event.getEntity().level().isClientSide) {
            FIXTURES.remove(event.getEntity().getUUID());
            if (event.getEntity() instanceof ServerPlayer player) {
                if(dev.madeinabyss.forge.probe.deep.DeepCubeRuntime.nativeWorld(player.level())) {
                    dev.madeinabyss.forge.probe.deep.DeepCubeRuntime.release(player);
                    return;
                }
                Home home = PLAYER_HOMES.get(player.getUUID());
                if (home == null) home = recalledHome(player);
                if (home != null) {restoreHome(player, home, false);PLAYER_HOMES.remove(player.getUUID());} // Forge fires this before vanilla save(player).
            }
        }
    }
    private static void onServerStop(ServerStoppedEvent event) { FIXTURES.clear(); PLAYER_HOMES.clear(); }

    private record Home(Vec3 position, float yaw, float pitch, GameType gameType, boolean flying, float flightSpeed) {}
    private static void rememberHome(ServerPlayer player, Home home) {
        var tag = new CompoundTag();
        tag.putDouble("x", home.position().x); tag.putDouble("y", home.position().y); tag.putDouble("z", home.position().z);
        tag.putFloat("yaw", home.yaw()); tag.putFloat("pitch", home.pitch());
        tag.putString("mode", home.gameType().getName()); tag.putBoolean("flying", home.flying());
        tag.putFloat("flightSpeed", home.flightSpeed());
        player.getPersistentData().put(HOME_TAG, tag);
    }
    private static Home recalledHome(ServerPlayer player) {
        if (!player.getPersistentData().contains(HOME_TAG, 10)) return null;
        var tag = player.getPersistentData().getCompound(HOME_TAG);
        if (!tag.contains("x", 99) || !tag.contains("y", 99) || !tag.contains("z", 99)) return null;
        var position = new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                || Math.abs(position.x) > 30000000 || Math.abs(position.z) > 30000000
                || position.y < player.level().getMinBuildHeight() || position.y > 20000000) return null;
        return new Home(position, tag.getFloat("yaw"), tag.getFloat("pitch"),
                GameType.byName(tag.getString("mode"), GameType.SURVIVAL), tag.getBoolean("flying"),
                tag.contains("flightSpeed",99) && Float.isFinite(tag.getFloat("flightSpeed")) && tag.getFloat("flightSpeed") > 0
                        ? tag.getFloat("flightSpeed") : .05f);
    }
    private static void restoreHome(ServerPlayer player, Home home, boolean sendPosition) {
        dev.madeinabyss.forge.probe.deep.DeepCubeRuntime.release(player);
        player.setDeltaMovement(Vec3.ZERO); player.setGameMode(home.gameType());
        player.getAbilities().flying = home.flying();
        player.getAbilities().setFlyingSpeed(home.flightSpeed());
        if (sendPosition) {
            player.onUpdateAbilities();
            player.connection.teleport(home.position().x, home.position().y, home.position().z, home.yaw(), home.pitch());
        } else {
            player.setPos(home.position().x, home.position().y, home.position().z);
            player.setYRot(home.yaw()); player.setXRot(home.pitch());
        }
        if (!dev.madeinabyss.forge.probe.deep.DeepCubeRuntime.owns(player.level())) DeepCubeDomain.detach(player.level());
        player.getPersistentData().remove(HOME_TAG);
    }
    /** Generated admission failure rollback; keep the remembered home until release succeeds. */
    public static void rollbackCapturedHome(ServerPlayer player) {
        Home home = PLAYER_HOMES.get(player.getUUID());
        if (home != null) { restoreHome(player, home, true); PLAYER_HOMES.remove(player.getUUID()); }
    }
    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if(dev.madeinabyss.forge.probe.deep.DeepCubeRuntime.nativeWorld(player.level()))return;
            Home home = recalledHome(player);
            if (home != null) {
                restoreHome(player, home, true);
                LogUtils.getLogger().info("MIA_DEEP_HOME_RECOVERED player={} y={}", player.getGameProfile().getName(), home.position().y);
            }
        }
    }
    private record PlayerSetup(UUID id) {}
    private record PlayerQuery(UUID id, boolean returnHome) {}
    public record PlayerState(UUID id, double x, double y, double z, boolean onGround,
                              String floorBlock, boolean returned, long serverPid, String gameMode, boolean flying, float flightSpeed) {}

    private static void encodeReply(Reply reply, FriendlyByteBuf out) {
        out.writeUUID(reply.id()); out.writeInt(reply.phase());
        out.writeBoolean(reply.serverDedicated()); out.writeLong(reply.serverPid());
        out.writeVarInt(reply.frames.size());
        for (byte[] frame : reply.frames) { out.writeVarInt(frame.length); out.writeBytes(frame); }
    }

    private static Reply decodeReply(FriendlyByteBuf in) {
        if (in.readableBytes() > MAX_BATCH) throw new IllegalArgumentException("Cube batch exceeds 1 MiB");
        var id = in.readUUID(); int phase = in.readInt();
        boolean dedicated = in.readBoolean(); long pid = in.readLong();
        int count = in.readVarInt();
        if (count < 1 || count > MAX_CUBES) throw new IllegalArgumentException("Invalid cube batch count");
        var frames = new ArrayList<byte[]>(count);
        int total = 34;
        for (int index = 0; index < count; index++) {
            int length = in.readVarInt();
            total = Math.addExact(total, length + 5);
            if (length < 1 || length > MAX_FRAME || total > MAX_BATCH || length > in.readableBytes())
                throw new IllegalArgumentException("Invalid cube batch frame length");
            byte[] frame = new byte[length]; in.readBytes(frame); frames.add(frame);
        }
        if (in.isReadable()) throw new IllegalArgumentException("Trailing cube batch data");
        return new Reply(id, phase, dedicated, pid, frames);
    }

    private record Request(UUID id, int phase) {
        private Request {
            java.util.Objects.requireNonNull(id);
            if (phase < 0 || phase > 2) throw new IllegalArgumentException("Cube probe phase must be 0..2");
        }
    }

    public record Reply(UUID id, int phase, boolean serverDedicated, long serverPid, List<byte[]> frames) {
        public Reply {
            java.util.Objects.requireNonNull(id);
            if (phase < 0 || phase > 2 || frames.isEmpty() || frames.size() > MAX_CUBES)
                throw new IllegalArgumentException("Invalid cube reply");
            int total = 34;
            for (byte[] frame : frames) {
                if (frame.length < 1 || frame.length > MAX_FRAME) throw new IllegalArgumentException("Invalid cube frame size");
                total = Math.addExact(total, frame.length + 5);
            }
            if (total > MAX_BATCH) throw new IllegalArgumentException("Cube reply exceeds 1 MiB");
            frames = frames.stream().map(byte[]::clone).toList();
        }
        @Override public List<byte[]> frames() { return frames.stream().map(byte[]::clone).toList(); }
    }

    private static final class Fixture {
        private final Map<CubeKey, LevelChunkSection> sections = new HashMap<>();
        private int phase = -1;
        private Fixture(Registry<Biome> biomes) {
            // Logical observers exercise the ticket union; they are not four real connected players.
            var tickets = new CubeTickets(27, 108, 4);
            for (int index = 0; index < DEPTHS.length; index++)
                tickets.move("fixture-" + index, new BlockCoordinate(5, DEPTHS[index], 5), 1, 1);
            for (var key : tickets.snapshot().keySet()) sections.put(key, new LevelChunkSection(biomes));
            var blocks = new net.minecraft.world.level.block.Block[]{Blocks.STONE, Blocks.DIRT, Blocks.GLOWSTONE, Blocks.DIAMOND_BLOCK};
            for (int index = 0; index < DEPTHS.length; index++)
                sections.get(CubeKey.fromBlock(new BlockCoordinate(5, DEPTHS[index], 5)))
                        .setBlockState(5, Math.floorMod(DEPTHS[index], 16), 5, blocks[index].defaultBlockState());
        }
        private void advance(int next) {
            if (next > 0) sections.get(CubeKey.fromBlock(new BlockCoordinate(5, -20000, 5)))
                    .setBlockState(5, Math.floorMod(-20000, 16), 5,
                            (next == 1 ? Blocks.AIR : Blocks.GOLD_BLOCK).defaultBlockState());
            phase = next;
        }
    }
}
