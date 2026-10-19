package dev.madeinabyss.forge.probe.client.compat.lightgpu;

import dev.madeinabyss.forge.worldgen.*;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import java.util.*;
import net.minecraft.world.level.block.Block;

/** Canonical physical authority; excludes diagnostic Map iteration order and camera/runtime counters. */
public final class GpuSourceAuthority {
    private GpuSourceAuthority(){}
    public static String base(GeneratedSkySource source,String fingerprint){
        return base(source,fingerprint,LivingAbyssGeneration.algorithmSha256(),source.physicalSemanticsIdentity());
    }
    private static String base(GeneratedSkySource source,String fingerprint,String generation,String physical){
        source.requirePhysicalAuthority();var parts=new ArrayList<String>();Collections.addAll(parts,"gpu-source-base-v1",Objects.requireNonNull(fingerprint),generation,physical,Integer.toString(source.minY()),Integer.toString(source.maxY()));
        for(int id:source.volumeStateIds())Collections.addAll(parts,Integer.toString(id),GeneratedVoxelVolume.name(Block.stateById(id)),Integer.toString(source.opacity(id)),Integer.toString(source.emission(id)));
        return TerrainLodDiskCache.identity(parts.toArray(String[]::new));
    }
    public static String full(GeneratedSkySource source,String fingerprint){
        return full(source,fingerprint,LivingAbyssGeneration.algorithmSha256(),source.physicalSemanticsIdentity());
    }
    public static String full(GeneratedSkySource source,String fingerprint,String generation,String physical){
        var parts=new ArrayList<String>();Collections.addAll(parts,"gpu-source-edits-v1",base(source,fingerprint,generation,physical));
        source.cacheAuthority().editedCubeDigests().entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<dev.madeinabyss.kernel.cube.CubeKey,String> e)->e.getKey().x()).thenComparingInt(e->e.getKey().y()).thenComparingInt(e->e.getKey().z())).forEach(e->{parts.add(e.getKey().toString());parts.add(e.getValue());});
        source.requirePhysicalAuthority();return TerrainLodDiskCache.identity(parts.toArray(String[]::new));
    }
}
