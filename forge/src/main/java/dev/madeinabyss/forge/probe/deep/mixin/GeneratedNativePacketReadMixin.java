package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.util.function.Consumer;

/** Keep native cache hydration distinct from gameplay queries in the generated overlay. */
@Mixin(ClientChunkCache.class)
public abstract class GeneratedNativePacketReadMixin {
    @Redirect(method="replaceWithPacketData",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/chunk/LevelChunk;replaceWithPacketData(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)V"),require=2)
    private void mia$nativeChunkCallbacks(LevelChunk chunk,FriendlyByteBuf data,CompoundTag heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> entities) {
        if(DeepCubeDomain.generated(chunk.getLevel()))
            DeepCubeDomain.readNativeHost(chunk.getLevel(),()->chunk.replaceWithPacketData(data,heightmaps,entities));
        else chunk.replaceWithPacketData(data,heightmaps,entities);
    }
}
