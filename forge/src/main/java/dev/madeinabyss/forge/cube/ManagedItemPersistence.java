package dev.madeinabyss.forge.cube;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public interface ManagedItemPersistence {
    Optional<ManagedItemSnapshot> load(UUID uuid) throws IOException;
    void save(ManagedItemSnapshot snapshot) throws IOException;
    void erase(UUID uuid) throws IOException;
    Path file(UUID uuid);
}
