package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.living.*;
import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.function.IntUnaryOperator;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Derived scalar/material view of the frozen Stage A composition. Never supplies a BlockState.
 * Saved cubes, availability and physical retirement are enforced by GeneratedSkySource first.
 * All authored/reserved solid points retain the independent full-state oracle. */
public final class Generated155DerivedProjection {
    private static final String FINGERPRINT="fc361b11eb1d175bee064e4bf44eaefe0f0e83e9607e7ed9a5983e6c03136a6d";
    private static final String ALGORITHM="9e903c73b7e2a22ff7b1f2fb8495a348913d00edae5cb018fb2021d7b10fbc82";
    private final LivingAbyssGeneration living;
    private final LivingAbyssRevision155 revision;
    private final LivingAbyssReservations reserved,protectedVoxels;
    private final CompiledTerrainSampler legacyBare;
    private final ParameterizedTerrainChunkGenerator parent;
    private final int solidOpacity,airOpacity,tipOpacity;
    private final double limit;
    private final Map<String,net.minecraft.world.level.block.state.BlockState> rocks;
    private final Generated155ExactView exact;
    private final Generated155BDerivedProjection stageB;
    private Generated155DerivedProjection(LivingAbyssGeneration living,LivingAbyssReservations reserved,
            LivingAbyssReservations protectedVoxels,CompiledTerrainSampler legacyBare,ParameterizedTerrainChunkGenerator parent,
            int solidOpacity,int airOpacity,int tipOpacity,Map<String,net.minecraft.world.level.block.state.BlockState> rocks,Generated155ExactView exact){
        this.living=living;this.revision=living.revision155();this.reserved=reserved;this.protectedVoxels=protectedVoxels;
        this.legacyBare=legacyBare;this.parent=parent;this.solidOpacity=solidOpacity;this.airOpacity=airOpacity;this.tipOpacity=tipOpacity;
        limit=Math.max(960,revision.maximumRadius());
        this.rocks=rocks;this.exact=exact;this.stageB=null;
    }
    private Generated155DerivedProjection(Generated155BDerivedProjection b){stageB=b;living=null;revision=null;reserved=null;protectedVoxels=null;
        legacyBare=null;parent=null;solidOpacity=airOpacity=tipOpacity=0;limit=0;rocks=null;exact=null;}
    static Generated155DerivedProjection bind(GeneratedPreviewProfile profile,IntUnaryOperator opacity){
        var b=Generated155BDerivedProjection.bind(profile,opacity);if(b!=null)return new Generated155DerivedProjection(b);
        if(profile==null||!FINGERPRINT.equals(profile.fingerprint())||!LivingAbyssAlgorithmCompatibility.provenStageA(LivingAbyssGeneration.algorithmSha256()))return null;
        var living=profile.generator().livingAbyss();if(living==null||living.revision155()==null)return null;
        try{
            // Read the actual immutable guards once. Adding accessors to the frozen generator
            // would change its persisted algorithm closure. No field or generator cache is mutated.
            // A different field type/module/implementation refuses this optional projection.
            var lookup=MethodHandles.privateLookupIn(LivingAbyssGeneration.class,MethodHandles.lookup());
            var reserved=(LivingAbyssReservations)lookup.findVarHandle(LivingAbyssGeneration.class,"reservations",LivingAbyssReservations.class).get(living);
            var protectedVoxels=(LivingAbyssReservations)lookup.findVarHandle(LivingAbyssGeneration.class,"protectedReservations",LivingAbyssReservations.class).get(living);
            var bare=(CompiledTerrainSampler)lookup.findVarHandle(LivingAbyssGeneration.class,"legacyBare",CompiledTerrainSampler.class).get(living);
            var parent=(ParameterizedTerrainChunkGenerator)lookup.findVarHandle(LivingAbyssGeneration.class,"parent",ParameterizedTerrainChunkGenerator.class).get(living);
            @SuppressWarnings("unchecked") var rocks=(Map<String,net.minecraft.world.level.block.state.BlockState>)lookup.findVarHandle(LivingAbyssGeneration.class,"rocks",Map.class).get(living);
            int solid=-1;
            for(var state:rocks.values()){int value=opacity.applyAsInt(Block.getId(state));if(solid>=0&&solid!=value)return null;solid=value;}
            for(var block:List.of(Blocks.GRASS_BLOCK,Blocks.MOSS_BLOCK,Blocks.COARSE_DIRT,Blocks.DIRT,Blocks.ROOTED_DIRT))
                if(opacity.applyAsInt(Block.getId(block.defaultBlockState()))!=solid)return null;
            int tip=-1;
            for(var state:LivingAbyssBlockStates.palette())if(state.is(Blocks.POINTED_DRIPSTONE)){
                int value=opacity.applyAsInt(Block.getId(state));if(tip>=0&&tip!=value)return null;tip=value;
            }
            if(solid<0||tip<0||reserved==null||protectedVoxels==null||bare==null||parent==null)return null;
            var exact=Boolean.parseBoolean(System.getProperty("mia.generated.exact155View","false"))?Generated155ExactView.create(profile):null;
            return new Generated155DerivedProjection(living,reserved,protectedVoxels,bare,parent,solid,
                opacity.applyAsInt(Block.getId(Blocks.AIR.defaultBlockState())),tip,rocks,exact);
        }catch(ReflectiveOperationException|RuntimeException refused){return null;}
    }
    private boolean owned(int x,int y,int z){return y<0&&Math.hypot(x,z)<limit;}
    private boolean floor(int x,int y,int z){return y<=living.recipeValue().domain().temporaryFloorY()+8&&y<=living.geometry().floorTop(x,z);}
    private boolean originalRequired(int x,int y,int z){
        return y>=parent.parameters().cavity().declaredLowerBoundY()
            &&(protectedVoxels.contains(x,y,z)||reserved.contains(x,y,z)&&Math.hypot(x,z)>=legacyBare.cavityRadius(x,y,z));
    }
    boolean exactViewEnabled(){return exact!=null;}
    private int sample(int x,int y,int z){return exact==null?revision.sample(x,y,z):exact.sample(x,y,z);}
    /** -1 retains the original full-state sample. Every reachable natural solid shares the validated scalar. */
    int opacity(int x,int y,int z){
        if(stageB!=null)return stageB.opacity(x,y,z);
        if(!owned(x,y,z))return -1;if(floor(x,y,z))return solidOpacity;
        int code=sample(x,y,z);
        if(code==LivingAbyssRevision155.AIR||code==LivingAbyssRevision155.HALL)return airOpacity;
        if(originalRequired(x,y,z))return -1;
        return code>=7?tipOpacity:solidOpacity;
    }
    /** Null retains material(Block.getId(original full state)); lithology never splits a ROCK run. */
    TerrainMaterial material(int x,int y,int z){
        if(stageB!=null)return stageB.material(x,y,z);
        if(!owned(x,y,z))return null;if(floor(x,y,z))return TerrainMaterial.ROCK;
        int code=sample(x,y,z);
        if(code==LivingAbyssRevision155.AIR||code==LivingAbyssRevision155.HALL)return TerrainMaterial.AIR;
        if(originalRequired(x,y,z))return null;
        if(code>=7)return TerrainMaterial.AIR;
        if(code==LivingAbyssRevision155.LEDGE&&Math.hypot(x,z)<LivingAbyssMath155.inner(-((double)y))){
            boolean above=LivingAbyssRevision155.isSolid(sample(x,y+1,z));
            if(!above){
                long seed=living.recipeValue().seed();
                var top=LivingAbyssSurfaceMaterials.top(LivingAbyssSurfaceMaterials.patch(seed,x,z),LivingAbyssMath155.mixUnit(seed^x^Long.rotateLeft(z,21)));
                if(top==LivingAbyssGeometry.Kind.GRASS||top==LivingAbyssGeometry.Kind.MOSS)return TerrainMaterial.GRASS;
                if(top==LivingAbyssGeometry.Kind.COARSE_DIRT)return TerrainMaterial.SOIL;
            }else if(!LivingAbyssRevision155.isSolid(sample(x,y+2,z))||!LivingAbyssRevision155.isSolid(sample(x,y+3,z))){
                boolean side=!LivingAbyssRevision155.isSolid(sample(x+1,y,z))||!LivingAbyssRevision155.isSolid(sample(x-1,y,z))
                    ||!LivingAbyssRevision155.isSolid(sample(x,y,z+1))||!LivingAbyssRevision155.isSolid(sample(x,y,z-1));
                if(!side)return TerrainMaterial.SOIL;
            }
        }
        return TerrainMaterial.ROCK;
    }
    /** Exact registered full-state projection for colour/emission/direct columns only.
     * Tips and authored/delegated points keep the independent canonical continuation. */
    int stateId(int x,int y,int z){
        if(stageB!=null)return stageB.stateId(x,y,z);
        if(exact==null||!owned(x,y,z))return -1;
        if(floor(x,y,z))return Block.getId(rocks.get(revision.material(x,y,z)));
        int code=sample(x,y,z);
        if(code==LivingAbyssRevision155.AIR||code==LivingAbyssRevision155.HALL)return Block.getId(Blocks.AIR.defaultBlockState());
        if(originalRequired(x,y,z)||code>=7)return -1;
        if(code==LivingAbyssRevision155.SPELEO)return Block.getId(rocks.get(revision.speleoMaterial(x,y,z)));
        if(code==LivingAbyssRevision155.LEDGE&&Math.hypot(x,z)<LivingAbyssMath155.inner(-((double)y))){
            boolean above=LivingAbyssRevision155.isSolid(sample(x,y+1,z));
            if(!above){long seed=living.recipeValue().seed();
                var kind=LivingAbyssSurfaceMaterials.top(LivingAbyssSurfaceMaterials.patch(seed,x,z),LivingAbyssMath155.mixUnit(seed^x^Long.rotateLeft(z,21)));
                if(kind==LivingAbyssGeometry.Kind.GRASS)return Block.getId(Blocks.GRASS_BLOCK.defaultBlockState());
                if(kind==LivingAbyssGeometry.Kind.MOSS)return Block.getId(Blocks.MOSS_BLOCK.defaultBlockState());
                if(kind==LivingAbyssGeometry.Kind.COARSE_DIRT)return Block.getId(Blocks.COARSE_DIRT.defaultBlockState());
            }else if(!LivingAbyssRevision155.isSolid(sample(x,y+2,z))||!LivingAbyssRevision155.isSolid(sample(x,y+3,z))){
                boolean side=!LivingAbyssRevision155.isSolid(sample(x+1,y,z))||!LivingAbyssRevision155.isSolid(sample(x-1,y,z))
                    ||!LivingAbyssRevision155.isSolid(sample(x,y,z+1))||!LivingAbyssRevision155.isSolid(sample(x,y,z-1));
                if(!side)return Block.getId(revision.edgeSoilAt(x,y,z)?Blocks.ROOTED_DIRT.defaultBlockState():Blocks.DIRT.defaultBlockState());
            }
        }
        return Block.getId(rocks.get(revision.material(x,y,z)));
    }
}
