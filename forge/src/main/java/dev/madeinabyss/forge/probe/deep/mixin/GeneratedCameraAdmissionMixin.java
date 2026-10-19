package dev.madeinabyss.forge.probe.deep.mixin;

import dev.madeinabyss.forge.probe.client.ClientDeepRuntime;
import dev.madeinabyss.forge.probe.deep.CameraFogPresentation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FogType;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class GeneratedCameraAdmissionMixin {
    @Shadow private boolean initialized;
    @Shadow private BlockGetter level;
    @Unique private final CameraFogPresentation mia$fogHistory=new CameraFogPresentation();
    @Unique private Object mia$fogSession;
    @Unique private boolean mia$confirmFog;
    @Inject(method="setup",at=@At("TAIL"))
    private void mia$admitKnownGeneratedCamera(BlockGetter level,Entity entity,boolean detached,
                                               boolean mirrored,float partialTick,CallbackInfo callback) {
        ClientDeepRuntime.prepareGeneratedCamera(Minecraft.getInstance(),(Camera)(Object)this);
    }
    @Inject(method="getFluidInCamera",at=@At("HEAD"),cancellable=true)
    private void mia$holdCameraFogDuringResidencyHandoff(CallbackInfoReturnable<FogType> callback) {
        mia$confirmFog=false;
        var client=Minecraft.getInstance();
        mia$fogSession=ClientDeepRuntime.generatedSessionIdentity(client.level);
        if(mia$fogSession==null || !initialized || level!=client.level) {
            mia$fogHistory.reset();return;
        }
        var held=mia$fogHistory.pending(mia$fogSession);
        var camera=(Camera)(Object)this;var position=camera.getPosition();
        var near=camera.getNearPlane();var points=new ArrayList<BlockPos>(6);
        points.add(camera.getBlockPosition().immutable());
        points.add(BlockPos.containing(position.add(near.getPointOnPlane(0,0))));
        points.add(BlockPos.containing(position.add(near.getTopLeft())));
        points.add(BlockPos.containing(position.add(near.getTopRight())));
        points.add(BlockPos.containing(position.add(near.getBottomLeft())));
        points.add(BlockPos.containing(position.add(near.getBottomRight())));
        if(!CameraFogPresentation.readable(client.level,points))callback.setReturnValue(held);
        else mia$confirmFog=true;
    }
    @Inject(method="getFluidInCamera",at=@At("RETURN"))
    private void mia$rememberConfirmedCameraFog(CallbackInfoReturnable<FogType> callback) {
        if(mia$confirmFog && mia$fogSession!=null)mia$fogHistory.confirmed(mia$fogSession,callback.getReturnValue());
    }
    @Inject(method="reset",at=@At("HEAD"))
    private void mia$clearCameraFogHistory(CallbackInfo callback) {
        mia$fogHistory.reset();mia$fogSession=null;mia$confirmFog=false;
    }
}
