package dev.madeinabyss.forge.probe.client;

import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;

/** Presentation datum only; does not declare AIR, skylight or new world height. */
public final class GeneratedPreviewAtmosphere {
    private GeneratedPreviewAtmosphere() { }

    public static int minimum(ClientLevel level, Camera camera) {
        int nativeMinimum = level.getMinBuildHeight();
        var client = Minecraft.getInstance();
        if (client.level != level || client.player == null || client.player.level() != level
                || DeepCubeDomain.lookup(level).isEmpty()) return nativeMinimum;

        if (!ClientDeepRuntime.generatedAccepted(level)) return nativeMinimum;

        var position = camera.getPosition();
        // Same strict residency halo as movement, including the generated side of the seam.
        // No unavailable cube becomes AIR and no sky-light value is supplied here.
        if (!DeepCubeDomain.allowsMove(level,
                new AABB(position.x, position.y, position.z, position.x, position.y, position.z))) return nativeMinimum;
        // Entity.checkBelowWorld already uses this actual supported cube-runtime bottom.
        // Redirect only the void fade's datum; water, weather and mob-effect fog stay native.
        return Math.min(nativeMinimum, DeepCubeDomain.TECHNICAL_BOTTOM);
    }
}
