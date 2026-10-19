package dev.madeinabyss.forge.cube;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.BlockCoordinate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.List;
import java.util.Objects;

/** Native spatial-query adapter over diagnostic cube IDs; not a ServerLevel or an entity simulator. */
public final class CubeBlockView implements CollisionGetter {
    private static final int MAX_QUERY_CUBES = 4096;
    private final SparseCubeSpace space;
    private final int minY;
    private final int height;
    private final WorldBorder border = new WorldBorder();

    public CubeBlockView(SparseCubeSpace space, int minY, int height) {
        this.space = Objects.requireNonNull(space);
        if (height <= 0 || minY % 16 != 0 || height % 16 != 0
                || (long) minY + height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Expected aligned positive height with representable exclusive top");
        }
        this.minY = minY;
        this.height = height;
    }

    @Override public BlockState getBlockState(BlockPos position) {
        if (isOutsideBuildHeight(position)) throw new IllegalArgumentException("Position outside diagnostic view: " + position);
        var coordinate = new BlockCoordinate(position.getX(), position.getY(), position.getZ());
        int id = space.block(coordinate).orElseThrow(() -> new MissingCubeException(CubeKey.fromBlock(coordinate)));
        BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
        if (state == null) throw new IllegalStateException("Unknown native state ID: " + id);
        return state;
    }

    @Override public FluidState getFluidState(BlockPos position) { return getBlockState(position).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos position) {
        if (getBlockState(position).hasBlockEntity()) throw new UnsupportedOperationException("Block entities are not present in diagnostic snapshots");
        return null;
    }
    @Override public int getMinBuildHeight() { return minY; }
    @Override public int getHeight() { return height; }
    @Override public WorldBorder getWorldBorder() { return border; }
    // Native BlockCollisions caches by X/Z only; dispatch each actual Y through this column-view.
    @Override public BlockGetter getChunkForCollisions(int sectionX, int sectionZ) { return this; }
    @Override public List<VoxelShape> getEntityCollisions(Entity entity, AABB query) { return List.of(); }
    @Override public BlockHitResult clip(ClipContext context) {
        // Native traversal nudges the endpoints; preflight a conservative one-block halo.
        requireLoaded(new AABB(context.getFrom(), context.getTo()).inflate(1));
        return CollisionGetter.super.clip(context);
    }
    @Override public Iterable<VoxelShape> getBlockCollisions(Entity entity, AABB query) {
        // Native Cursor3D checks a one-cell halo; two blocks also cover floating-point epsilon.
        requireLoaded(query.inflate(2));
        return CollisionGetter.super.getBlockCollisions(entity, query);
    }

    private void requireLoaded(AABB query) {
        int minX = blockFloor(query.minX), maxX = blockFloor(query.maxX);
        int minBlockY = blockFloor(query.minY), maxBlockY = blockFloor(query.maxY);
        int minZ = blockFloor(query.minZ), maxZ = blockFloor(query.maxZ);
        if (minBlockY < minY || maxBlockY >= (long) minY + height) {
            throw new IllegalArgumentException("Spatial query exceeds diagnostic height");
        }
        int sx0 = Math.floorDiv(minX, 16), sx1 = Math.floorDiv(maxX, 16);
        int sy0 = Math.floorDiv(minBlockY, 16), sy1 = Math.floorDiv(maxBlockY, 16);
        int sz0 = Math.floorDiv(minZ, 16), sz1 = Math.floorDiv(maxZ, 16);
        long nx = (long) sx1 - sx0 + 1, ny = (long) sy1 - sy0 + 1, nz = (long) sz1 - sz0 + 1;
        if (nx > MAX_QUERY_CUBES || ny > MAX_QUERY_CUBES || nz > MAX_QUERY_CUBES
                || nx * ny * nz > MAX_QUERY_CUBES) {
            throw new IllegalArgumentException("Spatial query exceeds " + MAX_QUERY_CUBES + "-cube diagnostic budget");
        }
        for (int x = sx0; x <= sx1; x++) {
            for (int y = sy0; y <= sy1; y++) {
                for (int z = sz0; z <= sz1; z++) {
                    var key = new CubeKey(x, y, z);
                    if (!space.isLoaded(key)) throw new MissingCubeException(key);
                }
            }
        }
    }

    private static int blockFloor(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value >= (double) Integer.MAX_VALUE + 1) {
            throw new IllegalArgumentException("Nonfinite or unrepresentable spatial bound: " + value);
        }
        return (int) Math.floor(value);
    }

    public static final class MissingCubeException extends IllegalStateException {
        private final CubeKey key;
        public MissingCubeException(CubeKey key) { super("Cube data is not resident: " + key); this.key = key; }
        public CubeKey key() { return key; }
    }
}
