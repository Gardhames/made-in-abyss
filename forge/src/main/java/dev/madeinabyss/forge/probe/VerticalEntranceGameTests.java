package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.VerticalShaftFixture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VerticalEntranceGameTests {
    private VerticalEntranceGameTests() {}

    // Break caught: setBlock's temporary UNKNOWN loading ticket expires before ordinary-height
    // player preflight, so a prepared entrance becomes unavailable despite its generated blocks.
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeEntranceHaloRemainsAvailableAfterPreparationAndCacheEviction(GameTestHelper helper) {
        var level = helper.getLevel();
        VerticalShaftFixture.prepareNativeEntrance(level);
        helper.runAfterDelay(12, () -> {
            try {
            // ServerChunkCache has four last-chunk slots. Distinct non-generating requests evict
            // those shortcuts without adding loading tickets or reloading the entrance itself.
            for (int index = 0; index < 5; index++) {
                level.getChunkSource().getChunk(100000 + index, -100000, ChunkStatus.FULL, false);
            }
            helper.assertTrue(DeepCubeDomain.nativeColumnsLoaded(level, 184, 184, 201, 201),
                    "prepared ordinary entrance must retain all four native FULL columns for the player's eight-block halo after twelve ticks");
            helper.succeed();
            } finally {
                VerticalShaftFixture.releaseNativeEntrance(level);
            }
        });
    }
}
