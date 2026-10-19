package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Cube blocks use their signed custom channel; ordinary vanilla block packets retain native cache ownership. */
@Mixin(PacketUtils.class)
public abstract class GeneratedNativeBlockPacketMixin {
    @Redirect(method="lambda$ensureRunningOnSameThread$0",at=@At(value="INVOKE",
            target="Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private static <T extends PacketListener> void mia$nativeBlockPacket(Packet<T> packet,T listener) {
        var client=Minecraft.getInstance();var level=client.level;
        boolean nativeBlocks=packet instanceof ClientboundLevelChunkWithLightPacket
                || packet instanceof ClientboundBlockUpdatePacket || packet instanceof ClientboundSectionBlocksUpdatePacket
                || packet instanceof ClientboundBlockEntityDataPacket || packet instanceof ClientboundBlockEventPacket;
        if(nativeBlocks && listener instanceof ClientPacketListener && listener==client.getConnection()
                && DeepCubeDomain.generated(level))
            DeepCubeDomain.readNativeHost(level,()->packet.handle(listener));
        else packet.handle(listener);
    }
}
