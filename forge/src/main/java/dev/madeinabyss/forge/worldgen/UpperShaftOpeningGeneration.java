package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Optional immutable v14 opening: removes only large shelf extrusion, retaining old wall noise/caves. */
public final class UpperShaftOpeningGeneration {
    @FunctionalInterface public interface Source {BlockState sample(int x,int y,int z);}
    private final CompiledTerrainSampler original,withoutShelves;
    private final int topY,fullY;
    private final double maximumRadiusSquared,cleanupRadiusSquared,mouthY;
    public UpperShaftOpeningGeneration(JsonObject recipe,TerrainParameters legacy){
        if(recipe.get("schema").getAsBigDecimal().intValueExact()!=1
                ||!"RECONSTRUCTION".equals(recipe.get("provenance").getAsString())
                ||!actualAlgorithmSha256().equals(recipe.get("algorithmSha256").getAsString()))
            throw new IllegalArgumentException("Explicit schema1 RECONSTRUCTION upper opening with actual SHA required");
        topY=recipe.get("topY").getAsBigDecimal().intValueExact();fullY=recipe.get("fullY").getAsBigDecimal().intValueExact();
        if(topY!=-250||fullY!=-400)throw new IllegalArgumentException("Approved upper opening requires -250/-400 transition");
        var c=legacy.cavity();mouthY=c.firstLayerTopY();
        if(mouthY!=64||c.mouthRadius()!=500)throw new IllegalArgumentException("Upper opening requires preserved R500/Y64 mouth");
        original=new CompiledTerrainSampler(legacy);
        var noShelves=new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),c.declaredLowerBoundY(),
                c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0);
        withoutShelves=new CompiledTerrainSampler(new TerrainParameters(legacy.seed(),legacy.surface(),noShelves,legacy.noise()));
        double max=Math.max(c.mouthRadius(),c.lowerRadius())+legacy.noise().wallAmplitude();maximumRadiusSquared=max*max;cleanupRadiusSquared=(max+16)*(max+16);
    }
    public static String actualAlgorithmSha256(){return TerrainLodDiskCache.codeIdentity(UpperShaftOpeningGeneration.class,CompiledTerrainSampler.class);}
    public static JsonObject proposal(){
        var j=new JsonObject();j.addProperty("schema",1);j.addProperty("provenance","RECONSTRUCTION");
        j.addProperty("topY",-250);j.addProperty("fullY",-400);j.addProperty("algorithmSha256",actualAlgorithmSha256());return j;
    }
    /** Zero shelf retention above -250, zero-derivative smooth restoration by -400. */
    public double shelfRetention(double y){
        if(y>=topY)return 0;if(y<=fullY)return 1;
        double t=(topY-y)/(topY-(double)fullY);return t*t*(3-2*t);
    }
    public double originalRadius(int x,int y,int z){return original.cavityRadius(x,y,z);}
    public double openingRadius(int x,int y,int z){
        if(y<=fullY)return original.cavityRadius(x,y,z);
        double wall=withoutShelves.cavityRadius(x,y,z),retention=shelfRetention(y);
        if(retention==0)return wall;
        double old=original.cavityRadius(x,y,z);return wall-retention*Math.max(0,wall-old);
    }
    public boolean carves(int x,int y,int z){
        if(y<=fullY||y>=mouthY)return false;
        double r2=(double)x*x+(double)z*z;if(r2>=maximumRadiusSquared)return false;
        double radius=openingRadius(x,y,z);return r2<radius*radius;
    }
    /** Cheap hot-path scope; outside it the generator keeps its original short-circuit order. */
    public boolean affects(int x,int y,int z){
        return y>fullY&&y<mouthY&&(double)x*x+(double)z*z<cleanupRadiusSquared;
    }
    /** Parent callback is the unchanged composed source: support probes never recurse through this modifier. */
    public BlockState apply(int x,int y,int z,BlockState old,Source parent){
        if(!affects(x,y,z))return old;
        if(carves(x,y,z))return Blocks.AIR.defaultBlockState();
        if(old.isAir()||!old.getFluidState().isEmpty())return old;
        // Boundary cleanup affects decorations only; original rock/cracks and surviving soil remain exact.
        if(old.getBlock() instanceof VineBlock){
            BlockState result=old;boolean attached=false;
            for(var direction:Direction.values()){
                BooleanProperty property=vineProperty(direction);if(property==null||!old.getValue(property))continue;
                if(carves(x+direction.getStepX(),y+direction.getStepY(),z+direction.getStepZ()))result=result.setValue(property,false);
                else attached=true;
            }
            return attached?result:Blocks.AIR.defaultBlockState();
        }
        if(old.getBlock() instanceof BushBlock){
            int supportY=y-1;
            if(old.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    &&old.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
                supportY=y-2;
            if(carves(x,supportY,z))return Blocks.AIR.defaultBlockState();
        }
        if(old.is(BlockTags.LOGS) && rootRemoved(x,y,z,parent))return Blocks.AIR.defaultBlockState();
        if(old.getBlock() instanceof LeavesBlock && Math.abs(Math.hypot((double)x,(double)z)-openingRadius(x,y,z))<=12){
            boolean found=false;
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)for(int dy=-3;dy<=1;dy++){
                int bx=x+dx,by=y+dy,bz=z+dz;if(!parent.sample(bx,by,bz).is(BlockTags.LOGS))continue;
                found=true;if(!carves(bx,by,bz)&&!rootRemoved(bx,by,bz,parent))return old;
            }
            if(found)return Blocks.AIR.defaultBlockState();
        }
        // Hanging author/vanilla vines have upper support, unlike rooted bushes.
        if(old.getBlock() instanceof GrowingPlantBlock || old.is(BlockTags.CLIMBABLE)){
            String name=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(old.getBlock()).getPath();
            if((name.endsWith("vine")||name.endsWith("vine_plant")||name.endsWith("vines")||name.endsWith("vines_plant"))
                    && carves(x,y+1,z))return Blocks.AIR.defaultBlockState();
        }
        return old;
    }
    private boolean rootRemoved(int x,int y,int z,Source parent){
        BlockState log=parent.sample(x,y,z);
        if(log.hasProperty(BlockStateProperties.AXIS)&&log.getValue(BlockStateProperties.AXIS)!=Direction.Axis.Y)
            return carves(x,y-1,z);
        for(int d=0;d<=16;d++){
            if(carves(x,y-d,z))return true;
            if(!parent.sample(x,y-d,z).is(BlockTags.LOGS))return false;
        }
        return false;
    }
    private static BooleanProperty vineProperty(Direction d){
        if(d==Direction.NORTH)return VineBlock.NORTH;if(d==Direction.SOUTH)return VineBlock.SOUTH;
        if(d==Direction.EAST)return VineBlock.EAST;if(d==Direction.WEST)return VineBlock.WEST;
        return d==Direction.UP?VineBlock.UP:null;
    }
    public JsonObject status(){var j=proposal();j.addProperty("mouthY",mouthY);j.addProperty("parentExactAtOrBelowY",fullY);return j;}
}
