package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import dev.madeinabyss.kernel.volume.*;
import dev.madeinabyss.forge.probe.client.compat.LodBlockStateMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Shared canonical producer for RAW, exact occupancy and subsequent GPU meshing.
 * No client wiring/scheduling here: G1 builds/validates the immutable source volume. */
public final class GeneratedVoxelVolume {
    private final GeneratedSkySource source;
    private final VoxelPalette palette;
    private final int[] nativeToVolume;
    private final LongAdder sampledStates=new LongAdder(),uniformBricks=new LongAdder(),unknownVoxels=new LongAdder();
    private final LongAdder sampledOpacity=new LongAdder();
    public GeneratedVoxelVolume(GeneratedSkySource source) {
        this.source=Objects.requireNonNull(source);int[] ids=source.volumeStateIds();var entries=new ArrayList<VoxelPalette.Entry>();
        nativeToVolume=new int[Block.BLOCK_STATE_REGISTRY.size()];Arrays.fill(nativeToVolume,-1);
        for(int id:ids){var state=Block.stateById(id);entries.add(new VoxelPalette.Entry(name(state),source.opacity(id),source.emission(id),state.isAir(),!state.getFluidState().isEmpty(),state.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).isEmpty(),LodBlockStateMaterials.findMaterial(state)));}
        palette=new VoxelPalette(entries);for(int id:ids)nativeToVolume[id]=palette.index(name(Block.stateById(id)));
    }
    /** Namespace:block[property=value,...], sorted properties, no registry numeric ID. */
    public static String name(BlockState state) {
        var text=new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var values=new TreeMap<String,String>();for(var entry:state.getValues().entrySet())values.put(entry.getKey().getName(),propertyValue(entry.getKey(),entry.getValue()));
        if(!values.isEmpty()){text.append('[');boolean first=true;for(var entry:values.entrySet()){if(!first)text.append(',');first=false;text.append(entry.getKey()).append('=').append(entry.getValue());}text.append(']');}return text.toString();
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static String propertyValue(Property property,Comparable value){return property.getName(value);}
    public VoxelPalette palette(){return palette;}
    public int volumeId(int nativeId){if(nativeId<0||nativeId>=nativeToVolume.length||nativeToVolume[nativeId]<0)throw new IllegalArgumentException("Uncaptured native state "+nativeId);return nativeToVolume[nativeId];}
    public VoxelBrick capture(CubeKey key,Runnable checkpoint) {
        source.requirePhysicalAuthority();checkpoint.run();int uniform=source.uniformVolumeState(key);
        if(uniform>=0){uniformBricks.increment();return VoxelBrick.uniform(volumeId(uniform),source.opacity(uniform));}
        int sx=Math.multiplyExact(key.x(),16),sy=Math.multiplyExact(key.y(),16),sz=Math.multiplyExact(key.z(),16);int[] states=new int[4096];Arrays.fill(states,-1);
        // Each column's spatial availability is checked independently: partial edge cubes
        // do not discard their known exterior voxels or turn unavailable inland into AIR.
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            checkpoint.run();int lower=source.knownLowerY(sx+x,sz+z);
            for(int y=0;y<16;y++){int index=(y<<8)|(z<<4)|x;if(sy+y<lower){unknownVoxels.increment();continue;}
                states[index]=volumeId(source.stateIdForVolume(sx+x,sy+y,sz+z));sampledStates.increment();}
        }
        source.requirePhysicalAuthority();checkpoint.run();return palette.pack(states);
    }
    /** G1 reachability experiment: scalar channel only, state remains explicitly absent. */
    public VoxelBrick captureOpacity(CubeKey key,Runnable checkpoint) {
        source.requirePhysicalAuthority();checkpoint.run();int uniform=source.uniformVolumeState(key);
        if(uniform>=0){uniformBricks.increment();return VoxelBrick.uniform(-1,source.opacity(uniform));}
        int sx=Math.multiplyExact(key.x(),16),sy=Math.multiplyExact(key.y(),16),sz=Math.multiplyExact(key.z(),16);int[] opacity=new int[4096];Arrays.fill(opacity,-1);long samples=0,unknown=0;
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            checkpoint.run();int lower=source.knownLowerY(sx+x,sz+z);
            for(int y=0;y<16;y++){if(sy+y<lower){unknown++;continue;}opacity[(y<<8)|(z<<4)|x]=source.opacityForVolume(sx+x,sy+y,sz+z);samples++;}
        }
        sampledOpacity.add(samples);unknownVoxels.add(unknown);source.requirePhysicalAuthority();checkpoint.run();return VoxelBrick.opacityOnly(opacity);
    }
    public SparseVoxelVolume open(Path dhCache,String fingerprint,String generationSha,String fullSourceIdentity,long maxResidentBytes)throws IOException {
        String code=TerrainLodDiskCache.codeIdentity(GeneratedVoxelVolume.class,VoxelPalette.class,VoxelBrick.class,VoxelVolumeArchive.class,SparseVoxelVolume.class,
            GeneratedSkySource.class,Generated155DerivedProjection.class,Generated155BDerivedProjection.class);
        var authority=new VoxelVolumeArchive.Authority(fingerprint,generationSha,source.physicalSemanticsIdentity(),fullSourceIdentity,code);
        return new SparseVoxelVolume(palette,new VoxelVolumeArchive(dhCache,authority,palette),this::capture,source::requirePhysicalAuthority,maxResidentBytes);
    }
    public Map<String,Object> status(){return Map.of("sampledFinalStates",sampledStates.sum(),"sampledOpacities",sampledOpacity.sum(),"provenUniformBricks",uniformBricks.sum(),"unknownVoxels",unknownVoxels.sum(),"paletteStates",palette.size());}
}
