package dev.madeinabyss.forge.probe.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.RemotePlayerAvailability;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.UUID;

/** File-only development control. Disabled unless the isolated run explicitly enables it. */
@Mod.EventBusSubscriber(modid = MadeInAbyssProbe.MOD_ID, value = Dist.CLIENT)
public final class ClientProbeControl {
    private static final Gson JSON = new Gson();
    private static final String SESSION = UUID.randomUUID().toString();
    private static boolean attempted;
    private static Path root;
    private static int movementTicks;
    private static final long[] frameIntervals=new long[8192];
    private static int frameCursor,frameSamples;
    private static long frameLast,frameCount,frameTotal,frameMaximum,framesOver50,framesOver100,framesOver250;
    private static final long[] slowFrameEndMillis=new long[64],slowFrameNanos=new long[64];
    private static int slowFrameCursor,slowFrameCount;

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||!Boolean.getBoolean("mia.client.automation"))return;
        var client=Minecraft.getInstance();long now=System.nanoTime();
        if(client.level==null||client.player==null){frameLast=0;return;}
        if(frameLast!=0){
            long elapsed=now-frameLast;
            frameIntervals[frameCursor]=elapsed;frameCursor=(frameCursor+1)%frameIntervals.length;
            frameSamples=Math.min(frameSamples+1,frameIntervals.length);frameCount++;frameTotal+=elapsed;
            frameMaximum=Math.max(frameMaximum,elapsed);
            if(elapsed>50_000_000){framesOver50++;slowFrameEndMillis[slowFrameCursor]=System.currentTimeMillis();slowFrameNanos[slowFrameCursor]=elapsed;
                slowFrameCursor=(slowFrameCursor+1)%slowFrameNanos.length;slowFrameCount=Math.min(slowFrameCount+1,slowFrameNanos.length);}
            if(elapsed>100_000_000)framesOver100++;
            if(elapsed>250_000_000)framesOver250++;
        }
        frameLast=now;
    }

    private static JsonObject frameTimings(boolean reset) {
        if(reset){frameCursor=frameSamples=slowFrameCursor=slowFrameCount=0;frameLast=frameCount=frameTotal=frameMaximum=framesOver50=framesOver100=framesOver250=0;}
        var result=new JsonObject();var sorted=java.util.Arrays.copyOf(frameIntervals,frameSamples);java.util.Arrays.sort(sorted);
        result.addProperty("frames",frameCount);result.addProperty("sampledRecentFrames",frameSamples);
        result.addProperty("meanMillis",frameCount==0?0:frameTotal/(1e6*frameCount));result.addProperty("maxMillis",frameMaximum/1e6);
        result.addProperty("p95Millis",frameSamples==0?0:sorted[(int)Math.ceil(frameSamples*.95)-1]/1e6);
        result.addProperty("p99Millis",frameSamples==0?0:sorted[(int)Math.ceil(frameSamples*.99)-1]/1e6);
        result.addProperty("over50Millis",framesOver50);result.addProperty("over100Millis",framesOver100);result.addProperty("over250Millis",framesOver250);
        var slow=new com.google.gson.JsonArray();for(int offset=0;offset<slowFrameCount;offset++){
            int index=Math.floorMod(slowFrameCursor-slowFrameCount+offset,slowFrameNanos.length);var row=new JsonObject();
            row.addProperty("endEpochMillis",slowFrameEndMillis[index]);row.addProperty("durationMillis",slowFrameNanos[index]/1e6);slow.add(row);
        }result.add("recentSlowFrames",slow);
        return result;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCubeProbe.disconnect();
        ClientDeepRuntime.disconnect();
        if (Boolean.getBoolean("mia.client.automation")) release(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Boolean.getBoolean("mia.client.automation")) return;
        Minecraft client = Minecraft.getInstance();
        if (!attempted) {
            attempted = true;
            try { start(client); }
            catch (Exception error) { LogUtils.getLogger().error("MIA client control initialization failed", error); }
        }
        if (movementTicks > 0 && (--movementTicks == 0 || client.player == null || client.screen != null)) {
            release(client);
        }
    }

    private static void start(Minecraft client) throws Exception {
        if(System.getProperty("mia.client.maxFps") != null) {
            int limit = Integer.parseInt(System.getProperty("mia.client.maxFps"));
            if(limit < 10 || limit > 260) throw new IllegalArgumentException("Diagnostic FPS must be 10..260");
            client.options.framerateLimit().set(limit);
            client.getWindow().setFramerateLimit(limit);
        }
        root = Path.of(System.getProperty("mia.control.root", new java.io.File(client.gameDirectory, "automation").getPath()));
        Path requests = Files.createDirectories(root.resolve("requests"));
        Files.createDirectories(root.resolve("processing"));
        Files.createDirectories(root.resolve("responses"));
        WatchService watcher = requests.getFileSystem().newWatchService();
        requests.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
        JsonObject ready = new JsonObject();
        ready.addProperty("session", SESSION);
        ready.addProperty("minecraft", "1.20.1");
        ready.addProperty("pid", ProcessHandle.current().pid());
        atomicWrite(root.resolve("ready.json"), ready);
        Thread worker = new Thread(() -> {
            try {
                while (true) {
                    var key = watcher.take();
                    for (var event : key.pollEvents()) {
                        String name = event.context().toString();
                        if (!name.matches("[a-f0-9-]{36}\\.json")) continue;
                        Path claimed = root.resolve("processing").resolve(name);
                        try {
                            Files.move(requests.resolve(name), claimed, StandardCopyOption.ATOMIC_MOVE);
                            if (Files.size(claimed) > 8192) throw new IllegalArgumentException("Request exceeds 8 KiB");
                            JsonObject request = JsonParser.parseString(Files.readString(claimed)).getAsJsonObject();
                            client.execute(() -> handle(client, request, claimed, name));
                        } catch (Exception error) {
                            respond(name, null, error);
                            Files.deleteIfExists(claimed);
                        }
                    }
                    if (!key.reset()) break;
                }
            } catch (Exception error) { LogUtils.getLogger().error("MIA client control watcher stopped", error); }
        }, "mia-client-control");
        worker.setDaemon(true);
        worker.start();
        LogUtils.getLogger().info("MIA_CLIENT_CONTROL_READY directory={}", root.toAbsolutePath());
    }

    private static void handle(Minecraft client, JsonObject request, Path claimed, String name) {
        if(!GeneratedFrameCosts.enabled()){handleMeasured(client,request,claimed,name);return;}
        // Reset discards a partial frame and cannot run inside an active timing scope.
        boolean resetCosts=false;
        try{resetCosts=request.has("action")&&request.get("action").getAsString().equals("frame_cost")
                &&request.has("params")&&request.getAsJsonObject("params").has("reset")&&request.getAsJsonObject("params").get("reset").getAsBoolean();
        }catch(RuntimeException malformed){/* The original handler reports malformed requests and releases the claim. */}
        int timing=resetCosts?-1:GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.CONTROL);
        try{handleMeasured(client,request,claimed,name);}finally{GeneratedFrameCosts.end(timing);}
    }
    private static void handleMeasured(Minecraft client, JsonObject request, Path claimed, String name) {
        try {
            if (!SESSION.equals(request.get("session").getAsString())) throw new IllegalArgumentException("Stale client session");
            JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
            JsonObject result = dispatch(client, request.get("action").getAsString(), params);
            respond(name, result, null);
        } catch (Exception error) { respond(name, null, error); }
        finally {
            try { Files.deleteIfExists(claimed); }
            catch (Exception error) { LogUtils.getLogger().warn("Cannot remove processed probe request", error); }
        }
    }

    private static JsonObject stackStatus(net.minecraft.world.item.ItemStack stack) {
        var out=new JsonObject();out.addProperty("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());out.addProperty("count",stack.getCount());
        if(stack.hasTag())out.addProperty("nbt",stack.getTag().toString());return out;
    }
    private static JsonObject dispatch(Minecraft client, String action, JsonObject params) {
        if(action.equals("frame_cost"))return GeneratedFrameCosts.status(params);
        if(action.equals("draw_counters"))return GeneratedDrawCounters.status(params);
        JsonObject result = new JsonObject();
        switch (action) {
            case "frame_timings" -> { return frameTimings(params.has("reset")&&params.get("reset").getAsBoolean()); }
            case "status" -> {
                result.addProperty("connected", client.player != null);
                result.addProperty("integrated", client.hasSingleplayerServer());
                if(client.getSingleplayerServer()!=null)result.addProperty("worldName",client.getSingleplayerServer().getWorldData().getLevelName());
                result.addProperty("paused", client.isPaused());
                result.addProperty("screen", client.screen == null ? "none" : client.screen.getClass().getSimpleName());
                if(client.screen!=null&&client.screen.getTitle().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t)result.addProperty("screenTitleKey",t.getKey());
                result.addProperty("fps", client.getFps());
                result.addProperty("width", client.getMainRenderTarget().width);
                result.addProperty("height", client.getMainRenderTarget().height);
                result.addProperty("fov",client.options.fov().get());
                result.addProperty("forwardPressed", client.options.keyUp.isDown());
                result.addProperty("jumpPressed", client.options.keyJump.isDown());
                result.addProperty("attackPressed", client.options.keyAttack.isDown());
                result.addProperty("debugOverlay", client.options.renderDebug);
                if(client.level!=null){result.addProperty("dayTime",client.level.getDayTime());result.addProperty("rainLevel",client.level.getRainLevel(1));result.addProperty("thunderLevel",client.level.getThunderLevel(1));}
                var mods=new JsonObject();for(var mod:net.minecraftforge.fml.ModList.get().getMods())mods.addProperty(mod.getModId(),mod.getVersion().toString());result.add("mods",mods);
                result.addProperty("movementTicksRemaining", movementTicks);
                result.addProperty("skippedEnvironmentTickCount", RemotePlayerAvailability.skippedEnvironmentTickCount());
                result.addProperty("skippedPushTickCount", RemotePlayerAvailability.skippedPushTickCount());
                result.addProperty("remoteInterpolationTickCount", RemotePlayerAvailability.remoteInterpolationTickCount());
                result.addProperty("knownEnvironmentTickCount", RemotePlayerAvailability.knownEnvironmentTickCount());
                result.addProperty("skippedAmbientLightTickCount",dev.madeinabyss.forge.probe.deep.OptionalAmbientLight.skippedTicks());
                var lightSkips=new JsonObject();
                for(var kind:dev.madeinabyss.forge.probe.deep.OptionalLightWork.Kind.values())
                    lightSkips.addProperty(kind.name(),dev.madeinabyss.forge.probe.deep.OptionalLightWork.skipped(kind));
                result.add("skippedOptionalLightWork",lightSkips);
                JsonArray remotePlayers = new JsonArray();
                if (client.level != null) {
                    for (var entity : client.level.players()) {
                        if (!(entity instanceof RemotePlayer remote)) continue;
                        JsonObject observed = new JsonObject();
                        observed.addProperty("uuid", remote.getUUID().toString());
                        observed.addProperty("username", remote.getGameProfile().getName());
                        observed.add("position", JSON.toJsonTree(new double[]{remote.getX(), remote.getY(), remote.getZ()}));
                        observed.addProperty("tickCount", remote.tickCount);
                        observed.addProperty("yaw", remote.getYRot());
                        observed.addProperty("pitch", remote.getXRot());
                        remotePlayers.add(observed);
                    }
                }
                result.add("remotePlayers", remotePlayers);
                if (client.player != null) {
                    result.addProperty("uuid", client.player.getUUID().toString());
                    result.addProperty("username", client.player.getGameProfile().getName());
                    result.addProperty("gameMode", client.gameMode.getPlayerMode().getName());
                    result.addProperty("health", client.player.getHealth());
                    result.add("position", JSON.toJsonTree(new double[]{client.player.getX(), client.player.getY(), client.player.getZ()}));
                    result.addProperty("yaw", client.player.getYRot());
                    result.addProperty("pitch", client.player.getXRot());
                    result.addProperty("dimension", client.level.dimension().location().toString());
                    result.addProperty("flying", client.player.getAbilities().flying);
                    result.addProperty("flightSpeed", client.player.getAbilities().getFlyingSpeed());
                    result.addProperty("clientTick", client.player.tickCount);
                    result.addProperty("gameTime", client.level.getGameTime());
                    result.addProperty("onGround", client.player.onGround());
                    result.addProperty("heldItem",BuiltInRegistries.ITEM.getKey(client.player.getMainHandItem().getItem()).toString());
                }
            }
            case "open_probe_lan" -> { return ProbeLanBridge.open(client, params); }
            case "probe_lan_status" -> { return ProbeLanBridge.status(client); }
            case "window_size" -> {
                int width = params.get("width").getAsInt();
                int height = params.get("height").getAsInt();
                if (width < 320 || width > 3840 || height < 240 || height > 2160)
                    throw new IllegalArgumentException("Diagnostic window must be 320..3840 by 240..2160");
                client.getWindow().setWindowed(width, height);
                result.addProperty("requestedWidth", width);
                result.addProperty("requestedHeight", height);
                result.addProperty("queued", true);
            }
            case "command" -> {
                requirePlayer(client);
                String command = params.get("text").getAsString().replaceFirst("^/", "");
                client.player.connection.sendCommand(command);
                result.addProperty("queued", true); // Server acceptance is checked separately.
            }
            case "look" -> {
                requirePlayer(client);
                client.player.setYRot(params.get("yaw").getAsFloat());
                client.player.setXRot(Math.max(-90, Math.min(90, params.get("pitch").getAsFloat())));
                result.addProperty("applied", true);
            }
            case "move" -> {
                requirePlayer(client);
                if (client.screen != null) throw new IllegalStateException("Close the screen before moving");
                int ticks = params.get("ticks").getAsInt();
                if (ticks < 1 || ticks > 100) throw new IllegalArgumentException("Movement must be 1..100 ticks");
                boolean up = params.has("up") && params.get("up").getAsBoolean();
                boolean down = params.has("down") && params.get("down").getAsBoolean();
                if ((up || down) && !client.player.getAbilities().flying) throw new IllegalArgumentException("Vertical flight input requires native flight");
                release(client);
                client.options.pauseOnLostFocus = false;
                client.options.keyUp.setDown(params.has("forward") && params.get("forward").getAsBoolean());
                client.options.keyJump.setDown(up || (params.has("jump") && params.get("jump").getAsBoolean()));
                client.options.keyShift.setDown(down);
                client.options.keyAttack.setDown(params.has("attack") && params.get("attack").getAsBoolean());
                movementTicks = ticks;
                result.addProperty("scheduledTicks", ticks);
            }
            case "release" -> { release(client); result.addProperty("released", true); }
            case "camera_hold" -> {
                requirePlayer(client);
                boolean enabled=params.get("enabled").getAsBoolean();
                if(enabled){
                    if(client.screen!=null&&!(client.screen instanceof AutomatedCameraHoldScreen))
                        throw new IllegalStateException("Close the current screen before holding the camera");
                    release(client);net.minecraft.client.KeyMapping.releaseAll();
                    client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    client.options.pauseOnLostFocus=false;
                    client.setScreen(new AutomatedCameraHoldScreen());
                }else if(client.screen instanceof AutomatedCameraHoldScreen)client.setScreen(null);
                result.addProperty("held",client.screen instanceof AutomatedCameraHoldScreen);
                result.addProperty("scope","Development-only input hold; world, native ticks and shaders continue; Escape releases");
            }
            case "camera_fov" -> {
                int fov=params.get("fov").getAsInt();
                if(fov<30||fov>110)throw new IllegalArgumentException("Native FOV must be 30..110");
                client.options.fov().set(fov);result.addProperty("fov",client.options.fov().get());
                result.addProperty("persisted",false);
            }
            case "cube_probe" -> { requirePlayer(client); return ClientCubeProbe.begin(client, params.get("phase").getAsInt()); }
            case "cube_probe_status" -> { return ClientCubeProbe.status(); }
            case "deep_player_probe" -> { requirePlayer(client); return ClientCubeProbe.beginPlayer(client); }
            case "deep_player_query" -> { requirePlayer(client); return ClientCubeProbe.queryPlayer(client, false); }
            case "deep_player_return" -> { requirePlayer(client); return ClientCubeProbe.queryPlayer(client, true); }
            case "deep_player_query_status" -> { return ClientCubeProbe.playerStatus(); }
            case "deep_render_status" -> { return DeepCubeRenderer.status(); }
            case "memory_status" -> { return ClientMemoryDiagnostics.status(); }
            case "native_latency_control" -> {
                requirePlayer(client);
                if(params.has("flightSpeed")){
                    float speed=params.get("flightSpeed").getAsFloat();
                    if(!Float.isFinite(speed)||speed<.01f||speed>.5f
                            ||!client.player.isSpectator()&&!client.player.getAbilities().instabuild)
                        throw new IllegalArgumentException("Native latency movement speed requires flying creative/spectator and .01.. .5");
                    client.player.getAbilities().setFlyingSpeed(speed);
                }
                var controlled=ClientGeneratedLight.latencyControl(params);
                controlled.addProperty("actualFlightSpeed",client.player.getAbilities().getFlyingSpeed());return controlled;
            }
            case "render_distance" -> {
                int chunks=params.get("chunks").getAsInt();
                if(chunks<2||chunks>32)throw new IllegalArgumentException("Vanilla render distance must be2..32chunks");
                client.options.renderDistance().set(chunks);
                boolean persist=params.has("persist")&&params.get("persist").getAsBoolean();
                if(persist)client.options.save();
                var distance=DeepCubeRenderer.status();
                distance.addProperty("requestedChunks",chunks);
                distance.addProperty("targetRadius",Math.min(chunks,dev.madeinabyss.kernel.cube.NativeRenderDistance.MAX_CHUNKS)*16);
                distance.addProperty("persisted",persist);
                return distance;
            }
            case "far_shadow" -> {
                GeneratedFarShadowRenderer.enabled(params.get("enabled").getAsBoolean());
                return GeneratedFarShadowRenderer.status();
            }
            case "shader_toggle" -> {
                requirePlayer(client);
                boolean enabled=params.get("enabled").getAsBoolean();
                try {
                    var iris=Class.forName("net.irisshaders.iris.Iris");
                    var config=iris.getMethod("getIrisConfig").invoke(null);
                    config.getClass().getMethod("setShadersEnabled",boolean.class).invoke(config,enabled);
                    // reload initializes from disk, so persist this isolated-client comparison first.
                    config.getClass().getMethod("save").invoke(config);
                    iris.getMethod("reload").invoke(null);
                    boolean actual=(Boolean)config.getClass().getMethod("areShadersEnabled").invoke(config);
                    if(actual!=enabled)throw new IllegalStateException("Oculus shader toggle readback differs from requested state");
                    result.addProperty("enabled",actual);
                    result.addProperty("fallback",(Boolean)iris.getMethod("isFallback").invoke(null));
                    var manager=iris.getMethod("getPipelineManager").invoke(null);
                    var pipeline=(java.util.Optional<?>)manager.getClass().getMethod("getPipeline").invoke(manager);
                    String type=pipeline.map(p->p.getClass().getName()).orElse("none");
                    result.addProperty("pipeline",type);
                    if(!enabled && type.equals("net.irisshaders.iris.pipeline.IrisRenderingPipeline"))
                        throw new IllegalStateException("Oculus pipeline still active after disabling shaders");
                    GeneratedShaderLightPresentation.prepareFrame();
                }catch(ReflectiveOperationException unavailable){throw new IllegalStateException("Oculus shader comparison unavailable",unavailable);}
            }
            case "shader_presentation" -> {
                requirePlayer(client);
                GeneratedShaderLightPresentation.enabled(params.get("enabled").getAsBoolean());
                result.add("presentation",new com.google.gson.Gson().toJsonTree(GeneratedShaderLightPresentation.status()));
            }
            case "generated_sampling_diagnostics" -> {return ClientGeneratedLight.samplingDiagnostics();}
            case "render_frame_status" -> { return GeneratedFrameDiagnostics.status(); }
            case "shader_status" -> {
                result.add("fogAdapter",new com.google.gson.Gson().toJsonTree(GeneratedIterationFogAdapter.status()));
                try {
                    var iris=Class.forName("net.irisshaders.iris.Iris");
                    var config=iris.getMethod("getIrisConfig").invoke(null);
                    result.addProperty("present",true);
                    result.addProperty("enabled",(Boolean)config.getClass().getMethod("areShadersEnabled").invoke(config));
                    result.addProperty("pack",String.valueOf(iris.getMethod("getCurrentPackName").invoke(null)));
                    result.addProperty("fallback",(Boolean)iris.getMethod("isFallback").invoke(null));
                    var error=(java.util.Optional<?>)iris.getMethod("getStoredError").invoke(null);
                    result.addProperty("error",error.map(Object::toString).orElse(""));
                    var manager=iris.getMethod("getPipelineManager").invoke(null);
                    var pipeline=(java.util.Optional<?>)manager.getClass().getMethod("getPipeline").invoke(manager);
                    result.addProperty("pipeline",pipeline.map(p->p.getClass().getName()).orElse("none"));
                }catch(ClassNotFoundException absent){result.addProperty("present",false);}
                catch(ReflectiveOperationException unavailable){throw new IllegalStateException("Oculus public status unavailable",unavailable);}
                return result;
            }
            case "camera_mode" -> {
                requirePlayer(client);
                String mode=params.get("mode").getAsString().toUpperCase(java.util.Locale.ROOT);
                var type=switch(mode) {
                    case "FRONT" -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
                    case "BACK" -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
                    case "FIRST_PERSON" -> net.minecraft.client.CameraType.FIRST_PERSON;
                    default -> throw new IllegalArgumentException("Camera mode must be FRONT, BACK or FIRST_PERSON");
                };
                client.options.setCameraType(type);
                result.addProperty("cameraType",client.options.getCameraType().name());
            }
            case "entity_light_probe" -> {
                requirePlayer(client);
                result.addProperty("cameraType",client.options.getCameraType().name());
                var samples=new JsonArray();
                for(float partialTick:new float[]{0,0.5f,1}) {
                    var probe=client.player.getLightProbePosition(partialTick);
                    var pos=BlockPos.containing(probe);
                    var sample=new JsonObject();sample.addProperty("partialTick",partialTick);
                    var position=new JsonArray();position.add(probe.x);position.add(probe.y);position.add(probe.z);
                    sample.add("probePosition",position);
                    var blockPosition=new JsonArray();blockPosition.add(pos.getX());blockPosition.add(pos.getY());blockPosition.add(pos.getZ());
                    sample.add("blockPosition",blockPosition);
                    var generated=ClientGeneratedLight.packedLight(pos.getX(),pos.getY(),pos.getZ());
                    sample.addProperty("generatedAvailable",generated.isPresent());
                    if(generated.isPresent()) {
                        int packed=generated.getAsInt();sample.addProperty("generatedPacked",packed);
                        sample.addProperty("generatedSky",(packed>>>20)&15);sample.addProperty("generatedBlock",(packed>>>4)&15);
                    }
                    try {
                        int packed=client.getEntityRenderDispatcher().getPackedLightCoords(client.player,partialTick);
                        sample.addProperty("rendererAvailable",true);sample.addProperty("rendererPacked",packed);
                        sample.addProperty("rendererSky",(packed>>>20)&15);sample.addProperty("rendererBlock",(packed>>>4)&15);
                    } catch(dev.madeinabyss.forge.cube.CubeBlockLightAccess.UnavailableLightException unavailable) {
                        sample.addProperty("rendererAvailable",false);sample.addProperty("rendererError",unavailable.toString());
                    }
                    samples.add(sample);
                }
                result.add("samples",samples);result.add("light",ClientGeneratedLight.status());return result;
            }
            case "generated_light_query" -> {
                var samples=new JsonArray();
                var positions=params.getAsJsonArray("positions");
                if(positions.size()>32)throw new IllegalArgumentException("At most32 light samples");
                for(var item:positions){var xyz=item.getAsJsonArray();int x=xyz.get(0).getAsInt(),y=xyz.get(1).getAsInt(),z=xyz.get(2).getAsInt();
                    var sample=new JsonObject();sample.add("position",xyz.deepCopy());
                    var packed=ClientGeneratedLight.packedLight(x,y,z);sample.addProperty("available",packed.isPresent());
                    if(packed.isPresent()){sample.addProperty("sky",(packed.getAsInt()>>>20)&15);sample.addProperty("block",(packed.getAsInt()>>>4)&15);}
                    var source=ClientGeneratedLight.source();if(source!=null){sample.addProperty("directSky",source.directSkyFast(x,y,z));sample.addProperty("state",net.minecraft.world.level.block.Block.stateById(source.stateIdFast(x,y,z)).toString());}
                    samples.add(sample);
                }
                result.add("samples",samples);result.add("light",ClientGeneratedLight.status());return result;
            }
            case "generated_source_metrics" -> {
                var source=ClientGeneratedLight.source();
                result.addProperty("sourceAvailable",source!=null);
                if(source!=null){
                    result.addProperty("sourceInstance",Integer.toUnsignedString(System.identityHashCode(source)));
                    result.addProperty("hasIslandSurface",source.hasIslandSurface());
                    result.add("islandSurface",source.islandStatus());
                    result.addProperty("deepAirCertificateEnabled",source.deepAirCertificateEnabled());
                    result.addProperty("deepAirCertificateHits",source.deepAirCertificateHits());
                    result.addProperty("deepAirCellCertificateEnabled",source.deepAirCellCertificateEnabled());
                    result.addProperty("deepAirCellCertificateHits",source.deepAirCellCertificateHits());
                    result.addProperty("deepAirCellCertificateResidentBytes",source.deepAirCellCertificateResidentBytes());
                    result.addProperty("deepAirCellCertificateConstructionNanos",source.deepAirCellCertificateConstructionNanos());
                    result.addProperty("deepAirCellCertificateAdmittedCells",source.deepAirCellCertificateAdmittedCells());
                    result.addProperty("derivedGeometryAirCertificateEnabled",source.derivedGeometryAirCertificateEnabled());
                    result.addProperty("derivedGeometryAirCertificateHits",source.derivedGeometryAirCertificateHits());
                    result.addProperty("caveOpacityProjectionEnabled",source.caveOpacityProjectionEnabled());
                    result.addProperty("caveOpacityProjectionMode",source.caveOpacityProjectionMode());
                    result.addProperty("caveOpacityProjectionCoreSamples",source.caveOpacityProjectionCoreSamples());
                    result.addProperty("caveOpacityProjectionHits",source.caveOpacityProjectionHits());
                    result.addProperty("caveOpacityProjectionRockHits",source.caveOpacityProjectionRockHits());
                    result.addProperty("caveOpacityProjectionSoilHits",source.caveOpacityProjectionSoilHits());
                    result.addProperty("caveOpacityProjectionGrassHits",source.caveOpacityProjectionGrassHits());
                    result.addProperty("caveOpacityProjectionContinued",source.caveOpacityProjectionContinued());
                    result.addProperty("caveOpacityProjectionRefusals",source.caveOpacityProjectionRefusals());
                    result.addProperty("islandAirCertificateEnabled",source.islandAirCertificateEnabled());
                    result.addProperty("islandAirCertificateHits",source.islandAirCertificateHits());
                }
                return result;
            }
            case "deep_light_render" -> {
                requirePlayer(client);
                DeepCubeRenderer.blockLightMode(params.get("enabled").getAsBoolean());
                return DeepCubeRenderer.status();
            }
            case "deep_runtime_probe" -> {requirePlayer(client);return ClientDeepRuntime.begin(client);}
            case "deep_generated_probe" -> {requirePlayer(client);DeepCubeRenderer.blockLightMode(false);return ClientDeepRuntime.beginGenerated(client,params.get("profileId").getAsString());}
            case "dh_compat_status" -> {return dev.madeinabyss.forge.probe.client.compat.DistantHorizonsCompat.status(params);}
            case "dh_generated_preview" -> {return dev.madeinabyss.forge.probe.client.compat.DistantHorizonsCompat.control(client,params);}
            case "deep_native_light_begin" -> {return ClientNativeLightProbe.begin(client, params);}
            case "deep_native_light_status" -> {return ClientNativeLightProbe.status(client);}
            case "deep_native_light_end" -> {return ClientNativeLightProbe.end(client);}
            case "deep_vertical_probe" -> {
                requirePlayer(client);
                String direction = params.has("direction") ? params.get("direction").getAsString() : "descent";
                Integer startY = params.has("startY") ? params.get("startY").getAsInt() : null;
                boolean flying = params.has("flying") && params.get("flying").getAsBoolean();
                return ClientDeepRuntime.begin(client,true,dev.madeinabyss.forge.probe.deep.DeepProbeStart.vertical(direction,startY,flying));
            }
            case "deep_movement_packet" -> {
                requirePlayer(client);
                if(DeepCubeDomain.lookup(client.level).isEmpty()) throw new IllegalStateException("Movement diagnostic requires an attached dev fixture");
                var xyz=params.getAsJsonArray("position");
                double x=xyz.get(0).getAsDouble(),y=xyz.get(1).getAsDouble(),z=xyz.get(2).getAsDouble();
                if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Finite diagnostic position required");
                client.player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(x,y,z,false));
                result.addProperty("queued",true); // Server rejection and position must be observed separately.
            }
            case "deep_runtime_status" -> {return ClientDeepRuntime.status();}
            case "deep_visual_status" -> {return ClientDeepVisual.status();}
            case "deep_visual_light" -> {
                requirePlayer(client);
                var positions=params.getAsJsonArray("positions");
                if(positions.size()>64) throw new IllegalArgumentException("At most 64 visual light positions");
                var physical=dev.madeinabyss.forge.probe.deep.DeepCubeDomain.space(client.level).orElseThrow();
                var light=ClientDeepVisual.light(client.level,physical);
                var observations=new JsonArray();
                for(var value:positions) {
                    var xyz=value.getAsJsonArray();
                    var observation=new JsonObject();observation.add("position",xyz.deepCopy());
                    try {
                        observation.addProperty("blockLight",light.value(client.level,new BlockPos(xyz.get(0).getAsInt(),xyz.get(1).getAsInt(),xyz.get(2).getAsInt())));
                        observation.addProperty("blockLightKnown",true);
                    } catch(RuntimeException unknown) {
                        observation.addProperty("blockLightKnown",false);
                        observation.addProperty("blockLightError",unknown.toString());
                    }
                    observations.add(observation);
                }
                result.add("blocks",observations);
                result.addProperty("lightRevision",light.revision(client.level));
                result.addProperty("computedCubes",light.computedCubes());
                result.addProperty("nativeHaloCubes",light.nativeHaloCubes());
                result.addProperty("recomputations",light.recomputations());
            }
            case "deep_visual_blocks" -> {
                requirePlayer(client);var positions=params.getAsJsonArray("positions");
                if(positions.size()>64) throw new IllegalArgumentException("At most 64 visual positions");
                var observations=new JsonArray();
                for(var value:positions) {
                    var xyz=value.getAsJsonArray();var coordinate=new dev.madeinabyss.kernel.BlockCoordinate(xyz.get(0).getAsInt(),xyz.get(1).getAsInt(),xyz.get(2).getAsInt());
                    var state=ClientDeepVisual.space(client.level).map(s->s.block(coordinate)).orElse(java.util.OptionalInt.empty());
                    var observation=new JsonObject();observation.add("position",xyz.deepCopy());observation.addProperty("loaded",state.isPresent());
                    if(state.isPresent()) observation.addProperty("block",BuiltInRegistries.BLOCK.getKey(net.minecraft.world.level.block.Block.stateById(state.getAsInt()).getBlock()).toString());
                    observations.add(observation);
                }
                result.add("blocks",observations);
            }
            case "deep_runtime_query" -> {
                requirePlayer(client);var xyz=params.getAsJsonArray("position");
                return ClientDeepRuntime.query(new BlockPos(xyz.get(0).getAsInt(),xyz.get(1).getAsInt(),xyz.get(2).getAsInt()));
            }
            case "deep_runtime_query_status" -> {return ClientDeepRuntime.queryStatus();}
            case "deep_fluid_control" -> {requirePlayer(client);return ClientDeepRuntime.fluid(params.get("operation").getAsString());}
            case "deep_chest_control" -> {requirePlayer(client);return ClientDeepRuntime.chest(params.get("operation").getAsString());}
            case "deep_item_control" -> {requirePlayer(client);return ClientDeepRuntime.item(params.get("operation").getAsString());}
            case "deep_item_status" -> {return ClientDeepItems.status(client);}
            case "deep_chest_render_status" -> {return ClientDeepChest.status();}
            case "deep_chest_render_fault" -> {return ClientDeepChest.diagnosticFault(params.get("enabled").getAsBoolean());}
            case "container_status" -> {
                requirePlayer(client);var menu=client.player.containerMenu;
                result.addProperty("containerId",menu.containerId);result.addProperty("stateId",menu.getStateId());result.addProperty("chest",menu instanceof net.minecraft.world.inventory.ChestMenu);
                result.addProperty("screen",client.screen==null?"none":client.screen.getClass().getSimpleName());
                result.add("carried",stackStatus(menu.getCarried()));var slots=new JsonArray();
                for(int i=0;i<menu.slots.size();i++){var slot=stackStatus(menu.slots.get(i).getItem());slot.addProperty("slot",i);slots.add(slot);}result.add("slots",slots);
                result.addProperty("selected",client.player.getInventory().selected);
            }
            case "container_click" -> {
                requirePlayer(client);var menu=client.player.containerMenu;
                if(!(menu instanceof net.minecraft.world.inventory.ChestMenu))throw new IllegalStateException("native chest menu required");
                int slot=params.get("slot").getAsInt(),button=params.has("button")?params.get("button").getAsInt():0;
                var type=params.has("type")?net.minecraft.world.inventory.ClickType.valueOf(params.get("type").getAsString()):net.minecraft.world.inventory.ClickType.PICKUP;
                if(slot<0||slot>=menu.slots.size()||button<0||button>1||!(type==net.minecraft.world.inventory.ClickType.PICKUP||type==net.minecraft.world.inventory.ClickType.QUICK_MOVE))throw new IllegalArgumentException("bounded chest click");
                client.gameMode.handleInventoryMouseClick(menu.containerId,slot,button,type,client.player);result.addProperty("requested",true);
            }
            case "close_container" -> {requirePlayer(client);client.player.closeContainer();result.addProperty("requested",true);}
            case "deep_click" -> {
                requirePlayer(client);var xyz=params.getAsJsonArray("position");
                var pos=new BlockPos(xyz.get(0).getAsInt(),xyz.get(1).getAsInt(),xyz.get(2).getAsInt());
                var face=net.minecraft.core.Direction.valueOf(params.get("face").getAsString());
                if(params.get("place").getAsBoolean()) {
                    var point=net.minecraft.world.phys.Vec3.atCenterOf(pos).add(face.getStepX()*.5,face.getStepY()*.5,face.getStepZ()*.5);
                    var hit=new net.minecraft.world.phys.BlockHitResult(point,face,pos,false);
                    if(params.has("nativePick")&&params.get("nativePick").getAsBoolean()) {
                        var picked=client.player.pick(client.gameMode.getPickRange(),1,false);
                        if(!(picked instanceof net.minecraft.world.phys.BlockHitResult nativeHit)
                                || nativeHit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK
                                || !nativeHit.getBlockPos().equals(pos)||nativeHit.getDirection()!=face)
                            throw new IllegalStateException("Native cursor did not hit requested block face: "+picked);
                        hit=nativeHit;var location=new JsonArray();location.add(hit.getLocation().x);location.add(hit.getLocation().y);location.add(hit.getLocation().z);result.add("nativeHit",location);
                    }
                    result.addProperty("result",client.gameMode.useItemOn(client.player,net.minecraft.world.InteractionHand.MAIN_HAND,hit).toString());
                } else result.addProperty("started",client.gameMode.startDestroyBlock(pos,face));
            }
            case "debug_overlay" -> {
                client.options.renderDebug = params.get("enabled").getAsBoolean();
                result.addProperty("enabled", client.options.renderDebug);
            }
            case "close_screen" -> {
                requirePlayer(client);
                client.options.pauseOnLostFocus = false;
                client.setScreen(null);
                result.addProperty("closed", true);
            }
            case "confirm_screen" -> {
                if(client.screen==null||client.screen.getClass()!=net.minecraft.client.gui.screens.ConfirmScreen.class)
                    throw new IllegalStateException("An exact native ConfirmScreen is required");
                if(params.has("expectedTitleKey")){
                    if(!(client.screen.getTitle().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t)
                            ||!t.getKey().equals(params.get("expectedTitleKey").getAsString()))throw new IllegalStateException("Unexpected confirmation title");
                }
                // 1.20.1 ConfirmScreen.addButtons registers its affirmative callback first.
                var button=client.screen.children().stream().filter(e->e instanceof net.minecraft.client.gui.components.Button)
                    .map(e->(net.minecraft.client.gui.components.Button)e).findFirst().orElseThrow(()->new IllegalStateException("Confirmation buttons not initialized"));
                if(!button.active)throw new IllegalStateException("Confirmation button is still delayed");
                button.onPress();result.addProperty("confirmed",true);
            }
            case "blocks" -> {
                requirePlayer(client);
                JsonArray positions = params.getAsJsonArray("positions");
                if (positions.size() > 64) throw new IllegalArgumentException("At most 64 block queries per request");
                JsonArray blocks = new JsonArray();
                for (var value : positions) {
                    JsonArray xyz = value.getAsJsonArray();
                    BlockPos position = new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
                    JsonObject block = new JsonObject();
                    block.add("position", xyz);
                    // ClientLevel.hasChunk always returns true in 1.20.1, even outside its cache.
                    boolean loaded = client.level.getChunkSource().getChunk(position.getX() >> 4,
                        position.getZ() >> 4, ChunkStatus.FULL, false) != null;
                    boolean deep = DeepCubeDomain.belowVanilla(client.level, position);
                    if (deep) loaded = DeepCubeDomain.space(client.level).orElseThrow()
                            .isLoaded(CubeKey.fromBlock(new BlockCoordinate(position.getX(), position.getY(), position.getZ())));
                    block.addProperty("loaded", loaded);
                    if (loaded) {
                        var actual=client.level.getBlockState(position);
                        block.addProperty("block", BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString());
                        block.addProperty("fluidType",BuiltInRegistries.FLUID.getKey(actual.getFluidState().getType()).toString());
                        if(actual.hasProperty(net.minecraft.world.level.block.LiquidBlock.LEVEL)) block.addProperty("fluidLevel",actual.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL));
                        if (deep) {
                            block.addProperty("lightingImplemented", false); // Full block+SKY/shader contract is still open.
                            boolean lightEnabled = DeepCubeDomain.blockLightEnabled(client.level);
                            block.addProperty("blockLightImplemented", lightEnabled);
                            block.addProperty("skyLightImplemented", false);
                            if (!lightEnabled) block.addProperty("blockLightKnown", false);
                            else try {
                                block.addProperty("blockLight", client.level.getBrightness(LightLayer.BLOCK, position));
                                block.addProperty("blockLightKnown", true);
                            } catch (RuntimeException unavailable) {
                                block.addProperty("blockLightKnown", false);
                                block.addProperty("blockLightError", unavailable.toString());
                            }
                        }
                        else {
                            block.addProperty("skyLight", client.level.getBrightness(LightLayer.SKY, position));
                            block.addProperty("blockLight", client.level.getBrightness(LightLayer.BLOCK, position));
                        }
                    }
                    blocks.add(block);
                }
                result.add("blocks", blocks);
            }
            case "screenshot" -> {
                String filename = "mia-probe-" + UUID.randomUUID() + ".png";
                Screenshot.grab(client.gameDirectory, filename, client.getMainRenderTarget(), message -> LogUtils.getLogger().info("MIA_SCREENSHOT {}", message.getString()));
                result.addProperty("path", new java.io.File(new java.io.File(client.gameDirectory, "screenshots"), filename).getAbsolutePath());
                result.addProperty("queued", true);
            }
            case "open_world" -> {
                if (client.player != null) throw new IllegalStateException("Disconnect first");
                String name = params.get("name").getAsString();
                if (name.contains("/") || name.contains("\\") || !client.getLevelSource().levelExists(name)) throw new IllegalArgumentException("Unknown local probe world");
                client.createWorldOpenFlows().loadLevel(new TitleScreen(), name);
                result.addProperty("queued", true);
            }
            case "connect_probe_server" -> {
                if (client.player != null) throw new IllegalStateException("Disconnect first");
                int port = params.has("port") ? params.get("port").getAsInt() : 25576;
                if (port != 25576 && port != 25577) throw new IllegalArgumentException("Only isolated probe ports are allowed");
                String address = "127.0.0.1:" + port;
                ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address), new ServerData("MIA Probe", address, false), false);
                result.addProperty("queued", true);
            }
            case "disconnect" -> {
                release(client);
                ClientCubeProbe.disconnect();
                if (client.level != null) client.level.disconnect();
                client.clearLevel(new TitleScreen());
                result.addProperty("disconnected", true);
            }
            case "quit" -> { release(client); client.stop(); result.addProperty("requested", true); }
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        }
        return result;
    }

    private static void requirePlayer(Minecraft client) {
        if (client.player == null) throw new IllegalStateException("No player connected");
    }

    private static final class AutomatedCameraHoldScreen extends net.minecraft.client.gui.screens.Screen {
        AutomatedCameraHoldScreen(){super(net.minecraft.network.chat.Component.literal("Automated check"));}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void render(net.minecraft.client.gui.GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
            graphics.drawCenteredString(font,"Automated camera check — press Esc to resume control",width/2,8,0xFFE0A0);
        }
    }

    private static void release(Minecraft client) {
        movementTicks = 0;
        client.options.keyUp.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keyAttack.setDown(false);
    }

    private static void respond(String name, JsonObject result, Exception error) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", error == null);
        if (error == null) response.add("result", result);
        else response.addProperty("error", error.toString());
        try { atomicWrite(root.resolve("responses").resolve(name), response); }
        catch (Exception failure) { LogUtils.getLogger().error("Cannot write client probe response", failure); }
    }

    private static void atomicWrite(Path target, JsonObject value) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(value));
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
