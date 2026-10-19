package dev.madeinabyss.forge.worldgen;

import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Native bulk preparation must not expand ordinary point/column consumers. */
@GameTestHolder("near_scope")
@PrefixGameTestTemplate(false)
public final class GeneratedNativeTileScopeGameTests {
    @GameTest(template="empty",batch="nearTileScope",timeoutTicks=1200)
    public static void genericSparseStateOpacityAndColumnQueriesDoNotPrepareWholeNativeTiles(GameTestHelper h)throws Exception {
        var source=new GeneratedSkySource(LivingAbyssGameTests.profile(h),Map.of());int x=-1,y=-900,z=-1;
        int expected=source.stateIdUncached(x,y,z);
        h.assertTrue(source.stateIdFast(x,y,z)==expected,"Sparse fast-state lookup keeps independent exact state");
        // Fail early on the old broad tile branch; RED does not execute a costly full column.
        h.assertTrue(source.exactTileBuilds()==0,"One generic sparse state lookup must not prepare4096 native states");
        h.assertTrue(source.stateIdForDerivedGeometry(x,y,z)==expected,"Sparse derived geometry retains exact state");
        h.assertTrue(source.opacityFast(x,y,z)==source.opacity(expected),"Generic sparse opacity keeps exact scalar");
        var column=source.columnExposure(x,z);h.assertTrue(column.directSky(y)==source.directSkyFast(x,y,z),"Generic direct column retains exact exposure");
        h.assertTrue(source.stateId(x,y,z)==expected,"Generic full-column state API retains the exact chosen point");
        h.assertTrue(source.exactTileBuilds()==0,"Generic state/opacity/column paths must not expand one column to16x16 neighbouring columns");
        h.succeed();
    }
}
