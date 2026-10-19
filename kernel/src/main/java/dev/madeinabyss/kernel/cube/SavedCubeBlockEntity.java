package dev.madeinabyss.kernel.cube;

import dev.madeinabyss.kernel.BlockCoordinate;
import java.util.Arrays;
import java.util.Objects;

/** Registry-named opaque payload; the kernel does not interpret Minecraft NBT. */
public record SavedCubeBlockEntity(BlockCoordinate position, String type, byte[] data) {
    public static final int MAX_PER_CUBE = 64;
    public static final int MAX_PAYLOAD_BYTES = 65536;
    public SavedCubeBlockEntity {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        if (!type.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("canonical namespaced block entity type required");
        if (data.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("block entity payload exceeds budget");
        data = data.clone();
    }
    @Override public byte[] data() { return data.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof SavedCubeBlockEntity saved && position.equals(saved.position)
            && type.equals(saved.type) && Arrays.equals(data, saved.data);
    }
    @Override public int hashCode() { return 31 * Objects.hash(position, type) + Arrays.hashCode(data); }
}
