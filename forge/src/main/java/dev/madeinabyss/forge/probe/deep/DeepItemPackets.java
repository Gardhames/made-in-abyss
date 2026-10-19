package dev.madeinabyss.forge.probe.deep;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

public final class DeepItemPackets {
    public record Admission(UUID session,String dimension,UUID uuid,int entityId,Vec3 position,boolean active) {}
    private DeepItemPackets() {}
    public static void write(FriendlyByteBuf b,Admission a) { validate(a);b.writeUUID(a.session());b.writeUtf(a.dimension(),128);b.writeUUID(a.uuid());b.writeInt(a.entityId());b.writeDouble(a.position().x);b.writeDouble(a.position().y);b.writeDouble(a.position().z);b.writeBoolean(a.active()); }
    public static Admission read(FriendlyByteBuf b) {
        if(b.readableBytes()>512)throw new IllegalArgumentException("oversized native item admission");
        var session=b.readUUID();var dimension=b.readUtf(128);var uuid=b.readUUID();int id=b.readInt();
        var position=new Vec3(b.readDouble(),b.readDouble(),b.readDouble());int flag=b.readUnsignedByte();
        if(flag>1 || b.isReadable())throw new IllegalArgumentException("invalid native item admission trailer/boolean");
        var admission=new Admission(session,dimension,uuid,id,position,flag==1);validate(admission);return admission;
    }
    private static void validate(Admission a){
        var p=a.position();var dimension=net.minecraft.resources.ResourceLocation.tryParse(a.dimension());
        if(a.session()==null || a.uuid()==null || a.entityId()<0 || dimension==null || !dimension.toString().equals(a.dimension()) || a.dimension().length()>128
            || p==null || !Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z) || Math.abs(p.x)>30000512D || Math.abs(p.z)>30000512D || p.y<DeepCubeDomain.TECHNICAL_BOTTOM || p.y>=-64)
            throw new IllegalArgumentException("invalid native item admission identity/full XYZ");
    }
}
