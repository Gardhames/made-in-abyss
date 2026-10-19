package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.worldgen.GeneratedSkySource;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent authority for exact original32 scalars, independent of runtime revision counters.
 * An owner's six probes never start below owner.minY-1; the 32m interpolation lattice never
 * falls more than 31m below a probe. Segments stay between endpoints, all sky rays go upwards,
 * direct SKY depends only on voxels above its probe, and block emission uses the owner center.
 * Therefore a page whose owners start at pageMinY cannot depend on edits below pageMinY-33.
 * We deliberately include every X/Z above that conservative cutoff: 32 is spacing/ray count,
 * not a maximum ray distance. This proof does not assume any upper bound on owner dimensions.
 * A palette change or a changed global sky ceiling invalidates the complete family safely.
 */
public final class AbyssLodScalarAuthority {
    private record Edit(CubeKey cube,String digest) { }
    private final String familyIdentity,globalRevision;
    private final List<Edit> edits;
    private final ConcurrentHashMap<Long,String> heightRevisions=new ConcurrentHashMap<>();

    public static AbyssLodScalarAuthority create(String worldFingerprint,String resourceIdentity,GeneratedSkySource source) {
        return new AbyssLodScalarAuthority(worldFingerprint,resourceIdentity,AbyssLodPhysicalLighting.codeIdentity(),
                Objects.requireNonNull(source).cacheAuthority());
    }
    AbyssLodScalarAuthority(String worldFingerprint,String resourceIdentity,String physicalCode,GeneratedSkySource.CacheAuthority metadata) {
        Objects.requireNonNull(metadata);
        familyIdentity=TerrainLodDiskCache.identity("abyss-original32-owner-authority-v1",required(worldFingerprint),
                required(resourceIdentity),required(physicalCode),required(metadata.baseGeometryIdentity()),PolicyIdentity.VALUE);
        globalRevision=required(metadata.globalRevision());
        edits=metadata.editedCubeDigests().entrySet().stream().map(e->new Edit(e.getKey(),required(e.getValue())))
                .sorted(Comparator.comparingInt((Edit e)->e.cube().x()).thenComparingInt(e->e.cube().y()).thenComparingInt(e->e.cube().z())).toList();
    }
    public String familyIdentity(){return familyIdentity;}
    public String globalRevision(){return globalRevision;}
    public int editedCubes(){return edits.size();}
    /** Owner pages are indexed by floorDiv(minXYZ,128); coordinates supplied here are block minima. */
    public String pageIdentity(long pageMinX,long pageMinY,long pageMinZ) {
        if(Math.floorMod(pageMinX,128)!=0||Math.floorMod(pageMinY,128)!=0||Math.floorMod(pageMinZ,128)!=0)
            throw new IllegalArgumentException("Exact owner authority requires aligned 128m page minima");
        return heightRevisions.computeIfAbsent(pageMinY,this::heightIdentity);
    }
    private String heightIdentity(long pageMinY) {
        long minimumDependencyY=Math.subtractExact(pageMinY,33);
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest));
            out.writeUTF("abyss-original32-upward-dependencies-v1");out.writeUTF(familyIdentity);out.writeLong(pageMinY);
            for(var edit:edits) {
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Scalar page authority canceled");
                if((long)edit.cube().y()*16+15<minimumDependencyY)continue;
                out.writeInt(edit.cube().x());out.writeInt(edit.cube().y());out.writeInt(edit.cube().z());out.writeUTF(edit.digest());
            }
            out.flush();return HexFormat.of().formatHex(digest.digest());
        }catch(IOException|NoSuchAlgorithmException impossible){throw new IllegalStateException("Cannot identify exact scalar dependencies",impossible);}
    }
    private static String required(String value){if(Objects.requireNonNull(value).isEmpty())throw new IllegalArgumentException("Complete persistent scalar authority required");return value;}
    private static final class PolicyIdentity {static final String VALUE=TerrainLodDiskCache.codeIdentity(AbyssLodScalarAuthority.class);}
}
