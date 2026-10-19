package dev.madeinabyss.forge.probe.client.compat.lightgpu;

import java.util.Objects;
import java.util.function.Supplier;

/** Boundary contract: immutable identities, live cancellation/retirement check. */
public final class GpuAuthorityGuard {
    private final String fingerprint;
    private final String generation,physical,source,producer,packed;
    public GpuAuthorityGuard(String fingerprint,Supplier<String> generation,Supplier<String> physical,Supplier<String> source,Supplier<String> producer,Supplier<String> packed){
        this.fingerprint=Objects.requireNonNull(fingerprint);this.generation=Objects.requireNonNull(generation.get());this.physical=Objects.requireNonNull(physical.get());this.source=Objects.requireNonNull(source.get());this.producer=Objects.requireNonNull(producer.get());this.packed=Objects.requireNonNull(packed.get());
    }
    public void require(GpuSnapshotMetadata metadata,Runnable live){
        live.run();metadata.require(fingerprint,generation,physical,source,producer,packed);live.run();
    }
    public String fingerprint(){return fingerprint;}
}
