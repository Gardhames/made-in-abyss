package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.Objects;

/** Scalar light input; local index is x | (z << 4) | (y << 8). */
public final class CubeLightInput {
    private final CubeKey key;
    private final byte[] emission;
    private final byte[] opacity;

    public CubeLightInput(CubeKey key, byte[] emission, byte[] opacity) {
        this.key = Objects.requireNonNull(key, "key");
        validateAxis(key.x());
        validateAxis(key.y());
        validateAxis(key.z());
        this.emission = copyValues(emission, "emission");
        this.opacity = copyValues(opacity, "opacity");
    }

    public CubeKey key() { return key; }
    public byte[] emission() { return emission.clone(); }
    public byte[] opacity() { return opacity.clone(); }

    private static byte[] copyValues(byte[] values, String name) {
        Objects.requireNonNull(values, name);
        if (values.length != 4096) throw new IllegalArgumentException(name + " must contain 4096 values");
        var copy = values.clone();
        for (byte value : copy) {
            if (value < 0 || value > 15) throw new IllegalArgumentException(name + " values must be 0..15");
        }
        return copy;
    }

    private static void validateAxis(int section) {
        if (section < -134217728 || section > 134217727) {
            throw new IllegalArgumentException("cube must fit entirely in signed int block coordinates");
        }
    }
}
