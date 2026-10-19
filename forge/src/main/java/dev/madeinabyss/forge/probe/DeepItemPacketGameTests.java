package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepItemPackets;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepItemPacketGameTests {
    @GameTest(template="empty")
    public static void fractionalDeepIdentityRoundTripsWithoutPackedCoordinates(GameTestHelper h) {
        var b=new FriendlyByteBuf(Unpooled.buffer());
        var packet=new DeepItemPackets.Admission(UUID.randomUUID(),"minecraft:overworld",UUID.randomUUID(),71,new Vec3(-16.25,-19999.125,-.5),true);
        try { DeepItemPackets.write(b,packet); h.assertTrue(packet.equals(DeepItemPackets.read(b)),"session, UUID, native id and fractional full XYZ preserved"); }
        finally { b.release(); } h.succeed();
    }
    @GameTest(template="empty")
    public static void malformedAdmissionRejectsBeforeClientPublication(GameTestHelper h) {
        for(int malformed=0;malformed<4;malformed++) {
            var b=new FriendlyByteBuf(Unpooled.buffer());
            b.writeUUID(UUID.randomUUID());b.writeUtf("minecraft:overworld",128);b.writeUUID(UUID.randomUUID());b.writeInt(malformed==0?-1:71);
            b.writeDouble(malformed==1?Double.NaN:-.5);b.writeDouble(-19999.125);b.writeDouble(-16.25);b.writeByte(malformed==2?2:1);
            if(malformed==3)b.writeByte(42);
            try { boolean refused=false;try{DeepItemPackets.read(b);}catch(IllegalArgumentException expected){refused=true;}h.assertTrue(refused,"invalid id/NaN/boolean/trailer rejected"); }
            finally { b.release(); }
        } h.succeed();
    }
}
