package dev.madeinabyss.kernel.terrain.trail;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.util.Objects;
public final class TrailOverlay {
    private final TrailNetwork.SampleSource base;
    private final TrailNetwork network;
    private static final TerrainSample.Known AIR=new TerrainSample.Known(TerrainMaterial.AIR);
    /** Same immutable base instance used during verification; never pass this overlay back as its base. */
    public TrailOverlay(TrailNetwork.SampleSource base,TrailNetwork network) {
        this.base=Objects.requireNonNull(base);this.network=Objects.requireNonNull(network);
        if(base!=network.verifiedBase())throw new IllegalArgumentException("Overlay needs exact verified base source");
    }
    public TerrainSample sample(int x,int y,int z) {
        var sample=base.sample(x,y,z);
        if(sample instanceof TerrainSample.Unavailable)return sample;
        var floor=network.floor(x,z);
        return floor!=null && (long)y>floor && (long)y<=((long)floor+network.parameters().headroomMetres())?AIR:sample;
    }
}
