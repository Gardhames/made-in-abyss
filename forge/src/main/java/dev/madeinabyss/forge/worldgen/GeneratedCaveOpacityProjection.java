package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.natural.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/** Scalar-only projection AFTER the exact approved Cave composition.
 * No representative state escapes: AIR, WATER, partial reachable scalars and final
 * modifiers retain the same-sample state continuation or the original generator. */
public final class GeneratedCaveOpacityProjection {
    private static final String CAVE="91f87867f1ab1ed693723175e41733f65e6f655d3e6024c32156e55ef3b42812";
    private static final String ALEX="d6e13e295aa4907a0aa2082d6922821354569759862d02c82eb756be4cd15813";
    private static final String ISLAND="01c3c884b5bdba301d3dfb0b2c59d88e10a473cce82bf18706110a898bd99463";
    private static final String OPENING="c217cd95b585e768a4fc65ecb9f56245c672bdbd53b3d19780f7ca957b41e422";
    private final ParameterizedTerrainChunkGenerator generator;
    private final long alexSeed;
    private final boolean graph;
    private final int rock,rockWithoutCoprolith,soil,grass;

    public GeneratedCaveOpacityProjection(GeneratedPreviewProfile profile,IntUnaryOperator opacity) {
        java.util.Objects.requireNonNull(profile);java.util.Objects.requireNonNull(opacity);
        generator=profile.generator();alexSeed=profile.derivedAlexEcologySeed();
        // Profile construction binds source=CaveExpansion::sample. Raw/custom generator
        // constructors never receive this proof. Literal code pairs deliberately do not
        // follow future construction bridges automatically.
        graph=GeneratedDeepAirCellCertificate.APPROVED_FINGERPRINT.equals(profile.fingerprint())
            &&generator.caveExpansion()!=null&&generator.alexEcology()!=null&&generator.wilderness()==null
            &&generator.islandSurface()!=null&&generator.upperShaftOpening()!=null
            &&CAVE.equals(CaveExpansionGeneration.algorithmSha256())&&ALEX.equals(AlexEcologyGeneration.algorithmSha256())
            &&ISLAND.equals(IslandSurfaceGeneration.actualAlgorithmSha256())&&OPENING.equals(UpperShaftOpeningGeneration.actualAlgorithmSha256());
        if(!graph){rock=rockWithoutCoprolith=soil=grass=-1;return;}

        // This exact fingerprint binds both actual external palettes. Resolve their
        // registered states, then validate membership against the owned instances.
        var alex=new AlexEcologyBlockStates(true);
        var moss=alex.state(AlexEcologyOverlay.Decoration.MOSS);
        var mud=alex.state(AlexEcologyOverlay.Decoration.MUD);
        var limestone=alex.state(AlexEcologyOverlay.Decoration.LIMESTONE);
        var galena=alex.state(AlexEcologyOverlay.Decoration.GALENA);
        var coprolith=alex.state(AlexEcologyOverlay.Decoration.COPROLITH);
        var actualAlex=generator.alexEcology().palette();var actualCave=generator.caveExpansion().palette();
        boolean palettes=actualAlex.containsAll(java.util.List.of(moss,mud,limestone,galena,coprolith))
            &&actualCave.containsAll(java.util.List.of(Blocks.CALCITE.defaultBlockState(),Blocks.DRIPSTONE_BLOCK.defaultBlockState(),moss,limestone));
        if(!palettes){rock=rockWithoutCoprolith=soil=grass=-1;return;}
        soil=common(opacity,Blocks.DIRT.defaultBlockState(),moss);
        grass=common(opacity,Blocks.GRASS_BLOCK.defaultBlockState(),moss);
        // Cave's ROCK dressing reaches calcite/dripstone/moss or limestone/STONE.
        // Its waterAt already evaluated the SAME pure pool decision against water::sample;
        // if that decision were WATER, this post-composed material would not be ROCK.
        // All pre-Alex states must be solid under the actual classifier's early branches.
        boolean solids=solidBeforeAlex(Blocks.STONE.defaultBlockState())&&solidBeforeAlex(Blocks.CALCITE.defaultBlockState())
            &&solidBeforeAlex(Blocks.DRIPSTONE_BLOCK.defaultBlockState())&&solidBeforeAlex(moss)&&solidBeforeAlex(limestone);
        rockWithoutCoprolith=solids?common(opacity,Blocks.STONE.defaultBlockState(),Blocks.CALCITE.defaultBlockState(),
            Blocks.DRIPSTONE_BLOCK.defaultBlockState(),moss,limestone,mud,galena):-1;
        int cop=opacity.applyAsInt(Block.getId(coprolith));
        rock=rockWithoutCoprolith>=0&&cop==rockWithoutCoprolith?rockWithoutCoprolith:-1;
    }
    private static boolean solidBeforeAlex(BlockState state){
        if(state.isAir()||!state.getFluidState().isEmpty())return false;
        return state.is(Blocks.MOSS_BLOCK)||state.is(Blocks.GRASS_BLOCK)||state.is(BlockTags.DIRT)
            ||state.is(Blocks.STONE)||state.is(Blocks.CALCITE)||state.is(Blocks.DRIPSTONE_BLOCK)
            ||WildernessBlockStates.material(state)==TerrainMaterial.ROCK;
    }
    private static int common(IntUnaryOperator opacity,BlockState... states){
        int result=-1;
        for(var state:states){int value=opacity.applyAsInt(Block.getId(state));if(value<0||value>15||result>=0&&result!=value)return -1;result=value;}
        return result;
    }
    public boolean eligible(){return graph&&(rockWithoutCoprolith>=0||soil>=0||grass>=0);}
    /** Refuse before sampling the composed cave when final modifiers/saved authority apply. */
    boolean mayEnter(int x,int y,int z,Set<CubeKey> savedKeys){
        java.util.Objects.requireNonNull(savedKeys);
        if(!eligible()||y<generator.knownLowerY(x,z))return false;
        double r2=(double)x*x+(double)z*z;
        // Exact sampleIfOwned delegation predicate, including the lower ocean domain.
        if(!(r2<250000||(r2<921600&&y<0))||generator.upperShaftOpening().affects(x,y,z))return false;
        return savedKeys.isEmpty()||!savedKeys.contains(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)));
    }
    /** -1 means continue the already composed sample through the real state decorators. */
    int opacityFor(TerrainMaterial material,int x,int y,int z){
        if(!eligible())return -1;
        if(material==TerrainMaterial.SOIL)return soil;
        if(material==TerrainMaterial.GRASS)return grass;
        if(material!=TerrainMaterial.ROCK)return -1; // Broad AIR and WATER ALWAYS continue.
        if(rock>=0)return rock;
        return rockWithoutCoprolith>=0&&!coprolithCandidate(alexSeed,x,y,z)?rockWithoutCoprolith:-1;
    }
    /** Necessary, not sufficient, original surface-ore condition. Positive candidates
     * retain the full Cave/Alex continuation; neighbor/protection/feature guards remain there. */
    static boolean coprolithCandidate(long seed,int x,int y,int z){
        long cx=Math.floorDiv(x,4),cy=Math.floorDiv(y,4),cz=Math.floorDiv(z,4);
        double patch=random(seed,cx,cy,cz,30),fine=random(seed,x,y,z,31);
        if(fine>.72||patch>.26)return false;
        double species=random(seed,cx,cy,cz,32);return species>=.78&&species<.91;
    }
    private static double random(long seed,long x,long y,long z,long salt){long bits=mix(seed^mix(x)^Long.rotateLeft(mix(y),21)^Long.rotateLeft(mix(z),42)^mix(salt));return(bits>>>11)*0x1.0p-53;}
    private static long mix(long n){n=(n^(n>>>30))*0xbf58476d1ce4e5b9L;n=(n^(n>>>27))*0x94d049bb133111ebL;return n^(n>>>31);}

    /** Derived-cache token only: no canonical recipe, visited namespace or state identity. */
    public static String proofCodeIdentity(){return ProofIdentity.VALUE;}
    private static final class ProofIdentity {
        private static final String VALUE=TerrainLodDiskCache.codeIdentity(GeneratedCaveOpacityProjection.class,GeneratedPreviewProfile.class,
            ParameterizedTerrainChunkGenerator.class,TerrainParameters.class,TerrainSample.class,TerrainMaterial.class,
            CaveExpansionGeneration.class,CaveNetworkParameters.class,CaveNetworkTerrainSampler.class,SupportedCaveTerrainSampler.class,
            RouteVoxelIndex.class,CompiledTerrainSampler.class,CliffTerrainSampler.class,RetainedWaterSampler.class,
            CaveInteriorOverlay.class,CaveInteriorBlockStates.class,DenseEcologyOverlay.class,EcologyMaskFields.class,
            WildernessBlockStates.class,NaturalBlockStates.class,NaturalGeneration.class,
            AlexEcologyGeneration.class,AlexEcologyOverlay.class,AlexEcologyBlockStates.class,
            IslandSurfaceGeneration.class,IslandHeightAsset.class,UpperShaftOpeningGeneration.class,
            dev.madeinabyss.kernel.light.SkyColumnKey.class,dev.madeinabyss.kernel.concurrent.BoundedReuseCache.class,
            dev.madeinabyss.kernel.terrain.trail.IndexedTrailOverlay.class,TerrainLodDiskCache.class);
    }
}
