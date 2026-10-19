package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepManagedItemIdentity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepManagedItemIdentityGameTests {
    // Break caught: UUID-only release clears a new live object, or marker tags admit ordinary items.
    @GameTest(template="empty")
    public static void oldServerObjectCannotReleaseReplacementWithSameUuid(GameTestHelper h) {
        var first=item(h);var replacement=item(h);replacement.setUUID(first.getUUID());
        try {
            h.assertTrue(!DeepManagedItemIdentity.managed(first),"ordinary native item is unadmitted");
            DeepManagedItemIdentity.admit(first);
            h.assertTrue(DeepManagedItemIdentity.managed(first) && !DeepManagedItemIdentity.managed(replacement),"native identity admission is object-specific");
            DeepManagedItemIdentity.release(first);DeepManagedItemIdentity.admit(replacement);DeepManagedItemIdentity.release(first);
            h.assertTrue(DeepManagedItemIdentity.managed(replacement),"late old-object release preserves replacement");
            DeepManagedItemIdentity.release(replacement);
            h.assertTrue(!DeepManagedItemIdentity.managed(replacement),"matching release removes managed identity");
        } finally {DeepManagedItemIdentity.release(first);DeepManagedItemIdentity.release(replacement);first.discard();replacement.discard();}
        h.succeed();
    }

    // Break caught: stale session/id release revokes a later admission, or repeated admission leaks slots.
    @GameTest(template="empty")
    public static void staleClientReleaseCannotRevokeNewSessionAdmission(GameTestHelper h) {
        var level=h.getLevel();UUID uuid=UUID.randomUUID(),oldSession=UUID.randomUUID(),newSession=UUID.randomUUID();DeepManagedItemIdentity.clearClient(level);
        try {
            DeepManagedItemIdentity.clientAdmit(level,uuid,1001,oldSession);
            DeepManagedItemIdentity.clientAdmit(level,uuid,1001,oldSession);
            h.assertTrue(DeepManagedItemIdentity.clientCount(level)==1,"identical admission is idempotent");
            h.assertTrue(DeepManagedItemIdentity.clientRelease(level,uuid,1001,oldSession),"exact admission releases");
            DeepManagedItemIdentity.clientAdmit(level,uuid,1002,newSession);
            h.assertTrue(!DeepManagedItemIdentity.clientRelease(level,uuid,1001,oldSession),"late old admission release refused");
            h.assertTrue(DeepManagedItemIdentity.clientAdmitted(level,uuid,1002,newSession),"new session remains admitted");
            h.assertTrue(!DeepManagedItemIdentity.clientAdmitted(level,uuid,1001,newSession),"wrong native id never matches");
        } finally {DeepManagedItemIdentity.clearClient(level);}
        h.succeed();
    }

    // Break caught: excess client admission mutates the bounded table or replaces an occupied native id.
    @GameTest(template="empty")
    public static void clientAdmissionCapAndIdConflictsRejectBeforeMutation(GameTestHelper h) {
        var level=h.getLevel();DeepManagedItemIdentity.clearClient(level);
        try {
            UUID first=UUID.randomUUID(),session=UUID.randomUUID();DeepManagedItemIdentity.clientAdmit(level,first,1000,session);
            boolean refused=false;try{DeepManagedItemIdentity.clientAdmit(level,UUID.randomUUID(),1000,session);}catch(IllegalArgumentException expected){refused=true;}
            h.assertTrue(refused && DeepManagedItemIdentity.clientAdmitted(level,first,1000,session) && DeepManagedItemIdentity.clientCount(level)==1,"id conflict preserves old admission");
            for(int i=1;i<16;i++)DeepManagedItemIdentity.clientAdmit(level,UUID.randomUUID(),1000+i,session);
            refused=false;try{DeepManagedItemIdentity.clientAdmit(level,UUID.randomUUID(),1016,session);}catch(IllegalArgumentException expected){refused=true;}
            h.assertTrue(refused && DeepManagedItemIdentity.clientCount(level)==16 && DeepManagedItemIdentity.clientAdmitted(level,first,1000,session),"over-cap admission leaves previous table intact");
            DeepManagedItemIdentity.clearClient(level);h.assertTrue(DeepManagedItemIdentity.clientCount(level)==0,"level cleanup empties admissions");
        } finally {DeepManagedItemIdentity.clearClient(level);}
        h.succeed();
    }
    private static ItemEntity item(GameTestHelper h){return new ItemEntity(h.getLevel(),-.5,-19999.25,-16.5,new ItemStack(Items.DIAMOND,1));}
}
