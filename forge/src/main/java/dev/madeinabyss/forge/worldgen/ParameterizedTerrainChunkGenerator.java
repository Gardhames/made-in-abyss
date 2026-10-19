package dev.madeinabyss.forge.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.terrain.ParametricTerrainSampler;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import dev.madeinabyss.kernel.terrain.habitat.HabitatOverlay;
import dev.madeinabyss.kernel.terrain.habitat.LandscapeOverlay;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/** Parameterized geological skeleton shared by native columns and deep cubes. */
public final class ParameterizedTerrainChunkGenerator extends ChunkGenerator {
    public static final Codec<ParameterizedTerrainChunkGenerator> CODEC = TerrainParameterCodecs.guarded(
            RecordCodecBuilder.<ParameterizedTerrainChunkGenerator>create(i -> i.group(
                    TerrainParameterCodecs.PARAMETERS.fieldOf("parameters").forGetter(ParameterizedTerrainChunkGenerator::parameters),
                    FixedBiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> (FixedBiomeSource) g.getBiomeSource()),
                    Codec.INT.fieldOf("column_min_y").forGetter(ParameterizedTerrainChunkGenerator::getMinY),
                    Codec.INT.fieldOf("column_height").forGetter(ParameterizedTerrainChunkGenerator::getGenDepth)
            ).apply(i, ParameterizedTerrainChunkGenerator::new))).flatXmap(
                    com.mojang.serialization.DataResult::success,
                    g -> g.derivedPreview ? com.mojang.serialization.DataResult.error(() -> "Composed preview source needs its explicit profile manifest")
                            : com.mojang.serialization.DataResult.success(g));
    private final TerrainParameters parameters;
    private final ParametricTerrainSampler sampler;
    private final TrailNetwork.SampleSource sampleSource;
    private final boolean derivedPreview;
    private final HabitatOverlay habitats;
    private final LandscapeOverlay landscape;
    private final NaturalGeneration natural;
    private final WildernessGeneration wilderness;
    private final CaveExpansionGeneration caveExpansion;
    private final AlexEcologyGeneration alexEcology;
    private final IslandSurfaceGeneration islandSurface;
    private final String islandIdentity;
    private final UpperShaftOpeningGeneration upperShaftOpening;
    private final LivingAbyssGeneration livingAbyss;
    private final int columnMinY;
    private final int columnHeight;
    /** Activated exclusively by a v15 cube. Exact immutable parent results, with a 32m XZ halo for projected mouths. */
    private static final class PreparedParentCube {
        static final int WIDTH=80,SIZE=WIDTH*WIDTH*16;
        final TerrainSample[] geometry=new TerrainSample[SIZE];final BlockState[] decorated=new BlockState[SIZE];
        final int[] geometryStamp=new int[SIZE],decoratedStamp=new int[SIZE];
        int x,y,z,epoch;boolean active;
        void begin(int x,int y,int z){this.x=x-32;this.y=y;this.z=z-32;if(++epoch==0){java.util.Arrays.fill(geometryStamp,0);java.util.Arrays.fill(decoratedStamp,0);epoch=1;}active=true;}
        int index(int x,int y,int z){long dx=(long)x-this.x,dy=(long)y-this.y,dz=(long)z-this.z;return active&&dx>=0&&dx<WIDTH&&dy>=0&&dy<16&&dz>=0&&dz<WIDTH?(int)(dx+WIDTH*(dz+WIDTH*dy)):-1;}
    }
    private final ThreadLocal<PreparedParentCube> preparedParent=new ThreadLocal<>();
    void beginPreparedParentCube(int x,int y,int z){var cube=preparedParent.get();if(cube==null){cube=new PreparedParentCube();preparedParent.set(cube);}cube.begin(x,y,z);}
    void finishPreparedParentCube(){var cube=preparedParent.get();if(cube!=null)cube.active=false;}
    private TerrainSample sourceGeometry(int x,int y,int z){var cube=preparedParent.get();int slot=cube==null?-1:cube.index(x,y,z);
        if(slot<0)return sampleSource.sample(x,y,z);if(cube.geometryStamp[slot]==cube.epoch)return cube.geometry[slot];
        var value=sampleSource.sample(x,y,z);cube.geometry[slot]=value;cube.geometryStamp[slot]=cube.epoch;return value;}

    public ParameterizedTerrainChunkGenerator(TerrainParameters parameters, FixedBiomeSource biomeSource,
                                             int columnMinY, int columnHeight) {
        this(parameters, biomeSource, columnMinY, columnHeight, null);
    }
    public ParameterizedTerrainChunkGenerator(TerrainParameters parameters, FixedBiomeSource biomeSource,
                                             int columnMinY, int columnHeight, TrailNetwork.SampleSource composedSource) {
        this(parameters, biomeSource, columnMinY, columnHeight, composedSource, null);
    }
    public ParameterizedTerrainChunkGenerator(TerrainParameters parameters, FixedBiomeSource biomeSource,
                                             int columnMinY, int columnHeight, TrailNetwork.SampleSource composedSource,
                                             HabitatOverlay habitats) {
        this(parameters, biomeSource, columnMinY, columnHeight, composedSource, habitats, null);
    }
    public ParameterizedTerrainChunkGenerator(TerrainParameters parameters, FixedBiomeSource biomeSource,
                                             int columnMinY, int columnHeight, TrailNetwork.SampleSource composedSource,
                                             HabitatOverlay habitats, LandscapeOverlay landscape) {
        this(parameters,biomeSource,columnMinY,columnHeight,composedSource,habitats,landscape,null);
    }
    public ParameterizedTerrainChunkGenerator(TerrainParameters parameters, FixedBiomeSource biomeSource,
                                             int columnMinY, int columnHeight, TrailNetwork.SampleSource composedSource,
                                             HabitatOverlay habitats, LandscapeOverlay landscape,NaturalGeneration natural) {
        this(parameters,biomeSource,columnMinY,columnHeight,composedSource,habitats,landscape,natural,null);
    }
    public ParameterizedTerrainChunkGenerator(TerrainParameters parameters, FixedBiomeSource biomeSource,
                                             int columnMinY, int columnHeight, TrailNetwork.SampleSource composedSource,
                                             HabitatOverlay habitats, LandscapeOverlay landscape,NaturalGeneration natural,WildernessGeneration wilderness) {
        this(parameters,biomeSource,columnMinY,columnHeight,composedSource,habitats,landscape,natural,wilderness,null);
    }
    public ParameterizedTerrainChunkGenerator(TerrainParameters parameters, FixedBiomeSource biomeSource,
                                             int columnMinY, int columnHeight, TrailNetwork.SampleSource composedSource,
                                             HabitatOverlay habitats, LandscapeOverlay landscape,NaturalGeneration natural,WildernessGeneration wilderness,CaveExpansionGeneration caveExpansion) {
        this(parameters,biomeSource,columnMinY,columnHeight,composedSource,habitats,landscape,natural,wilderness,caveExpansion,null,null);
    }
    public ParameterizedTerrainChunkGenerator(TerrainParameters parameters, FixedBiomeSource biomeSource,
                                             int columnMinY,int columnHeight,TrailNetwork.SampleSource composedSource,
                                             HabitatOverlay habitats,LandscapeOverlay landscape,NaturalGeneration natural,WildernessGeneration wilderness,CaveExpansionGeneration caveExpansion,
                                             com.google.gson.JsonObject alexRecipe,dev.madeinabyss.kernel.terrain.natural.NaturalProtection protection) {
        this(parameters,biomeSource,columnMinY,columnHeight,composedSource,habitats,landscape,natural,wilderness,caveExpansion,alexRecipe,protection,null,"");
    }
    public ParameterizedTerrainChunkGenerator(TerrainParameters parameters, FixedBiomeSource biomeSource,
                                             int columnMinY,int columnHeight,TrailNetwork.SampleSource composedSource,
                                             HabitatOverlay habitats,LandscapeOverlay landscape,NaturalGeneration natural,WildernessGeneration wilderness,CaveExpansionGeneration caveExpansion,
                                             com.google.gson.JsonObject alexRecipe,dev.madeinabyss.kernel.terrain.natural.NaturalProtection protection,
                                             IslandSurfaceGeneration islandSurface,String islandIdentity) {
        this(parameters,biomeSource,columnMinY,columnHeight,composedSource,habitats,landscape,natural,wilderness,caveExpansion,
                alexRecipe,protection,islandSurface,islandIdentity,null);
    }
    public ParameterizedTerrainChunkGenerator(TerrainParameters parameters, FixedBiomeSource biomeSource,
                                             int columnMinY,int columnHeight,TrailNetwork.SampleSource composedSource,
                                             HabitatOverlay habitats,LandscapeOverlay landscape,NaturalGeneration natural,WildernessGeneration wilderness,CaveExpansionGeneration caveExpansion,
                                             com.google.gson.JsonObject alexRecipe,dev.madeinabyss.kernel.terrain.natural.NaturalProtection protection,
                                             IslandSurfaceGeneration islandSurface,String islandIdentity,UpperShaftOpeningGeneration upperShaftOpening) {
        this(parameters,biomeSource,columnMinY,columnHeight,composedSource,habitats,landscape,natural,wilderness,caveExpansion,
                alexRecipe,protection,islandSurface,islandIdentity,upperShaftOpening,null);
    }
    public ParameterizedTerrainChunkGenerator(TerrainParameters parameters, FixedBiomeSource biomeSource,
                                             int columnMinY,int columnHeight,TrailNetwork.SampleSource composedSource,
                                             HabitatOverlay habitats,LandscapeOverlay landscape,NaturalGeneration natural,WildernessGeneration wilderness,CaveExpansionGeneration caveExpansion,
                                             com.google.gson.JsonObject alexRecipe,dev.madeinabyss.kernel.terrain.natural.NaturalProtection protection,
                                             IslandSurfaceGeneration islandSurface,String islandIdentity,UpperShaftOpeningGeneration upperShaftOpening,LivingAbyssGeneration livingAbyss) {
        super(Objects.requireNonNull(biomeSource), ignored -> BiomeGenerationSettings.EMPTY);
        this.parameters = Objects.requireNonNull(parameters);
        if (columnMinY != -64 || columnHeight != 384) throw new IllegalArgumentException("first slice supports only native -64/384 column envelope");
        if (parameters.cavity().declaredLowerBoundY() > columnMinY) throw new IllegalArgumentException("ordinary column extends below declared terrain");
        if (parameters.surface().terraceY() + parameters.surface().mountainHeight()
                + parameters.noise().surfaceAmplitude() >= (long) columnMinY + columnHeight
                || parameters.surface().oceanLevelY() >= (long) columnMinY + columnHeight) {
            throw new IllegalArgumentException("surface exceeds native first-slice column envelope");
        }
        this.columnMinY = columnMinY;
        this.columnHeight = columnHeight;
        sampler = new ParametricTerrainSampler(parameters);
        sampleSource = composedSource == null ? sampler::sample : composedSource;
        this.habitats = habitats;
        this.landscape = landscape;
        this.natural = natural;
        this.wilderness = wilderness;
        this.caveExpansion=caveExpansion;
        this.islandSurface=islandSurface;
        this.islandIdentity=Objects.requireNonNull(islandIdentity);
        this.upperShaftOpening=upperShaftOpening;
        this.livingAbyss=livingAbyss;
        if(islandSurface!=null && islandSurface.maximumSurfaceY()>=320)
            throw new IllegalArgumentException("Island surface exceeds native column envelope");
        this.alexEcology=alexRecipe==null?null:new AlexEcologyGeneration(alexRecipe,this::sampleBaseBlock,caveExpansion,Objects.requireNonNull(protection));
        derivedPreview = composedSource != null || habitats != null || landscape != null || natural != null || islandSurface != null || upperShaftOpening != null;
    }
    public TerrainParameters parameters() { return parameters; }
    public WildernessGeneration wilderness(){return wilderness;}
    public CaveExpansionGeneration caveExpansion(){return caveExpansion;}
    public AlexEcologyGeneration alexEcology(){return alexEcology;}
    public IslandSurfaceGeneration islandSurface(){return islandSurface;}
    public UpperShaftOpeningGeneration upperShaftOpening(){return upperShaftOpening;}
    public LivingAbyssGeneration livingAbyss(){return livingAbyss;}
    /** Procedural literal AIR only; callers must veto saved whole-cube overrides first. */
    public boolean uniformAirCube(int cx,int cy,int cz){return livingAbyss!=null&&livingAbyss.uniformAirCube(cx,cy,cz);}
    public String islandIdentity(){return islandIdentity;}
    public int knownLowerY(int x,int z){return livingAbyss!=null?livingAbyss.knownLowerY(x,z):islandSurface==null?parameters.cavity().declaredLowerBoundY():islandSurface.knownLowerY(x,z);}
    public int minimumKnownY(){return islandSurface==null?parameters.cavity().declaredLowerBoundY():islandSurface.minimumKnownY();}
    public int maximumSurfaceY(){return islandSurface==null?(int)Math.ceil(parameters.surface().terraceY()+parameters.surface().mountainHeight()+parameters.noise().surfaceAmplitude()):islandSurface.maximumSurfaceY();}
    public boolean supportsCube(CubeKey key){
        Objects.requireNonNull(key);
        int x=cubeStart(key.x()),y=cubeStart(key.y()),z=cubeStart(key.z());
        if(livingAbyss!=null){
            int cx=x>0?x:x+15<0?x+15:0,cz=z>0?z:z+15<0?z+15:0;
            return y>=knownLowerY(cx,cz);
        }
        return islandSurface==null?y>=parameters.cavity().declaredLowerBoundY():islandSurface.supportsCube(key);
    }
    /** Conservative availability over every inclusive column, not a planning envelope. */
    public int minKnownYForBox(int minX,int minZ,int maxX,int maxZ){
        if(minX>maxX||minZ>maxZ)throw new IllegalArgumentException("Inverted spatial domain box");
        if(islandSurface==null)return parameters.cavity().declaredLowerBoundY();
        int x=minX>0?minX:maxX<0?maxX:0;
        int z=minZ>0?minZ:maxZ<0?maxZ:0;
        return knownLowerY(x,z);
    }
    @Override protected Codec<? extends ChunkGenerator> codec() { return CODEC; }
    @Override public int getMinY() { return columnMinY; }
    @Override public int getGenDepth() { return columnHeight; }
    @Override public int getSeaLevel() { return (int) Math.floor(parameters.surface().oceanLevelY()); }
    public BlockState sampleBlock(int x, int y, int z) {
        if(livingAbyss!=null)return livingAbyss.sample(x,y,z);
        if(y<knownLowerY(x,z))throw new TerrainUnavailableException(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER,new BlockCoordinate(x,y,z));
        if(upperShaftOpening!=null&&upperShaftOpening.affects(x,y,z)){
            if(upperShaftOpening.carves(x,y,z)){
                // A carve overrides every parent material, including plants/fluids/trail fills.
                // Do not evaluate discarded ecology, but the approved island remains authoritative.
                if(islandSurface!=null){var island=islandSurface.sampleIfOwned(x,y,z);if(island!=null)return island;}
                return Blocks.AIR.defaultBlockState();
            }
            // Compose against the unchanged parent first; support probes bypass this modifier.
            // Exterior ocean below the old parent domain belongs exclusively to the island.
            if(y>=parameters.cavity().declaredLowerBoundY()){
                var changed=upperShaftOpening.apply(x,y,z,sampleParentBlock(x,y,z),this::sampleParentBlock);
                if(islandSurface!=null){var island=islandSurface.sampleIfOwned(x,y,z);if(island!=null)return island;}
                return changed;
            }
        }
        if(islandSurface!=null){var override=islandSurface.sampleIfOwned(x,y,z);if(override!=null)return override;}
        return sampleParentBlock(x,y,z);
    }
    /** Undecorated immutable parent, after v14 opening. Water stays explicit for plan probes. */
    public TerrainSample sampleGeometry(int x,int y,int z) {
        if(y<knownLowerY(x,z))return new TerrainSample.Unavailable(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER);
        if(islandSurface!=null){
            var state=islandSurface.sampleIfOwned(x,y,z);
            if(state!=null)return new TerrainSample.Known(state.isAir()?TerrainMaterial.AIR:!state.getFluidState().isEmpty()?TerrainMaterial.WATER:
                    state.is(Blocks.GRASS_BLOCK)?TerrainMaterial.GRASS:state.is(Blocks.DIRT)?TerrainMaterial.SOIL:TerrainMaterial.ROCK);
        }
        if(upperShaftOpening!=null&&upperShaftOpening.carves(x,y,z))return new TerrainSample.Known(TerrainMaterial.AIR);
        return sourceGeometry(x,y,z);
    }
    private BlockState sampleParentBlock(int x,int y,int z){
        var cube=preparedParent.get();int slot=cube==null?-1:cube.index(x,y,z);if(slot>=0&&cube.decoratedStamp[slot]==cube.epoch)return cube.decorated[slot];
        var base=sampleBaseBlock(x,y,z);
        var value=alexEcology==null?base:alexEcology.sample(x,y,z,base);
        if(slot>=0){cube.decorated[slot]=value;cube.decoratedStamp[slot]=cube.epoch;}return value;
    }
    private BlockState sampleBaseBlock(int x,int y,int z) {
        var sampled = sourceGeometry(x, y, z);
        if (sampled instanceof TerrainSample.Unavailable unavailable) {
            throw new TerrainUnavailableException(unavailable.reason(), new BlockCoordinate(x, y, z));
        }
        if (landscape != null) {
            var decoration = landscape.sample(x,y,z);
            if (decoration.isPresent()) return HabitatBlockStates.state(decoration.get());
        }
        if (habitats != null) {
            var decoration = habitats.sample(x,y,z);
            if (decoration.isPresent()) return HabitatBlockStates.state(decoration.get());
        }
        if(caveExpansion!=null){
            var decoration=caveExpansion.decoration(x,y,z,sampled);if(decoration.isPresent())return decoration.get();
        }else if(wilderness!=null){
            var decoration=wilderness.decoration(x,y,z,sampled);
            if(decoration.isPresent())return decoration.get();
        }else if(natural!=null) {
            var decoration=natural.decoration(x,y,z,sampled);
            if(decoration.isPresent())return NaturalBlockStates.state(decoration.get());
        }
        return switch (((TerrainSample.Known) sampled).material()) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case ROCK -> Blocks.STONE.defaultBlockState();
            case SOIL -> Blocks.DIRT.defaultBlockState();
            case GRASS -> Blocks.GRASS_BLOCK.defaultBlockState();
            case WATER -> Blocks.WATER.defaultBlockState();
        };
    }

    /** Derived-only continuation for a trusted cave binding in delegated, unmodified territory.
     * Deliberately duplicates the eligible Cave branch; the original full-state oracle
     * stays independent. Never repeat the top-level composed sampler or enter ray caches. */
    BlockState continueCaveSampleForDerivedOpacity(int x,int y,int z,TerrainSample composed){
        if(composed instanceof TerrainSample.Unavailable unavailable)
            throw new TerrainUnavailableException(unavailable.reason(),new BlockCoordinate(x,y,z));
        if(caveExpansion==null)throw new IllegalStateException("Cave continuation requires a bound Cave source");
        var decoration=caveExpansion.decoration(x,y,z,composed);
        BlockState base;
        if(decoration.isPresent())base=decoration.get();
        else base=switch(((TerrainSample.Known)composed).material()){
            case AIR->Blocks.AIR.defaultBlockState();
            case ROCK->Blocks.STONE.defaultBlockState();
            case SOIL->Blocks.DIRT.defaultBlockState();
            case GRASS->Blocks.GRASS_BLOCK.defaultBlockState();
            case WATER->Blocks.WATER.defaultBlockState();
        };
        return alexEcology==null?base:alexEcology.sample(x,y,z,base);
    }

    /** Pure factory. Store-first admission owns preservation of already visited regions. */
    public CubeSnapshot generateCube(CubeKey key) {
        Objects.requireNonNull(key);
        int startX = cubeStart(key.x()), startY = cubeStart(key.y()), startZ = cubeStart(key.z());
        if (!supportsCube(key)) {
            throw new TerrainUnavailableException(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER,
                    new BlockCoordinate(startX, startY, startZ));
        }
        int[] states = new int[CubeSnapshot.BLOCK_COUNT];
        try {
        if(livingAbyss!=null&&livingAbyss.prepareCube(startX,startY,startZ)){
            java.util.Arrays.fill(states,Block.getId(Blocks.AIR.defaultBlockState()));return new CubeSnapshot(key,states);
        }
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            states[(y << 8) | (z << 4) | x] = Block.getId(sampleBlock(startX + x, startY + y, startZ + z));
        }
        return new CubeSnapshot(key, states);
        } finally {if(livingAbyss!=null)livingAbyss.finishCube();}
    }

    private static int cubeStart(int section) {
        long start = (long) section * 16;
        if (start < Integer.MIN_VALUE || start + 15 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("cube lies outside signed global block coordinates");
        }
        return (int) start;
    }

    private void requireColumnEnvelope(LevelHeightAccessor height) {
        Objects.requireNonNull(height);
        if (height.getMinBuildHeight() != columnMinY || height.getHeight() != columnHeight) {
            throw new IllegalArgumentException("unsupported native column envelope");
        }
    }
    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState state,
                                                                  StructureManager structures, ChunkAccess chunk) {
        requireColumnEnvelope(chunk);
        int startX = cubeStart(chunk.getPos().x), startZ = cubeStart(chunk.getPos().z);
        var oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        var worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        var position = new BlockPos.MutableBlockPos();
        // Same synchronous native fill/update contract as the actual 1.20.1 FlatLevelSource.
        // No decoration/carver callback is permitted to replace this geological source.
        for (int sectionY = columnMinY; sectionY < columnMinY + columnHeight; sectionY+=16) {
            try {
                boolean uniformAir=livingAbyss!=null&&livingAbyss.prepareCube(startX,sectionY,startZ);
                for(int y=sectionY;y<sectionY+16;y++) {
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        BlockState block = uniformAir?Blocks.AIR.defaultBlockState():sampleBlock(startX + x, y, startZ + z);
                        chunk.setBlockState(position.set(startX + x, y, startZ + z), block, false);
                        oceanFloor.update(x, y, z, block);
                        worldSurface.update(x, y, z, block);
                    }
                }
            }finally{if(livingAbyss!=null)livingAbyss.finishCube();}
        }
        return CompletableFuture.completedFuture(chunk);
    }
    @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState state) {
        requireColumnEnvelope(height);
        BlockState[] blocks = new BlockState[columnHeight];
        for (int offset = 0; offset < blocks.length; offset++) blocks[offset] = sampleBlock(x, columnMinY + offset, z);
        return new NoiseColumn(columnMinY, blocks);
    }
    @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor height, RandomState state) {
        requireColumnEnvelope(height);
        Objects.requireNonNull(type);
        for (int y = columnMinY + columnHeight - 1; y >= columnMinY; y--) {
            if (type.isOpaque().test(sampleBlock(x, y, z))) return y + 1;
        }
        return columnMinY;
    }
    @Override public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> sets, RandomState state, long seed) {
        return ChunkGeneratorStructureState.createForFlat(state, seed, biomeSource, Stream.empty());
    }
    @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState state, BiomeManager biomes,
                                       StructureManager structures, ChunkAccess chunk, GenerationStep.Carving stage) { }
    @Override public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState state, ChunkAccess chunk) { }
    @Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) { }
    @Override public void spawnOriginalMobs(WorldGenRegion region) { }
    @Override public void addDebugScreenInfo(List<String> lines, RandomState state, BlockPos pos) {
        lines.add("MIA parameterized first-layer reconstruction; declared lower Y=" + parameters.cavity().declaredLowerBoundY());
    }
}
