package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.living.*;
import dev.madeinabyss.forge.probe.client.compat.LodBlockStateMaterials;
import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.function.IntUnaryOperator;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.core.Direction;

/** Separate, fingerprint-bound B proof. A's optional evaluator is never enabled.
 * The canonical full-state generator remains the independent differential oracle. */
public final class Generated155BDerivedProjection {
    private static final String FINGERPRINT="3af600402e26dd456daf516a782ac7d5b94ecc3b2d22628d4d256d505c9238ae";
    private static final String ALGORITHM="d992fb787da808feb9aa530b9a89acecdf485c72f55edae7beedd41953cf7013";
    private final LivingAbyssGeneration living;
    private final LivingAbyssRevision155 shape;
    private final LivingAbyssReservations reserved,protectedVoxels;
    private final CompiledTerrainSampler legacyBare;
    private final ParameterizedTerrainChunkGenerator parent;
    private final LivingAbyssCaveMouths mouths;
    private final double limit;
    private final int air,airOpacity,solidOpacity;
    private final Map<String,Integer> rocks;
    private final int[] tips=new int[8],tipOpacity=new int[8];
    private final TerrainMaterial[] tipMaterial=new TerrainMaterial[8];
    private final int[] features=new int[LivingAbyssFeatureBlock.values().length],featureOpacity=new int[features.length];
    private final TerrainMaterial[] featureMaterial=new TerrainMaterial[features.length];
    private Generated155BDerivedProjection(GeneratedPreviewProfile profile,IntUnaryOperator opacity)throws ReflectiveOperationException{
        living=profile.generator().livingAbyss();shape=living.revision155();limit=Math.max(960,shape.maximumRadius());
        var lookup=MethodHandles.privateLookupIn(LivingAbyssGeneration.class,MethodHandles.lookup());
        reserved=(LivingAbyssReservations)lookup.findVarHandle(LivingAbyssGeneration.class,"reservations",LivingAbyssReservations.class).get(living);
        protectedVoxels=(LivingAbyssReservations)lookup.findVarHandle(LivingAbyssGeneration.class,"protectedReservations",LivingAbyssReservations.class).get(living);
        legacyBare=(CompiledTerrainSampler)lookup.findVarHandle(LivingAbyssGeneration.class,"legacyBare",CompiledTerrainSampler.class).get(living);
        parent=(ParameterizedTerrainChunkGenerator)lookup.findVarHandle(LivingAbyssGeneration.class,"parent",ParameterizedTerrainChunkGenerator.class).get(living);
        mouths=(LivingAbyssCaveMouths)lookup.findVarHandle(LivingAbyssGeneration.class,"mouths",LivingAbyssCaveMouths.class).get(living);
        @SuppressWarnings("unchecked") var states=(Map<String,BlockState>)lookup.findVarHandle(LivingAbyssGeneration.class,"rocks",Map.class).get(living);
        var palette=new HashMap<String,Integer>();int solid=-1;
        for(var entry:states.entrySet()){int id=Block.getId(entry.getValue()),value=opacity.applyAsInt(id);
            if(solid>=0&&solid!=value)throw new IllegalArgumentException("Nonuniform B rock opacity");solid=value;palette.put(entry.getKey(),id);}
        for(var block:List.of(Blocks.GRASS_BLOCK,Blocks.MOSS_BLOCK,Blocks.COARSE_DIRT,Blocks.DIRT,Blocks.ROOTED_DIRT))
            if(opacity.applyAsInt(Block.getId(block.defaultBlockState()))!=solid)throw new IllegalArgumentException("B cover opacity differs from proof");
        rocks=Map.copyOf(palette);solidOpacity=solid;air=Block.getId(Blocks.AIR.defaultBlockState());airOpacity=opacity.applyAsInt(air);
        for(int i=0;i<8;i++){int code=7+i;var thickness=switch(i&3){case 0->DripstoneThickness.BASE;case 1->DripstoneThickness.MIDDLE;case 2->DripstoneThickness.FRUSTUM;default->DripstoneThickness.TIP;};
            tips[i]=Block.getId(Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(BlockStateProperties.VERTICAL_DIRECTION,code<11?Direction.DOWN:Direction.UP).setValue(BlockStateProperties.DRIPSTONE_THICKNESS,thickness));
            tipOpacity[i]=opacity.applyAsInt(tips[i]);tipMaterial[i]=LodBlockStateMaterials.materialById(tips[i]);}
        Arrays.fill(features,-1);
        for(var f:LivingAbyssFeatureBlock.values())if(f!=LivingAbyssFeatureBlock.NONE){int i=f.ordinal();features[i]=Block.getId(LivingAbyssBlockStates.feature(i));
            featureOpacity[i]=opacity.applyAsInt(features[i]);featureMaterial[i]=LodBlockStateMaterials.materialById(features[i]);}
        if(solid<0||reserved==null||protectedVoxels==null||legacyBare==null||parent==null||mouths==null)throw new IllegalArgumentException("Incomplete B closure");
    }
    static Generated155BDerivedProjection bind(GeneratedPreviewProfile profile,IntUnaryOperator opacity){
        if(profile==null||!FINGERPRINT.equals(profile.fingerprint())||!ALGORITHM.equals(LivingAbyssGeneration.algorithmSha256()))return null;
        var living=profile.generator().livingAbyss();if(living==null||living.revision155()==null||!living.revision155().stageB()||!living.revision155().floorSupportFinished())return null;
        try{return new Generated155BDerivedProjection(profile,opacity);}catch(ReflectiveOperationException|RuntimeException refused){return null;}
    }
    /** Refused points always use the original continuation, never opacity-inferred AIR. */
    private int code(int x,int y,int z){double r=Math.hypot(x,z);if(y>=0||r>=limit)return -1;
        if(y<=living.recipeValue().domain().temporaryFloorY()+8&&y<=living.geometry().floorTop(x,z))return LivingAbyssRevision155.ROCK;
        int code=shape.sample(x,y,z);if(code==LivingAbyssRevision155.AIR||code==LivingAbyssRevision155.HALL)return LivingAbyssRevision155.AIR;
        var mouth=code<7?mouths.hit(x,y,z):null;
        if(mouth!=null&&code!=LivingAbyssRevision155.SPELEO&&mouths.carve(mouth,y)&&!shape.speleoProtectedAt(x,y,z))return LivingAbyssRevision155.AIR;
        if(y>=parent.parameters().cavity().declaredLowerBoundY()&&protectedVoxels.contains(x,y,z))return -1;
        if(code>=LivingAbyssRevision155.FEATURE_BASE)return code;
        if(y>=parent.parameters().cavity().declaredLowerBoundY()&&reserved.contains(x,y,z)&&r>=legacyBare.cavityRadius(x,y,z))return -1;
        if(code>=7||code==LivingAbyssRevision155.SPELEO)return code;
        if(mouth!=null){var rim=mouths.rimRock(mouth,x,y,z);if(rim!=LivingAbyssFeatureBlock.NONE)return LivingAbyssRevision155.FEATURE_BASE+rim.ordinal();}
        if(code==LivingAbyssRevision155.LEDGE&&r<LivingAbyssMath155.inner(-((double)y))){
            boolean above=LivingAbyssRevision155.isSolid(shape.sample(x,y+1,z));
            if(!above){long seed=living.recipeValue().seed();var top=LivingAbyssSurfaceMaterials.top(LivingAbyssSurfaceMaterials.patch(seed,x,z),LivingAbyssMath155.mixUnit(seed^x^Long.rotateLeft(z,21)));
                if(top==LivingAbyssGeometry.Kind.GRASS)return feature(LivingAbyssFeatureBlock.GRASS);
                if(top==LivingAbyssGeometry.Kind.MOSS)return feature(LivingAbyssFeatureBlock.MOSS);
                if(top==LivingAbyssGeometry.Kind.COARSE_DIRT)return feature(LivingAbyssFeatureBlock.COARSE_DIRT);
            }else if(!LivingAbyssRevision155.isSolid(shape.sample(x,y+2,z))||!LivingAbyssRevision155.isSolid(shape.sample(x,y+3,z))){
                boolean side=!LivingAbyssRevision155.isSolid(shape.sample(x+1,y,z))||!LivingAbyssRevision155.isSolid(shape.sample(x-1,y,z))||!LivingAbyssRevision155.isSolid(shape.sample(x,y,z+1))||!LivingAbyssRevision155.isSolid(shape.sample(x,y,z-1));
                if(!side)return feature(shape.edgeSoilAt(x,y,z)?LivingAbyssFeatureBlock.ROOTED_DIRT:LivingAbyssFeatureBlock.DIRT);
            }
        }return code;
    }
    private static int feature(LivingAbyssFeatureBlock block){return LivingAbyssRevision155.FEATURE_BASE+block.ordinal();}
    int opacity(int x,int y,int z){int code=code(x,y,z);if(code<0)return -1;if(code==0)return airOpacity;
        if(code>=LivingAbyssRevision155.FEATURE_BASE){int i=code-LivingAbyssRevision155.FEATURE_BASE;return i<features.length?featureOpacity[i]:-1;}
        return code>=7?tipOpacity[code-7]:solidOpacity;
    }
    TerrainMaterial material(int x,int y,int z){int code=code(x,y,z);if(code<0)return null;if(code==0)return TerrainMaterial.AIR;
        if(code>=LivingAbyssRevision155.FEATURE_BASE){int i=code-LivingAbyssRevision155.FEATURE_BASE;return i<features.length?featureMaterial[i]:null;}
        return code>=7?tipMaterial[code-7]:TerrainMaterial.ROCK;
    }
    int stateId(int x,int y,int z){int code=code(x,y,z);if(code<0)return -1;if(code==0)return air;
        if(code>=LivingAbyssRevision155.FEATURE_BASE){int i=code-LivingAbyssRevision155.FEATURE_BASE;return i<features.length?features[i]:-1;}
        if(code>=7)return tips[code-7];
        return rocks.get(code==LivingAbyssRevision155.SPELEO?shape.speleoMaterial(x,y,z):code==LivingAbyssRevision155.LEDGE?shape.bodyMaterial(x,y,z):shape.material(x,y,z));
    }
}
