package dev.madeinabyss.forge.probe;

import com.mojang.authlib.GameProfile;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepSurvivalPlacement;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Files;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Breaks caught: native placement bypasses stack cost, Forge cancellation, or full XYZ identity. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepSurvivalPlacementGameTests {
    private static final BlockPos CLICKED = new BlockPos(-1, -20000, -1);
    private static final BlockPos TARGET = new BlockPos(-1, -19999, -1);
    private static final BlockPos ALIAS = new BlockPos(-1, -15903, -1);

    @GameTest(template = "empty")
    public static void actualNativeSurvivalPlaceConsumesOneTaggedStoneAtSignedDepth(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var tag = f.player.getMainHandItem().getTag().copy();
            var result = f.place();
            h.assertTrue(result.consumesAction(), "actual native survival placement must succeed at Y=-19999");
            h.assertTrue(f.level.getBlockState(TARGET).is(Blocks.STONE), "native write reaches true signed target");
            h.assertTrue(f.player.getMainHandItem().getCount() == 2 && tag.equals(f.player.getMainHandItem().getTag()),
                    "native BlockItem spends exactly one stone while retaining its complete tag");
            h.assertTrue(f.level.getBlockState(ALIAS).is(Blocks.GOLD_BLOCK), "Y+4096 alias remains unchanged");
            var saved=f.store.read(CubeKey.fromBlock(coordinate(TARGET))).orElseThrow();
            h.assertTrue(saved.states()[511]==Block.getId(Blocks.STONE.defaultBlockState()),
                    "actual MISP cube on disk contains placed stone before native success returns");
            h.assertTrue(f.events.placements == 1 && TARGET.equals(f.events.position),
                    "one actual Forge EntityPlaceEvent receives full signed snapshot position");
            h.assertTrue(!f.level.captureBlockSnapshots && !f.level.restoringBlockSnapshots
                    && f.level.capturedBlockSnapshots.isEmpty(), "native snapshot flags/list do not leak");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void actualForgePlaceCancellationRestoresCubeAndWholeInventory(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            f.events.cancel = true;
            var before = f.inventory();
            var result = f.place();
            h.assertTrue(f.events.placements == 1 && TARGET.equals(f.events.position),
                    "cancellation must run on the real Forge snapshot event, not an empty snapshot list");
            h.assertTrue(result == InteractionResult.FAIL && f.level.getBlockState(TARGET).isAir(),
                    "Forge cancellation rolls native placement back to resident AIR");
            h.assertTrue(before.equals(f.inventory()) && f.level.getBlockState(ALIAS).is(Blocks.GOLD_BLOCK),
                    "canceled placement preserves all native inventory NBT and distant alias");
            h.assertTrue(!f.level.captureBlockSnapshots && !f.level.restoringBlockSnapshots
                    && f.level.capturedBlockSnapshots.isEmpty(), "cancellation leaves no snapshot state behind");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void survivalPlacementRefusesUnknownNeighborWithoutStackLoss(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            f.space.unload(new CubeKey(-1, -1250, 0)); // Target's north/south neighborhood is now unknown.
            var before = f.inventory();
            h.assertTrue(!f.place().consumesAction(), "UNKNOWN neighbor refuses native placement");
            h.assertTrue(f.level.getBlockState(TARGET).isAir() && before.equals(f.inventory()),
                    "refusal preserves destination and complete inventory without treating UNKNOWN as AIR");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void survivalPlacementRefusesPlayerCollisionWithoutInventoryMutation(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            f.player.setPos(-.5, -19999, -.5);
            var before = f.inventory();
            h.assertTrue(!f.place().consumesAction(), "placement through player's real full XYZ collider refuses");
            h.assertTrue(f.level.getBlockState(TARGET).isAir() && before.equals(f.inventory()),
                    "collision refusal leaves known block and inventory intact");
        }
        h.succeed();
    }

    // Break caught: IO refusal leaves a spent stack or replaces the previously acknowledged native cube file.
    @GameTest(template="empty")
    public static void survivalPlacementDiskRefusalKeepsExactNativeInventoryAndDurableBytes(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            var before=f.inventory();var bytes=Files.readAllBytes(f.store.path(CubeKey.fromBlock(coordinate(TARGET))));
            f.failWrite=true;
            h.assertTrue(f.place()==InteractionResult.FAIL,"failed durable acknowledgement refuses native placement");
            h.assertTrue(f.level.getBlockState(TARGET).isAir() && before.equals(f.inventory()),"save failure rolls back live block and whole tagged inventory");
            h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(f.store.path(CubeKey.fromBlock(coordinate(TARGET))))),"previous durable MISP bytes are untouched");
            h.assertTrue(f.events.placements==1,"native placement reached actual Forge event before injected IO refusal");
        }
        h.succeed();
    }

    // Break caught: a repeated use against an occupied destination spends another stack without another placement.
    @GameTest(template="empty")
    public static void repeatedNativeUseCannotSpendTwiceOnTheSameOccupiedCell(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            h.assertTrue(f.place().consumesAction(),"first native placement succeeds");
            var before=f.inventory();var bytes=Files.readAllBytes(f.store.path(CubeKey.fromBlock(coordinate(TARGET))));
            h.assertTrue(!f.place().consumesAction(),"second native use of original floor hit refuses occupied cell");
            h.assertTrue(before.equals(f.inventory()) && f.events.placements==1,"second use cannot spend a stack or emit another place event");
            h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(f.store.path(CubeKey.fromBlock(coordinate(TARGET))))),"occupied refusal retains exact acknowledged file");
        }
        h.succeed();
    }

    public static final class Events {
        final UUID player;
        int placements;
        boolean cancel;
        BlockPos position;
        Events(UUID player) { this.player = player; }
        @SubscribeEvent public void placed(BlockEvent.EntityPlaceEvent event) {
            if (event.getEntity() == null || !event.getEntity().getUUID().equals(player)) return;
            placements++; position = event.getBlockSnapshot().getPos();
            if (cancel) event.setCanceled(true);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final ServerPlayer player;
        final SparseCubeSpace space = new SparseCubeSpace();
        final Optional<DeepCubeDomain.Attachment> previous;
        final List<Packet<?>> packets = new ArrayList<>();
        final Events events;
        final StableCubeStore store=new StableCubeStore(java.nio.file.Path.of(
                System.getProperty("mia.storageRoot", ".local") + "/evidence/survival/place-game-tests",UUID.randomUUID().toString()),"madeinabyss:survival_test",1);
        boolean failWrite;
        Fixture(GameTestHelper h) throws Exception {
            level = h.getLevel(); previous = DeepCubeDomain.lookup(level);
            for (int x = -2; x <= 0; x++) for (int y = -1251; y <= -1249; y++) for (int z = -2; z <= 0; z++)
                space.loadEmpty(new CubeKey(x, y, z));
            space.loadEmpty(new CubeKey(-1, -994, -1));
            space.setBlock(coordinate(CLICKED), Block.getId(Blocks.STONE.defaultBlockState()));
            space.setBlock(coordinate(ALIAS), Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()));
            store.write(space.snapshot(CubeKey.fromBlock(coordinate(TARGET))).orElseThrow());
            DeepCubeDomain.attach(level, space, false);
            DeepSurvivalPlacement.attach(level,space,new dev.madeinabyss.kernel.cube.CubeStore() {
                @Override public Optional<dev.madeinabyss.kernel.cube.CubeSnapshot> read(CubeKey key) throws java.io.IOException {return store.read(key);}
                @Override public void write(dev.madeinabyss.kernel.cube.CubeSnapshot snapshot) throws java.io.IOException {
                    if(failWrite)throw new java.io.IOException("injected placement acknowledgement failure");
                    store.write(snapshot);
                }
            });
            player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "SurPlace"));
            new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player) {
                @Override public void send(Packet<?> packet) { packets.add(packet); }
            };
            player.setGameMode(GameType.SURVIVAL); player.setPos(2.5, -19999, -.5); player.setOnGround(true);
            var stack = new ItemStack(Items.STONE, 3);
            stack.getOrCreateTag().putString("miaSurToken", player.getUUID().toString());
            stack.getOrCreateTag().putIntArray("signedCoordinates", new int[]{-1, -19999, -15903});
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            events = new Events(player.getUUID()); MinecraftForge.EVENT_BUS.register(events);
        }
        InteractionResult place() {
            return player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(new Vec3(-.5, -19999, -.5), Direction.UP, CLICKED, false));
        }
        ListTag inventory() { return player.getInventory().save(new ListTag()); }
        @Override public void close() {
            MinecraftForge.EVENT_BUS.unregister(events);
            DeepSurvivalPlacement.detach(level);
            DeepCubeDomain.detach(level);
            previous.ifPresent(a -> DeepCubeDomain.attach(level, a.space(), a.blockLightEnabled()));
        }
    }
    private static BlockCoordinate coordinate(BlockPos pos) { return new BlockCoordinate(pos.getX(), pos.getY(), pos.getZ()); }
}
