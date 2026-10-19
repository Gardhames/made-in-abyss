package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepFluidEngine;
import dev.madeinabyss.forge.probe.deep.DeepSurvivalPlacement;
import dev.madeinabyss.forge.probe.deep.DeepChestEngine;
import dev.madeinabyss.forge.probe.deep.DeepChestClientMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class LevelMixin implements LevelReader, LevelAccessor {
    @Unique private Level mia$self() { return (Level) (Object) this; }

    @Inject(method="tickBlockEntities",at=@At("HEAD"),cancellable=true)
    private void mia$retainHostBlockEntities(CallbackInfo callback) {
        if(DeepCubeDomain.generated(mia$self())) callback.cancel();
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void mia$readDeep(BlockPos pos, CallbackInfoReturnable<BlockState> result) {
        Level level = mia$self();
        if (DeepCubeDomain.isDeep(level, pos)) result.setReturnValue(DeepSurvivalPlacement.overlay(level,pos).or(() -> DeepFluidEngine.overlay(level,pos))
                .orElseGet(() -> DeepCubeDomain.view(level).orElseThrow().getBlockState(pos)));
    }
    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void mia$fluidDeep(BlockPos pos, CallbackInfoReturnable<FluidState> result) {
        Level level = mia$self();
        if (DeepCubeDomain.isDeep(level, pos)) result.setReturnValue(DeepSurvivalPlacement.overlay(level,pos).or(() -> DeepFluidEngine.overlay(level,pos))
                .map(BlockState::getFluidState).orElseGet(() -> DeepCubeDomain.view(level).orElseThrow().getFluidState(pos)));
    }
    @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("HEAD"),cancellable=true)
    private void mia$fluidWrite(BlockPos pos,BlockState state,int flags,int depth,CallbackInfoReturnable<Boolean> result) {
        Level level=mia$self();
        if(DeepSurvivalPlacement.active(level)) result.setReturnValue(DeepSurvivalPlacement.lookup(level).orElseThrow().write(level,pos,state,flags,depth));
        else if(DeepCubeDomain.isDeep(level,pos)) result.setReturnValue(DeepFluidEngine.lookup(level)
                .map(engine -> engine.write(level,pos,state,flags,depth)).orElse(false));
    }
    @Inject(method="markAndNotifyBlock",remap=false,at=@At("HEAD"),cancellable=true)
    private void mia$survivalNotify(BlockPos pos,net.minecraft.world.level.chunk.LevelChunk chunk,BlockState before,BlockState after,
                                    int flags,int depth,CallbackInfo ci) {
        var level=mia$self();
        if(DeepSurvivalPlacement.active(level)) {
            DeepSurvivalPlacement.lookup(level).orElseThrow().notifyNeighbors(level,pos,before,after,flags,depth);
            ci.cancel();
        }
    }
    // Actual targets are inherited LevelAccessor defaults. Concrete overrides also cover interface call sites.
    @Override public void scheduleTick(BlockPos pos,Fluid fluid,int delay) { scheduleTick(pos,fluid,delay,TickPriority.NORMAL); }
    @Override public void scheduleTick(BlockPos pos,Fluid fluid,int delay,TickPriority priority) {
        Level level=mia$self();
        if(DeepCubeDomain.isDeep(level,pos)) {
            DeepFluidEngine.lookup(level).orElseThrow(() -> new UnsupportedOperationException("deep fluid scheduler is not attached"))
                    .schedule(level,pos,fluid,delay,priority);
        } else level.getFluidTicks().schedule(new ScheduledTick<>(fluid,pos,
                level.getLevelData().getGameTime()+delay,priority,level.nextSubTickCount()));
    }
    @Override public void scheduleTick(BlockPos pos,Block block,int delay) { scheduleTick(pos,block,delay,TickPriority.NORMAL); }
    @Override public void scheduleTick(BlockPos pos,Block block,int delay,TickPriority priority) {
        Level level=mia$self();
        if(DeepCubeDomain.isDeep(level,pos)) {
            DeepChestEngine.lookup(level).orElseThrow(()->new UnsupportedOperationException("deep native block scheduled work is not supported"))
                    .schedule(level,pos,block,delay,priority);
        } else level.getBlockTicks().schedule(new ScheduledTick<>(block,pos,
                level.getLevelData().getGameTime()+delay,priority,level.nextSubTickCount()));
    }
    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void mia$blockEntityDeep(BlockPos pos, CallbackInfoReturnable<BlockEntity> result) {
        Level level = mia$self();
        if (DeepCubeDomain.isDeep(level, pos)) {
            if(level.isClientSide && DeepChestClientMirror.lookup(level)!=null)result.setReturnValue(DeepChestClientMirror.get(level,pos));
            else if(DeepChestEngine.lookup(level).isPresent())result.setReturnValue(DeepChestEngine.lookup(level).orElseThrow().get(level,pos));
            else result.setReturnValue(DeepCubeDomain.view(level).orElseThrow().getBlockEntity(pos));
        }
    }
    @Inject(method = "getChunkForCollisions", at = @At("RETURN"), cancellable = true)
    private void mia$collisionColumn(int x, int z, CallbackInfoReturnable<BlockGetter> result) {
        Level level = mia$self();
        if (DeepCubeDomain.lookup(level).isPresent()) result.setReturnValue(DeepCubeDomain.collisionColumn(level, result.getReturnValue()));
    }

    // These methods are inherited interface defaults in vanilla Level. Concrete methods retain actual Y.
    @Override public boolean canSeeSky(BlockPos position){
        var direct=dev.madeinabyss.forge.probe.deep.GeneratedLightAccess.directSky(mia$self(),position);
        return direct.isPresent()?direct.getAsInt()==15:getBrightness(LightLayer.SKY,position)==15;
    }
    @Inject(method="isRainingAt",at=@At("HEAD"),cancellable=true)
    private void mia$generatedRainExposure(BlockPos position,CallbackInfoReturnable<Boolean> callback){
        var level=mia$self();
        if(!level.isRaining()){callback.setReturnValue(false);return;}
        var height=dev.madeinabyss.forge.probe.deep.GeneratedLightAccess.rainHeight(level,position);
        if(height.isPresent()){
            var biome=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value();
            callback.setReturnValue(level.isRaining() && canSeeSky(position) && height.getAsInt()<=position.getY()
                    && biome.getPrecipitationAt(position)==net.minecraft.world.level.biome.Biome.Precipitation.RAIN);
        }
    }
    @Override public int getBrightness(LightLayer layer, BlockPos position) {
        Level level = mia$self();
        var generated=dev.madeinabyss.forge.probe.deep.GeneratedLightAccess.packed(level,position);
        if(generated.isPresent())return (generated.getAsInt() >>> (layer==LightLayer.SKY?20:4))&15;
        if (layer == LightLayer.BLOCK && DeepCubeDomain.blockLightEnabled(level) && DeepCubeDomain.isDeep(level, position)) {
            return DeepCubeDomain.lookup(level).orElseThrow().blockLight().value(level, position);
        }
        return level.getLightEngine().getLayerListener(layer).getLightValue(position);
    }
    @Override public int getRawBrightness(BlockPos position, int skyDarken) {
        Level level = mia$self();
        var generated=dev.madeinabyss.forge.probe.deep.GeneratedLightAccess.packed(level,position);
        if(generated.isPresent()){int p=generated.getAsInt();return Math.max((p>>>4)&15,((p>>>20)&15)-skyDarken);}
        if (DeepCubeDomain.blockLightEnabled(level) && DeepCubeDomain.isDeep(level, position)) {
            int block = DeepCubeDomain.lookup(level).orElseThrow().blockLight().value(level, position);
            // SKY remains the native engine's value; this does not certify deep skylight/canSeeSky.
            int sky = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(position);
            return Math.max(block, sky - skyDarken);
        }
        return level.getLightEngine().getRawBrightness(position, skyDarken);
    }
    public boolean hasChunkAt(BlockPos pos) {
        Level level = mia$self();
        if (DeepCubeDomain.belowVanilla(level, pos)) {
            return DeepCubeDomain.loadedRange(level, pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
        }
        return level.hasChunkAt(pos.getX(), pos.getZ());
    }
    public boolean hasChunksAt(BlockPos min, BlockPos max) {
        return mia$self().hasChunksAt(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
    }
    public boolean hasChunksAt(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Level level = mia$self();
        int managedTop = DeepCubeDomain.managedTop(level);
        if (DeepCubeDomain.lookup(level).isPresent() && minY < managedTop) {
            if (!DeepCubeDomain.loadedRange(level, minX, minY, minZ, maxX, maxY, maxZ)) return false;
            if (maxY < managedTop) return true;
        }
        // Exact original LevelReader default for nondeep requests.
        return maxY >= level.getMinBuildHeight() && minY < level.getMaxBuildHeight()
                && level.hasChunksAt(minX, minZ, maxX, maxZ);
    }
}
