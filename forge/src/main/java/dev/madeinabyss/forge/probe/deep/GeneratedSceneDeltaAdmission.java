package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;

/** Owner-thread transactional admission. A live global observation never grants residency. */
public final class GeneratedSceneDeltaAdmission {
    private UUID session;
    private long revision,lifetime;
    public GeneratedSceneDeltaAdmission(){}
    public void reset(UUID token){lifetime=Math.incrementExact(lifetime);session=token;revision=0;}
    public long revision(){return revision;}

    public static final class Prepared {
        private final GeneratedSceneDeltaAdmission owner;
        private final UUID session;
        private final long baseRevision,revision,lifetime;
        private final CubeSnapshot snapshot;
        private Prepared(GeneratedSceneDeltaAdmission owner,UUID session,long baseRevision,long revision,long lifetime,CubeSnapshot snapshot){
            this.owner=owner;this.session=session;this.baseRevision=baseRevision;this.revision=revision;this.lifetime=lifetime;this.snapshot=snapshot;
        }
        public CubeKey key(){return snapshot.key();}
        public long revision(){return revision;}
        /** Every call returns a new owned state array; no mutable internal array can escape. */
        public Map<CubeKey,int[]> statesMap(){return Map.of(snapshot.key(),snapshot.states());}
    }

    /** Foreign/stale packets are ignored. Gaps and malformed native palettes refuse without advance.
     * Caller additionally validates profile spatial support before observing/committing this stage. */
    public Prepared stage(GeneratedSceneNetwork.Delta delta,Registry<Biome> biomes){
        Objects.requireNonNull(delta);
        if(session==null||!session.equals(delta.session())||delta.revision()<=revision)return null;
        if(delta.revision()!=Math.incrementExact(revision))throw new IllegalArgumentException("Global delta revision gap");
        Objects.requireNonNull(biomes);
        var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(delta.frame().bytes()));
        try{
            var decoded=CubeSectionPacketCodec.decode(buffer,biomes);
            if(!decoded.key().equals(delta.frame().key()))throw new IllegalArgumentException("Global delta cube key mismatch");
            int[] states=new int[4096];
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)states[y<<8|z<<4|x]=Block.getId(decoded.section().getBlockState(x,y,z));
            return new Prepared(this,session,revision,delta.revision(),lifetime,new CubeSnapshot(decoded.key(),states));
        }finally{buffer.release();}
    }
    /** Call only after profile validation and observations.observe accepted the immutable states. */
    public void commit(Prepared prepared){
        Objects.requireNonNull(prepared);
        if(prepared.owner!=this||prepared.lifetime!=lifetime||!Objects.equals(prepared.session,session)
                ||prepared.baseRevision!=revision||prepared.revision!=Math.incrementExact(revision))
            throw new IllegalStateException("Stale global delta transaction");
        revision=prepared.revision;
    }
}
