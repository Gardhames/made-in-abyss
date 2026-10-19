package dev.madeinabyss.forge.probe.deep;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.mojang.logging.LogUtils;
import dev.madeinabyss.forge.cube.DeepCreativeEdits;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.forge.cube.ManagedItemStore;
import dev.madeinabyss.forge.probe.CubeProbeNetwork;
import dev.madeinabyss.forge.worldgen.GeneratedPreviewProfile;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.AsyncCubeLoader;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeResidencyService;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.ticket.SweptCubeWindow;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Versioned development fixtures: real moving windows and durable edits, no canon terrain. */
public final class DeepCubeRuntime {
    private static final BlockPos FLUID_SOURCE=new BlockPos(-1,-19999,5);
    private static final BlockPos CHEST_SOURCE=new BlockPos(-1,-19999,1);
    private static final Map<ServerLevel,Domain> DOMAINS=new HashMap<>();
    private static final Map<UUID,Session> PLAYERS=new HashMap<>();
    private static final boolean ASYNC_LOADING=Boolean.parseBoolean(System.getProperty("mia.cube.asyncLoading","true"));
    private static final boolean ASYNC_SAVING=Boolean.parseBoolean(System.getProperty("mia.cube.asyncSaving","true"));
    private static final int LOADER_THREADS=2;
    private static final dev.madeinabyss.kernel.ticket.CubeTickets.Delta NO_CHANGE=new dev.madeinabyss.kernel.ticket.CubeTickets.Delta(Set.of(),Set.of());
    /** Prefetch horizons in ticks; all predictions follow the current physical and visual windows. */
    private static final int[] PREFETCH_TICKS={0,10,20,40};
    private DeepCubeRuntime() {}
    private static final class Domain {
        final ServerLevel level;
        final StableCubeStore store;
        final CubeResidencyService residency;
        final boolean vertical;
        final GeneratedPreviewProfile generated;
        DeepFluidEngine fluid;
        DeepChestEngine chest;
        DeepManagedItemEngine item;
        boolean fluidPaused=true;
        long revision;
        long reportedSaveFailures;
        Domain(ServerLevel level,boolean vertical) {
            this.level=level;
            this.vertical=vertical;
            this.generated=null;
            store=new StableCubeStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve(vertical?"mia-vertical-runtime/overworld":"mia-deep-runtime/overworld"),
                    vertical?"madeinabyss:diagnostic_vertical_shaft":"madeinabyss:diagnostic_corridor",1);
            residency=new CubeResidencyService(store,vertical?VerticalShaftFixture::generate:DeepCubeRuntime::generate,27,108,4);
        }
        Domain(ServerLevel level,GeneratedPreviewProfile profile) {
            this.level=level;
            vertical=false;generated=profile;
            store=new StableCubeStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve(profile.relativeStoreRoot()),profile.generatorId(),1);
            residency=new CubeResidencyService(store,profile.generator()::generateCube,27,108,4);
            // Generated worlds read and generate incoming cubes off the server thread; fixtures stay synchronous.
            if(ASYNC_LOADING)residency.attachLoader(new AsyncCubeLoader(store,profile.generator()::generateCube,LOADER_THREADS,1024,"mia-cube-loader"));
            if(ASYNC_SAVING)residency.enableAsyncSaving(CubeResidencyService.DEFAULT_MAX_PINNED_CUBES,"mia-cube-writer");
        }
        void flush() throws IOException {
            IOException refused=null;
            try {if(item!=null)item.flush();}catch(IOException failure){refused=failure;}
            try {if(chest!=null)chest.flush(level);}catch(RuntimeException failure){
                var wrapped=new IOException("Native chest snapshot failed",failure);
                if(refused==null)refused=wrapped;else refused.addSuppressed(wrapped);
            }
            // Even an item/chest refusal must not leave queued cube writes unjoined.
            try {if(refused==null)residency.flush();else residency.awaitPendingSaves();}
            catch(IOException failure){if(refused==null)refused=failure;else refused.addSuppressed(failure);}
            if(refused!=null)throw refused;
        }
        void reportSaveFailures() {
            var metrics=residency.saveMetrics();
            if(metrics.failures()>reportedSaveFailures) {
                LogUtils.getLogger().error("Deep cube save failed; resident contents retained for retry: {}",metrics.lastFailure());
                reportedSaveFailures=metrics.failures();
            }
        }
        void close(){
            try {flush();if(item!=null)item.close();residency.close();}
            catch(IOException failure){
                LogUtils.getLogger().error("Deep cube domain close FAILED; domain and resident edits must be retained",failure);
                throw new IllegalStateException("Deep cube close failed; data remains resident",failure);
            }
        }
    }
    private static final class Session {
        final UUID token;
        final ServerLevel level;
        final Domain domain;
        Set<CubeKey> sent=Set.of();
        long geometryRevision;
        long globalDeltaRevision,globalDeltaPendingBytes;
        final java.util.LinkedHashMap<CubeKey,GeneratedSceneNetwork.Frame> globalDeltaPending=new java.util.LinkedHashMap<>();
        long lastSequence;
        DeepRuntimeNetwork.Ack lastAck;
        long miningLastSequence,miningOperation,miningReplySequence;
        BlockPos miningTarget;
        int miningExpected;
        net.minecraft.world.item.ItemStack miningTool;
        DeepRuntimeNetwork.MiningAck miningLastAck;
        String error="";
        long positionPackets,rejectedMoves,movementSamples;
        double lastY=Double.NaN,minY=Double.POSITIVE_INFINITY,maxVerticalStep;
        final long startedNanos=System.nanoTime();
        final JsonArray positionEvents=new JsonArray();
        JsonObject lastRejected;
        String fluidAction="",fluidError="";
        boolean fluidAccepted;
        String chestAction="",chestError="";
        boolean chestAccepted;
        String itemAction="",itemError="";
        boolean itemAccepted;
        // Observed server-side position per tick; client-driven players have no reliable server velocity.
        double lastX=Double.NaN,lastPrefetchY=Double.NaN,lastZ=Double.NaN,velocityX,velocityY,velocityZ;
        int lastPrefetchTick=Integer.MIN_VALUE;
        long predictionResets,loadTargetGeneration,loadDesiredNanos;
        CubeKey loadTarget;
        // Diagnostic owner-thread cost of prepare(); read by deep_runtime_query.
        long prepareTicks,prepareOver5ms,prepareOver50ms;double prepareMaxMillis,prepareTotalMillis;
        final long[] phaseOver5ms=new long[PHASES.length];final double[] phaseMaxMillis=new double[PHASES.length],phaseTotalMillis=new double[PHASES.length];
        Session(UUID token,ServerLevel level,Domain domain) {this.token=token;this.level=level;this.domain=domain;}
    }
    public static void register() {
        DeepRuntimeNetwork.register();
        WorldSessionNetwork.register();
        DeepVisualRuntime.register();
        MinecraftForge.EVENT_BUS.addListener((TickEvent.LevelTickEvent event)->{
            if(event.phase!=TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
            var domain=DOMAINS.get(level);
            if(domain==null) return;
            try {
                // Native inventories can mutate after prepare(); capture them before deciding
                // whether a completed background snapshot is still current enough to unload.
                if(domain.chest!=null)domain.chest.flush(level);
                domain.residency.processPendingSaves();domain.reportSaveFailures();
            }
            catch(IOException|RuntimeException failure){LogUtils.getLogger().error("Deep cube save acknowledgement failed; data remains resident",failure);return;}
            if(domain.residency.metrics().owners()==0) return;
            if(domain.chest==null && domain.item==null && (domain.fluid==null || domain.fluidPaused))return;
            long now=Math.incrementExact(domain.residency.space().scheduledTicks().clock());
            if(domain.chest!=null){domain.chest.step(level,now);publishChest(level,domain);}
            if(domain.fluid!=null && !domain.fluidPaused){domain.fluid.step(level,now);publishFluid(level,domain);}
            if(domain.item!=null)try{domain.item.step();publishItems(level,domain);}catch(IOException e){LogUtils.getLogger().error("Managed item tick refused; previous identity retained",e);}
        });
        // Forge 47.4.20 posts this from ServerLevel.save, covering save-all and autosave.
        MinecraftForge.EVENT_BUS.addListener((LevelEvent.Save event)->{
            if(!(event.getLevel() instanceof ServerLevel level))return;
            var domain=DOMAINS.get(level);if(domain==null)return;
            try {domain.flush();}
            catch(IOException failure){
                LogUtils.getLogger().error("Deep cube world save FAILED; resident data retained",failure);
                throw new IllegalStateException("Deep cube world save failed",failure);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event)->{
            IllegalStateException refused=null;
            for(var entry:DOMAINS.entrySet()) if(entry.getKey().getServer()==event.getServer()) {
                try {entry.getValue().flush();}
                catch(IOException e) {
                    LogUtils.getLogger().error("Deep cube shutdown flush FAILED; resident data retained",e);
                    if(refused==null)refused=new IllegalStateException("Deep cube flush failed; data remains resident");
                    refused.addSuppressed(e);
                }
            }
            if(refused!=null)throw refused;
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event)->{
            DeepLightDependencies.clear(event.getServer());
            // Close before removal/detachment. A failed domain stays strongly reachable for diagnosis/retry.
            var iterator=DOMAINS.entrySet().iterator();
            while(iterator.hasNext()){
                var entry=iterator.next();var level=entry.getKey();if(level.getServer()!=event.getServer())continue;
                try {entry.getValue().close();}
                catch(IllegalStateException failure){continue;} // close logged the full failure
                DeepChestEngine.detach(level);DeepFluidEngine.detach(level);DeepSurvivalPlacement.detach(level);
                iterator.remove();
            }
            PLAYERS.entrySet().removeIf(entry->entry.getValue().level.getServer()==event.getServer()&&!DOMAINS.containsKey(entry.getValue().level));
        });
    }
    public static void start(ServerPlayer player,UUID token) {
        start(player,token,false);
    }
    public static void start(ServerPlayer player,UUID token,boolean vertical) {
        start(player,token,vertical,DeepProbeStart.vertical("descent",null,false));
    }
    public static void start(ServerPlayer player,UUID token,boolean vertical,DeepProbeStart start) {
        java.util.Objects.requireNonNull(start);
        if(player.level().dimension()!=Level.OVERWORLD || player.getY()<player.level().getMinBuildHeight() || PLAYERS.containsKey(player.getUUID())) return;
        var level=player.serverLevel();
        var domain=DOMAINS.get(level);
        if(domain==null || domain.generated!=null || domain.vertical!=vertical) {
            // Fixture namespaces cannot silently replace a still-attached owner's world.
            if(domain!=null && domain.residency.metrics().owners()!=0) return;
            if(domain!=null){domain.close();DOMAINS.remove(level);}
            domain=new Domain(level,vertical);DOMAINS.put(level,domain);
        }
        try {
            attachItems(level,domain);
            if(vertical) VerticalShaftFixture.prepareNativeEntrance(level);
            var initial=vertical?new BlockCoordinate(VerticalShaftFixture.CENTER,start.startY(),VerticalShaftFixture.CENTER):new BlockCoordinate(5,-19999,5);
            var residencyDelta=domain.residency.move(player.getUUID().toString(),initial,1,1);
            CubeProbeNetwork.capturePlayerHome(player);
            var session=new Session(token,level,domain); PLAYERS.put(player.getUUID(),session);
            DeepCubeDomain.attach(level,domain.residency.space(),true);
            if(DeepSurvivalPlacement.lookup(level).isEmpty()) DeepSurvivalPlacement.attach(level,domain.residency.space(),domain.store);
            if(domain.item!=null)DeepSurvivalPlacement.lookup(level).orElseThrow().bindDrops(domain.item);
            if(hasChestPayloads(domain))attachChest(level,domain);
            if(domain.fluid!=null) domain.fluid=DeepFluidEngine.attach(level,domain.residency.space());
            if(domain.item!=null)domain.item.reconcile();
            sendWindow(player,domain,session);
            DeepLightDependencies.residencyChanged(level,changedKeys(residencyDelta),player.getUUID());
            if(vertical) DeepVisualRuntime.start(player,token,domain.residency,domain.store,VerticalShaftFixture::generate,initial);
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying=vertical && start.flying();
            if(vertical) player.getAbilities().setFlyingSpeed(start.flightSpeed());
            player.onUpdateAbilities();
            player.setDeltaMovement(Vec3.ZERO);
            if(vertical) player.connection.teleport(VerticalShaftFixture.CENTER+.5,start.startY(),VerticalShaftFixture.CENTER+.5,0,90);
            else player.connection.teleport(5.5,-19996,5.5,0,24);
            LogUtils.getLogger().info("MIA_DEEP_RUNTIME_START pid={} window=27 vertical={} path={}",ProcessHandle.current().pid(),vertical,domain.store.path(CubeKey.fromBlock(initial)));
        } catch(IOException e) {
            if(vertical && domain.residency.metrics().owners()==0) VerticalShaftFixture.releaseNativeEntrance(level);
            throw new IllegalStateException("Deep runtime start refused",e);
        }
    }
    /** Development creative preview; the server manifest alone owns coordinates and metrics. */
    public static void startGenerated(ServerPlayer player,UUID token,String profileId) {
        var reply=new JsonObject();reply.addProperty("generatedAccepted",false);
        Domain domain=null;boolean homeCaptured=false;boolean published=false;
        try {
            if(player.level().dimension()!=Level.OVERWORLD || player.getY()<player.level().getMinBuildHeight() || PLAYERS.containsKey(player.getUUID()))throw new IllegalArgumentException("Generated setup requires inactive ordinary Overworld player");
            var level=player.serverLevel();var previous=DOMAINS.get(level);
            if(previous!=null&&previous.residency.metrics().owners()!=0)throw new IllegalArgumentException("Another owned profile is active");
            // Complete the old writer before a replacement can open the same store namespace.
            if(previous!=null){previous.close();DOMAINS.remove(level);}
            var biomes=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            var profile=GeneratedPreviewProfile.load(profileId,new net.minecraft.world.level.biome.FixedBiomeSource(biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)));
            domain=new Domain(level,profile);
            if(!level.getServer().isSameThread())throw new IllegalStateException("Generated inventory requires server owner thread");
            if(!GeneratedSceneNetwork.remotePresent(player.connection.connection))throw new IllegalStateException("Generated scene channel absent");
            var savedScene=GeneratedSceneInventory.capture(biomes,domain.store,domain.generated.generator()::generateCube,GeneratedSceneInventory.optionalProceduralIdentity(domain.generated));
            var sceneMessages=GeneratedSceneNetwork.prepare(token,profile.fingerprint(),savedScene);
            var initial=profile.feet();profile.requireVisualDomain(initial);domain.residency.move(player.getUUID().toString(),initial,1,1);
            homeCaptured=true;CubeProbeNetwork.capturePlayerHome(player);
            var session=new Session(token,level,domain);DOMAINS.put(level,domain);PLAYERS.put(player.getUUID(),session);published=true;
            DeepCubeDomain.attachGenerated(level,domain.residency.space(),true);
            sendWindow(player,domain,session);
            DeepLightDependencies.residencyChanged(level,domain.residency.ownerWindow(player.getUUID().toString()),player.getUUID());
            DeepVisualRuntime.start(player,token,domain.residency,domain.store,profile.generator()::generateCube,initial,profile::visualMinimumCubeY);
            var visual=DeepVisualRuntime.status(player);
            if(!visual.get("error").getAsString().isEmpty()||visual.get("cubeCount").getAsInt()!=DeepVisualNetwork.windowKeys(CubeKey.fromBlock(initial),profile.visualMinimumCubeY(CubeKey.fromBlock(initial))).size())throw new IllegalStateException("Generated visual startup refused: "+visual);
            GeneratedSceneNetwork.send(player,sceneMessages);
            bootstrapGeneratedDeltas(player,session);
            player.setGameMode(GameType.CREATIVE);player.getAbilities().flying=false;player.onUpdateAbilities();player.setDeltaMovement(Vec3.ZERO);
            player.connection.teleport(initial.x()+.5,initial.y(),initial.z()+.5,0,12);
            reply=profile.status();reply.addProperty("generatedAccepted",true);
            reply.addProperty("savedSceneCubeCount",savedScene.cubes().size());reply.addProperty("savedSceneEncodedBytes",savedScene.encodedBytes());
        } catch(IOException|RuntimeException failure) {
            try {
                if(published)release(player);
                else if(domain!=null){domain.residency.release(player.getUUID().toString());domain.close();}
                if(homeCaptured)CubeProbeNetwork.rollbackCapturedHome(player);
                reply.addProperty("generatedRollbackComplete",true);
            }catch(IOException|RuntimeException rollback){
                if(domain!=null&&!published)DOMAINS.putIfAbsent(player.serverLevel(),domain);
                failure.addSuppressed(rollback);reply.addProperty("generatedRollbackComplete",false);reply.addProperty("rollbackError",rollback.toString());
            }
            reply.addProperty("generatedError",failure.toString());
            LogUtils.getLogger().error("Generated preview refused; saved data retained",failure);
        }
        DeepRuntimeNetwork.state(player,token,reply.toString());
    }
    /** Called before both ServerPlayer.tick and doTick. No data is released during the player body. */
    public static boolean nativeWorld(Level level) {
        return level instanceof ServerLevel server && server.getChunkSource().getGenerator() instanceof dev.madeinabyss.forge.worldgen.ComposedTerrainChunkGenerator;
    }
    /** Native generator worlds admit the saved position; no fixture home or camera teleport. */
    public static boolean startWorld(ServerPlayer player,UUID token,GeneratedPreviewProfile profile) {
        if(!nativeWorld(player.level())||PLAYERS.containsKey(player.getUUID()))return false;
        var level=player.serverLevel();Domain domain=DOMAINS.get(level);boolean published=false;
        try {
            long admissionStarted=System.nanoTime();
            if(!level.getServer().isSameThread())throw new IllegalStateException("World admission requires server owner thread");
            if(domain==null){domain=new Domain(level,profile);DOMAINS.put(level,domain);}
            if(domain.generated==null||!domain.generated.fingerprint().equals(profile.fingerprint()))throw new IllegalStateException("World profile differs from existing cube authority");
            var initial=coordinate(player.blockPosition());profile.requireVisualDomain(initial);
            var biomes=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            long identityStarted=System.nanoTime();
            var expectedInventoryIdentity=GeneratedSceneInventory.optionalProceduralIdentity(domain.generated);
            long identityFinished=System.nanoTime();
            var savedScene=GeneratedSceneInventory.capture(biomes,domain.store,domain.generated.generator()::generateCube,expectedInventoryIdentity);
            long captureFinished=System.nanoTime();
            var sceneMessages=GeneratedSceneNetwork.prepare(token,profile.fingerprint(),savedScene);
            long physicalStarted=System.nanoTime();
            domain.residency.move(player.getUUID().toString(),initial,1,1);
            long residencyFinished=System.nanoTime();
            var session=new Session(token,level,domain);PLAYERS.put(player.getUUID(),session);published=true;
            DeepCubeDomain.attachGenerated(level,domain.residency.space(),true);
            sendWindow(player,domain,session);
            DeepLightDependencies.residencyChanged(level,domain.residency.ownerWindow(player.getUUID().toString()),player.getUUID());
            long visualStarted=System.nanoTime();
            DeepVisualRuntime.start(player,token,domain.residency,domain.store,profile.generator()::generateCube,initial,profile::visualMinimumCubeY);
            var visual=DeepVisualRuntime.status(player);
            if(!visual.get("error").getAsString().isEmpty()||visual.get("cubeCount").getAsInt()!=DeepVisualNetwork.windowKeys(CubeKey.fromBlock(initial),profile.visualMinimumCubeY(CubeKey.fromBlock(initial))).size())throw new IllegalStateException("World visual startup refused: "+visual);
            long visualFinished=System.nanoTime();
            GeneratedSceneNetwork.send(player,sceneMessages);
            bootstrapGeneratedDeltas(player,session);
            long inventoryEnqueued=System.nanoTime();
            var reply=profile.status();reply.addProperty("generatedAccepted",true);reply.addProperty("persistentWorld",true);
            reply.addProperty("savedSceneCubeCount",savedScene.cubes().size());reply.addProperty("savedSceneEncodedBytes",savedScene.encodedBytes());
            // Diagnostic spans only: enqueue is not a delivery acknowledgement.
            // Count validated on-disk cubes separately from the transmitted edits.
            var bootstrap=new com.google.gson.JsonObject();
            bootstrap.addProperty("session",token.toString());bootstrap.addProperty("profileFingerprint",profile.fingerprint());
            bootstrap.addProperty("expectedInventoryIdentity",expectedInventoryIdentity);
            bootstrap.addProperty("validatedSavedCubes",savedScene.cacheStats().validatedCubes());
            bootstrap.addProperty("expectedDigestHits",savedScene.cacheStats().expectedHits());
            bootstrap.addProperty("expectedGeneratedCubes",savedScene.cacheStats().expectedGenerated());
            bootstrap.addProperty("invalidDigestShards",savedScene.cacheStats().invalidShards());
            bootstrap.addProperty("digestWriteFailures",savedScene.cacheStats().writeFailures());
            bootstrap.addProperty("saturatedDigestMisses",savedScene.cacheStats().saturatedMisses());
            var spans=new com.google.gson.JsonObject();
            spans.addProperty("domainAndPosition",(identityStarted-admissionStarted)/1_000_000.0);
            spans.addProperty("expectedIdentity",(identityFinished-identityStarted)/1_000_000.0);
            spans.addProperty("savedInventoryCapture",(captureFinished-identityFinished)/1_000_000.0);
            spans.addProperty("inventoryPreparation",(physicalStarted-captureFinished)/1_000_000.0);
            spans.addProperty("physicalResidency",(residencyFinished-physicalStarted)/1_000_000.0);
            spans.addProperty("physicalPublishAndDependencies",(visualStarted-residencyFinished)/1_000_000.0);
            spans.addProperty("visualWindow",(visualFinished-visualStarted)/1_000_000.0);
            spans.addProperty("inventoryEnqueue",(inventoryEnqueued-visualFinished)/1_000_000.0);
            spans.addProperty("beforeSuccessReply",(inventoryEnqueued-admissionStarted)/1_000_000.0);
            bootstrap.add("stagesMillis",spans);reply.add("nativeBootstrap",bootstrap);
            DeepRuntimeNetwork.state(player,token,reply.toString());
            return true;
        }catch(IOException|RuntimeException failure){
            try {if(published)release(player);else if(domain!=null)domain.residency.release(player.getUUID().toString());}
            catch(IOException|RuntimeException rollback){failure.addSuppressed(rollback);}
            LogUtils.getLogger().error("Native world admission refused; saved coordinates and cubes retained",failure);
            player.connection.disconnect(net.minecraft.network.chat.Component.literal("Abyss cube admission failed; saved world retained."));
            return false;
        }
    }
    /** Saved bootstrap can precede another owner's latest dirty resident geometry. */
    private static void bootstrapGeneratedDeltas(ServerPlayer player,Session session){
        if(session.domain.generated==null)return;
        var biomes=session.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        for(var key:session.domain.residency.space().residentKeys()){
            var snapshot=session.domain.residency.space().snapshot(key).orElseThrow(()->new IllegalStateException("Missing resident generated bootstrap cube"));
            queueGeneratedDelta(session,GeneratedSceneNetwork.frame(biomes,snapshot));
        }
        flushGeneratedDeltas(player,session);
    }
    /** Global exact rays may depend on a changed cube outside every local observer window. */
    private static void generatedCubeChanged(ServerLevel level,Domain domain,CubeKey key){
        if(domain.generated==null)return;
        var snapshot=domain.residency.space().snapshot(key).orElseThrow(()->new IllegalStateException("Global edit delta requires committed resident geometry"));
        var frame=GeneratedSceneNetwork.frame(level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME),snapshot);
        for(var observer:level.getServer().getPlayerList().getPlayers()){
            var other=PLAYERS.get(observer.getUUID());
            if(other==null||other.domain!=domain||observer.level()!=level)continue;
            queueGeneratedDelta(other,frame);
            try{flushGeneratedDeltas(observer,other);}
            catch(RuntimeException unavailable){other.error="Generated global delta queued: "+unavailable;LogUtils.getLogger().error("Generated global edit delivery deferred; authoritative frame retained",unavailable);}
        }
    }
    private static void queueGeneratedDelta(Session session,GeneratedSceneNetwork.Frame frame){
        var previous=session.globalDeltaPending.get(frame.key());
        long bytes=session.globalDeltaPendingBytes+(long)frame.bytes().length-(previous==null?0:previous.bytes().length);
        if(bytes>GeneratedSceneNetwork.MAX_ENCODED_BYTES||previous==null&&session.globalDeltaPending.size()>=GeneratedSceneNetwork.MAX_CUBES)
            throw new IllegalStateException("Generated global delta queue exceeds bounded authority budget");
        session.globalDeltaPending.put(frame.key(),frame);session.globalDeltaPendingBytes=bytes;
    }
    private static void flushGeneratedDeltas(ServerPlayer player,Session session){
        if(session.domain.generated==null)return;
        var pending=session.globalDeltaPending.entrySet().iterator();
        while(pending.hasNext()){
            var frame=pending.next().getValue();long next=Math.incrementExact(session.globalDeltaRevision);
            GeneratedSceneNetwork.sendDelta(player,new GeneratedSceneNetwork.Delta(session.token,next,frame));
            session.globalDeltaRevision=next;session.globalDeltaPendingBytes-=frame.bytes().length;pending.remove();
        }
    }
    private static final String[] PHASES={"validate","prefetch","residency","visualPrefetch","reconcile","sendWindow","publishItems","light","visualUpdate","allowsMove"};
    /** Diagnostic: charges the time since {@code started} to one prepare() phase and returns now. */
    private static long phase(Session session,int index,long started){long now=System.nanoTime();double millis=(now-started)/1e6;
        session.phaseTotalMillis[index]+=millis;session.phaseMaxMillis[index]=Math.max(session.phaseMaxMillis[index],millis);if(millis>5)session.phaseOver5ms[index]++;return now;}
    public static boolean prepare(ServerPlayer player) {
        var session=PLAYERS.get(player.getUUID()); if(session==null) {if(nativeWorld(player.level())){player.setDeltaMovement(Vec3.ZERO);return false;}return true;}
        var domain=session.domain;
        if(player.level()!=session.level || !fitsWindow(player)) {cancelMining(player,"unavailable_player_window");player.setDeltaMovement(Vec3.ZERO);return false;}
        long prepareStarted=System.nanoTime();
        try {
            if(domain.generated!=null)domain.generated.requireVisualDomain(coordinate(player.blockPosition()));
            flushGeneratedDeltas(player,session);
            if(session.miningTarget!=null) {
                var center=CubeKey.fromBlock(coordinate(player.blockPosition()));var target=CubeKey.fromBlock(coordinate(session.miningTarget));
                if(Math.abs((long)center.x()-target.x())>1||Math.abs((long)center.y()-target.y())>1||Math.abs((long)center.z()-target.z())>1)cancelMining(player,"target_window_released");
            }
            if(domain.chest!=null)domain.chest.flush(session.level);
            if(domain.item!=null)domain.item.flush();
            long mark=phase(session,0,prepareStarted);
            var predicted=prefetch(player,session,domain);
            mark=phase(session,1,mark);
            // A cube that is not prepared yet keeps the previous window; allowsMove then stops
            // the body at loaded data instead of generating in this tick or treating it as air.
            var residencyDelta=domain.residency.tryMove(player.getUUID().toString(),coordinate(player.blockPosition()),1,1)
                    .orElse(NO_CHANGE);
            mark=phase(session,2,mark);
            DeepVisualRuntime.prefetch(player,predicted);
            mark=phase(session,3,mark);
            if(domain.chest!=null)domain.chest.reconcile(session.level);
            else if(hasChestPayloads(domain))attachChest(session.level,domain);
            if(domain.item!=null)domain.item.reconcile();
            mark=phase(session,4,mark);
            if(!session.sent.equals(domain.residency.ownerWindow(player.getUUID().toString()))) sendWindow(player,domain,session);
            mark=phase(session,5,mark);
            publishItems(session.level,domain);
            mark=phase(session,6,mark);
            DeepLightDependencies.residencyChanged(session.level,changedKeys(residencyDelta),player.getUUID());
            mark=phase(session,7,mark);
            DeepVisualRuntime.update(player);
            mark=phase(session,8,mark);
            session.error="";
            if(Double.isFinite(session.lastY)) session.maxVerticalStep=Math.max(session.maxVerticalStep,Math.abs(player.getY()-session.lastY));
            session.lastY=player.getY();session.minY=Math.min(session.minY,player.getY());session.movementSamples++;
            boolean safe=DeepCubeDomain.allowsMove(player.level(),player.getBoundingBox().expandTowards(player.getDeltaMovement()));
            if(!safe) player.setDeltaMovement(Vec3.ZERO);
            phase(session,9,mark);
            return safe;
        } catch(IOException | IllegalArgumentException e) {
            String error=e.toString();
            if(!error.equals(session.error)) LogUtils.getLogger().error("Deep residency refused; retaining previous window: {}",error);
            session.error=error; player.setDeltaMovement(Vec3.ZERO); return false;
        } finally {
            double millis=(System.nanoTime()-prepareStarted)/1e6;
            session.prepareTicks++;session.prepareTotalMillis+=millis;session.prepareMaxMillis=Math.max(session.prepareMaxMillis,millis);
            if(millis>5)session.prepareOver5ms++;if(millis>50)session.prepareOver50ms++;
        }
    }
    public static void release(ServerPlayer player) {
        var session=PLAYERS.get(player.getUUID());if(session==null) return;
        cancelMining(player,"released");
        var domain=session.domain;
        dev.madeinabyss.kernel.ticket.CubeTickets.Delta residencyDelta;
        try {if(domain.item!=null)domain.item.flush();if(domain.chest!=null)domain.chest.flush(session.level);residencyDelta=domain.residency.release(player.getUUID().toString());if(domain.chest!=null)domain.chest.reconcile(session.level);if(domain.item!=null)domain.item.reconcile();}
        catch(IOException e) {LogUtils.getLogger().error("Deep save-before-release FAILED; previous window retained",e);throw new IllegalStateException("Deep save-before-release failed; previous window retained",e);}
        // Only release speculative demand after the durable residency release succeeds.
        if(domain.residency.loader()!=null)domain.residency.loader().releaseInterest(player.getUUID().toString());
        DeepVisualRuntime.release(player);
        DeepLightDependencies.release(player);
        PLAYERS.remove(player.getUUID());
        publishItems(session.level,domain);
        DeepLightDependencies.residencyChanged(session.level,changedKeys(residencyDelta));
        if(domain.residency.metrics().owners()==0) {
            domain.fluidPaused=true;
            DeepFluidEngine.detach(session.level);
            DeepChestEngine.detach(session.level);domain.chest=null;
            DeepSurvivalPlacement.detach(session.level);
            if(domain.item!=null)try{domain.item.close();}catch(IOException e){throw new IllegalStateException("managed item close acknowledgement failed",e);}
            DeepCubeDomain.detach(session.level);
            if(domain.vertical) VerticalShaftFixture.releaseNativeEntrance(session.level);
        }
    }
    public static boolean owns(Level level) {return level instanceof ServerLevel server && DOMAINS.containsKey(server) && DOMAINS.get(server).residency.metrics().owners()>0;}
    public static boolean active(ServerPlayer player) {return PLAYERS.containsKey(player.getUUID());}
    public static void positionPacket(ServerPlayer player,double x,double y,double z,boolean awaiting) {
        var session=PLAYERS.get(player.getUUID());if(session==null) return;
        session.positionPackets++;
        if(session.positionEvents.size()<10) {
            var event=new JsonObject();event.addProperty("elapsedMs",(System.nanoTime()-session.startedNanos)/1000000.0);
            event.addProperty("playerTick",player.tickCount);event.addProperty("awaiting",awaiting);
            event.addProperty("targetX",x);event.addProperty("targetY",y);event.addProperty("targetZ",z);
            String caller="other";
            for(var frame:Thread.currentThread().getStackTrace()) {
                if(frame.getMethodName().equals("handleMovePlayer") || frame.getMethodName().equals("start")
                        || (frame.getClassName().equals("net.minecraft.server.network.ServerGamePacketListenerImpl") && frame.getMethodName().equals("tick"))) {
                    caller=frame.getClassName()+"."+frame.getMethodName();break;
                }
            }
            event.addProperty("caller",caller);session.positionEvents.add(event);
        }
    }
    public static void rejectedMove(ServerPlayer player,double x,double y,double z) {
        var session=PLAYERS.get(player.getUUID());if(session==null) return;
        session.rejectedMoves++;
        var event=new JsonObject();event.addProperty("elapsedMs",(System.nanoTime()-session.startedNanos)/1000000.0);
        event.addProperty("playerTick",player.tickCount);
        event.addProperty("fromX",player.getX());event.addProperty("fromY",player.getY());event.addProperty("fromZ",player.getZ());
        event.addProperty("candidateX",x);event.addProperty("candidateY",y);event.addProperty("candidateZ",z);
        var halo=player.getBoundingBox().expandTowards(x-player.getX(),y-player.getY(),z-player.getZ()).inflate(8);
        event.addProperty("nativeHaloRequired",halo.maxY>=player.level().getMinBuildHeight() && halo.minY<player.level().getMaxBuildHeight());
        event.addProperty("nativeHaloLoaded",DeepCubeDomain.nativeColumnsLoaded(player.level(),
                (int)Math.floor(halo.minX),(int)Math.floor(halo.minZ),(int)Math.floor(halo.maxX),(int)Math.floor(halo.maxZ)));
        session.lastRejected=event;
    }
    private static void sendWindow(ServerPlayer player,Domain domain,Session session) {
        var keys=domain.residency.ownerWindow(player.getUUID().toString());
        var snapshots=keys.stream().sorted(java.util.Comparator.comparingInt(CubeKey::x).thenComparingInt(CubeKey::y).thenComparingInt(CubeKey::z))
                .map(k->domain.residency.space().snapshot(k).orElseThrow()).toList();
        long geometryRevision=Math.incrementExact(domain.revision);
        DeepRuntimeNetwork.window(player,session.token,geometryRevision,snapshots);
        domain.revision=geometryRevision;session.sent=keys;session.geometryRevision=geometryRevision;
        DeepLightDependencies.geometry(player,session.token,geometryRevision,keys);
        publishItems(player.serverLevel(),domain);
    }

    private static Set<CubeKey> changedKeys(dev.madeinabyss.kernel.ticket.CubeTickets.Delta delta) {
        var changed=new java.util.HashSet<CubeKey>(delta.added());changed.addAll(delta.removed());return Set.copyOf(changed);
    }
    public static void edit(ServerPlayer player,DeepRuntimeNetwork.Edit message) {
        var session=PLAYERS.get(player.getUUID());
        if(session==null || !session.token.equals(message.session())) {DeepRuntimeNetwork.ack(player,new DeepRuntimeNetwork.Ack(message.session(),message.sequence(),false,"inactive_session"));return;}
        if(message.sequence()==session.lastSequence && session.lastAck!=null) {DeepRuntimeNetwork.ack(player,session.lastAck);return;}
        String reason=null;
        if(message.sequence()<=session.lastSequence) reason="stale_sequence";
        else if(!player.level().dimension().location().toString().equals(message.dimension())) reason="wrong_dimension";
        else if(!player.isAlive() || !prepare(player)) reason="unavailable_player_window";
        else if(!player.level().getWorldBorder().isWithinBounds(message.request().clicked())) reason="outside_world_border";
        var domain=DOMAINS.get(player.serverLevel());
        DeepCreativeEdits.Result result=null;
        if(reason==null) {
            var stack=player.getItemInHand(message.hand());
            var held=stack.getItem() instanceof BlockItem item?item.getBlock().defaultBlockState():null;
            BlockPos destination=null;
            try {var p=message.request().clicked();var f=message.request().face();destination=new BlockPos(Math.addExact(p.getX(),f.getStepX()),Math.addExact(p.getY(),f.getStepY()),Math.addExact(p.getZ(),f.getStepZ()));}
            catch(ArithmeticException overflow) {reason="coordinate_overflow";}
            if(reason==null && message.request().place() && !player.level().getWorldBorder().isWithinBounds(destination)) reason="outside_world_border";
            if(reason==null && message.request().place() && !player.level().getEntities(player,new AABB(destination),e->!e.isSpectator()).isEmpty()) reason="entity_collision";
            if(reason==null) {
                if(!player.gameMode.isCreative() && message.request().place() && player.gameMode.getGameModeForPlayer()==GameType.SURVIVAL) {
                    var plan=DeepCreativeEdits.validate(domain.residency.space(),message.request(),player.getEyePosition(),player.getBoundingBox(),held,player.getBlockReach());
                    if(plan.accepted()) {
                        var nativeResult=player.gameMode.useItemOn(player,player.level(),stack,message.hand(),
                                new net.minecraft.world.phys.BlockHitResult(message.request().hit(),message.request().face(),message.request().clicked(),false));
                        result=new DeepCreativeEdits.Result(nativeResult.consumesAction(),nativeResult.consumesAction()?"accepted":
                                DeepSurvivalPlacement.lookup(player.level()).map(DeepSurvivalPlacement::lastError).orElse("placement_unavailable"),
                                plan.changed(),plan.state());
                    } else result=plan;
                } else result=DeepCreativeEdits.apply(domain.residency.space(),message.request(),player.getEyePosition(),player.getBoundingBox(),
                            player.gameMode.isCreative(),held,player.getBlockReach());
                reason=result.reason();
            }
        }
        boolean accepted=result!=null && result.accepted();
        if(accepted) {
            // All connected owners observing the changed cube receive authoritative data before acknowledgement.
            var changed=CubeKey.fromBlock(coordinate(result.changed()));
            for(ServerPlayer observer:player.getServer().getPlayerList().getPlayers()) {
                var other=PLAYERS.get(observer.getUUID());
                if(other!=null && observer.level()==player.level() && domain.residency.ownerWindow(observer.getUUID().toString()).contains(changed)) sendWindow(observer,domain,other);
            }
            DeepLightDependencies.changed(player.serverLevel(),changed);
            DeepVisualRuntime.changed(player.level(),changed);
            generatedCubeChanged(player.serverLevel(),domain,changed);
        }
        var ack=new DeepRuntimeNetwork.Ack(session.token,message.sequence(),accepted,reason);
        if(message.sequence()>session.lastSequence) {session.lastSequence=message.sequence();session.lastAck=ack;}
        DeepRuntimeNetwork.ack(player,ack);
    }
    /** Clears only owned native server mining fields, without reading an unavailable cube. */
    public interface ServerMiningAccess {void mia$cancelMining();boolean mia$miningActive();}
    public static boolean miningContextReady(ServerPlayer player,BlockPos pos) {
        var session=PLAYERS.get(player.getUUID());
        if(session==null)return true; // Existing isolated native GameTests own their separate fixture.
        return session.miningTarget!=null && session.miningTarget.equals(pos) && session.level==player.level()
                && net.minecraft.world.item.ItemStack.matches(session.miningTool,player.getMainHandItem());
    }
    public static void mining(ServerPlayer player,DeepRuntimeNetwork.Mining message) {
        var session=PLAYERS.get(player.getUUID());
        if(session==null||!session.token.equals(message.session())) {DeepRuntimeNetwork.miningAck(player,new DeepRuntimeNetwork.MiningAck(message.session(),message.sequence(),message.operation(),false,true,"inactive_session"));return;}
        if(message.sequence()==session.miningLastSequence && session.miningLastAck!=null){DeepRuntimeNetwork.miningAck(player,session.miningLastAck);return;}
        String reason=null;
        if(message.sequence()<=session.miningLastSequence)reason="stale_sequence";
        else if(!player.level().dimension().location().toString().equals(message.dimension()))reason="wrong_dimension";
        else if(!DeepRuntimeNetwork.miningAction(message.action()))reason="unsupported_action";
        else if(message.action()!=net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && (session.miningOperation!=message.operation()||session.miningTarget==null||!session.miningTarget.equals(message.position())))reason="unowned_operation";
        else if(message.action()==net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && message.operation()!=message.sequence())reason="invalid_operation";
        boolean abort=message.action()==net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK;
        if(reason==null&&!abort) {
            if(!player.isAlive()||!prepare(player)||!DeepCubeDomain.isDeep(player.level(),message.position()))reason="unavailable_player_window";
            else if(!player.level().getWorldBorder().isWithinBounds(message.position()))reason="outside_world_border";
            else if(!session.domain.residency.ownerWindow(player.getUUID().toString()).contains(CubeKey.fromBlock(coordinate(message.position()))))reason="unowned_target_cube";
            else {
                var state=session.domain.residency.space().block(coordinate(message.position()));
                if(state.isEmpty()||state.getAsInt()!=message.expectedState())reason="stale_or_unknown_target";
                else if(!DeepSurvivalPlacement.lookup(player.level()).map(e->e.readyForMining(player,message.position())).orElse(false))reason="unsupported_mining_context";
                else if(message.action()!=net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                        && (!miningContextReady(player,message.position())||message.expectedState()!=session.miningExpected))reason="changed_mining_context";
            }
        }
        if(reason!=null){
            // Rejected STOP/START cannot leave an old native delayed destroy armed.
            if(message.sequence()>session.miningLastSequence && session.miningOperation==message.operation())cancelMining(player,reason);
            acknowledgeMining(player,session,new DeepRuntimeNetwork.MiningAck(session.token,message.sequence(),message.operation(),false,true,reason));return;
        }
        if(message.action()==net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            if(session.miningTarget!=null)cancelMining(player,"replaced");
            session.miningOperation=message.operation();session.miningTarget=message.position().immutable();session.miningExpected=message.expectedState();session.miningTool=player.getMainHandItem().copy();
        }
        session.miningLastSequence=message.sequence();session.miningReplySequence=message.sequence();session.miningLastAck=null;
        try {
            // Real native START/STOP/ABORT and native tick own hardness and delayed completion.
            player.gameMode.handleBlockBreakAction(message.position(),message.action(),message.direction(),player.level().getMaxBuildHeight(),message.nativeSequence());
            if(session.miningLastAck!=null)return; // Instant or STOP commit already sent terminal outcome.
            if(abort){miningCancelled(player,"aborted");return;}
            if(!((ServerMiningAccess)player.gameMode).mia$miningActive()){miningCancelled(player,"native_mining_refused");return;}
            acknowledgeMining(player,session,new DeepRuntimeNetwork.MiningAck(session.token,message.sequence(),message.operation(),true,false,"native_action_received"));
        }catch(RuntimeException refused){cancelMining(player,"native_action_refused");}
    }
    private static void acknowledgeMining(ServerPlayer player,Session session,DeepRuntimeNetwork.MiningAck ack) {
        if(ack.reason()==null||ack.reason().isEmpty())ack=new DeepRuntimeNetwork.MiningAck(ack.session(),ack.sequence(),ack.operation(),ack.accepted(),ack.terminal(),"native_mining_refused");
        if(ack.reason().length()>128)ack=new DeepRuntimeNetwork.MiningAck(ack.session(),ack.sequence(),ack.operation(),ack.accepted(),ack.terminal(),ack.reason().substring(0,128));
        if(ack.sequence()>=session.miningLastSequence){session.miningLastSequence=ack.sequence();session.miningLastAck=ack;}
        DeepRuntimeNetwork.miningAck(player,ack);
    }
    public static void miningCompleted(ServerPlayer player,BlockPos pos,boolean accepted,String reason) {
        var session=PLAYERS.get(player.getUUID());if(session==null||session.miningTarget==null||!session.miningTarget.equals(pos))return;
        if(accepted) {
            var changed=CubeKey.fromBlock(coordinate(pos));var domain=session.domain;
            // Geometry and light are published only after native durability/drop/cube commit.
            for(ServerPlayer observer:player.getServer().getPlayerList().getPlayers()) {
                var other=PLAYERS.get(observer.getUUID());
                if(other!=null&&observer.level()==player.level()&&domain.residency.ownerWindow(observer.getUUID().toString()).contains(changed))sendWindow(observer,domain,other);
            }
            DeepLightDependencies.changed(player.serverLevel(),changed);DeepVisualRuntime.changed(player.level(),changed);
            generatedCubeChanged(player.serverLevel(),domain,changed);
        }
        var ack=new DeepRuntimeNetwork.MiningAck(session.token,session.miningReplySequence,session.miningOperation,accepted,true,accepted?"accepted":reason);
        session.miningTarget=null;session.miningOperation=0;session.miningTool=null;
        ((ServerMiningAccess)player.gameMode).mia$cancelMining();acknowledgeMining(player,session,ack);
    }
    public static void miningCancelled(ServerPlayer player,String reason) {
        var session=PLAYERS.get(player.getUUID());if(session==null||session.miningTarget==null)return;
        miningCompleted(player,session.miningTarget,false,reason);
    }
    private static void cancelMining(ServerPlayer player,String reason) {
        var session=PLAYERS.get(player.getUUID());if(session==null||session.miningTarget==null)return;
        var pos=session.miningTarget;
        // Known native ABORT is safe before residency is removed. Mixin guards unknown ABORT.
        player.gameMode.handleBlockBreakAction(pos,net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,net.minecraft.core.Direction.DOWN,player.level().getMaxBuildHeight(),0);
        ((ServerMiningAccess)player.gameMode).mia$cancelMining();miningCancelled(player,reason);
    }
    public static void query(ServerPlayer player,DeepRuntimeNetwork.Query query) {
        var state=new JsonObject(); var domain=DOMAINS.get(player.serverLevel()); var session=PLAYERS.get(player.getUUID());
        state.addProperty("active",session!=null); state.addProperty("serverPid",ProcessHandle.current().pid());
        state.add("visual",DeepVisualRuntime.status(player));
        if(session!=null){var prepare=new JsonObject();prepare.addProperty("ticks",session.prepareTicks);prepare.addProperty("maxMillis",session.prepareMaxMillis);
            prepare.addProperty("meanMillis",session.prepareTicks==0?0:session.prepareTotalMillis/session.prepareTicks);prepare.addProperty("over5ms",session.prepareOver5ms);prepare.addProperty("over50ms",session.prepareOver50ms);
            var phases=new JsonObject();for(int i=0;i<PHASES.length;i++){var item=new JsonObject();item.addProperty("maxMillis",session.phaseMaxMillis[i]);item.addProperty("totalMillis",session.phaseTotalMillis[i]);item.addProperty("over5ms",session.phaseOver5ms[i]);phases.add(PHASES[i],item);}
            prepare.add("phases",phases);
            state.add("prepareCost",prepare);}
        state.add("lightDependencies",DeepLightDependencies.status(player));
        state.addProperty("dedicated",player.getServer().isDedicatedServer());
        state.addProperty("dimension",player.level().dimension().location().toString());
        state.addProperty("levelMinY",player.level().getMinBuildHeight());state.addProperty("levelHeight",player.level().getHeight());
        var column=player.level().getChunkSource().getChunk(Math.floorDiv(player.blockPosition().getX(),16),Math.floorDiv(player.blockPosition().getZ(),16),net.minecraft.world.level.chunk.ChunkStatus.FULL,false);
        state.addProperty("nativeSections",column==null?0:column.getSections().length);
        state.addProperty("x",player.getX());state.addProperty("y",player.getY());state.addProperty("z",player.getZ());state.addProperty("onGround",player.onGround());
        state.addProperty("flying",player.getAbilities().flying);state.addProperty("flightSpeed",player.getAbilities().getFlyingSpeed());
        if(domain!=null) {
            state.addProperty("fixture",domain.generated!=null?"generated":domain.vertical?"vertical":"corridor");state.addProperty("windowRevision",domain.revision);
            if(domain.generated!=null)state.add("generatedProfile",domain.generated.status());
            var metrics=domain.residency.metrics();
            state.add("asyncCubeSaving",saveStatus(domain.residency));
            state.addProperty("owners",metrics.owners());state.addProperty("residentCubes",metrics.residentCubes());
            state.addProperty("loadedCubes",metrics.loadedCubes());state.addProperty("savedCubes",metrics.savedCubes());state.addProperty("unloadedCubes",metrics.unloadedCubes());
            var block=domain.residency.space().block(coordinate(query.pos()));state.addProperty("loaded",block.isPresent());
            if(block.isPresent()) {
                var actual=Block.stateById(block.getAsInt());
                state.addProperty("block",BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString());
                state.addProperty("fluidType",BuiltInRegistries.FLUID.getKey(actual.getFluidState().getType()).toString());
                if(actual.hasProperty(net.minecraft.world.level.block.LiquidBlock.LEVEL)) state.addProperty("fluidLevel",actual.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL));
            }
            state.add("fluid",fluidStatus(domain,query.pos()));
            state.add("chest",chestStatus(player,domain,query.pos()));
            if(domain.item!=null)state.add("item",domain.item.status());
            state.addProperty("blockLightImplemented", DeepCubeDomain.blockLightEnabled(player.level()));
            if (DeepCubeDomain.blockLightEnabled(player.level()) && DeepCubeDomain.isDeep(player.level(), query.pos())) {
                state.addProperty("blockLightImplemented", true);
                state.addProperty("skyLightImplemented", false);
                try {
                    state.addProperty("blockLight", player.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, query.pos()));
                    state.addProperty("blockLightKnown", true);
                } catch (RuntimeException unavailable) {
                    state.addProperty("blockLightKnown", false);
                    state.addProperty("blockLightError", unavailable.toString());
                }
                var light = DeepCubeDomain.lookup(player.level()).orElseThrow().blockLight();
                state.addProperty("lightComputedCubes", light.computedCubes());
                state.addProperty("lightRecomputations", light.recomputations());
            }
            state.addProperty("file",domain.store.path(CubeKey.fromBlock(coordinate(query.pos()))).toAbsolutePath().toString());
        }
        if(session!=null) {
            state.addProperty("miningOperation",session.miningOperation);state.addProperty("miningSequence",session.miningLastSequence);state.addProperty("miningActive",((ServerMiningAccess)player.gameMode).mia$miningActive());
            if(session.miningLastAck!=null){state.addProperty("miningAccepted",session.miningLastAck.accepted());state.addProperty("miningTerminal",session.miningLastAck.terminal());state.addProperty("miningReason",session.miningLastAck.reason());}
            state.addProperty("itemAction",session.itemAction);state.addProperty("itemAccepted",session.itemAccepted);state.addProperty("itemError",session.itemError);
            if(domain!=null && domain.item!=null && domain.item.item()!=null)state.addProperty("token",domain.item.status().get("token").getAsString());
            state.addProperty("chestAction",session.chestAction);state.addProperty("chestAccepted",session.chestAccepted);state.addProperty("chestError",session.chestError);
            state.addProperty("fluidAction",session.fluidAction);state.addProperty("fluidAccepted",session.fluidAccepted);state.addProperty("fluidError",session.fluidError);
            state.addProperty("error",session.error);state.addProperty("positionPackets",session.positionPackets);
            state.addProperty("globalDeltaRevision",session.globalDeltaRevision);state.addProperty("globalDeltaQueuedCubes",session.globalDeltaPending.size());state.addProperty("globalDeltaQueuedBytes",session.globalDeltaPendingBytes);
            state.addProperty("rejectedMoves",session.rejectedMoves);state.addProperty("movementSamples",session.movementSamples);
            if(Double.isFinite(session.minY)) state.addProperty("minY",session.minY);
            state.addProperty("maxVerticalStep",session.maxVerticalStep);
            state.addProperty("loadTargetGeneration",session.loadTargetGeneration);
            state.addProperty("predictionResets",session.predictionResets);
            if(session.loadTarget!=null){
                var target=new JsonObject();target.addProperty("x",session.loadTarget.x());target.addProperty("y",session.loadTarget.y());target.addProperty("z",session.loadTarget.z());
                target.addProperty("ageMillis",(System.nanoTime()-session.loadDesiredNanos)/1e6);state.add("loadDesiredCube",target);
            }
            state.add("positionEvents",session.positionEvents.deepCopy());
            if(session.lastRejected!=null) state.add("lastRejected",session.lastRejected.deepCopy());
        }
        DeepRuntimeNetwork.state(player,query.id(),DeepChestDiagnostics.state(state).toString());
    }
    /** Diagnostic fields only; resident content selection and rendered appearance are unchanged. */
    static JsonObject saveStatus(CubeResidencyService residency) {
        var m=residency.saveMetrics();var out=new JsonObject();
        out.addProperty("enabled",m.enabled());out.addProperty("pinnedCubes",m.pinnedCubes());
        out.addProperty("pendingWrites",m.pendingWrites());out.addProperty("maxPinnedCubes",m.maxPinnedCubes());
        out.addProperty("peakPinnedCubes",m.peakPinnedCubes());out.addProperty("submitted",m.submitted());
        out.addProperty("completed",m.completed());out.addProperty("failures",m.failures());
        out.addProperty("retries",m.retries());out.addProperty("staleSnapshots",m.staleSnapshots());
        out.addProperty("backpressureWrites",m.backpressureWrites());out.addProperty("lastFailure",m.lastFailure());
        out.addProperty("ownerThreadWrites",m.ownerThreadWrites());
        out.addProperty("backpressureEvents",m.backpressureEvents());
        return out;
    }
    /** Explicit diagnostic native water seed. It never overwrites a visited nonwater construction. */
    public static void fluidControl(ServerPlayer player,DeepRuntimeNetwork.FluidControl message) {
        var session=PLAYERS.get(player.getUUID());
        if(session==null || !session.token.equals(message.session())) {
            var denied=new JsonObject();denied.addProperty("fluidAccepted",false);denied.addProperty("fluidError","inactive_session");
            DeepRuntimeNetwork.state(player,message.id(),denied.toString());return;
        }
        var domain=session.domain;session.fluidAction=message.action();session.fluidAccepted=false;session.fluidError="";
        try {
            if(domain.vertical) throw new IllegalStateException("Water acceptance uses the bounded corridor fixture");
            if(message.action().equals("activate")) {
                if(domain.fluid==null) domain.fluid=DeepFluidEngine.attach(session.level,domain.residency.space());
                domain.fluidPaused=true;
                player.getAbilities().flying=true;player.onUpdateAbilities();player.setDeltaMovement(Vec3.ZERO);
                session.fluidAccepted=true;
            } else {
                if(domain.fluid==null) throw new IllegalStateException("Activate the fluid fixture first");
                switch(message.action()) {
                    case "pause" -> {domain.fluidPaused=true;session.fluidAccepted=true;}
                    case "resume" -> {domain.fluidPaused=false;session.fluidAccepted=true;}
                    case "seed" -> {
                        if(!session.level.getBlockState(FLUID_SOURCE).isAir()) throw new IllegalStateException("Seed position must be confirmed AIR");
                        session.fluidAccepted=domain.fluid.edit(session.level,FLUID_SOURCE,Blocks.WATER.defaultBlockState());
                    }
                    case "remove" -> {
                        if(!session.level.getBlockState(FLUID_SOURCE).is(Blocks.WATER)) throw new IllegalStateException("Source position must contain water");
                        session.fluidAccepted=domain.fluid.edit(session.level,FLUID_SOURCE,Blocks.AIR.defaultBlockState());
                    }
                    default -> throw new IllegalArgumentException("Unsupported fluid control");
                }
                publishFluid(session.level,domain);
                if(!session.fluidAccepted) session.fluidError="native_transaction_refused";
            }
        } catch(RuntimeException refused) {session.fluidError=refused.toString();}
        query(player,new DeepRuntimeNetwork.Query(message.id(),FLUID_SOURCE));
    }
    private static void publishFluid(ServerLevel level,Domain domain) {
        var changed=domain.fluid.drainChanged();if(changed.isEmpty()) return;
        for(var observer:level.getServer().getPlayerList().getPlayers()) {
            var session=PLAYERS.get(observer.getUUID());
            if(session!=null && session.domain==domain && domain.residency.ownerWindow(observer.getUUID().toString()).stream().anyMatch(changed::contains))
                sendWindow(observer,domain,session);
        }
        for(var key:changed) {DeepLightDependencies.changed(level,key);DeepVisualRuntime.changed(level,key);generatedCubeChanged(level,domain,key);}
    }
    private static boolean hasChestPayloads(Domain domain){for(var key:domain.residency.space().residentKeys())if(!domain.residency.space().snapshot(key).orElseThrow().blockEntities().isEmpty())return true;return false;}
    private static void attachChest(ServerLevel level,Domain domain) {
        if(domain.chest!=null)return;
        domain.chest=DeepChestEngine.attach(level,domain.residency.space());
        domain.chest.onLidEvent(event->{
            var key=CubeKey.fromBlock(coordinate(event.position()));
            for(var observer:level.getServer().getPlayerList().getPlayers()) {
                var session=PLAYERS.get(observer.getUUID());
                if(session!=null && session.domain==domain && domain.residency.ownerWindow(observer.getUUID().toString()).contains(key))
                    DeepRuntimeNetwork.lid(observer,new DeepRuntimeNetwork.Lid(session.token,session.geometryRevision,event.position(),event.id(),event.value()));
            }
        });
    }
    public static void chestControl(ServerPlayer player,DeepRuntimeNetwork.ChestControl message) {
        var session=PLAYERS.get(player.getUUID());
        if(session==null || !session.token.equals(message.session())){DeepRuntimeNetwork.state(player,message.id(),"{\"chestAccepted\":false,\"chestError\":\"inactive_session\"}");return;}
        var domain=session.domain;session.chestAction=message.action();session.chestAccepted=false;session.chestError="";
        try {
            if(domain.vertical || !player.isCreative())throw new IllegalStateException("chest fixture requires creative corridor");
            attachChest(session.level,domain);
            switch(message.action()) {
                case "activate","query" -> session.chestAccepted=true;
                case "place" -> session.chestAccepted=domain.chest.place(session.level,CHEST_SOURCE,Blocks.CHEST.defaultBlockState(),null);
                case "remove" -> session.chestAccepted=domain.chest.remove(session.level,CHEST_SOURCE);
                default -> throw new IllegalArgumentException("unsupported chest control");
            }
            publishChest(session.level,domain);
            if(!session.chestAccepted)session.chestError="native_chest_refused";
        }catch(RuntimeException refused){session.chestError=refused.toString();}
        query(player,new DeepRuntimeNetwork.Query(message.id(),CHEST_SOURCE));
    }
    public static void chestUse(ServerPlayer player,DeepChestPackets.Use message) {
        var session=PLAYERS.get(player.getUUID());
        if(session==null || !session.token.equals(message.session())){DeepRuntimeNetwork.state(player,message.id(),"{\"chestAccepted\":false,\"chestError\":\"inactive_session\"}");return;}
        session.chestAction="use";session.chestAccepted=false;session.chestError="";
        try {
            if(player.level()!=session.level || !player.isAlive() || player.isSpectator() || !player.level().dimension().location().toString().equals(message.dimension()) || !prepare(player))throw new IllegalStateException("unavailable player/dimension");
            if(!DeepCubeDomain.isDeep(session.level,message.position()) || !player.level().getWorldBorder().isWithinBounds(message.position()))throw new IllegalStateException("outside chest domain");
            var id=session.domain.residency.space().block(coordinate(message.position())).orElseThrow(()->new IllegalStateException("unknown chest"));
            if(id!=message.expectedState() || !Block.stateById(id).is(Blocks.CHEST))throw new IllegalStateException("stale/unsupported chest");
            var eye=player.getEyePosition();double reach=player.getBlockReach();var hit=message.hit();var pos=message.position();
            var local=hit.subtract(pos.getX(),pos.getY(),pos.getZ());
            if(eye.distanceToSqr(hit)>reach*reach || local.x<-.001 || local.x>1.001 || local.y<-.001 || local.y>1.001 || local.z<-.001 || local.z>1.001)throw new IllegalStateException("invalid hit/reach");
            var direction=hit.subtract(eye);if(direction.lengthSqr()<1e-12)throw new IllegalStateException("invalid chest ray");
            var actual=DeepCubeDomain.view(session.level).orElseThrow().clip(new net.minecraft.world.level.ClipContext(eye,hit.add(direction.normalize().scale(.02)),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,player));
            if(actual.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK || !actual.getBlockPos().equals(pos) || actual.getDirection()!=message.face())throw new IllegalStateException("occluded/wrong chest face");
            if(player.containerMenu!=player.inventoryMenu)throw new IllegalStateException("container already open");
            attachChest(session.level,session.domain);session.chestAccepted=session.domain.chest.open(player,pos);
            if(!session.chestAccepted)session.chestError="native_menu_refused";
        }catch(RuntimeException refused){session.chestError=refused.toString();}
        query(player,new DeepRuntimeNetwork.Query(message.id(),message.position()));
    }
    private static void publishChest(ServerLevel level,Domain domain) {
        var changed=domain.chest.drainChanged();if(changed.isEmpty())return;
        for(var observer:level.getServer().getPlayerList().getPlayers()) {
            var session=PLAYERS.get(observer.getUUID());
            if(session!=null && session.domain==domain && domain.residency.ownerWindow(observer.getUUID().toString()).stream().anyMatch(changed::contains))sendWindow(observer,domain,session);
        }
        for(var key:changed){DeepLightDependencies.changed(level,key);DeepVisualRuntime.changed(level,key);generatedCubeChanged(level,domain,key);}
    }
    private static void attachItems(ServerLevel level,Domain domain) throws IOException {
        if(domain.item!=null)return;
        var path=domain.store.path(new CubeKey(0,0,0)).getParent().getParent().resolve("managed-items-v1");
        var uuid=UUID.nameUUIDFromBytes(("madeinabyss:managed-item-v1:"+path.toAbsolutePath()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        domain.item=new DeepManagedItemEngine(level,domain.residency.space(),new ManagedItemStore(path),uuid);
        domain.item.pause(true);
    }
    private static void publishItems(ServerLevel level,Domain domain) {
        if(domain.item==null)return;
        var observers=new java.util.ArrayList<DeepManagedItemEngine.Observer>();
        for(var player:level.getServer().getPlayerList().getPlayers()){
            var session=PLAYERS.get(player.getUUID());
            if(session!=null && session.domain==domain && !session.sent.isEmpty())observers.add(new DeepManagedItemEngine.Observer(player,session.token,session.sent));
        }
        domain.item.observers(observers);
    }
    public static void itemControl(ServerPlayer player,DeepRuntimeNetwork.ItemControl message) {
        var session=PLAYERS.get(player.getUUID());
        if(session==null || !session.token.equals(message.session())){DeepRuntimeNetwork.state(player,message.id(),"{\"itemAccepted\":false,\"itemError\":\"inactive_session\"}");return;}
        var domain=session.domain;session.itemAction=message.action();session.itemAccepted=false;session.itemError="";
        try{
            if(domain.vertical || !player.isCreative() || player.level()!=session.level || !player.isAlive() || !prepare(player))throw new IllegalStateException("item fixture requires available creative corridor");
            attachItems(session.level,domain);
            switch(message.action()){
                case "activate" -> domain.item.pause(true);
                case "seed" -> {domain.item.pause(true);domain.item.seed(new Vec3(2.5,-19996.25,6.5),Vec3.ZERO,UUID.randomUUID().toString());}
                case "pause" -> domain.item.pause(true);
                case "resume" -> domain.item.pause(false);
                case "flush" -> domain.item.flush();
                case "remove" -> domain.item.erase();
                case "query" -> {}
                default -> throw new IllegalArgumentException("unsupported item control");
            }
            publishItems(session.level,domain);session.itemAccepted=true;
        }catch(IOException | RuntimeException refused){session.itemError=refused.toString();}
        query(player,new DeepRuntimeNetwork.Query(message.id(),new BlockPos(2,-19999,6)));
    }
    private static JsonObject chestStatus(ServerPlayer player,Domain domain,BlockPos pos) {
        var out=new JsonObject();out.addProperty("enabled",domain.chest!=null);out.addProperty("live",domain.chest==null?0:domain.chest.liveCount());
        out.addProperty("scope","dry-native-single-chest;native-menu;full-nbt;bounded-game-events");
        if(domain.chest==null)return out;
        var stats=domain.chest.stats();out.addProperty("completed",stats.completed());out.addProperty("stale",stats.stale());out.addProperty("deferred",stats.deferred());out.addProperty("rolledBack",stats.rolledBack());out.addProperty("lastError",domain.chest.lastError());
        if(domain.residency.space().block(coordinate(pos)).isEmpty())return out;
        var chest=domain.chest.get(player.level(),pos);out.addProperty("present",chest!=null);
        if(chest!=null) {
            out.addProperty("openers",net.minecraft.world.level.block.entity.ChestBlockEntity.getOpenCount(player.level(),pos));
            out.addProperty("name",chest.getName().getString());out.addProperty("menuOpen",player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu menu && menu.getContainer()==chest);
            out.add("items",DeepChestDiagnostics.items(chest));
            out.addProperty("payloadBytes",domain.residency.space().blockEntity(coordinate(pos)).orElseThrow().data().length);
        }
        var pending=new JsonArray();for(var tick:domain.residency.space().scheduledTicks().snapshot(CubeKey.fromBlock(coordinate(pos))))if(tick.position().equals(coordinate(pos)) && tick.kind()==dev.madeinabyss.kernel.tick.SavedCubeTick.Kind.BLOCK){var t=new JsonObject();t.addProperty("type",tick.type());t.addProperty("delay",tick.delay());t.addProperty("order",tick.order());pending.add(t);}out.add("pending",pending);
        return out;
    }
    private static JsonObject fluidStatus(Domain domain,BlockPos position) {
        var out=new JsonObject();out.addProperty("enabled",domain.fluid!=null);out.addProperty("paused",domain.fluidPaused);
        out.addProperty("clock",domain.residency.space().scheduledTicks().clock());
        out.addProperty("pending",domain.residency.space().scheduledTicks().pendingCount());
        out.addProperty("scope","native-water-air-inert;loaded-clock;clean-restart");
        if(domain.fluid!=null) {
            var stats=domain.fluid.stats();out.addProperty("attempted",stats.attempted());out.addProperty("completed",stats.completed());
            out.addProperty("stale",stats.stale());out.addProperty("deferred",stats.deferred());out.addProperty("unsupported",stats.unsupported());out.addProperty("rolledBack",stats.rolledBack());
            out.addProperty("lastError",domain.fluid.lastError());
        }
        var pending=new JsonArray();
        for(var tick:domain.residency.space().scheduledTicks().snapshot(CubeKey.fromBlock(coordinate(position)))) {
            if(!tick.position().equals(coordinate(position))) continue;
            var t=new JsonObject();t.addProperty("kind",tick.kind().toString());t.addProperty("type",tick.type());t.addProperty("delay",tick.delay());t.addProperty("priority",tick.priority());t.addProperty("order",tick.order());pending.add(t);
        }
        out.add("atPosition",pending);return out;
    }
    /**
     * Publishes one player's combined physical, visual and extrapolated load interest before
     * residency admission. The loader merges all four players before cancelling stale work.
     */
    private static java.util.List<CubeKey> prefetch(ServerPlayer player,Session session,Domain domain){
        double x=player.getX(),y=player.getY(),z=player.getZ();
        if(Double.isFinite(session.lastX)){
            double dx=x-session.lastX,dy=y-session.lastPrefetchY,dz=z-session.lastZ;
            long elapsed=Math.max(1L,(long)player.tickCount-session.lastPrefetchTick);
            // A discontinuous relocation is a new target, not a sustained hundreds-m/s velocity.
            // tick/doTick can both prepare one unchanged position; do not halve velocity twice.
            if(dx*dx+dy*dy+dz*dz>256.0*elapsed*elapsed){
                session.velocityX=session.velocityY=session.velocityZ=0;session.predictionResets++;
            }else if(player.tickCount!=session.lastPrefetchTick||dx!=0||dy!=0||dz!=0){
                session.velocityX=session.velocityX*.5+dx/elapsed*.5;
                session.velocityY=session.velocityY*.5+dy/elapsed*.5;
                session.velocityZ=session.velocityZ*.5+dz/elapsed*.5;
            }
        }
        session.lastX=x;session.lastPrefetchY=y;session.lastZ=z;session.lastPrefetchTick=player.tickCount;
        var centers=new java.util.ArrayList<CubeKey>();
        if(domain.residency.loader()==null||domain.generated==null)return centers;
        for(int i=0;i<PREFETCH_TICKS.length;i++){
            int t=PREFETCH_TICKS[i];double px=x+session.velocityX*t,py=y+session.velocityY*t,pz=z+session.velocityZ*t;
            if(!Double.isFinite(px)||!Double.isFinite(py)||!Double.isFinite(pz)||Math.abs(px)>1e8||Math.abs(pz)>1e8||py<DeepCubeDomain.TECHNICAL_BOTTOM||py>1e6)continue;
            var center=CubeKey.fromBlock(new BlockCoordinate((int)Math.floor(px),(int)Math.floor(py),(int)Math.floor(pz)));
            if(!centers.isEmpty()&&centers.get(centers.size()-1).equals(center))continue;
            centers.add(center);
        }
        if(!centers.isEmpty()){
            var desired=centers.get(0);
            if(!desired.equals(session.loadTarget)){
                session.loadTarget=desired;session.loadTargetGeneration++;session.loadDesiredNanos=System.nanoTime();
            }
        }
        var interest=loadInterest(domain.generated,centers,key->!DeepVisualRuntime.alreadySent(player,key));
        interest.keySet().removeIf(domain.residency.space()::isLoaded);
        domain.residency.loader().replaceInterest(player.getUUID().toString(),interest);
        return centers;
    }
    /** Production load plan; support and clipping are shared with actual visual publication. */
    private static Map<CubeKey,Integer> loadInterest(GeneratedPreviewProfile profile,java.util.List<CubeKey> centers,
                                                   java.util.function.Predicate<CubeKey> needVisual){
        var interest=new HashMap<CubeKey,Integer>();var generator=profile.generator();
        for(int i=0;i<centers.size();i++){
            var center=centers.get(i);int physicalPriority=i==0?0:2+i;
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
                var key=new CubeKey(center.x()+dx,center.y()+dy,center.z()+dz);
                // Unknown spatial domain is never generated, prefetched or treated as air.
                if(generator.supportsCube(key))interest.merge(key,physicalPriority,Math::min);
            }
            int minimum=profile.visualMinimumCubeY(center);if(minimum>center.y())continue;
            int visualPriority=i==0?1:6+i;
            for(var key:DeepVisualNetwork.windowKeys(center,minimum))
                if(needVisual.test(key)&&generator.supportsCube(key))interest.merge(key,visualPriority,Math::min);
        }
        return interest;
    }
    private static BlockCoordinate coordinate(BlockPos pos) {return new BlockCoordinate(pos.getX(),pos.getY(),pos.getZ());}
    private static boolean fitsWindow(ServerPlayer player) {
        var swept=player.getBoundingBox().expandTowards(player.getDeltaMovement());
        if(swept.minY<DeepCubeDomain.TECHNICAL_BOTTOM) return false;
        var bounds=swept.inflate(8);
        return SweptCubeWindow.fits(coordinate(player.blockPosition()),bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ,
                DeepCubeDomain.TECHNICAL_BOTTOM,DeepCubeDomain.managedTop(player.level()));
    }
    private static CubeSnapshot generate(CubeKey key) {
        int[] states=new int[4096]; java.util.Arrays.fill(states,Block.getId(Blocks.AIR.defaultBlockState()));
        // ORIGINAL diagnostic corridor, versioned independently from future canon generation.
        for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            long gx=(long)key.x()*16+x,gy=(long)key.y()*16+y,gz=(long)key.z()*16+z;
            if(gy==-20000 && gx>=-256 && gx<=256 && gz>=-8 && gz<=16) states[y<<8|z<<4|x]=Block.getId(Blocks.STONE.defaultBlockState());
            if(gx==5 && gy==-20000 && gz==5) states[y<<8|z<<4|x]=Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState());
            if(gx==8 && gy==-19999 && gz==5) states[y<<8|z<<4|x]=Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
            if(gx==2 && gy==-19999 && gz==8) states[y<<8|z<<4|x]=Block.getId(Blocks.GLOWSTONE.defaultBlockState());
        }
        return new CubeSnapshot(key,states);
    }
}
