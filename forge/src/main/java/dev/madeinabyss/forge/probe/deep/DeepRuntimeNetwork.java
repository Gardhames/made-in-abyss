package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.DevProbeAccess;
import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.forge.cube.DeepCreativeEdits;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded native section mirror and integer-coordinate requests; never uses packed BlockPos packets. */
public final class DeepRuntimeNetwork {
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MadeInAbyssProbe.MOD_ID,"deep_runtime"),()->"8",
            NetworkRegistry.acceptMissingOr("8"),NetworkRegistry.acceptMissingOr("8"));
    private static Consumer<Window> windowReceiver = packet -> {};
    private static Consumer<Ack> ackReceiver = packet -> {};
    private static Consumer<State> stateReceiver = packet -> {};
    private static Consumer<Lid> lidReceiver=packet -> {};
    private static Consumer<DeepItemPackets.Admission> itemReceiver=packet -> {};
    private static Consumer<MiningAck> miningReceiver=packet -> {};
    private DeepRuntimeNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0,Setup.class,(m,b)->{
            b.writeUUID(m.session()); b.writeBoolean(m.vertical()); b.writeInt(m.start().startY());
            b.writeBoolean(m.start().flying()); b.writeFloat(m.start().flightSpeed());
        },b->{
            var setup = new Setup(b.readUUID(),b.readBoolean(),new DeepProbeStart(b.readInt(),b.readBoolean(),b.readFloat()));
            if(b.isReadable()) throw new IllegalArgumentException("Trailing deep setup");
            return setup;
        },(m,c)->server(c,p->DeepCubeRuntime.start(p,m.session(),m.vertical(),m.start())),Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1,Window.class,(m,b)->{
            b.writeUUID(m.session()); b.writeLong(m.revision()); b.writeVarInt(m.frames().size());
            for(byte[] frame:m.frames()) { b.writeVarInt(frame.length); b.writeBytes(frame); }
            DeepChestPackets.writeMirrors(b,m.chests());
        },b->{
            if(b.readableBytes()>1048576) throw new IllegalArgumentException("Oversized deep window");
            UUID session=b.readUUID(); long revision=b.readLong(); int count=b.readVarInt();
            if(count!=27) throw new IllegalArgumentException("Expected bounded 27-cube window");
            List<byte[]> frames=new ArrayList<>();
            for(int i=0;i<count;i++) {
                int n=b.readVarInt();
                if(n<=0 || n>CubeSectionPacketCodec.MAX_SECTION_BYTES+24 || n>b.readableBytes()) throw new IllegalArgumentException("Invalid deep frame");
                byte[] frame=new byte[n]; b.readBytes(frame); frames.add(frame);
            }
            var chests=DeepChestPackets.readMirrors(b);
            if(b.isReadable() || revision<0) throw new IllegalArgumentException("Invalid deep window trailer/revision");
            return new Window(session,revision,List.copyOf(frames),chests);
        },(m,c)->client(c,()->windowReceiver.accept(m)),Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2,Edit.class,(m,b)->{
            b.writeUUID(m.session()); b.writeLong(m.sequence()); b.writeUtf(m.dimension(),128);
            b.writeBoolean(m.request().place()); BlockPos p=m.request().clicked();
            b.writeInt(p.getX()); b.writeInt(p.getY()); b.writeInt(p.getZ());
            b.writeByte(m.request().face().ordinal()); b.writeByte(m.hand().ordinal());
            b.writeDouble(m.request().hit().x); b.writeDouble(m.request().hit().y); b.writeDouble(m.request().hit().z);
            b.writeInt(m.request().expectedState());
        },b->{
            UUID session=b.readUUID(); long sequence=b.readLong(); String dimension=b.readUtf(128); boolean place=b.readBoolean();
            BlockPos pos=new BlockPos(b.readInt(),b.readInt(),b.readInt()); int face=b.readUnsignedByte(),hand=b.readUnsignedByte();
            if(face>=Direction.values().length || hand>=InteractionHand.values().length || sequence<=0) throw new IllegalArgumentException("Invalid deep edit enums/sequence");
            Vec3 hit=new Vec3(b.readDouble(),b.readDouble(),b.readDouble()); int expected=b.readInt();
            if(b.isReadable()) throw new IllegalArgumentException("Trailing deep edit");
            return new Edit(session,sequence,dimension,new DeepCreativeEdits.Request(place,pos,Direction.values()[face],hit,expected),InteractionHand.values()[hand]);
        },(m,c)->server(c,p->DeepCubeRuntime.edit(p,m)),Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3,Ack.class,(m,b)->{b.writeUUID(m.session());b.writeLong(m.sequence());b.writeBoolean(m.accepted());b.writeUtf(m.reason(),128);},
                b->new Ack(b.readUUID(),b.readLong(),b.readBoolean(),b.readUtf(128)),(m,c)->client(c,()->ackReceiver.accept(m)),Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(4,Query.class,(m,b)->{b.writeUUID(m.id());b.writeInt(m.pos().getX());b.writeInt(m.pos().getY());b.writeInt(m.pos().getZ());},
                b->new Query(b.readUUID(),new BlockPos(b.readInt(),b.readInt(),b.readInt())),(m,c)->server(c,p->DeepCubeRuntime.query(p,m)),Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(5,State.class,(m,b)->{b.writeUUID(m.id());b.writeUtf(m.json(),8192);},
                b->new State(b.readUUID(),b.readUtf(8192)),(m,c)->client(c,()->stateReceiver.accept(m)),Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(6,FluidControl.class,(m,b)->{b.writeUUID(m.session());b.writeUUID(m.id());b.writeUtf(m.action(),16);},
                b->{var m=new FluidControl(b.readUUID(),b.readUUID(),b.readUtf(16));
                    if(b.isReadable() || !List.of("activate","pause","resume","seed","remove").contains(m.action()))
                        throw new IllegalArgumentException("Invalid fluid control");return m;},
                (m,c)->server(c,p->DeepCubeRuntime.fluidControl(p,m)),Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(7,ChestControl.class,(m,b)->{b.writeUUID(m.session());b.writeUUID(m.id());b.writeUtf(m.action(),16);},
                b->{var m=new ChestControl(b.readUUID(),b.readUUID(),b.readUtf(16));if(b.isReadable()||!List.of("activate","place","remove","query").contains(m.action()))throw new IllegalArgumentException("invalid chest fixture control");return m;},
                (m,c)->server(c,p->DeepCubeRuntime.chestControl(p,m)),Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(8,DeepChestPackets.Use.class,(m,b)->DeepChestPackets.writeUse(b,m),DeepChestPackets::readUse,
                (m,c)->server(c,p->DeepCubeRuntime.chestUse(p,m)),Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(9,Lid.class,(m,b)->{b.writeUUID(m.session());b.writeLong(m.revision());b.writeInt(m.position().getX());b.writeInt(m.position().getY());b.writeInt(m.position().getZ());b.writeByte(m.id());b.writeByte(m.value());},
                b->{var m=new Lid(b.readUUID(),b.readLong(),new BlockPos(b.readInt(),b.readInt(),b.readInt()),b.readUnsignedByte(),b.readUnsignedByte());if(b.isReadable()||m.revision()<0||m.id()!=1||m.value()>4)throw new IllegalArgumentException("invalid chest lid event");return m;},
                (m,c)->client(c,()->lidReceiver.accept(m)),Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(10,ItemControl.class,(m,b)->{b.writeUUID(m.session());b.writeUUID(m.id());b.writeUtf(m.action(),16);},
                b->{var m=new ItemControl(b.readUUID(),b.readUUID(),b.readUtf(16));if(b.isReadable()||!List.of("activate","seed","pause","resume","flush","remove","query").contains(m.action()))throw new IllegalArgumentException("invalid item fixture control");return m;},
                (m,c)->server(c,p->DeepCubeRuntime.itemControl(p,m)),Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(11,DeepItemPackets.Admission.class,(m,b)->DeepItemPackets.write(b,m),DeepItemPackets::read,
                (m,c)->client(c,()->itemReceiver.accept(m)),Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(12,Mining.class,DeepRuntimeNetwork::writeMining,DeepRuntimeNetwork::readMining,
                (m,c)->server(c,p->DeepCubeRuntime.mining(p,m)),Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(13,MiningAck.class,(m,b)->{b.writeUUID(m.session());b.writeLong(m.sequence());b.writeLong(m.operation());b.writeBoolean(m.accepted());b.writeBoolean(m.terminal());b.writeUtf(m.reason(),128);},
                b->{var m=new MiningAck(b.readUUID(),b.readLong(),b.readLong(),b.readBoolean(),b.readBoolean(),b.readUtf(128));if(b.isReadable()||m.sequence()<=0||m.operation()<=0)throw new IllegalArgumentException("invalid mining acknowledgement");return m;},
                (m,c)->client(c,()->miningReceiver.accept(m)),Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(14,GeneratedSetup.class,(m,b)->{b.writeUUID(m.session());b.writeUtf(m.profileId(),64);},
                b->{var m=new GeneratedSetup(b.readUUID(),b.readUtf(64));if(b.isReadable()||!m.profileId().matches("[a-z0-9][a-z0-9_-]{0,63}"))throw new IllegalArgumentException("Invalid generated profile request");return m;},
                (m,c)->server(c,p->DeepCubeRuntime.startGenerated(p,m.session(),m.profileId())),Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
    public static void writeMining(Mining m,FriendlyByteBuf b) {
        b.writeUUID(m.session());b.writeLong(m.sequence());b.writeLong(m.operation());b.writeUtf(m.dimension(),128);
        b.writeInt(m.position().getX());b.writeInt(m.position().getY());b.writeInt(m.position().getZ());
        b.writeByte(m.direction().ordinal());b.writeByte(m.action().ordinal());b.writeVarInt(m.nativeSequence());b.writeInt(m.expectedState());
    }
    public static Mining readMining(FriendlyByteBuf b) {
        var session=b.readUUID();long sequence=b.readLong(),operation=b.readLong();String dimension=b.readUtf(128);
        var pos=new BlockPos(b.readInt(),b.readInt(),b.readInt());int face=b.readUnsignedByte(),action=b.readUnsignedByte();int nativeSequence=b.readVarInt(),state=b.readInt();
        if(b.isReadable()||sequence<=0||operation<=0||operation>sequence||nativeSequence<0||face>=Direction.values().length||action>=ServerboundPlayerActionPacket.Action.values().length)
            throw new IllegalArgumentException("invalid signed mining request");
        var value=ServerboundPlayerActionPacket.Action.values()[action];
        if(!miningAction(value))throw new IllegalArgumentException("unsupported mining action");
        return new Mining(session,sequence,operation,dimension,pos,Direction.values()[face],value,nativeSequence,state);
    }
    public static boolean miningAction(ServerboundPlayerActionPacket.Action action) {
        return action==ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK||action==ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK||action==ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK;
    }
    private static void server(Supplier<NetworkEvent.Context> context, Consumer<ServerPlayer> work) {
        var c=context.get(); c.enqueueWork(()->{
            var p=c.getSender();
            if(p!=null && (DeepCubeRuntime.nativeWorld(p.level()) || DevProbeAccess.allowed(p.getGameProfile().getName(),Boolean.getBoolean("mia.cube.playerProbe")))) work.accept(p);
        }); c.setPacketHandled(true);
    }
    private static void client(Supplier<NetworkEvent.Context> context,Runnable work) {var c=context.get();c.enqueueWork(work);c.setPacketHandled(true);}
    public static void receivers(Consumer<Window> windows,Consumer<Ack> acks,Consumer<State> states) {windowReceiver=windows;ackReceiver=acks;stateReceiver=states;}
    public static boolean remotePresent(Connection connection){return CHANNEL.isRemotePresent(connection);}
    public static void setup(Connection connection,UUID session) {
        setup(connection,session,false);
    }
    public static void setup(Connection connection,UUID session,boolean vertical) {
        setup(connection,session,vertical,DeepProbeStart.vertical("descent",null,false));
    }
    public static void setup(Connection connection,UUID session,boolean vertical,DeepProbeStart start) {
        if(!Boolean.getBoolean("mia.cube.playerProbe") || !CHANNEL.isRemotePresent(connection)) throw new IllegalStateException("Deep runtime unavailable");
        if(!vertical && !DeepLightNetwork.remotePresent(connection)) throw new IllegalStateException("Observer scalar light channel unavailable");
        CHANNEL.sendToServer(new Setup(session,vertical,start));
    }
    public static void edit(Edit edit) {CHANNEL.sendToServer(edit);}
    public static void generatedSetup(Connection connection,UUID session,String profileId) {
        if(!Boolean.getBoolean("mia.cube.playerProbe")||!CHANNEL.isRemotePresent(connection))throw new IllegalStateException("Generated preview unavailable");
        CHANNEL.sendToServer(new GeneratedSetup(session,profileId));
    }
    public static void mining(Mining mining) {CHANNEL.sendToServer(mining);}
    public static void miningReceiver(Consumer<MiningAck> receiver) {miningReceiver=receiver;}
    public static void miningAck(ServerPlayer player,MiningAck ack) {CHANNEL.send(PacketDistributor.PLAYER.with(()->player),ack);}
    public static void query(UUID id,BlockPos pos) {CHANNEL.sendToServer(new Query(id,pos));}
    public static void fluid(UUID session,UUID id,String action) {CHANNEL.sendToServer(new FluidControl(session,id,action));}
    public static void chest(UUID session,UUID id,String action) {CHANNEL.sendToServer(new ChestControl(session,id,action));}
    public static void item(UUID session,UUID id,String action) {CHANNEL.sendToServer(new ItemControl(session,id,action));}
    public static void itemReceiver(Consumer<DeepItemPackets.Admission> receiver){itemReceiver=receiver;}
    public static void itemAdmission(ServerPlayer player,DeepItemPackets.Admission admission){CHANNEL.send(PacketDistributor.PLAYER.with(()->player),admission);}
    public static void useChest(DeepChestPackets.Use use) {CHANNEL.sendToServer(use);}
    public static void lidReceiver(Consumer<Lid> receiver){lidReceiver=receiver;}
    public static void lid(ServerPlayer player,Lid event){CHANNEL.send(PacketDistributor.PLAYER.with(()->player),event);}
    public static void ack(ServerPlayer player,Ack ack) {CHANNEL.send(PacketDistributor.PLAYER.with(()->player),ack);}
    public static void state(ServerPlayer player,UUID id,String json) {CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new State(id,json));}
    public static void window(ServerPlayer player,UUID session,long revision,List<CubeSnapshot> snapshots) {
        var biomes=player.level().registryAccess().registryOrThrow(Registries.BIOME);
        List<byte[]> frames=new ArrayList<>();
        List<DeepChestPackets.Mirror> chests=new ArrayList<>();
        for(CubeSnapshot snapshot:snapshots) {
            int[] states=snapshot.states(); var section=new LevelChunkSection(biomes);
            for(var saved:snapshot.blockEntities()) {
                var pos=saved.position();var nativePos=new BlockPos(pos.x(),pos.y(),pos.z());
                int index=(Math.floorMod(pos.y(),16)<<8)|(Math.floorMod(pos.z(),16)<<4)|Math.floorMod(pos.x(),16);
                try {
                    var full=dev.madeinabyss.forge.cube.ChestBlockEntityCodec.validateAndRead(saved,Block.stateById(states[index]));
                    // Native metadata only. Inventory contents remain private to vanilla menu sync.
                    var update=new net.minecraft.nbt.CompoundTag();update.putString("id","minecraft:chest");update.putInt("x",pos.x());update.putInt("y",pos.y());update.putInt("z",pos.z());update.put("Items",new net.minecraft.nbt.ListTag());
                    if(full.contains("CustomName"))update.putString("CustomName",full.getString("CustomName"));
                    var bytes=new java.io.ByteArrayOutputStream();net.minecraft.nbt.NbtIo.write(update,new java.io.DataOutputStream(bytes));
                    chests.add(new DeepChestPackets.Mirror(nativePos,states[index],bytes.toByteArray()));
                }catch(java.io.IOException invalid){throw new IllegalStateException("chest metadata cannot be published",invalid);}
            }
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) section.setBlockState(x,y,z,Block.stateById(states[y<<8|z<<4|x]));
            var b=new FriendlyByteBuf(Unpooled.buffer(4096,CubeSectionPacketCodec.MAX_SECTION_BYTES+24));
            try {CubeSectionPacketCodec.encode(b,snapshot.key(),section);byte[] frame=new byte[b.readableBytes()];b.readBytes(frame);frames.add(frame);}
            finally {b.release();}
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new Window(session,revision,List.copyOf(frames),List.copyOf(chests)));
    }
    private record Setup(UUID session,boolean vertical,DeepProbeStart start) {}
    private record GeneratedSetup(UUID session,String profileId) {}
    public record Window(UUID session,long revision,List<byte[]> frames,List<DeepChestPackets.Mirror> chests) {
        public Window(UUID session,long revision,List<byte[]> frames){this(session,revision,frames,List.of());}
    }
    public record Edit(UUID session,long sequence,String dimension,DeepCreativeEdits.Request request,InteractionHand hand) {}
    public record Ack(UUID session,long sequence,boolean accepted,String reason) {}
    public record Mining(UUID session,long sequence,long operation,String dimension,BlockPos position,Direction direction,ServerboundPlayerActionPacket.Action action,int nativeSequence,int expectedState) {}
    public record MiningAck(UUID session,long sequence,long operation,boolean accepted,boolean terminal,String reason) {}
    public record Query(UUID id,BlockPos pos) {}
    public record State(UUID id,String json) {}
    public record FluidControl(UUID session,UUID id,String action) {}
    public record ChestControl(UUID session,UUID id,String action) {}
    public record ItemControl(UUID session,UUID id,String action) {}
    public record Lid(UUID session,long revision,BlockPos position,int id,int value) {}
}
