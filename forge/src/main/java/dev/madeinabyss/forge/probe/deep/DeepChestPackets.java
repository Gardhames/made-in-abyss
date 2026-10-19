package dev.madeinabyss.forge.probe.deep;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.HashSet;
import java.util.ArrayList;

/** Full signed XYZ chest framing; native inventory clicks retain vanilla container packets. */
public final class DeepChestPackets {
    public static final int MAX_MIRRORS=64,MAX_UPDATE_BYTES=4096;
    private DeepChestPackets() {}
    public record Mirror(BlockPos position,int stateId,byte[] updateNbt) {
        public Mirror {
            position=Objects.requireNonNull(position).immutable();updateNbt=Objects.requireNonNull(updateNbt).clone();
            if(stateId<0 || updateNbt.length<1 || updateNbt.length>MAX_UPDATE_BYTES) throw new IllegalArgumentException("invalid chest metadata");
        }
        @Override public byte[] updateNbt() {return updateNbt.clone();}
    }
    public record Use(UUID session,UUID id,String dimension,BlockPos position,int expectedState,
                      InteractionHand hand,Direction face,Vec3 hit) {
        public Use {
            Objects.requireNonNull(session);Objects.requireNonNull(id);Objects.requireNonNull(hand);Objects.requireNonNull(face);
            position=Objects.requireNonNull(position).immutable();Objects.requireNonNull(hit);
            if(dimension==null || dimension.length()>128 || net.minecraft.resources.ResourceLocation.tryParse(dimension)==null || expectedState<0
                    || !Double.isFinite(hit.x) || !Double.isFinite(hit.y) || !Double.isFinite(hit.z)) throw new IllegalArgumentException("invalid chest interaction");
        }
    }
    public static void writeMirrors(FriendlyByteBuf buffer,List<Mirror> mirrors) {
        var stable=List.copyOf(mirrors);var unique=new HashSet<BlockPos>();
        if(stable.size()>MAX_MIRRORS) throw new IllegalArgumentException("chest metadata budget");
        for(var mirror:stable)if(!unique.add(mirror.position()))throw new IllegalArgumentException("duplicate chest metadata");
        buffer.writeVarInt(stable.size());
        for(var mirror:stable){var pos=mirror.position();byte[] nbt=mirror.updateNbt();buffer.writeInt(pos.getX());buffer.writeInt(pos.getY());buffer.writeInt(pos.getZ());buffer.writeVarInt(mirror.stateId());buffer.writeVarInt(nbt.length);buffer.writeBytes(nbt);}
    }
    public static List<Mirror> readMirrors(FriendlyByteBuf buffer) {
        int count=buffer.readVarInt();if(count<0 || count>MAX_MIRRORS)throw new IllegalArgumentException("chest metadata budget");
        var mirrors=new ArrayList<Mirror>();var unique=new HashSet<BlockPos>();
        for(int i=0;i<count;i++) {
            var pos=new BlockPos(buffer.readInt(),buffer.readInt(),buffer.readInt());int state=buffer.readVarInt(),size=buffer.readVarInt();
            if(size<1 || size>MAX_UPDATE_BYTES || size>buffer.readableBytes() || !unique.add(pos))throw new IllegalArgumentException("invalid chest metadata bounds/duplicate");
            byte[] bytes=new byte[size];buffer.readBytes(bytes);mirrors.add(new Mirror(pos,state,bytes));
        }
        return List.copyOf(mirrors);
    }
    public static void writeUse(FriendlyByteBuf buffer,Use use) {
        buffer.writeUUID(use.session());buffer.writeUUID(use.id());buffer.writeUtf(use.dimension(),128);
        var pos=use.position();buffer.writeInt(pos.getX());buffer.writeInt(pos.getY());buffer.writeInt(pos.getZ());buffer.writeVarInt(use.expectedState());
        buffer.writeByte(use.hand().ordinal());buffer.writeByte(use.face().ordinal());buffer.writeDouble(use.hit().x);buffer.writeDouble(use.hit().y);buffer.writeDouble(use.hit().z);
    }
    public static Use readUse(FriendlyByteBuf buffer) {
        var session=buffer.readUUID();var id=buffer.readUUID();var dimension=buffer.readUtf(128);var pos=new BlockPos(buffer.readInt(),buffer.readInt(),buffer.readInt());int expected=buffer.readVarInt();
        int hand=buffer.readUnsignedByte(),face=buffer.readUnsignedByte();
        if(hand>=InteractionHand.values().length || face>=Direction.values().length)throw new IllegalArgumentException("invalid chest interaction enums");
        var hit=new Vec3(buffer.readDouble(),buffer.readDouble(),buffer.readDouble());
        var result=new Use(session,id,dimension,pos,expected,InteractionHand.values()[hand],Direction.values()[face],hit);
        if(buffer.isReadable())throw new IllegalArgumentException("trailing chest interaction");return result;
    }
}
