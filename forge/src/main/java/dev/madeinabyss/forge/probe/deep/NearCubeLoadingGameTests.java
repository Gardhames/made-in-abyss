package dev.madeinabyss.forge.probe.deep;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.forge.worldgen.GeneratedPreviewProfile;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.AsyncCubeLoader;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeResidencyService;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.VersionedCubeStore;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** Exercises the actual server preparation entry points without a live client or world admission. */
@GameTestHolder("near_stage1")
@PrefixGameTestTemplate(false)
public final class NearCubeLoadingGameTests {
    // Removing the visual/physics priority distinction must put the older full-window key first.
    @GameTest(template="empty",timeoutTicks=400)
    public static void currentPhysicsPrecedesEarlierFullVisualDemand(GameTestHelper h) throws Exception {
        var store = new MemoryStore(); var gate = new CubeKey(0,-1250,0);
        var far = new CubeKey(-1,-40,-2); var near = new CubeKey(-1,-21,-2);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var starts = new CopyOnWriteArrayList<CubeKey>();
        try (var loader = new AsyncCubeLoader(store,key -> {
            starts.add(key); if (key.equals(gate)) { entered.countDown(); await(release); }
            return empty(key);
        },1,32,"mia-near-priority-test")) {
            var residency = new CubeResidencyService(store,NearCubeLoadingGameTests::empty,27,108,4);
            residency.attachLoader(loader);
            var player = new ServerPlayer(h.getLevel().getServer(),h.getLevel(),new GameProfile(UUID.randomUUID(),"NearPriority"));
            var stable = new StableCubeStore(h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("near-priority-test"),"madeinabyss:near_priority_test",1);
            Class<?> sceneType = Class.forName(DeepVisualRuntime.class.getName()+"$Scene");
            var ctor = sceneType.getDeclaredConstructor(ServerPlayer.class,UUID.class,CubeResidencyService.class,
                    StableCubeStore.class,Function.class,BlockCoordinate.class,ToIntFunction.class); ctor.setAccessible(true);
            var scene = ctor.newInstance(player,UUID.randomUUID(),residency,stable,
                    (Function<CubeKey,CubeSnapshot>)NearCubeLoadingGameTests::empty,new BlockCoordinate(-1,-330,-17),
                    (ToIntFunction<CubeKey>)key -> -1500);
            Method prepared = DeepVisualRuntime.class.getDeclaredMethod("prepared",sceneType,CubeKey.class); prepared.setAccessible(true);
            loader.request(gate,0); h.assertTrue(entered.await(5,TimeUnit.SECONDS),"controlled running load begins");
            h.assertTrue(prepared.invoke(null,scene,far)==null,"full visual demand is actually deferred");
            loader.request(near,0);
            release.countDown(); h.assertTrue(loader.awaitIdle(5000),"bounded loads finish");
            h.assertTrue(starts.equals(List.of(gate,near,far)),"current 27-cube physics demand must run before older full visual demand: "+starts);
        } finally { release.countDown(); }
        h.succeed();
    }

    // A 500m relocation must clear observed velocity instead of requesting kilometres of stale predictions.
    @GameTest(template="empty",timeoutTicks=400)
    public static void teleportResetsServerPredictionAtNegativeCoordinates(GameTestHelper h) throws Exception {
        var profile = profile(h);
        Class<?> domainType = Class.forName(DeepCubeRuntime.class.getName()+"$Domain");
        var domainCtor = domainType.getDeclaredConstructor(ServerLevel.class,GeneratedPreviewProfile.class); domainCtor.setAccessible(true);
        Object domain = domainCtor.newInstance(h.getLevel(),profile);
        var residencyField = domainType.getDeclaredField("residency"); residencyField.setAccessible(true);
        var residency = (CubeResidencyService)residencyField.get(domain);
        if (residency.loader()!=null) residency.loader().close();
        var store = new MemoryStore();
        try (var loader = new AsyncCubeLoader(store,NearCubeLoadingGameTests::empty,1,1024,"mia-near-prediction-test")) {
            residency.attachLoader(loader);
            Class<?> sessionType = Class.forName(DeepCubeRuntime.class.getName()+"$Session");
            var sessionCtor = sessionType.getDeclaredConstructor(UUID.class,ServerLevel.class,domainType); sessionCtor.setAccessible(true);
            Object session = sessionCtor.newInstance(UUID.randomUUID(),h.getLevel(),domain);
            Method prefetch = DeepCubeRuntime.class.getDeclaredMethod("prefetch",ServerPlayer.class,sessionType,domainType); prefetch.setAccessible(true);
            var player = new ServerPlayer(h.getLevel().getServer(),h.getLevel(),new GameProfile(UUID.randomUUID(),"NearTeleport"));
            player.setPos(-501,-330,-17); prefetch.invoke(null,player,session,domain);
            player.setPos(-1,-330,-17);
            var predicted = (List<?>)prefetch.invoke(null,player,session,domain);
            h.assertTrue(predicted.equals(List.of(new CubeKey(-1,-21,-2))),"500m jump must retain only its current negative-coordinate target: "+predicted);
        } finally { residency.close(); }
        h.succeed();
    }

    // Returning to the admission anchor while first send is pending must not survive a later teleport.
    @GameTest(template="empty",timeoutTicks=400)
    public static void pendingFirstVisualWindowFollowsCurrentPlayerAfterTeleport(GameTestHelper h) throws Exception {
        var store=new MemoryStore();var gate=new CubeKey(0,-1250,0);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),new GameProfile(UUID.randomUUID(),"NearFirst"));
        Class<?> sceneType=Class.forName(DeepVisualRuntime.class.getName()+"$Scene");
        var scenesField=DeepVisualRuntime.class.getDeclaredField("SCENES");scenesField.setAccessible(true);
        @SuppressWarnings("unchecked") var scenes=(Map<UUID,Object>)scenesField.get(null);
        try(var loader=new AsyncCubeLoader(store,key->{
            if(key.equals(gate)){entered.countDown();await(release);}return empty(key);
        },1,1024,"mia-near-first-send-test")){
            var residency=new CubeResidencyService(store,NearCubeLoadingGameTests::empty,27,108,4);residency.attachLoader(loader);
            var stable=new StableCubeStore(h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("near-first-send-test"),"madeinabyss:near_first_test",1);
            var ctor=sceneType.getDeclaredConstructor(ServerPlayer.class,UUID.class,CubeResidencyService.class,
                    StableCubeStore.class,Function.class,BlockCoordinate.class,ToIntFunction.class);ctor.setAccessible(true);
            var scene=ctor.newInstance(player,UUID.randomUUID(),residency,stable,
                    (Function<CubeKey,CubeSnapshot>)NearCubeLoadingGameTests::empty,new BlockCoordinate(-501,-330,-17),
                    (ToIntFunction<CubeKey>)key->-1500);
            scenes.put(player.getUUID(),scene);
            loader.request(gate,0);h.assertTrue(entered.await(5,TimeUnit.SECONDS),"controlled read prevents first complete window");
            player.setPos(-1,-330,-17);DeepVisualRuntime.update(player);
            var inFlightField=AsyncCubeLoader.class.getDeclaredField("inFlight");inFlightField.setAccessible(true);
            var keys=((Map<?,?>)inFlightField.get(loader)).keySet();
            h.assertTrue(keys.contains(new CubeKey(-1,-21,-2)),"pending first send must request current player cube instead of the old admission anchor");
            var status=DeepVisualRuntime.status(player);
            h.assertTrue(status.getAsJsonObject("desiredCube").get("x").getAsInt()==-1&&status.get("targetGeneration").getAsLong()==1,
                    "desired target is traceable before a single visual send succeeds");
            h.assertTrue(status.get("revision").getAsLong()==0&&status.get("missingIncomingCubes").getAsInt()==243,
                    "unprepared target retains previous publication and reports all missing cubes");
            release.countDown();h.assertTrue(loader.awaitIdle(5000),"test work actually exits");
        }finally{release.countDown();scenes.remove(player.getUUID());}
        h.succeed();
    }

    private static GeneratedPreviewProfile profile(GameTestHelper h) throws Exception {
        try(var in=NearCubeLoadingGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json")) {
            var manifest=JsonParser.parseString(new String(java.util.Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            return new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),
                    new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)));
        }
    }
    private static CubeSnapshot empty(CubeKey key) { return new CubeSnapshot(key,new int[4096]); }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("test release timed out"); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static final class MemoryStore implements VersionedCubeStore {
        private final Map<CubeKey,CubeSnapshot> data=new ConcurrentHashMap<>();
        @Override public Optional<CubeSnapshot> read(CubeKey key) { return Optional.ofNullable(data.get(key)); }
        @Override public void write(CubeSnapshot snapshot) { data.put(snapshot.key(),snapshot); }
        @Override public long version(CubeKey key) { return 0; }
    }
}
