package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepFluidEngine;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.tick.CubeScheduledTicks;
import dev.madeinabyss.kernel.tick.SavedCubeTick;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.TickPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;

/** Actual NMS water callbacks against signed cube storage; no fake fluid solver. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepFluidGameTests {
    private static final BlockPos SOURCE=new BlockPos(-1,-20001,-1);

    @GameTest(template="empty")
    public static void nativeSourceDelayAndDownwardFalling(GameTestHelper h) {
        var level=h.getLevel(); var space=window(); var engine=attach(h,space);
        try {
            h.assertTrue(engine.edit(level,SOURCE,Blocks.WATER.defaultBlockState()),"native seed commits water source");
            h.assertTrue(engine.stats().pending()==1,"onPlace schedules one exact source tick");
            engine.step(level,4);
            h.assertTrue(level.getBlockState(SOURCE.below()).isAir(),"water delay5 must not spread at4");
            engine.step(level,5);
            var down=level.getBlockState(SOURCE.below());
            h.assertTrue(down.is(Blocks.WATER) && down.getValue(LiquidBlock.LEVEL)==8,"native downward spread writes falling level8");
            h.assertTrue(down.getFluidState().is(Fluids.FLOWING_WATER),"falling tick type remains FLOWING_WATER");
            h.assertTrue(engine.stats().completed()==1 && engine.stats().pending()>0,"one execution retains native future ticks");
            h.assertTrue(engine.drainChanged().contains(new CubeKey(-1,-1251,-1)),"negativeXYZ changed cube published");
        } finally { clean(h); }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void flatNativeDecayAndSourceRemovalAcrossNegativeSeam(GameTestHelper h) {
        var level=h.getLevel(); var space=window();
        for(int x=-12;x<=12;x++) for(int z=-12;z<=12;z++) put(space,new BlockPos(x,-20002,z),Blocks.STONE.defaultBlockState());
        var engine=attach(h,space);
        try {
            h.assertTrue(engine.edit(level,SOURCE,Blocks.WATER.defaultBlockState()),"negative seam seed");
            engine.step(level,5);
            h.assertTrue(level.getBlockState(SOURCE.east()).getValue(LiquidBlock.LEVEL)==1,"native first horizontal level1 across x=-1/0");
            engine.step(level,10);
            h.assertTrue(level.getBlockState(SOURCE.east(2)).getValue(LiquidBlock.LEVEL)==2,"native second horizontal level2");
            h.assertTrue(engine.edit(level,SOURCE,Blocks.AIR.defaultBlockState()),"native source removal committed");
            for(long t=15;t<=180;t+=5) engine.step(level,t);
            h.assertTrue(level.getBlockState(SOURCE.east()).isAir() && level.getBlockState(SOURCE.east(2)).isAir(),"neighbor callbacks drain flow after source removal");
        } finally { clean(h); }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void unknownDueTickRetainsStateAndQueueThenResumes(GameTestHelper h) {
        var level=h.getLevel(); var space=window(); var engine=attach(h,space);
        try {
            h.assertTrue(engine.edit(level,SOURCE,Blocks.WATER.defaultBlockState()),"seed before missing dependency");
            var missing=new CubeKey(0,-1251,-1); var saved=space.unload(missing).orElseThrow();
            long generation=space.contentGeneration(); int pending=space.scheduledTicks().pendingCount();
            engine.step(level,5);
            h.assertTrue(space.contentGeneration()==generation && space.scheduledTicks().pendingCount()==pending,"UNKNOWN performs no partial write or tick consumption");
            h.assertTrue(engine.stats().deferred()>0 && engine.stats().completed()==0,"missing halo remains deferred");
            space.load(saved); engine.step(level,6);
            h.assertTrue(level.getBlockState(SOURCE.below()).is(Blocks.WATER),"resident restoration resumes original due tick");
            h.assertTrue(engine.stats().completed()==1,"restored pending dispatched once");
        } finally { clean(h); }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void inheritedOverloadsKeepExactTypesAndPriority(GameTestHelper h) {
        var level=h.getLevel(); var space=window(); var engine=attach(h,space);
        try {
            put(space,SOURCE,Blocks.WATER.defaultBlockState());
            level.scheduleTick(SOURCE,Fluids.FLOWING_WATER,1,TickPriority.HIGH);
            level.scheduleTick(SOURCE,Fluids.FLOWING_WATER,0,TickPriority.EXTREMELY_HIGH);
            var due=space.scheduledTicks().due(1,10);
            h.assertTrue(due.size()==1 && due.get(0).priority()==-1 && due.get(0).triggerTick()==1,"pending dedup first-wins with native priority");
            engine.step(level,1);
            h.assertTrue(engine.stats().stale()==1 && engine.stats().completed()==0,"source must not execute stale flowing tick");
            h.assertTrue(level.getBlockState(SOURCE.below()).isAir(),"stale strict type does not spread");
            level.scheduleTick(SOURCE,Fluids.WATER,5);
            engine.step(level,6);
            h.assertTrue(level.getBlockState(SOURCE.below()).is(Blocks.WATER),"nonpriority inherited overload routes source queue");
        } finally { clean(h); }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void unsupportedNeighborRefusesWithoutPartialMutation(GameTestHelper h) {
        var level=h.getLevel(); var space=window(); var engine=attach(h,space);
        try {
            put(space,SOURCE,Blocks.WATER.defaultBlockState());
            put(space,SOURCE.east(3),Blocks.HOPPER.defaultBlockState());
            level.scheduleTick(SOURCE,Fluids.WATER,0);
            long generation=space.contentGeneration(); int pending=space.scheduledTicks().pendingCount();
            engine.step(level,0);
            h.assertTrue(space.contentGeneration()==generation && space.scheduledTicks().pendingCount()==pending,"unsupported BE does not consume pending or mutate room");
            h.assertTrue(engine.stats().unsupported()>0,"unsupported work is explicit");
            h.assertTrue(!engine.edit(level,SOURCE,Blocks.LAVA.defaultBlockState()),"lava refused before mutation");
            h.assertTrue(level.getBlockState(SOURCE).is(Blocks.WATER),"source preserved after rejected edit");
        } finally { clean(h); }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void queueCapacityRollbackPreservesNativeSourceSeed(GameTestHelper h) {
        var level=h.getLevel(); var space=window(); var engine=attach(h,space);
        try {
            for(int i=0;i<512;i++) {
                var p=new BlockCoordinate(-16+(i&15),-20001-(i>>8),-16+(i>>4&15));
                space.scheduledTicks().schedule(new CubeScheduledTicks.Tick(SavedCubeTick.Kind.BLOCK,"minecraft:stone",p,100,0,i));
            }
            long generation=space.contentGeneration();
            h.assertTrue(!engine.edit(level,SOURCE,Blocks.WATER.defaultBlockState()),"full cube tick capacity refuses seed transaction");
            h.assertTrue(space.contentGeneration()==generation && level.getBlockState(SOURCE).isAir(),"queue failure rolls back source and callbacks");
            h.assertTrue(space.scheduledTicks().pendingCount()==512 && engine.stats().rolledBack()>0,"original full queue preserved");
            h.assertTrue(engine.lastError().contains("budget"),"rollback exposes actual native queue budget failure diagnostic");
        } finally { clean(h); }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void fluidAttachmentPreservesOrdinaryNativeWritesAndQueue(GameTestHelper h) {
        var level=h.getLevel(); var space=window(); attach(h,space); var p=h.absolutePos(new BlockPos(1,2,1)); var original=level.getBlockState(p);
        try {
            h.assertTrue(level.setBlock(p,Blocks.WATER.defaultBlockState(),2),"ordinary native water write retained");
            h.assertTrue(level.getBlockState(p).is(Blocks.WATER) && level.getFluidTicks().hasScheduledTick(p,Fluids.WATER),"ordinary onPlace reaches native queue");
            h.assertTrue(space.scheduledTicks().pendingCount()==0,"ordinary tick never duplicates into cube queue");
            h.assertTrue(!level.setBlock(SOURCE,Blocks.WATER.defaultBlockState(),2),"deep write outside explicit transaction refused");
        } finally { level.setBlock(p,original,2); clean(h); }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void nativeDownwardSpreadCrossesActualNegativeYSectionSeam(GameTestHelper h) {
        var level=h.getLevel(); var space=window(); var engine=attach(h,space); var source=new BlockPos(-1,-20000,-1);
        try {
            h.assertTrue(engine.edit(level,source,Blocks.WATER.defaultBlockState()),"section-localY0 source seed commits");
            engine.step(level,5);
            var down=level.getBlockState(new BlockPos(-1,-20001,-1));
            h.assertTrue(down.is(Blocks.WATER) && down.getValue(LiquidBlock.LEVEL)==8,"native falling water reaches next lower signed section");
            var changes=engine.drainChanged();
            h.assertTrue(changes.contains(new CubeKey(-1,-1250,-1)) && changes.contains(new CubeKey(-1,-1251,-1)),"both real Y sections publish actual mutations");
        } finally { clean(h); }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void lateRestoredTickOrdersPrecedeNewNativeCallbacks(GameTestHelper h) {
        var level=h.getLevel(); var space=window(); var engine=attach(h,space);
        try {
            var restored=new BlockCoordinate(32,-20001,32); var key=new CubeKey(2,-1251,2);
            space.load(new CubeSnapshot(key,new int[CubeSnapshot.BLOCK_COUNT],List.of(
                    new SavedCubeTick(SavedCubeTick.Kind.FLUID,"minecraft:water",restored,5,0,9000))));
            h.assertTrue(engine.edit(level,SOURCE,Blocks.WATER.defaultBlockState()),"actual onPlace after new cube restoration");
            var due=space.scheduledTicks().due(5,10);
            h.assertTrue(due.size()==2 && due.get(0).position().equals(restored),"previously saved order wins same due/priority over new callback");
            h.assertTrue(due.get(1).order()>9000 && engine.stats().nextOrder()>due.get(1).order(),"native callback sequence advances past late incoming persistent high-water");
        } finally { clean(h); }
        h.succeed();
    }

    private static DeepFluidEngine attach(GameTestHelper h,SparseCubeSpace space) {
        DeepCubeDomain.attach(h.getLevel(),space,false); return DeepFluidEngine.attach(h.getLevel(),space);
    }
    private static void clean(GameTestHelper h) { DeepFluidEngine.detach(h.getLevel()); DeepCubeDomain.detach(h.getLevel()); }
    private static void put(SparseCubeSpace space,BlockPos p,net.minecraft.world.level.block.state.BlockState state) {
        space.setBlock(new BlockCoordinate(p.getX(),p.getY(),p.getZ()),Block.getId(state));
    }
    private static SparseCubeSpace window() {
        var space=new SparseCubeSpace();
        for(int x=-2;x<=1;x++) for(int y=-1252;y<=-1249;y++) for(int z=-2;z<=1;z++) space.loadEmpty(new CubeKey(x,y,z));
        return space;
    }
}
