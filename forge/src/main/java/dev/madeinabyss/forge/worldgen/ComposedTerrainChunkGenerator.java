package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Native column adapter for the exact immutable recipe used by deep cubes.
 * Complete recipe bytes and their identity live in level.dat via this codec.
 * No preview path, JVM development flag, vanilla feature or structure fallback.
 * Existing saved chunks remain owned by Minecraft's normal chunk store.
 */
public final class ComposedTerrainChunkGenerator extends ChunkGenerator {
    private static final MapCodec<ComposedTerrainChunkGenerator> FIELDS=
            RecordCodecBuilder.<ComposedTerrainChunkGenerator>mapCodec(i->i.group(
                    Codec.STRING.fieldOf("manifest_json").forGetter(ComposedTerrainChunkGenerator::manifestJson),
                    Codec.STRING.fieldOf("manifest_sha256").forGetter(ComposedTerrainChunkGenerator::manifestSha256),
                    Codec.STRING.fieldOf("profile_fingerprint").forGetter(g->g.profile.fingerprint()),
                    FixedBiomeSource.CODEC.fieldOf("biome_source").forGetter(g->(FixedBiomeSource)g.getBiomeSource())
            ).apply(i,ComposedTerrainChunkGenerator::new));
    // Registry dispatch recognizes MapCodecCodec. A generic Codec guard changes the
    // dispatch shape into {type,value:{...}} and loses native flattened world fields.
    public static final Codec<ComposedTerrainChunkGenerator> CODEC=new MapCodec<ComposedTerrainChunkGenerator>() {
        @Override public <T> Stream<T> keys(DynamicOps<T> ops){return FIELDS.keys(ops);}
        @Override public <T> DataResult<ComposedTerrainChunkGenerator> decode(DynamicOps<T> ops,MapLike<T> input) {
            try{return FIELDS.decode(ops,input);}
            catch(IllegalArgumentException invalid){return DataResult.error(()->invalid.getMessage());}
        }
        @Override public <T> RecordBuilder<T> encode(ComposedTerrainChunkGenerator value,DynamicOps<T> ops,RecordBuilder<T> prefix) {
            return FIELDS.encode(value,ops,prefix);
        }
    }.codec();
    private static final int MAX_MANIFEST_NBT_UTF_BYTES=65535;
    private final String manifestJson,manifestSha256;
    private final JsonObject manifest;
    private final GeneratedPreviewProfile profile;
    private final ParameterizedTerrainChunkGenerator delegate;

    public ComposedTerrainChunkGenerator(JsonObject manifest,FixedBiomeSource biomes) {
        this(Objects.requireNonNull(manifest).toString(),biomes);
    }
    private ComposedTerrainChunkGenerator(String json,FixedBiomeSource biomes) {
        this(json,sha256(json),null,biomes);
    }
    private ComposedTerrainChunkGenerator(String json,String expectedHash,String expectedFingerprint,FixedBiomeSource biomes) {
        super(Objects.requireNonNull(biomes),ignored->BiomeGenerationSettings.EMPTY);
        if(!fitsNativeString(json))
            throw new IllegalArgumentException("Native composed manifest exceeds NBT modified UTF limit of 65535 bytes");
        manifestSha256=sha256(json);
        if(!manifestSha256.equals(expectedHash))throw new IllegalArgumentException("Native composed manifest SHA256 mismatch");
        manifestJson=json;
        try {
            var parsed=JsonParser.parseString(json);
            if(!parsed.isJsonObject())throw new IllegalArgumentException("Native composed manifest must be an object");
            manifest=parsed.getAsJsonObject();
            profile=SharedPreviewProfiles.forServer(manifest,manifest.get("profileId").getAsString(),biomes);
            if(expectedFingerprint!=null&&!profile.fingerprint().equals(expectedFingerprint))
                throw new IllegalArgumentException("Native composed profile fingerprint mismatch");
        }catch(RuntimeException invalid) {
            throw new IllegalArgumentException("Native composed manifest refused: "+invalid.getMessage(),invalid);
        }
        delegate=profile.generator();
    }
    private static String sha256(String json) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(json).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA256 unavailable",impossible);}
    }
    /** NBT StringTag writes DataOutput.writeUTF, including separate surrogate code units. */
    static boolean fitsNativeString(String json) {
        if(json==null||json.length()>MAX_MANIFEST_NBT_UTF_BYTES)return false;
        int bytes=0;
        for(int i=0;i<json.length();i++) {
            char c=json.charAt(i);
            bytes+=(c>=1&&c<=127)?1:(c<=2047?2:3);
            if(bytes>MAX_MANIFEST_NBT_UTF_BYTES)return false;
        }
        return true;
    }
    /** Defensive recipe copy for world-session transport; consumers cannot change this generator. */
    public JsonObject manifest(){return manifest.deepCopy();}
    public String manifestJson(){return manifestJson;}
    public String manifestSha256(){return manifestSha256;}
    public GeneratedPreviewProfile profile(){return profile;}
    @Override protected Codec<? extends ChunkGenerator> codec(){return CODEC;}
    @Override public int getMinY(){return delegate.getMinY();}
    @Override public int getGenDepth(){return delegate.getGenDepth();}
    @Override public int getSeaLevel(){return delegate.getSeaLevel();}
    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor,Blender blender,RandomState state,StructureManager structures,ChunkAccess chunk) {
        return delegate.fillFromNoise(executor,blender,state,structures,chunk);
    }
    @Override public NoiseColumn getBaseColumn(int x,int z,LevelHeightAccessor height,RandomState state){return delegate.getBaseColumn(x,z,height,state);}
    @Override public int getBaseHeight(int x,int z,Heightmap.Types type,LevelHeightAccessor height,RandomState state){return delegate.getBaseHeight(x,z,type,height,state);}
    @Override public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> sets,RandomState state,long seed){return delegate.createState(sets,state,seed);}
    @Override public void applyCarvers(WorldGenRegion region,long seed,RandomState state,BiomeManager biomes,StructureManager structures,ChunkAccess chunk,GenerationStep.Carving stage){}
    @Override public void buildSurface(WorldGenRegion region,StructureManager structures,RandomState state,ChunkAccess chunk){}
    @Override public void applyBiomeDecoration(WorldGenLevel level,ChunkAccess chunk,StructureManager structures){}
    @Override public void spawnOriginalMobs(WorldGenRegion region){}
    @Override public void addDebugScreenInfo(List<String> lines,RandomState state,BlockPos pos){
        delegate.addDebugScreenInfo(lines,state,pos);lines.add("MIA persisted composed world: "+profile.id());
    }
}
