package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LivingAbyssPart3aGameTests {
    @GameTest(template="empty",batch="aV15Part3a",timeoutTicks=1200)
    public static void nativeGroundCoverSurvivesOnItsActualSupportAndKeepsDoubleHalves(GameTestHelper h)throws Exception{
        var generator=LivingAbyssGameTests.profile(h).generator();var living=generator.livingAbyss();
        var reader=(net.minecraft.world.level.LevelReader)java.lang.reflect.Proxy.newProxyInstance(net.minecraft.world.level.LevelReader.class.getClassLoader(),new Class<?>[]{net.minecraft.world.level.LevelReader.class},(proxy,method,args)->{
            if(method.getName().equals("getBlockState")){var p=(net.minecraft.core.BlockPos)args[0];return generator.sampleBlock(p.getX(),p.getY(),p.getZ());}
            if(method.getName().equals("getFluidState")){var p=(net.minecraft.core.BlockPos)args[0];return generator.sampleBlock(p.getX(),p.getY(),p.getZ()).getFluidState();}
            return method.invoke(h.getLevel(),args);
        });
        var bad=new ArrayList<String>();int[] count={0};
        living.flora().features().visit((x,y,z,code)->{
            if(code<dev.madeinabyss.kernel.terrain.living.LivingAbyssFeatureBlock.SHORT_GRASS.ordinal()||code>dev.madeinabyss.kernel.terrain.living.LivingAbyssFeatureBlock.DANDELION.ordinal())return;
            count[0]++;var state=generator.sampleBlock(x,y,z);
            if(!state.canSurvive(reader,new net.minecraft.core.BlockPos(x,y,z))&&bad.size()<12)bad.add(x+","+y+","+z+" "+state+" support="+generator.sampleBlock(x,y-1,z));
        });
        h.assertTrue(count[0]>10000,"nonempty actual native cover");h.assertTrue(bad.isEmpty(),"native cover support failed: "+bad);
        System.out.println("MIA_V15_COVER_SUPPORT checked="+count[0]+" invalid="+bad.size());h.succeed();
    }
    @GameTest(template="empty",batch="aV15Part3a",timeoutTicks=1200)
    public static void nativeLeafDistanceUpdatesAndDripFrustumsRemainInTheExplicitPalette(GameTestHelper h){
        var bad=new ArrayList<String>();
        for(var block:List.of(Blocks.OAK_LEAVES,Blocks.BIRCH_LEAVES,Blocks.DARK_OAK_LEAVES,Blocks.AZALEA_LEAVES,Blocks.FLOWERING_AZALEA_LEAVES))for(int distance=1;distance<=7;distance++){
            var state=block.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,true).setValue(BlockStateProperties.DISTANCE,distance);
            if(!LivingAbyssBlockStates.isKnown(state))bad.add(state.toString());
        }
        var frustum=Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(BlockStateProperties.VERTICAL_DIRECTION,net.minecraft.core.Direction.DOWN).setValue(BlockStateProperties.DRIPSTONE_THICKNESS,net.minecraft.world.level.block.state.properties.DripstoneThickness.FRUSTUM);
        if(!LivingAbyssBlockStates.isKnown(frustum))bad.add(frustum.toString());
        h.assertTrue(bad.isEmpty(),"native neighbour changes must retain v15 palette closure: "+bad);h.succeed();
    }
    @GameTest(template="empty",batch="aV15Part3a",timeoutTicks=1200)
    public static void revision153LodGrassUsesApprovedDarkOwnerRgb(GameTestHelper h)throws Exception{
        var profile=LivingAbyssGameTests.profile(h);var source=new GeneratedSkySource(profile,Map.of());
        var grass=new TerrainLodColumns.Box(-33,-500,-33,-32,-499,-32,TerrainMaterial.GRASS);
        h.assertTrue(LivingAbyssColors.lod(source,grass)==0xff4e7d33,"section21 grass owner must be #4E7D33");
        h.assertTrue(LivingAbyssColors.legacyLod(grass)==0xff688a41,"v14 color remains unchanged");h.succeed();
    }
    @GameTest(template="empty",batch="aV15Part3a",timeoutTicks=1200)
    public static void treeStampsAndSurfacePassesAreBakedIntoTheProfile(GameTestHelper h)throws Exception{
        var status=LivingAbyssGameTests.profile(h).generator().livingAbyss().status();
        h.assertTrue(status.has("treeStampCount")&&status.get("treeStampCount").getAsInt()>20,"deterministic multi-kind stamp library is built with profile");
        h.assertTrue(status.has("treeCount")&&status.get("treeCount").getAsInt()>100,"nonempty surface/giant/inverted tree placement");
        h.assertTrue(status.has("surfaceColumns")&&status.get("surfaceColumns").getAsInt()>10000,"all surface passes have column index");h.succeed();
    }
    @GameTest(template="empty",batch="aV15Part3a",timeoutTicks=1200)
    public static void allApprovedPart3aPlantAndRuinStatesHaveExplicitLodAdmission(GameTestHelper h){
        var states=new ArrayList<net.minecraft.world.level.block.state.BlockState>();
        for(var b:List.of(Blocks.MOSS_CARPET,Blocks.ROOTED_DIRT,Blocks.COARSE_DIRT,Blocks.MANGROVE_ROOTS,Blocks.HANGING_ROOTS,
                Blocks.OAK_LOG,Blocks.DARK_OAK_LOG,Blocks.GRASS,Blocks.FERN,Blocks.OXEYE_DAISY,Blocks.AZURE_BLUET,Blocks.LILY_OF_THE_VALLEY,Blocks.CORNFLOWER,Blocks.BLUE_ORCHID,Blocks.DANDELION,
                Blocks.MOSSY_STONE_BRICKS,Blocks.CRACKED_STONE_BRICKS,Blocks.CHISELED_STONE_BRICKS,Blocks.COBBLESTONE,Blocks.GRAVEL))states.add(b.defaultBlockState());
        for(var b:List.of(Blocks.OAK_LEAVES,Blocks.DARK_OAK_LEAVES,Blocks.AZALEA_LEAVES,Blocks.FLOWERING_AZALEA_LEAVES))states.add(b.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,true));
        var missing=states.stream().filter(s->!LivingAbyssBlockStates.isKnown(s)).toList();
        h.assertTrue(missing.isEmpty(),"all new blocks belong to LivingAbyssBlockStates: "+missing);h.succeed();
    }
}
