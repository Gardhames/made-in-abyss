package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeBlockLightAccess;
import dev.madeinabyss.forge.probe.deep.DeepChestEngine;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.OptionalLightWork;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.Arrays;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChestLightAvailabilityGameTests {
    private static final BlockPos SOURCE=new BlockPos(-8,-19992,-8);
    private static final BlockPos CHEST=SOURCE.east();

    // Break caught: actual native chest input crashes optional hands/GUI, substitutes numbers, or leaves stale light after removal.
    @GameTest(template="empty")
    public static void nativeChestMakesLightTypedUnavailableAndOptionalWorkRecovers(GameTestHelper h) {
        var level=h.getLevel();var space=window();DeepCubeDomain.attach(level,space,true);var engine=DeepChestEngine.attach(level,space);
        try {
            h.assertTrue(level.getBrightness(LightLayer.BLOCK,SOURCE)==15 && level.getBrightness(LightLayer.BLOCK,CHEST)==14,"actual native BLOCK baseline is confirmed15/14");
            h.assertTrue(engine.place(level,CHEST,Blocks.CHEST.defaultBlockState(),null) && level.getBlockEntity(CHEST) instanceof ChestBlockEntity,"real native dry single chest is placed in resident27");
            boolean typed=false;
            try {level.getBrightness(LightLayer.BLOCK,SOURCE);}
            catch(CubeBlockLightAccess.UnavailableLightException expected){
                typed=true;
                h.assertTrue(expected.getMessage().contains(CHEST.toString()) && expected.getCause() instanceof UnsupportedOperationException,"typed unavailability reports actual unsupported chest XYZ/cause");
            } catch(UnsupportedOperationException generic){ /* Current crash: fail the typed contract below. */ }
            h.assertTrue(typed,"native chest must produce strict typed unavailable BLOCK light, never a generic render-thread failure or numeric value");
            h.assertTrue(DeepCubeDomain.lookup(level).orElseThrow().blockLight().computedCubes()==0,"chest input revokes previously published numeric light");
            for(var kind:new OptionalLightWork.Kind[]{OptionalLightWork.Kind.HANDS,OptionalLightWork.Kind.VIGNETTE}){
                long before=OptionalLightWork.skipped(kind);boolean[] used={false};
                boolean completed=OptionalLightWork.run(level,kind,()->{level.getRawBrightness(SOURCE,15);used[0]=true;});
                h.assertTrue(!completed && !used[0] && OptionalLightWork.skipped(kind)==before+1,"typed unsupported chest aborts and counts optional "+kind+" exactly once");
            }
            h.assertTrue(engine.remove(level,CHEST),"native chest removal succeeds");
            h.assertTrue(level.getBrightness(LightLayer.BLOCK,SOURCE)==15 && level.getBrightness(LightLayer.BLOCK,CHEST)==14,"actual prior known15/14 recover after removal");
            for(var kind:new OptionalLightWork.Kind[]{OptionalLightWork.Kind.HANDS,OptionalLightWork.Kind.VIGNETTE}){
                long before=OptionalLightWork.skipped(kind);boolean[] used={false};
                h.assertTrue(OptionalLightWork.run(level,kind,()->{h.assertTrue(level.getRawBrightness(SOURCE,15)==15,"recovered direct native raw brightness");used[0]=true;}) && used[0],"known optional work resumes "+kind);
                h.assertTrue(OptionalLightWork.skipped(kind)==before,"known work doesn't add skips");
            }
            h.assertTrue(space.loadedCubeCount()==27,"light handling never widens residency");
        } finally {DeepChestEngine.detach(level);DeepCubeDomain.detach(level);}
        h.succeed();
    }

    // Break caught: broad unsupported-state wrapping hides unrelated BEs, wet/double chests, or unsupported face shapes.
    @GameTest(template="empty")
    public static void unrelatedEntitiesAndShapesStillPropagate(GameTestHelper h) {
        var level=h.getLevel();var space=window();DeepCubeDomain.attach(level,space,true);
        try {
            for(var state: new net.minecraft.world.level.block.state.BlockState[]{Blocks.BARREL.defaultBlockState(),Blocks.TRAPPED_CHEST.defaultBlockState(),
                    Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true),Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.CHEST_TYPE,ChestType.LEFT),Blocks.OAK_SLAB.defaultBlockState()}){
                space.setBlock(coordinate(CHEST),Block.getId(state));long before=OptionalLightWork.skipped(OptionalLightWork.Kind.HANDS);boolean propagated=false;
                try{OptionalLightWork.run(level,OptionalLightWork.Kind.HANDS,()->level.getBrightness(LightLayer.BLOCK,SOURCE));}
                catch(UnsupportedOperationException expected){propagated=true;}
                h.assertTrue(propagated && OptionalLightWork.skipped(OptionalLightWork.Kind.HANDS)==before,"unrelated unsupported state propagates without counting skip: "+state);
                space.setBlock(coordinate(CHEST),Block.getId(Blocks.AIR.defaultBlockState()));
                h.assertTrue(level.getBrightness(LightLayer.BLOCK,CHEST)==14,"supported scalar inputs recover after unsupported fixture");
            }
        } finally {DeepCubeDomain.detach(level);}
        h.succeed();
    }

    private static BlockCoordinate coordinate(BlockPos p){return new BlockCoordinate(p.getX(),p.getY(),p.getZ());}
    private static SparseCubeSpace window(){var space=new SparseCubeSpace();int[] states=new int[4096];Arrays.fill(states,Block.getId(Blocks.STONE.defaultBlockState()));
        for(int x=-2;x<=0;x++)for(int y=-1251;y<=-1249;y++)for(int z=-2;z<=0;z++)space.load(new CubeSnapshot(new CubeKey(x,y,z),states));
        space.setBlock(coordinate(SOURCE),Block.getId(Blocks.GLOWSTONE.defaultBlockState()));space.setBlock(coordinate(CHEST),Block.getId(Blocks.AIR.defaultBlockState()));return space;}
}
