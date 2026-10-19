package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.ProceduralCubeDigestCache;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** Optional derived expected geometry; saved payload validation remains the caller's responsibility. */
final class SavedProceduralDigestIndex {
    // Each shard keeps the existing 8192-entry/1MiB format bound. Saturation is a safe miss.
    static final int SHARDS=64;
    private final Path directory;
    private final String identity;
    private final Map<Integer,Map<CubeKey,byte[]>> shards=new HashMap<>();
    private final Set<Integer> dirty=new HashSet<>();
    private long hits,generated,invalidShards,writeFailures,saturated;
    SavedProceduralDigestIndex(Path directory,String identity) {
        this.directory=Objects.requireNonNull(directory);
        if(identity==null||!identity.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Explicit SHA256 identity required");
        this.identity=identity;
    }
    boolean matches(CubeKey key,int[] saved,Function<CubeKey,CubeSnapshot> generator) throws java.io.IOException {
        int shard=Math.floorMod(key.hashCode(),SHARDS);
        var digests=shards.computeIfAbsent(shard,index->{
            var read=ProceduralCubeDigestCache.read(directory.resolve("shard-"+index),identity);
            if(read.status().startsWith("INVALID")||read.status().equals("OVERSIZE"))invalidShards++;
            return new HashMap<>(read.entry().orElse(Map.of()));
        });
        var digest=digests.get(key);
        if(digest!=null){hits++;return MessageDigest.isEqual(digest,ProceduralCubeDigestCache.digest(saved));}
        var expected=generator.apply(key);
        if(expected==null||!expected.key().equals(key))throw new java.io.IOException("Wrong expected cube");
        generated++;var states=expected.states();
        if(digests.size()<ProceduralCubeDigestCache.MAX_ENTRIES){digests.put(key,ProceduralCubeDigestCache.digest(states));dirty.add(shard);}
        else saturated++;
        return Arrays.equals(saved,states);
    }
    void flush() {
        for(int shard:dirty)try{ProceduralCubeDigestCache.write(directory.resolve("shard-"+shard),identity,shards.get(shard));}
        catch(java.io.IOException|RuntimeException unavailable){writeFailures++;}
        dirty.clear();
    }
    long hits(){return hits;}
    long generated(){return generated;}
    long invalidShards(){return invalidShards;}
    long writeFailures(){return writeFailures;}
    long saturated(){return saturated;}
}
