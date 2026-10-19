package dev.madeinabyss.forge.probe.deep;

import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import dev.madeinabyss.forge.cube.CubeBlockLightAccess;

/** Client installs its derived immutable light reader; dedicated servers never link client classes. */
public final class GeneratedLightAccess {
    private static BiPredicate<Level,BlockPos> owns=(level,pos)->false;
    private static BiFunction<Level,BlockPos,OptionalInt> reader=(level,pos)->OptionalInt.empty();
    private static BiFunction<Level,BlockPos,OptionalInt> direct=(level,pos)->OptionalInt.empty(),rain=(level,pos)->OptionalInt.empty();
    private GeneratedLightAccess(){}
    public static void install(BiPredicate<Level,BlockPos> predicate,BiFunction<Level,BlockPos,OptionalInt> query){
        owns=java.util.Objects.requireNonNull(predicate);reader=java.util.Objects.requireNonNull(query);
    }
    public static OptionalInt packed(Level level,BlockPos pos){
        if(!level.isClientSide || !owns.test(level,pos))return OptionalInt.empty();
        var light=reader.apply(level,pos);
        if(light.isEmpty())throw new CubeBlockLightAccess.UnavailableLightException(pos,"Generated SKY/BLOCK snapshot unavailable",null);
        return light;
    }
    public static void geometry(BiFunction<Level,BlockPos,OptionalInt> sky,BiFunction<Level,BlockPos,OptionalInt> height){direct=sky;rain=height;}
    public static OptionalInt directSky(Level level,BlockPos pos){return exposure(level,pos,direct,"direct SKY");}
    public static OptionalInt rainHeight(Level level,BlockPos pos){return exposure(level,pos,rain,"rain height");}
    /** Readiness at an optional tick boundary. Required weather reads below remain strict. */
    public static boolean rainReady(Level level,BlockPos pos){
        return !owns.test(level,pos) || rain.apply(level,pos).isPresent();
    }
    private static OptionalInt exposure(Level level,BlockPos pos,BiFunction<Level,BlockPos,OptionalInt> query,String kind){
        if(!level.isClientSide||!owns.test(level,pos))return OptionalInt.empty();
        var value=query.apply(level,pos);
        if(value.isEmpty())throw new CubeBlockLightAccess.UnavailableLightException(pos,"Generated "+kind+" snapshot unavailable",null);
        return value;
    }
}
