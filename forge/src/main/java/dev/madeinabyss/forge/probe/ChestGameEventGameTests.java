package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepChestEngine;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.VanillaGameEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.List;

/** Native movement events must retain the Forge/body path even with the bounded chest adapter. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChestGameEventGameTests {
    private static final BlockPos CHEST=new BlockPos(-1,-19999,1);
    private static final Vec3 WALK=new Vec3(.25,-19999,2.75);

    @GameTest(template="empty")
    public static void nativeDeepStepWithoutChestEngineReachesOrdinaryEventBody(GameTestHelper h) {
        ordinaryDeepEvent(h,GameEvent.STEP);
    }
    @GameTest(template="empty")
    public static void nativeDeepLandingWithoutChestEngineReachesOrdinaryEventBody(GameTestHelper h) {
        ordinaryDeepEvent(h,GameEvent.HIT_GROUND);
    }
    private static void ordinaryDeepEvent(GameTestHelper h,GameEvent event) {
        var space=window();var level=h.getLevel();DeepCubeDomain.attach(level,space,false);
        var observer=new Observer(level,WALK);MinecraftForge.EVENT_BUS.register(observer);
        try {
            long generation=space.contentGeneration();var player=h.makeMockPlayer();
            level.gameEvent(event,WALK,GameEvent.Context.of(player,Blocks.STONE.defaultBlockState()));
            h.assertTrue(observer.events.equals(List.of(event)),"native movement event reaches the existing Forge event hook exactly once");
            h.assertTrue(space.contentGeneration()==generation && space.residentKeys().stream().allMatch(key->space.snapshot(key).orElseThrow().blockEntities().isEmpty()),"movement event does not mutate deep states or invent block entities");
            h.assertTrue(space.loadedCubeCount()==27,"movement listener dispatch does not load extra cubes");
        } finally {MinecraftForge.EVENT_BUS.unregister(observer);clean(h);}h.succeed();
    }

    @GameTest(template="empty")
    public static void nativeDeepMovementWithChestEnginePreservesPayloadAndEventBody(GameTestHelper h) {
        var space=window();var level=h.getLevel();DeepCubeDomain.attach(level,space,false);var engine=DeepChestEngine.attach(level,space);
        var observer=new Observer(level,WALK);MinecraftForge.EVENT_BUS.register(observer);
        try {
            h.assertTrue(engine.place(level,CHEST,Blocks.CHEST.defaultBlockState(),null),"bounded native chest fixture");
            var chest=engine.get(level,CHEST);chest.setItem(0,new ItemStack(Items.DIAMOND,17));
            var before=space.blockEntity(coordinate(CHEST)).orElseThrow();long generation=space.contentGeneration();var player=h.makeMockPlayer();
            level.gameEvent(GameEvent.STEP,WALK,GameEvent.Context.of(player));
            level.gameEvent(GameEvent.HIT_GROUND,WALK,GameEvent.Context.of(player,Blocks.STONE.defaultBlockState()));
            h.assertTrue(observer.events.equals(List.of(GameEvent.STEP,GameEvent.HIT_GROUND)),"STEP and HIT_GROUND retain native Forge event body while chest engine attached");
            h.assertTrue(space.contentGeneration()==generation && space.blockEntity(coordinate(CHEST)).orElseThrow().equals(before),"walking and landing preserve exact persisted chest payload");
            h.assertTrue(chest.getItem(0).getCount()==17 && chest.getOpenNess(1)==0 && engine.liveCount()==1 && space.scheduledTicks().pendingCount()==0,"ordinary events do not open chest, schedule ticks or alter inventory");
        } finally {MinecraftForge.EVENT_BUS.unregister(observer);clean(h);}h.succeed();
    }

    @GameTest(template="empty")
    public static void nativeContainerEventsConsumeOnlyKnownChestAndRejectAbsentInventory(GameTestHelper h) {
        var space=window();var level=h.getLevel();DeepCubeDomain.attach(level,space,false);var engine=DeepChestEngine.attach(level,space);
        var point=Vec3.atCenterOf(CHEST);var observer=new Observer(level,point);MinecraftForge.EVENT_BUS.register(observer);
        try {
            h.assertTrue(engine.place(level,CHEST,Blocks.CHEST.defaultBlockState(),null),"bounded native chest fixture");
            var chest=engine.get(level,CHEST);chest.setItem(0,new ItemStack(Items.DIAMOND,17));
            var before=space.blockEntity(coordinate(CHEST)).orElseThrow();long generation=space.contentGeneration();
            for(var event:List.of(GameEvent.CONTAINER_OPEN,GameEvent.CONTAINER_CLOSE))level.gameEvent(event,point,GameEvent.Context.of(chest.getBlockState()));
            h.assertTrue(observer.events.isEmpty(),"known chest decorative container events are consumed before native dispatcher");
            h.assertTrue(space.contentGeneration()==generation && space.blockEntity(coordinate(CHEST)).orElseThrow().equals(before) && chest.getItem(0).getCount()==17,"decorative event does not mutate inventory payload");
            for(var event:List.of(GameEvent.CONTAINER_OPEN,GameEvent.CONTAINER_CLOSE)) {
                boolean refused=false;try {level.gameEvent(event,WALK,GameEvent.Context.of(Blocks.AIR.defaultBlockState()));}catch(UnsupportedOperationException expected){refused=true;}
                h.assertTrue(refused,"container event without native chest stays strict: "+event);
            }
        } finally {MinecraftForge.EVENT_BUS.unregister(observer);clean(h);}h.succeed();
    }

    @GameTest(template="empty")
    public static void ordinaryHeightMovementAndContainerEventsRetainNativeBody(GameTestHelper h) {
        var space=window();var level=h.getLevel();DeepCubeDomain.attach(level,space,false);DeepChestEngine.attach(level,space);
        var point=Vec3.atCenterOf(h.absolutePos(new BlockPos(1,2,1)));var observer=new Observer(level,point);MinecraftForge.EVENT_BUS.register(observer);
        try {
            long generation=space.contentGeneration();var player=h.makeMockPlayer();
            var events=List.of(GameEvent.STEP,GameEvent.HIT_GROUND,GameEvent.CONTAINER_OPEN,GameEvent.CONTAINER_CLOSE);
            for(var event:events)level.gameEvent(event,point,GameEvent.Context.of(player));
            h.assertTrue(observer.events.equals(events),"all ordinary height events reach existing native Forge hook exactly once");
            h.assertTrue(space.contentGeneration()==generation && space.loadedCubeCount()==27,"ordinary native dispatch leaves deep storage unchanged");
        } finally {MinecraftForge.EVENT_BUS.unregister(observer);clean(h);}h.succeed();
    }

    public static final class Observer {
        private final Level level;private final Vec3 point;private final List<GameEvent> events=new ArrayList<>();
        private Observer(Level level,Vec3 point){this.level=level;this.point=point;}
        @SubscribeEvent public void onNativeEvent(VanillaGameEvent event){if(event.getLevel()==level&&event.getEventPosition().equals(point))events.add(event.getVanillaEvent());}
    }
    private static BlockCoordinate coordinate(BlockPos p){return new BlockCoordinate(p.getX(),p.getY(),p.getZ());}
    private static SparseCubeSpace window(){var space=new SparseCubeSpace();for(int x=-1;x<=1;x++)for(int y=-1251;y<=-1249;y++)for(int z=-1;z<=1;z++)space.loadEmpty(new CubeKey(x,y,z));return space;}
    private static void clean(GameTestHelper h){DeepChestEngine.detach(h.getLevel());DeepCubeDomain.detach(h.getLevel());}
}
