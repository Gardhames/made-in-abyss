package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.living.*;
import dev.madeinabyss.forge.probe.client.compat.AbyssLodPhysicalLighting;
import java.util.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LivingAbyssPart2GameTests {
    @GameTest(template="empty",batch="miaV15LodCost",timeoutTicks=20)
    public static void optionalFullShaftGeometryAndExactOwnerWorkloadAudit(GameTestHelper h)throws Exception {
        if(!Boolean.getBoolean("mia.bench.v15Lod")){h.succeed();return;}
        var m=LivingAbyssGameTests.parentManifest();var v14=new GeneratedPreviewProfile(m,m.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        var v15=LivingAbyssGameTests.profile(h);var root=java.nio.file.Path.of(System.getProperty("mia.bench.v15Output",System.getProperty("mia.storageRoot", ".local") + "/codex/20261013-landscape-v15/part2"));
        var result=dev.madeinabyss.forge.probe.client.compat.LivingAbyssLodCostAudit.measure(v14,v15,m,LivingAbyssGameTests.manifest(),root.resolve("lod-audit"));
        System.out.println("MIA_V15_LOD_COST "+result);h.assertTrue(result.get("gatePass").getAsBoolean(),"LOD surfaces/visible owners <=1.5x, preparation/index <=1.65x, heap <6GB; stop and report on refusal");h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Part2",timeoutTicks=1200)
    public static void approvedWallSpireAndBiomeRecipeIsNotThePart1Placeholder(GameTestHelper h) throws Exception {
        var r=LivingAbyssGameTests.recipe();
        h.assertTrue(r.has("wall")&&r.has("curtains")&&r.has("spires")&&r.has("biomeColors"),"part2 requires explicit approved wall, curtains, spires and biome colors");
        h.assertTrue(r.getAsJsonObject("wall").get("slabPeriod").getAsDouble()==34,"Claude's macro slab period");
        h.assertTrue(r.getAsJsonObject("wall").getAsJsonArray("palettes").get(1).getAsJsonObject().get("dripstone_block").getAsInt()==7,"section21 gardens dripstone weight 7");
        h.assertTrue(r.getAsJsonObject("wall").getAsJsonArray("palettes").get(1).getAsJsonObject().get("white_terracotta").getAsInt()==2,"section21 gardens terracotta weight 2");
        h.assertTrue(LivingAbyssGameTests.profile(h).generator().livingAbyss().status().get("spireCount").getAsInt()>0,"non-vacuous built spire plan");
        h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Part2",timeoutTicks=1200)
    public static void everyGeneratedVineVoxelHasAnActualSturdyFaceAndNoColumnGap(GameTestHelper h) throws Exception {
        var g=LivingAbyssGameTests.profile(h).generator();var green=g.livingAbyss().green();int checked=0;
        h.assertTrue(green.vines().size()>100,"non-vacuous supported curtain plan");
        for(var column:green.vines())for(int i=0;i<column.masks().size();i++){
            int x=column.x(),y=column.topY()-i,z=column.z();var state=g.sampleBlock(x,y,z);
            h.assertTrue(state.is(Blocks.VINE),"planned straight column is present in final composition at "+x+","+y+","+z);
            boolean supported=false;
            for(var direction:Direction.Plane.HORIZONTAL){boolean attached=state.getValue(VineBlock.PROPERTY_BY_DIRECTION.get(direction));
                var neighbor=g.sampleBlock(x+direction.getStepX(),y,z+direction.getStepZ());
                if(attached)h.assertTrue(neighbor.isFaceSturdy(h.getLevel(),BlockPos.ZERO,direction.getOpposite()),"every native vine attachment has a sturdy final face");supported|=attached;}
            h.assertTrue(supported,"no air-only vine");checked++;
        }
        System.out.println("MIA_V15_VINE_SUPPORT checked="+checked+" columns="+green.vines().size());h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Part2",timeoutTicks=1200)
    public static void allSpireCoresAndBasalSaddlesConnectToRealWallAndTreeRoots(GameTestHelper h) throws Exception {
        var g=LivingAbyssGameTests.profile(h).generator();var living=g.livingAbyss();int cores=0,roots=0,trees=0;
        for(var q:living.spires().spires()){
            h.assertTrue(q.topY()-q.baseY()>=40&&q.topY()-q.baseY()<=220,"spire height");
            h.assertTrue(Math.hypot(q.x(),q.z())-q.radius()>=380,"descriptor outside R380");
            for(int y=q.baseY()-1;y<=q.topY();y++){h.assertTrue(g.sampleBlock(q.x(),y,q.z()).isSolid(),"continuous final pillar core "+q.id()+" y="+y);cores++;}
            double a=Math.toRadians(q.theta()),start=Math.hypot(q.x(),q.z());int yy=q.baseY()-1;
            double outer=living.geometry().wallRadius(q.x(),yy,q.z());
            boolean straight=true;for(double r=start;r<=outer+3;r+=.5){int x=(int)Math.round(r*Math.sin(a)),z=(int)Math.round(-r*Math.cos(a));straight&=g.sampleBlock(x,yy,z).isSolid();roots++;}
            if(!straight)h.assertTrue(saddleConnected(g,q),"actual 6-neighbour basal component meets wall around preserved cave/path cuts "+q.id());
            for(var t:living.flora().trees())if(t.owner().equals(q.id())){
                h.assertTrue(g.sampleBlock(t.x(),t.y()-1,t.z()).isSolid(),"baked tree on supported spire cap");
                h.assertTrue(g.sampleBlock(t.x(),t.y(),t.z()).isSolid(),"connected baked trunk anchor "+q.id()+" "+t.kind());trees++;
            }
        }
        h.assertTrue(cores>1000&&trees>20,"non-vacuous spire and tree probes");System.out.println("MIA_V15_SPIRE_SUPPORT cores="+cores+" saddle="+roots+" trees="+trees);h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Part2",timeoutTicks=1200)
    public static void profileBiomeEffectsAndDhOwnerColorsUseApprovedRgbWithoutChangingV14(GameTestHelper h) throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var colors=profile.generator().livingAbyss().recipeValue().biomeColors();
        var original=h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS).value();int oldGrass=original.getGrassColor(-33,-33);
        var biome=LivingAbyssColors.biome(original,colors);h.assertTrue(biome.getGrassColor(-33,-33)==0x5e8f34&&biome.getFoliageColor()==0x4f8030&&biome.getWaterColor()==0x3f9fb0,"all native biome callbacks use the exact RGB");
        h.assertTrue(original.getGrassColor(-33,-33)==oldGrass&&LivingAbyssColors.biome(original,null)==original,"old source biome is untouched");
        var source=new GeneratedSkySource(profile,Map.of());var light=new AbyssLodPhysicalLighting(source,profile.fingerprint());
        var grass=new TerrainLodColumns.Box(-33,-500,-33,-32,-499,-32,TerrainMaterial.GRASS);var water=new TerrainLodColumns.Box(-33,-500,-33,-32,-499,-32,TerrainMaterial.WATER);
        h.assertTrue(light.rgba(grass)==0xff4e7d33&&light.rgba(water)==0xb43f9fb0,"DH callback receives section21 grass owner and unchanged water");
        final int[] leaf={0,0,0};final boolean[] found={false};
        profile.generator().livingAbyss().flora().features().visit((x,y,z,b)->{if(!found[0]&&dev.madeinabyss.kernel.terrain.living.LivingAbyssFeatureBlock.isLeaves(b)){leaf[0]=x;leaf[1]=y;leaf[2]=z;found[0]=true;}});
        h.assertTrue(found[0],"a real stamped leaf is sampled");
        var leaves=new TerrainLodColumns.Box(leaf[0],leaf[1],leaf[2],leaf[0]+1,leaf[1]+1,leaf[2]+1,TerrainMaterial.GRASS);
        h.assertTrue(light.rgba(leaves)==0xff43702b,"DH foliage receives section21 separate owner color");
        var oldManifest=LivingAbyssGameTests.parentManifest();var old=new GeneratedPreviewProfile(oldManifest,oldManifest.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        h.assertTrue(new AbyssLodPhysicalLighting(new GeneratedSkySource(old,Map.of())).rgba(grass)==0xff688a41,"historical LOD owner color is exact");h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Part2",timeoutTicks=1200)
    public static void newWallDoesNotFillParentCaveEntrancesOrProtectedPaths(GameTestHelper h) throws Exception {
        var g=LivingAbyssGameTests.profile(h).generator();var m=LivingAbyssGameTests.parentManifest();var old=new GeneratedPreviewProfile(m,m.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h)).generator();int portals=0;
        // Genuine named v14 cave mouths, including their voxel lips. Old weathering
        // recesses outside this spatial index belong to the new canonical wall formula.
        for(var portal:old.caveExpansion().caves().portals()){var p=portal.position();int cx=(int)Math.round(p.x()),cy=(int)Math.round(p.y()),cz=(int)Math.round(p.z());
            for(int dx=-2;dx<=2;dx++)for(int dy=-2;dy<=2;dy++)for(int dz=-2;dz<=2;dz++){int x=cx+dx,y=cy+dy,z=cz+dz;if(Math.hypot(x,z)<380)continue;
                var sample=old.sampleGeometry(x,y,z);if(sample instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.AIR&&!old.sampleBlock(x,y,z).isSolid()){
                    h.assertTrue(!g.sampleBlock(x,y,z).isSolid(),"actual v14 named portal lip remains open: "+x+","+y+","+z);portals++;}}
            double theta=LivingAbyssFields.azimuth(p.x(),p.z()),a=Math.toRadians(theta);
            for(int dy=-4;dy<=4;dy+=2){int y=cy+dy;double boundary=g.livingAbyss().geometry().wallBoundaryAt(theta,y);int px=(int)Math.round((boundary+3)*Math.sin(a)),pz=(int)Math.round(-(boundary+3)*Math.cos(a));
                var sample=old.sampleGeometry(px,y,pz);if(sample instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.AIR)for(int dr=0;dr<=15;dr+=3){int x=(int)Math.round((boundary-dr)*Math.sin(a)),z=(int)Math.round(-(boundary-dr)*Math.cos(a));
                    h.assertTrue(!g.sampleBlock(x,y,z).isSolid()||old.sampleBlock(x,y,z).isSolid(),"projected actual mouth corridor remains open");}}
        }
        int paths=0;
        for(var value:m.getAsJsonObject("trails").getAsJsonArray("routes"))for(var point:value.getAsJsonObject().getAsJsonArray("points")){
            var pos=point.getAsJsonArray();int x=pos.get(0).getAsInt(),y=pos.get(1).getAsInt(),z=pos.get(2).getAsInt();
            for(int dy=0;dy<=2;dy++){h.assertTrue(g.sampleBlock(x,y+dy,z).equals(old.sampleBlock(x,y+dy,z)),"exact protected trail and headroom retained");paths++;}
        }
        h.assertTrue(portals>0&&paths>100,"actual v14 cave/path witnesses");System.out.println("MIA_V15_PORTALS checked="+portals+" pathVoxels="+paths);h.succeed();
    }
    private static boolean saddleConnected(ParameterizedTerrainChunkGenerator g,LivingAbyssSpirePlan.Spire q){
        var living=g.livingAbyss();var pending=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();var start=new BlockPos(q.x(),q.baseY()-1,q.z());pending.add(start);seen.add(start);
        while(!pending.isEmpty()){
            var pos=pending.remove();int x=pos.getX(),y=pos.getY(),z=pos.getZ();
            if(Math.hypot(x,z)>=living.geometry().wallRadius(x,y,z)+1&&g.sampleBlock(x,y,z).isSolid())return true;
            for(var d:Direction.values()){
                var n=pos.relative(d);if(n.getY()<q.baseY()-q.saddleThickness()||n.getY()>q.baseY()||!seen.add(n))continue;
                if(!living.spires().solid(q,n.getX(),n.getY(),n.getZ()))continue;
                if(g.sampleBlock(n.getX(),n.getY(),n.getZ()).isSolid())pending.add(n);
            }
        }return false;
    }
}
