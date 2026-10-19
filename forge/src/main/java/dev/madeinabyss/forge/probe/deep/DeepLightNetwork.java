package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.CubeLightInput;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Scalar light inputs only: this optional channel never grants geometry or physics residency. */
public final class DeepLightNetwork {
    public static final int MAX_INPUT_CUBES = 108;
    public static final int MAX_PACKET_BYTES = 1048576;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MadeInAbyssProbe.MOD_ID, "deep_light"), () -> "1",
            NetworkRegistry.acceptMissingOr("1"), NetworkRegistry.acceptMissingOr("1"));
    private static Consumer<Snapshot> receiver = packet -> { };
    private DeepLightNetwork() { }

    public static void register() {
        CHANNEL.registerMessage(0, Snapshot.class, DeepLightNetwork::encode, DeepLightNetwork::decode,
                (packet, context) -> {
                    var c = context.get();
                    c.enqueueWork(() -> receiver.accept(packet));
                    c.setPacketHandled(true);
                }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void receiver(Consumer<Snapshot> handler) { receiver = Objects.requireNonNull(handler); }
    public static boolean remotePresent(Connection connection) { return CHANNEL.isRemotePresent(connection); }
    public static void send(ServerPlayer player, Snapshot snapshot) {
        if (!remotePresent(player.connection.connection)) throw new IllegalStateException("Observer scalar channel absent");
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), snapshot);
    }

    static void encode(Snapshot snapshot, FriendlyByteBuf buffer) {
        int start = buffer.writerIndex();
        buffer.writeUUID(snapshot.session());
        buffer.writeLong(snapshot.geometryRevision()); buffer.writeLong(snapshot.revision());
        buffer.writeBoolean(snapshot.available()); buffer.writeUtf(snapshot.error(), 512);
        buffer.writeVarInt(snapshot.inputs().size());
        for (var input : snapshot.inputs()) {
            buffer.writeInt(input.key().x()); buffer.writeInt(input.key().y()); buffer.writeInt(input.key().z());
            buffer.writeBytes(input.emission()); buffer.writeBytes(input.opacity());
        }
        if (buffer.writerIndex() - start > MAX_PACKET_BYTES) throw new IllegalArgumentException("Oversized scalar light packet");
    }

    static Snapshot decode(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MAX_PACKET_BYTES) throw new IllegalArgumentException("Oversized scalar light packet");
        var session = buffer.readUUID();
        long geometry = buffer.readLong(), revision = buffer.readLong();
        boolean available = buffer.readBoolean();
        String error = buffer.readUtf(512);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_INPUT_CUBES || (!available && count != 0)) {
            throw new IllegalArgumentException("Invalid scalar input count");
        }
        var inputs = new ArrayList<CubeLightInput>(count);
        for (int i = 0; i < count; i++) {
            var key = new CubeKey(buffer.readInt(), buffer.readInt(), buffer.readInt());
            var emission = new byte[4096]; var opacity = new byte[4096];
            buffer.readBytes(emission); buffer.readBytes(opacity);
            inputs.add(new CubeLightInput(key, emission, opacity));
        }
        if (buffer.isReadable()) throw new IllegalArgumentException("Trailing scalar light payload");
        return new Snapshot(session, geometry, revision, available, error, inputs);
    }

    public record Snapshot(UUID session, long geometryRevision, long revision, boolean available,
                           String error, List<CubeLightInput> inputs) {
        public Snapshot {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(error, "error");
            inputs = List.copyOf(inputs);
            if (geometryRevision < 0 || revision < 0 || error.length() > 512
                    || inputs.size() > MAX_INPUT_CUBES || (!available && !inputs.isEmpty())
                    || (available && !error.isEmpty())) throw new IllegalArgumentException("Invalid scalar light snapshot");
            var keys = new HashSet<CubeKey>();
            for (var input : inputs) {
                if (!keys.add(input.key())) throw new IllegalArgumentException("Duplicate scalar light key");
            }
        }
    }
}
