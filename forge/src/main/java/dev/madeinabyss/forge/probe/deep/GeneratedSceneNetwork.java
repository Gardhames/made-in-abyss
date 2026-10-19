package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;
import io.netty.buffer.Unpooled;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Initial saved geometry and authoritative live deltas, independent from cube residency. */
public final class GeneratedSceneNetwork {
    public static final int MAX_CUBES=8192, MAX_BATCH_BYTES=1048576-1;
    public static final long MAX_ENCODED_BYTES=64L*1024*1024;
    private static final int FRAME_MAX=CubeSectionPacketCodec.MAX_SECTION_BYTES+24;
    private static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MadeInAbyssProbe.MOD_ID,"generated_scene"),()->"2",
            NetworkRegistry.acceptMissingOr("2"),NetworkRegistry.acceptMissingOr("2"));
    private static Consumer<Message> receiver=message->{ };
    private GeneratedSceneNetwork() { }
    public sealed interface Message permits Begin,Batch,End,Delta { UUID session(); }
    /** One committed whole cube; revision is a separate contiguous per-observer stream. */
    public record Delta(UUID session,long revision,Frame frame) implements Message {
        public Delta {Objects.requireNonNull(session);Objects.requireNonNull(frame);if(revision<=0)throw new IllegalArgumentException("Invalid global delta revision");}
    }
    public record Begin(UUID session,String profileFingerprint,int cubeCount,long encodedBytes) implements Message {
        public Begin {checkTotals(session,cubeCount,encodedBytes);if(profileFingerprint==null||!profileFingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid generated profile fingerprint");}
    }
    public record Frame(CubeKey key,byte[] bytes) {
        public Frame {
            Objects.requireNonNull(key);Objects.requireNonNull(bytes);
            if(bytes.length<25||bytes.length>FRAME_MAX)throw new IllegalArgumentException("Invalid scene frame length");
            var header=ByteBuffer.wrap(bytes);
            if(header.getInt()!=0x4d494143||header.getInt()!=1||header.getInt()!=key.x()||header.getInt()!=key.y()||header.getInt()!=key.z())throw new IllegalArgumentException("Invalid scene frame header/XYZ");
            int length=header.getInt();if(length<1||length>CubeSectionPacketCodec.MAX_SECTION_BYTES||length!=header.remaining())throw new IllegalArgumentException("Invalid scene payload length");
            bytes=bytes.clone();
        }
        @Override public byte[] bytes(){return bytes.clone();}
    }
    public record Batch(UUID session,int firstIndex,List<Frame> frames) implements Message {
        public Batch {
            Objects.requireNonNull(session);frames=List.copyOf(frames);
            if(firstIndex<0||firstIndex>=MAX_CUBES||frames.isEmpty()||frames.size()>MAX_CUBES-firstIndex)throw new IllegalArgumentException("Invalid scene batch index/count");
            long size=25;var keys=new HashSet<CubeKey>();
            for(var frame:frames){size+=16L+frame.bytes.length;if(!keys.add(frame.key()))throw new IllegalArgumentException("Duplicate scene batch XYZ");}
            if(size>MAX_BATCH_BYTES)throw new IllegalArgumentException("Scene batch exceeds packet bound");
        }
    }
    public record End(UUID session,int cubeCount,long encodedBytes) implements Message {
        public End {checkTotals(session,cubeCount,encodedBytes);}
    }
    public record Completed(Begin begin,List<Frame> frames) {
        public Completed {Objects.requireNonNull(begin);frames=List.copyOf(frames);}
    }
    private static void checkTotals(UUID session,int count,long bytes) {
        Objects.requireNonNull(session);
        if(count<0||count>MAX_CUBES||bytes<25L*count||bytes>FRAME_MAX*(long)count||bytes>MAX_ENCODED_BYTES)throw new IllegalArgumentException("Invalid generated scene count/bytes");
    }
    public static final class Receiver {
        private Begin begin;private final ArrayList<Frame> frames=new ArrayList<>();private final HashSet<CubeKey> keys=new HashSet<>();private long bytes;
        public Optional<Completed> accept(Message message) {
            try {
                if(message instanceof Delta)throw new IllegalArgumentException("Global deltas require separate admission");
                if(message instanceof Begin start){clear();begin=start;return Optional.empty();}
                if(begin==null||!begin.session().equals(message.session()))throw new IllegalArgumentException("Scene session without matching begin");
                if(message instanceof Batch batch) {
                    if(batch.firstIndex()!=frames.size()||batch.frames().size()>begin.cubeCount()-frames.size())throw new IllegalArgumentException("Scene batch out of sequence");
                    for(var frame:batch.frames()) {
                        if(!keys.add(frame.key()))throw new IllegalArgumentException("Duplicate scene XYZ across batches");
                        bytes+=frame.bytes.length;
                        if(bytes>begin.encodedBytes())throw new IllegalArgumentException("Scene exceeds declared bytes");
                        frames.add(frame);
                    }
                    return Optional.empty();
                }
                var end=(End)message;
                if(end.cubeCount()!=begin.cubeCount()||end.encodedBytes()!=begin.encodedBytes()||frames.size()!=begin.cubeCount()||bytes!=begin.encodedBytes())throw new IllegalArgumentException("Incomplete scene end counts");
                var result=new Completed(begin,frames);clear();return Optional.of(result);
            }catch(RuntimeException invalid){clear();throw invalid;}
        }
        public void clear() {begin=null;frames.clear();keys.clear();bytes=0;}
    }
    public static void register() {
        CHANNEL.registerMessage(0,Begin.class,GeneratedSceneNetwork::encode,b->(Begin)decode(b),GeneratedSceneNetwork::handle,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1,Batch.class,GeneratedSceneNetwork::encode,b->(Batch)decode(b),GeneratedSceneNetwork::handle,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2,End.class,GeneratedSceneNetwork::encode,b->(End)decode(b),GeneratedSceneNetwork::handle,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3,Delta.class,GeneratedSceneNetwork::encode,b->(Delta)decode(b),GeneratedSceneNetwork::handle,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    private static void handle(Message message,java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> context) {
        var c=context.get();c.enqueueWork(()->receiver.accept(message));c.setPacketHandled(true);
    }
    public static void receiver(Consumer<Message> handler){receiver=Objects.requireNonNull(handler);}
    public static boolean remotePresent(Connection connection){return CHANNEL.isRemotePresent(connection);}
    /** Read-only actual snapshot encoding; no store/cache lookup, generation or world mutation. */
    public static Frame frame(Registry<Biome> biomes,CubeSnapshot snapshot){
        Objects.requireNonNull(biomes);Objects.requireNonNull(snapshot);var section=new LevelChunkSection(biomes);int[] states=snapshot.states();
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            var state=Block.BLOCK_STATE_REGISTRY.byId(states[y<<8|z<<4|x]);
            if(state==null)throw new IllegalArgumentException("Unknown global delta state ID");section.setBlockState(x,y,z,state);
        }
        var buffer=new FriendlyByteBuf(Unpooled.buffer(4096,FRAME_MAX));
        try{CubeSectionPacketCodec.encode(buffer,snapshot.key(),section);byte[] bytes=new byte[buffer.readableBytes()];buffer.readBytes(bytes);return new Frame(snapshot.key(),bytes);}
        finally{buffer.release();}
    }
    public static void sendDelta(ServerPlayer player,Delta delta){
        Objects.requireNonNull(delta);
        if(!player.serverLevel().getServer().isSameThread())throw new IllegalStateException("Global delta requires server owner thread");
        if(!remotePresent(player.connection.connection))throw new IllegalStateException("Global delta channel absent");
        CHANNEL.send(PacketDistributor.PLAYER.with(()->player),delta);
    }
    public static List<Message> prepare(UUID session,String fingerprint,GeneratedSceneInventory.Snapshot inventory) {
        var result=new ArrayList<Message>();result.add(new Begin(session,fingerprint,inventory.frames().size(),inventory.encodedBytes()));
        int index=0;var batch=new ArrayList<Frame>();long size=25;
        for(var frame:inventory.frames()) {
            if(size+16+frame.bytes.length>MAX_BATCH_BYTES){result.add(new Batch(session,index,batch));index+=batch.size();batch=new ArrayList<>();size=25;}
            batch.add(frame);size+=16+frame.bytes.length;
        }
        if(!batch.isEmpty())result.add(new Batch(session,index,batch));
        result.add(new End(session,inventory.frames().size(),inventory.encodedBytes()));return List.copyOf(result);
    }
    /** Validates the entire immutable transfer before its first outbound message. */
    public static void send(ServerPlayer player,List<Message> messages) {
        if(!player.serverLevel().getServer().isSameThread())throw new IllegalStateException("Generated scene requires server owner thread");
        if(!remotePresent(player.connection.connection))throw new IllegalStateException("Generated scene channel absent");
        messages=List.copyOf(messages);var validator=new Receiver();int completed=0;
        for(int i=0;i<messages.size();i++) {
            if(i==0&&!(messages.get(i) instanceof Begin)||i>0&&messages.get(i) instanceof Begin)throw new IllegalArgumentException("Invalid scene begin sequence");
            if(validator.accept(messages.get(i)).isPresent()){completed++;if(i!=messages.size()-1)throw new IllegalArgumentException("Scene end must be final");}
        }
        if(completed!=1)throw new IllegalArgumentException("Incomplete outbound scene");
        for(var message:messages)CHANNEL.send(PacketDistributor.PLAYER.with(()->player),message);
    }
    public static void encode(Message message,FriendlyByteBuf buffer) {
        int start=buffer.writerIndex();
        buffer.writeByte(message instanceof Begin?0:message instanceof Batch?1:message instanceof Delta?3:2);buffer.writeUUID(message.session());
        if(message instanceof Begin begin){buffer.writeUtf(begin.profileFingerprint(),64);buffer.writeInt(begin.cubeCount());buffer.writeLong(begin.encodedBytes());}
        else if(message instanceof End end){buffer.writeInt(end.cubeCount());buffer.writeLong(end.encodedBytes());}
        else if(message instanceof Delta delta){
            buffer.writeLong(delta.revision());var f=delta.frame();buffer.writeInt(f.key().x());buffer.writeInt(f.key().y());buffer.writeInt(f.key().z());buffer.writeInt(f.bytes.length);buffer.writeBytes(f.bytes);
        }
        else {
            var batch=(Batch)message;buffer.writeInt(batch.firstIndex());buffer.writeInt(batch.frames().size());
            for(var frame:batch.frames()){buffer.writeInt(frame.key().x());buffer.writeInt(frame.key().y());buffer.writeInt(frame.key().z());buffer.writeInt(frame.bytes.length);buffer.writeBytes(frame.bytes);}
        }
        if(buffer.writerIndex()-start>MAX_BATCH_BYTES)throw new IllegalArgumentException("Oversized scene packet");
    }
    /** Transactional bounded decode: failures leave the source reader index unchanged. */
    public static Message decode(FriendlyByteBuf buffer) {
        if(buffer.readableBytes()>MAX_BATCH_BYTES)throw new IllegalArgumentException("Oversized scene packet");
        var input=new FriendlyByteBuf(buffer.duplicate());
        try {
            int kind=input.readUnsignedByte();var session=input.readUUID();Message message;
            if(kind==0)message=new Begin(session,input.readUtf(64),input.readInt(),input.readLong());
            else if(kind==2)message=new End(session,input.readInt(),input.readLong());
            else if(kind==3){
                long revision=input.readLong();var key=new CubeKey(input.readInt(),input.readInt(),input.readInt());int length=input.readInt();
                if(length<25||length>FRAME_MAX||length>input.readableBytes())throw new IllegalArgumentException("Invalid global delta frame length");
                byte[] bytes=new byte[length];input.readBytes(bytes);message=new Delta(session,revision,new Frame(key,bytes));
            }
            else if(kind==1) {
                int index=input.readInt(),count=input.readInt();
                if(count<1||count>MAX_CUBES||index<0||index>MAX_CUBES-count||count>input.readableBytes()/41)throw new IllegalArgumentException("Invalid scene wire batch count");
                var frames=new ArrayList<Frame>(count);
                for(int i=0;i<count;i++) {
                    var key=new CubeKey(input.readInt(),input.readInt(),input.readInt());int length=input.readInt();
                    if(length<25||length>FRAME_MAX||length>input.readableBytes())throw new IllegalArgumentException("Invalid scene wire frame length");
                    byte[] bytes=new byte[length];input.readBytes(bytes);frames.add(new Frame(key,bytes));
                }
                message=new Batch(session,index,frames);
            }else throw new IllegalArgumentException("Unknown scene message kind");
            if(input.isReadable())throw new IllegalArgumentException("Trailing scene payload");buffer.readerIndex(input.readerIndex());return message;
        }catch(IndexOutOfBoundsException truncated){throw new IllegalArgumentException("Truncated scene packet",truncated);}
    }
}
