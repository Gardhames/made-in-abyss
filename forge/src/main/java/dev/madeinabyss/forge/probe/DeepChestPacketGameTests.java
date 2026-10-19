package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepChestPackets;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepChestPacketGameTests {
    @GameTest(template="empty")
    public static void signedChestMirrorsDoNotAliasPackedVerticalCoordinates(GameTestHelper h) {
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {
            byte[] first={10,0,0,0};byte[] second={10,0,1,0};
            var a=new BlockPos(-1,-19999,-17);var b=new BlockPos(-1,-15903,-17);
            DeepChestPackets.writeMirrors(buffer,List.of(new DeepChestPackets.Mirror(a,1,first),new DeepChestPackets.Mirror(b,2,second)));
            var decoded=DeepChestPackets.readMirrors(buffer);
            h.assertTrue(decoded.size()==2 && decoded.get(0).position().equals(a) && decoded.get(1).position().equals(b),"full signed XYZ separated by4096 remain distinct");
            h.assertTrue(Arrays.equals(decoded.get(0).updateNbt(),first) && Arrays.equals(decoded.get(1).updateNbt(),second) && !buffer.isReadable(),"bounded payloads roundtrip without trailer");
            first[0]=3;h.assertTrue(decoded.get(0).updateNbt()[0]==10,"decoded metadata independent of source");
        } finally {buffer.release();}h.succeed();
    }
    @GameTest(template="empty")
    public static void chestUseRetainsSessionDimensionReachInputsAndExactY(GameTestHelper h) {
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {
            var request=new DeepChestPackets.Use(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",new BlockPos(-1,-19999,5),7,InteractionHand.MAIN_HAND,Direction.NORTH,new Vec3(-.5,-19998.5,5));
            DeepChestPackets.writeUse(buffer,request);
            h.assertTrue(request.equals(DeepChestPackets.readUse(buffer)) && !buffer.isReadable(),"native interaction inputs signed exact roundtrip");
            buffer.clear();DeepChestPackets.writeUse(buffer,request);buffer.writeByte(99);
            boolean rejected=false;try{DeepChestPackets.readUse(buffer);}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected,"trailing interaction rejected before server dispatch");
        } finally {buffer.release();}h.succeed();
    }
    @GameTest(template="empty")
    public static void chestFrameBoundsAndDuplicateCoordinatesRejectBeforePublication(GameTestHelper h) {
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {
            var mirror=new DeepChestPackets.Mirror(new BlockPos(-1,-19999,-1),1,new byte[]{10});
            boolean rejected=false;try{DeepChestPackets.writeMirrors(buffer,List.of(mirror,mirror));}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected,"duplicate metadata coordinates refused");
            buffer.clear();buffer.writeVarInt(65);rejected=false;
            try{DeepChestPackets.readMirrors(buffer);}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected,"over-budget mirror count refused before allocations");
            buffer.clear();buffer.writeVarInt(1);buffer.writeInt(-1);buffer.writeInt(-19999);buffer.writeInt(-1);buffer.writeVarInt(1);buffer.writeVarInt(4097);rejected=false;
            try{DeepChestPackets.readMirrors(buffer);}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected,"over-budget NBT length refused before allocations");
        } finally {buffer.release();}h.succeed();
    }
}
