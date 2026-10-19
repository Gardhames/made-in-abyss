package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import java.util.UUID;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorldSessionNetworkGameTests {
    @GameTest(template="empty")
    public static void worldManifestOfferRetainsSessionDimensionAndFullRecipe(GameTestHelper helper) {
        var offer=new WorldSessionNetwork.Offer(UUID.randomUUID(),"minecraft:overworld","a".repeat(64),"{\"profileId\":\"test\",\"recipe\":\"\u0440\u0430\u0441\u0442\u0435\u043d\u0438\u044f\"}");
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {
            WorldSessionNetwork.encode(offer,buffer);
            helper.assertTrue(offer.equals(WorldSessionNetwork.decode(buffer)),"complete manifest/session/dimension must roundtrip");
            helper.assertTrue(!buffer.isReadable(),"offer consumed entirely");
        } finally {buffer.release();}
        try {new WorldSessionNetwork.Offer(UUID.randomUUID(),"minecraft:overworld","invalid","{}");throw new AssertionError("invalid fingerprint accepted");}
        catch(IllegalArgumentException expected) { }
        try {new WorldSessionNetwork.Offer(UUID.randomUUID(),"minecraft:overworld","a".repeat(64),"x".repeat(WorldSessionNetwork.MAX_MANIFEST_CHARS+1));throw new AssertionError("unbounded manifest accepted");}
        catch(IllegalArgumentException expected) { }
        helper.succeed();
    }
}
