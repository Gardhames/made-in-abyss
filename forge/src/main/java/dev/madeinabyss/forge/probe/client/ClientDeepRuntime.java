package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.madeinabyss.forge.cube.DeepCreativeEdits;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepRuntimeNetwork;
import dev.madeinabyss.forge.probe.deep.DeepProbeStart;
import dev.madeinabyss.forge.probe.deep.DeepChestClientMirror;
import dev.madeinabyss.forge.probe.deep.DeepChestPackets;
import dev.madeinabyss.forge.probe.deep.DeepVisualNetwork;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.concurrent.OrderedOwnerPreparation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Main-thread authoritative mirror. There is no local optimistic block mutation or vanilla packed prediction. */
public final class ClientDeepRuntime {
    private static UUID session;
    private static boolean blockLightEnabled;
    private static boolean generatedStartup, generatedAdmitted;
    private static java.lang.ref.WeakReference<net.minecraft.world.level.Level> generatedLevel = new java.lang.ref.WeakReference<>(null);
    private static long revision=-1,sequence,pending,cooldownUntil;
    private static JsonObject report=new JsonObject(),ack=new JsonObject(),query=new JsonObject();
    private static UUID pendingQuery;
    private static DeepChestClientMirror.Cache chestCache;
    private static final java.util.ArrayDeque<DeepRuntimeNetwork.Mining> miningQueue=new java.util.ArrayDeque<>();
    private static long miningSequence,miningOperation,miningInFlight,miningStops,miningStarts,miningAborts;
    private static int miningExpected,lastNativeSequence;
    private static boolean miningStopPending;
    private static BlockPos miningTarget;
    private static JsonObject miningAck=new JsonObject();
    /** Accesses only existing native mining fields; there is no alternate progress clock. */
    public interface MiningClientAccess {void mia$resetMining();JsonObject mia$miningState();}
    private ClientDeepRuntime() {}
    public static JsonObject begin(Minecraft client) {
        return begin(client,false);
    }
    public static JsonObject begin(Minecraft client,boolean vertical) {
        return begin(client,vertical,DeepProbeStart.vertical("descent",null,false));
    }
    public static JsonObject begin(Minecraft client,boolean vertical,DeepProbeStart start) {
        return beginSession(client,vertical?Startup.SHAFT:Startup.CORRIDOR,start,null);
    }
    private enum Startup { CORRIDOR, SHAFT, GENERATED }
    public static JsonObject beginGenerated(Minecraft client,String profileId) {
        if(!profileId.matches("[a-z0-9][a-z0-9_-]{0,63}"))throw new IllegalArgumentException("Invalid generated profile ID");
        return beginSession(client,Startup.GENERATED,null,profileId);
    }
    private static JsonObject beginSession(Minecraft client,Startup startup,DeepProbeStart start,String profileId) {
        return beginSession(client,startup,start,profileId,null,null);
    }
    public static JsonObject beginWorld(Minecraft client,dev.madeinabyss.forge.probe.deep.WorldSessionNetwork.Offer offer) {
        if(client.level==null||!client.level.dimension().location().toString().equals(offer.dimension()))throw new IllegalArgumentException("Native world admission dimension mismatch");
        var manifest=JsonParser.parseString(offer.manifest()).getAsJsonObject();
        var biomes=client.level.registryAccess().registryOrThrow(Registries.BIOME);
        var profile=dev.madeinabyss.forge.worldgen.SharedPreviewProfiles.forSampling(manifest,manifest.get("profileId").getAsString(),new net.minecraft.world.level.biome.FixedBiomeSource(biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)));
        if(!profile.fingerprint().equals(offer.fingerprint()))throw new IllegalArgumentException("Native world recipe fingerprint mismatch");
        return beginSession(client,Startup.GENERATED,null,profile.id(),offer.session(),profile);
    }
    private static JsonObject beginSession(Minecraft client,Startup startup,DeepProbeStart start,String profileId,UUID worldToken,dev.madeinabyss.forge.worldgen.GeneratedPreviewProfile worldProfile) {
        if(client.player==null || client.getConnection()==null) throw new IllegalStateException("No player connection");
        if(session!=null) throw new IllegalStateException("Deep runtime already active; return or disconnect first");
        ClientWindowPreparation.clear();
        session=worldToken==null?UUID.randomUUID():worldToken;revision=-1;sequence=0;pending=0;cooldownUntil=0;
        generatedStartup=startup==Startup.GENERATED;generatedAdmitted=false;
        generatedLevel=new java.lang.ref.WeakReference<>(client.level);
        resetMiningTransport();miningSequence=0;miningStarts=0;miningStops=0;miningAborts=0;miningAck=new JsonObject();
        blockLightEnabled = true;
        ClientDeepLight.begin(session,blockLightEnabled);
        report=new JsonObject();report.addProperty("session",session.toString());report.addProperty("completed",false);
        report.addProperty("fixture",startup==Startup.GENERATED?"generated":startup==Startup.SHAFT?"vertical":"corridor");
        report.addProperty("blockLightImplemented", blockLightEnabled);
        if(start!=null){report.addProperty("startY",start.startY()); report.addProperty("flying",start.flying()); report.addProperty("flightSpeed",start.flightSpeed());}
        DeepVisualNetwork.receiver(ClientDeepVisual::receive);
        if(startup!=Startup.CORRIDOR) ClientDeepVisual.begin(session);else ClientDeepVisual.clear();
        DeepRuntimeNetwork.receivers(ClientDeepRuntime::receive,packet->ClientWindowPreparation.after(()->receiveAck(packet)),packet->ClientWindowPreparation.after(()->receiveState(packet)));
        DeepRuntimeNetwork.lidReceiver(packet->ClientWindowPreparation.after(()->receiveLid(packet)));
        DeepRuntimeNetwork.itemReceiver(packet->ClientWindowPreparation.after(()->receiveItem(packet)));
        DeepRuntimeNetwork.miningReceiver(packet->ClientWindowPreparation.after(()->receiveMiningAck(packet)));
        try {
            if(worldProfile!=null){ClientGeneratedLight.begin(session,worldProfile,client.level);report.addProperty("persistentWorld",true);}
            else if(startup==Startup.GENERATED){ClientGeneratedLight.begin(session,profileId,client.level);DeepRuntimeNetwork.generatedSetup(client.getConnection().getConnection(),session,profileId);}
            else DeepRuntimeNetwork.setup(client.getConnection().getConnection(),session,startup==Startup.SHAFT,start);
        }catch(RuntimeException failed){disconnect();throw failed;}
        return report.deepCopy();
    }
    public static JsonObject status() {var r=report.deepCopy();r.add("ack",ack.deepCopy());r.addProperty("pendingEdit",pending);r.add("lightDependencies",ClientDeepLight.status());r.add("generatedLight",ClientGeneratedLight.status());r.add("worldBootstrap",ClientWorldSession.status());r.add("mining",miningStatus());r.add("windowPreparation",ClientWindowPreparation.status());return r;}
    /** Render-thread predicate: no report copies or derived-light allocations each frame. */
    public static boolean generatedSession(net.minecraft.world.level.Level level) {
        return level!=null && level==generatedLevel.get() && session!=null && generatedStartup;
    }
    public static Object generatedSessionIdentity(net.minecraft.world.level.Level level) {
        return generatedSession(level) ? session : null;
    }
    /** Owner-thread DH guard; do not construct diagnostic JSON on every render callback. */
    public static boolean matchesGeneratedProfile(net.minecraft.world.level.Level level,UUID token,String fingerprint) {
        if(!generatedSession(level) || token==null || !token.equals(session) || !report.has("generatedProfile"))return false;
        var admitted=report.getAsJsonObject("generatedProfile");
        return admitted.has("fingerprint") && fingerprint.equals(admitted.get("fingerprint").getAsString());
    }
    public static boolean observationFailed() {return report.has("error");}
    public static boolean generatedAccepted(net.minecraft.world.level.Level level) {
        return generatedSession(level) && revision>=0
                && generatedAdmitted && !report.has("error") && DeepCubeDomain.generated(level);
    }
    /** Window, native teleport, admission reply and interpolated camera need not arrive in one frame. */
    public static void prepareGeneratedCamera(Minecraft client, net.minecraft.client.Camera camera) {
        if(!generatedStartup || session==null || client.level==null || client.player==null
                || generatedLevel.get()!=client.level) return;
        var attached=DeepCubeDomain.lookup(client.level).orElse(null);if(attached==null) return;
        var position=camera.getPosition();
        var body=client.player.getBoundingBox().expandTowards(client.player.getDeltaMovement());
        var cameraPoint=new net.minecraft.world.phys.AABB(position.x,position.y,position.z,position.x,position.y,position.z);
        boolean fullHalo=DeepCubeDomain.knownGeneratedHalo(client.level,attached.space(),body)
                && DeepCubeDomain.knownGeneratedHalo(client.level,attached.space(),cameraPoint);
        // Residency lag may pause movement at its conservative 8m halo. Retain generated rendering
        // while the actual body/camera reads remain known; don't flash the old host terrain on a seam.
        boolean currentReads=attached.generated() && DeepCubeDomain.knownGeneratedHalo(client.level,attached.space(),body,2)
                && DeepCubeDomain.knownGeneratedHalo(client.level,attached.space(),cameraPoint,2);
        boolean ready=generatedAdmitted && (fullHalo || currentReads);
        if(ready!=attached.generated()) {
            if(ready) DeepCubeDomain.attachGenerated(client.level,attached.space(),blockLightEnabled);
            else DeepCubeDomain.attach(client.level,attached.space(),blockLightEnabled);
            ClientDeepLight.geometry(client.level,revision,attached.space().residentKeys());
        }
        report.addProperty("generatedRouteActive",ready);
    }
    public static JsonObject query(BlockPos pos) {
        pendingQuery=UUID.randomUUID();query=new JsonObject();query.addProperty("request",pendingQuery.toString());query.addProperty("completed",false);
        DeepRuntimeNetwork.query(pendingQuery,pos);return query.deepCopy();
    }
    public static JsonObject queryStatus() {return query.deepCopy();}
    public static JsonObject fluid(String action) {
        if(session==null) throw new IllegalStateException("Begin the deep corridor fixture first");
        if(!java.util.List.of("activate","pause","resume","seed","remove").contains(action))
            throw new IllegalArgumentException("Unsupported fluid control");
        pendingQuery=UUID.randomUUID();query=new JsonObject();query.addProperty("request",pendingQuery.toString());query.addProperty("completed",false);
        DeepRuntimeNetwork.fluid(session,pendingQuery,action);return query.deepCopy();
    }
    public static JsonObject chest(String action) {
        if(session==null || !java.util.List.of("activate","place","remove","query").contains(action))throw new IllegalStateException("unsupported/inactive chest fixture");
        pendingQuery=UUID.randomUUID();query=new JsonObject();query.addProperty("request",pendingQuery.toString());query.addProperty("completed",false);
        DeepRuntimeNetwork.chest(session,pendingQuery,action);return query.deepCopy();
    }
    public static JsonObject item(String action){
        if(session==null || !java.util.List.of("activate","seed","pause","resume","flush","remove","query").contains(action))throw new IllegalStateException("unsupported/inactive item fixture");
        pendingQuery=UUID.randomUUID();query=new JsonObject();query.addProperty("request",pendingQuery.toString());query.addProperty("completed",false);
        DeepRuntimeNetwork.item(session,pendingQuery,action);return query.deepCopy();
    }
    public static void disconnect() {ClientWindowPreparation.clear();abortMining();flushMining();resetMiningTransport();var level=Minecraft.getInstance().level;if(level!=null){ClientDeepItems.clear(level);DeepChestClientMirror.clear(level);}chestCache=null;ClientDeepChest.clear();session=null;revision=-1;pending=0;generatedStartup=generatedAdmitted=false;generatedLevel=new java.lang.ref.WeakReference<>(null);report=new JsonObject();ack=new JsonObject();query=new JsonObject();pendingQuery=null;ClientDeepVisual.clear();ClientDeepLight.clear();ClientGeneratedLight.clear();}
    private static void receive(DeepRuntimeNetwork.Window window) {
        var current=Minecraft.getInstance().level;var requested=new java.lang.ref.WeakReference<net.minecraft.world.level.Level>(current);
        ClientWindowPreparation.submit(current,GeneratedFrameCosts.Stage.PHYSICAL_APPLY,()->prepare(requested.get(),window));
    }
    private static OrderedOwnerPreparation.Work<ClientWindowFrames.Decoded> prepare(net.minecraft.world.level.Level current,DeepRuntimeNetwork.Window window) {
        if(session==null || !session.equals(window.session()) || window.revision()<=revision || current==null)return null;
        int timing=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.WINDOW_VALIDATE);
        try {
            var biomes=current.registryAccess().registryOrThrow(Registries.BIOME);
            var requested=new java.lang.ref.WeakReference<>(current);long baseRevision=revision;
            return new OrderedOwnerPreparation.Work<>(check->ClientWindowFrames.decode(window.frames(),biomes,null,check),(decoded,error)->{
                var target=requested.get();
                if(session==null || !session.equals(window.session()) || revision!=baseRevision || target==null)return;
                if(error!=null){refuse(error);return;}
                publish(target,window,decoded);
            });
        }catch(RuntimeException error){refuse(error);return null;}
        finally{GeneratedFrameCosts.end(timing);}
    }
    private static void publish(net.minecraft.world.level.Level current,DeepRuntimeNetwork.Window window,ClientWindowFrames.Decoded decoded) {
        try {
            int buildTiming=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.WINDOW_BUILD);
            SparseCubeSpace space;DeepChestClientMirror.Cache nextChests;
            try {
            space=new SparseCubeSpace();
            for(var snapshot:decoded.snapshots())space.load(snapshot);
            if(space.loadedCubeCount()!=27) throw new IllegalArgumentException("Duplicate or incomplete player window");
            if(blockLightEnabled) dev.madeinabyss.kernel.light.ObserverLightWindow.keys(space.residentKeys());
            nextChests=DeepChestClientMirror.prepare(current,space,window.chests(),decoded.entityBlocks());
            }finally{GeneratedFrameCosts.end(buildTiming);}
            int notifyTiming=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.WINDOW_NOTIFY);
            try {
            // stopDestroyBlock reads the OLD target: abort before replacing its known mirror.
            if(miningTarget!=null && space.block(new BlockCoordinate(miningTarget.getX(),miningTarget.getY(),miningTarget.getZ())).isEmpty())abortMining();
            // Publish the whole replacement only after every frame validates. Old residency survives any failed decode.
            // Retain ordinary routing until the actual camera and body both occupy the received halo.
            if(generatedStartup && DeepCubeDomain.generated(current)) DeepCubeDomain.attachGenerated(current,space,blockLightEnabled);
            else DeepCubeDomain.attach(current,space,blockLightEnabled);
            revision=window.revision();
            DeepChestClientMirror.publish(current,nextChests);chestCache=nextChests;
            ClientDeepLight.geometry(current,revision,space.residentKeys());
            if(generatedStartup)ClientGeneratedLight.physical(current,space);
            report.addProperty("completed",true);report.addProperty("revision",revision);report.addProperty("cubeCount",27);
            report.addProperty("packetBytes",decoded.bytes());report.addProperty("clientPid",ProcessHandle.current().pid());report.remove("error");
            report.addProperty("chestCount",chestCache.chests().size());
            ClientWindowPreparation.decoded(decoded);
            }finally{GeneratedFrameCosts.end(notifyTiming);}
        } catch(RuntimeException error) {refuse(error);}
    }
    private static void refuse(RuntimeException error) {
        report.addProperty("error",error.toString());ClientDeepLight.invalidGeometry(error.toString());if(generatedStartup)ClientGeneratedLight.invalidGeometry(error.toString());
    }
    private static void receiveLid(DeepRuntimeNetwork.Lid event) {
        var client=Minecraft.getInstance();
        if(session==null || !session.equals(event.session()) || revision!=event.revision() || client.level==null || chestCache==null)return;
        try{var chest=DeepChestClientMirror.get(client.level,event.position());if(chest!=null && chest.triggerEvent(event.id(),event.value()))ClientDeepChest.lidEvent();}
        catch(RuntimeException unavailable){report.addProperty("chestEventError",unavailable.toString());}
    }
    private static void receiveItem(dev.madeinabyss.forge.probe.deep.DeepItemPackets.Admission event){
        var client=Minecraft.getInstance();
        if(session==null || !session.equals(event.session()) || revision<0 || client.level==null || !client.level.dimension().location().toString().equals(event.dimension()))return;
        try{
            if(event.active()){
                if(!DeepCubeDomain.isDeep(client.level,event.position().y) || !DeepCubeDomain.allowsMove(client.level,new net.minecraft.world.phys.AABB(event.position().x-.125,event.position().y,event.position().z-.125,event.position().x+.125,event.position().y+.25,event.position().z+.125)))throw new IllegalStateException("item admission outside known native physics halo");
                ClientDeepItems.admit(client.level,event.uuid(),event.entityId(),event.session());
            }else ClientDeepItems.release(client.level,event.uuid(),event.entityId(),event.session());
        }catch(RuntimeException refused){report.addProperty("itemAdmissionError",refused.toString());}
    }
    private static void receiveAck(DeepRuntimeNetwork.Ack response) {
        if(session==null || !session.equals(response.session()) || response.sequence()!=pending) return;
        ack=new JsonObject();ack.addProperty("sequence",response.sequence());ack.addProperty("accepted",response.accepted());ack.addProperty("reason",response.reason());
        pending=0;cooldownUntil=System.nanoTime()+250000000L;
    }
    private static void receiveState(DeepRuntimeNetwork.State response) {
        if(session!=null&&response.id().equals(session)) {
            var admission=JsonParser.parseString(response.json()).getAsJsonObject();
            if(admission.has("generatedAccepted")) {
                if(!admission.get("generatedAccepted").getAsBoolean()) {
                    if(admission.get("generatedRollbackComplete").getAsBoolean()) {
                        var level=Minecraft.getInstance().level;if(level!=null)DeepCubeDomain.detach(level);disconnect();
                    }
                    report=admission;report.addProperty("completed",true);report.addProperty("error",admission.get("generatedError").getAsString());
                }else {report.add("generatedProfile",admission);generatedAdmitted=true;}
            }
            return;
        }
        if(!response.id().equals(pendingQuery)) return;
        query=JsonParser.parseString(response.json()).getAsJsonObject();query.addProperty("request",response.id().toString());query.addProperty("completed",true);
    }
    public static JsonObject miningStatus() {
        var r=new JsonObject();r.addProperty("operation",miningOperation);r.addProperty("inFlight",miningInFlight);r.addProperty("queued",miningQueue.size());r.addProperty("stopPending",miningStopPending);
        r.addProperty("starts",miningStarts);r.addProperty("stops",miningStops);r.addProperty("aborts",miningAborts);r.addProperty("lastNativeSequence",lastNativeSequence);r.add("ack",miningAck.deepCopy());
        var mode=Minecraft.getInstance().gameMode;if(mode instanceof MiningClientAccess access)r.add("native",access.mia$miningState());return r;
    }
    public static boolean miningCoordinate(BlockPos pos) {var level=Minecraft.getInstance().level;return Boolean.getBoolean("mia.cube.playerProbe")&&level!=null&&pos.getY()<level.getMinBuildHeight();}
    public static boolean admitMining(BlockPos pos) {
        var c=Minecraft.getInstance();
        if(session==null||c.level==null||c.player==null||c.gameMode==null||c.gameMode.getPlayerMode()!=net.minecraft.world.level.GameType.SURVIVAL||!c.player.isAlive()) {abortMining();return false;}
        if(miningStopPending)return false;
        if(miningQueue.size()>=14||pending!=0||!DeepCubeDomain.isDeep(c.level,pos)||!DeepCubeDomain.allowsMove(c.level,c.player.getBoundingBox())) {abortMining();return false;}
        var space=DeepCubeDomain.space(c.level);if(space.isEmpty()){abortMining();return false;}
        var state=space.get().block(new BlockCoordinate(pos.getX(),pos.getY(),pos.getZ()));
        var tool=c.player.getMainHandItem();
        if(state.isEmpty()||!Block.stateById(state.getAsInt()).is(net.minecraft.world.level.block.Blocks.STONE)||!tool.is(net.minecraft.world.item.Items.DIAMOND_PICKAXE)||tool.isEnchanted()||tool.getDamageValue()>=tool.getMaxDamage()-1) {abortMining();return false;}
        return true;
    }
    /** Called at native packet send, before packed BlockPos serialization. Always consumes owned deep actions. */
    public static void captureMining(ServerboundPlayerActionPacket packet) {
        var c=Minecraft.getInstance();lastNativeSequence=packet.getSequence();
        if(session==null||c.level==null||c.player==null)return;
        var action=packet.getAction();long request=++miningSequence;
        if(action==ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            if(!admitMining(packet.getPos())) {resetNativeMining();return;}
            miningOperation=request;miningTarget=packet.getPos().immutable();miningExpected=Block.getId(c.level.getBlockState(miningTarget));miningStarts++;
        }else {
            if(miningOperation==0||miningTarget==null||!miningTarget.equals(packet.getPos()))return;
            if(action==ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK){miningStopPending=true;miningStops++;}else miningAborts++;
        }
        if(miningQueue.size()>=16)throw new IllegalStateException("reserved mining transport slots exhausted");
        miningQueue.addLast(new DeepRuntimeNetwork.Mining(session,request,miningOperation,c.level.dimension().location().toString(),packet.getPos().immutable(),packet.getDirection(),action,packet.getSequence(),miningExpected));
        if(action==ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK){miningOperation=0;miningTarget=null;miningStopPending=false;}
        pumpMining();
    }
    private static void pumpMining() {if(miningInFlight==0&&!miningQueue.isEmpty()&&Minecraft.getInstance().getConnection()!=null){miningInFlight=miningQueue.peekFirst().sequence();DeepRuntimeNetwork.mining(miningQueue.peekFirst());}}
    private static void flushMining() {
        if(Minecraft.getInstance().getConnection()!=null)for(var request:miningQueue)if(request.sequence()!=miningInFlight)DeepRuntimeNetwork.mining(request);
    }
    private static void receiveMiningAck(DeepRuntimeNetwork.MiningAck response) {
        if(session==null||!session.equals(response.session()))return;
        if(response.sequence()==miningInFlight){miningQueue.removeFirst();miningInFlight=0;}
        miningAck=new JsonObject();miningAck.addProperty("sequence",response.sequence());miningAck.addProperty("operation",response.operation());miningAck.addProperty("accepted",response.accepted());miningAck.addProperty("terminal",response.terminal());miningAck.addProperty("reason",response.reason());
        if(response.operation()==miningOperation && (response.terminal()||!response.accepted())){resetNativeMining();miningTarget=null;miningOperation=0;miningStopPending=false;}
        pumpMining();
    }
    private static void resetNativeMining(){if(Minecraft.getInstance().gameMode instanceof MiningClientAccess access)access.mia$resetMining();}
    private static void resetMiningTransport(){resetNativeMining();miningQueue.clear();miningInFlight=0;miningOperation=0;miningTarget=null;miningStopPending=false;}
    public static void abortMining() {
        if(miningTarget==null)return;
        var c=Minecraft.getInstance();var old=miningTarget;
        boolean known=c.level!=null && DeepCubeDomain.space(c.level).map(s->s.block(new BlockCoordinate(old.getX(),old.getY(),old.getZ())).isPresent()).orElse(false);
        if(known&&c.gameMode!=null)c.gameMode.stopDestroyBlock();
        // Native stop is a no-op after STOP; explicitly cancel the outstanding server operation then.
        if(miningTarget!=null)captureMining(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,old,Direction.DOWN));
        resetNativeMining();
    }
    public static void miningTick() {
        if(miningTarget==null)return;
        var c=Minecraft.getInstance();
        if(c.player==null||c.level==null||!c.player.isAlive()||c.gameMode.getPlayerMode()!=net.minecraft.world.level.GameType.SURVIVAL||!DeepCubeDomain.allowsMove(c.level,c.player.getBoundingBox())
                ||DeepCubeDomain.space(c.level).map(s->s.block(new BlockCoordinate(miningTarget.getX(),miningTarget.getY(),miningTarget.getZ())).isEmpty()).orElse(true))abortMining();
    }
    public static boolean breakBlock(BlockPos pos,Direction face) {
        var client=Minecraft.getInstance();
        Vec3 hit=Vec3.atCenterOf(pos).add(face.getStepX()*.5,face.getStepY()*.5,face.getStepZ()*.5);
        if(client.hitResult instanceof BlockHitResult nativeHit && nativeHit.getType()==HitResult.Type.BLOCK && nativeHit.getBlockPos().equals(pos)) hit=nativeHit.getLocation();
        return send(false,pos,face,hit,InteractionHand.MAIN_HAND);
    }
    public static boolean place(BlockHitResult hit,InteractionHand hand) {
        var client=Minecraft.getInstance();
        if(session!=null && client.player!=null && client.level!=null && client.level.getBlockState(hit.getBlockPos()).is(net.minecraft.world.level.block.Blocks.CHEST)) {
            if(client.player.isSecondaryUseActive() && !client.player.getItemInHand(hand).isEmpty())return false;
            pendingQuery=UUID.randomUUID();query=new JsonObject();query.addProperty("request",pendingQuery.toString());query.addProperty("completed",false);
            DeepRuntimeNetwork.useChest(new DeepChestPackets.Use(session,pendingQuery,client.level.dimension().location().toString(),hit.getBlockPos(),Block.getId(client.level.getBlockState(hit.getBlockPos())),hand,hit.getDirection(),hit.getLocation()));return true;
        }
        return send(true,hit.getBlockPos(),hit.getDirection(),hit.getLocation(),hand);
    }
    private static boolean send(boolean place,BlockPos pos,Direction face,Vec3 hit,InteractionHand hand) {
        var client=Minecraft.getInstance();
        if(session==null || client.player==null || client.level==null || pending!=0 || System.nanoTime()<cooldownUntil) return false;
        if(!client.player.isCreative() && (!place || client.gameMode.getPlayerMode()!=net.minecraft.world.level.GameType.SURVIVAL)) return false;
        var space=DeepCubeDomain.space(client.level);if(space.isEmpty()) return false;
        var state=space.get().block(new BlockCoordinate(pos.getX(),pos.getY(),pos.getZ()));if(state.isEmpty()) return false;
        if(place && (!(client.player.getItemInHand(hand).getItem() instanceof BlockItem item) || !DeepCreativeEdits.inert(item.getBlock().defaultBlockState()))) return false;
        pending=++sequence;
        DeepRuntimeNetwork.edit(new DeepRuntimeNetwork.Edit(session,pending,client.level.dimension().location().toString(),
                new DeepCreativeEdits.Request(place,pos.immutable(),face,hit,state.getAsInt()),hand));
        return true;
    }
}
