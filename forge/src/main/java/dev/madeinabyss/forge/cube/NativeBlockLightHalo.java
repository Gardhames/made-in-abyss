package dev.madeinabyss.forge.cube;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.CubeLightInput;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import java.lang.ref.WeakReference;
import java.util.*;

/** One native section above the seam: cached blocks only, no tickets, join, Level retention or AIR fallback. */
public final class NativeBlockLightHalo {
    public static final int MAX_CUBES = 36;
    private record Entry(CubeKey key, WeakReference<LevelChunk> chunk,
                         WeakReference<LevelChunkSection> section, long revision, boolean ready) {}
    private final List<Entry> entries;
    private NativeBlockLightHalo(List<Entry> entries) { this.entries = List.copyOf(entries); }

    public static NativeBlockLightHalo capture(Level level, Collection<CubeKey> deepKeys) {
        return capture(level,deepKeys,level.getMinBuildHeight());
    }

    public static NativeBlockLightHalo capture(Level level, Collection<CubeKey> deepKeys,int cubeOwnedTop) {
        int nativeStart=Math.max(level.getMinBuildHeight(),cubeOwnedTop);
        if (nativeStart % 16 != 0) throw new UnsupportedOperationException("Unaligned native light seam");
        // A generated cube view owns the complete native Y range. Its UNKNOWN neighbors cannot
        // be supplied by retained host blocks, and its known cubes must enter the solver only once.
        if(nativeStart>=level.getMaxBuildHeight())return new NativeBlockLightHalo(List.of());
        int seam = Math.floorDiv(nativeStart, 16);
        var wanted = new HashSet<CubeKey>();
        for (var key : deepKeys) if (key.y() == seam - 1) wanted.add(new CubeKey(key.x(), seam, key.z()));
        if (wanted.size() > MAX_CUBES) throw new IllegalStateException("Native light halo budget exceeded: " + wanted.size());
        var keys = new ArrayList<>(wanted);
        keys.sort(Comparator.comparingInt(CubeKey::x).thenComparingInt(CubeKey::z));
        var entries = new ArrayList<Entry>(keys.size());
        for (var key : keys) {
            // FULL,false on the server can join a future. getChunkNow is the actual nonblocking owner-thread path.
            LevelChunk chunk = level.getChunkSource() instanceof ServerChunkCache server
                    ? server.getChunkNow(key.x(), key.z())
                    : (LevelChunk) level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false);
            LevelChunkSection section = chunk == null ? null : chunk.getSection(chunk.getSectionIndex(nativeStart));
            if (section != null && !(section instanceof NativeLightRevision))
                throw new IllegalStateException("Native section light revision hook is missing");
            var version = (NativeLightRevision) (Object) section;
            entries.add(new Entry(key, new WeakReference<>(chunk), new WeakReference<>(section),
                    section == null ? -1 : version.mia$lightContentRevision(), section != null && version.mia$lightInputReady()));
        }
        return new NativeBlockLightHalo(entries);
    }

    public boolean sameInputs(NativeBlockLightHalo other) {
        if (other == null || entries.size() != other.entries.size()) return false;
        for (int i = 0; i < entries.size(); i++) {
            var a = entries.get(i); var b = other.entries.get(i);
            if (!a.key.equals(b.key) || a.chunk.get() != b.chunk.get() || a.section.get() != b.section.get()
                    || a.revision != b.revision || a.ready != b.ready) return false;
        }
        return true;
    }

    public List<CubeLightInput> inputs(CubeBlockView deepView, int nativeMaxY) {
        var states = new HashMap<CubeKey, BlockState[]>();
        for (var entry : entries) {
            var section = entry.section.get();
            if (!entry.ready || section == null) continue;
            var copy = new BlockState[4096];
            for (int i = 0; i < copy.length; i++) copy[i] = section.getBlockState(i & 15, i >>> 8, (i >>> 4) & 15);
            states.put(entry.key, copy);
        }
        // Forge callbacks see only explicitly captured states. They cannot trigger native chunk loading.
        BlockGetter bounded = new BlockGetter() {
            @Override public BlockState getBlockState(BlockPos pos) {
                if (pos.getY() < deepView.getMinBuildHeight() + deepView.getHeight()) return deepView.getBlockState(pos);
                var key = new CubeKey(Math.floorDiv(pos.getX(),16), Math.floorDiv(pos.getY(),16), Math.floorDiv(pos.getZ(),16));
                var known = states.get(key);
                if (known == null) throw new CubeBlockLightAccess.UnavailableLightException(pos);
                return known[(pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15)];
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public BlockEntity getBlockEntity(BlockPos pos) {
                if (getBlockState(pos).hasBlockEntity()) throw new UnsupportedOperationException("Light halo block entity callback");
                return null;
            }
            @Override public int getMinBuildHeight() { return deepView.getMinBuildHeight(); }
            @Override public int getHeight() { return nativeMaxY - getMinBuildHeight(); }
        };
        var inputs = new ArrayList<CubeLightInput>(states.size());
        for (var entry : states.entrySet()) inputs.add(CubeBlockLightAccess.input(entry.getKey(), entry.getValue(), bounded));
        return List.copyOf(inputs);
    }
    public int knownCubes() { return (int) entries.stream().filter(e -> e.ready && e.section.get() != null).count(); }
}
