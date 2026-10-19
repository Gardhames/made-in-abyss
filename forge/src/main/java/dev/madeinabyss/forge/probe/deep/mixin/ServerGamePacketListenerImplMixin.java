package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepCubeRuntime;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/** Checks existing residency before native movement and its final-position collision reads. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;
    @Shadow private Vec3 awaitingPositionFromClient;
    @Unique private boolean mia$chestResync;
    @Shadow public abstract void teleport(double x, double y, double z, float yaw, float pitch);
    @Inject(method="send(Lnet/minecraft/network/protocol/Packet;)V",at=@At("HEAD"),cancellable=true)
    private void mia$noPackedDeepBlockUpdate(net.minecraft.network.protocol.Packet<?> packet,CallbackInfo ci) {
        if(packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket block
                && DeepCubeDomain.belowVanilla(player.level(),block.getPos()))ci.cancel();
    }
    @Redirect(method="handleContainerClick",at=@At(value="INVOKE",target="Lnet/minecraft/world/inventory/AbstractContainerMenu;clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V"))
    private void mia$chestInventoryTransaction(net.minecraft.world.inventory.AbstractContainerMenu menu,int slot,int button,net.minecraft.world.inventory.ClickType type,net.minecraft.world.entity.player.Player actor) {
        if(menu instanceof net.minecraft.world.inventory.ChestMenu chestMenu && chestMenu.getContainer() instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest
                && DeepCubeDomain.isDeep(player.level(),chest.getBlockPos())) {
            var engine=dev.madeinabyss.forge.probe.deep.DeepChestEngine.lookup(player.level());
            mia$chestResync=engine.isEmpty() || !engine.get().click(chestMenu,slot,button,type,player);
        }else menu.clicked(slot,button,type,actor);
    }
    @Inject(method="handleContainerClick",at=@At("RETURN"))
    private void mia$chestAuthoritativeResync(net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet,CallbackInfo ci) {
        if(mia$chestResync){mia$chestResync=false;player.containerMenu.broadcastFullState();}
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void mia$rejectUnknownMovement(ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        // Netty first enters this method off-thread. Vanilla schedules the same handler on the
        // server thread through PacketUtils; only that re-entry may inspect the cube store.
        if (!player.getServer().isSameThread() || awaitingPositionFromClient != null) return;
        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        // Leave malformed packets to vanilla's invalid-player-movement handling.
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(packet.getYRot(player.getYRot()))
                || !Float.isFinite(packet.getXRot(player.getXRot()))) return;
        if (!DeepCubeRuntime.active(player)) return;
        var swept = player.getBoundingBox().expandTowards(x - player.getX(), y - player.getY(), z - player.getZ());
        if (DeepCubeDomain.allowsMove(player.level(), swept)) return;
        DeepCubeRuntime.rejectedMove(player,x,y,z);
        player.setDeltaMovement(Vec3.ZERO);
        teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        callback.cancel();
    }

    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"))
    private void mia$countPositionPacket(double x, double y, double z, float yaw, float pitch,
                                         Set<RelativeMovement> relative, CallbackInfo callback) {
        // The five-argument overload delegates here, so each native correction is counted once.
        DeepCubeRuntime.positionPacket(player,x,y,z,awaitingPositionFromClient!=null);
    }
}
