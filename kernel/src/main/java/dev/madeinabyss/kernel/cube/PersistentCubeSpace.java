package dev.madeinabyss.kernel.cube;

import java.io.IOException;
import java.util.Objects;

/** Synchronous diagnostic save-before-unload boundary; does not tick or generate Minecraft chunks. */
public final class PersistentCubeSpace {
    private final SparseCubeSpace space;
    private final CubeStore store;

    public PersistentCubeSpace(SparseCubeSpace space, FileCubeStore store) {
        this(space, (CubeStore) store);
    }

    public PersistentCubeSpace(SparseCubeSpace space, CubeStore store) {
        this.space = Objects.requireNonNull(space);
        this.store = Objects.requireNonNull(store);
    }

    public void load(CubeKey key) throws IOException {
        if (space.isLoaded(key)) return;
        var saved = store.read(key);
        if (saved.isPresent()) space.load(saved.orElseThrow());
        else space.loadEmpty(key);
    }

    public boolean saveAndUnload(CubeKey key) throws IOException {
        var snapshot = space.snapshot(key);
        if (snapshot.isEmpty()) return false;
        store.write(snapshot.orElseThrow());
        space.unload(key);
        return true;
    }
}
