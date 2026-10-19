package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;

/** Three signed section coordinates; never packed into a lossy block-position long. */
public record CubeKey(int x, int y, int z) {
    /** The default record hash is a base-31 polynomial that collides heavily for neighbouring cubes. */
    @Override public int hashCode(){return dev.madeinabyss.kernel.concurrent.FrozenCubeTable.hash(x,y,z);}
    public static CubeKey fromBlock(BlockCoordinate position) {
        return new CubeKey(Math.floorDiv(position.x(), 16), Math.floorDiv(position.y(), 16),
                Math.floorDiv(position.z(), 16));
    }
}
