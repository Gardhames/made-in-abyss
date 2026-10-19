package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.HashSet;
import java.util.Set;

/** Dirty geometry authority keys; revisions identify verified immutable worlds, not residency. */
public final class GeometryPatchChanges {
    private GeometryPatchChanges() {}
    public static <T> Set<T> againstBase(String baseIdentity,String currentIdentity,Set<T> affected) {
        if(baseIdentity!=null&&baseIdentity.equals(currentIdentity))return Set.of();
        return Set.copyOf(affected);
    }
    public static Set<CubeKey> between(long currentRevision,long previousRevision,
                                      Set<CubeKey> currentAuthority,Set<CubeKey> previousAuthority) {
        if(currentRevision==previousRevision)return Set.of();
        var changed=new HashSet<>(currentAuthority);changed.addAll(previousAuthority);
        return Set.copyOf(changed);
    }
}
