package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/** Separate ORIGINAL diagnostic shaft; never changes the versioned DEP-02 corridor generator. */
public final class VerticalShaftFixture {
    public static final int CENTER=192, START_Y=80, FLOOR_Y=-22000;
    private static final TicketType<ChunkPos> ENTRANCE_TICKET=TicketType.create("mia_vertical_entrance",Comparator.comparingLong(ChunkPos::toLong));
    private VerticalShaftFixture() {}
    public static CubeSnapshot generate(CubeKey key) {
        Objects.requireNonNull(key);
        int[] states = new int[CubeSnapshot.BLOCK_COUNT];
        Arrays.fill(states, Block.getId(Blocks.AIR.defaultBlockState()));
        // Signed section coordinates can describe origins outside block-int range. Never wrap them into the shaft.
        long originX = (long) key.x() * 16, originY = (long) key.y() * 16, originZ = (long) key.z() * 16;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            long gx = originX + x, gy = originY + y, gz = originZ + z;
            if (gx < CENTER - 2 || gx > CENTER + 2 || gz < CENTER - 2 || gz > CENTER + 2
                    || gy < FLOOR_Y || gy >= -64) continue;
            BlockState state;
            if (gy == FLOOR_Y) {
                state = gx == CENTER && gz == CENTER ? Blocks.DIAMOND_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState();
            } else if (gx == CENTER - 2 || gx == CENTER + 2 || gz == CENTER - 2 || gz == CENTER + 2) {
                state = Blocks.STONE.defaultBlockState();
                if (gx == CENTER - 2 && gz == CENTER) {
                    if (gy == -4096) state = Blocks.GOLD_BLOCK.defaultBlockState();
                    else if (gy == -15904) state = Blocks.DIAMOND_BLOCK.defaultBlockState();
                    else if (gy == -20000) state = Blocks.EMERALD_BLOCK.defaultBlockState();
                }
            } else continue;
            states[y << 8 | z << 4 | x] = Block.getId(state);
        }
        return new CubeSnapshot(key, states);
    }

    /** Destructive only within the backed-up dev fixture's 5x5 ordinary-height entrance, including bedrock. */
    public static void prepareNativeEntrance(ServerLevel level) {
        Objects.requireNonNull(level);
        if (!Boolean.getBoolean("mia.cube.playerProbe") || level.dimension() != Level.OVERWORLD)
            throw new IllegalStateException("Vertical entrance requires the opt-in overworld development probe");
        int nativeMin = level.getMinBuildHeight();
        if (nativeMin != -64 || level.getHeight() != 384)
            throw new IllegalStateException("Vertical entrance expects unchanged native -64/384 height");
        // FULL(true) alone adds UNKNOWN tickets which expire after one tick. Retain the
        // four ordinary columns until the last fixture owner leaves; movement reads stay non-generating.
        for(int x=11;x<=12;x++) for(int z=11;z<=12;z++) {
            var key=new ChunkPos(x,z);
            level.getChunkSource().addRegionTicket(ENTRANCE_TICKET,key,0,key);
            level.getChunk(x,z);
        }
        for (int y = nativeMin; y <= START_Y + 2; y++) for (int z = CENTER - 2; z <= CENTER + 2; z++) {
            for (int x = CENTER - 2; x <= CENTER + 2; x++) {
                boolean wall = x == CENTER - 2 || x == CENTER + 2 || z == CENTER - 2 || z == CENTER + 2;
                level.setBlock(new BlockPos(x, y, z), wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    public static void releaseNativeEntrance(ServerLevel level) {
        for(int x=11;x<=12;x++) for(int z=11;z<=12;z++) {
            var key=new ChunkPos(x,z);
            level.getChunkSource().removeRegionTicket(ENTRANCE_TICKET,key,0,key);
        }
    }
}
