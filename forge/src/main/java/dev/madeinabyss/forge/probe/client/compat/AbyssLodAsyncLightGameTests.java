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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;

/** Draft CPU coordinator fixture only; no native GPU WORLD presentation claim. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodAsyncLightGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void unrelatedFarLightDebtDoesNotBlockNewLocalFineCohorts(GameTestHelper h)throws Exception{
        var release=new CountDownLatch(1);var entered=new CountDownLatch(1);var enabled=new java.util.concurrent.atomic.AtomicBoolean();
        var near=new TerrainLodColumns.Bounds(240,0,0,271,15,31);
        var world=new TerrainLodColumns.Bounds(-1024,0,-1024,1023,15,1023);
        AbyssLodPipeline.BoxLighting lighting=box->{if(enabled.get()&&box.minX()<-128){entered.countDown();
            try{release.await();}catch(InterruptedException canceled){Thread.currentThread().interrupt();throw new CancellationException("Controlled background owner canceled");}}
            return new AbyssLodPipeline.Light(7,0);};
        dev.madeinabyss.kernel.terrain.trail.TrailNetwork.SampleSource terrain=
                (x,y,z)->new TerrainSample.Known(y==15?TerrainMaterial.ROCK:TerrainMaterial.AIR);
        var bands=List.of(new TerrainLodRefinement.Band(128,1));var limits=AbyssLodPipeline.Limits.defaults();
        var target=new AbyssLodPipeline.Target("local-debt-fixture",1,"roof-cohort",1,1,1,1);
        var movedTarget=new AbyssLodPipeline.Target("local-debt-fixture",1,"roof-cohort",1,1,1,2);
        var movedInput=new AbyssLodPipeline.Input(movedTarget,new AbyssLodPipeline.Camera(768,8,8,0,0,1),
                new TerrainLodColumns.Bounds(752,0,0,783,15,31),world,bands,List.of(),terrain,lighting,limits);
        try(var pipeline=new AbyssLodPipeline(true)){
            pipeline.request(new AbyssLodPipeline.Input(target,new AbyssLodPipeline.Camera(256,8,8,0,0,1),near,world,
                bands,List.of(),terrain,lighting,limits));
            AbyssLodPipeline.Proposal first=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<until){var p=pipeline.poll();if(p.isPresent()){first=p.orElseThrow();break;}Thread.sleep(2);}
            h.assertTrue(first!=null,"Nearest exact positive page is staged before controlled background debt");enabled.set(true);
            h.assertTrue(pipeline.acknowledge(first,true),"Initial current positive CPU WORLD simulation accepted");
            int atBackground=-1;boolean progressed=false,newAreaFinePublished=false;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<until){var next=pipeline.poll();if(next.isPresent()){
                    var proposal=next.orElseThrow();h.assertTrue(pipeline.acknowledge(proposal,true),"Every intermediate page still requires current positive CPU WORLD simulation");
                    if(proposal.target().equals(movedTarget))for(var grid:proposal.fine())
                        if(grid.bounds().minX()>=640&&grid.bounds().maxXInclusive()<896)newAreaFinePublished=true;
                }
                var status=pipeline.status();h.assertTrue(String.valueOf(status.get("error")).isEmpty(),"No hidden CPU refusal in local/background cohort fixture");
                int missing=((Number)status.getOrDefault("surfaceLightRemaining",0)).intValue();
                int prepared=((Number)status.getOrDefault("preparedFineJobs",0)).intValue();
                if(entered.getCount()==0&&missing>2048){if(atBackground<0){
                        // The whole initial128m band can fit in one large source quantum. Ask
                        // for a disjoint positive-X band only AFTER the FAR debt is blocked.
                        // Same source/lighting/world authority retains that exact background debt.
                        atBackground=prepared;pipeline.request(movedInput);until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    }else if(prepared>=atBackground+64&&newAreaFinePublished){progressed=true;break;}}
                Thread.sleep(2);
            }
            h.assertTrue(atBackground>=0,"Fixture really has more than2048 unresolved independent FAR owners");
            h.assertTrue(progressed,"New local fine source cohorts progress while unrelated global FAR light remains blocked: atBackground="+atBackground+" status="+pipeline.status());
            h.assertTrue(newAreaFinePublished,"Fresh disjoint local fine grids publish under the current retarget, not merely an old cumulative source counter");
            h.assertTrue(((Number)pipeline.status().get("surfaceLightRemaining")).intValue()>2048,"Global exact-light debt is still reported and never falsely COMPLETE");
            h.assertTrue(!Boolean.TRUE.equals(pipeline.status().get("completed")),"Unresolved original background light prevents terminal completion");
        }finally{release.countDown();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void unrelatedPageLightRunsBeforeFirstProposalWorldAcknowledgement(GameTestHelper h)throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        var local=new TerrainLodColumns.Bounds(-32,0,-32,31,15,31);
        var world=new TerrainLodColumns.Bounds(-512,0,-512,511,15,511);
        AbyssLodPipeline.BoxLighting lighting=box->{
            if(box.minX()<0){entered.countDown();try{release.await();}catch(InterruptedException cancel){interrupted.countDown();Thread.currentThread().interrupt();throw new CancellationException("Owned asynchronous page light canceled");}}
            return new AbyssLodPipeline.Light(7,0);
        };
        var target=new AbyssLodPipeline.Target("async-fixture",1,"roof",1,1,1,1);
        try(var pipeline=new AbyssLodPipeline(true)){
            var input=new AbyssLodPipeline.Input(target,new AbyssLodPipeline.Camera(8,8,8,0,0,1),local,world,
                List.of(new TerrainLodRefinement.Band(128,1)),List.of(),
                (x,y,z)->new TerrainSample.Known(y==15?TerrainMaterial.ROCK:TerrainMaterial.AIR),lighting,AbyssLodPipeline.Limits.defaults());
            pipeline.request(input);AbyssLodPipeline.Proposal first=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<until){var proposal=pipeline.poll();if(proposal.isPresent()){first=proposal.orElseThrow();break;}
                h.assertTrue(String.valueOf(pipeline.status().get("error")).isEmpty(),"No hidden CPU refusal while unrelated page is blocked");Thread.sleep(2);}
            h.assertTrue(first!=null&&first.surfacePages()!=null&&first.surfacePages().faces()>0,"Positive nearest page publishes independently of the blocked negative-X page");
            h.assertTrue(entered.await(3,TimeUnit.SECONDS),"Unrelated page task really runs while the first positive proposal is still awaiting WORLD ACK");
            h.assertTrue(((Number)pipeline.status().get("acknowledgedSequence")).longValue()==0,"The test deliberately withholds the first WORLD acknowledgement");
            h.assertTrue(pipeline.poll().orElseThrow()==first,"Completion must not replace an unacknowledged staged proposal");
            h.assertTrue(!pipeline.acknowledge(first,false),"Hidden upload cannot consume the staged positive proposal");
            h.assertTrue(pipeline.acknowledge(first,true),"Only simulated positive current WORLD presentation releases the staged proposal");
            pipeline.close();h.assertTrue(interrupted.await(3,TimeUnit.SECONDS),"Close cancels owned independent page work even after its producer coordinator returned");
        }finally{release.countDown();}
        h.succeed();
    }
}
