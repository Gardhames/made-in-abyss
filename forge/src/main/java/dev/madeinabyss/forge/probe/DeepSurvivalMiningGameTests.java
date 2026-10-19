package dev.madeinabyss.forge.probe;

import com.mojang.authlib.GameProfile;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepSurvivalPlacement;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Native mining clock/tool conservation. Loot is explicitly disabled here and tested in the next gate. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepSurvivalMiningGameTests {
    private static final BlockPos TARGET=new BlockPos(-1,-19999,-1);
    private static final CubeKey KEY=new CubeKey(-1,-1250,-1);
    @GameTest(template="empty")
    public static void nativeSurvivalMiningWaitsForProgressAndDamagesToolOnce(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            // Stone1.5 hardness, diamond speed8, grounded/harvestable: 8/1.5/30 per tick.
            h.assertTrue(Math.abs(f.level.getBlockState(TARGET).getDestroyProgress(f.player,f.level,TARGET)-8f/1.5f/30f)<1e-6,
                    "fixture uses actual native grounded diamond-pickaxe progress");
            f.action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
            f.player.gameMode.tick();f.player.gameMode.tick();
            h.assertTrue(f.level.getBlockState(TARGET).is(Blocks.STONE) && f.player.getMainHandItem().getDamageValue()==0,
                    "native START and early ticks cannot remove stone or damage tool");
            f.action(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            f.player.gameMode.tick();
            h.assertTrue(f.level.getBlockState(TARGET).is(Blocks.STONE),"early STOP waits for native delayed progress to complete");
            f.player.gameMode.tick();f.player.gameMode.tick();
            h.assertTrue(f.level.getBlockState(TARGET).isAir(),"completed native clock removes true Y=-19999 target");
            h.assertTrue(f.player.getMainHandItem().getDamageValue()==1,"completed native mineBlock damages actual tool exactly once");
            h.assertTrue(f.store.read(KEY).orElseThrow().states()[511]==Block.getId(Blocks.AIR.defaultBlockState()),
                    "completed deep mining saves native AIR before publication");
            h.assertTrue(f.packets.stream().noneMatch(p->p instanceof ClientboundBlockUpdatePacket),
                    "native mining must not send packed-Y block updates to the client's alias coordinate");
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void nativeMiningAbortCannotFinishLaterOrDamageTool(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            f.action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);f.player.gameMode.tick();f.player.gameMode.tick();
            f.action(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            for(int i=0;i<12;i++)f.player.gameMode.tick();
            h.assertTrue(f.level.getBlockState(TARGET).is(Blocks.STONE) && f.player.getMainHandItem().getDamageValue()==0,
                    "actual ABORT clears native progress without a late break or tool cost");
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void nativeMiningUnknownWindowCancelsProgressWithoutReadingAbsentCube(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            f.action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);f.player.gameMode.tick();
            var snapshot=f.space.unload(KEY).orElseThrow();
            f.player.gameMode.tick(); // Must suspend before native getBlockState, never substitute AIR.
            f.space.load(snapshot);for(int i=0;i<12;i++)f.player.gameMode.tick();
            h.assertTrue(f.level.getBlockState(TARGET).is(Blocks.STONE) && f.player.getMainHandItem().getDamageValue()==0,
                    "restored residency cannot finish a canceled deep mining operation using elapsed ticks");
        }
        h.succeed();
    }
    static final class Fixture implements AutoCloseable {
        final ServerLevel level;final ServerPlayer player;final SparseCubeSpace space=new SparseCubeSpace();
        final Optional<DeepCubeDomain.Attachment> previous;final List<Packet<?>> packets=new ArrayList<>();final boolean previousDrops;
        final StableCubeStore store=new StableCubeStore(java.nio.file.Path.of(System.getProperty("mia.storageRoot", ".local") + "/evidence/survival/mining-game-tests",UUID.randomUUID().toString()),"madeinabyss:survival_test",1);
        Fixture(GameTestHelper h) throws Exception {
            level=h.getLevel();previous=DeepCubeDomain.lookup(level);previousDrops=level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS);
            for(int x=-2;x<=0;x++)for(int y=-1251;y<=-1249;y++)for(int z=-2;z<=0;z++)space.loadEmpty(new CubeKey(x,y,z));
            space.setBlock(new BlockCoordinate(-1,-19999,-1),Block.getId(Blocks.STONE.defaultBlockState()));
            space.setBlock(new BlockCoordinate(-1,-20000,-1),Block.getId(Blocks.STONE.defaultBlockState()));
            store.write(space.snapshot(KEY).orElseThrow());DeepCubeDomain.attach(level,space,false);DeepSurvivalPlacement.attach(level,space,store);
            level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(false,level.getServer());
            player=new ServerPlayer(level.getServer(),level,new GameProfile(UUID.randomUUID(),"SurMine"));
            new ServerGamePacketListenerImpl(level.getServer(),new Connection(PacketFlow.SERVERBOUND){
                @Override public void send(Packet<?> packet,net.minecraft.network.PacketSendListener listener){packets.add(packet);}
            },player);
            player.setGameMode(GameType.SURVIVAL);player.setPos(2.5,-19999,-.5);player.setOnGround(true);
            var tool=new ItemStack(Items.DIAMOND_PICKAXE);tool.getOrCreateTag().putString("miaSurToken",player.getUUID().toString());player.setItemInHand(InteractionHand.MAIN_HAND,tool);packets.clear();
        }
        void action(ServerboundPlayerActionPacket.Action action){player.gameMode.handleBlockBreakAction(TARGET,action,Direction.UP,level.getMaxBuildHeight(),1);}
        @Override public void close(){
            level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(previousDrops,level.getServer());
            DeepSurvivalPlacement.detach(level);DeepCubeDomain.detach(level);previous.ifPresent(a->DeepCubeDomain.attach(level,a.space(),a.blockLightEnabled()));
        }
    }
}
