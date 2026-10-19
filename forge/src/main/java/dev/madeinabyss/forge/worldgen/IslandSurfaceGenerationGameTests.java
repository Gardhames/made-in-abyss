package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.*;
import net.minecraftforge.gametest.*;
import java.util.Arrays;
import java.util.zip.Deflater;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IslandSurfaceGenerationGameTests {
    static JsonObject testRecipe(){return IslandSurfaceGeneration.proposal();}
    static TerrainParameters legacy(){return TerrainParameterCodecs.PARAMETERS.parse(JsonOps.INSTANCE,
            GeneratedPreviewProfileGameTests.manifest().get("parameters")).result().orElseThrow();}
    static IslandSurfaceGeneration island(){return new IslandSurfaceGeneration(testRecipe(),legacy());}

    @GameTest(template="empty",timeoutTicks=600)
    public static void islandLosslessAssetReloadAndFractionalGrid(GameTestHelper h){
        var a=island();var b=island();
        h.assertTrue(a.maximumSurfaceY()<320 && a.minimumKnownY()==-5000,"native ceiling and exterior depth");
        for(int x=-1280;x<=1280;x+=4)for(int z=-1280;z<=1280;z+=4)
            h.assertTrue(Double.doubleToLongBits(a.surfaceHeight(x,z))==Double.doubleToLongBits(b.surfaceHeight(x,z)),"resource reload retains every grid height");
        double expected=(a.surfaceHeight(-1004,-800)+a.surfaceHeight(-1000,-800)+a.surfaceHeight(-1004,-796)+a.surfaceHeight(-1000,-796))/4;
        h.assertTrue(Math.abs(a.surfaceHeight(-1002,-798)-expected)<1e-10,"negative coordinates retain four-metre bilinear detail");
        final var stale=testRecipe();stale.addProperty("dataSha256","0".repeat(64));
        expectRefusal(()->new IslandSurfaceGeneration(stale,legacy()),h,"stale data must refuse");
        final var wrong=testRecipe();wrong.addProperty("algorithmSha256","0".repeat(64));
        expectRefusal(()->new IslandSurfaceGeneration(wrong,legacy()),h,"stale algorithm must refuse");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void islandWholeCubeDomainAtSignedEdges(GameTestHelper h){
        var a=island();
        h.assertTrue(a.knownLowerY(959,0)==-1286 && a.knownLowerY(960,0)==-5000,"inclusive outward radius");
        h.assertTrue(!a.supportsCube(new CubeKey(59,-100,0)),"positive cube crosses inner unknown");
        h.assertTrue(!a.supportsCube(new CubeKey(-60,-100,0)),"negative cube includes -945 inner voxel");
        h.assertTrue(a.supportsCube(new CubeKey(60,-100,0))&&a.supportsCube(new CubeKey(-61,-100,0)),"fully exterior signed cubes admitted");
        h.assertTrue(!a.supportsCube(new CubeKey(0,-81,0))&&a.supportsCube(new CubeKey(0,-80,0)),"legacy unaligned -1286 cube edge");
        h.assertTrue(!a.supportsCube(new CubeKey(70,-313,0))&&a.supportsCube(new CubeKey(70,-312,0)),"exterior unaligned -5000 cube edge");
        h.assertTrue(!a.supportsCube(new CubeKey(Integer.MAX_VALUE,0,0)),"coordinate overflow refuses");
        h.assertTrue(a.sampleIfOwned(499,70,0)==null&&a.sampleIfOwned(-499,-1100,0)==null,"protected mouth delegates");
        h.assertTrue(a.sampleIfOwned(700,-1,0)==null&&a.sampleIfOwned(700,0,0)!=null,"deep cavity retained under surface join");
        expectRefusal(()->a.sampleIfOwned(2000,-5001,0),h,"sample cannot bypass domain");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void islandTrueOceanAndAllQuadrantSeams(GameTestHelper h){
        var a=island();
        for(int i=0;i<72;i++){
            double t=i*Math.PI/36,coast=IslandSurfaceGeneration.coast(t),r=coast+10000;
            int x=(int)Math.round(r*Math.cos(t)),z=(int)Math.round(r*Math.sin(t));
            int bed=(int)Math.floor(a.surfaceHeight(x,z));
            h.assertTrue(bed< -4000&&bed> -4200,"approved deep apron at 10km offshore");
            h.assertTrue(!a.sampleIfOwned(x,bed,z).isAir()&&!a.sampleIfOwned(x,bed,z).is(Blocks.WATER),"real rock seabed");
            h.assertTrue(a.sampleIfOwned(x,bed+1,z).is(Blocks.WATER)&&a.sampleIfOwned(x,-65,z).is(Blocks.WATER)
                    &&a.sampleIfOwned(x,0,z).is(Blocks.WATER)&&a.sampleIfOwned(x,1,z).isAir(),"full continuous water column through native/deep seam");
        }
        for(int sign:new int[]{-1,1})for(int z=-1280;z<=1280;z+=80){
            h.assertTrue(Math.abs(a.surfaceHeight(sign*1281,z)-a.surfaceHeight(sign*1280,z))<3,"bundled edge to parametric ocean continuous");
            h.assertTrue(Math.abs(a.surfaceHeight(z,sign*1281)-a.surfaceHeight(z,sign*1280))<3,"signed square corner seam");
        }
        h.assertTrue(Math.abs(a.surfaceHeight(-12000,-.001)-a.surfaceHeight(-12000,.001))<.01,"atan2 signed seam");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void islandCorruptAssetAndBombFailClosed(GameTestHelper h){
        byte[] compressed;
        try(var in=IslandSurfaceGenerationGameTests.class.getClassLoader().getResourceAsStream(IslandHeightAsset.ID)){
            compressed=in.readAllBytes();
        }catch(Exception e){throw new AssertionError(e);}
        byte[] corrupt=compressed.clone();corrupt[corrupt.length/2]^=1;
        expectRefusal(()->IslandHeightAsset.decode(corrupt,IslandSurfaceGeneration.DATA_SHA,IslandSurfaceGeneration.COMPRESSED_SHA),h,"corrupt byte hash refuses");
        byte[] trailing=Arrays.copyOf(compressed,compressed.length+1);
        expectRefusal(()->IslandHeightAsset.decode(trailing,IslandSurfaceGeneration.DATA_SHA,IslandHeightAsset.sha(trailing)),h,"extra framing refuses even matching compressed hash");
        var deflater=new Deflater(9);deflater.setInput(new byte[IslandHeightAsset.RAW_BYTES+1]);deflater.finish();
        byte[] buf=new byte[65536];int count=deflater.deflate(buf);deflater.end();byte[] bomb=Arrays.copyOf(buf,count);
        expectRefusal(()->IslandHeightAsset.decode(bomb,IslandSurfaceGeneration.DATA_SHA,IslandHeightAsset.sha(bomb)),h,"inflated overflow refuses bounded allocation");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void islandSupportedPlantsAndUnbrokenSurfaceFoundation(GameTestHelper h){
        var a=island();int plants=0,logs=0;
        for(int x=-1000;x<=1000;x+=3)for(int z=-1000;z<=1000;z+=3){
            double r=Math.hypot(x,z);if(r<500||r>=960)continue;
            int top=(int)Math.floor(a.surfaceHeight(x,z));
            h.assertTrue(top>0 && !a.sampleIfOwned(x,0,z).isAir(),"surface join owns solid continuous new foundation");
            var plant=a.sampleIfOwned(x,top+1,z);
            if(plant.is(Blocks.GRASS)||plant.is(Blocks.FERN)||plant.is(Blocks.OAK_LOG)){
                h.assertTrue(a.sampleIfOwned(x,top,z).is(Blocks.GRASS_BLOCK)||a.sampleIfOwned(x,top,z).is(Blocks.COARSE_DIRT),"actual soil below plant");
                h.assertTrue(r>540,"mouth clearing preserved");plants++;if(plant.is(Blocks.OAK_LOG))logs++;
            }
        }
        h.assertTrue(plants>100&&logs>0,"supported ground cover and trees materialized");h.succeed();
    }
    private static void expectRefusal(Runnable action,GameTestHelper h,String message){
        boolean refused=false;try{action.run();}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,message);
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void islandComposedJoinRetainsOldNegativeCavesAndSupportsOceanFloor(GameTestHelper h){
        var source=GeneratedPreviewProfileGameTests.manifest();
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var before=new GeneratedPreviewProfile(source,"test-generated",biomes);
        var revised=source.deepCopy();revised.add("islandSurface",testRecipe());
        var after=new GeneratedPreviewProfile(revised,"test-generated",biomes);
        for(int i=0;i<144;i++){
            double t=i*Math.PI/72;
            for(int radius:new int[]{501,550,700,900,959}){
                int x=(int)Math.round(radius*Math.cos(t)),z=(int)Math.round(radius*Math.sin(t));
                if(Math.hypot(x,z)>=960)continue;
                for(int y:new int[]{-1,-64,-600,-1280})h.assertTrue(
                        after.generator().sampleBlock(x,y,z).equals(before.generator().sampleBlock(x,y,z)),"all preserved negative cave/ledge voxels retain parent");
                h.assertTrue(!after.generator().sampleBlock(x,0,z).isAir(),"new join foundation remains solid at0");
            }
            for(int radius:new int[]{958,959,960,961,962}){
                int x=(int)Math.round(radius*Math.cos(t)),z=(int)Math.round(radius*Math.sin(t));
                h.assertTrue(!after.generator().sampleBlock(x,0,z).isAir(),"outer foundation joins across R960 at0");
                h.assertTrue(!after.generator().sampleBlock(x,-1,z).isAir(),"outer foundation joins across R960 at-1");
            }
        }
        h.assertTrue(after.generator().sampleBlock(12000,-5000,0).is(Blocks.DEEPSLATE),"actual solid deep foundation at known floor");
        expectRefusal(()->after.generator().sampleBlock(12000,-5001,0),h,"composed domain rejects below floor");
        h.succeed();
    }
}
