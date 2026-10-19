package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.cube.CubeBlockView;
import dev.madeinabyss.forge.cube.CubeBlockLightAccess;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.AABB;
import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.Optional;

/** Explicit development-only real-Level routing. Does not extend vanilla height or block-write semantics. */
public final class DeepCubeDomain {
    public static final int TECHNICAL_BOTTOM = -24000;
    /** Cube ownership includes sky above vanilla's column envelope. This is a
     * technical coordinate limit, not invented terrain or a different dimension. */
    public static final int GENERATED_TOP = 24000;
    private static final int MAX_QUERY_CUBES = 4096;
    // Attachment values contain no Level reference; ordinary Level unload can release weak keys.
    private static final Map<Level, Attachment> ATTACHMENTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Map<Level,Integer>> NATIVE_HOST_OPERATIONS =
            ThreadLocal.withInitial(java.util.IdentityHashMap::new);
    // lookup() runs per animate tick, collision probe and render query: the JVM-launch flag is read once,
    // the per-thread host-scope map is consulted only while some scope is open, and the last answer is
    // memoised against an attachment version (attach/detach invalidate it; a racing read stores a stale version).
    private static final boolean PLAYER_PROBE=Boolean.getBoolean("mia.cube.playerProbe");
    private static final java.util.concurrent.atomic.AtomicInteger OPEN_HOST_SCOPES=new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong VERSION=new java.util.concurrent.atomic.AtomicLong();
    private record LastLookup(long version,java.lang.ref.WeakReference<Level> level,Optional<Attachment> attachment){}
    private static volatile LastLookup lastLookup;
    private DeepCubeDomain() {}
    public record Attachment(SparseCubeSpace space, CubeBlockView view, CubeBlockLightAccess blockLight,
                             boolean blockLightEnabled, boolean generated) {}
    public static void attach(Level level, SparseCubeSpace space) {
        attach(level, space, true);
    }
    public static void attachGenerated(Level level, SparseCubeSpace space, boolean blockLightEnabled) {
        attach(level, space, blockLightEnabled, true);
    }
    public static void attach(Level level, SparseCubeSpace space, boolean blockLightEnabled) {
        attach(level, space, blockLightEnabled, false);
    }
    private static void attach(Level level, SparseCubeSpace space, boolean blockLightEnabled, boolean generated) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(space);
        if (!PLAYER_PROBE) throw new IllegalStateException("Deep player development probe is disabled");
        int height = (generated ? GENERATED_TOP : level.getMinBuildHeight()) - TECHNICAL_BOTTOM;
        var view = new CubeBlockView(space, TECHNICAL_BOTTOM, height);
        ATTACHMENTS.put(level, new Attachment(space, view, new CubeBlockLightAccess(space, view), blockLightEnabled, generated));
        VERSION.incrementAndGet();
    }
    public static boolean generated(Level level) {
        if(level==null || !PLAYER_PROBE)return false;
        var attachment=ATTACHMENTS.get(level);
        // Native backing scopes change read/write ownership, not the host simulation's pause state.
        return attachment!=null && attachment.generated();
    }
    public static int managedTop(Level level) {
        return lookup(level).map(a -> a.view().getMaxBuildHeight()).orElse(level.getMinBuildHeight());
    }
    public static boolean blockLightEnabled(Level level) { return lookup(level).map(Attachment::blockLightEnabled).orElse(false); }
    public static void detach(Level level) { ATTACHMENTS.remove(level); VERSION.incrementAndGet(); }
    public static Optional<Attachment> lookup(Level level) {
        if (level == null || !PLAYER_PROBE || OPEN_HOST_SCOPES.get()>0 && NATIVE_HOST_OPERATIONS.get().containsKey(level)) return Optional.empty();
        long version=VERSION.get();var last=lastLookup;
        if(last!=null&&last.version==version&&last.level.get()==level)return last.attachment;
        var result=Optional.ofNullable(ATTACHMENTS.get(level));
        lastLookup=new LastLookup(version,new java.lang.ref.WeakReference<>(level),result);
        return result;
    }
    /** A native host lifecycle operation retains its actual Level backing and restores cube ownership even on failure. */
    public static void readNativeHost(Level level,Runnable operation) {
        Objects.requireNonNull(level);Objects.requireNonNull(operation);
        var scopes=NATIVE_HOST_OPERATIONS.get();
        int previous=scopes.getOrDefault(level,0);
        scopes.put(level,Math.incrementExact(previous));OPEN_HOST_SCOPES.incrementAndGet();
        try { operation.run(); }
        finally { OPEN_HOST_SCOPES.decrementAndGet(); if (previous == 0) scopes.remove(level); else scopes.put(level,previous); }
    }
    public static Optional<CubeBlockView> view(Level level) { return lookup(level).map(Attachment::view); }
    public static Optional<SparseCubeSpace> space(Level level) { return lookup(level).map(Attachment::space); }

    public static boolean isDeep(Level level, BlockPos pos) { return isDeep(level, pos.getY()); }
    public static boolean isDeep(Level level, double y) {
        var attachment = lookup(level);
        return attachment.isPresent() && y >= TECHNICAL_BOTTOM && y < attachment.get().view().getMaxBuildHeight();
    }
    public static boolean belowVanilla(Level level, BlockPos pos) {
        return lookup(level).isPresent() && pos.getY() < managedTop(level);
    }

    /** True only when every requested deep cube is resident. Caller retains single-owner-thread residency. */
    public static boolean loadedRange(Level level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        var attached = lookup(level);
        if (attached.isEmpty() || minX > maxX || minY > maxY || minZ > maxZ || minY < TECHNICAL_BOTTOM) return false;
        int top = Math.min(maxY, attached.get().view().getMaxBuildHeight() - 1);
        if (minY > top) return true;
        return knownRange(attached.get().space(), minX, minY, minZ, maxX, top, maxZ);
    }
    /** Admission/camera handoff must inspect the actual cube halo, even above the old native min. */
    public static boolean knownGeneratedHalo(Level level, SparseCubeSpace space, AABB bounds) {
        return knownGeneratedHalo(level, space, bounds, 8);
    }
    public static boolean knownGeneratedHalo(Level level, SparseCubeSpace space, AABB bounds, double margin) {
        if (!Double.isFinite(margin) || margin < 0 || margin > 8) throw new IllegalArgumentException("Known halo margin 0..8");
        AABB query = bounds.inflate(margin);
        if (!finiteBlockBound(query.minX) || !finiteBlockBound(query.maxX)
                || !finiteBlockBound(query.minY) || !finiteBlockBound(query.maxY)
                || !finiteBlockBound(query.minZ) || !finiteBlockBound(query.maxZ)
                || query.minY < TECHNICAL_BOTTOM || query.maxY >= GENERATED_TOP) return false;
        return knownRange(space, floor(query.minX), floor(query.minY), floor(query.minZ),
                floor(query.maxX), floor(query.maxY), floor(query.maxZ));
    }
    private static boolean knownRange(SparseCubeSpace space, int minX, int minY, int minZ,
                                      int maxX, int maxY, int maxZ) {
        int x0 = Math.floorDiv(minX, 16), x1 = Math.floorDiv(maxX, 16);
        int y0 = Math.floorDiv(minY, 16), y1 = Math.floorDiv(maxY, 16);
        int z0 = Math.floorDiv(minZ, 16), z1 = Math.floorDiv(maxZ, 16);
        long nx = (long) x1 - x0 + 1, ny = (long) y1 - y0 + 1, nz = (long) z1 - z0 + 1;
        if (nx > MAX_QUERY_CUBES || ny > MAX_QUERY_CUBES || nz > MAX_QUERY_CUBES || nx * ny * nz > MAX_QUERY_CUBES) return false;
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            if (!space.isLoaded(new CubeKey(x, y, z))) return false;
        }
        return true;
    }

    /** Native movement including step-up and neighbor reads is allowed only with a conservative known halo. */
    public static boolean allowsMove(Level level, AABB sweptBounds) {
        if (lookup(level).isEmpty()) return true;
        AABB query = sweptBounds.inflate(8);
        if (!finiteBlockBound(query.minX) || !finiteBlockBound(query.maxX)
                || !finiteBlockBound(query.minY) || !finiteBlockBound(query.maxY)
                || !finiteBlockBound(query.minZ) || !finiteBlockBound(query.maxZ)
                || sweptBounds.minY < TECHNICAL_BOTTOM) return false;
        // Check the ordinary portion against actual cache entries, never ClientLevel.hasChunk (always true).
        int top = managedTop(level);
        if (query.maxY >= top && query.minY < level.getMaxBuildHeight()
                && !nativeColumnsLoaded(level,floor(query.minX),floor(query.minZ),floor(query.maxX),floor(query.maxZ))) return false;
        int deepMin=Math.max(TECHNICAL_BOTTOM,floor(query.minY));
        int deepMax=Math.min(top-1,floor(query.maxY));
        return deepMin>deepMax || loadedRange(level,floor(query.minX),deepMin,floor(query.minZ),floor(query.maxX),deepMax,floor(query.maxZ));
    }
    public static boolean nativeColumnsLoaded(Level level,int minX,int minZ,int maxX,int maxZ) {
        if(minX>maxX || minZ>maxZ) return false;
        int x0=Math.floorDiv(minX,16),x1=Math.floorDiv(maxX,16),z0=Math.floorDiv(minZ,16),z1=Math.floorDiv(maxZ,16);
        long nx=(long)x1-x0+1,nz=(long)z1-z0+1;
        if(nx>MAX_QUERY_CUBES || nz>MAX_QUERY_CUBES || nx*nz>MAX_QUERY_CUBES) return false;
        for(int x=x0;x<=x1;x++) for(int z=z0;z<=z1;z++) if(level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false)==null) return false;
        return true;
    }
    private static boolean finiteBlockBound(double v) {
        return Double.isFinite(v) && v >= Integer.MIN_VALUE && v < (double) Integer.MAX_VALUE + 1;
    }
    private static int floor(double v) { return (int) Math.floor(v); }

    /** Native collision lookup is X/Z-only; route every actual Y through the appropriate backing view. */
    public static BlockGetter collisionColumn(Level level, BlockGetter legacy) {
        var attached = lookup(level);
        if (attached.isEmpty()) return legacy;
        CubeBlockView deep = attached.get().view();
        int managedTop = deep.getMaxBuildHeight(), maxVanilla = level.getMaxBuildHeight();
        return new BlockGetter() {
            private boolean deep(BlockPos pos) { return pos.getY() >= TECHNICAL_BOTTOM && pos.getY() < managedTop; }
            @Override public BlockState getBlockState(BlockPos pos) {
                if (deep(pos)) return deep.getBlockState(pos);
                return legacy == null ? Blocks.VOID_AIR.defaultBlockState() : legacy.getBlockState(pos);
            }
            @Override public FluidState getFluidState(BlockPos pos) {
                if (deep(pos)) return deep.getFluidState(pos);
                return legacy == null ? Fluids.EMPTY.defaultFluidState() : legacy.getFluidState(pos);
            }
            @Override public BlockEntity getBlockEntity(BlockPos pos) {
                if (deep(pos)) return level.getBlockEntity(pos);
                return legacy == null ? null : legacy.getBlockEntity(pos);
            }
            @Override public int getMinBuildHeight() { return TECHNICAL_BOTTOM; }
            @Override public int getHeight() { return maxVanilla - TECHNICAL_BOTTOM; }
        };
    }
}
