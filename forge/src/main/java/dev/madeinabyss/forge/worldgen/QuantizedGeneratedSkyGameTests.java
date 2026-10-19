package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.light.SkyVisibility;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuantizedGeneratedSkyGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void nativeWaterScalarMatchesOriginalDenseFractionAndKeepsDirectMaximum(GameTestHelper h){
        var p=new TerrainParameters(20261003,new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
                new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),new TerrainParameters.Noise(140,20,60,70));
        var generator=new ParameterizedTerrainChunkGenerator(p,new FixedBiomeSource(h.getLevel().registryAccess()
                .registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)),-64,384,
                (x,y,z)->new TerrainSample.Known(y<0?TerrainMaterial.WATER:TerrainMaterial.AIR));
        var source=new GeneratedSkySource(generator,Map.of());
        for(int depth:new int[]{1,2,3,5,8,13,21,34,55,89,144,1000}){
            int y=-depth,x=-17-depth,z=-33;var reads=new AtomicInteger();
            int diffuse=SkyVisibility.diffuseLevel(SkyVisibility.visibleFraction(x,y,z,source.maxY(),100000,source::opacityFast));
            int direct=source.directSkyFast(x,y,z);
            int actual=source.ambientSky(x,y,z,(xx,yy,zz)->{reads.incrementAndGet();return source.opacityFast(xx,yy,zz);},direct);
            h.assertTrue(actual==Math.max(direct,diffuse),"native partial WATER exact integer/direct maximum depth="+depth);
            int preview=SkyVisibility.diffuseLevel(SkyVisibility.visibleFractionPreview(x,y,z,source.maxY(),100000,source::opacityFast));
            h.assertTrue(source.ambientSkyPreview(x,y,z,32)==Math.max(direct,preview),"independent8-ray preview keeps exact old response depth="+depth);
            if(depth==1000)h.assertTrue(reads.get()<3001,"native deep-water scalar consults bounded prefix, reads="+reads.get());
        }
        boolean refused=false;try{source.ambientSky(-17,-1287,-33);}catch(TerrainUnavailableException expected){refused=true;}
        h.assertTrue(refused,"fast scalar never admits unknown source coordinate");h.succeed();
    }
}
