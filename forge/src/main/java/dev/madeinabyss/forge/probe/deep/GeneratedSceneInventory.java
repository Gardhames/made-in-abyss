package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable store-first geometry inventory; never grants simulation residency. */
public final class GeneratedSceneInventory {
    private GeneratedSceneInventory() { }
    public record CacheStats(long validatedCubes,long expectedHits,long expectedGenerated,long invalidShards,long writeFailures,long saturatedMisses) { }
    public record Snapshot(List<CubeSnapshot> cubes, List<GeneratedSceneNetwork.Frame> frames, long encodedBytes,CacheStats cacheStats) {
        public Snapshot(List<CubeSnapshot> cubes,List<GeneratedSceneNetwork.Frame> frames,long encodedBytes){this(cubes,frames,encodedBytes,new CacheStats(cubes.size(),0,0,0,0,0));}
        public Snapshot {
            cubes=List.copyOf(cubes); frames=List.copyOf(frames);
            if(cubes.size()!=frames.size()||cubes.size()>GeneratedSceneNetwork.MAX_CUBES)
                throw new IllegalArgumentException("Invalid inventory cube count");
            long total=0;
            for(int i=0;i<cubes.size();i++) {
                if(!cubes.get(i).key().equals(frames.get(i).key()))throw new IllegalArgumentException("Inventory frame key mismatch");
                total+=frames.get(i).bytes().length;
            }
            if(total!=encodedBytes||total>GeneratedSceneNetwork.MAX_ENCODED_BYTES)throw new IllegalArgumentException("Invalid inventory byte count");
        }
    }
    /** Caller holds the server owner thread; capture completes before any scene packet is published. */
    public static Snapshot capture(Registry<Biome> biomes, StableCubeStore store) throws IOException {
        return capture(biomes,store,null);
    }
    /** Validates every saved payload before omitting geometry exactly equal to the current domain generator.
     * Saves, ticks and block entities remain untouched; this filters only the derived observer transfer. */
    public static Snapshot capture(Registry<Biome> biomes, StableCubeStore store,
                                   java.util.function.Function<CubeKey,CubeSnapshot> generator) throws IOException {
        return capture(biomes,store,generator,null);
    }
    /** Full registry mapping binds numeric digest IDs; immutable profile and actual loaded project
     * code bind generation. Identity is intentionally conservative across unrelated project changes. */
    // The digest covers the whole numeric block-state registry (tens of thousands of toString calls) and was
    // recomputed for every native target. Registry and profile are immutable for a session: memoise per profile.
    private record ProceduralIdentity(java.lang.ref.WeakReference<dev.madeinabyss.forge.worldgen.GeneratedPreviewProfile> profile,int registrySize,String value){}
    private static volatile ProceduralIdentity lastProceduralIdentity;
    public static String proceduralIdentity(dev.madeinabyss.forge.worldgen.GeneratedPreviewProfile profile) {
        var last=lastProceduralIdentity;
        if(last!=null&&last.profile.get()==profile&&last.registrySize==Block.BLOCK_STATE_REGISTRY.size())return last.value;
        var value=computeProceduralIdentity(profile);
        lastProceduralIdentity=new ProceduralIdentity(new java.lang.ref.WeakReference<>(profile),Block.BLOCK_STATE_REGISTRY.size(),value);
        return value;
    }
    private static String computeProceduralIdentity(dev.madeinabyss.forge.worldgen.GeneratedPreviewProfile profile) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest))){
                out.writeUTF("generated-observer-expected-v1");out.writeUTF(profile.fingerprint());
                out.writeUTF(GeneratedInventoryCodeIdentity.get());out.writeInt(Block.BLOCK_STATE_REGISTRY.size());
                for(int i=0;i<Block.BLOCK_STATE_REGISTRY.size();i++){
                    var state=Block.BLOCK_STATE_REGISTRY.byId(i);
                    if(state==null||Block.getId(state)!=i)throw new IllegalStateException("Invalid numeric registry palette");
                    out.writeInt(i);out.writeUTF(state.toString());
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("Cannot identify expected procedural inventory",unavailable);}
    }
    /** Optional acceleration must not prevent opening a fully validated saved world. */
    public static String optionalProceduralIdentity(dev.madeinabyss.forge.worldgen.GeneratedPreviewProfile profile) {
        try{return proceduralIdentity(profile);}
        catch(RuntimeException unavailable){com.mojang.logging.LogUtils.getLogger().warn("Procedural inventory cache unavailable; validating against generator",unavailable);return null;}
    }
    /** Explicit identity is supplied only for a known immutable generator. Cache failures are misses;
     * every saved payload still passes CRC/provenance/tick/BE decoding on every capture. */
    public static Snapshot capture(Registry<Biome> biomes,StableCubeStore store,
            java.util.function.Function<CubeKey,CubeSnapshot> generator,String verifiedIdentity)throws IOException {
        if(verifiedIdentity!=null&&generator==null)throw new IllegalArgumentException("Expected generator required for digest cache");
        var keys=new ArrayList<CubeKey>();var directory=store.path(new CubeKey(0,0,0)).getParent();
        var index=verifiedIdentity==null?null:new SavedProceduralDigestIndex(directory.resolveSibling("observer-procedural-digests"),verifiedIdentity);
        try(var entries=Files.newDirectoryStream(directory)) {
            for(var file:entries) {
                String name=file.getFileName().toString();
                if(!name.startsWith("c.")||!name.endsWith(".miacube"))continue;
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Nonregular saved cube: "+name);
                String[] fields=name.split("\\.",-1);
                try {
                    if(fields.length!=5||!fields[0].equals("c")||!fields[4].equals("miacube"))throw new IllegalArgumentException();
                    int x=Integer.parseInt(fields[1]),y=Integer.parseInt(fields[2]),z=Integer.parseInt(fields[3]);
                    if(!Integer.toString(x).equals(fields[1])||!Integer.toString(y).equals(fields[2])||!Integer.toString(z).equals(fields[3]))throw new IllegalArgumentException();
                    keys.add(new CubeKey(x,y,z));
                }catch(IllegalArgumentException invalid){throw new IOException("Noncanonical saved cube filename: "+name,invalid);}
                if(generator==null&&keys.size()>GeneratedSceneNetwork.MAX_CUBES)throw new IOException("Saved scene cube count exceeds bound");
            }
        }catch(NoSuchFileException absent) {
            return new Snapshot(List.of(),List.of(),0);
        }
        keys.sort(Comparator.comparingInt(CubeKey::x).thenComparingInt(CubeKey::y).thenComparingInt(CubeKey::z));
        int capacity=Math.min(keys.size(),GeneratedSceneNetwork.MAX_CUBES);
        var snapshots=new ArrayList<CubeSnapshot>(capacity);var frames=new ArrayList<GeneratedSceneNetwork.Frame>(capacity);long total=0;
        for(var key:keys) {
            var snapshot=store.read(key).orElseThrow(()->new IOException("Saved cube disappeared during capture: "+key));
            int[] states=snapshot.states();
            if(generator!=null) {
                if(index!=null) {if(index.matches(key,states,generator))continue;}
                else {
                    var expected=generator.apply(key);
                    if(expected==null||!expected.key().equals(key))throw new IOException("Generated scene comparator returned wrong cube: "+key);
                    if(java.util.Arrays.equals(states,expected.states()))continue;
                }
            }
            if(snapshots.size()>=GeneratedSceneNetwork.MAX_CUBES)throw new IOException("Saved scene cube count exceeds bound");
            var section=new LevelChunkSection(biomes);
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)section.setBlockState(x,y,z,Block.stateById(states[y<<8|z<<4|x]));
            var buffer=new FriendlyByteBuf(Unpooled.buffer(4096,CubeSectionPacketCodec.MAX_SECTION_BYTES+24));
            try {
                CubeSectionPacketCodec.encode(buffer,key,section);byte[] bytes=new byte[buffer.readableBytes()];buffer.readBytes(bytes);
                total+=bytes.length;
                if(total>GeneratedSceneNetwork.MAX_ENCODED_BYTES)throw new IOException("Saved scene encoded bytes exceed bound");
                // Full CRC/provenance/ticks/BE validation above; retain only immutable geometry
                // for light/LOD observers, avoiding unbounded retained chest metadata.
                snapshots.add(new CubeSnapshot(key,states));frames.add(new GeneratedSceneNetwork.Frame(key,bytes));
            }finally {buffer.release();}
        }
        if(index!=null)index.flush();
        var stats=new CacheStats(keys.size(),index==null?0:index.hits(),index==null?(generator==null?0:keys.size()):index.generated(),
                index==null?0:index.invalidShards(),index==null?0:index.writeFailures(),index==null?0:index.saturated());
        return new Snapshot(snapshots,frames,total,stats);
    }
}
