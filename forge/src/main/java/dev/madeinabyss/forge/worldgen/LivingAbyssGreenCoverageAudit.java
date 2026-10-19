package dev.madeinabyss.forge.worldgen;
import com.google.gson.*;
import dev.madeinabyss.kernel.terrain.living.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import java.nio.file.*;
import java.util.*;

/** Read-only unfolded-wall census. One actually exposed sturdy face per sample; no screen-space claim. */
public final class LivingAbyssGreenCoverageAudit {
    private LivingAbyssGreenCoverageAudit(){}
    public static JsonObject measure(ParameterizedTerrainChunkGenerator g,Path root)throws Exception{
        var living=g.livingAbyss();var geometry=living.geometry();var fields=living.plan().fields();int width=(int)Math.round(2*Math.PI*650);
        double[][] denominator=new double[5][4],green=new double[5][4],moss=new double[5][4],vines=new double[5][4],textured=new double[5][4];int sampled=0,exposed=0;
        for(int depth=2;depth<1350;depth+=4)for(int arc=2;arc<width;arc+=4){sampled++;int y=-depth;double theta=360.0*arc/width,a=Math.toRadians(theta),sin=Math.sin(a),cos=Math.cos(a);
            double raw=geometry.wallBoundaryAt(theta,y),surface=raw-geometry.wallField().protrusion(theta,depth);int lastX=Integer.MIN_VALUE,lastZ=Integer.MIN_VALUE;
            for(int dr=-3;dr<=9;dr++){
                int x=(int)Math.round((surface+dr)*sin),z=(int)Math.round(-(surface+dr)*cos);if(x==lastX&&z==lastZ)continue;lastX=x;lastZ=z;
                if(Math.hypot(x,z)<380||geometry.sample(x,y,z)!=LivingAbyssGeometry.Kind.ROCK)continue;
                var state=g.sampleBlock(x,y,z);if(!state.isFaceSturdy(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO,Direction.UP))continue;
                int dx=Math.abs(x)>=Math.abs(z)?-Integer.signum(x):0,dz=dx==0?-Integer.signum(z):0;var inward=g.sampleBlock(x+dx,y,z+dz);if(inward.isSolid())continue;
                var face=Direction.getNearest((float)-dx,0,(float)-dz);boolean vine=inward.is(Blocks.VINE)&&inward.getValue(VineBlock.PROPERTY_BY_DIRECTION.get(face)),isMoss=state.is(Blocks.MOSS_BLOCK);
                int zone=fields.zone(x,y,z);double[] sides=fields.sideWeights(theta);exposed++;
                for(int side=0;side<4;side++){double weight=sides[side];denominator[zone][side]+=weight;if(isMoss||vine)green[zone][side]+=weight;if(isMoss)moss[zone][side]+=weight;if(vine)vines[zone][side]+=weight;if(state.is(Blocks.MOSSY_COBBLESTONE))textured[zone][side]+=weight;}
                break;
            }
        }
        var result=new JsonObject();result.addProperty("scope","exposed final wall faces, unfolded arc/depth4m grid; side transitions weighted by sideWeights; green=moss_block or a vine face attached to this wall voxel; mossy texture counted separately; no render/pixel-area claim");
        result.addProperty("sampled",sampled);result.addProperty("exposed",exposed);var rows=new JsonArray();String[] sideNames={"wet","karst","sunny","forest"};double all=0,allGreen=0;
        for(int zone=0;zone<5;zone++)for(int side=0;side<4;side++){double n=denominator[zone][side];var row=new JsonObject();row.addProperty("zone",zone);row.addProperty("side",sideNames[side]);row.addProperty("weightedExposed",n);
            row.addProperty("greenPercent",n==0?0:100*green[zone][side]/n);row.addProperty("mossPercent",n==0?0:100*moss[zone][side]/n);row.addProperty("vinePercent",n==0?0:100*vines[zone][side]/n);row.addProperty("mossyTexturePercent",n==0?0:100*textured[zone][side]/n);
            row.addProperty("targetPercent",18*(side==0?1.5:side==2?.5:1));rows.add(row);all+=n;allGreen+=green[zone][side];}
        result.add("zonesAndSides",rows);result.addProperty("greenPercent",100*allGreen/all);Files.createDirectories(root);Files.writeString(root.resolve("green-coverage.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));return result;
    }
}
