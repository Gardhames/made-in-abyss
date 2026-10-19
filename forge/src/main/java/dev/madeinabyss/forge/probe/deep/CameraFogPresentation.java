package dev.madeinabyss.forge.probe.deep;

import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Objects;

/** Camera presentation history only; never an authoritative fluid or block query. */
public final class CameraFogPresentation {
    private Object identity;
    private FogType previous=FogType.NONE;
    public FogType pending(Object session) {
        if(identity!=session) {reset();identity=session;}
        return previous;
    }
    public void confirmed(Object session,FogType value) {
        pending(Objects.requireNonNull(session));previous=Objects.requireNonNull(value);
    }
    public void reset() {identity=null;previous=FogType.NONE;}
    /** The vanilla water/lava height consumer also reads the block directly above each sample. */
    public static boolean readable(Level level,List<BlockPos> points) {
        var attached=DeepCubeDomain.lookup(level).orElse(null);
        if(attached==null || points.isEmpty())return false;
        for(var point:points) {
            int y=point.getY();
            if(y<DeepCubeDomain.TECHNICAL_BOTTOM || y==Integer.MAX_VALUE)return false;
            if(y<attached.view().getMaxBuildHeight()) {
                if(!DeepCubeDomain.loadedRange(level,point.getX(),y,point.getZ(),point.getX(),y+1,point.getZ()))return false;
            }
            if(y+1>=attached.view().getMaxBuildHeight()
                    && !DeepCubeDomain.nativeColumnsLoaded(level,point.getX(),point.getZ(),point.getX(),point.getZ()))return false;
        }
        return true;
    }
}
