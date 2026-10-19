package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Executes the actual new adapter transport without loading Minecraft client or optional DH. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodTransportGameTests {
    private static AbyssLodPipeline.Target target(){return new AbyssLodPipeline.Target("actual-session",7,"verified-source",19,3,2,40);}
    private static AbyssLodTransport<AbyssLodPipeline.Target,Object> displayed(Object scene) {
        var t=new AbyssLodTransport<AbyssLodPipeline.Target,Object>();t.request(target(),1);t.stage(target(),1,scene);t.uploaded(1,scene);t.promote();return t;
    }
    @GameTest(template="empty")
    public static void sameSequenceDifferentSourceCannotStage(GameTestHelper h) {
        var variants=java.util.List.of(new AbyssLodPipeline.Target("other-session",7,"verified-source",19,3,2,40),
            new AbyssLodPipeline.Target("actual-session",8,"verified-source",19,3,2,40),
            new AbyssLodPipeline.Target("actual-session",7,"changed-source",19,3,2,40),
            new AbyssLodPipeline.Target("actual-session",7,"verified-source",20,3,2,40),
            new AbyssLodPipeline.Target("actual-session",7,"verified-source",19,4,2,40),
            new AbyssLodPipeline.Target("actual-session",7,"verified-source",19,3,3,40),
            new AbyssLodPipeline.Target("actual-session",7,"verified-source",19,3,2,41));
        for(var changed:variants){var t=new AbyssLodTransport<AbyssLodPipeline.Target,Object>();t.request(target(),1);boolean refused=false;
            try{t.stage(changed,1,new Object());}catch(IllegalStateException expected){refused=true;}
            h.assertTrue(refused&&t.size()==0,"Same numeric sequence cannot certify any changed authority field");
            var scene=new Object();t=displayed(scene);h.assertTrue(!t.drawn(changed,1,scene,AbyssLodTransport.DrawPass.WORLD,8),"Actual WORLD callback must fence every target field");
            h.assertTrue(t.drawn(target(),1,scene,AbyssLodTransport.DrawPass.WORLD,8),"Rejected foreign callback cannot consume current ACK");}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void hiddenUploadCannotAdvanceWorkerLedger(GameTestHelper h) {
        var scene=new Object();var t=displayed(scene);
        h.assertTrue(!t.drawn(target(),1,scene,AbyssLodTransport.DrawPass.HIDDEN_UPLOAD,8),"Hidden-origin GPU callback is readiness, not WORLD presentation");
        h.assertTrue(t.drawn(target(),1,scene,AbyssLodTransport.DrawPass.WORLD,8),"Positive actual WORLD pass acknowledges exactly the displayed scene");h.succeed();
    }
    @GameTest(template="empty")
    public static void shadowPassCannotAdvanceWorkerLedger(GameTestHelper h) {
        var scene=new Object();var t=displayed(scene);
        h.assertTrue(!t.drawn(target(),1,scene,AbyssLodTransport.DrawPass.SHADOW,8),"Shadow callback never unlocks FAR or pins fine grids");h.succeed();
    }
    @GameTest(template="empty")
    public static void cancellationRetainsActualDisplayedSceneAndCapsTwo(GameTestHelper h) {
        var old=new Object();var t=displayed(old);var pending=new Object();t.request(target(),2);t.stage(target(),2,pending);
        h.assertTrue(t.size()==2,"Exactly displayed plus one private uploading replacement");boolean duplicate=false;
        try{t.stage(target(),2,new Object());}catch(IllegalStateException expected){duplicate=true;}
        h.assertTrue(duplicate&&t.size()==2,"Third scene refuses before replacing any owner");
        h.assertTrue(t.request(target(),3)==pending&&t.displayed()==old&&t.size()==1,"Canceled candidate retires alone; old scene remains");
        t.uploaded(2,pending);h.assertTrue(!t.ready(),"Late callback from canceled target cannot mark a future candidate ready");h.succeed();
    }
    @GameTest(template="empty")
    public static void emptyWorldPassAndDuplicateCannotAck(GameTestHelper h) {
        var scene=new Object();var t=displayed(scene);
        h.assertTrue(!t.drawn(target(),1,scene,AbyssLodTransport.DrawPass.WORLD,0),"All-AIR/empty draw does not certify a first visible surface");
        h.assertTrue(t.drawn(target(),1,scene,AbyssLodTransport.DrawPass.WORLD,1),"First positive WORLD acknowledgement succeeds");
        h.assertTrue(!t.drawn(target(),1,scene,AbyssLodTransport.DrawPass.WORLD,1),"Duplicate callback cannot commit ledger twice");h.succeed();
    }
}
