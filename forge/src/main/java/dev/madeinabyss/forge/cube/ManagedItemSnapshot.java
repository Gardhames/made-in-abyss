package dev.madeinabyss.forge.cube;

import java.util.UUID;
import java.util.Objects;

/** Immutable native item payload, keyed by identity rather than its current cube. */
public record ManagedItemSnapshot(UUID uuid, byte[] data) {
    public ManagedItemSnapshot {
        Objects.requireNonNull(uuid,"uuid"); Objects.requireNonNull(data,"data");
        if(data.length<1 || data.length>ManagedItemCodec.MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("invalid managed item payload size");
        data=data.clone();
    }
    @Override public byte[] data() { return data.clone(); }
}
