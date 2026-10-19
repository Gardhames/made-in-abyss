package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.lod.TerrainLodRefinement;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual CPU pipeline and transport simulation; this does not certify a native GPU WORLD draw. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodPagePublicationGameTests {
    private static final TerrainLodColumns.Bounds NEAR=new TerrainLodColumns.Bounds(-16,0,-16,15,15,15);
    private static final TerrainLodColumns.Bounds WORLD=new TerrainLodColumns.Bounds(-512,0,-512,1023,15,511);
    private static final List<TerrainLodRefinement.Band> BANDS=List.of(new TerrainLodRefinement.Band(128,1));
    private static final class LightGate {
        final AtomicBoolean enabled=new AtomicBoolean();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        AbyssLodPipeline.Light light(TerrainLodColumns.Box box) {
            if(enabled.get()&&box.minX()>=512){entered.countDown();
                try{release.await();}catch(InterruptedException canceled){interrupted.countDown();Thread.currentThread().interrupt();
                    throw new CancellationException("Unrelated distant physical scalar canceled");}}
            return new AbyssLodPipeline.Light(7,0);
        }
    }
    private static AbyssLodPipeline.Target target(String source,long lifetime,long revision){
        return new AbyssLodPipeline.Target("page-publication-test",lifetime,source,revision,1,1,revision);
    }
    private static AbyssLodPipeline.Input input(AbyssLodPipeline.Target target,LightGate gate){
        return new AbyssLodPipeline.Input(target,new AbyssLodPipeline.Camera(0,8,0,0,0,1),NEAR,WORLD,BANDS,List.of(),
            (x,y,z)->new TerrainSample.Known(y==15?TerrainMaterial.ROCK:TerrainMaterial.AIR),gate::light,AbyssLodPipeline.Limits.defaults());
    }
    private static AbyssLodPipeline.Proposal await(AbyssLodPipeline pipeline,long millis)throws Exception{
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(System.nanoTime()<until){var result=pipeline.poll();if(result.isPresent())return result.orElseThrow();
            String error=String.valueOf(pipeline.status().get("error"));if(!error.isEmpty())throw new AssertionError("Unexpected CPU refusal: "+error);
            Thread.sleep(2);}
        throw new AssertionError("A ready positive page remained behind unrelated whole-scene light");
    }
    private static boolean fullBackgroundReady(AbyssLodPipeline.Proposal proposal)throws Exception{
        try{return (Boolean)proposal.getClass().getMethod("fullBackgroundReady").invoke(proposal);}
        catch(NoSuchMethodException beforeImplementation){return true;}
    }
    private static void assertFixtureOwners(GameTestHelper h,AbyssLodPipeline.Proposal proposal){
        h.assertTrue(proposal.surfacePages()!=null&&proposal.surfacePages().faces()>0,"Publication contains actual positive prepared faces");
        for(var page:proposal.surfacePages().pages())for(var owner:page.prepared().owners())
            h.assertTrue(owner.sky()==7&&owner.block()==0&&owner.box().material()==TerrainMaterial.ROCK,
                "Every published original owner has the exact controlled scalar; no default light placeholder");
    }
    private static void simulateWorldAck(GameTestHelper h,AbyssLodPipeline pipeline,AbyssLodPipeline.Proposal proposal){
        var transport=new AbyssLodTransport<AbyssLodPipeline.Target,AbyssLodPipeline.Proposal>();
        transport.request(proposal.target(),proposal.sequence());transport.stage(proposal.target(),proposal.sequence(),proposal);
        transport.uploaded(proposal.sequence(),proposal);transport.promote();int faces=proposal.surfacePages().faces();
        h.assertTrue(!transport.drawn(proposal.target(),proposal.sequence(),proposal,AbyssLodTransport.DrawPass.HIDDEN_UPLOAD,faces)
            &&!transport.drawn(proposal.target(),proposal.sequence(),proposal,AbyssLodTransport.DrawPass.SHADOW,faces),
            "Hidden and shadow transport simulations do not advance WORLD proof");
        h.assertTrue(transport.drawn(proposal.target(),proposal.sequence(),proposal,AbyssLodTransport.DrawPass.WORLD,faces)
            &&pipeline.acknowledge(proposal,true),"Positive current WORLD transport simulation admits the actual worker proposal");
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void actualCpuPartialPagesPrecedeUnrelatedWholeLightAndCancelOnSourceChange(GameTestHelper h)throws Exception{
        var gate=new LightGate();var initialTarget=target("roof-original",1,1);
        try(var pipeline=new AbyssLodPipeline(true)){
            pipeline.request(input(initialTarget,gate));var first=await(pipeline,10000);
            h.assertTrue(first.stage()==AbyssLodPipeline.Stage.NEAR_FINE,"Initial bounded local positive pages precede background publication");
            assertFixtureOwners(h,first);gate.enabled.set(true);simulateWorldAck(h,pipeline,first);
            AbyssLodPipeline.Proposal partial=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<until){var next=await(pipeline,3000);assertFixtureOwners(h,next);
                if(next.farStep()>0&&next.stage().name().equals("PARTIAL_PAGES")){
                    h.assertTrue(((Number)pipeline.status().getOrDefault("surfaceLightRemaining",0)).intValue()>0,
                        "Positive background page is published while unrelated physical owner light remains");
                    h.assertTrue(!next.requestedComplete()&&!fullBackgroundReady(next),
                        "Partial positive pages cannot stamp full-background or terminal completion");
                    partial=next;simulateWorldAck(h,pipeline,next);break;
                }
                simulateWorldAck(h,pipeline,next);
            }
            h.assertTrue(partial!=null,"Actual coordinator emits PARTIAL_PAGES before whole scene light is complete");
            // Continue the real worker until the controlled distant scalar actually blocks.
            long gatedUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(gate.entered.getCount()>0&&System.nanoTime()<gatedUntil){var next=pipeline.poll();
                if(next.isPresent()){assertFixtureOwners(h,next.orElseThrow());simulateWorldAck(h,pipeline,next.orElseThrow());}Thread.sleep(2);}
            h.assertTrue(gate.entered.getCount()==0,"Unrelated exact scalar really reached the interruptible slow operation");
            var replacement=target("roof-replacement",2,2);gate.enabled.set(false);pipeline.request(input(replacement,gate));
            h.assertTrue(gate.interrupted.await(3,TimeUnit.SECONDS),"Changed source/lifetime cancels the old owned scalar worker");
            h.assertTrue(!pipeline.acknowledge(partial,true),"Canceled old-source proposal cannot advance replacement ledger");
            var current=await(pipeline,10000);h.assertTrue(current.target().equals(replacement),"No foreign source page escapes the replacement generation");
            assertFixtureOwners(h,current);simulateWorldAck(h,pipeline,current);
        }finally{gate.release.countDown();}
        h.succeed();
    }
}
