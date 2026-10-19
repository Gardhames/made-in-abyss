package dev.madeinabyss.forge.probe.deep;

import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Objects;

/** Availability decision for remote-player environmental ticks; never changes packet interpolation or Level reads. */
public final class RemotePlayerAvailability {
    private static long skippedEnvironmentTicks, remoteInterpolationTicks, knownEnvironmentTicks, skippedPushTicks;
    private RemotePlayerAvailability() {}

    public static boolean canTickEnvironment(Level level, AABB body, Vec3 interpolationStep, Vec3 velocity) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(body);
        Objects.requireNonNull(interpolationStep);
        Objects.requireNonNull(velocity);
        AABB swept = body.expandTowards(interpolationStep).expandTowards(velocity);
        boolean geometryKnown;
        if (DeepCubeDomain.lookup(level).isPresent()) geometryKnown=DeepCubeDomain.allowsMove(level, swept);
        else {
        // A local owner's return removes this client's mirror while distant server-tracked
        // players remain. Exact ordinary min is vanilla; below it has no cube environment.
        geometryKnown= !Boolean.getBoolean("mia.cube.playerProbe") || level.dimension() != Level.OVERWORLD
                || swept.minY >= level.getMinBuildHeight();
        }
        if(!geometryKnown)return false;
        if(!level.isRaining())return true;
        double x=(body.minX+body.maxX)*.5,z=(body.minZ+body.maxZ)*.5;
        // Entity.isInRain reads feet and head at the same current XZ. Also admit the
        // next interpolated column before optional remote environment/interpolation work.
        return GeneratedLightAccess.rainReady(level,BlockPos.containing(x,body.minY,z))
                && GeneratedLightAccess.rainReady(level,BlockPos.containing(x+interpolationStep.x,body.minY+interpolationStep.y,z+interpolationStep.z));
    }

    /** Client-thread cumulative diagnostics; not session ownership or proof of visible pixels. */
    public static long skippedEnvironmentTickCount() { return skippedEnvironmentTicks; }
    public static long remoteInterpolationTickCount() { return remoteInterpolationTicks; }
    public static long knownEnvironmentTickCount() { return knownEnvironmentTicks; }
    public static long skippedPushTickCount() { return skippedPushTicks; }
    public static void recordSkippedEnvironmentTick() { skippedEnvironmentTicks++; }
    public static void recordRemoteInterpolationTick() { remoteInterpolationTicks++; }
    public static void recordKnownEnvironmentTick() { knownEnvironmentTicks++; }
    public static void recordSkippedPushTick() { skippedPushTicks++; }
}
