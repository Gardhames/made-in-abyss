package dev.madeinabyss.kernel;

/** Lossless section/local addressing; integration with Minecraft is tested separately. */
public record SectionAddress(int sectionX, int sectionY, int sectionZ,
                             int localX, int localY, int localZ) {
    public SectionAddress {
        if (localX < 0 || localX >= 16 || localY < 0 || localY >= 16 || localZ < 0 || localZ >= 16) {
            throw new IllegalArgumentException("local coordinates must be inside a 16-block section");
        }
    }
    public static SectionAddress fromBlock(BlockCoordinate block) {
        return new SectionAddress(Math.floorDiv(block.x(), 16), Math.floorDiv(block.y(), 16),
                Math.floorDiv(block.z(), 16), Math.floorMod(block.x(), 16),
                Math.floorMod(block.y(), 16), Math.floorMod(block.z(), 16));
    }
    public BlockCoordinate block() {
        return new BlockCoordinate(reconstruct(sectionX, localX), reconstruct(sectionY, localY),
                reconstruct(sectionZ, localZ));
    }
    private static int reconstruct(int section, int local) {
        return Math.toIntExact((long) section * 16 + local);
    }
}
