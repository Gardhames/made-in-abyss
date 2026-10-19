package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.Arrays;
import java.util.Map;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IslandSkyGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void spatialColumnsRejectInnerUnknownAndKeepDeepOcean(GameTestHelper h) {
        var source=new GeneratedSkySource(generator(h),Map.of());
        h.assertTrue(source.minY()==-5000 && source.knownLowerY(0,0)==-1286,"global envelope never extends inner availability");
        h.assertTrue(source.knownLowerY(-10000,-100)==-5000,"negative exterior has real ocean domain");
        int x=-10000,z=-100;
        h.assertTrue(source.stateIdUncached(x,-4000,z)==Block.getId(Blocks.WATER.defaultBlockState()),"far ocean contains actual deep water");
        for(int y:new int[]{-5000,-4000,-1300,-1286,0,319})
            h.assertTrue(source.stateId(x,y,z)==source.stateIdUncached(x,y,z),"local full-column indexes match scalar at "+y);
        var exposure=source.columnExposure(x,z);
        h.assertTrue(exposure.minimumY()==-5000 && exposure.directSky(1)==15 && exposure.directSky(-4000)==0,"deep ocean direct exposure attenuates through real water");
        int dense=15;var waterSlice=source.directSkySlice(x,z,-20,23);
        for(int yy=2;yy>=-20;yy--){int opacity=source.opacity(source.stateIdUncached(x,yy,z));if(!(dense==15&&opacity==0))dense=Math.max(0,dense-Math.max(1,opacity));h.assertTrue(waterSlice[yy+20]==dense,"sea-interface attenuation matches dense reference at "+yy);}
        var slice=source.directSkySlice(x,z,-4016,32);
        for(int i=0;i<slice.length;i++)h.assertTrue(slice[i]==source.directSkyFast(x,-4016+i,z),"deep local slices agree with column");
        for(int kind=0;kind<5;kind++) {
            boolean refused=false;
            try {switch(kind){case 0->source.stateId(0,-1287,0);case 1->source.stateIdFast(0,-1287,0);case 2->source.opacityFast(0,-1287,0);case 3->source.directSkyFast(0,-1287,0);default->source.directSkySlice(0,0,-1287,1);}}
            catch(IllegalArgumentException expected){refused=true;}
            h.assertTrue(refused,"unknown center is never geometry or SKY, path "+kind);
        }
        var inner=source.columnExposure(0,0);
        h.assertTrue(inner.minimumY()==-1286,"inner exposure retains local minimum after exterior cache entry");
        var centerPlan=source.nativePlan(8,0,-1200,0);
        h.assertTrue(centerPlan.lightBounds().minY()==-1286,"entire central light halo clipped above unknown");
        var seaPlan=source.nativePlan(8,-10000,-3900,-100);
        h.assertTrue(seaPlan.lightBounds().minY() < -4000,"far ocean plan retains deep supported halo");
        boolean mixed=false;try{source.nativePlan(8,970,-2000,0);}catch(IllegalArgumentException expected){mixed=true;}
        h.assertTrue(mixed,"deep box crossing inner unknown explicitly refused");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void savedOceanAndHighRoofAreAuthoritativeAndDomainValidated(GameTestHelper h) {
        var generator=generator(h);int[] saved=new int[4096];Arrays.fill(saved,Block.getId(Blocks.AIR.defaultBlockState()));
        int x=-10000,z=-100,y=-4000;var key=new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16));
        saved[(Math.floorMod(y,16)<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16)]=Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        var source=new GeneratedSkySource(generator,Map.of(key,saved));
        h.assertTrue(source.stateId(x,y,z)==Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()),"deep saved ocean edit wins");
        boolean refused=false;try{new GeneratedSkySource(generator,Map.of(new CubeKey(0,-82,0),saved));}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"whole saved cube below local protected floor rejected");
        var roof=new int[4096];Arrays.fill(roof,Block.getId(Blocks.AIR.defaultBlockState()));roof[0]=Block.getId(Blocks.STONE.defaultBlockState());
        var upper=new GeneratedSkySource(generator,Map.of(new CubeKey(0,21,0),roof));
        h.assertTrue(upper.directSkyFast(0,335,0)==0&&upper.directSkyFast(0,337,0)==15,"saved roof above native ceiling blocks sky");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void localExposureCacheReopensWithBothDomains(GameTestHelper h) throws java.io.IOException {
        var generator=generator(h);var first=new GeneratedSkySource(generator,Map.of());
        var directory=java.nio.file.Files.createTempDirectory("mia-island-local-sky-");
        try {
            first.restoreLightCache(directory,"island-local-domain-fixture");
            first.columnExposure(0,0);first.columnExposure(-10000,-100);first.persistLightCache();
            h.assertTrue(first.lightCacheWarning().isEmpty(),"local domain sky cache persisted");
            var reopened=new GeneratedSkySource(generator,Map.of());reopened.restoreLightCache(directory,"island-local-domain-fixture");
            h.assertTrue(reopened.lightCacheStatus().equals("HIT"),"exact island lighting identity reopens derived cache");
            var inner=reopened.cachedColumnExposure(0,0).orElseThrow();
            var sea=reopened.cachedColumnExposure(-10000,-100).orElseThrow();
            h.assertTrue(inner.minimumY()==-1286&&sea.minimumY()==-5000,"disk columns restore spatial lower bounds rather than global envelope");
            h.assertTrue(sea.directSky(-4000)==first.directSky(-10000,-4000,-100),"deep reopened exposure matches original");
            boolean refused=false;try{inner.directSky(-1287);}catch(IllegalArgumentException expected){refused=true;}
            h.assertTrue(refused,"reopened center still refuses unknown light");
            var old=new ParameterizedTerrainChunkGenerator(generator.parameters(),(FixedBiomeSource)generator.getBiomeSource(),-64,384);
            h.assertTrue(!first.cacheGeometryIdentity().equals(new GeneratedSkySource(old,Map.of()).cacheGeometryIdentity()),"island geometry cannot reuse legacy lighting identity");
            h.succeed();
        } finally {
            try(var files=java.nio.file.Files.walk(directory)){for(var file:files.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(file);}
        }
    }
    private static ParameterizedTerrainChunkGenerator generator(GameTestHelper h) {
        var p=new TerrainParameters(20261003,new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
                new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),new TerrainParameters.Noise(140,20,60,70));
        var recipe=IslandSurfaceGeneration.proposal();
        return new ParameterizedTerrainChunkGenerator(p,new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)),
                -64,384,null,null,null,null,null,null,null,null,new IslandSurfaceGeneration(recipe,p),recipe.toString());
    }
}
