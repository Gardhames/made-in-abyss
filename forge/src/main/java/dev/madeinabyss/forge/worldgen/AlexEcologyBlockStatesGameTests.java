package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.terrain.natural.AlexEcologyOverlay.Decoration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AlexEcologyBlockStatesGameTests {
    @GameTest(template="empty")
    public static void ecologyPaletteHasRegisteredStatesAndScalarContracts(GameTestHelper h) {
        checkPalette(h, new AlexEcologyBlockStates(false));
        if (ModList.get().isLoaded("alexscaves")) checkPalette(h, new AlexEcologyBlockStates(true));
        else {
            boolean refused=false;
            try { new AlexEcologyBlockStates(true); } catch (IllegalArgumentException expected) { refused=true; }
            h.assertTrue(refused,"explicit author palette fails closed without dependency");
        }
        h.succeed();
    }
    private static void checkPalette(GameTestHelper h, AlexEcologyBlockStates p) {
        for (Decoration d:Decoration.values()) h.assertTrue(p.state(d)!=null,"decoration mapped: "+d);
        for (var s:p.palette()) {
            h.assertTrue(Block.stateById(Block.getId(s)).equals(s),"registered state roundtrip "+s);
            h.assertTrue(!s.hasBlockEntity(),"no unmanaged BE "+s);
            h.assertTrue(!s.useShapeForLightOcclusion(),"scalar lighting must not require directional shape occlusion "+s);
            h.assertTrue(AlexEcologyBlockStates.isKnown(s)&&AlexEcologyBlockStates.material(s)!=null,"full-state classification "+s);
            int opacity=s.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            h.assertTrue(opacity>=0&&opacity<=15,"scalar opacity "+s);
            int light=s.getLightEmission(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            h.assertTrue(light>=0&&light<=15,"scalar emission "+s);
            s.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
        }
        h.assertTrue(p.state(Decoration.PEWEN_LOG_X).getValue(RotatedPillarBlock.AXIS)==Direction.Axis.X,"pewen X rotation");
        h.assertTrue(p.state(Decoration.PEWEN_LOG_Z).getValue(RotatedPillarBlock.AXIS)==Direction.Axis.Z,"pewen Z rotation");
        h.assertTrue(p.state(Decoration.THORNWOOD_LOG_X).getValue(RotatedPillarBlock.AXIS)==Direction.Axis.X,"thornwood X rotation");
        h.assertTrue(p.state(Decoration.THORNWOOD_LOG_Z).getValue(RotatedPillarBlock.AXIS)==Direction.Axis.Z,"thornwood Z rotation");
        h.assertTrue(AlexEcologyBlockStates.material(p.state(Decoration.AMBER))==dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK,"translucent amber remains rock");
        h.assertTrue(AlexEcologyBlockStates.material(p.state(Decoration.UNDERWEED))==dev.madeinabyss.kernel.terrain.TerrainMaterial.AIR,"noncolliding plants classify as air");
        h.assertTrue(!AlexEcologyBlockStates.isKnown(p.state(Decoration.THORNWOOD_LOG_X).setValue(RotatedPillarBlock.AXIS,Direction.Axis.Y)),"classification uses full selected state, not block identity");
    }
    @GameTest(template="empty")
    public static void authorPlantsRequireRealSoilPairsAndHangingSupport(GameTestHelper h) {
        if (!ModList.get().isLoaded("alexscaves")) { h.succeed(); return; }
        var p=new AlexEcologyBlockStates(true);
        var level=h.getLevel(); var at=h.absolutePos(new BlockPos(1,2,1));
        for(int dy=-1;dy<=3;dy++) level.setBlock(at.above(dy),Blocks.AIR.defaultBlockState(),2);
        level.setBlock(at.below(),Blocks.DIRT.defaultBlockState(),2);
        level.setBlock(at,p.state(Decoration.CURLY_LOWER),2);
        level.setBlock(at.above(),p.state(Decoration.CURLY_UPPER),2);
        h.assertTrue(p.state(Decoration.CURLY_LOWER).canSurvive(level,at),"curly lower on soil");
        h.assertTrue(p.state(Decoration.CURLY_UPPER).canSurvive(level,at.above()),"curly matching upper");
        h.assertTrue(!p.state(Decoration.CURLY_UPPER).setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,Direction.EAST).canSurvive(level,at.above()),"upper refuses mismatching facing");
        level.setBlock(at,Blocks.AIR.defaultBlockState(),2);
        h.assertTrue(!p.state(Decoration.CURLY_UPPER).canSurvive(level,at.above()),"upper refuses missing lower");
        level.setBlock(at.above(),Blocks.AIR.defaultBlockState(),2);
        level.setBlock(at,p.state(Decoration.CYCAD_TOP),2);
        h.assertTrue(p.state(Decoration.CYCAD_TOP).canSurvive(level,at),"cycad soil support");
        h.assertTrue(!p.state(Decoration.CYCAD_TOP).getCollisionShape(level,at).isEmpty(),"cycad collision must be respected");
        h.assertTrue(level.getBlockState(at.above()).isAir(),"cycad headroom remains clear");
        level.setBlock(at.below(),Blocks.WATER.defaultBlockState(),2);
        h.assertTrue(!p.state(Decoration.CYCAD_TOP).canSurvive(level,at),"cycad refuses water support");
        level.setBlock(at,p.state(Decoration.UNDERWEED),2);
        h.assertTrue(!p.state(Decoration.UNDERWEED).canSurvive(level,at),"underweed refuses water support");
        level.setBlock(at.below(),Blocks.STONE.defaultBlockState(),2);
        h.assertTrue(p.state(Decoration.UNDERWEED).canSurvive(level,at),"underweed accepts sturdy stone");
        level.setBlock(at.above(2),Blocks.STONE.defaultBlockState(),2);
        level.setBlock(at.above(),p.state(Decoration.VINE_BODY),2);
        level.setBlock(at,p.state(Decoration.VINE_HEAD),2);
        h.assertTrue(p.state(Decoration.VINE_BODY).canSurvive(level,at.above()),"vine body attached to ceiling");
        h.assertTrue(p.state(Decoration.VINE_HEAD).canSurvive(level,at),"vine head attached to body");
        level.setBlock(at.above(2),Blocks.AIR.defaultBlockState(),2);
        h.assertTrue(!p.state(Decoration.VINE_BODY).canSurvive(level,at.above()),"vine body refuses missing ceiling");
        level.setBlock(at.above(),Blocks.AIR.defaultBlockState(),2);
        h.assertTrue(!p.state(Decoration.VINE_HEAD).canSurvive(level,at),"vine head refuses missing body");
        h.succeed();
    }
}
