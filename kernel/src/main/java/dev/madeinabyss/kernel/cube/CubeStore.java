package dev.madeinabyss.kernel.cube;

import java.io.IOException;
import java.util.Optional;

/** Persistence boundary: empty means missing, while invalid/unreadable data is an error. */
public interface CubeStore {
    Optional<CubeSnapshot> read(CubeKey key) throws IOException;
    void write(CubeSnapshot snapshot) throws IOException;
}
