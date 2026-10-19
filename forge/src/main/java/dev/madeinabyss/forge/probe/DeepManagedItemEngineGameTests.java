package dev.madeinabyss.forge.probe;

import com.mojang.authlib.GameProfile;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.ManagedItemCodec;
import dev.madeinabyss.forge.cube.ManagedItemPersistence;
import dev.madeinabyss.forge.cube.ManagedItemSnapshot;
import dev.madeinabyss.forge.cube.ManagedItemStore;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemEngine;
import dev.madeinabyss.forge.probe.deep.DeepItemPackets;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Engine contracts; real native item/codec, with persistence faults injected only at acknowledged IO. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepManagedItemEngineGameTests {
    private static final Vec3 START = new Vec3(-16.5, -19998.5, -16.5);
    private static final CubeKey HOME = new CubeKey(-2, -1250, -2);
    private static final CubeKey HALO = new CubeKey(-1, -1250, -2);

    // Break caught: controller simulates motion, loses stack tags, or publishes a competing native disk authority.
    @GameTest(template = "empty")
    public static void nativeGravityLandsAtTrueDeepFloorAndSavesOneAuthority(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var store = new ManagedItemStore(directory());
            var engine = f.open(store);
            engine.seed(START, new Vec3(0, -.75, 0), "collision");
            var item = engine.item();
            h.assertTrue(item != null && item.getClass() == ItemEntity.class && !item.isNoGravity(),
                    "representative object is actual vanilla ItemEntity with gravity enabled");
            mark(item);
            for (int i = 0; i < 24; i++) engine.step();
            h.assertTrue(Math.abs(item.getY() + 19999) < 1e-7 && item.onGround() && !item.isRemoved(),
                    "native gravity and collision land real item feet on Y=-19999 without void removal");
            h.assertTrue(item.getX() == -16.5 && item.getZ() == -16.5 && item.tickCount == 24,
                    "full XYZ preserved and each permitted engine step ticks native object exactly once");
            h.assertTrue(h.getLevel().getEntity(f.id) == null && !item.shouldBeSaved(),
                    "managed item does not enter vanilla persistent-manager lookup/save authority");
            engine.flush();
            var restored = ManagedItemCodec.decode(h.getLevel(), f.id, store.load(f.id).orElseThrow().data());
            h.assertTrue(nativeTag(restored).equals(nativeTag(item)), "all acknowledged native NBT survives real disk codec");
            assertMarked(h, restored, f.id);
            try (var files = Files.walk(store.file(f.id).getParent())) {
                h.assertTrue(files.filter(Files::isRegularFile).count() == 1, "one UUID has exactly one persistent frame");
            }
        }
        h.succeed();
    }

    // Break caught: failed save releases an object or overwrites the previous acknowledged payload during eviction.
    @GameTest(template = "empty")
    public static void failedEvictionSaveRetainsLiveObjectAndAcknowledgedBytes(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var store = new FaultStore();
            var engine = f.open(store);
            engine.seed(START, Vec3.ZERO, "failed-unload");
            engine.flush();
            byte[] old = store.load(f.id).orElseThrow().data();
            var original = engine.item();
            mark(original);
            var before = nativeTag(original);
            var cube = f.space.unload(HOME).orElseThrow();
            store.failSave = true;
            try {
                rejects(h, engine::reconcile, "save-before-unload must propagate failed acknowledgement");
                h.assertTrue(engine.item() == original && !original.isRemoved(), "failed eviction retains exact live native object");
                h.assertTrue(nativeTag(original).equals(before) && Arrays.equals(old, store.load(f.id).orElseThrow().data()),
                        "failed acknowledgement preserves full live NBT and older authoritative bytes");
            } finally { store.failSave = false; f.space.load(cube); }
        }
        h.succeed();
    }

    // Break caught: unload keeps a stale live object, reload duplicates UUID, or a new engine discards native tags.
    @GameTest(template = "empty")
    public static void unloadReloadAndNewEngineRestoreSameUuidAndFullNativePayload(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var store = new ManagedItemStore(directory());
            var engine = f.open(store);
            engine.seed(START, new Vec3(.125, -.25, .375), "cold-object");
            var original = engine.item();
            mark(original);
            engine.flush();
            byte[] acknowledged = store.load(f.id).orElseThrow().data();
            var expected = nativeTag(original);
            var cube = f.space.unload(HOME).orElseThrow();
            engine.reconcile();
            h.assertTrue(engine.item() == null && original.isRemoved(), "cube eviction releases old native object");
            h.assertTrue(Arrays.equals(acknowledged, store.load(f.id).orElseThrow().data()), "unloaded record preserves exact native payload");
            f.space.load(cube);
            engine.reconcile();
            var reloaded = engine.item();
            h.assertTrue(reloaded != null && reloaded != original && nativeTag(reloaded).equals(expected),
                    "residency return creates fresh native instance with exact saved state");
            assertMarked(h, reloaded, f.id);
            engine.close();
            f.engine = null;
            h.assertTrue(reloaded.isRemoved(), "closed engine releases its last native instance");
            var cold = f.open(new ManagedItemStore(store.file(f.id).getParent()));
            cold.reconcile();
            h.assertTrue(cold.item() != null && cold.item() != reloaded && nativeTag(cold.item()).equals(expected),
                    "new engine restores the same native payload without reseeding");
            assertMarked(h, cold.item(), f.id);
            h.assertTrue(h.getLevel().getEntity(f.id) == null, "no competing vanilla manager entity is resurrected");
        }
        h.succeed();
    }

    // Break caught: move-only guard allows baseTick/gravity/age mutations before discovering an unavailable neighbor.
    @GameTest(template = "empty")
    public static void unknownHaloFreezesWholeNativeTickWithoutTurningIntoAir(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var engine = f.open(new FaultStore());
            engine.seed(START, new Vec3(.125, -.25, .375), "unknown");
            var original = engine.item();
            mark(original);
            var before = nativeTag(original);
            Vec3 position = original.position(), motion = original.getDeltaMovement();
            int ticks = original.tickCount;
            var absent = f.space.unload(HALO).orElseThrow();
            try {
                for (int i = 0; i < 4; i++) engine.step();
                h.assertTrue(engine.item() == original && !original.isRemoved(), "missing neighbor does not evict central resident item");
                h.assertTrue(original.position().equals(position) && original.getDeltaMovement().equals(motion)
                                && original.tickCount == ticks && nativeTag(original).equals(before),
                        "UNKNOWN freezes before native tick: Pos, Motion, tickCount and all NBT unchanged");
                h.assertTrue(!f.space.isLoaded(HALO) && f.space.loadedCubeCount() == 26,
                        "entity creates no demand and unavailable cube never becomes loaded air");
            } finally { f.space.load(absent); }
            engine.step();
            h.assertTrue(original.tickCount == ticks + 1 && original.getY() < position.y, "known halo permits native gravity recovery");
        }
        h.succeed();
    }

    // Break caught: observer admission tests only X/Z or a central cube, exposing an item to wrong-depth/partial mirrors.
    @GameTest(template = "empty")
    public static void observerRequiresWholeFullXyzPhysicsHalo(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var engine = f.open(new FaultStore());
            engine.seed(START, new Vec3(.125, -.25, .375), "observer");
            Set<CubeKey> near = f.space.residentKeys();
            var far = new HashSet<CubeKey>();
            for (var key : near) far.add(new CubeKey(key.x(), key.y() + 256, key.z()));
            h.assertTrue(engine.visibleIn(near), "full true-depth window admits native item");
            h.assertTrue(!engine.visibleIn(far), "same X/Z mirror 4096 blocks higher never aliases deep item");
            var partial = new HashSet<>(near); partial.remove(HALO);
            h.assertTrue(!engine.visibleIn(partial) && !engine.visibleIn(Set.of(HOME)) && !engine.visibleIn(Set.of()),
                    "observer must own complete bbox/motion neighbor halo, not just central item cube");
        }
        h.succeed();
    }

    // Break caught: failed tombstone removes live item, or successful erase later reloads a consumed UUID.
    @GameTest(template = "empty")
    public static void failedTombstoneRetainsLiveItemUntilAcknowledgedErase(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var store = new FaultStore();
            var engine = f.open(store);
            engine.seed(START, Vec3.ZERO, "tombstone");
            mark(engine.item()); engine.flush();
            var original = engine.item(); var before = nativeTag(original);
            byte[] old = store.load(f.id).orElseThrow().data();
            store.failErase = true;
            try {
                rejects(h, engine::erase, "failed tombstone acknowledgement propagates");
                h.assertTrue(engine.item() == original && !original.isRemoved() && nativeTag(original).equals(before),
                        "failed erase retains exact live native item");
                h.assertTrue(Arrays.equals(old, store.load(f.id).orElseThrow().data()), "failed erase leaves authoritative payload readable");
            } finally { store.failErase = false; }
            engine.erase();
            engine.reconcile();
            h.assertTrue(engine.item() == null && original.isRemoved() && store.load(f.id).isEmpty(),
                    "acknowledged consumed identity removes native object and cannot reconcile back");
            engine.close(); f.engine = null;
            var replacement = f.open(store); replacement.reconcile();
            h.assertTrue(replacement.item() == null, "new engine does not resurrect acknowledged tombstone");
        }
        h.succeed();
    }

    // Break caught: same-session observer windows stay stale, or native movement is sent before current visibility pruning.
    @GameTest(template = "empty")
    public static void latestObserverWindowPrunesBeforeNativeMovementFanout(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var first = capturePlayer(h, "ItemObserverA");
            var second = capturePlayer(h, "ItemObserverB");
            var admissions = new HashMap<UUID,List<DeepItemPackets.Admission>>();
            f.engine = new DeepManagedItemEngine(f.level,f.space,new FaultStore(),f.id,
                    (player,event) -> admissions.computeIfAbsent(player.getUUID(),key -> new ArrayList<>()).add(event));
            var engine = f.engine;
            engine.seed(new Vec3(-26,-19998.5,-25.5),new Vec3(.5,0,0),"observer-transition");
            UUID firstSession = UUID.randomUUID(), secondSession = UUID.randomUUID();
            Set<CubeKey> all = f.space.residentKeys();
            engine.observers(List.of(new DeepManagedItemEngine.Observer(first.player(),firstSession,all),
                    new DeepManagedItemEngine.Observer(second.player(),secondSession,all)));
            h.assertTrue(first.packets().size() == 1 && first.packets().get(0) instanceof ClientboundBundlePacket
                            && second.packets().size() == 1 && second.packets().get(0) instanceof ClientboundBundlePacket,
                    "both real ServerPlayer listeners receive actual native ServerEntity pairing bundles");

            var latest = new HashSet<>(all); latest.removeIf(key -> key.x() == -1);
            h.assertTrue(engine.visibleIn(latest), "latest first window still owns start bbox/motion halo");
            engine.observers(List.of(new DeepManagedItemEngine.Observer(first.player(),firstSession,latest),
                    new DeepManagedItemEngine.Observer(second.player(),secondSession,all)));
            h.assertTrue(first.packets().size() == 1, "same-session window replacement causes no premature remove or respawn");
            engine.step();
            h.assertTrue(engine.visibleIn(latest), "first permitted movement remains inside latest first mirror");
            int beforeCrossing = first.packets().size();
            engine.step();
            h.assertTrue(!engine.visibleIn(latest) && engine.visibleIn(all) && f.space.loadedCubeCount() == 27,
                    "second native move leaves first halo while second observer keeps union residency intact");
            h.assertTrue(first.packets().size() == beforeCrossing + 1
                            && first.packets().get(beforeCrossing) instanceof ClientboundRemoveEntitiesPacket,
                    "crossing observer is removed before any movement/motion/metadata fanout outside its latest window");
            var firstAdmission = admissions.get(first.player().getUUID());
            h.assertTrue(firstAdmission.size() == 2 && firstAdmission.get(0).active() && !firstAdmission.get(1).active()
                            && firstAdmission.get(1).session().equals(firstSession),
                    "native removal has matching session-scoped admission withdrawal");
            h.assertTrue(second.packets().stream().anyMatch(p -> p instanceof ClientboundMoveEntityPacket
                            || p instanceof ClientboundSetEntityMotionPacket),
                    "still-visible second observer receives real native tracker movement rather than global suppression");
            int removedCount = first.packets().size();
            for (int i=0;i<4;i++) engine.step();
            h.assertTrue(first.packets().size() == removedCount && engine.item() != null && !engine.item().isRemoved(),
                    "later native ticks never send to revoked observer and keep the union-resident item live");
        }
        h.succeed();
    }

    private record CapturedPlayer(ServerPlayer player,List<Packet<?>> packets) { }
    private static CapturedPlayer capturePlayer(GameTestHelper h,String name) {
        var level = h.getLevel(); var packets = new ArrayList<Packet<?>>();
        var player = new ServerPlayer(level.getServer(),level,new GameProfile(UUID.randomUUID(),name));
        new ServerGamePacketListenerImpl(level.getServer(),new Connection(PacketFlow.SERVERBOUND),player) {
            @Override public void send(Packet<?> packet) { packets.add(packet); }
        };
        return new CapturedPlayer(player,packets);
    }

    private static void mark(ItemEntity item) {
        var evidence = new CompoundTag(); evidence.putString("label", "full-native-engine");
        evidence.putIntArray("signedBounds", new int[]{-20000, -16, -1, 0, 15, 16});
        item.getItem().getOrCreateTag().put("engineEvidence", evidence);
        item.getPersistentData().putString("engineEvidence", "ForgeData-preserved");
    }
    private static void assertMarked(GameTestHelper h, ItemEntity item, UUID id) {
        h.assertTrue(item.getUUID().equals(id) && item.getItem().is(Items.DIAMOND) && item.getItem().getCount() == 17,
                "native UUID and seventeen diamonds survive");
        h.assertTrue(item.getItem().getTag().getCompound("engineEvidence").getString("label").equals("full-native-engine")
                        && Arrays.equals(item.getItem().getTag().getCompound("engineEvidence").getIntArray("signedBounds"), new int[]{-20000,-16,-1,0,15,16})
                        && item.getPersistentData().getString("engineEvidence").equals("ForgeData-preserved"),
                "complete nested stack tags and ForgeData survive without trimming");
    }
    private static CompoundTag nativeTag(ItemEntity item) { var tag = new CompoundTag(); item.save(tag); return tag; }
    private static Path directory() { return Path.of("diagnostics", "managed-item-engine", UUID.randomUUID().toString()); }
    private interface Action { void run() throws IOException; }
    private static void rejects(GameTestHelper h, Action action, String message) throws IOException {
        boolean rejected = false; try { action.run(); } catch (IOException expected) { rejected = true; }
        h.assertTrue(rejected, message);
    }

    private static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final SparseCubeSpace space = new SparseCubeSpace();
        final UUID id = UUID.randomUUID();
        final Optional<DeepCubeDomain.Attachment> previous;
        DeepManagedItemEngine engine;
        Fixture(GameTestHelper h) {
            level = h.getLevel(); previous = DeepCubeDomain.lookup(level);
            for (int x = -3; x <= -1; x++) for (int y = -1251; y <= -1249; y++) for (int z = -3; z <= -1; z++)
                space.loadEmpty(new CubeKey(x,y,z));
            for (int x = -20; x <= -13; x++) for (int z = -20; z <= -13; z++)
                space.setBlock(new BlockCoordinate(x,-20000,z), Block.getId(Blocks.STONE.defaultBlockState()));
            DeepCubeDomain.attach(level, space, false);
        }
        DeepManagedItemEngine open(ManagedItemPersistence store) throws IOException {
            if (engine != null) throw new IllegalStateException("fixture already has a live engine");
            engine = new DeepManagedItemEngine(level,space,store,id); return engine;
        }
        @Override public void close() throws IOException {
            try { if (engine != null) engine.close(); }
            finally {
                DeepCubeDomain.detach(level);
                previous.ifPresent(a -> DeepCubeDomain.attach(level,a.space(),a.blockLightEnabled()));
            }
        }
    }

    /** Fault injection keeps real native codec validation; a failed write never acknowledges replacement bytes. */
    private static final class FaultStore implements ManagedItemPersistence {
        final Map<UUID, ManagedItemSnapshot> frames = new HashMap<>();
        boolean failSave, failErase;
        @Override public Optional<ManagedItemSnapshot> load(UUID id) { return Optional.ofNullable(frames.get(id)); }
        @Override public void save(ManagedItemSnapshot snapshot) throws IOException {
            ManagedItemCodec.readAndValidate(snapshot.uuid(),snapshot.data());
            if (failSave) throw new IOException("injected entity save acknowledgement failure");
            frames.put(snapshot.uuid(), new ManagedItemSnapshot(snapshot.uuid(),snapshot.data()));
        }
        @Override public void erase(UUID id) throws IOException {
            if (failErase) throw new IOException("injected entity tombstone acknowledgement failure");
            frames.remove(id);
        }
        @Override public Path file(UUID id) { return Path.of("diagnostics", "injected-entity-store", id + ".miep"); }
    }
}
