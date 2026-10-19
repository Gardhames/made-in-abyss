package dev.madeinabyss.forge.probe.deep;

import com.google.gson.JsonParser;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.client.compat.AbyssLodPipeline;
import dev.madeinabyss.forge.worldgen.*;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.LodBoxSkyLight;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real CPU coordinator and approved/saved source. No client Minecraft, DH API or GPU class loads. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodPipelineGameTests {
    private static final TerrainLodColumns.Bounds NEAR=new TerrainLodColumns.Bounds(-272,-352,400,-241,-337,431);
    private static final TerrainLodColumns.Bounds WORLD=new TerrainLodColumns.Bounds(-304,-352,384,-209,-337,447);
    private static final List<TerrainLodRefinement.Band> BANDS=List.of(new TerrainLodRefinement.Band(128,1));
    private record Fixture(GeneratedSkySource source,GeneratedSkySource oracle) { }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void actualNearFineProposalPrecedesFullWorldSourceAcquisition(GameTestHelper h)throws Exception {
        var f=fixture(h);var outsideNear=new AtomicInteger();
        TrailNetwork.SampleSource terrain=(x,y,z)->{if(x<NEAR.minX()||x>NEAR.maxXInclusive()||z<NEAR.minZ()||z>NEAR.maxZInclusive())outsideNear.incrementAndGet();
            return material(f.source(),x,y,z);};
        try(var pipeline=new AbyssLodPipeline()){
            pipeline.request(input(target(1),terrain,box->light(f.source(),box),List.of()));var proposal=await(pipeline);
            h.assertTrue(proposal.stage()==AbyssLodPipeline.Stage.NEAR_FINE,"Real worker must publish exact nearby surface before full background");
            h.assertTrue(outsideNear.get()==0,"Full-world source acquisition cannot precede first local fine proposal");
            h.assertTrue(proposal.fine().size()<=8&&proposal.fine().stream().anyMatch(g->!g.raw().boxes().isEmpty()&&g.step()<=2),
                    "Positive authoritative saved surface, not empty metadata, is in the bounded first fine prefix");
            h.assertTrue(!pipeline.acknowledge(proposal,false)&&!Boolean.TRUE.equals(pipeline.status().get("completed")),
                    "Worker completion/hidden upload never claims owner-drawn completion");
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void actualSavedGridsAndExact32LightMatchUntouchedFullStateSourceWhileUnknownRefuses(GameTestHelper h)throws Exception {
        var f=fixture(h);
        for(boolean surface:new boolean[]{false,true})try(var pipeline=new AbyssLodPipeline(surface)){
            pipeline.request(input(target(1),(x,y,z)->material(f.source(),x,y,z),
                    new dev.madeinabyss.forge.probe.client.compat.AbyssLodPhysicalLighting(f.source()),List.of()));
            var proposal=await(pipeline);int savedStates=0,water=0,air=0;
            h.assertTrue(!proposal.requestedComplete()&&!Boolean.TRUE.equals(pipeline.status().get("completed")),
                    "Initial bounded acquisition is not reported as full target completion");
            var checkedGrids=new HashSet<String>();boolean observed=false;
            for(int publication=0;publication<20;publication++){
                for(var grid:proposal.fine()){
                    var b=grid.bounds();if(publication==0)h.assertTrue(grid.step()<=2,"Initial exact source grids never masquerade as coarse4/16m");
                    // Only whole current face-page coverage certifies displayed RAW grids.
                    // Validate every ready grid, counting detailed coverage once.
                    boolean count=grid.step()<=2&&checkedGrids.add(grid.fingerprint());
                    for(int y=b.minY();y<=b.maxYInclusive();y++)for(int z=b.minZ();z<=b.maxZInclusive();z++)for(int x=b.minX();x<=b.maxXInclusive();x++){
                        int expected=f.oracle().stateIdUncached(x,y,z),actual=f.source().stateIdForDerivedGeometry(x,y,z);
                        h.assertTrue(actual==expected,"Every source voxel preserves whole saved cube including AIR/water");
                        if(count){if(actual==Block.getId(Blocks.WATER.defaultBlockState()))water++;
                            if(actual==Block.getId(Blocks.AIR.defaultBlockState()))air++;savedStates++;}
                    }
                }
                for(var box:proposal.boxes())h.assertTrue(box.light().equals(light(f.oracle(),box.box())),
                        "Every actual proposed box uses original32-ray/direct/full-state BLOCK scalar");
                if(surface){h.assertTrue(proposal.surfacePages()!=null&&proposal.surfacePages().faces()>0,"Actual surface pipeline emits positive prepared face pages");
                    for(var page:proposal.surfacePages().pages())for(var owner:page.prepared().owners()){var expected=light(f.oracle(),owner.box());
                        h.assertTrue(owner.sky()==expected.sky()&&owner.block()==expected.block(),"Exposed owners retain original bounds and exact full-state32 lighting");}}
                if(savedStates>0&&water>0&&air>0){observed=true;break;}
                h.assertTrue(pipeline.acknowledge(proposal,true),"Current positive CPU WORLD simulation advances partial ready-grid publication");
                proposal=await(pipeline);
            }
            h.assertTrue(observed,"Actual detailed ready source grids cumulatively exercise both WATER and authoritative AIR");
        }
        for(boolean surface:new boolean[]{false,true})try(var pipeline=new AbyssLodPipeline(surface)){
            var bad=new TerrainLodColumns.Bounds(-16,-1287,-16,-1,-1287,-1);
            var input=new AbyssLodPipeline.Input(target(2),new AbyssLodPipeline.Camera(-8,-1287,-8),bad,bad,BANDS,List.of(),
                    (x,y,z)->material(f.source(),x,y,z),box->light(f.source(),box),AbyssLodPipeline.Limits.defaults());
            pipeline.request(input);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<until&&String.valueOf(pipeline.status().get("error")).isEmpty()){pipeline.poll();Thread.sleep(2);}
            h.assertTrue(pipeline.poll().isEmpty()&&!String.valueOf(pipeline.status().get("error")).isEmpty(),
                    "Unknown actual source floor refuses whole candidate; no default AIR scene appears");
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void sameSourceNativeRetargetCoalescesWithoutRestartingInFlightGeometry(GameTestHelper h)throws Exception {
        var f=fixture(h);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        TrailNetwork.SampleSource terrain=(x,y,z)->{
            if(entered.getCount()>0){entered.countDown();try{release.await();}catch(InterruptedException cancel){interrupted.countDown();Thread.currentThread().interrupt();throw new CancellationException("Source work restarted");}}
            return material(f.source(),x,y,z);
        };
        try(var pipeline=new AbyssLodPipeline()){
            pipeline.request(input(target(1),terrain,box->light(f.source(),box),List.of()));
            h.assertTrue(entered.await(3,TimeUnit.SECONDS),"Actual source worker entered its captured geometry");
            var hole=new TerrainLodClipper.BoundsLong(-272,-352,400,-256,-336,416);
            var latest=target(2);pipeline.request(input(latest,terrain,box->light(f.source(),box),List.of(hole)));
            h.assertTrue(!interrupted.await(200,TimeUnit.MILLISECONDS),"Same-source retarget must preserve owned geometry work instead of cancel/restarting full acquisition");
            release.countDown();
            var proposal=await(pipeline);
            h.assertTrue(proposal.target().equals(latest),"Published scene composition belongs to latest native/request revision");
            for(var lit:proposal.boxes()){var b=lit.box();h.assertTrue(b.maxXExclusive()<=hole.minX()||b.minX()>=hole.maxXExclusive()
                    ||b.maxYExclusive()<=hole.minY()||b.minY()>=hole.maxYExclusive()||b.maxZExclusive()<=hole.minZ()||b.minZ()>=hole.maxZExclusive(),
                    "Retained raw geometry is clipped against actual latest native coverage");}
        }finally{release.countDown();}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void blockedFarSourceCannotDelaySecondPositiveFineWorldProposal(GameTestHelper h)throws Exception {
        var f=fixture(h);var local=new TerrainLodColumns.Bounds(-288,-352,384,-225,-337,447);
        var gate=new FarGate(WORLD);
        TrailNetwork.SampleSource terrain=(x,y,z)->{gate.sample(x,y,z);return material(f.source(),x,y,z);};
        try(var pipeline=new AbyssLodPipeline()){
            pipeline.request(windowInput(target(1),new AbyssLodPipeline.Camera(-269,-348,405,1,0,0),local,WORLD,terrain,
                    box->light(f.source(),box)));
            var first=await(pipeline);
            h.assertTrue(first.stage()==AbyssLodPipeline.Stage.NEAR_FINE&&gate.entered.getCount()==1,
                    "Initial positive K8 fine must precede any FULL FAR source work");
            h.assertTrue(pipeline.acknowledge(first,true),"First real WORLD promotion releases background acquisition");
            h.assertTrue(gate.entered.await(3,TimeUnit.SECONDS),"Full source acquisition actually entered its controlled slow sample");
            var second=awaitWithin(pipeline,2500);
            h.assertTrue(second.isPresent(),"A blocked FULL FAR source must not hold the second ready positive fine proposal");
            var next=second.orElseThrow();
            h.assertTrue(!next.boxes().isEmpty()&&next.fine().size()>first.fine().size()&&next.farStep()==0,
                    "Additional actual fine grids, not a duplicate prefix or incomplete FAR scene, are published while source is blocked");
            h.assertTrue(pipeline.acknowledge(next,true)&&count(pipeline,"acknowledgedSequence")==next.sequence(),
                    "Second current WORLD ACK progresses independently of unfinished FAR source");
            var replacement=new AbyssLodPipeline.Target("replacement-session",2,"replacement-source",1,3,1,3);
            pipeline.request(windowInput(replacement,new AbyssLodPipeline.Camera(-269,-348,405,1,0,0),local,WORLD,terrain,
                    box->light(f.source(),box)));
            h.assertTrue(gate.interrupted.await(3,TimeUnit.SECONDS),"A changed source/session cancels its owned background acquisition");
            h.assertTrue(await(pipeline).target().equals(replacement),"No old source result escapes into the replacement lease");
        }finally{gate.release.countDown();}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void pendingFarRetargetRetainsAcquisitionAndUnvisitedSampleReservation(GameTestHelper h)throws Exception {
        var f=fixture(h);var world=new TerrainLodColumns.Bounds(-1024,-352,-1024,1023,-337,1023);
        var local=new TerrainLodColumns.Bounds(-288,-352,384,-225,-337,447);var gate=new FarGate(world);
        TrailNetwork.SampleSource terrain=(x,y,z)->{gate.sample(x,y,z);return material(f.source(),x,y,z);};
        try(var pipeline=new AbyssLodPipeline()){
            pipeline.request(windowInput(target(1),new AbyssLodPipeline.Camera(-269,-348,405,1,0,0),local,world,terrain,
                    box->light(f.source(),box)));
            var first=await(pipeline);h.assertTrue(pipeline.acknowledge(first,true),"Positive initial WORLD ACK admitted");
            h.assertTrue(gate.entered.await(3,TimeUnit.SECONDS),"Pending full-source task reached its first original coarse sample");
            var shifted=new TerrainLodColumns.Bounds(-224,-352,384,-161,-337,447);var latest=target(2);
            pipeline.request(windowInput(latest,new AbyssLodPipeline.Camera(-213,-348,405,1,0,0),shifted,world,terrain,
                    box->light(f.source(),box)));
            long primary=new TerrainLodRefinement.Region(world,16).sampledVoxels();
            h.assertTrue(count(pipeline,"freshSampleReserve")>=primary,
                    "All unvisited FULL FAR samples remain reserved during a local-window recenter; charged.reset cannot erase them");
            var ready=awaitWithin(pipeline,3000);
            h.assertTrue(ready.isPresent()&&ready.orElseThrow().target().equals(latest)&&!ready.orElseThrow().boxes().isEmpty(),
                    "Same-source recenter prepares current positive fine without waiting for or restarting pending FAR");
            h.assertTrue(gate.starts.get()==1&&gate.interrupted.getCount()==1,
                    "The original background acquisition remains owned exactly once across camera/native retarget");
            h.assertTrue(count(pipeline,"freshSampleReserve")>=primary&&pipeline.acknowledge(ready.orElseThrow(),true),
                    "A successful recentered WORLD promotion retains the in-flight FAR reservation");
        }finally{gate.release.countDown();}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void farLightQuantumIsBoundedAndRunsAfterFreshRequestedFineLight(GameTestHelper h)throws Exception {
        var world=new TerrainLodColumns.Bounds(-1024,-352,-1024,1023,-337,1023);
        var local=new TerrainLodColumns.Bounds(-288,-352,384,-225,-337,447);
        TrailNetwork.SampleSource terrain=(x,y,z)->new TerrainSample.Known(y==-337?TerrainMaterial.ROCK:TerrainMaterial.AIR);
        var farCalls=new AtomicInteger();var fineCalls=new AtomicInteger();var order=new ConcurrentLinkedQueue<Boolean>();
        // A known roof supplies 16,384 genuine coarse boxes. This controlled scalar callback
        // tests production scheduling; the independent full-state/32-ray oracle above is unchanged.
        AbyssLodPipeline.BoxLighting lighting=box->{boolean far=box.maxXExclusive()-box.minX()==16&&box.maxZExclusive()-box.minZ()==16;
            if(far)farCalls.incrementAndGet();else if(box.maxXExclusive()-box.minX()<=2&&box.maxZExclusive()-box.minZ()<=2)fineCalls.incrementAndGet();
            order.add(far);return new AbyssLodPipeline.Light(7,0);};
        try(var pipeline=new AbyssLodPipeline()){
            pipeline.request(windowInput(target(1),new AbyssLodPipeline.Camera(-269,-348,405,1,0,0),local,world,terrain,lighting));
            var first=await(pipeline);farCalls.set(0);fineCalls.set(0);order.clear();
            h.assertTrue(pipeline.acknowledge(first,true),"First positive fine WORLD ACK admitted");
            boolean observed=false;
            for(int proposal=0;proposal<12&&!observed;proposal++){
                var next=await(pipeline);int far=farCalls.get();
                if(far>0){observed=true;
                    h.assertTrue(far<=1024,"One FAR-light turn cannot monopolize workers with the legacy 16,384-box batch");
                    h.assertTrue(fineCalls.get()==0||!Boolean.TRUE.equals(order.peek()),
                            "Fresh current requested fine light must run before lower-priority FAR misses in the same turn");
                }
                farCalls.set(0);fineCalls.set(0);order.clear();
                h.assertTrue(pipeline.acknowledge(next,true),"Every controlled scene is acknowledged on its current WORLD target");
            }
            h.assertTrue(observed,"The actual pipeline must eventually process FAR light, not starve it or merely change metadata");
        }
        h.succeed();
    }

    private static final class FarGate {
        final TerrainLodColumns.Bounds bounds;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        final AtomicInteger starts=new AtomicInteger();
        FarGate(TerrainLodColumns.Bounds bounds){this.bounds=bounds;}
        void sample(long x,long y,long z){
            if(x!=(long)bounds.minX()+7||y!=bounds.minY()||z!=(long)bounds.minZ()+7)return;
            starts.incrementAndGet();entered.countDown();
            try{release.await();}catch(InterruptedException canceled){interrupted.countDown();Thread.currentThread().interrupt();
                throw new CancellationException("Owned FAR source interrupted");}
        }
    }
    private static AbyssLodPipeline.Input windowInput(AbyssLodPipeline.Target target,AbyssLodPipeline.Camera camera,
            TerrainLodColumns.Bounds local,TerrainLodColumns.Bounds world,TrailNetwork.SampleSource source,AbyssLodPipeline.BoxLighting lighting){
        return new AbyssLodPipeline.Input(target,camera,local,world,BANDS,List.of(),source,lighting,AbyssLodPipeline.Limits.defaults());
    }
    private static long count(AbyssLodPipeline pipeline,String field){return ((Number)pipeline.status().getOrDefault(field,0L)).longValue();}
    private static Optional<AbyssLodPipeline.Proposal> awaitWithin(AbyssLodPipeline pipeline,long millis)throws Exception{
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(System.nanoTime()<until){var value=pipeline.poll();if(value.isPresent())return value;
            String error=String.valueOf(pipeline.status().get("error"));if(!error.isEmpty())throw new AssertionError("Unexpected CPU refusal: "+error);
            Thread.sleep(2);}
        return Optional.empty();
    }

    private static AbyssLodPipeline.Target target(long request){return new AbyssLodPipeline.Target("test-session",1,"actual-approved-saved",1,request,1,request);}
    private static AbyssLodPipeline.Input input(AbyssLodPipeline.Target target,TrailNetwork.SampleSource source,
            AbyssLodPipeline.BoxLighting light,List<TerrainLodClipper.BoundsLong> cuts){
        return new AbyssLodPipeline.Input(target,new AbyssLodPipeline.Camera(-269,-348,405,1,0,0),NEAR,WORLD,BANDS,cuts,source,light,AbyssLodPipeline.Limits.defaults());
    }
    private static AbyssLodPipeline.Proposal await(AbyssLodPipeline pipeline)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until){var p=pipeline.poll();if(p.isPresent())return p.get();
            var error=String.valueOf(pipeline.status().get("error"));if(!error.isEmpty())throw new AssertionError("Unexpected CPU source refusal: "+error);Thread.sleep(2);}
        throw new AssertionError("Bounded CPU proposal did not complete");
    }
    private static Fixture fixture(GameTestHelper h)throws Exception{
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        try(var in=AbyssLodPipelineGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json")){
            var json=JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            var profile=new GeneratedPreviewProfile(json,json.get("profileId").getAsString(),biomes);
            var saved=new HashMap<CubeKey,int[]>();int air=Block.getId(Blocks.AIR.defaultBlockState());
            for(int x=-19;x<=-14;x++)for(int z=24;z<=27;z++){int[] states=new int[4096];Arrays.fill(states,air);saved.put(new CubeKey(x,-22,z),states);}
            // The actual saved roof forces diffuse32 evaluation for the lower WATER/stone boxes;
            // an entirely open fixture would exercise only the direct15 early exit.
            for(int x=WORLD.minX();x<=WORLD.maxXInclusive();x++)for(int z=WORLD.minZ();z<=WORLD.maxZInclusive();z++)set(saved,x,-337,z,Blocks.STONE);
            for(int y=-352;y<=-345;y++)set(saved,-265,y,407,Blocks.WATER);
            for(int y=-352;y<=-342;y++)set(saved,-270,y,402,Blocks.STONE);
            set(saved,-264,-345,408,Blocks.GLOWSTONE);
            return new Fixture(new GeneratedSkySource(profile,saved),new GeneratedSkySource(profile.generator(),saved));
        }
    }
    private static void set(Map<CubeKey,int[]> saved,int x,int y,int z,net.minecraft.world.level.block.Block block){
        saved.get(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)))[(y&15)<<8|(z&15)<<4|(x&15)]=Block.getId(block.defaultBlockState());
    }
    private static TerrainSample material(GeneratedSkySource source,int x,int y,int z){
        int id=source.stateIdForDerivedGeometry(x,y,z);var state=Block.stateById(id);
        if(state.isAir())return new TerrainSample.Known(TerrainMaterial.AIR);
        if(!state.getFluidState().isEmpty())return new TerrainSample.Known(TerrainMaterial.WATER);
        var material=WildernessBlockStates.material(state);if(material==null)material=CaveInteriorBlockStates.material(state);
        if(material==null)material=AlexEcologyBlockStates.material(state);
        if(material==null){if(state.is(Blocks.STONE)||state.is(Blocks.GLOWSTONE))material=TerrainMaterial.ROCK;
            else throw new IllegalArgumentException("Unsupported saved palette state "+state);}
        return new TerrainSample.Known(material);
    }
    private static AbyssLodPipeline.Light light(GeneratedSkySource source,TerrainLodColumns.Box box){
        int lower=source.minKnownYForBox(Math.toIntExact(box.minX()-1),Math.toIntExact(box.minZ()-1),Math.toIntExact(box.maxXExclusive()),Math.toIntExact(box.maxZExclusive()));
        // The independent raw-generator Source has no profile certificates/projection;
        // its public32-spacing ambient path ultimately samples the untouched full-state oracle.
        int sky=LodBoxSkyLight.maximum(box,lower,source::opacityFast,source::directSkyFast,
                (x,y,z)->source.opacityFast(x,y,z)>=15?0:source.ambientSky(x,y,z,32));
        int x=Math.toIntExact(box.minX()+(box.maxXExclusive()-box.minX()-1)/2),y=Math.toIntExact(box.minY()+(box.maxYExclusive()-box.minY()-1)/2),
            z=Math.toIntExact(box.minZ()+(box.maxZExclusive()-box.minZ()-1)/2);
        return new AbyssLodPipeline.Light(sky,source.emission(source.stateIdUncached(x,y,z)));
    }
}
