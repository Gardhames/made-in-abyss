package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import dev.madeinabyss.forge.probe.deep.DeepRuntimeNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Full signed XYZ remains intact here, before Connection.send and writeBlockPos. */
@Mixin(ClientPacketListener.class)
public abstract class ClientMiningPacketMixin {
    @Inject(method="handleMovePlayer",at=@At("TAIL"))
    private void mia$appliedNativeTeleport(ClientboundPlayerPositionPacket packet,CallbackInfo ci){
        var client=Minecraft.getInstance();var player=client.player;
        if(player!=null)dev.madeinabyss.forge.probe.client.ClientGeneratedLight.teleportApplied(player.getX(),player.getY(),player.getZ(),packet.getId());
    }
    @Inject(method="send(Lnet/minecraft/network/protocol/Packet;)V",at=@At("HEAD"),cancellable=true)
    private void mia$signedMining(Packet<?> packet,CallbackInfo ci) {
        var level=Minecraft.getInstance().level;
        if(packet instanceof ServerboundPlayerActionPacket action && DeepRuntimeNetwork.miningAction(action.getAction())
                && Boolean.getBoolean("mia.cube.playerProbe") && level!=null && action.getPos().getY()<level.getMinBuildHeight()) {
            ci.cancel(); // Losing a session must never fall through into packed-Y native wire.
            ClientDeepRuntime.captureMining(action);
        }
    }
}
