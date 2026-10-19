package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.worldgen.ComposedTerrainChunkGenerator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import java.util.*;

/** Server-owned native-world recipe handshake. No filesystem profile lookup on the client. */
public final class WorldSessionNetwork {
    public static final int MAX_MANIFEST_CHARS=262144;
    private static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new ResourceLocation(MadeInAbyssProbe.MOD_ID,"world_session"),()->"1",NetworkRegistry.acceptMissingOr("1"),NetworkRegistry.acceptMissingOr("1"));
    private static final Map<UUID,Offer> PENDING=new HashMap<>();
    public record Offer(UUID session,String dimension,String fingerprint,String manifest) {
        public Offer {
            Objects.requireNonNull(session);
            if(dimension==null||dimension.length()>256||ResourceLocation.tryParse(dimension)==null||fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||manifest==null||manifest.isEmpty()||manifest.length()>MAX_MANIFEST_CHARS)throw new IllegalArgumentException("Invalid bounded native world offer");
        }
    }
    private record Ready(UUID session,String fingerprint) { }
    private WorldSessionNetwork() { }
    public static void encode(Offer message,FriendlyByteBuf buffer){buffer.writeUUID(message.session());buffer.writeUtf(message.dimension(),256);buffer.writeUtf(message.fingerprint(),64);buffer.writeUtf(message.manifest(),MAX_MANIFEST_CHARS);}
    public static Offer decode(FriendlyByteBuf buffer){var message=new Offer(buffer.readUUID(),buffer.readUtf(256),buffer.readUtf(64),buffer.readUtf(MAX_MANIFEST_CHARS));if(buffer.isReadable())throw new IllegalArgumentException("Trailing world offer data");return message;}
    public static void register(){
        CHANNEL.registerMessage(0,Offer.class,WorldSessionNetwork::encode,WorldSessionNetwork::decode,(message,context)->{
            var owner=context.get();owner.enqueueWork(()->net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,()->()->dev.madeinabyss.forge.probe.client.ClientWorldSession.offer(message)));owner.setPacketHandled(true);
        },Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1,Ready.class,(message,buffer)->{buffer.writeUUID(message.session());buffer.writeUtf(message.fingerprint(),64);},buffer->new Ready(buffer.readUUID(),buffer.readUtf(64)),(message,context)->{
            var owner=context.get();owner.enqueueWork(()->{
                var player=owner.getSender();if(player==null)return;
                var offer=PENDING.get(player.getUUID());
                if(offer==null||!offer.session().equals(message.session())||!offer.fingerprint().equals(message.fingerprint())||!player.level().dimension().location().toString().equals(offer.dimension()))return;
                if(!(player.serverLevel().getChunkSource().getGenerator() instanceof ComposedTerrainChunkGenerator generator)||!generator.profile().fingerprint().equals(offer.fingerprint()))return;
                if(DeepCubeRuntime.startWorld(player,offer.session(),generator.profile()))PENDING.remove(player.getUUID());
            });owner.setPacketHandled(true);
        },Optional.of(NetworkDirection.PLAY_TO_SERVER));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event)->{if(event.getEntity() instanceof ServerPlayer player)offer(player);});
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event)->PENDING.remove(event.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event)->PENDING.clear());
    }
    private static void offer(ServerPlayer player){
        if(!(player.serverLevel().getChunkSource().getGenerator() instanceof ComposedTerrainChunkGenerator generator))return;
        if(!CHANNEL.isRemotePresent(player.connection.connection)||!DeepRuntimeNetwork.remotePresent(player.connection.connection)||!GeneratedSceneNetwork.remotePresent(player.connection.connection)) {
            player.connection.disconnect(net.minecraft.network.chat.Component.literal("This Abyss world requires the matching Made in Abyss cube runtime."));return;
        }
        var offer=new Offer(UUID.randomUUID(),player.level().dimension().location().toString(),generator.profile().fingerprint(),generator.manifest().toString());
        PENDING.put(player.getUUID(),offer);CHANNEL.send(PacketDistributor.PLAYER.with(()->player),offer);
    }
    public static void ready(Offer offer){CHANNEL.sendToServer(new Ready(offer.session(),offer.fingerprint()));}
}
