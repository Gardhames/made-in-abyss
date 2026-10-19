package dev.madeinabyss.forge.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.light.CubeBlockLight;
import dev.madeinabyss.kernel.light.CubeLightInput;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.*;

/** Owner-thread derived BLOCK light. Level is only a temporary argument; attachments retain no Level. */
public final class CubeBlockLightAccess {
    public static final int MAX_CUBES = 108;
    public static final int MAX_LIGHT_INPUTS = MAX_CUBES + NativeBlockLightHalo.MAX_CUBES;
    public static final int MAX_VISUAL_CUBES = 243;
    public static final int MAX_VISUAL_NATIVE_CUBES = 9;
    public static final int MAX_VISUAL_LIGHT_INPUTS = MAX_VISUAL_CUBES + MAX_VISUAL_NATIVE_CUBES;
    private final SparseCubeSpace space;
    private final CubeBlockView view;
    private final boolean visual;
    private final int maxCubes, maxInputs;
    private final java.util.function.BiFunction<Level, Collection<CubeKey>, NativeBlockLightHalo> nativeCapture;
    private List<CubeLightInput> dependencyInputs;
    private boolean dependenciesAvailable = true;
    private long dependencyGeneration, revision, recomputations, computedGeneration = -1;
    private long seenGeneration = -1, seenDependency = -1;
    private NativeBlockLightHalo seenNative;
    private CubeBlockLight.Result published;
    private RuntimeException failure;
    private boolean captureFailed;

    public CubeBlockLightAccess(SparseCubeSpace space, CubeBlockView view) {
        this(space, view, (level,keys)->NativeBlockLightHalo.capture(level,keys,view.getMaxBuildHeight()));
    }

    /** Separate visual snapshots never attach to Level block queries or physics. */
    public static CubeBlockLightAccess visual(SparseCubeSpace space, CubeBlockView view) {
        return new CubeBlockLightAccess(space, view, (level,keys)->NativeBlockLightHalo.capture(level,keys,view.getMaxBuildHeight()), true);
    }

    /** Package-local fault injection for the real adapter; production always uses cached native capture. */
    CubeBlockLightAccess(SparseCubeSpace space, CubeBlockView view,
            java.util.function.BiFunction<Level, Collection<CubeKey>, NativeBlockLightHalo> nativeCapture) {
        this(space, view, nativeCapture, false);
    }

    private CubeBlockLightAccess(SparseCubeSpace space, CubeBlockView view,
            java.util.function.BiFunction<Level, Collection<CubeKey>, NativeBlockLightHalo> nativeCapture, boolean visual) {
        this.space = Objects.requireNonNull(space); this.view = Objects.requireNonNull(view);
        this.nativeCapture = Objects.requireNonNull(nativeCapture);
        this.visual = visual;
        maxCubes = visual ? MAX_VISUAL_CUBES : MAX_CUBES;
        maxInputs = visual ? MAX_VISUAL_LIGHT_INPUTS : MAX_LIGHT_INPUTS;
    }

    /** Separate scalar inputs never grant block reads, collisions or physical residency. */
    public void dependencies(Collection<CubeLightInput> inputs, boolean available) {
        if (visual) throw new UnsupportedOperationException("Visual inputs come only from its fixed snapshot window");
        Objects.requireNonNull(inputs);
        List<CubeLightInput> next = dependencyInputs;
        if (available) {
            if (inputs.size() > MAX_CUBES) throw new IllegalArgumentException("Light dependency budget exceeded");
            var keys = new HashSet<CubeKey>();
            for (var input : inputs) {
                if (!deepKey(input.key()) || !keys.add(input.key()))
                    throw new IllegalArgumentException("Invalid/duplicate deep light dependency: " + input.key());
            }
            for (var key : space.residentKeys()) if (deepKey(key) && !keys.contains(key))
                throw new IllegalArgumentException("Light dependencies omit physical deep cube: " + key);
            next = List.copyOf(inputs);
        }
        long changed = Math.incrementExact(dependencyGeneration);
        dependencyInputs = next; dependenciesAvailable = available; dependencyGeneration = changed;
    }

    public int value(Level level, BlockPos position) {
        if (!visual && !space.isLoaded(CubeKey.fromBlock(new BlockCoordinate(position.getX(), position.getY(), position.getZ()))))
            throw new UnavailableLightException(position);
        refresh(level);
        return published.value(new BlockCoordinate(position.getX(), position.getY(), position.getZ()))
                .orElseThrow(() -> new UnavailableLightException(position));
    }

    /** Renderer observes input changes even when a new solve is unavailable. */
    public long revision(Level level) {
        try { refresh(level); } catch (RuntimeException unavailable) { /* Queries expose the actual error. */ }
        return revision;
    }

    /** Immutable owner-thread mesh batch; reacquire after geometry/light revision changes. */
    public CubeBlockLight.Result visualSnapshot(Level level) {
        if (!visual) throw new UnsupportedOperationException("Physical queries require their own residency gate");
        refresh(level);
        return published;
    }

    /** Known resident deep inputs for authoritative observer synchronization; no store/generation/native reads. */
    public List<CubeLightInput> deepInputs() {
        var keys = space.residentKeys();
        if (keys.size() > maxCubes) throw new IllegalStateException("Block-light snapshot budget exceeded: " + keys.size());
        var result = new ArrayList<CubeLightInput>(keys.size());
        long generation = space.contentGeneration();
        for (var key : keys) if (deepKey(key)) {
            int[] ids = space.snapshot(key).orElseThrow().states();
            var states = new BlockState[4096];
            for (int i = 0; i < states.length; i++) {
                states[i] = Block.BLOCK_STATE_REGISTRY.byId(ids[i]);
                if (states[i] == null) throw new UnsupportedOperationException("Unknown block-state registry ID: " + ids[i]);
            }
            result.add(input(key, states, view));
        }
        if (generation != space.contentGeneration()) throw new IllegalStateException("Cube content changed during light input capture");
        return List.copyOf(result);
    }

    private boolean deepKey(CubeKey key) {
        long firstY = (long) key.y() * 16;
        return firstY >= view.getMinBuildHeight() && firstY + 15 < (long) view.getMinBuildHeight() + view.getHeight();
    }

    public static CubeLightInput input(CubeKey key, BlockState[] states, BlockGetter boundedView) {
        if (states.length != 4096) throw new IllegalArgumentException("Expected full native light input");
        var emission = new byte[4096]; var opacity = new byte[4096];
        int ox = Math.multiplyExact(key.x(),16), oy = Math.multiplyExact(key.y(),16), oz = Math.multiplyExact(key.z(),16);
        var pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < states.length; i++) {
            var state = Objects.requireNonNull(states[i], "Unknown native block state");
            pos.set(ox + (i & 15), oy + (i >>> 8), oz + ((i >>> 4) & 15));
            if (state.is(Blocks.CHEST) && state.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE
                    && !state.getValue(BlockStateProperties.WATERLOGGED)) {
                var unsupported = new UnsupportedOperationException("Single chest BLOCK-light entity/face callbacks remain unsupported: " + state);
                throw new UnavailableLightException(pos, "unsupported native single chest BLOCK-light callbacks", unsupported);
            }
            if (state.hasBlockEntity() || (state.canOcclude() && state.useShapeForLightOcclusion()))
                throw new UnsupportedOperationException("Block-light state requires unsupported entity/face callbacks: " + state);
            int emitted = state.getLightEmission(boundedView,pos), blocked = state.getLightBlock(boundedView,pos);
            if (emitted < 0 || emitted > 15 || blocked < 0 || blocked > 15)
                throw new UnsupportedOperationException("Block-light callback outside native 0..15 range: " + state);
            emission[i] = (byte) emitted; opacity[i] = (byte) blocked;
        }
        return new CubeLightInput(key, emission, opacity);
    }

    private void refresh(Level level) {
        Objects.requireNonNull(level);
        long generation = space.contentGeneration(), dependencies = dependencyGeneration;
        Set<CubeKey> keys = new HashSet<>();
        for (var key : space.residentKeys()) if (deepKey(key)) keys.add(key);
        var activeInputs = dependenciesAvailable ? dependencyInputs : null;
        if (activeInputs != null) {
            keys.clear(); for (var input : activeInputs) keys.add(input.key());
        }
        var nativeHalo = captureNative(level, keys, generation, dependencies);
        boolean same = !captureFailed && seenGeneration == generation && seenDependency == dependencies
                && (nativeHalo == null ? seenNative == null : nativeHalo.sameInputs(seenNative));
        if (same && failure != null) throw failure;
        if (same && published != null) return;
        revision = Math.incrementExact(revision);
        seenGeneration = generation; seenDependency = dependencies; seenNative = nativeHalo; captureFailed = false;
        try {
            if (space.loadedCubeCount() > maxCubes) throw new IllegalStateException("Block-light snapshot budget exceeded");
            // A missing dependency packet discards its old active inputs. Only locally proven bounds
            // may remain numeric; a possible external source keeps fringe cells explicitly unknown.
            var inputs = new ArrayList<>(activeInputs == null ? deepInputs() : activeInputs);
            inputs.addAll(nativeHalo.inputs(view, level.getMaxBuildHeight()));
            var next = CubeBlockLight.solve(inputs, maxInputs);
            if (space.contentGeneration() != generation || dependencyGeneration != dependencies
                    || !nativeHalo.sameInputs(captureNative(level, keys, generation, dependencies)))
                throw new IllegalStateException("Light input changed during calculation");
            published = next; computedGeneration = generation; recomputations++; failure = null;
        } catch (RuntimeException error) {
            published = null; failure = error; throw error;
        }
    }

    private NativeBlockLightHalo captureNative(Level level, Collection<CubeKey> keys,
                                               long generation, long dependencies) {
        try {
            if (visual) validateVisualWindow();
            var captured = nativeCapture.apply(level, keys);
            if (visual && captured.knownCubes() > MAX_VISUAL_NATIVE_CUBES)
                throw new IllegalStateException("Visual native light halo budget exceeded");
            return captured;
        }
        catch (RuntimeException error) {
            // A capture error precedes the solver, but still invalidates already lit renderer output.
            // The same failed input must not cause another mesh rebuild on every render frame.
            if (!captureFailed || seenGeneration != generation || seenDependency != dependencies
                    || failure == null || failure.getClass() != error.getClass()
                    || !Objects.equals(failure.getMessage(), error.getMessage()))
                revision = Math.incrementExact(revision);
            seenGeneration = generation; seenDependency = dependencies; seenNative = null;
            published = null; failure = error; captureFailed = true;
            throw error;
        }
    }

    private void validateVisualWindow() {
        var keys = space.residentKeys();
        if (keys.size() > MAX_VISUAL_CUBES) throw new IllegalStateException("Visual light snapshot budget exceeded");
        if (keys.isEmpty()) return;
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
        int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for (var key : keys) {
            minX=Math.min(minX,key.x()); maxX=Math.max(maxX,key.x());
            minY=Math.min(minY,key.y()); maxY=Math.max(maxY,key.y());
            minZ=Math.min(minZ,key.z()); maxZ=Math.max(maxZ,key.z());
        }
        if ((long)maxX-minX>2 || (long)maxZ-minZ>2 || (long)maxY-minY>26)
            throw new IllegalStateException("Visual light inputs exceed fixed 3x27x3 window");
    }

    public long computedGeneration() { return computedGeneration; }
    public long recomputations() { return recomputations; }
    public int computedCubes() { return published == null ? 0 : published.keys().size(); }
    public long dependencyGeneration() { return dependencyGeneration; }
    public int nativeHaloCubes() { return seenNative == null ? 0 : seenNative.knownCubes(); }

    public static final class UnavailableLightException extends IllegalStateException {
        public UnavailableLightException(BlockPos position) {
            super("BLOCK light is not confirmed at actual XYZ " + position + ": missing input or uncertain boundary");
        }
        public UnavailableLightException(BlockPos position, String reason, Throwable cause) {
            super("BLOCK light is not confirmed at actual XYZ " + position + ": " + reason, cause);
        }
    }
}
