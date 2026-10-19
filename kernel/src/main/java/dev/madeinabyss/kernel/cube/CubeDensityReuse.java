package dev.madeinabyss.kernel.cube;
import java.util.Map;
import java.util.Objects;

/** Whole immutable cube reuse only within the exact same authoritative geometry epoch. */
public final class CubeDensityReuse {
    private CubeDensityReuse() {}
    @FunctionalInterface public interface StateSource {int state(int x,int y,int z);}
    public record Capture(int[] states,boolean reused) {}
    public static Capture capture(CubeKey key,long epoch,long previousEpoch,Map<CubeKey,int[]> previous,StateSource source) {
        Objects.requireNonNull(key);Objects.requireNonNull(previous);Objects.requireNonNull(source);
        int[] old=epoch==previousEpoch?previous.get(key):null;
        if(old!=null){if(old.length!=4096)throw new IllegalArgumentException("Incomplete immutable density cube");return new Capture(old,true);}
        int x0=Math.multiplyExact(key.x(),16),y0=Math.multiplyExact(key.y(),16),z0=Math.multiplyExact(key.z(),16);
        Math.addExact(x0,15);Math.addExact(y0,15);Math.addExact(z0,15);
        var states=new int[4096];
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)states[(y<<8)|(z<<4)|x]=source.state(x0+x,y0+y,z0+z);
        return new Capture(states,false);
    }
}
