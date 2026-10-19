package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.forge.cube.CubeBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.util.Arrays;
import java.util.List;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CameraFogPresentationGameTests {
    @GameTest(template="empty")
    public static void pendingCameraRetainsLastConfirmedWaterLavaAndSnow(GameTestHelper h) {
        var history=new CameraFogPresentation();var session=new Object();
        h.assertTrue(history.pending(session)==FogType.NONE,"uninitialized camera presentation");
        for(var value:new FogType[]{FogType.WATER,FogType.LAVA,FogType.POWDER_SNOW,FogType.NONE}) {
            history.confirmed(session,value);
            h.assertTrue(history.pending(session)==value,"pending residency retains last actual camera fog: "+value);
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void resetAndSessionChangesNeverLeakOldCameraFog(GameTestHelper h) {
        var history=new CameraFogPresentation();var oldSession=new Object();var newSession=new Object();
        history.confirmed(oldSession,FogType.WATER);
        h.assertTrue(history.pending(oldSession)==FogType.WATER,"remember actual water first");
        h.assertTrue(history.pending(newSession)==FogType.NONE,"new world/session has no confirmed old fog");
        history.confirmed(newSession,FogType.LAVA);history.reset();
        h.assertTrue(history.pending(newSession)==FogType.NONE,"camera reset clears history");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void signedCameraWaterHeightPreflightRequiresActualUpperCube(GameTestHelper h) {
        var level=h.getLevel();var space=new SparseCubeSpace();var point=new BlockPos(-1,-561,-1);
        var lower=new CubeKey(-1,-36,-1);var upper=new CubeKey(-1,-35,-1);
        int[] air=new int[4096];Arrays.fill(air,Block.getId(Blocks.AIR.defaultBlockState()));
        space.load(new CubeSnapshot(lower,air));space.load(new CubeSnapshot(upper,air));
        space.setBlock(new BlockCoordinate(-1,-561,-1),Block.getId(Blocks.WATER.defaultBlockState()));
        DeepCubeDomain.attachGenerated(level,space,false);
        try {
            h.assertTrue(CameraFogPresentation.readable(level,List.of(point)),"known negative camera cell and upper-cell permit native read");
            var fluid=level.getFluidState(point);
            h.assertTrue(!fluid.isEmpty() && fluid.getHeight(level,point)>0,"actual known water height");
            space.unload(upper);
            h.assertTrue(!CameraFogPresentation.readable(level,List.of(point)),"water height needs upper-cell residency across signed Y seam");
            boolean strict=false;
            try {fluid.getHeight(level,point);}catch(CubeBlockView.MissingCubeException expected){strict=true;}
            h.assertTrue(strict,"camera guard never converts authoritative UNKNOWN fluid into AIR");
        } finally {DeepCubeDomain.detach(level);}
        h.succeed();
    }
}
