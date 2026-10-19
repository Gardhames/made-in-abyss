package dev.madeinabyss.kernel.lod;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** Preserve a revoked derived-cache directory in an ignored sibling; never moves world/save roots. */
public final class DerivedCacheQuarantine {
    private DerivedCacheQuarantine() { }
    public static void moveChild(Path parent,String child)throws IOException {
        var root=parent.toAbsolutePath().normalize();var source=root.resolve(child).normalize();
        if(!source.getParent().equals(root)||!child.matches("[a-z0-9-]+"))throw new IOException("Invalid derived cache quarantine scope");
        if(!Files.exists(source))return;
        if(Files.isSymbolicLink(source)||!Files.isDirectory(source))throw new IOException("Derived cache quarantine requires an owned directory");
        var target=root.resolve(child+".quarantined-"+UUID.randomUUID()).normalize();
        if(!target.getParent().equals(root))throw new IOException("Invalid derived cache quarantine destination");
        Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);
    }
}
