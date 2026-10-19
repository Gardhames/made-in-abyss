package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LivingAbyssPart3bGameTests {
    @GameTest(template="empty",batch="aV15Part3b",timeoutTicks=1200)
    public static void underwaterPlantsKeepTheirNativeBlockModelAsWellAsTheirWater(GameTestHelper h)throws Exception{
        var method=java.util.Arrays.stream(IslandSurfaceBlockStates.class.getMethods()).filter(m->m.getName().equals("admitsNativeModel")).findFirst();
        h.assertTrue(method.isPresent(),"native model admission must not discard every nonempty fluid state");
        for(var block:List.of(Blocks.SEAGRASS,Blocks.TALL_SEAGRASS,Blocks.KELP,Blocks.KELP_PLANT))
            h.assertTrue((boolean)method.orElseThrow().invoke(null,block.defaultBlockState(),true),"aquatic native model missing: "+block);
        h.assertTrue(!(boolean)method.orElseThrow().invoke(null,Blocks.WATER.defaultBlockState(),true),"pure water stays on the native liquid pass");h.succeed();
    }
    @GameTest(template="empty",batch="aV15Part3b",timeoutTicks=1200)
    public static void nativeWaterAndAquaticNeighbourStatesCloseTheLodPalette(GameTestHelper h){
        var missing=new ArrayList<String>();
        for(var block:List.of(Blocks.WATER,Blocks.SEAGRASS,Blocks.TALL_SEAGRASS,Blocks.KELP,Blocks.KELP_PLANT,
                Blocks.LILY_PAD,Blocks.SUGAR_CANE,Blocks.SAND,Blocks.CLAY,Blocks.MUD))
            for(var state:block.getStateDefinition().getPossibleStates())if(!LivingAbyssBlockStates.isKnown(state))missing.add(state.toString());
        h.assertTrue(missing.isEmpty(),"native flowing water and aquatic state transitions must be explicitly admitted: "+missing.stream().limit(12).toList());h.succeed();
    }
    @GameTest(template="empty",batch="aV15Part3b",timeoutTicks=1200)
    public static void realProfileContainsPondsWaterfallsAndPrototypeSpires(GameTestHelper h)throws Exception{
        var status=LivingAbyssGameTests.profile(h).generator().livingAbyss().status();
        h.assertTrue(status.has("waterFeatures")&&status.getAsJsonArray("waterFeatures").size()>0,"section17/22 water features must be baked into actual profile");
        h.assertTrue(status.has("landmarks"),"full-sized section13.6 landmark diagnostics must be explicit");
        h.assertTrue(status.has("spireArchetypes"),"section18 replaces the basic cylinders");h.succeed();
    }
    @GameTest(template="empty",batch="aV15Part3b",timeoutTicks=1200)
    public static void revision154SeparatesDarkSoilAndTrunkLodOwners(GameTestHelper h)throws Exception{
        var profile=LivingAbyssGameTests.profile(h);var source=new GeneratedSkySource(profile,Map.of());
        var soil=new TerrainLodColumns.Box(-33,-500,-33,-32,-499,-32,TerrainMaterial.SOIL);
        h.assertTrue(LivingAbyssColors.lod(source,soil)==0xff5b4b3c,"section23 soil owner #5B4B3C");
        var tree=profile.generator().livingAbyss().flora().trees().stream().filter(t->t.kind().equals("broad_oak")).findFirst().orElseThrow();
        var trunk=new TerrainLodColumns.Box(tree.x(),tree.y()+3,tree.z(),tree.x()+1,tree.y()+4,tree.z()+1,TerrainMaterial.SOIL);
        h.assertTrue(LivingAbyssColors.lod(source,trunk)==0xff4a3b2c,"section23 trunk owner #4A3B2C");
        h.assertTrue(LivingAbyssColors.legacyLod(soil)==0xff745337,"v14 owner color remains unchanged");h.succeed();
    }
}
