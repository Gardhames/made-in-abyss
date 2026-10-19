package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.OptionalAmbientLight;
import dev.madeinabyss.forge.cube.CubeBlockLightAccess;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.LightLayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OptionalAmbientLightGameTests {
    @GameTest(template="empty")
    public static void unknownAmbientLightSkipsWorkWithoutInventingDarkness(GameTestHelper helper) {
        var level=helper.getLevel(); var space=new SparseCubeSpace();
        for(int x=11;x<=13;x++) for(int y=-1376;y<=-1374;y++) for(int z=11;z<=13;z++)
            space.loadEmpty(new CubeKey(x,y,z));
        DeepCubeDomain.attach(level,space,true);
        try {
            boolean[] used={false};
            boolean completed=OptionalAmbientLight.tick(level,()->{
                // Exact failing native ambient sample: geometry is resident, boundary light is unknown.
                level.getBrightness(LightLayer.BLOCK,new BlockPos(189,-22002,195));
                used[0]=true;
            });
            helper.assertTrue(!completed && !used[0],"optional sound evaluation must skip its unknown input without a fake number");
            boolean unknown=false;
            try {level.getBrightness(LightLayer.BLOCK,new BlockPos(189,-22002,195));}
            catch(CubeBlockLightAccess.UnavailableLightException expected){unknown=true;}
            helper.assertTrue(unknown,"ordinary explicit deep light query must keep the strict unavailable contract");
            completed=OptionalAmbientLight.tick(level,()->{
                helper.assertTrue(level.getBrightness(LightLayer.BLOCK,new BlockPos(192,-21999,192))==0,
                        "hand-checked interior is beyond any unknown source's 14-block reach");
                used[0]=true;
            });
            helper.assertTrue(completed && used[0],"confirmed light retains the actual ambient operation");
        } finally {DeepCubeDomain.detach(level);}
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void ambientGuardDoesNotSwallowUnrelatedOrDetachedFailures(GameTestHelper helper) {
        var level=helper.getLevel(); var bug=new IllegalStateException("unrelated ambient failure");
        boolean propagated=false;
        try {OptionalAmbientLight.tick(level,()->{throw bug;});}
        catch(IllegalStateException actual){propagated=actual==bug;}
        helper.assertTrue(propagated,"guard cannot hide unrelated sound bugs");
        var unavailable=new CubeBlockLightAccess.UnavailableLightException(new BlockPos(0,-20000,0));
        propagated=false;
        try {OptionalAmbientLight.tick(level,()->{throw unavailable;});}
        catch(CubeBlockLightAccess.UnavailableLightException actual){propagated=actual==unavailable;}
        helper.assertTrue(propagated,"detached native work has no authorization to suppress failure");
        helper.succeed();
    }
}
