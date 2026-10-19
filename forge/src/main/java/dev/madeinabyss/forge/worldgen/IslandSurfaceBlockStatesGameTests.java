package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;
import java.util.HashSet;

/** The exact admission predicate used by both native mesh builders, without loading client classes. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IslandSurfaceBlockStatesGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void everyActualIslandStateAdmittedByBothNativeMeshPaths(GameTestHelper h){
        var actual=IslandSurfaceGenerationGameTests.island().palette();
        h.assertTrue(actual.size()==15&&new HashSet<>(actual).equals(IslandSurfaceBlockStates.palette()),"exact palette equals actual persisted island sampler");
        int opaque=0;
        for(var state:actual){
            h.assertTrue(IslandSurfaceBlockStates.admitsNative(state,true),"every actual island state admitted by shared generated predicate: "+state);
            if(state.canOcclude()){
                opaque++;
                h.assertTrue(state.getRenderShape()==RenderShape.MODEL&&!state.hasBlockEntity(),"opaque island states are native models, never silently excluded after admission");
            }
        }
        h.assertTrue(opaque>=10,"solid rock/soil/coast regressions exercised");
        h.assertTrue(!IslandSurfaceBlockStates.admitsNative(Blocks.NETHERRACK.defaultBlockState(),true),"unrelated solid remains unsupported");
        h.assertTrue(!IslandSurfaceBlockStates.admitsNative(Blocks.CHEST.defaultBlockState(),true),"unmanaged block entity guard retained");
        h.assertTrue(!IslandSurfaceBlockStates.admitsNative(Blocks.ANDESITE.defaultBlockState(),false),"ordinary diagnostic admission not expanded");
        h.assertTrue(IslandSurfaceBlockStates.admitsNative(Blocks.STONE.defaultBlockState(),false)
                &&IslandSurfaceBlockStates.admitsNative(Blocks.GLOWSTONE.defaultBlockState(),false),"legacy inert diagnostic palette retained");
        h.succeed();
    }
}
