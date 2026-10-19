package dev.madeinabyss.kernel;

public record DepthReference(int surfaceY) {
    public long depthAt(int worldY) {
        return (long) surfaceY - worldY;
    }
}
