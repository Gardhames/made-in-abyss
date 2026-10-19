package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.ManagedItemStore;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** One meaningful flow: native mine -> native loot -> native item -> inventory -> durable tombstone. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepSurvivalDropGameTests {
    @GameTest(template="empty")
    public static void oneNativeStoneDropIsPickedUpExactlyOnceAndCannotReturnAfterReload(GameTestHelper h) throws Exception {
        try(var f=new DeepSurvivalMiningGameTests.Fixture(h)) {
            f.level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(true,f.level.getServer());
            var id=UUID.randomUUID();var store=new ManagedItemStore(Path.of(System.getProperty("mia.storageRoot", ".local") + "/evidence/survival/drop-game-tests",id.toString()));
            var engine=new DeepManagedItemEngine(f.level,f.space,store,id,(player,admission)->{});
            dev.madeinabyss.forge.probe.deep.DeepSurvivalPlacement.lookup(f.level).orElseThrow().bindDrops(engine);
            try {
                var target=new BlockPos(-1,-19999,-1);
                f.action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
                for(int i=0;i<5;i++)f.player.gameMode.tick();
                f.action(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
                h.assertTrue(f.level.getBlockState(target).isAir(),"native mining with drops enabled removes the real deep block: "
                        +dev.madeinabyss.forge.probe.deep.DeepSurvivalPlacement.lookup(f.level).orElseThrow().lastError());
                var item=engine.item();
                h.assertTrue(item!=null && item.getClass()==ItemEntity.class && item.getItem().is(Items.COBBLESTONE) && item.getItem().getCount()==1,
                        "actual native stone loot/popResource produces one actual managed cobblestone ItemEntity");
                h.assertTrue(item.getX()>=-1 && item.getX()<0 && item.getY()>=-19999 && item.getY()< -19998 && item.getZ()>=-1 && item.getZ()<0,
                        "native fractional spawn keeps full signed XYZ inside mined cell");
                h.assertTrue(f.level.getEntity(id)==null && !item.shouldBeSaved(),"drop has one managed authority, with no vanilla column registration/save");
                engine.observers(List.of(new DeepManagedItemEngine.Observer(f.player,UUID.randomUUID(),f.space.residentKeys())));
                f.player.setPos(-.5,-19999,-.5);
                engine.step();h.assertTrue(cobble(f)==0,"native pickup delay prevents immediate collection");
                for(int i=0;i<12;i++)engine.step();
                h.assertTrue(cobble(f)==1 && engine.item()==null && store.load(id).isEmpty(),
                        "actual native pickup puts one cobble in inventory and durably tombstones the item");
                for(int i=0;i<5;i++)engine.step();
                h.assertTrue(cobble(f)==1,"further native ticks cannot pick up the removed notification stack again");
            } finally {engine.close();}
            var restored=new DeepManagedItemEngine(f.level,f.space,store,id,(player,admission)->{});
            try {
                restored.step();h.assertTrue(restored.item()==null && cobble(f)==1,"a new engine cannot resurrect the acknowledged pickup tombstone");
            } finally {restored.close();}
        }
        h.succeed();
    }
    private static int cobble(DeepSurvivalMiningGameTests.Fixture f) {
        int count=0;for(int i=0;i<f.player.getInventory().getContainerSize();i++) {
            var item=f.player.getInventory().getItem(i);if(item.is(Items.COBBLESTONE))count+=item.getCount();
        }return count;
    }
}
