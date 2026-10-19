package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Actual adapter lease primitive and transport; no optional DH or client class loading. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodGroupLeaseGameTests {
    private record CollisionBox(int coordinate) { @Override public int hashCode(){return 11;} }
    private static AbyssLodGroupLeases.Scope scope(){return new AbyssLodGroupLeases.Scope("session",7,"source",19,2);}
    private static AbyssLodGroupLeases.Payload<CollisionBox> payload(){return new AbyssLodGroupLeases.Payload<>("cell/0",List.of(new CollisionBox(-16),new CollisionBox(0)),9,3);}
    private static AbyssLodPipeline.Target target(long request){return new AbyssLodPipeline.Target("session",7,"source",19,3,2,request);}

    @GameTest(template="empty")
    public static void identicalOrderedPhysicalPayloadReusesActualWarmGroup(GameTestHelper h){
        var pool=new AbyssLodGroupLeases<CollisionBox,Object>(8);var created=new AtomicInteger();var closed=new AtomicInteger();
        var a=pool.acquire(scope(),payload(),()->{created.incrementAndGet();return new Object();},g->closed.incrementAndGet());a.markWarm();
        var b=pool.acquire(scope(),payload(),()->{created.incrementAndGet();return new Object();},g->closed.incrementAndGet());
        boolean reused=a.value()==b.value()&&created.get()==1&&b.warm();b.close();a.close();
        h.assertTrue(reused,"Same exact ordered boxes and physical SKY/BLOCK must reuse the uploaded group, without another factory/upload");
        h.assertTrue(closed.get()==1&&pool.size()==0,"One actual group closes exactly once after both owners retire");h.succeed();
    }

    @GameTest(template="empty")
    public static void canceledCandidateCannotCloseDisplayedSharedGroup(GameTestHelper h){
        var pool=new AbyssLodGroupLeases<CollisionBox,Object>(8);var closed=new AtomicInteger();
        var displayed=pool.acquire(scope(),payload(),Object::new,g->closed.incrementAndGet());displayed.markWarm();
        var candidate=pool.acquire(scope(),payload(),Object::new,g->closed.incrementAndGet());candidate.close();candidate.close();
        boolean survived=closed.get()==0&&displayed.warm()&&pool.size()==1;displayed.close();
        h.assertTrue(survived,"Canceled second scene must release only its reference, never close/hide an actual displayed group");
        h.assertTrue(closed.get()==1,"Displayed last release closes exactly once");h.succeed();
    }

    @GameTest(template="empty")
    public static void digestCollisionOrderLightAndAuthorityCannotAlias(GameTestHelper h){
        var pool=new AbyssLodGroupLeases<CollisionBox,Object>(32);var closed=new AtomicInteger();
        var base=pool.acquire(scope(),payload(),Object::new,g->closed.incrementAndGet());
        var variants=List.of(new AbyssLodGroupLeases.Payload<>("cell/0",List.of(new CollisionBox(99),new CollisionBox(0)),9,3),
            new AbyssLodGroupLeases.Payload<>("cell/0",List.of(new CollisionBox(0),new CollisionBox(-16)),9,3),
            new AbyssLodGroupLeases.Payload<>("cell/0",payload().boxes(),10,3),new AbyssLodGroupLeases.Payload<>("cell/0",payload().boxes(),9,4),
            new AbyssLodGroupLeases.Payload<>("cell/1",payload().boxes(),9,3));
        h.assertTrue(variants.get(0).boxes().hashCode()==payload().boxes().hashCode(),"Fixture really collides; a digest cannot prove geometry equality");
        for(var p:variants){var other=pool.acquire(scope(),p,Object::new,g->closed.incrementAndGet());h.assertTrue(other.value()!=base.value(),"Different exact geometry/order/light/partition must create independent group");other.close();}
        for(var s:List.of(new AbyssLodGroupLeases.Scope("other",7,"source",19,2),new AbyssLodGroupLeases.Scope("session",8,"source",19,2),
            new AbyssLodGroupLeases.Scope("session",7,"other",19,2),new AbyssLodGroupLeases.Scope("session",7,"source",20,2),new AbyssLodGroupLeases.Scope("session",7,"source",19,3))){
            var other=pool.acquire(s,payload(),Object::new,g->closed.incrementAndGet());h.assertTrue(other.value()!=base.value(),"Session/lifetime/source/revision/resource changes invalidate GPU leases");other.close();}
        base.close();h.assertTrue(pool.size()==0&&closed.get()==11,"Every independent owner retires once");h.succeed();
    }

    @GameTest(template="empty")
    public static void equivalentContentRetargetNeedsNewCurrentWorldAckWithoutReplacement(GameTestHelper h){
        var pool=new AbyssLodGroupLeases<CollisionBox,Object>(8);var created=new AtomicInteger();var t=new AbyssLodTransport<AbyssLodPipeline.Target,Object>();
        var old=pool.acquire(scope(),payload(),()->{created.incrementAndGet();return new Object();},g->{});old.markWarm();
        var oldScene=new Object();t.request(target(40),1);t.stage(target(40),1,oldScene);t.uploaded(1,oldScene);t.promote();
        h.assertTrue(t.drawn(target(40),1,oldScene,AbyssLodTransport.DrawPass.WORLD,2),"Old target was genuinely displayed");
        var next=pool.acquire(scope(),payload(),()->{created.incrementAndGet();return new Object();},g->{});var newScene=new Object();
        t.request(target(41),2);t.stage(target(41),2,newScene);h.assertTrue(t.size()==2,"Retarget still has only two logical scene owners");
        t.uploaded(2,newScene);h.assertTrue(t.promote()==oldScene,"Atomic promotion releases old logical scene only");old.close();
        h.assertTrue(!t.drawn(target(40),1,oldScene,AbyssLodTransport.DrawPass.WORLD,2),"Old group's callback cannot ACK the new target");
        h.assertTrue(!t.drawn(target(41),2,newScene,AbyssLodTransport.DrawPass.HIDDEN_UPLOAD,2),"Reused upload readiness is never WORLD ACK");
        h.assertTrue(t.drawn(target(41),2,newScene,AbyssLodTransport.DrawPass.WORLD,2),"Actual current main WORLD pass must recertify retarget");
        boolean reused=created.get()==1&&next.value()==old.value()&&next.warm();next.close();t.clear();
        h.assertTrue(reused,"Equivalent-content retarget must recertify metadata using the existing GPU group, without replacement/upload");h.succeed();
    }

    @GameTest(template="empty")
    public static void localInsertionKeepsUnaffectedActualSpatialPayloads(GameTestHelper h){
        var light=new AbyssLodPipeline.Light(9,3);var material=dev.madeinabyss.kernel.terrain.TerrainMaterial.ROCK;
        var a=new dev.madeinabyss.kernel.lod.TerrainLodColumns.Box(-600,-257,0,-599,-256,1,material);
        var b=new dev.madeinabyss.kernel.lod.TerrainLodColumns.Box(0,-1,0,1,0,1,material);
        var c=new dev.madeinabyss.kernel.lod.TerrainLodColumns.Box(1024,256,0,1025,257,1,material);
        var inserted=new dev.madeinabyss.kernel.lod.TerrainLodColumns.Box(2,-1,0,3,0,1,material);
        var before=AbyssLodGroupPartition.parts(List.of(new AbyssLodPipeline.LitBox(a,light),new AbyssLodPipeline.LitBox(b,light),new AbyssLodPipeline.LitBox(c,light)));
        var after=AbyssLodGroupPartition.parts(List.of(new AbyssLodPipeline.LitBox(a,light),new AbyssLodPipeline.LitBox(b,light),new AbyssLodPipeline.LitBox(inserted,light),new AbyssLodPipeline.LitBox(c,light)));
        h.assertTrue(after.stream().flatMap(p->p.boxes().stream()).toList().size()==4&&after.stream().allMatch(p->p.boxes().size()<=4096),"Partition retains every original exact box and physical scalar within API cap");
        h.assertTrue(before.stream().filter(after::contains).count()==2,"Local insert must preserve two unaffected spatial GPU payloads at signed cell boundaries");h.succeed();
    }

    @GameTest(template="empty")
    public static void groupCapRefusesBeforeFactoryAndKeepsDisplayedOwner(GameTestHelper h){
        var pool=new AbyssLodGroupLeases<CollisionBox,Object>(1);var created=new AtomicInteger();var closed=new AtomicInteger();
        var first=pool.acquire(scope(),payload(),()->{created.incrementAndGet();return new Object();},g->closed.incrementAndGet());boolean refused=false;
        try{pool.acquire(scope(),new AbyssLodGroupLeases.Payload<>("other",payload().boxes(),9,3),()->{created.incrementAndGet();return new Object();},g->closed.incrementAndGet());}
        catch(IllegalStateException expected){refused=true;}
        h.assertTrue(refused&&created.get()==1&&closed.get()==0&&pool.size()==1,"Cap must reject before any new GPU factory or displayed release");first.close();h.succeed();
    }
}
