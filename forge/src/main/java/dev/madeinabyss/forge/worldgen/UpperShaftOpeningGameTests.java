package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UpperShaftOpeningGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void openingRetainsExactDeepParentAndImmutableIsland(GameTestHelper h)throws Exception{
        var manifest=GeneratedPreviewProfileGameTests.manifest();
        String configured=System.getProperty("mia.generator.previewProfile");
        if(configured!=null){
            var actual=JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(configured))).getAsJsonObject();
            if(actual.has("islandSurface"))manifest=actual;
        }
        manifest.remove("upperShaftOpening");manifest.add("islandSurface",IslandSurfaceGeneration.proposal());
        String id=manifest.get("profileId").getAsString();
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var old=new GeneratedPreviewProfile(manifest,id,biomes);
        var newManifest=manifest.deepCopy();newManifest.add("upperShaftOpening",UpperShaftOpeningGeneration.proposal());
        var revised=new GeneratedPreviewProfile(newManifest,id,biomes);
        h.assertTrue(!old.fingerprint().equals(revised.fingerprint()),"optional modifier receives independent visited store");
        for(int x=-720;x<=720;x+=31)for(int z=-720;z<=720;z+=31)for(int y:new int[]{-400,-401,-450,-600,-1000,-1280})
            h.assertTrue(old.generator().sampleBlock(x,y,z).equals(revised.generator().sampleBlock(x,y,z)),"every tested deep ecology/trail voxel is exact parent");
        for(int x=-1300;x<=1300;x+=29)for(int z=-1300;z<=1300;z+=29)for(int y:new int[]{0,1,63,64,128,256}){
            if(Math.hypot(x,z)>=500)h.assertTrue(old.generator().sampleBlock(x,y,z).equals(revised.generator().sampleBlock(x,y,z)),"approved island overrides modifier");
        }
        var stale=newManifest.deepCopy();stale.getAsJsonObject("upperShaftOpening").addProperty("algorithmSha256","0".repeat(64));
        boolean refused=false;try{new GeneratedPreviewProfile(stale,id,biomes);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"stale modifier hash refuses");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void openingRemovesLargeUpperPetalsAndRetainsVariableRockWall(GameTestHelper h){
        var opening=new UpperShaftOpeningGeneration(UpperShaftOpeningGeneration.proposal(),IslandSurfaceGenerationGameTests.legacy());
        int removed=0;double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(int y=-240;y<64;y+=8)for(int a=0;a<144;a++){
            double angle=a*Math.PI/72;int x=(int)Math.round(500*Math.cos(angle)),z=(int)Math.round(500*Math.sin(angle));
            double old=opening.originalRadius(x,y,z),wall=opening.openingRadius(x,y,z);min=Math.min(min,wall);max=Math.max(max,wall);
            if(wall-old>40){
                int bx=(int)Math.round((wall-3)*Math.cos(angle)),bz=(int)Math.round((wall-3)*Math.sin(angle));
                if(Math.hypot(bx,bz)>opening.originalRadius(bx,y,bz)&&opening.carves(bx,y,bz)){
                    h.assertTrue(opening.apply(bx,y,bz,Blocks.STONE.defaultBlockState(),(px,py,pz)->Blocks.STONE.defaultBlockState()).isAir(),"large old upper petal is carved to real air");removed++;
                }
            }
        }
        h.assertTrue(removed>100,"large first petals are actually removed across angles/heights");
        h.assertTrue(max-min>30,"wall retains original radial ribs/noise, never a smooth cylinder");h.succeed();
    }
    @GameTest(template="empty")
    public static void openingTransitionIsContinuousMonotoneAndSigned(GameTestHelper h){
        var o=new UpperShaftOpeningGeneration(UpperShaftOpeningGeneration.proposal(),IslandSurfaceGenerationGameTests.legacy());
        double previous=0;
        for(double y=-250;y>=-400;y-=.125){double w=o.shelfRetention(y);h.assertTrue(w>=previous&&w>=0&&w<=1,"continuous monotone retention");previous=w;}
        h.assertTrue(o.shelfRetention(-249)==0&&o.shelfRetention(-401)==1,"outside transition exact endpoints");
        h.assertTrue(o.shelfRetention(-250.001)<1e-9&&1-o.shelfRetention(-399.999)<1e-9,"zero derivative endpoints avoid horizontal slicing");
        for(int x:new int[]{-700,-501,0,501,700})for(int z:new int[]{-700,-501,0,501,700})
            h.assertTrue(!o.carves(x,-400,z)&&!o.carves(x,64,z),"identity endpoints at signed coordinates");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void openingRemovesOrphanVineAttachmentWithoutRecursiveSampling(GameTestHelper h){
        var o=new UpperShaftOpeningGeneration(UpperShaftOpeningGeneration.proposal(),IslandSurfaceGenerationGameTests.legacy());
        int y=-200,z=0,x=(int)Math.ceil(o.openingRadius(500,y,z));
        while(o.carves(x,y,z))x++;while(!o.carves(x-1,y,z))x--;
        var vine=Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST,true);
        var result=o.apply(x,y,z,vine,(px,py,pz)->Blocks.STONE.defaultBlockState());
        h.assertTrue(result.isAir(),"last carved wall attachment removes floating vine");h.succeed();
    }
}
